package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.exposure.GridSource
import com.scenaristo.camera.domain.exposure.resolveGrid
import com.scenaristo.camera.domain.exposure.shutterLadder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRD 6.2's detection chain and its override.
 *
 * The override half is #15, and its acceptance criteria are about what the user
 * is *told* as much as what the shutter does — so the source travels with the
 * answer and is asserted here alongside it.
 */
class GridResolutionTest {

    // PRD 6.2: "Determine the local grid frequency from, in order: SIM country
    //           code (MCC), then device region setting, then timezone."
    @Test
    fun `PRD 6_2 - the SIM decides before the device region, which decides before the timezone`() {
        // A US SIM in a phone set to German locale: the phone is in the US.
        val sim = resolveGrid(simRegion = "US", deviceRegion = "DE", timezoneRegion = "DE")
        assertEquals(GridFrequency.HZ_60, sim.grid)
        assertEquals(GridSource.SIM, sim.source)

        val region = resolveGrid(simRegion = null, deviceRegion = "DE", timezoneRegion = "US")
        assertEquals(GridFrequency.HZ_50, region.grid)
        assertEquals(GridSource.DEVICE_REGION, region.source)

        val zone = resolveGrid(timezoneRegion = "US")
        assertEquals(GridFrequency.HZ_60, zone.grid)
        assertEquals(GridSource.TIMEZONE, zone.source)
    }

    // A source that is present but unrecognised must not stop the chain: a
    // traveller with a foreign SIM the table does not list should still get
    // their device region's answer rather than the fallback.
    @Test
    fun `PRD 6_2 - an unrecognised source falls through to the next one`() {
        val resolved = resolveGrid(simRegion = "ZZ", deviceRegion = "US")
        assertEquals(GridFrequency.HZ_60, resolved.grid)
        assertEquals(GridSource.DEVICE_REGION, resolved.source)
        assertEquals("US", resolved.region)
    }

    // Issue #15: "Given any region, a manual grid override is available ... While
    //             the override is active, the UI shows that the grid was set
    //             manually rather than detected."
    @Test
    fun `PRD 6_2 - a manual override beats every detection and says that it did`() {
        val overridden = resolveGrid(override = GridFrequency.HZ_60, simRegion = "DE")

        assertEquals(GridFrequency.HZ_60, overridden.grid)
        assertEquals(GridSource.MANUAL_OVERRIDE, overridden.source)
        assertFalse(overridden.detected, "the UI has to be able to say 'set manually'")
        assertEquals(60, shutterLadder(overridden.grid).first(), "the shutter follows the override")
    }

    // PRD 6.2: "Given the device region is Japan, then the UI shows the grid
    //           toggle prominently and the shutter follows the toggle."
    @Test
    fun `PRD 6_2 - Japan gets the prominent toggle and the shutter follows it`() {
        val detected = resolveGrid(simRegion = "JP")
        assertTrue(detected.prominentToggle)
        assertEquals(GridFrequency.HZ_50, detected.grid, "the majority default, until told otherwise")
        assertEquals(50, shutterLadder(detected.grid).first())

        val corrected = resolveGrid(override = GridFrequency.HZ_60, simRegion = "JP")
        assertEquals(60, shutterLadder(corrected.grid).first())
        assertTrue(
            corrected.prominentToggle,
            "still in Japan: the control they just used is the one they may need again",
        )
    }

    // The override must not cost the user the reason the toggle was prominent.
    // A country is a fact about where the phone is, not about what was pressed.
    @Test
    fun `PRD 6_2 - an override keeps the region that made the toggle prominent`() {
        assertEquals("JP", resolveGrid(override = GridFrequency.HZ_60, deviceRegion = "JP").region)
        assertFalse(resolveGrid(override = GridFrequency.HZ_50, deviceRegion = "DE").prominentToggle)
    }

    // Nothing resolved -- no SIM, an unlisted region, an unmapped timezone. The
    // app still has to pick a shutter, and it must be able to say that it
    // guessed. 50 Hz is a product choice, not an arithmetic one: both wrong
    // guesses band, and this is only the one that is wrong less often.
    @Test
    fun `PRD 6_2 - an unresolvable device still gets a shutter, and admits it guessed`() {
        val fallback = resolveGrid()
        assertEquals(GridFrequency.HZ_50, fallback.grid)
        assertEquals(GridSource.FALLBACK, fallback.source)
        assertEquals(null, fallback.region)
        assertFalse(fallback.prominentToggle)

        assertEquals(
            GridFrequency.HZ_60,
            resolveGrid(fallback = GridFrequency.HZ_60).grid,
            "the guess is a parameter, so a later decision changes one call site",
        )
    }
}
