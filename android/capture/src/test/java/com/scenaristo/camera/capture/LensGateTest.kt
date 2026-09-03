package com.scenaristo.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensGateTest {

    private fun lens(manualSensor: Boolean, manualPostProcessing: Boolean) =
        LensCapabilities("0", manualSensor, manualPostProcessing, supportsUhd30 = true)

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
}
