package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.whitebalance.AwbApproximation
import com.scenaristo.camera.domain.whitebalance.DEFAULT_KELVIN
import com.scenaristo.camera.domain.whitebalance.LightScenario
import com.scenaristo.camera.domain.whitebalance.TINT
import com.scenaristo.camera.domain.whitebalance.approximationFor
import com.scenaristo.camera.domain.whitebalance.presetsFor
import com.scenaristo.camera.domain.whitebalance.settingFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRD 6.4's white balance: two short lists, one default, and an honest answer
 * on a lens that cannot do it exactly.
 */
class WhiteBalancePresetsTest {

    // PRD 6.4: "Two scenarios, each with three Kelvin presets ... Natural light
    //           present: 4500, 5600, 6500. Artificial light only: 3200, 4500,
    //           5600. Default 5600 in both."
    @Test
    fun `PRD 6_4 - each scenario offers its three presets and both default to 5600 K`() {
        assertEquals(listOf(4500, 5600, 6500), presetsFor(LightScenario.NATURAL_LIGHT))
        assertEquals(listOf(3200, 4500, 5600), presetsFor(LightScenario.ARTIFICIAL_LIGHT))
        assertEquals(5600, DEFAULT_KELVIN)
        assertTrue(LightScenario.entries.all { DEFAULT_KELVIN in presetsFor(it) })
    }

    // PRD 6.1's capture default is "Locked preset, default 5600 K", and PRD 6.4
    // fixes tint at 0 for v1. Both are one number, and both are worth pinning:
    // they are what a fresh install records with.
    @Test
    fun `PRD 6_1 - the capture default is 5600 K with no tint`() {
        assertEquals(5600, DEFAULT_KELVIN)
        assertEquals(0, TINT)
    }

    // PRD 6.4: "Android note: ... falling back to the platform AWB modes
    //           (INCANDESCENT ~ 3000 K, FLUORESCENT ~ 4000 K, DAYLIGHT ~ 5500 K,
    //           CLOUDY ~ 6500 K) on devices that do not support manual colour
    //           gains."
    @Test
    fun `PRD 6_4 - every preset maps to the nearest platform mode`() {
        assertEquals(AwbApproximation.INCANDESCENT, approximationFor(3200))
        assertEquals(AwbApproximation.FLUORESCENT, approximationFor(4500))
        assertEquals(AwbApproximation.DAYLIGHT, approximationFor(5600))
        assertEquals(AwbApproximation.CLOUDY, approximationFor(6500))
    }

    // Nearest in mired, not in Kelvin, and there are temperatures where the two
    // metrics genuinely disagree. Mired is the one to trust: it is roughly
    // linear in how different two whites look, which is why lighting gels are
    // graded in it and why nearest-in-Kelvin picks wrong near a boundary.
    @Test
    fun `PRD 6_4 - presets resolve by perceived difference and not by Kelvin distance`() {
        // 3450 K is 450 K from INCANDESCENT and 550 K from FLUORESCENT, so
        // nearest-in-Kelvin would say INCANDESCENT. In mired it is 289.9,
        // against 333.3 and 250.0 -- nearer FLUORESCENT, and that is the mode a
        // viewer would agree with.
        assertEquals(AwbApproximation.FLUORESCENT, approximationFor(3450))
        // The same disagreement at the cool end: 5980 K is nearer DAYLIGHT in
        // Kelvin and nearer CLOUDY in mired.
        assertEquals(AwbApproximation.CLOUDY, approximationFor(5980))
        // Well outside the modes there is nothing to disagree about.
        assertEquals(AwbApproximation.INCANDESCENT, approximationFor(2800))
        assertEquals(AwbApproximation.CLOUDY, approximationFor(9000))
    }

    // PRD 6.4: "Given a device without manual WB gains, then the app shows which
    //           preset is approximated and by which platform mode."
    // ADR-0011: missing MANUAL_POST_PROCESSING degrades white balance; it does
    // not disqualify the lens the way a missing MANUAL_SENSOR does.
    @Test
    fun `PRD 6_4 - a lens without manual gains says which mode is standing in`() {
        val degraded = settingFor(kelvin = 3200, hasManualGains = false)
        assertFalse(degraded.exact)
        assertEquals(AwbApproximation.INCANDESCENT, degraded.approximatedBy)
        // The preset the user chose is still what is reported: they asked for
        // 3200 K and the UI owes them that number, plus how it is being reached.
        assertEquals(3200, degraded.kelvin)
    }

    @Test
    fun `PRD 6_4 - a lens with manual gains applies the preset and names no mode`() {
        val exact = settingFor(kelvin = 3200, hasManualGains = true)
        assertTrue(exact.exact)
        assertNull(exact.approximatedBy)
        assertEquals(3200, exact.kelvin)
    }
}
