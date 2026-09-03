package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.exposure.MIXED_GRID_REGIONS
import com.scenaristo.camera.domain.exposure.gridFrequencyForRegion
import com.scenaristo.camera.domain.exposure.shutterLadder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GridFrequencyTest {

    // PRD 6.2: "Given the device region is Germany, when the app launches, then
    //           shutter defaults to 1/50 s and the UI reads '50 Hz'."
    @Test
    fun `PRD 6_2 - Germany defaults to 1 over 50 s and reads 50 Hz`() {
        val grid = gridFrequencyForRegion("DE")
        assertEquals(GridFrequency.HZ_50, grid)
        assertEquals(50, grid!!.hz)
        assertEquals(50, shutterLadder(grid).first())
    }

    // PRD 6.2: "Given the device region is the United States, then shutter
    //           defaults to 1/60 s and the UI reads '60 Hz'."
    @Test
    fun `PRD 6_2 - United States defaults to 1 over 60 s and reads 60 Hz`() {
        val grid = gridFrequencyForRegion("US")
        assertEquals(GridFrequency.HZ_60, grid)
        assertEquals(60, grid!!.hz)
        assertEquals(60, shutterLadder(grid).first())
    }

    // PRD 6.2: "Given the device region is Japan, then the UI shows the grid
    //           toggle prominently and the shutter follows the toggle."
    @Test
    fun `PRD 6_2 - Japan is a mixed-grid region so the toggle is shown`() {
        assertTrue("JP" in MIXED_GRID_REGIONS)
        // A majority default still exists, so the app is never without a shutter.
        assertEquals(GridFrequency.HZ_50, gridFrequencyForRegion("JP"))
    }

    // PRD 6.2 names Japan, Saudi Arabia and Brazil as the mixed-grid cases;
    // ADR-0010 action item 2 asks for all three to be covered.
    @Test
    fun `PRD 6_2 - Saudi Arabia and Brazil are mixed-grid regions`() {
        assertTrue("SA" in MIXED_GRID_REGIONS)
        assertTrue("BR" in MIXED_GRID_REGIONS)
        assertEquals(GridFrequency.HZ_60, gridFrequencyForRegion("SA"))
        assertEquals(GridFrequency.HZ_60, gridFrequencyForRegion("BR"))
    }

    // PRD 6.2 detection order is MCC, then region, then timezone. An unknown
    // region must not silently resolve; it has to fall through to the next source.
    @Test
    fun `PRD 6_2 - an unknown region does not resolve to a default`() {
        assertNull(gridFrequencyForRegion("ZZ"))
    }

    // PRD 6.3 / ADR-0005: overexposed at base ISO steps the shutter to the next
    // flicker-safe rung, and only one such rung exists before the app warns.
    @Test
    fun `PRD 6_3 - the ladder offers exactly one flicker-safe overexposure step`() {
        assertEquals(listOf(50, 100), shutterLadder(GridFrequency.HZ_50))
        assertEquals(listOf(60, 120), shutterLadder(GridFrequency.HZ_60))
    }
}
