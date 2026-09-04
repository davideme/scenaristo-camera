package com.scenaristo.camera.domain.exposure

import kotlin.math.exp
import kotlin.math.ln

/**
 * The metering half of ADR-0005: one number per frame for [ExposureLoop] to act
 * on, weighted towards the face.
 *
 * Android reports no exposure offset once auto-exposure is off, which is the
 * whole reason this exists — with `CONTROL_AE_MODE_OFF` the HAL stops answering
 * and the app has to look at the picture itself. iOS does answer, and is ignored
 * anyway: ADR-0005 requires one metering implementation so the two platforms
 * cannot drift apart on what "correctly exposed" means.
 *
 * The frames come from the preview tap (ADR-0018) rather than an `ImageAnalysis`
 * stream, because a UHD recording and an `ImageAnalysis` cannot be bound together
 * on the reference device (#20). Nothing here depends on which: it takes a luma
 * plane and returns a number.
 *
 * It is deliberately not an average of the picture. A talking head is usually
 * backlit by whatever is behind them — a window, a lamp, a bright wall — and the
 * frame mean of that scene is a correctly exposed *room* with an underexposed
 * face in it, which is the failure this whole feature exists to prevent.
 */
class FaceWeightedMeter(private val config: MeteringConfig = MeteringConfig()) {

    /**
     * Meter one frame, returning gamma-encoded luma in 0.0..1.0 — the number
     * [ExposureLoop.onFrame] takes.
     *
     * [faces] are the rectangles the platform detected (Camera2
     * `STATISTICS_FACES`, iOS `AVMetadataFaceObject`), normalised in the frame.
     * Empty means either "no faces" or "this device does not report them with
     * auto-exposure off", and both fall back to [MeteringConfig.centreWindow] —
     * which is where a person sitting in front of a tripod is anyway.
     *
     * The average is geometric, not arithmetic: ADR-0005 asks for a *log*
     * luminance, and the reason is that one blown highlight in an arithmetic mean
     * drags the whole frame darker in proportion to how blown it is. A geometric
     * mean of gamma-encoded values is also the gamma-encoded geometric mean of
     * the linear ones — a power law commutes with it — so metering in encoded
     * space here and linearising in the loop are the same operation done once.
     */
    fun meter(frame: LumaFrame, faces: List<FrameRect> = emptyList()): Double {
        val windows = faces.ifEmpty { listOf(config.centreWindow) }
        val stride = config.sampleStride.coerceAtLeast(1)

        var weightedLog = 0.0
        var weight = 0.0
        var row = 0
        while (row < frame.height) {
            val y = (row + 0.5) / frame.height
            val rowStart = row * frame.rowStride
            var col = 0
            while (col < frame.width) {
                val x = (col + 0.5) / frame.width
                val w = if (windows.any { it.contains(x, y) }) 1.0 else config.backgroundWeight
                if (w > 0.0) {
                    weightedLog += w * ln(frame.luma(rowStart + col, config.videoRange))
                    weight += w
                }
                col += stride
            }
            row += stride
        }

        return if (weight == 0.0) 0.0 else exp(weightedLog / weight)
    }
}

/**
 * A frame's luma plane, exactly as the platform hands it over: `Y` of a
 * `YUV_420_888` image on Android, the luma plane of a `kCVPixelFormatType_420f`
 * buffer on iOS.
 *
 * Not a `data class` on purpose — a `ByteArray` in one gives you an `equals` that
 * compares identity while looking like it compares content, and this is exactly
 * the kind of object someone would reach for that with.
 */
class LumaFrame(
    val y: ByteArray,
    val width: Int,
    val height: Int,
    /**
     * Bytes per row, which is **not** [width] on most devices: the camera pads
     * rows out to an alignment. Reading [width] bytes per row from a padded plane
     * meters a slowly shearing diagonal of the picture rather than the picture.
     */
    val rowStride: Int = width,
) {
    /**
     * One sample, normalised to 0.0..1.0 and floored.
     *
     * A frame that meters as pure black is a lens cap, not an infinite exposure
     * error; the floor keeps the logarithm finite so the loop asks for maximum
     * ISO and stops there.
     */
    internal fun luma(index: Int, videoRange: Boolean): Double {
        val raw = y[index].toInt() and 0xFF
        val scaled = if (videoRange) (raw - 16) / 219.0 else raw / 255.0
        return scaled.coerceIn(BLACK_FLOOR, 1.0)
    }

    private companion object {
        const val BLACK_FLOOR = 1e-4
    }
}

/** A rectangle in the frame, normalised so 0.0 is the left or top edge and 1.0 the right or bottom. */
data class FrameRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    fun contains(x: Double, y: Double): Boolean = x in left..right && y in top..bottom
}

/**
 * The metering half of what ADR-0005 left to tune, kept apart from
 * [ExposureConfig] because these are answers about a *picture* and those are
 * answers about a *controller*. Phase 0 #25 measures both.
 */
data class MeteringConfig(
    /**
     * Where the subject is assumed to be when the platform reports no faces.
     * A seated speaker framed by a tripod fills roughly the middle of the frame,
     * and the window that ruins the shot is outside it.
     */
    val centreWindow: FrameRect = FrameRect(left = 0.3, top = 0.2, right = 0.7, bottom = 0.8),
    /**
     * What the rest of the frame is worth. Not zero: a face metered in complete
     * isolation exposes the same in a dark studio and a blown conservatory, and
     * the surroundings are part of what a viewer sees.
     */
    val backgroundWeight: Double = 0.1,
    /**
     * Sample every nth pixel in both axes. At ADR-0008's 960x540 preview size a
     * stride of 4 still reads about 32 000 samples, which is far more than a
     * mean needs and a quarter of the work of reading all of them.
     */
    val sampleStride: Int = 4,
    /**
     * True when the luma plane is studio-range (16..235) rather than full-range
     * (0..255). Camera YUV is conventionally studio-range; a device that hands
     * over full-range luma and is metered as studio-range reads about a tenth of
     * a stop bright, which is inside the loop's dead band but not inside the
     * grey-card tolerance of PRD 6.4. Confirm per device in #25.
     */
    val videoRange: Boolean = true,
)
