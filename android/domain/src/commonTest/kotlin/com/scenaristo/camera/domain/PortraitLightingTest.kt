package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.exposure.FrameRect
import com.scenaristo.camera.domain.exposure.FaceWeightedMeter
import com.scenaristo.camera.domain.exposure.LumaFrame
import com.scenaristo.camera.domain.exposure.LumaSampler
import com.scenaristo.camera.domain.exposure.Subject
import com.scenaristo.camera.domain.lighting.PortraitLighting
import com.scenaristo.camera.domain.lighting.PortraitLightingFilter
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic frames rather than device captures, because the question these
 * answer is arithmetic: given two halves of a face at a known ratio, does the
 * reading say that ratio. Whether the *face box* is in the right place is
 * `FaceMappingTest`'s question and was checked against the reference Pixel 10.
 *
 * Frames are painted in gamma-encoded luma, which is what `LumaScale` produces
 * and what the meter walks. Where a test wants "one stop darker" it encodes it,
 * because a stop is a doubling of *light* and the encoded values are not
 * proportional to light — that gap is the bug `stopsBetween` exists to avoid.
 */
class PortraitLightingTest {

    private val meter = FaceWeightedMeter()

    /** The subject fills the middle; the split is its centre. */
    private val face = FrameRect(left = 0.3, top = 0.2, right = 0.7, bottom = 0.8)
    private val subject = Subject(rect = face, splitX = 0.5)

    /**
     * A frame with a flat background and a face whose two halves differ.
     *
     * Values are encoded luma in 0..1; [background] is everything outside the
     * face rectangle.
     */
    private fun frame(left: Double, right: Double, background: Double): LumaFrame =
        LumaFrame(
            width = 200,
            height = 200,
            sampler = LumaSampler { x, y ->
                val nx = (x + 0.5) / 200.0
                val ny = (y + 0.5) / 200.0
                when {
                    !face.contains(nx, ny) -> background
                    nx < 0.5 -> left
                    else -> right
                }
            },
        )

    /** Encoded luma one that is [stops] brighter than [encoded], through Rec.709. */
    private fun brighterBy(encoded: Double, stops: Double): Double {
        val linear = if (encoded < 0.081) encoded / 4.5 else ((encoded + 0.099) / 1.099).pow(1 / 0.45)
        val scaled = linear * 2.0.pow(stops)
        return if (scaled < 0.018) scaled * 4.5 else 1.099 * scaled.pow(0.45) - 0.099
    }

    // PRD 6.11: the reading exists so a creator can be told the light is flat.
    // A face lit evenly is 1:1 and must read as such rather than as "about 2".
    @Test
    fun `PRD 6_11 - a flat-lit face reads 1 to 1`() {
        val reading = PortraitLighting.read(
            meter.measure(frame(left = 0.5, right = 0.5, background = 0.3), listOf(face), subject),
            iso = 100,
        )

        assertTrue(reading.measuring, "a face in frame reported nothing")
        assertEquals(10, reading.keyRatioTenths, "an evenly lit face did not read 1:1")
        assertFalse(reading.alreadyShaped, "flat light was reported as already shaped")
    }

    // The feature's own rule: if the face is already near 2:1, change nothing.
    @Test
    fun `PRD 6_11 - a face already at 2 to 1 is reported as already shaped`() {
        val shadow = 0.35
        val lit = brighterBy(shadow, 1.0) // one stop is 2:1 in light

        val reading = PortraitLighting.read(
            meter.measure(frame(left = lit, right = shadow, background = 0.2), listOf(face), subject),
            iso = 100,
        )

        assertTrue(reading.alreadyShaped, "a 2:1 face was not recognised, ratio=${reading.keyRatioTenths}")
        assertEquals(PortraitLighting.KeySide.LEFT, reading.keySide, "the key was found on the wrong side")
    }

    @Test
    fun `the key side names the brighter half of the frame`() {
        val shadow = 0.35
        val lit = brighterBy(shadow, 1.0)

        val fromRight = PortraitLighting.read(
            meter.measure(frame(left = shadow, right = lit, background = 0.2), listOf(face), subject),
            iso = 100,
        )

        assertEquals(PortraitLighting.KeySide.RIGHT, fromRight.keySide, "the key side did not follow the light")
    }

    /**
     * §2 of the research note: the background belongs one to two stops under the
     * face. This is the measurement that rule is read against.
     */
    @Test
    fun `PRD 6_11 - a background one stop down reads as one stop down`() {
        val faceLuma = 0.6
        val background = brighterBy(faceLuma, -1.0)

        val reading = PortraitLighting.read(
            meter.measure(frame(faceLuma, faceLuma, background), listOf(face), subject),
            iso = 100,
        )

        val stops = reading.backgroundStopsTenths
        assertNotNull(stops, "the background was not measured")
        assertTrue(
            stops in 8..12,
            "a background one stop down read as ${stops / 10.0} stops",
        )
        assertFalse(reading.subjectIsBacklit, "a darker background was called backlit")
    }

    /**
     * The common domestic failure, and the one worth detecting most: a window
     * behind the speaker inverts the relationship, and no key fixes it.
     */
    @Test
    fun `PRD 6_11 - a brighter background reads as backlit`() {
        val faceLuma = 0.35
        val background = brighterBy(faceLuma, 2.0)

        val reading = PortraitLighting.read(
            meter.measure(frame(faceLuma, faceLuma, background), listOf(face), subject),
            iso = 100,
        )

        assertTrue(reading.subjectIsBacklit, "a window behind the speaker was not reported")
        val stops = reading.backgroundStopsTenths
        assertNotNull(stops, "the background was not measured")
        assertTrue(stops < 0, "a brighter background reported a positive separation")
    }

    // PRD 6.3's refusal, applied here: no face means the meter used its centre
    // window, and a ratio across a fixed rectangle is a fact about a rectangle.
    @Test
    fun `PRD 6_3 - no face reports nothing rather than a plausible number`() {
        val reading = PortraitLighting.read(
            meter.measure(frame(0.5, 0.5, 0.3), faces = emptyList(), subject = null),
            iso = 100,
        )

        assertFalse(reading.measuring, "a reading was published with no face to take it from")
        assertEquals(0, reading.keyRatioTenths, "an unmeasured reading carried a ratio")
        assertNull(reading.backgroundStopsTenths, "an unmeasured reading carried a separation")
    }

    @Test
    fun `the enough-light gate follows ISO even when there is no face`() {
        val dark = PortraitLighting.read(
            meter.measure(frame(0.5, 0.5, 0.3), emptyList(), null),
            iso = 1600,
        )
        val lit = PortraitLighting.read(
            meter.measure(frame(0.5, 0.5, 0.3), emptyList(), null),
            iso = 200,
        )

        assertFalse(dark.enoughLight, "ISO 1600 was called enough light")
        assertTrue(lit.enoughLight, "ISO 200 was not called enough light")
    }

    @Test
    fun `ADR-0024 - a jittering ratio does not republish`() {
        val filter = PortraitLightingFilter()
        val first = PortraitLighting.Reading(
            measuring = true,
            keyRatioTenths = 20,
            keySide = PortraitLighting.KeySide.LEFT,
            backgroundStopsTenths = 12,
            enoughLight = true,
        )

        assertEquals(first, filter.accept(first), "the first reading was withheld")
        val jittered = first.copy(keyRatioTenths = 21, backgroundStopsTenths = 13)
        assertEquals(first, filter.accept(jittered), "a tenth of jitter advanced the reading")
    }

    @Test
    fun `ADR-0024 - a real move does republish`() {
        val filter = PortraitLightingFilter()
        val first = PortraitLighting.Reading(
            measuring = true,
            keyRatioTenths = 20,
            keySide = PortraitLighting.KeySide.LEFT,
            backgroundStopsTenths = 12,
            enoughLight = true,
        )
        filter.accept(first)

        val moved = first.copy(keyRatioTenths = 40)
        assertEquals(moved, filter.accept(moved), "a lamp moved and the reading did not")
    }

    @Test
    fun `losing the face clears the reading rather than freezing it`() {
        val filter = PortraitLightingFilter()
        filter.accept(
            PortraitLighting.Reading(
                measuring = true,
                keyRatioTenths = 20,
                keySide = PortraitLighting.KeySide.LEFT,
                backgroundStopsTenths = 12,
                enoughLight = true,
            ),
        )

        val gone = filter.accept(PortraitLighting.Reading.unmeasured(iso = 100))

        assertFalse(gone.measuring, "the reading kept measuring with no face")
        assertEquals(0, gone.keyRatioTenths, "a stale ratio outlived the face it came from")
        assertNull(gone.backgroundStopsTenths, "a stale separation outlived the face it came from")
    }

    @Test
    fun `the key side changing republishes even within the ratio deadband`() {
        val filter = PortraitLightingFilter()
        val left = PortraitLighting.Reading(
            measuring = true,
            keyRatioTenths = 20,
            keySide = PortraitLighting.KeySide.LEFT,
            backgroundStopsTenths = 12,
            enoughLight = true,
        )
        filter.accept(left)

        val right = left.copy(keySide = PortraitLighting.KeySide.RIGHT, keyRatioTenths = 21)

        assertEquals(
            PortraitLighting.KeySide.RIGHT,
            filter.accept(right).keySide,
            "the key moved to the other side of the face and the reading did not say so",
        )
    }
}
