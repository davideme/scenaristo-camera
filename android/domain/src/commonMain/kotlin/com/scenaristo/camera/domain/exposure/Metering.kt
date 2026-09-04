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
            var col = 0
            while (col < frame.width) {
                val x = (col + 0.5) / frame.width
                val w = if (windows.any { it.contains(x, y) }) 1.0 else config.backgroundWeight
                if (w > 0.0) {
                    weightedLog += w * ln(frame.sampler.lumaAt(col, row))
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
 * A frame to meter: its size, and a way to read one pixel's luma.
 *
 * A sampler rather than a buffer because `:domain` is platform-free and the
 * buffer never is. On Android the frames come from the GL tap of ADR-0018 as
 * packed RGBA; on iOS they will come from a `CVPixelBuffer`'s luma plane; and a
 * JVM byte buffer cannot cross into `commonMain` at all -- which the invariant
 * check enforces by grepping these files, comments included, so do not name the
 * type here. What is genuinely shared is the
 * photometry and the weighting, and those are here — [LumaScale] converts a
 * platform's pixel to luma, and [FaceWeightedMeter] decides which pixels count.
 */
class LumaFrame(
    val width: Int,
    val height: Int,
    val sampler: LumaSampler,
)

/** Reads gamma-encoded luma in 0.0..1.0 at a pixel, whatever the buffer's layout. */
fun interface LumaSampler {
    fun lumaAt(x: Int, y: Int): Double
}

/**
 * Turning a platform's pixel into luma, which is shared arithmetic rather than
 * platform detail — and easy to get quietly wrong in two different ways on two
 * different platforms.
 */
object LumaScale {

    /**
     * A frame that meters as pure black is a lens cap, not an infinite exposure
     * error. The floor keeps the logarithm finite so the loop asks for maximum
     * ISO and stops there.
     */
    const val BLACK_FLOOR: Double = 1e-4

    /**
     * Rec.709 luma from encoded R, G, B bytes — the coefficients PRD 6.1's
     * "SDR, Rec.709, 8-bit" implies, and what the GL tap's RGBA frames need.
     *
     * Note the luma is computed from the *encoded* values rather than linearised
     * first. That is deliberate and matches what a camera's own Y channel is: the
     * loop linearises once, downstream, with a single gamma.
     */
    fun rec709(r: Int, g: Int, b: Int): Double =
        ((0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0).coerceIn(BLACK_FLOOR, 1.0)

    /** A studio-range (16..235) luma byte, which is what a camera Y plane carries. */
    fun studioRange(raw: Int): Double = ((raw - 16) / 219.0).coerceIn(BLACK_FLOOR, 1.0)

    /** A full-range (0..255) luma byte. */
    fun fullRange(raw: Int): Double = (raw / 255.0).coerceIn(BLACK_FLOOR, 1.0)
}

/**
 * A sampler over a luma plane held as bytes — a `YUV_420_888` Y plane copied out,
 * and the shape the tests use.
 *
 * [rowStride] is **not** [LumaFrame.width] on most devices: cameras pad rows out
 * to an alignment, and reading width bytes per row meters a slowly shearing
 * diagonal of the picture while looking entirely plausible.
 */
fun yPlaneSampler(
    y: ByteArray,
    rowStride: Int,
    pixelStride: Int = 1,
    videoRange: Boolean = true,
): LumaSampler = LumaSampler { x, row ->
    val raw = y[row * rowStride + x * pixelStride].toInt() and 0xFF
    if (videoRange) LumaScale.studioRange(raw) else LumaScale.fullRange(raw)
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
     * mean needs and a sixteenth of the work of reading all of them.
     */
    val sampleStride: Int = 4,
)
