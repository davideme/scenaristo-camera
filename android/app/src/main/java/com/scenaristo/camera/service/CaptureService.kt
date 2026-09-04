package com.scenaristo.camera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.os.StatFs
import androidx.camera.core.CameraSelector
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleService
import com.scenaristo.camera.MainActivity
import com.scenaristo.camera.R
import com.scenaristo.camera.capture.CodecReport
import com.scenaristo.camera.capture.ExposureController
import com.scenaristo.camera.capture.LensSweepRunner
import com.scenaristo.camera.capture.ManualControls
import com.scenaristo.camera.capture.ManualSession
import com.scenaristo.camera.capture.PreviewJpegSource
import com.scenaristo.camera.capture.PreviewTapProcessor
import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.protocol.CaptureSettings
import com.scenaristo.camera.domain.protocol.DeviceStatus
import com.scenaristo.camera.domain.protocol.RecordingState
import com.scenaristo.camera.domain.protocol.Session
import com.scenaristo.camera.domain.protocol.ThermalState
import com.scenaristo.camera.domain.protocol.State as ProtocolState
import com.scenaristo.camera.server.ControlServer
import com.scenaristo.camera.server.LocalAddress
import com.scenaristo.camera.server.PreviewFrames
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Capture and the web server, in one foreground service (ADR-0003).
 *
 * The activity is a thin client of this: it renders the [surfaceRequest] and
 * nothing else crosses the boundary. That is what lets a take survive the screen
 * locking, an incoming call, or the activity being destroyed and recreated —
 * every one of which killed a recording while this lived in the activity, as
 * Phase 0's soak found out the hard way (#23).
 *
 * `LifecycleService` is the `LifecycleOwner` the CameraX use cases bind to, so
 * there is no hand-written lifecycle registry to get wrong.
 */
class CaptureService : LifecycleService() {

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        val service: CaptureService get() = this@CaptureService
    }

    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)

    /** The only thing the activity needs: something to draw the viewfinder into. */
    val surfaceRequest: StateFlow<SurfaceRequest?> = _surfaceRequest.asStateFlow()

    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    /** #21: what the UHD profile picks against what the device can encode. */
    private val _codecs = MutableStateFlow<String?>(null)
    val codecs: StateFlow<String?> = _codecs.asStateFlow()

    /** #20: the per-lens key echo, once the sweep has run. */
    private val _lensSweep = MutableStateFlow<String?>(null)
    val lensSweep: StateFlow<String?> = _lensSweep.asStateFlow()

    /** Camera2 id of the bound back camera, for the sweep's physical-id lookup. */
    private var backCameraId: String? = null

    private val jpeg = PreviewJpegSource()
    private lateinit var tap: PreviewTapProcessor
    private lateinit var camera: ManualSession
    private lateinit var session: Session
    private lateinit var server: ControlServer

    private var recording: Recording? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    /** Last rotation handed to the camera, so a brightness change is not one. */
    private var appliedRotation: Int? = null

    /** ADR-0005's loop, alive only once a camera is bound. */
    @Volatile
    private var exposure: ExposureController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Android 14 requires the type to be declared in the manifest and the
        // service to have been started while the app was visible; both are
        // ADR-0003's premise.
        //
        // ADR-0003 specifies `camera|microphone`. Only `camera` is declared
        // today, because the platform validates that RECORD_AUDIO is *granted*
        // before it will start a microphone service, and this app records
        // video-only until PRD 6.6's audio lands. Declaring it early crashed the
        // service outright.
        startForeground(NOTIFICATION_ID, notification("Starting…"))

        session = Session(startingState())
        jpeg.quality = 80
        tap = PreviewTapProcessor(onFrame = ::onTapFrame)
        camera = ManualSession(DEFAULT_REQUEST, tap = tap, onCaptureResult = ::onCaptureResult)
        server = ControlServer(session = session, frames = PreviewFrames { jpeg.latest() })

        followDisplayRotation()
        lifecycleScope.launch { bindCamera() }
        server.start()
        _url.value = LocalAddress.url()

        // The state document is only useful if it is true, so the phone's own
        // readings go in on a tick rather than being left as placeholders.
        lifecycleScope.launch { publishStatus() }
        // Commands arrive on the server's threads and change protocol state; the
        // recorder has to follow, or the browser's Record button is a light
        // switch wired to nothing.
        lifecycleScope.launch { followRecordingState() }
    }

    /**
     * Keeps the viewfinder and the recording pointed at the display's rotation.
     *
     * Capture lives in a service (ADR-0003), so the use cases are built once,
     * with no window and no configuration change to react to — and CameraX only
     * samples the display rotation when a use case is built. Nothing moved it
     * afterwards, so turning the phone left the preview drawn at the rotation the
     * service happened to start in.
     *
     * A `DisplayManager` listener rather than the activity's configuration
     * change, because the activity is a client that may not exist: PRD 6.9 has
     * the phone screen off during a take, and the recording still owes the file
     * an orientation (PRD 6.1).
     */
    private fun followDisplayRotation() {
        val displays = getSystemService(DisplayManager::class.java) ?: return
        applyDisplayRotation()
        displays.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) applyDisplayRotation()
                }
            }.also { displayListener = it },
            Handler(Looper.getMainLooper()),
        )
    }

    /**
     * `onDisplayChanged` is not a rotation callback — it also fires for
     * brightness, refresh rate and HDR changes, which on this device means
     * several times a second while the screen adapts. Comparing first keeps a
     * dimming screen from rebuilding the camera's transform.
     */
    private fun applyDisplayRotation() {
        val display = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        if (display.rotation == appliedRotation) return
        appliedRotation = display.rotation
        camera.setTargetRotation(display.rotation)
    }

    /**
     * One tapped frame, to the meter and then to the browser (ADR-0018).
     *
     * Order matters: [PreviewJpegSource.accept] closes the image, and the tap's
     * reader stalls on outstanding frames rather than dropping them, so the
     * meter reads first and the encoder disposes.
     */
    private fun onTapFrame(image: android.media.Image) {
        exposure?.onFrame(image, System.currentTimeMillis())
        jpeg.accept(image)
    }

    /** On a camera thread: the sensor reporting what it actually used (ADR-0005). */
    private fun onCaptureResult(result: android.hardware.camera2.TotalCaptureResult) {
        exposure?.onCaptureResult(result)
    }

    /**
     * Publishes the loop's shutter, ISO and warnings into the state document.
     *
     * Deliberately no broadcast of its own. ISO moves up to six times a second
     * (ADR-0005) and a snapshot per step would put the exposure loop's cadence on
     * the wire; [publishStatus]'s one-second tick carries it instead, which is
     * inside the 2 s ADR-0007 already guarantees.
     */
    private suspend fun publishExposure(controller: ExposureController) {
        controller.state.collect { exposure ->
            session.update(System.currentTimeMillis()) { state ->
                state.copy(
                    settings = state.settings.copy(
                        shutterHz = exposure.shutterHz,
                        iso = exposure.iso,
                    ),
                    // The loop owns both warnings that exist today (PRD 6.3).
                    // TOO_CLOSE_TO_LENS is PRD 6.5's and is not raised yet, so
                    // there is nothing here to merge with.
                    warnings = exposure.warnings.toList(),
                )
            }
        }
    }

    private suspend fun bindCamera() {
        camera.preview.setSurfaceProvider { _surfaceRequest.value = it }
        val provider = ProcessCameraProvider.awaitInstance(this)
        runCatching {
            val bound = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                camera.sessionConfig,
            )
            val lens = camera.capabilities(bound.cameraInfo)
            backCameraId = lens.cameraId
            camera.logicalCameraId = lens.cameraId
            startExposureLoop(bound)
            val report = CodecReport.markdown(CodecReport.of(lens.cameraId))
            _codecs.value = report
            // Logged as well as shown: #21's answer is a number to paste into an
            // ADR, and reading it off a screenshot means unlocking the phone.
            Log.i("CodecReport", report)
        }.onFailure { updateNotification("Camera unavailable: ${it.message}") }
    }

    /**
     * Hands the exposure loop the camera, once there is one (ADR-0005).
     *
     * After the bind rather than in `onCreate`, because the loop needs two things
     * only a bound camera has: the lens's own ISO range, and a `CameraControl` to
     * push each step at. A lens that declares no sensitivity range is left alone
     * and logged — ADR-0011 gates recording on `MANUAL_SENSOR`, which implies the
     * key, so a lens missing it is a device worth reporting rather than a default
     * worth inventing.
     */
    private fun startExposureLoop(bound: androidx.camera.core.Camera) {
        val isoRange = ManualControls.isoRange(bound.cameraInfo)
        if (isoRange == null) {
            Log.w(EXPOSURE_TAG, "lens reports no ISO range; leaving exposure at the bind-time keys")
            return
        }
        val controller = ExposureController(
            isoRange = isoRange,
            grid = session.state.settings.grid,
            cameraControl = bound.cameraControl,
        )
        exposure = controller
        controller.start()
        Log.i(EXPOSURE_TAG, "exposure loop running, ISO ${isoRange.min}..${isoRange.max}")
        lifecycleScope.launch { publishExposure(controller) }
    }

    /**
     * Runs #20's pinned lens sweep, then restores the app's own session.
     *
     * Triggered by an intent action rather than a button because the measurement
     * has to be startable with the phone locked: the result is a table for an
     * ADR, and reading it off the screen would mean unlocking the phone in front
     * of the camera it is measuring.
     */
    private suspend fun runLensSweep() {
        val logicalId = backCameraId ?: run {
            Log.w(SWEEP_TAG, "camera not bound yet; nothing to sweep")
            return
        }
        val provider = ProcessCameraProvider.awaitInstance(this)
        val text = runCatching {
            LensSweepRunner.run(this, provider, this, DEFAULT_REQUEST, logicalId)
        }.getOrElse { "Sweep failed: ${it.message}" }
        _lensSweep.value = text
        // Line by line: logcat truncates a single message past about 4 KB, and
        // this table is longer than that with four lenses.
        text.lineSequence().forEach { Log.i(SWEEP_TAG, it) }
        // The sweep unbound everything, including the preview the browser reads.
        bindCamera()
    }

    /**
     * Battery, thermal and free storage, once a second.
     *
     * ADR-0007 has the server broadcast at least every 2 s anyway, and these are
     * the values that make PRD 6.8's status line worth looking at — "84 minutes
     * left" tells a creator whether they can finish the take, where "14.2 GB"
     * does not.
     */
    private suspend fun publishStatus() {
        val battery = getSystemService(BatteryManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        while (true) {
            val free = File(getExternalFilesDir(null)?.path ?: filesDir.path).let { StatFs(it.path) }
                .let { it.availableBlocksLong * it.blockSizeLong }
            session.update(System.currentTimeMillis()) { state ->
                state.copy(
                    device = DeviceStatus(
                        batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                        charging = battery.isCharging,
                        thermal = thermalOf(power.currentThermalStatus),
                        storageMinutesRemaining = (free / BYTES_PER_MINUTE).toInt(),
                    ),
                )
            }
            server.broadcastSnapshot()
            updateNotification(describe())
            delay(1_000)
        }
    }

    /**
     * Makes the recorder follow the protocol state.
     *
     * The command handler deliberately does not touch the camera: ADR-0007's
     * `Session` is pure and shared with iOS, so the platform work happens here,
     * one step behind. The cost is that a start is visible in the state document
     * a moment before the file exists, which is why the notification and the
     * browser both read from the same state rather than from the recorder.
     */
    private suspend fun followRecordingState() {
        var wasRecording = false
        while (true) {
            val shouldRecord = session.state.recording.recording
            if (shouldRecord && !wasRecording) startRecording()
            if (!shouldRecord && wasRecording) stopRecording()
            wasRecording = shouldRecord
            delay(200)
        }
    }

    private fun startRecording() {
        val file = File(getExternalFilesDir(null), "take-${System.currentTimeMillis()}.mp4")
        recording = camera.recorder
            .prepareRecording(this, FileOutputOptions.Builder(file).build())
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    // A recording can end on its own -- storage, a file size cap,
                    // the source going away. The state document has to follow the
                    // truth rather than the request, or the browser shows a take
                    // that stopped minutes ago (#20).
                    session.update(System.currentTimeMillis()) {
                        it.copy(recording = RecordingState(recording = false, startedAtMs = null))
                    }
                }
            }
        acquireLocks()
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
        releaseLocksIfIdle()
    }

    /**
     * ADR-0003: a wake lock and a high-performance Wi-Fi lock while a browser is
     * connected or a recording is running.
     *
     * Doze throttles networking, and a remote that stops answering the moment the
     * phone is left alone is the failure PRD 6.8 exists to prevent.
     */
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "scenaristo:capture")
                .apply { acquire(MAX_LOCK_MS) }
        }
        if (wifiLock == null) {
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "scenaristo:server")
                .apply { acquire() }
        }
    }

    private fun releaseLocksIfIdle() {
        if (session.state.recording.recording || session.state.clients > 0) return
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    override fun onDestroy() {
        stopRecording()
        displayListener?.let { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it) }
        displayListener = null
        server.stop()
        tap.release()
        jpeg.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    // --- notification -------------------------------------------------------

    /**
     * ADR-0003 action item 3: recording state, elapsed time, connected clients,
     * and a stop action.
     *
     * With the screen off this notification is the only thing telling a user
     * their phone is recording, so it carries the same facts the browser sees.
     */
    private fun describe(): String {
        val state = session.state
        val elapsed = state.recording.startedAtMs
            ?.let { (System.currentTimeMillis() - it) / 1000 }
            ?.let { "%d:%02d".format(it / 60, it % 60) }
        val clients = if (state.clients > 0) " · ${state.clients} watching" else ""
        return when {
            elapsed != null -> "Recording $elapsed$clients"
            else -> "Ready${clients}${_url.value?.let { " · $it" } ?: ""}"
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Scenaristo Camera")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Capture",
            // Low: the notification is a status indicator, not an interruption.
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Android's seven thermal levels onto PRD 6.8's four.
     *
     * `MODERATE` maps to SERIOUS, not FAIR. At moderate the platform is already
     * throttling, and a browser reading "fair" while the device sheds
     * performance tells the user the opposite of what is happening — measured
     * during ADR-0003's screen-off comparison, where `dumpsys` reported SEVERE
     * against a state document still claiming FAIR.
     *
     * That leaves FAIR meaning `LIGHT` alone: warm, nothing given up yet.
     */
    private fun thermalOf(status: Int): ThermalState = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.FAIR
        PowerManager.THERMAL_STATUS_MODERATE, PowerManager.THERMAL_STATUS_SEVERE ->
            ThermalState.SERIOUS
        else -> ThermalState.CRITICAL
    }

    private fun startingState() = ProtocolState(
        settings = CaptureSettings(
            grid = GridFrequency.HZ_50,
            shutterHz = 50,
            iso = DEFAULT_REQUEST.sensitivity,
            whiteBalanceKelvin = 5600,
            lensId = "0",
        ),
        recording = RecordingState(recording = false),
        device = DeviceStatus(0, false, ThermalState.NOMINAL, 0),
        serverTimeMs = System.currentTimeMillis(),
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_LENS_SWEEP) lifecycleScope.launch { runLensSweep() }
        return result
    }

    companion object {
        private const val CHANNEL_ID = "capture"
        private const val EXPOSURE_TAG = "ExposureLoop"
        private const val SWEEP_TAG = "LensSweep"

        /** #20's sweep, startable over adb so the phone need not be unlocked. */
        const val ACTION_LENS_SWEEP = "com.scenaristo.camera.LENS_SWEEP"
        private const val NOTIFICATION_ID = 1
        private const val MAX_LOCK_MS = 4 * 60 * 60 * 1000L

        /** 1/50 s at ISO 100, 30 fps: the 50 Hz default from PRD 6.2's ladder. */
        private val DEFAULT_REQUEST = ManualControls.Request(
            exposureTimeNs = 20_000_000L,
            sensitivity = 100,
            frameDurationNs = 33_333_333L,
        )

        /**
         * 4K30 measured at 33.4 Mbit/s on the reference device (#21), so roughly
         * 250 MB a minute. A rough number that is right is more use to a creator
         * than a precise one that needs the encoder to be running.
         */
        private const val BYTES_PER_MINUTE = 250L * 1024 * 1024

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CaptureService::class.java))
        }
    }
}
