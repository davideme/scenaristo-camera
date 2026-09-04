package com.scenaristo.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #20's remaining boxes. The failure these guard is a lens swap that quietly
 * resets exposure mid-take: a per-run verdict would average it away, and the
 * user would see a flicker the report called a pass.
 */
class LensSweepTest {

    private val request = ManualControls.Request(
        exposureTimeNs = 20_000_000,
        sensitivity = 100,
        frameDurationNs = 33_333_333,
    )

    private fun goodEchoes() = listOf(
        KeyEcho(ManualKey.SENSOR_EXPOSURE_TIME, 20_000_000, 20_000_000),
        KeyEcho(ManualKey.SENSOR_SENSITIVITY, 100, 100),
        KeyEcho(ManualKey.SENSOR_FRAME_DURATION, 33_333_333, 33_333_333),
        KeyEcho(ManualKey.CONTROL_AE_MODE, 0, 0),
        KeyEcho(ManualKey.CONTROL_AWB_MODE, 0, 0),
        KeyEcho(ManualKey.LENS_OPTICAL_STABILIZATION_MODE, 0, 0),
    )

    /** AE back on is the failure that matters: the app is no longer setting exposure. */
    private fun aeReEnabled() = goodEchoes().map {
        if (it.key == ManualKey.CONTROL_AE_MODE) KeyEcho(it.key, 0, 1) else it
    }

    @Test
    fun `the sweep covers below 1x and up to the maximum`() {
        val stops = LensSweep.stops(minRatio = 0.56f, maxRatio = 20f).map { it.ratio }
        assertEquals(0.56f, stops.first(), 0.001f)
        assertEquals(20f, stops.last(), 0.001f)
        assertTrue("1x must be a rung", stops.any { kotlin.math.abs(it - 1f) < 0.001f })
    }

    /** A camera that cannot zoom out still gets a sweep, without a rung below its own minimum. */
    @Test
    fun `a range starting at 1x yields no smaller rung`() {
        val stops = LensSweep.stops(minRatio = 1f, maxRatio = 8f).map { it.ratio }
        assertTrue(stops.all { it >= 1f })
        assertEquals(1f, stops.first(), 0.001f)
    }

    @Test
    fun `results are bucketed by the sensor the device reports`() {
        val acc = SweepAccumulator()
        acc.record("2", fallbackId = "0", zoomRatio = 0.6f, echoes = goodEchoes())
        acc.record("0", fallbackId = "0", zoomRatio = 1f, echoes = goodEchoes())
        acc.record("4", fallbackId = "0", zoomRatio = 8f, echoes = goodEchoes())

        val reports = acc.reports()
        assertEquals(3, reports.size)
        assertTrue(acc.sawMultipleLenses)
        assertTrue(reports.all { it.report.honoured })
    }

    /**
     * The point of bucketing: one bad sensor must not be hidden by the good ones
     * around it, and must not condemn them either.
     */
    @Test
    fun `a failure on one sensor stays on that sensor`() {
        val acc = SweepAccumulator()
        acc.record("0", fallbackId = "0", zoomRatio = 1f, echoes = goodEchoes())
        acc.record("4", fallbackId = "0", zoomRatio = 8f, echoes = aeReEnabled())
        acc.record("4", fallbackId = "0", zoomRatio = 8f, echoes = goodEchoes())

        val byId = acc.reports().associateBy { it.report.cameraId }
        assertTrue(byId.getValue("0").report.honoured)
        assertFalse("a later good frame must not clear an earlier failure",
                    byId.getValue("4").report.honoured)
    }

    /** A single-sensor camera reports no physical id, and that is not a failure. */
    @Test
    fun `frames without a physical id fall back to the logical camera`() {
        val acc = SweepAccumulator()
        acc.record(null, fallbackId = "1", zoomRatio = 1f, echoes = goodEchoes())

        val reports = acc.reports()
        assertEquals(1, reports.size)
        assertEquals("1", reports.single().report.cameraId)
        assertFalse(acc.sawMultipleLenses)
    }

    /** The lens name is the zoom range it served: the framework offers no display name. */
    @Test
    fun `the label names the zoom range rather than inventing a lens name`() {
        val acc = SweepAccumulator()
        acc.record("2", fallbackId = "0", zoomRatio = 0.6f, echoes = goodEchoes())
        acc.record("2", fallbackId = "0", zoomRatio = 1f, echoes = goodEchoes())

        val label = acc.reports().single().report.lensLabel
        assertTrue(label, "0.60x-1.00x" in label)
        assertTrue(label, "physical id 2" in label)
    }

    @Test
    fun `the markdown reports how many sensors served the sweep`() {
        val acc = SweepAccumulator()
        acc.record("2", fallbackId = "0", zoomRatio = 0.6f, echoes = goodEchoes())
        acc.record("4", fallbackId = "0", zoomRatio = 8f, echoes = goodEchoes())

        val text = acc.reports().markdown()
        assertTrue(text, "Sensors that served the sweep: 2" in text)
        assertTrue(text, "SENSOR_EXPOSURE_TIME" in text)
    }
}
