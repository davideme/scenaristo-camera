package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.blur.BlurCapability
import com.scenaristo.camera.domain.blur.BlurVerdict
import com.scenaristo.camera.domain.blur.advertisedCeiling
import com.scenaristo.camera.domain.blur.blurLine
import com.scenaristo.camera.domain.blur.blurVerdict
import com.scenaristo.camera.domain.blur.canBlur
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gating rule for background blur (ADR-0031).
 *
 * Every case here is a device this rule has to refuse or allow without one
 * being present, which is the point of keeping the rule pure: the Pixel 10 is
 * one row of this table and Phase 4's iPhone will be another.
 */
class BackgroundBlurTest {

    /** A device that passes every check, so each test can break exactly one. */
    private val perfect = BlurCapability(
        advertised = true,
        continuousMode = true,
        maxWidthPx = 3840,
        maxHeightPx = 2160,
        manualKeysHeld = true,
        frameRateHeld = true,
    )

    @Test
    fun `ADR-0031 - a camera that advertises no streaming scene mode cannot blur`() {
        assertEquals(BlurVerdict.NOT_ADVERTISED, blurVerdict(BlurCapability()))
        assertFalse(canBlur(BlurCapability()))
    }

    @Test
    fun `ADR-0031 - still-capture bokeh without a continuous mode is not blur on a recording`() {
        // The device advertises a mode and would blur a photograph. ADR-0002:
        // "we never use ImageCapture", so there is no photograph to blur.
        val stillsOnly = perfect.copy(continuousMode = false)
        assertEquals(BlurVerdict.NO_CONTINUOUS_MODE, blurVerdict(stillsOnly))
        assertNull(advertisedCeiling(stillsOnly))
    }

    @Test
    fun `ADR-0031 - blur is refused when the manual keys stop echoing`() {
        // Davide, 2026-09-09: manual exposure wins. A device can advertise the
        // mode, bind it at UHD and blur beautifully, and still be refused --
        // which is why this outranks an otherwise perfect capability.
        assertEquals(BlurVerdict.MANUAL_KEYS_LOST, blurVerdict(perfect.copy(manualKeysHeld = false)))
    }

    @Test
    fun `PRD 6_1 - blur is refused when 30_00 fps is not held`() {
        assertEquals(BlurVerdict.FRAME_RATE_LOST, blurVerdict(perfect.copy(frameRateHeld = false)))
    }

    @Test
    fun `ADR-0031 - the advertised ceiling is reported and never gates the verdict`() {
        // Davide, 2026-09-09: trust the measurement, not the vendor's ceiling.
        // The reference Pixel 10 advertises 1920x1080 and was measured applying
        // blur at 3840x2160, so a ceiling below what the app records is not a
        // reason to refuse and not a reason to record smaller.
        val lowCeiling = perfect.copy(maxWidthPx = 1920, maxHeightPx = 1080)
        assertEquals(BlurVerdict.SUPPORTED, blurVerdict(lowCeiling))
        assertTrue(canBlur(lowCeiling))
        assertEquals("1920x1080", advertisedCeiling(lowCeiling))
    }

    @Test
    fun `ADR-0031 - even a ceiling far below what the app records still supports blur`() {
        // The rule has no resolution in it at all any more. If a device like this
        // turns out not to blur what it records, that shows up as a measurement
        // -- manualKeysHeld and frameRateHeld are the only things a run can set.
        val tiny = perfect.copy(maxWidthPx = 640, maxHeightPx = 480)
        assertEquals(BlurVerdict.SUPPORTED, blurVerdict(tiny))
        assertEquals("640x480", advertisedCeiling(tiny))
    }

    @Test
    fun `PRD 6_1 - blur costs no resolution, so the report does not name one`() {
        // The sentence PRD 6.10's report shows. It used to end "ok at 1920x1080",
        // which would have been the 4K trade leaking into the copy.
        assertEquals("background blur: ok", blurLine(perfect))
    }

    @Test
    fun `ADR-0031 - the checks are ordered, so the first wall hit is the reason given`() {
        // A device that fails everything reports the cheapest true reason rather
        // than the most alarming one: PRD 6.10 labels controls with why, and
        // "no manual shutter" would be a lie about a camera that has no mode.
        assertEquals(
            BlurVerdict.NOT_ADVERTISED,
            blurVerdict(BlurCapability(manualKeysHeld = false, frameRateHeld = false)),
        )
    }

    @Test
    fun `ADR-0007 - capabilities default to unprobed, so blur reads false before anything measured it`() {
        // Not having looked is not the same as having looked and found nothing,
        // and only one of them may reach a user as an offer.
        assertFalse(canBlur(BlurCapability()))
        assertFalse(canBlur(BlurCapability(advertised = true, continuousMode = true, maxWidthPx = 3840, maxHeightPx = 2160)))
    }
}
