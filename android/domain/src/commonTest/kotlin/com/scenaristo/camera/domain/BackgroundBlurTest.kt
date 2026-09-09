package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.blur.BlurCapability
import com.scenaristo.camera.domain.blur.BlurVerdict
import com.scenaristo.camera.domain.blur.RecordingSize
import com.scenaristo.camera.domain.blur.bestBlurSize
import com.scenaristo.camera.domain.blur.blurVerdict
import com.scenaristo.camera.domain.blur.canBlur
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
        assertNull(bestBlurSize(stillsOnly))
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
    fun `PRD 6_10 - the best size that blurs is measured, not assumed`() {
        assertEquals(RecordingSize(3840, 2160), bestBlurSize(perfect))
        assertEquals(
            RecordingSize(1920, 1080),
            bestBlurSize(perfect.copy(maxWidthPx = 1920, maxHeightPx = 1080)),
        )
        // 720p blurs, but this app does not record there (PRD 6.10's fallback
        // stops at 1080p), so the device offers nothing rather than something
        // small.
        val hd = perfect.copy(maxWidthPx = 1280, maxHeightPx = 720)
        assertNull(bestBlurSize(hd))
        assertEquals(BlurVerdict.TOO_SMALL, blurVerdict(hd))
    }

    @Test
    fun `PRD 6_10 - a ceiling between two rungs takes the lower rung`() {
        // 2560x1440 holds 1080p and not UHD. The ladder is walked, not rounded.
        assertEquals(
            RecordingSize(1920, 1080),
            bestBlurSize(perfect.copy(maxWidthPx = 2560, maxHeightPx = 1440)),
        )
    }

    @Test
    fun `ADR-0031 - a ceiling wide enough but not tall enough does not count`() {
        // A device advertising 3840x1080 holds neither rung at UHD; taking the
        // width alone would record 2160 rows the HAL never promised to blur.
        assertEquals(
            RecordingSize(1920, 1080),
            bestBlurSize(perfect.copy(maxWidthPx = 3840, maxHeightPx = 1080)),
        )
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
