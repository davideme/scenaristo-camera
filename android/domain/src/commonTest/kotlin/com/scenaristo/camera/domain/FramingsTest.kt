package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.lens.LensAdvice
import com.scenaristo.camera.domain.lens.adviceFor
import com.scenaristo.camera.domain.lens.framingsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRD 6.5's lens list, as the zoom ratios a phone actually offers (#77).
 *
 * The reference device's numbers are the worked example: a 24 mm-equivalent base
 * lens, a zoom range starting below 1x for the ultrawide.
 */
class FramingsTest {

    @Test
    fun `a Pixel-shaped device offers ultrawide, base, and two longer framings`() {
        val framings = framingsFor(minZoomRatio = 0.5, maxZoomRatio = 30.0, baseEquivalentFocalLengthMm = 24)

        assertEquals(listOf(0.5, 1.0, 2.0, 5.0), framings.map { it.zoomRatio })
        assertEquals(listOf(12, 24, 48, 120), framings.map { it.equivalentFocalLengthMm })
    }

    /**
     * The point of the list, per PRD 6.5: it exists to move people off the wide
     * lens, and a 24 mm base only reaches "recommended for talking head" by
     * zooming.
     */
    @Test
    fun `zooming is what reaches PRD 6_5's recommended band`() {
        val framings = framingsFor(0.5, 30.0, 24)

        assertEquals(LensAdvice.WIDE_DISTANCE_GUIDANCE, adviceFor(framings.first { it.zoomRatio == 1.0 }.equivalentFocalLengthMm))
        assertEquals(LensAdvice.RECOMMENDED_FOR_TALKING_HEAD, adviceFor(framings.first { it.zoomRatio == 2.0 }.equivalentFocalLengthMm))
        assertEquals(LensAdvice.RECOMMENDED_FOR_TALKING_HEAD, adviceFor(framings.first { it.zoomRatio == 5.0 }.equivalentFocalLengthMm))
    }

    /** A device with no zoom at all still offers the lens it has, and only that. */
    @Test
    fun `a fixed-ratio device offers one framing`() {
        assertEquals(listOf(1.0), framingsFor(1.0, 1.0, 26).map { it.zoomRatio })
    }

    /** Nothing beyond the device's range is ever offered. */
    @Test
    fun `the ladder is clamped to what the device reports`() {
        val framings = framingsFor(minZoomRatio = 1.0, maxZoomRatio = 3.0, baseEquivalentFocalLengthMm = 24)
        assertEquals(listOf(1.0, 2.0), framings.map { it.zoomRatio })
        assertTrue(framings.none { it.zoomRatio > 3.0 })
    }

    /**
     * Past the longest real lens a phone has, more zoom is a crop of a sensor
     * already cropped to 16:9 at 4K. Offering it as a "lens" would be offering a
     * softer picture as a choice.
     */
    @Test
    fun `nothing beyond 5x is offered however far the device zooms`() {
        assertEquals(5.0, framingsFor(0.5, 100.0, 24).map { it.zoomRatio }.max())
    }

    /** A device that has not reported yet has nothing to offer, rather than a wrong guess. */
    @Test
    fun `an unprobed lens offers nothing`() {
        assertTrue(framingsFor(0.5, 30.0, baseEquivalentFocalLengthMm = 0).isEmpty())
    }
}
