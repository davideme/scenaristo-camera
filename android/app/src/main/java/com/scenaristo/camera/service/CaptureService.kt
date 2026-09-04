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
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.camera.video.AudioStats
import com.scenaristo.camera.domain.protocol.AudioInput
import com.scenaristo.camera.domain.protocol.AudioState
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
import com.scenaristo.camera.domain.whitebalance.DEFAULT_KELVIN
import com.scenaristo.camera.domain.protocol.CaptureSettings
import com.scenaristo.camera.domain.protocol.Command
import com.scenaristo.camera.domain.protocol.CommandName
import com.scenaristo.camera.domain.protocol.DeviceStatus
import com.scenaristo.camera.domain.protocol.RecordingState
import com.scenaristo.camera.domain.protocol.SettingsPatch
import com.scenaristo.camera.domain.protocol.Session
import com.scenaristo.camera.domain.protocol.ThermalState
import com.scenaristo.camera.domain.protocol.State as ProtocolState
import com.scenaristo.camera.server.ControlServer
import com.scenaristo.camera.server.LocalAddress
import com.scenaristo.camera.server.PreviewFrames
import kotlinx.coroutines.Job
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

    private val _state = MutableStateFlow(startingState())

    /**
     * The same state document the browser sees (ADR-0007), for the phone's own
     * HUD. One source of truth, so the two surfaces cannot disagree.
     */
    val state: StateFlow<ProtocolState> = _state.asStateFlow()

    /** "HEVC (H.265)" or "H.264", before recording starts (PRD 6.7). */
    private val _codecLabel = MutableStateFlow("—")
    val codecLabel: StateFlow<String> = _codecLabel.asStateFlow()

    /**
     * The active lens in 35 mm-equivalent millimetres (PRD 6.5), or null when
     * the characteristics do not say. Null is not 0: the guidance is withheld
     * rather than guessed.
     */
    private val _lensMm = MutableStateFlow<Int?>(null)
    val lensMm: StateFlow<Int?> = _lensMm.asStateFlow()

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

    /** Pending ADR-0019 shutdown, cancelled if the activity comes back. */
    private var idleShutdown: Job? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    /** Last rotation handed to the camera, so a brightness change is not one. */
    private var appliedRotation: Int? = null

    /** Last white balance handed to the sensor, so the tick only pushes changes. */
    private var appliedKelvin: Int? = null

    /** ADR-0005's loop, alive only once a camera is bound. */
    @Volatile
    private var exposure: ExposureController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        idleShutdown?.cancel()
        idleShutdown = null
        return binder
    }

    /**
     * The activity has gone (ADR-0019).
     *
     * Returning true asks for [onRebind] rather than a fresh [onBind], which is
     * what lets a recreated activity cancel the shutdown instead of racing it.
     *
     * This is the *destroy* signal, not the *stop* signal, and the difference is
     * the whole decision: a screen turning off or a phone put face down must not
     * reach here, because PRD 6.8's flow is a phone left alone while the user
     * walks to a laptop, and ADR-0003 exists so a locked screen changes nothing.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        super.onUnbind(intent)
        scheduleIdleShutdown()
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        idleShutdown?.cancel()
        idleShutdown = null
    }

    /**
     * ADR-0019: stop once the user has left and neither a recording nor a remote
     * is using this.
     *
     * The delay is not politeness, it is correctness: an activity being recreated
     * unbinds and rebinds, and without a grace period that sequence would tear
     * the camera down and build it again for nothing.
     *
     * The two conditions are re-read *after* the delay rather than before,
     * because a remote can connect in the meantime -- which is exactly the case
     * this must not break.
     */
    private fun scheduleIdleShutdown() {
        idleShutdown?.cancel()
        idleShutdown = lifecycleScope.launch {
            delay(IDLE_SHUTDOWN_GRACE_MS)
            if (session.state.recording.recording || session.state.clients > 0) {
                Log.i(IDLE_TAG, "staying up: recording or a remote is still attached")
                return@launch
            }
            Log.i(IDLE_TAG, "no activity, no recording, no remotes; stopping")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
        startForeground(NOTIFICATION_ID, notification("Starting…"), foregroundTypes())

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
            _lensMm.value = ManualControls.equivalentFocalLength(bound.cameraInfo)
            startExposureLoop(bound)
            val codecReport = CodecReport.of(lens.cameraId)
            _codecLabel.value = when {
                codecReport.profileCodec?.contains("hevc") == true -> "HEVC (H.265)"
                codecReport.profileCodec?.contains("avc") == true -> "H.264"
                else -> "—"
            }
            val report = CodecReport.markdown(codecReport)
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
            awbMode = ManualControls.awbModeFor(session.state.settings.whiteBalanceKelvin),
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
            // PRD 6.6 asks the app to show which input is active, and a user
            // checking their microphone before a take is the whole point -- so
            // the input is published on the tick, not only once a recording is
            // producing statistics. The level is not: there is none to report
            // until the recorder is running, and `metering` says so.
            if (!session.state.recording.recording) {
                session.update(System.currentTimeMillis()) {
                    it.copy(audio = AudioState(input = activeAudioInput(), metering = false))
                }
            }
            // PRD 6.4: a preset chosen from the phone or a remote has to reach
            // the sensor. Watched on the tick rather than pushed from the
            // command handler, because ADR-0007 keeps that handler pure and
            // platform-free -- the camera work happens here, one step behind,
            // exactly as it does for recording.
            val kelvin = session.state.settings.whiteBalanceKelvin
            if (kelvin != appliedKelvin) {
                appliedKelvin = kelvin
                exposure?.onWhiteBalanceChanged(ManualControls.awbModeFor(kelvin))
            }
            server.broadcastSnapshot()
            _state.value = session.state
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
    /**
     * The record button on the phone (PRD 6.9, spec UI-4).
     *
     * Goes through the server's command path rather than touching `Session`
     * directly, so the phone is one more client of ADR-0007's single writer --
     * and a take started on the phone is acked, revisioned and broadcast exactly
     * like one started from a laptop.
     */
    fun toggleRecording() {
        val start = !session.state.recording.recording
        lifecycleScope.launch {
            server.applyLocal(
                Command(
                    id = "phone-" + System.currentTimeMillis(),
                    name = if (start) CommandName.RECORD_START else CommandName.RECORD_STOP,
                ),
            )
            _state.value = session.state
        }
    }

    /**
     * A white balance preset chosen on the phone (PRD 6.4, UI-4).
     *
     * Through the server's command path like the record button, so the phone is
     * one more client of ADR-0007's single writer -- which also means the
     * recording guard applies to it for free: `Session` nacks a settings change
     * while recording, so the phone cannot walk past its own locked control.
     */
    fun setWhiteBalance(kelvin: Int) {
        lifecycleScope.launch {
            server.applyLocal(
                Command(
                    id = "phone-wb-" + System.currentTimeMillis(),
                    name = CommandName.SETTINGS_SET,
                    args = SettingsPatch(whiteBalanceKelvin = kelvin),
                ),
            )
            _state.value = session.state
        }
    }

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

    /**
     * Which foreground service types this process may claim right now (ADR-0003).
     *
     * `microphone` is only claimed once `RECORD_AUDIO` is granted: Android 14
     * validates the permission at `startForeground`, and claiming the type
     * without it kills the service outright rather than degrading. So the
     * manifest declares both and this decides, which also means the app is
     * usable video-only before the user has answered the microphone prompt.
     */
    private fun foregroundTypes(): Int {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        return if (hasAudioPermission()) {
            types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            types
        }
    }

    /**
     * Turns audio on when we are allowed to (PRD 6.6).
     *
     * Two things are load-bearing here. `withAudioEnabled` returns a **new**
     * pending recording rather than mutating this one, so its result has to be
     * kept -- dropping it records silence while the code reads as though it
     * asked for sound, which is the exact failure this story exists to prevent.
     *
     * And the permission check is inline rather than delegated, because lint
     * only recognises a guard it can see in the same function; a helper that
     * returns the same boolean is invisible to it and the build fails on
     * `MissingPermission`. Better to satisfy the check honestly than to
     * suppress it.
     */
    private fun PendingRecording.withAudioIfPermitted(): PendingRecording =
        if (ContextCompat.checkSelfPermission(this@CaptureService, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            withAudioEnabled()
        } else {
            this
        }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        val file = File(getExternalFilesDir(null), "take-${System.currentTimeMillis()}.mp4")
        val withAudio = hasAudioPermission()
        // The permission can arrive after the service started, so the microphone
        // type is claimed here rather than only in onCreate.
        if (withAudio) startForeground(NOTIFICATION_ID, notification(describe()), foregroundTypes())
        val pending = camera.recorder.prepareRecording(this, FileOutputOptions.Builder(file).build())
        recording = pending
            .withAudioIfPermitted()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Status) publishAudio(event.recordingStats)
                if (event is VideoRecordEvent.Finalize) {
                    publishAudio(null)
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
     * The level meter (PRD 6.6), from the recorder's own statistics.
     *
     * CameraX reports amplitude with each `Status` event, roughly every 200 ms,
     * which is the 5 Hz meter ADR-0002 accepted against PRD 6.6's eventual
     * 10 Hz. Null means the recording ended: the meter stops, and says it has
     * stopped rather than reporting a convincing silence.
     *
     * **The meter only exists while recording**, because that is the only time
     * the `Recorder` produces statistics. PRD 6.6 wants it before the take too --
     * "so I do not discover a silent or clipped take afterwards" is a
     * before-the-take promise -- and that needs a second audio source the stock
     * Recorder does not offer. Recorded as a gap rather than papered over.
     */
    private fun publishAudio(stats: androidx.camera.video.RecordingStats?) {
        val audio = if (stats == null) {
            AudioState(input = activeAudioInput(), metering = false)
        } else {
            val amplitude = stats.audioStats.audioAmplitude
            AudioState(
                level = amplitude.coerceIn(0.0, 1.0),
                clipping = amplitude >= CLIPPING_LEVEL,
                input = activeAudioInput(),
                metering = stats.audioStats.audioState == AudioStats.AUDIO_STATE_ACTIVE,
            )
        }
        session.update(System.currentTimeMillis()) { it.copy(audio = audio) }
        _state.value = session.state
    }

    /**
     * Which microphone the system routed to (PRD 6.6).
     *
     * A heuristic, and deliberately so: ADR-0002 accepted **system default
     * routing** for the MVP, so the app does not choose the input and Android
     * offers no "which input is the Recorder using" question. What it offers is
     * the list of inputs that exist, and the routing rule it applies is
     * documented -- a plugged microphone wins over the built-in one. So the best
     * available answer is the highest-priority device present, in the order
     * PRD 6.6 lists.
     *
     * It is wrong in one case worth naming: a wired headset plugged in but not
     * selected by the user in system settings. The MVP shows what the system
     * would normally pick, and the fix is the input *selection* ADR-0002
     * deferred, not a better guess here.
     */
    private fun activeAudioInput(): AudioInput {
        if (!hasAudioPermission()) return AudioInput.UNKNOWN
        val devices = getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.map { it.type }
            .orEmpty()
        return when {
            devices.any { it == AudioDeviceInfo.TYPE_USB_DEVICE || it == AudioDeviceInfo.TYPE_USB_HEADSET } ->
                AudioInput.USB
            devices.any { it == AudioDeviceInfo.TYPE_WIRED_HEADSET } -> AudioInput.WIRED
            devices.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } -> AudioInput.BLUETOOTH
            devices.any { it == AudioDeviceInfo.TYPE_BUILTIN_MIC } -> AudioInput.BUILT_IN
            else -> AudioInput.UNKNOWN
        }
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
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Stop",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
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
        // The user's own off switch (ADR-0019). UI-7's security copy promises
        // one -- "turn the server off when you are done" -- and an automatic
        // rule they cannot see is not an answer to a consequence they were just
        // told about. Deliberate, so it ignores the idle conditions.
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return result
    }

    companion object {
        private const val CHANNEL_ID = "capture"
        private const val EXPOSURE_TAG = "ExposureLoop"
        private const val IDLE_TAG = "IdleShutdown"

        /**
         * Long enough for a recreated activity to rebind, short enough that a
         * user who closed the app does not wonder why the camera light is on.
         */
        private const val IDLE_SHUTDOWN_GRACE_MS = 5_000L

        /**
         * Where the meter calls it clipping. Not 1.0: a signal that reaches full
         * scale has already been clipped by the converter, so the indicator has
         * to fire slightly before it to be a warning rather than a post-mortem.
         */
        private const val CLIPPING_LEVEL = 0.99
        private const val SWEEP_TAG = "LensSweep"

        /** #20's sweep, startable over adb so the phone need not be unlocked. */
        const val ACTION_LENS_SWEEP = "com.scenaristo.camera.LENS_SWEEP"

        /** The notification's own stop action (ADR-0019). */
        const val ACTION_STOP = "com.scenaristo.camera.STOP"
        private const val NOTIFICATION_ID = 1
        private const val MAX_LOCK_MS = 4 * 60 * 60 * 1000L

        /** 1/50 s at ISO 100, 30 fps: the 50 Hz default from PRD 6.2's ladder. */
        private val DEFAULT_REQUEST = ManualControls.Request(
            exposureTimeNs = 20_000_000L,
            sensitivity = 100,
            frameDurationNs = 33_333_333L,
            // PRD 6.1's default white balance, as the locked preset nearest to
            // it (PRD 6.4). Not AWB OFF: off with no gains is not a white
            // balance, it is the absence of one.
            awbMode = ManualControls.awbModeFor(DEFAULT_KELVIN),
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
