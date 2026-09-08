package com.scenaristo.camera.capture

import android.hardware.camera2.CaptureResult
import android.media.Image
import androidx.camera.core.CameraControl
import com.scenaristo.camera.domain.exposure.ExposureConfig
import com.scenaristo.camera.domain.exposure.ExposureLoop
import com.scenaristo.camera.domain.exposure.ExposureState
import com.scenaristo.camera.domain.exposure.FaceMapping
import com.scenaristo.camera.domain.exposure.FaceWeightedMeter
import com.scenaristo.camera.domain.exposure.FrameRect
import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.exposure.Histogram
import com.scenaristo.camera.domain.exposure.IsoRange
import com.scenaristo.camera.domain.exposure.LumaFrame
import com.scenaristo.camera.domain.exposure.LumaSampler
import com.scenaristo.camera.domain.exposure.LumaScale
import com.scenaristo.camera.domain.exposure.MeteringConfig
import com.scenaristo.camera.domain.exposure.SensorFace
import com.scenaristo.camera.domain.exposure.SensorRect
import com.scenaristo.camera.domain.exposure.TapGeometry
import com.scenaristo.camera.domain.lighting.PortraitLighting
import com.scenaristo.camera.domain.lighting.PortraitLightingFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The device half of ADR-0005: tapped frames in, a locked exposure out.
 *
 * Everything that decides anything is in `:domain` — [FaceWeightedMeter] weighs
 * the picture, [ExposureLoop] moves ISO and the shutter rung — and this class is
 * the wiring either side of them: reading pixels out of a GL frame, and pushing
 * the answer at a camera. That split is ADR-0013's, and it is what lets PRD 6.3's
 * numbers be checked on the host and re-checked here rather than only here.
 *
 * Two threads meet in this object. Frames arrive on the tap's GL thread and
 * capture results on a camera thread, and the loop is a plain state machine, so
 * every entry point takes the lock.
 */
class ExposureController(
    /** What this lens can do, straight from its characteristics (ADR-0011). */
    isoRange: IsoRange,
    grid: GridFrequency,
    /** The camera to push ISO and shutter at, from the bound `Camera`. */
    private val cameraControl: CameraControl,
    /** 30 fps, pinned (PRD 6.1). The loop never changes it; the sensor is told anyway. */
    private val frameDurationNs: Long = FRAME_DURATION_30FPS_NS,
    /**
     * The locked white balance mode to keep asserting (PRD 6.4).
     *
     * It rides along with every exposure push and is not the loop's business,
     * for a reason that is easy to miss: the runtime request path in
     * [ManualControls] **replaces** the whole set of options previously set
     * through it, so a push that carried only ISO would silently drop the white
     * balance somebody set a moment earlier. Anything applied that way has to be
     * applied every time. (Naming the API here would fail the ADR-0002
     * invariant check, which greps these files, comments included.)
     */
    awbMode: Int,
    private val config: ExposureConfig = ExposureConfig(),
    meteringConfig: MeteringConfig = MeteringConfig(),
    /** The user's stored answer to "hold exposure for the take?" (ADR-0023). */
    lockWhileRecording: Boolean = false,
    /**
     * Where a previous camera left off, when this loop is replacing one that was
     * released while nobody was watching (ADR-0025).
     *
     * Null on a cold start, which is the sensor floor per PRD 6.3.
     */
    resumeFrom: ExposureState? = null,
    /**
     * The lens's full active array, the fallback divisor for a capture result
     * that carries no crop region (PRD 6.3).
     */
    private val activeArray: SensorRect? = null,
    /**
     * What the tap is doing to the buffer right now (ADR-0018).
     *
     * A function rather than a value because it changes when the phone is
     * turned, and a face is mapped for the frame in hand rather than for the
     * orientation the camera was bound in. Defaults to knowing nothing, which
     * puts the meter on its centre window -- the behaviour every take had before
     * faces were wired.
     */
    private val tapGeometry: () -> TapGeometry? = { null },
) {

    @Volatile
    private var awb: Int = awbMode

    private val loop = ExposureLoop(isoRange, config)
    private val meter = FaceWeightedMeter(meteringConfig)
    private val lock = Any()

    private val _state = MutableStateFlow(
        resumeFrom?.let { loop.resume(it, grid, lockWhileRecording) }
            ?: loop.start(grid, lockWhileRecording),
    )

    /** What the phone and the browser read: shutter, ISO and the warnings (PRD 6.8). */
    val state: StateFlow<ExposureState> = _state.asStateFlow()

    private val _histogram = MutableStateFlow(Histogram())

    /**
     * The tonal distribution of the last metered frame (#97).
     *
     * Its own flow rather than a field on [ExposureState], because
     * [ExposureState] is pure replayable data that PRD 6.3's acceptance criteria
     * are checked against frame by frame -- and a 64-element array on it would
     * make every one of those comparisons a comparison of arrays, for a value
     * the loop never reads.
     *
     * It also changes on **every** frame where the loop's state usually does
     * not, so it is deliberately not something [state]'s collectors get woken
     * for. The service samples it on its own one-second tick instead.
     */
    val histogram: StateFlow<Histogram> = _histogram.asStateFlow()

    /**
     * The faces the sensor last reported, normalised into the region it is
     * reading but *not* yet turned or cropped into the meter's frame.
     *
     * Two steps rather than one, and split here on purpose: this half depends on
     * the capture result and is written on the camera thread, while the other
     * half depends on the tap's geometry and is only true for the frame being
     * metered. A rotation between the two -- the phone turned while a result was
     * in flight -- would otherwise leave a face mapped through the previous
     * orientation, which is a rectangle in the wrong half of the picture.
     *
     * Written on a camera thread, read on the tap's GL thread, hence volatile.
     */
    @Volatile
    private var facesInCrop: List<FrameRect> = emptyList()

    @Volatile
    private var sensorFaces: List<SensorFace> = emptyList()

    @Volatile
    private var sensorCropRegion: SensorRect? = null

    private val lightingFilter = PortraitLightingFilter()

    private val _lighting = MutableStateFlow(PortraitLighting.Reading())

    /**
     * How the room is lighting the subject (PRD 6.11).
     *
     * A separate flow from [state] for [histogram]'s reason: it changes on frames
     * where the exposure loop's state does not, and the service samples it on its
     * own one-second tick rather than waking every collector.
     */
    val lighting: StateFlow<PortraitLighting.Reading> = _lighting.asStateFlow()

    /**
     * Meter one tapped frame and act on it. **Does not close [image]** — the tap
     * hands the same frame to the JPEG encoder, which closes it.
     *
     * Called on the GL thread, so this is on the path of every preview frame:
     * the meter samples every fourth pixel of a 960x540 frame, which is about
     * 32 000 reads, and the loop itself does arithmetic on eight numbers.
     *
     * Unless the take is locked (ADR-0023), in which case it does none of it.
     * That early return **is** the feature: the 32 000 reads and their logarithms
     * are the cost, and they are spent on the same thread that owes the
     * viewfinder a frame every 33 ms while the 4K encoder runs beside it.
     */
    fun onFrame(image: Image, nowMs: Long) {
        // Read outside the lock deliberately. This runs 30 times a second and
        // the answer is a single volatile read; taking the lock to discover
        // there is nothing to do would contend with the camera thread's capture
        // results for no reason. A lost race costs one metered frame, which the
        // loop's own guard then discards.
        if (_state.value.locked) {
            // ADR-0023 is explicit that a locked take meters nothing, and the
            // exposure aids (#97) have to say so rather than keep drawing the
            // last histogram from before the lock. A stale distribution beside a
            // frozen EV reading is the same lie `AudioState.metering` exists to
            // prevent: a meter that is not running says nothing at all, and
            // drawing that as a measurement is how somebody trusts it.
            if (_histogram.value.measured) _histogram.value = Histogram()
            return
        }

        val geometry = tapGeometry()
        val subject = FaceMapping.subjectInFrame(sensorFaces, sensorCropRegion, activeArray, geometry)
        val measured = meter.measure(
            frameOf(image),
            FaceMapping.facesInFrame(facesInCrop, geometry),
            subject,
        )
        // Not during a take (ADR-0023, and ADR-0027's precedent for the mount):
        // the reading exists so somebody can move a lamp, and nobody re-lights
        // mid-take. Publishing it anyway would be a number that cannot be acted
        // on, changing under a recording that is meant to be quiet.
        val current = _state.value
        _lighting.value = lightingFilter.accept(
            if (current.recording) {
                PortraitLighting.Reading.unmeasured(current.iso)
            } else {
                PortraitLighting.read(measured, current.iso)
            },
        )
        _histogram.value = measured.histogram
        val next = synchronized(lock) {
            val before = _state.value
            val after = loop.onFrame(before, measured.luma, nowMs)
            _state.value = after
            after.takeIf { it.iso != before.iso || it.shutterHz != before.shutterHz }
        }
        // Outside the lock: this crosses into CameraX, and holding a lock across
        // it would put a camera thread's callback behind a GL thread's request.
        next?.let { push(it) }
    }

    /**
     * A capture result arrived. Releases the loop when the sensor confirms what
     * was asked for (ADR-0005).
     */
    fun onCaptureResult(result: CaptureResult) {
        val found = ManualControls.faces(result)
        val region = ManualControls.cropRegion(result)
        facesInCrop = FaceMapping.facesInCrop(found, region, activeArray)
        // Kept raw as well: the lighting read divides a face on its eye line
        // (PRD 6.11), and that point has to travel through the same turn and crop
        // as the rectangle rather than be recomputed from the mapped box.
        sensorFaces = found
        sensorCropRegion = region
        val reported = ManualControls.reported(result) ?: return
        synchronized(lock) {
            _state.value = loop.onSensorEcho(
                _state.value,
                iso = reported.sensitivity,
                shutterHz = shutterHzOf(reported.exposureTimeNs),
            )
        }
    }

    /**
     * The user chose a different white balance preset (PRD 6.4).
     *
     * Applied through the same request as exposure, for the replacement reason
     * above, and allowed at any time the session allows it -- `Session` already
     * refuses a settings change while recording (PRD 6.1's locked look), so this
     * does not have to.
     */
    fun onWhiteBalanceChanged(awbMode: Int) {
        awb = awbMode
        push(_state.value)
    }

    /** The user changed the mains frequency (PRD 6.2). */
    fun onGridChanged(grid: GridFrequency, nowMs: Long) {
        val next = synchronized(lock) {
            loop.onGridChanged(_state.value, grid, nowMs).also { _state.value = it }
        }
        push(next)
    }

    /**
     * A take started or stopped (ADR-0022).
     *
     * Nothing is pushed to the sensor: the mode changes how fast the loop may
     * move, not where it is. The next metered frame acts on the new damping.
     */
    fun onRecordingChanged(recording: Boolean) {
        synchronized(lock) {
            _state.value = loop.onRecordingChanged(_state.value, recording)
        }
    }

    /**
     * The user chose whether exposure tracks the light during a take (ADR-0023).
     *
     * Nothing is pushed: like the damping mode, this changes what the loop is
     * allowed to do on the next frame rather than where the exposure sits.
     */
    fun onExposureLockChanged(lockWhileRecording: Boolean) {
        synchronized(lock) {
            _state.value = loop.onExposureLockChanged(_state.value, lockWhileRecording)
        }
    }

    /**
     * The user locked or released the shutter (PRD 6.3, #51).
     *
     * Pushed to the sensor immediately, like every other change here: a lock the
     * camera has not been told about is a number the UI is reporting and the
     * hardware is not using.
     */
    fun onShutterLockChanged(shutterLock: Int?, nowMs: Long) {
        val next = synchronized(lock) {
            loop.onShutterLockChanged(_state.value, shutterLock, nowMs).also { _state.value = it }
        }
        push(next)
    }

    /** Pushes the opening exposure, so the first frames are the loop's and not the builder's. */
    fun start() = push(_state.value)

    private fun push(state: ExposureState) = ManualControls.apply(
        cameraControl,
        ManualControls.Request(
            exposureTimeNs = exposureTimeNsOf(state.shutterHz),
            sensitivity = state.iso,
            frameDurationNs = frameDurationNs,
            awbMode = awb,
        ),
    )

    /**
     * The tap renders RGBA, not YUV (ADR-0018), so luma is Rec.709 of three
     * channels rather than a plane to read straight out — PRD 6.1 fixes the
     * colour space, which fixes the coefficients.
     *
     * Read with absolute gets so the buffer's position is left alone: the JPEG
     * encoder rewinds and bulk-copies the same buffer for the browser preview,
     * and a meter that moved the position would corrupt the frame it shares.
     */
    private fun frameOf(image: Image): LumaFrame {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        return LumaFrame(
            width = image.width,
            height = image.height,
            sampler = LumaSampler { x, y ->
                val index = y * rowStride + x * pixelStride
                LumaScale.rec709(
                    r = buffer.get(index).toInt() and 0xFF,
                    g = buffer.get(index + 1).toInt() and 0xFF,
                    b = buffer.get(index + 2).toInt() and 0xFF,
                )
            },
        )
    }

    private fun shutterHzOf(exposureTimeNs: Long): Int =
        if (exposureTimeNs <= 0L) 0 else (NANOS_PER_SECOND.toDouble() / exposureTimeNs).roundToInt()

    private fun exposureTimeNsOf(shutterHz: Int): Long =
        (NANOS_PER_SECOND.toDouble() / shutterHz).roundToLong()

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** PRD 6.1: min and max frame duration both locked to 1/30 s. */
        const val FRAME_DURATION_30FPS_NS = 33_333_333L
    }
}
