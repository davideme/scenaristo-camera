package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.lighting.LookShape
import com.scenaristo.camera.domain.lighting.PortraitLighting
import com.scenaristo.camera.domain.protocol.StudioLook
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The look's arithmetic, which is the half both platforms must agree on
 * (ADR-0013). Whether a shader draws it convincingly is not testable here and is
 * not claimed to be.
 */
class LookShapeTest {

    private val rembrandt = LookShape.of(StudioLook.REMBRANDT)!!
    private val clamshell = LookShape.of(StudioLook.CLAMSHELL)!!

    @Test
    fun `PRD 6_11 - OFF has no shape at all`() {
        assertNull(LookShape.of(StudioLook.OFF), "OFF produced parameters to apply")
    }

    @Test
    fun `both looks exist and differ in where the light comes from`() {
        assertNotNull(LookShape.of(StudioLook.REMBRANDT), "Rembrandt has no parameters")
        assertNotNull(LookShape.of(StudioLook.CLAMSHELL), "Clamshell has no parameters")
        assertTrue(
            rembrandt.horizontal > clamshell.horizontal,
            "Rembrandt should sit further round than Clamshell",
        )
        assertTrue(
            rembrandt.vertical > 0 && clamshell.vertical < 0,
            "Rembrandt lights from above and Clamshell lifts from below; the signs disagree",
        )
    }

    /**
     * The feature's own rule, and the reason it is one: a face already near 2:1
     * is left alone. Shaping it further would be the app overriding a decision
     * somebody made with a lamp.
     */
    @Test
    fun `PRD 6_11 - a face already at the target is left alone`() {
        assertEquals(
            1.0,
            LookShape.ratioToApply(rembrandt.targetRatioTenths, rembrandt),
            1e-9,
            "a face already at the target was still corrected",
        )
        assertEquals(
            1.0,
            LookShape.ratioToApply(rembrandt.targetRatioTenths + LookShape.ALREADY_SHAPED_TENTHS, rembrandt),
            1e-9,
            "a face inside the already-shaped window was corrected",
        )
    }

    /**
     * The same window `PortraitLighting.Reading.alreadyShaped` uses. Two constants
     * would let a reading say "already shaped" while the look shaped it anyway,
     * which is the product disagreeing with itself.
     */
    @Test
    fun `the already-shaped window is the one the reading uses`() {
        val reading = PortraitLighting.Reading(
            measuring = true,
            keyRatioTenths = PortraitLighting.TARGET_KEY_RATIO_TENTHS + LookShape.ALREADY_SHAPED_TENTHS,
        )

        assertTrue(reading.alreadyShaped, "the reading and the look disagree about what is already shaped")
        assertEquals(
            1.0,
            LookShape.ratioToApply(reading.keyRatioTenths, rembrandt),
            1e-9,
            "the look corrected a face its own reading calls already shaped",
        )
    }

    @Test
    fun `PRD 6_11 - a flat face is corrected toward the target`() {
        val correction = LookShape.ratioToApply(measuredTenths = 10, parameters = rembrandt)

        assertTrue(correction > 1.0, "a 1:1 face was not corrected at all")
        assertEquals(2.0, correction, 1e-9, "a 1:1 face asked for 2:1 should need exactly 2x")
    }

    /**
     * A reading of 0.2:1 is not a lit face; it is a measurement error or a face
     * half out of frame. Without the cap it would ask for a gain that turns one
     * cheek white.
     */
    @Test
    fun `an absurd reading is capped rather than obeyed`() {
        val correction = LookShape.ratioToApply(measuredTenths = 2, parameters = rembrandt)

        assertEquals(LookShape.MAX_CORRECTION, correction, 1e-9, "an absurd reading was obeyed")
    }

    @Test
    fun `an unmeasured reading asks for no correction`() {
        assertEquals(1.0, LookShape.ratioToApply(0, rembrandt), 1e-9, "an unmeasured face was corrected")
    }

    /**
     * §4 of the research note: a look redistributes and never adds. If the mean
     * gain across a symmetric face were not 1.0, the look would quietly change the
     * exposure the ADR-0005 loop settled on, and the histogram the user is looking
     * at would stop meaning what it says.
     */
    @Test
    fun `a look costs no exposure - the gain is centred on 1`() {
        val ratio = 2.0
        val litEdge = LookShape.gainAt(1.0, rembrandt, ratio)
        val shadowEdge = LookShape.gainAt(-1.0, rembrandt, ratio)
        val middle = LookShape.gainAt(0.0, rembrandt, ratio)

        assertEquals(1.0, middle, 1e-9, "the centre of the face was not left alone")
        assertEquals(
            1.0,
            litEdge * shadowEdge,
            1e-9,
            "the two edges do not cancel, so the look changes the overall exposure",
        )
        assertTrue(litEdge > 1.0 && shadowEdge < 1.0, "the gradient runs the wrong way")
    }

    @Test
    fun `the lit and shadow edges are the requested ratio apart`() {
        val ratio = 2.0
        val lit = LookShape.gainAt(1.0, rembrandt, ratio)
        val shadow = LookShape.gainAt(-1.0, rembrandt, ratio)

        assertEquals(ratio, lit / shadow, 1e-9, "the applied ratio is not the one asked for")
    }

    @Test
    fun `a ratio of 1 leaves every point untouched`() {
        for (across in listOf(-1.0, -0.5, 0.0, 0.37, 1.0)) {
            assertEquals(
                1.0,
                LookShape.gainAt(across, clamshell, ratio = 1.0),
                1e-9,
                "a look asked for no correction still moved the pixel at $across",
            )
        }
    }

    /**
     * A straight ramp across a face reads as a printed gradient; the exponent is
     * what makes it look like falloff. Clamshell's is the tighter of the two, so
     * at the same point it should have moved less.
     */
    @Test
    fun `falloff is a curve, not a ramp`() {
        val halfway = LookShape.gainAt(0.5, rembrandt, ratio = 2.0)
        val linear = LookShape.gainAt(1.0, rembrandt, ratio = 2.0)

        assertTrue(
            abs(halfway - 1.0) < abs(linear - 1.0) / 2,
            "halfway across the face the gain is at least half of the edge's, which is a ramp",
        )
        assertTrue(
            abs(LookShape.gainAt(0.5, clamshell, 2.0) - 1.0) <
                abs(LookShape.gainAt(0.5, rembrandt, 2.0) - 1.0),
            "Clamshell's tighter falloff should move less at the same point",
        )
    }
}
