package com.scenaristo.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensGateTest {

    private fun lens(
        manualSensor: Boolean,
        manualPostProcessing: Boolean,
        uhd30: Boolean = true,
        id: String = "0",
    ) = LensCapabilities(id, manualSensor, manualPostProcessing, supportsUhd30 = uhd30)

    // PRD 8 Open Question 1, answered by ADR-0011: a lens without MANUAL_SENSOR
    // cannot hold a fixed shutter, so recording on it is refused outright.
    @Test
    fun `PRD 8-Q1 - a lens without MANUAL_SENSOR cannot record`() {
        assertFalse(LensGate.canRecord(lens(manualSensor = false, manualPostProcessing = true)))
    }

    @Test
    fun `PRD 8-Q1 - a lens with MANUAL_SENSOR can record`() {
        assertTrue(LensGate.canRecord(lens(manualSensor = true, manualPostProcessing = false)))
    }

    // PRD 6.4 / ADR-0011: missing MANUAL_POST_PROCESSING degrades white balance
    // to locked presets; it does not disqualify the lens. This is the case a
    // Samsung secondary lens is expected to hit.
    @Test
    fun `PRD 6_4 - without MANUAL_POST_PROCESSING white balance degrades but still records`() {
        val caps = lens(manualSensor = true, manualPostProcessing = false)
        assertTrue(LensGate.canRecord(caps))
        assertEquals(WhiteBalanceMode.LOCKED_PRESET, LensGate.whiteBalanceMode(caps))
    }

    @Test
    fun `PRD 6_4 - with MANUAL_POST_PROCESSING white balance uses gains`() {
        val caps = lens(manualSensor = true, manualPostProcessing = true)
        assertEquals(WhiteBalanceMode.GAINS, LensGate.whiteBalanceMode(caps))
    }

    // PRD 6.10: "Given the device cannot do 4K at 30 fps, the app falls back to
    //            1080p and says so before recording."
    // A fallback, not a refusal -- unlike the MANUAL_SENSOR gate above. 1080p at
    // a flicker-free shutter is still the product; 4K with bands is not.
    @Test
    fun `PRD 6_10 - a lens without 4K30 falls back to 1080p rather than refusing`() {
        val caps = lens(manualSensor = true, manualPostProcessing = true, uhd30 = false)
        assertTrue(LensGate.canRecord(caps))
        assertEquals(Resolution.FHD, LensGate.resolutionFor(caps))
        assertEquals(Resolution.UHD, LensGate.resolutionFor(lens(true, true)))
        assertTrue(
            "the fallback has to be said before recording, so it is in the report",
            LensGate.report(caps, "Main", hardwareHevc = true).line().contains("1080p only"),
        )
    }

    // PRD 8-Q1 / ADR-0011: "refuse to record on that lens and steer the user to
    // one that supports it". Refusing is only half an answer without a
    // destination.
    @Test
    fun `PRD 8-Q1 - the user is steered to the first lens that can record`() {
        val lenses = listOf(
            lens(manualSensor = false, manualPostProcessing = true, id = "2"),
            lens(manualSensor = true, manualPostProcessing = false, id = "0"),
        )
        assertEquals("0", LensGate.steerTo(lenses)?.cameraId)
    }

    // A phone where no lens can hold a shutter is not a phone with three bad
    // lenses; it is a phone this app does not run on, and it needs telling once.
    @Test
    fun `PRD 6_10 - a device with no recordable lens says so once, not per lens`() {
        val lenses = listOf(
            lens(manualSensor = false, manualPostProcessing = true, id = "0"),
            lens(manualSensor = false, manualPostProcessing = false, id = "2"),
        )
        assertNull(LensGate.steerTo(lenses))

        val report = lenses
            .map { LensGate.report(it, "Lens ${it.cameraId}", hardwareHevc = false) }
            .report()
        assertTrue(report.startsWith("This phone cannot record with locked settings"))
    }

    // PRD 6.10's own example: "Main camera: 4K30, manual shutter, manual WB
    // approximated, HEVC. Ultrawide: manual shutter crossed."
    @Test
    fun `PRD 6_10 - the report reads per lens, in the PRD's own terms`() {
        val main = LensGate.report(
            lens(manualSensor = true, manualPostProcessing = false),
            label = "Main",
            hardwareHevc = true,
        )
        assertEquals("Main: 4K30 ok, manual shutter ok, manual WB approximated, HEVC ok", main.line())

        val ultrawide = LensGate.report(
            lens(manualSensor = false, manualPostProcessing = true, id = "2"),
            label = "Ultrawide",
            hardwareHevc = true,
        )
        assertEquals("Ultrawide: cannot record - no manual shutter on this lens", ultrawide.line())
    }

    // The encoder belongs to the device, not to a lens, so a phone without
    // hardware HEVC says H.264 on every row rather than leaving the column out.
    @Test
    fun `PRD 6_10 - a device without hardware HEVC reports H_264 on every lens`() {
        val report = LensGate.report(lens(true, true), "Main", hardwareHevc = false)
        assertTrue(report.line().endsWith("H.264 only"))
    }
}
