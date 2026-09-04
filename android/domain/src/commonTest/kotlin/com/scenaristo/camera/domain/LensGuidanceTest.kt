package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.lens.Lens
import com.scenaristo.camera.domain.lens.LensAdvice
import com.scenaristo.camera.domain.lens.adviceFor
import com.scenaristo.camera.domain.lens.equivalentFocalLengthMm
import com.scenaristo.camera.domain.lens.recommendedForTalkingHead
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PRD 6.5's lens rules, which are two thresholds and one piece of arithmetic.
 *
 * The arithmetic is the part worth testing hardest: every threshold in 6.5 is
 * expressed in 35 mm-equivalent millimetres, so a wrong crop factor does not
 * produce a wrong number on screen — it produces the wrong *advice*, silently,
 * on the correct lens.
 */
class LensGuidanceTest {

    // PRD 6.5: "Given the active lens is 24 mm equivalent, then the distance
    //           guidance is visible on phone and web before recording starts."
    @Test
    fun `PRD 6_5 - a 24 mm equivalent lens asks the user to stand further back`() {
        assertEquals(LensAdvice.WIDE_DISTANCE_GUIDANCE, adviceFor(24))
    }

    // PRD 6.5: "Given the active lens is 77 mm equivalent, then no distance
    //           guidance is shown."
    @Test
    fun `PRD 6_5 - a 77 mm equivalent lens shows no distance guidance`() {
        assertEquals(LensAdvice.RECOMMENDED_FOR_TALKING_HEAD, adviceFor(77))
    }

    // PRD 6.5: "If the device has a longer lens (48 mm+ telephoto), list it as
    //           selectable and show 'Recommended for talking head'."
    @Test
    fun `PRD 6_5 - the recommendation starts at 48 mm and the guidance band ends at 25`() {
        assertEquals(LensAdvice.WIDE_DISTANCE_GUIDANCE, adviceFor(23))
        assertEquals(LensAdvice.WIDE_DISTANCE_GUIDANCE, adviceFor(25))
        assertEquals(LensAdvice.NONE, adviceFor(26))
        assertEquals(LensAdvice.NONE, adviceFor(47))
        assertEquals(LensAdvice.RECOMMENDED_FOR_TALKING_HEAD, adviceFor(48))
    }

    // The PRD's band is "23-25 mm (typical main and selfie cameras)", so an
    // ultrawide falls outside it and is told nothing -- despite distorting a face
    // more than the lens the guidance exists for. Asserted as the PRD states it,
    // and flagged rather than widened: where the band starts is Davide's call.
    @Test
    fun `PRD 6_5 - an ultrawide falls outside the band the PRD defines`() {
        assertEquals(LensAdvice.NONE, adviceFor(13))
    }

    // PRD 6.5: "Read the active lens's 35 mm-equivalent focal length (Android:
    //           LENS_INFO_AVAILABLE_FOCAL_LENGTHS with sensor physical size)."
    // A modern phone main camera: a ~1/1.3" sensor behind a 6.9 mm lens is the
    // 24 mm every maker quotes, and the number 6.5's own band is written around.
    @Test
    fun `PRD 6_5 - a main camera's physical focal length converts to the quoted 24 mm`() {
        assertEquals(24, equivalentFocalLengthMm(6.9, sensorWidthMm = 9.8, sensorHeightMm = 7.3))
    }

    // The same arithmetic on a telephoto: a 1/2.55" sensor behind a 12.5 mm lens
    // is the 77 mm the PRD names in its second acceptance criterion.
    @Test
    fun `PRD 6_5 - a telephoto converts to the quoted 77 mm`() {
        assertEquals(77, equivalentFocalLengthMm(12.5, sensorWidthMm = 5.6, sensorHeightMm = 4.2))
    }

    // A characteristics read that returns nothing useful must not become a
    // confident 0 mm lens with advice attached; every caller can tell 0 apart.
    @Test
    fun `an unreadable sensor size yields no focal length rather than a wrong one`() {
        assertEquals(0, equivalentFocalLengthMm(6.9, sensorWidthMm = 0.0, sensorHeightMm = 0.0))
        assertEquals(0, equivalentFocalLengthMm(0.0, sensorWidthMm = 9.8, sensorHeightMm = 7.3))
    }

    // PRD 6.5: "Each available lens is listed with its equivalent focal length",
    // and the point of listing them is to move the user off the wide one. Given
    // two that qualify, the longer flatters a face more.
    @Test
    fun `PRD 6_5 - the longest recommended lens is the one to steer towards`() {
        val lenses = listOf(
            Lens(id = "0", equivalentFocalLengthMm = 24),
            Lens(id = "2", equivalentFocalLengthMm = 48),
            Lens(id = "3", equivalentFocalLengthMm = 77),
        )
        assertEquals("3", lenses.recommendedForTalkingHead()?.id)
        assertEquals(LensAdvice.WIDE_DISTANCE_GUIDANCE, lenses.first().advice)
    }

    // A phone with only a wide camera -- which is most of them, and the Pixel 10's
    // own situation once ADR-0011 gates on MANUAL_SENSOR -- has nothing to steer
    // towards, and must say so rather than recommending the lens it warned about.
    @Test
    fun `PRD 6_5 - a phone with only a wide lens recommends nothing`() {
        val lenses = listOf(Lens(id = "0", equivalentFocalLengthMm = 24))
        assertNull(lenses.recommendedForTalkingHead())
    }
}
