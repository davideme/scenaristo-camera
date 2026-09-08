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
    fun meter(frame: LumaFrame, faces: List<FrameRect> = emptyList()): Double =
        measure(frame, faces).luma

    /**
     * Meter one frame *and* count where its pixels fall, in one walk.
     *
     * The histogram is the remote control's exposure aid (PRD 6.8, #97). It is
     * built here rather than anywhere else because the expensive part -- reading
     * a pixel out of a platform buffer -- is already being paid, and doing it a
     * second time elsewhere would double the cost of the one thing that runs on
     * every preview frame.
     *
     * Unlike the metering average, the histogram is **not** weighted and not
     * windowed: every sampled pixel counts once, wherever it is. A histogram
     * that quietly emphasised the face would not be a histogram, and the
     * clipping it exists to reveal is usually in the background -- the window
     * behind the speaker is exactly the thing being looked for.
     */
    fun measure(
        frame: LumaFrame,
        faces: List<FrameRect> = emptyList(),
        subject: Subject? = null,
    ): Metered {
        val windows = faces.ifEmpty { listOf(config.centreWindow) }
        val stride = config.sampleStride.coerceAtLeast(1)
        val bins = IntArray(Histogram.BINS)

        var weightedLog = 0.0
        var weight = 0.0
        // The lighting read (PRD 6.11): the two halves of the subject's face and
        // everything that is not the subject at all. Accumulated here rather than
        // in a second walk because reading a pixel out of a platform buffer is the
        // expensive part and it is already being paid -- the same argument the
        // histogram makes two fields up.
        var litLog = 0.0
        var litWeight = 0.0
        var shadowLog = 0.0
        var shadowWeight = 0.0
        var backgroundLog = 0.0
        var backgroundWeight = 0.0
        var row = 0
        while (row < frame.height) {
            val y = (row + 0.5) / frame.height
            var col = 0
            while (col < frame.width) {
                val x = (col + 0.5) / frame.width
                // Indexed rather than `windows.any { }`: that allocates an
                // iterator per *sampled pixel*, which at this stride is tens of
                // thousands per frame and about a million a second. It ran the
                // heap out of memory on the reference device and killed the tap
                // thread, taking the preview and the metering with it.
                var inWindow = false
                for (i in windows.indices) {
                    if (windows[i].contains(x, y)) {
                        inWindow = true
                        break
                    }
                }
                val luma = frame.sampler.lumaAt(col, row)
                bins[Histogram.binOf(luma)]++
                val w = if (inWindow) 1.0 else config.backgroundWeight
                if (w > 0.0) {
                    weightedLog += w * ln(luma)
                    weight += w
                }
                if (subject != null) {
                    if (subject.rect.contains(x, y)) {
                        if (x < subject.splitX) {
                            litLog += ln(luma)
                            litWeight += 1.0
                        } else {
                            shadowLog += ln(luma)
                            shadowWeight += 1.0
                        }
                    } else if (!inWindow) {
                        // Not the face, and not any other face either: the
                        // background the subject has to stand out from.
                        backgroundLog += ln(luma)
                        backgroundWeight += 1.0
                    }
                }
                col += stride
            }
            row += stride
        }

        return Metered(
            luma = if (weight == 0.0) 0.0 else exp(weightedLog / weight),
            histogram = Histogram(bins.toList()),
            // Named for the frame, not for the subject: "left" is the left of the
            // picture. Which side the key is on is a reading of these two, and it
            // is made in one place (PortraitLighting) rather than here.
            faceLeftLuma = mean(litLog, litWeight),
            faceRightLuma = mean(shadowLog, shadowWeight),
            backgroundLuma = mean(backgroundLog, backgroundWeight),
        )
    }

    private fun mean(log: Double, weight: Double): Double? =
        if (weight <= 0.0) null else exp(log / weight)
}

/**
 * The one face a lighting read is about, and where its two halves divide
 * (PRD 6.11).
 *
 * [splitX] is a frame coordinate rather than a fraction of [rect] because the
 * dividing line is the subject's nose, and a face turned three-quarters to camera
 * does not have its nose in the middle of its own bounding box. The camera layer
 * derives it from the eye landmarks where the device reports them and falls back
 * to the box's centre where it does not.
 */
data class Subject(val rect: FrameRect, val splitX: Double)

/** One metered frame: the number the loop acts on, and the shape of the picture it came from. */
data class Metered(
    val luma: Double,
    val histogram: Histogram,
    /** Geometric mean of the subject's left half of frame, or null if unmeasured. */
    val faceLeftLuma: Double? = null,
    /** Geometric mean of the subject's right half of frame, or null if unmeasured. */
    val faceRightLuma: Double? = null,
    /** Geometric mean of everything that is not a face, or null if unmeasured. */
    val backgroundLuma: Double? = null,
)

/**
 * Where a frame's sampled pixels fall on the tonal scale (PRD 6.8, #97).
 *
 * Gamma-encoded luma, not linear, because that is what a histogram means on
 * every camera anyone has used: equal bin widths are equal *perceived*
 * steps, so midtones get the space they deserve and a linear one would squeeze
 * everything a talking head cares about into the leftmost eighth.
 *
 * [BINS] is a compromise between resolution and the wire. 64 bins resolve a
 * quarter of a stop across the range, which is finer than anyone reads off a
 * 200 px wide drawing, and cost a few hundred bytes in a snapshot ADR-0007
 * already sends twice a second.
 */
data class Histogram(val bins: List<Int> = emptyList()) {

    /** True when there is a distribution to draw rather than nothing to say. */
    val measured: Boolean get() = bins.isNotEmpty()

    companion object {
        const val BINS: Int = 64

        /**
         * Which bin a gamma-encoded luma falls in.
         *
         * 1.0 belongs in the last bin rather than one past the end, which is the
         * off-by-one this is a named function to avoid -- and the value it
         * happens on, pure white, is the one a clipping indicator is entirely
         * about.
         */
        fun binOf(luma: Double): Int =
            (luma * BINS).toInt().coerceIn(0, BINS - 1)
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
