package com.scenaristo.camera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
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

    private val jpeg = PreviewJpegSource()
    private lateinit var tap: PreviewTapProcessor
    private lateinit var camera: ManualSession
    private lateinit var session: Session
    private lateinit var server: ControlServer

    private var recording: Recording? = null
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
        tap = PreviewTapProcessor(onFrame = jpeg::accept)
        camera = ManualSession(DEFAULT_REQUEST, tap = tap)
        server = ControlServer(session = session, frames = PreviewFrames { jpeg.latest() })

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

    private suspend fun bindCamera() {
        camera.preview.setSurfaceProvider { _surfaceRequest.value = it }
        val provider = ProcessCameraProvider.awaitInstance(this)
        runCatching {
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, camera.sessionConfig)
        }.onFailure { updateNotification("Camera unavailable: ${it.message}") }
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

    private fun thermalOf(status: Int): ThermalState = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
        PowerManager.THERMAL_STATUS_LIGHT, PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.FAIR
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SERIOUS
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

    companion object {
        private const val CHANNEL_ID = "capture"
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
