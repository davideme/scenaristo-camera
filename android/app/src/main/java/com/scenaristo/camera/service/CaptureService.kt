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
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
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
import com.scenaristo.camera.domain.exposure.shutterLadder
import com.scenaristo.camera.domain.lens.framingsFor
import com.scenaristo.camera.domain.whitebalance.DEFAULT_KELVIN
import com.scenaristo.camera.domain.protocol.CaptureSettings
import com.scenaristo.camera.domain.protocol.Command
import com.scenaristo.camera.domain.protocol.CommandName
import com.scenaristo.camera.domain.protocol.DeviceStatus
import com.scenaristo.camera.domain.protocol.RecordingState
import com.scenaristo.camera.domain.protocol.Optics
import com.scenaristo.camera.domain.recording.TakeName
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
import java.time.LocalDateTime

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

    /**
     * Seeded with the PRD's own defaults rather than with [startingState].
     *
     * Field initialisers run during construction, before `onCreate` has a
     * `Context` to read [Settings] with — so calling `startingState()` here
     * crashed the service on start-up. `onCreate` overwrites this the moment the
     * real one exists, and nothing observes the flow before then.
     */
    private val _state = MutableStateFlow(defaultState())

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

    /**
     * The take that was interrupted, if the last run ended badly (#17).
     *
     * Read once at start-up and never set again, so the phone screen can say so
     * and the user can go and find the file.
     */
    private val _interrupted = MutableStateFlow<String?>(null)
    val interrupted: StateFlow<String?> = _interrupted.asStateFlow()

    /** #20: the per-lens key echo, once the sweep has run. */
    private val _lensSweep = MutableStateFlow<String?>(null)
    val lensSweep: StateFlow<String?> = _lensSweep.asStateFlow()

    /** Camera2 id of the bound back camera, for the sweep's physical-id lookup. */
    private var backCameraId: String? = null

    private val jpeg = PreviewJpegSource()
    private lateinit var tap: PreviewTapProcessor
    private lateinit var camera: ManualSession
    private lateinit var settings: Settings
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

    /** Last shutter lock pushed, for the same reason (PRD 6.3, #51). */
    private var appliedShutterLock: Int? = null

    /** Last grid and lens written to storage, for the same reason. */
    private var appliedGrid: GridFrequency? = null
    private var appliedLens: String? = null

    /** Last gallery choice written to storage (PRD 6.7). */
    private var appliedGallery: Boolean? = null

    /** ADR-0023's mode, as last handed to the exposure loop and to storage. */
    private var appliedExposureLock: Boolean? = null

    /** The bound camera, for the framing control (#77). Null until the first bind. */
    private var boundCamera: androidx.camera.core.Camera? = null

    /** The framing last pushed at the camera, so the tick only acts on a change. */
    private var appliedZoom: Double = 1.0

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

        settings = Settings(this)
        session = Session(startingState())
        _state.value = session.state
        Log.i(SETTINGS_TAG, "grid ${session.state.settings.grid} from ${settings.grid().source}")
        jpeg.quality = 80
        tap = PreviewTapProcessor(onFrame = ::onTapFrame)
        camera = ManualSession(DEFAULT_REQUEST, tap = tap, onCaptureResult = ::onCaptureResult)
        server = ControlServer(session = session, frames = PreviewFrames { jpeg.latest() })

        // Before anything can write a new marker, and before the camera binds:
        // whatever is on disk now is a claim about the *previous* run.
        _interrupted.value = takeInterruptedTakeIfAny()
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
    /**
     * One tapped frame, to the meter and then to the preview encoder.
     *
     * The metering is wrapped because [PreviewTapProcessor] hands ownership of
     * the image over and stalls at its buffer count if one is not returned:
     * anything thrown here used to skip [PreviewJpegSource.accept], which is what
     * closes it, so a single bad frame leaked every frame after it and the
     * preview died along with the exposure loop. A frame the meter cannot read
     * is worth losing; the take is not.
     *
     * `Throwable` rather than `Exception` on purpose — the failure that found
     * this was an `OutOfMemoryError`.
     */
    private fun onTapFrame(image: android.media.Image) {
        try {
            exposure?.onFrame(image, System.currentTimeMillis())
        } catch (failure: Throwable) {
            Log.w(EXPOSURE_TAG, "metering skipped a frame", failure)
        }
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
                    // #97: the sign is flipped on the way out. The loop states
                    // its error as what it must do -- "needs 0.4 stops more
                    // light" -- and the remote states what the picture is:
                    // 0.4 stops under. Same number, opposite audience.
                    exposure = state.exposure.copy(
                        stopsFromTarget = -exposure.errorEv,
                        metering = exposure.acquired,
                    ),
                )
            }
        }
    }

    /**
     * Sets the framing (#77).
     *
     * `setZoomRatio` and not a rebind: on a phone the other lenses live behind
     * one logical camera, so this is a request to the HAL rather than a new
     * session -- no dropped preview, no restarted exposure loop, and nothing for
     * the browser's MJPEG stream to notice.
     *
     * `appliedZoom` is set before the call and not after, so a ratio the camera
     * refuses is not retried once a second forever.
     *
     * The result is a future, and a refusal arrives through it rather than as a
     * thrown exception -- so a `runCatching` around the call alone would report
     * success for every zoom the camera declined. The listener is what turns
     * that into something a log can show, and it reads the camera's *own*
     * `zoomState` back rather than echoing the value just sent: that is the
     * difference between "we asked" and "the camera did it", which is the whole
     * distinction a state document cannot make on its own.
     */
    private fun applyZoom(camera: androidx.camera.core.Camera, ratio: Double) {
        appliedZoom = ratio
        val future = runCatching { camera.cameraControl.setZoomRatio(ratio.toFloat()) }
            .onFailure { Log.w(ZOOM_TAG, "zoom to ${ratio}x refused: ${it.message}") }
            .getOrNull() ?: return
        future.addListener({
            val reported = camera.cameraInfo.zoomState.value?.zoomRatio
            runCatching { future.get() }
                .onSuccess { Log.i(ZOOM_TAG, "zoom ${ratio}x applied; camera reports ${reported}x") }
                .onFailure { Log.w(ZOOM_TAG, "zoom ${ratio}x refused: ${it.message}; camera at ${reported}x") }
        }, ContextCompat.getMainExecutor(this))
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
            // #101: the remote shows the lens as an optic -- focal length and
            // f/-number, and the T-stop :domain derives from the second. Lens
            // constants, so they are published once at bind rather than on the
            // status tick.
            // #77: the other lenses on a phone are reached by zoom ratio, not by
            // camera id -- the ultrawide and telephoto sit behind the back
            // logical camera and cannot be selected at all (Davide, 2026-09-06).
            // So the lens list is a list of framings, and the range comes from
            // the camera itself rather than from a table of device names.
            val zoom = bound.cameraInfo.zoomState.value
            val base = _lensMm.value ?: 0
            val framings = framingsFor(
                minZoomRatio = (zoom?.minZoomRatio ?: 1f).toDouble(),
                maxZoomRatio = (zoom?.maxZoomRatio ?: 1f).toDouble(),
                baseEquivalentFocalLengthMm = base,
            )
            boundCamera = bound
            session.update(System.currentTimeMillis()) {
                it.copy(
                    optics = Optics(
                        equivalentFocalLengthMm = base,
                        apertureFNumber = ManualControls.aperture(bound.cameraInfo),
                    ),
                    lenses = framings,
                )
            }
            // Re-applied on every bind, which covers both ways a framing is
            // otherwise lost: a rebind inside one service lifetime (the lens
            // sweep unbinds everything), and a fresh start, where the ratio
            // comes back from `Settings` through `startingState`.
            applyZoom(bound, session.state.settings.zoomRatio)
            startExposureLoop(bound)
            val codecReport = CodecReport.of(lens.cameraId)
            _codecLabel.value = when {
                codecReport.profileCodec?.contains("hevc") == true -> "HEVC (H.265)"
                codecReport.profileCodec?.contains("avc") == true -> "H.264"
                else -> "—"
            }
            // PRD 6.7: the codec in use is displayed on phone *and web* before
            // recording. The phone reads `_codecLabel`; the remote control reads
            // the state document, so the same report goes into both.
            session.update(System.currentTimeMillis()) {
                it.copy(
                    encoding = codecReport.encoding(
                        frameRate = RECORDING_FRAME_RATE,
                        bitrate = RECORDING_BITRATE,
                    ),
                )
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
            lockWhileRecording = session.state.settings.lockExposureWhileRecording,
        )
        exposure = controller
        // A fresh controller starts life believing no take is running. That is
        // true on the ordinary path and false if the camera is ever rebound
        // mid-take, and the cost of being wrong is a locked take that quietly
        // meters -- so it is told, rather than left to the next transition.
        controller.onRecordingChanged(session.state.recording.recording)
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
            // #97: the histogram changes on every metered frame, where the rest
            // of the exposure state usually does not. Sampled on this tick
            // rather than published from the tap, so a 64-element array does not
            // put the GL thread's cadence on the wire -- the same reason
            // publishExposure does not broadcast.
            exposure?.histogram?.value?.let { histogram ->
                session.update(System.currentTimeMillis()) {
                    it.copy(exposure = it.exposure.copy(histogram = histogram.bins))
                }
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
            // ADR-0023: hold exposure for the take, or track the light through
            // it. Watched here like every other setting, and it can only ever
            // change between takes because `Session` refuses settings while
            // recording -- which is what makes the mode stable for a whole file.
            val exposureLock = session.state.settings.lockExposureWhileRecording
            if (exposureLock != appliedExposureLock) {
                appliedExposureLock = exposureLock
                settings.lockExposureWhileRecording = exposureLock
                exposure?.onExposureLockChanged(exposureLock)
            }
            // #77: the framing, watched on the tick for the same reason the white
            // balance is -- ADR-0007 keeps the command handler pure and
            // platform-free, so the camera work happens here, one step behind.
            val wantedZoom = session.state.settings.zoomRatio
            if (wantedZoom != appliedZoom) {
                boundCamera?.let { applyZoom(it, wantedZoom) }
                // Persisted alongside the other settings: someone who framed up
                // at 2x and came back would otherwise be at 1x having changed
                // nothing, and it is the one setting whose loss is invisible
                // until you look at the shot.
                settings.zoomRatio = wantedZoom
            }
            val shutterLock = session.state.settings.shutterLock
            if (shutterLock != appliedShutterLock) {
                appliedShutterLock = shutterLock
                exposure?.onShutterLockChanged(shutterLock, System.currentTimeMillis())
            }
            val kelvin = session.state.settings.whiteBalanceKelvin
            if (kelvin != appliedKelvin) {
                appliedKelvin = kelvin
                exposure?.onWhiteBalanceChanged(ManualControls.awbModeFor(kelvin))
                // PRD 6.2 and #2: an override that does not survive a relaunch is
                // not an override, it is a suggestion the app forgets.
                settings.whiteBalanceKelvin = kelvin
            }
            val grid = session.state.settings.grid
            if (grid != appliedGrid) {
                appliedGrid = grid
                // A grid arriving through a command is a *manual* choice by
                // definition -- detection never goes through the protocol -- so
                // storing it here is what makes the UI able to say "set manually"
                // on the next launch rather than guessing again.
                settings.gridOverride = grid
                exposure?.onGridChanged(grid, System.currentTimeMillis())
            }
            val gallery = session.state.settings.saveToGallery
            if (gallery != appliedGallery) {
                appliedGallery = gallery
                settings.saveToGallery = gallery
            }
            val lens = session.state.settings.lensId
            if (lens != appliedLens) {
                appliedLens = lens
                settings.lensId = lens
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

    /**
     * Hold exposure for the take, or track the light through it (ADR-0023).
     *
     * Through the command path for the same reason white balance is: the phone
     * is one more client of ADR-0007's single writer, so the browser sees the
     * change, and `Session`'s recording guard makes it impossible to flip the
     * mode in the middle of a file without any code here saying so.
     */
    fun setExposureLock(lock: Boolean) {
        lifecycleScope.launch {
            server.applyLocal(
                Command(
                    id = "phone-exposure-lock-" + System.currentTimeMillis(),
                    name = CommandName.SETTINGS_SET,
                    args = SettingsPatch(lockExposureWhileRecording = lock),
                ),
            )
            _state.value = session.state
        }
    }

    private suspend fun followRecordingState() {
        var wasRecording = false
        while (true) {
            val shouldRecord = session.state.recording.recording
            if (shouldRecord && !wasRecording) {
                // Before the recorder rather than after it, and here rather than
                // on the one-second tick that used to carry it (ADR-0022): a
                // mode that promises exposure does not move during the take has
                // to be in force for the file's *first* frame, and a second of
                // setup-speed ISO at the head of a locked take would be exactly
                // the thing the user asked not to have.
                exposure?.onRecordingChanged(true)
                startRecording()
            }
            if (!shouldRecord && wasRecording) {
                stopRecording()
                exposure?.onRecordingChanged(false)
            }
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

    /**
     * The take's name, per PRD 6.7 (`Scenaristo_YYYY-MM-DD_HH-MM-SS`).
     *
     * The shape is `:domain`'s so that Phase 4 produces the same one from the
     * same instant (ADR-0013); the local calendar fields are this platform's,
     * because turning an instant into a local date needs a time-zone database
     * that `commonMain` cannot have (ADR-0010).
     *
     * Local time rather than UTC: the name exists to be recognised by the person
     * who shot it.
     */
    private fun takeName(): String = takeNameOf(LocalDateTime.now())

    private fun startRecording() {
        val name = takeName()
        // PRD 6.7 / #17: the app owes the user a word on the next launch if this
        // take does not finish. Written before the recorder starts, because a
        // crash between these two lines should over-report rather than
        // under-report -- a take that never began is a confusing message, and a
        // take that was lost silently is a lost take.
        markerFile().writeText(name)
        val withAudio = hasAudioPermission()
        // The permission can arrive after the service started, so the microphone
        // type is claimed here rather than only in onCreate.
        if (withAudio) startForeground(NOTIFICATION_ID, notification(describe()), foregroundTypes())
        val pending = if (session.state.settings.saveToGallery) {
            camera.recorder.prepareRecording(this, mediaStoreOutput(name))
        } else {
            camera.recorder.prepareRecording(this, appFolderOutput(name))
        }
        // PRD 6.8's transport row names the file. Published when the take starts
        // rather than when it finishes, because "what is this take called" is a
        // question asked during the take as often as after it.
        session.update(System.currentTimeMillis()) {
            it.copy(recording = it.recording.copy(fileName = name))
        }
        recording = pending
            .withAudioIfPermitted()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Status) publishAudio(event.recordingStats)
                if (event is VideoRecordEvent.Finalize) {
                    publishAudio(null)
                    recording = null
                    // Finalize is the recorder saying the file is closed and
                    // complete, however the take ended -- stop, storage full, or
                    // the source going away. All of those are endings the app
                    // saw; only the ones it did not see leave the marker behind.
                    markerFile().delete()
                    // A recording can end on its own -- storage, a file size cap,
                    // the source going away. The state document has to follow the
                    // truth rather than the request, or the browser shows a take
                    // that stopped minutes ago (#20).
                    session.update(System.currentTimeMillis()) {
                        // The name outlives the take on purpose: "did that save,
                        // and as what" is the question of the second after a
                        // stop, and a transport row that blanks just then is
                        // answering the wrong one.
                        it.copy(
                            recording = RecordingState(
                                recording = false,
                                startedAtMs = null,
                                fileName = it.recording.fileName,
                            ),
                        )
                    }
                }
            }
        acquireLocks()
    }

    /**
     * The default: the app's own external directory (decision 2026-09-05,
     * Davide).
     *
     * `Android/data/<package>/files/Movies/`, which is where an ordinary app
     * keeps its own files. Nothing else on the phone sees it, the gallery does
     * not index it, and it needs no permission. It is also deleted when the app
     * is uninstalled, which is the trade being made: a quiet neighbour that does
     * not fill someone's photo roll with multi-gigabyte files, at the cost of
     * takes that do not outlive the app unless the user moves them.
     */
    private fun appFolderOutput(name: String): FileOutputOptions {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        return FileOutputOptions.Builder(File(dir, "$name.mp4")).build()
    }

    /**
     * The opt-in: the shared gallery (ADR-0020, PRD 6.7 and section 3).
     *
     * Visible to the gallery and to a laptop over MTP the moment it is
     * finalised, and it survives the app being uninstalled. Chosen by the user
     * rather than for them, because it writes large files into shared storage
     * that only they can clean up.
     *
     * `Movies/Scenaristo Camera/` rather than `DCIM/Camera/`: a take is work
     * product, not a snapshot (Davide, 2026-09-05).
     *
     * No storage permission is involved. Since Android 10 an app inserting its
     * own MediaStore rows needs none, and the floor here is API 34 (ADR-0012).
     */
    private fun mediaStoreOutput(name: String): MediaStoreOutputOptions {
        val details = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, TAKES_DIRECTORY)
        }
        return MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(details)
            .build()
    }

    /**
     * The breadcrumb that survives a crash (#17, PRD 6.7).
     *
     * A file rather than a preference because the process may be killed between
     * any two instructions, and a file that exists is the only claim that
     * survives having no chance to tidy up. Its contents are the take's path, so
     * the message can say *where* rather than only *that*.
     */
    private fun markerFile() = File(filesDir, "recording-in-progress")

    /**
     * Checks, once, whether the last take ended without the app noticing.
     *
     * Called at service start, before anything can write a new marker. A marker
     * still on disk means the process died between `startRecording` and the
     * recorder's `Finalize` -- a force-kill, a battery death, or the system
     * reclaiming us -- so the file it names was never closed by us.
     *
     * The marker is cleared as soon as it is read. The user is told once; a
     * notice that reappeared on every launch would be a bug of its own.
     */
    private fun takeInterruptedTakeIfAny(): String? {
        val marker = markerFile()
        if (!marker.exists()) return null
        val name = runCatching { marker.readText().trim() }.getOrNull()
        marker.delete()
        // The marker carries the display name rather than a path, because the
        // take may be a file in the app's folder or a MediaStore row depending
        // on the setting (ADR-0020) -- and the name is what the user is shown
        // and what they will search for either way.
        return name?.takeIf { it.isNotBlank() }?.let { "$it.mp4" }
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
        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.NOMINAL
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.FAIR
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SERIOUS
        else -> ThermalState.CRITICAL
    }

    /**
     * The state a launch starts from (PRD 6.2, #2, #15).
     *
     * Everything the user can change is read back from [Settings] rather than
     * hard-coded, and the grid runs PRD 6.2's detection chain — SIM country, then
     * device region — with any stored override in front of it. The shutter
     * follows from the grid rather than being a separate constant, which is what
     * makes "the UI reads 50 Hz" and "the shutter is 1/50" the same fact.
     */
    /** PRD 6.1's defaults, needing nothing but the PRD. */
    private fun defaultState(): ProtocolState = ProtocolState(
        settings = CaptureSettings(
            grid = GridFrequency.HZ_50,
            shutterHz = shutterLadder(GridFrequency.HZ_50).first(),
            iso = DEFAULT_REQUEST.sensitivity,
            whiteBalanceKelvin = DEFAULT_KELVIN,
            lensId = "0",
        ),
        recording = RecordingState(recording = false),
        device = DeviceStatus(0, false, ThermalState.NOMINAL, 0),
        serverTimeMs = System.currentTimeMillis(),
    )

    private fun startingState(): ProtocolState {
        val grid = settings.grid()
        return ProtocolState(
        settings = CaptureSettings(
            grid = grid.grid,
            shutterHz = shutterLadder(grid.grid).first(),
            iso = DEFAULT_REQUEST.sensitivity,
            whiteBalanceKelvin = settings.whiteBalanceKelvin,
            lensId = settings.lensId,
            saveToGallery = settings.saveToGallery,
            lockExposureWhileRecording = settings.lockExposureWhileRecording,
            zoomRatio = settings.zoomRatio,
        ),
        recording = RecordingState(recording = false),
        device = DeviceStatus(0, false, ThermalState.NOMINAL, 0),
        serverTimeMs = System.currentTimeMillis(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)

        // Every start that is not the off switch re-enters the foreground,
        // including a start that lands on a service instance which is still
        // alive.
        //
        // `onCreate` is not enough, and the gap is the one a bound service
        // opens. ACTION_STOP calls `stopForeground` and then `stopSelf`, but
        // the activity is *bound*, so `stopSelf` does not destroy anything --
        // the instance survives, no longer foreground. The next
        // `startForegroundService` therefore skips `onCreate`, where the only
        // other `startForeground` lives, and Android's contract is broken: the
        // platform waits for a call that no code path makes, then kills the
        // process with
        // "Context.startForegroundService() did not then call
        // Service.startForeground()".
        //
        // It is not a hang and it leaves no crash of ours in the log. What the
        // user sees is the app disappearing, and -- because the process took
        // the server with it -- a browser preview that goes black for no stated
        // reason. Recorded from the reference device on 2026-09-07: an ANR
        // three minutes after the off switch was used and the camera started
        // again.
        //
        // `startForeground` is idempotent: on an already-foreground service it
        // updates the notification and nothing else, which is why this can run
        // unconditionally rather than tracking whether it is needed. Tracking
        // it would be a second piece of state that can disagree with the
        // platform, and the platform's answer is the only one that counts.
        if (intent?.action != ACTION_STOP) {
            startForeground(NOTIFICATION_ID, notification(describe()), foregroundTypes())
        }

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
        private const val SETTINGS_TAG = "Settings"

        /** ADR-0020. Relative to the external volume's root, as MediaStore wants it. */
        private const val TAKES_DIRECTORY = "Movies/Scenaristo Camera"

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

        private const val ZOOM_TAG = "Framing"

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
         * What 4K30 actually costs, measured at 33.4 Mbit/s on the reference
         * device (#21). A rough number that is right is more use to a creator
         * than a precise one that needs the encoder to be running.
         *
         * Deliberately not the `CamcorderProfile` bitrate: on the reference
         * device the UHD profile declares 72 Mbit/s, because it describes the
         * fastest mode it supports (2160p60) rather than the 30 fps this app
         * pins. Both the minutes-remaining figure and the transport row's
         * bitrate come from here, so the two cannot disagree on the same screen.
         */
        private const val RECORDING_BITRATE = 33_400_000

        /** PRD 6.1: the session pins `Range(30, 30)`, so this is the file's rate. */
        private const val RECORDING_FRAME_RATE = 30

        /** [RECORDING_BITRATE] as bytes a minute, for the storage arithmetic. */
        private const val BYTES_PER_MINUTE = RECORDING_BITRATE / 8L * 60L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CaptureService::class.java))
        }
    }
}

/**
 * PRD 6.7's name for a take, from a local date and time.
 *
 * A free function so it can be tested without a service, a camera or a clock.
 * The thing worth testing is that the calendar fields do not get transposed on
 * the way across -- `monthValue` and `dayOfMonth` are adjacent,
 * interchangeable-looking integers, and a take named `Scenaristo_2026-06-09`
 * instead of `Scenaristo_2026-09-06` is wrong in a way nobody notices until
 * they sort a directory.
 */
internal fun takeNameOf(at: LocalDateTime): String = TakeName.of(
    year = at.year,
    month = at.monthValue,
    day = at.dayOfMonth,
    hour = at.hour,
    minute = at.minute,
    second = at.second,
)
