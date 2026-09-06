package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.exposure.FaceWeightedMeter
import com.scenaristo.camera.domain.exposure.Histogram
import com.scenaristo.camera.domain.exposure.LumaFrame
import com.scenaristo.camera.domain.exposure.LumaSampler
import com.scenaristo.camera.domain.exposure.MeteringConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The remote control's histogram (#97, PRD 6.8).
 *
 * It is built on the metering walk, so what matters is that sharing that walk
 * did not make either half wrong: the histogram must count every sampled pixel
 * once and unweighted, and the metered average must be exactly what it was
 * before the histogram existed.
 */
class HistogramTest {

    private fun flat(value: Double, width: Int = 64, height: Int = 64) =
        LumaFrame(width, height, LumaSampler { _, _ -> value })

    /** Stride 1, so "every sampled pixel" is every pixel and the counts are checkable. */
    private val everyPixel = MeteringConfig(sampleStride = 1)

    @Test
    fun `a flat frame lands entirely in one bin`() {
        val measured = FaceWeightedMeter(everyPixel).measure(flat(0.5))

        assertEquals(Histogram.BINS, measured.histogram.bins.size)
        assertEquals(64 * 64, measured.histogram.bins.sum(), "every sampled pixel is counted once")
        assertEquals(1, measured.histogram.bins.count { it > 0 }, "a flat frame is one bin")
        assertEquals(Histogram.binOf(0.5), measured.histogram.bins.indexOfFirst { it > 0 })
    }

    /**
     * Pure white is the value a clipping indicator is entirely about, and
     * `(1.0 * 64).toInt()` is 64 -- one past the end of a 64-bin array.
     */
    @Test
    fun `pure white is the last bin, not one past the end`() {
        assertEquals(Histogram.BINS - 1, Histogram.binOf(1.0))
        assertEquals(0, Histogram.binOf(0.0))
        assertTrue(Histogram.binOf(1.5) < Histogram.BINS, "a value above the scale is still in range")
        assertEquals(0, Histogram.binOf(-0.5), "and so is one below it")
    }

    /**
     * The histogram is not windowed. The metering average deliberately weighs
     * the centre 20x the surround, and a histogram that inherited that weighting
     * would hide the blown window behind the speaker -- which is the thing
     * anyone looks at a histogram to find.
     */
    @Test
    fun `the histogram is unweighted where the metered average is not`() {
        // Dark in the centre window, bright outside it.
        val frame = LumaFrame(100, 100, LumaSampler { x, y ->
            val inCentre = x in 30..69 && y in 20..79
            if (inCentre) 0.1 else 0.9
        })

        val measured = FaceWeightedMeter(everyPixel).measure(frame)
        val bins = measured.histogram.bins

        val dark = bins[Histogram.binOf(0.1)]
        val bright = bins[Histogram.binOf(0.9)]
        assertEquals(40 * 60, dark, "the centre window's own pixel count, unweighted")
        assertEquals(100 * 100 - 40 * 60, bright, "and everything else, also unweighted")

        // The metered average, by contrast, sits nearer the dark centre than a
        // pixel count would put it -- that is the weighting still working.
        assertTrue(
            measured.luma < 0.5,
            "the face-weighted average leans dark though most pixels are bright: ${measured.luma}",
        )
    }

    /** `meter` is what the loop calls, and it must not have changed. */
    @Test
    fun `meter agrees with measure`() {
        val meter = FaceWeightedMeter(everyPixel)
        val frame = LumaFrame(32, 32, LumaSampler { x, y -> ((x + y) % 16 + 1) / 20.0 })

        assertEquals(meter.measure(frame).luma, meter.meter(frame), absoluteTolerance = 1e-12)
    }

    @Test
    fun `an unmeasured histogram says so rather than reading as all-black`() {
        assertFalse(Histogram().measured)
        assertTrue(Histogram(List(Histogram.BINS) { 0 }).measured)
    }
}
