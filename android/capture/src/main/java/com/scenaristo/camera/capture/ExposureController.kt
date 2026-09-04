package com.scenaristo.camera.capture

import android.hardware.camera2.CaptureResult
import android.media.Image
import androidx.camera.core.CameraControl
import com.scenaristo.camera.domain.exposure.ExposureConfig
import com.scenaristo.camera.domain.exposure.ExposureLoop
import com.scenaristo.camera.domain.exposure.ExposureState
import com.scenaristo.camera.domain.exposure.FaceWeightedMeter
import com.scenaristo.camera.domain.exposure.FrameRect
import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.exposure.IsoRange
import com.scenaristo.camera.domain.exposure.LumaFrame
import com.scenaristo.camera.domain.exposure.LumaSampler
import com.scenaristo.camera.domain.exposure.LumaScale
import com.scenaristo.camera.domain.exposure.MeteringConfig
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
) {

    @Volatile
    private var awb: Int = awbMode

    private val loop = ExposureLoop(isoRange, config)
    private val meter = FaceWeightedMeter(meteringConfig)
    private val lock = Any()

    private val _state = MutableStateFlow(loop.start(grid))

    /** What the phone and the browser read: shutter, ISO and the warnings (PRD 6.8). */
    val state: StateFlow<ExposureState> = _state.asStateFlow()

    /**
     * Face rectangles are not wired yet, so the meter uses its centre window.
     *
     * That is [FaceWeightedMeter]'s documented fallback for a device that does
     * not report faces with auto-exposure off, and for a talking head on a tripod
     * the two windows land in nearly the same place — but it is a fallback, and
     * ADR-0005 asks for `STATISTICS_FACES`. What is missing is not the plumbing
     * but the coordinate mapping: face rectangles arrive in the sensor's active
     * array space, and the meter wants them normalised in a frame the tap has
     * already cropped to the recording's aspect ratio (ADR-0018). Getting that
     * wrong meters a rectangle that is not where the face is, which is worse
     * than the centre window rather than better.
     */
    private val faces: List<FrameRect> = emptyList()

    /**
     * Meter one tapped frame and act on it. **Does not close [image]** — the tap
     * hands the same frame to the JPEG encoder, which closes it.
     *
     * Called on the GL thread, so this is on the path of every preview frame:
     * the meter samples every fourth pixel of a 960x540 frame, which is about
     * 32 000 reads, and the loop itself does arithmetic on eight numbers.
     */
    fun onFrame(image: Image, nowMs: Long) {
        val next = synchronized(lock) {
            val before = _state.value
            val after = loop.onFrame(before, meter.meter(frameOf(image), faces), nowMs)
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
