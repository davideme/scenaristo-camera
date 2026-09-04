package com.scenaristo.camera.domain.lens

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Which lens to use, and how far to sit from it (PRD 6.5).
 *
 * A phone's wide main camera is the wrong lens for a face at desk distance: at
 * 24 mm equivalent, a head filling the frame is close enough that the nose is
 * measurably nearer the lens than the ears, and renders that way. The fix is
 * free — sit further back, or use a longer lens — but only if someone says so
 * before the take rather than after.
 *
 * In `:domain` because the thresholds are a product decision that both platforms
 * owe identically (ADR-0013), and because the arithmetic that turns a phone's
 * focal length into a number a photographer recognises is the same on both.
 * Where the inputs come from is not: Android reads
 * `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` with `SENSOR_INFO_PHYSICAL_SIZE`, iOS the
 * EXIF 35 mm focal length (PRD 6.5).
 *
 * No copy lives here. What the user is told is UI-5's to word and Davide's to
 * decide; this only says which of the three things is true of a lens.
 */
enum class LensAdvice {
    /**
     * Wide enough to distort a face at desk distance. PRD 6.5 makes this
     * guidance persistent rather than a warning: it is true of the lens for the
     * whole session, not of the moment.
     */
    WIDE_DISTANCE_GUIDANCE,

    /** Long enough to flatter a face. PRD 6.5 labels these in the lens list. */
    RECOMMENDED_FOR_TALKING_HEAD,

    /** Neither: usable, unremarkable, and nothing to say about it. */
    NONE,
}

/**
 * What PRD 6.5 says about a lens, from its 35 mm equivalent focal length.
 *
 * The bands are the PRD's own, quoted rather than interpreted: "23-25 mm
 * (typical main and selfie cameras)" gets the distance guidance, "48 mm+
 * telephoto" gets the recommendation.
 *
 * Note what falls between and outside them. A 35 mm equivalent gets neither,
 * which is right — it is mild enough not to warn about and short enough not to
 * recommend. An **ultrawide at 13 mm also gets neither**, which is not right and
 * is the PRD's gap rather than this function's: it distorts a face more than the
 * 24 mm lens the guidance exists for. Left as the PRD states it rather than
 * silently widened, because where the band starts is a product decision.
 */
fun adviceFor(equivalentFocalLengthMm: Int): LensAdvice = when {
    equivalentFocalLengthMm in WIDE_BAND -> LensAdvice.WIDE_DISTANCE_GUIDANCE
    equivalentFocalLengthMm >= RECOMMENDED_FROM -> LensAdvice.RECOMMENDED_FOR_TALKING_HEAD
    else -> LensAdvice.NONE
}

/**
 * A lens's focal length as a 35 mm-equivalent, which is the only focal length
 * worth showing anyone.
 *
 * A phone lens's actual focal length is a number like 6.9 mm, which means
 * nothing next to the field of view it produces; the equivalent is that number
 * scaled by how much smaller the sensor is than a frame of 35 mm film. Scaled by
 * the *diagonal*, because that is the convention every camera maker quotes and
 * the one PRD 6.5's "23-25 mm" is drawn from.
 *
 * [sensorWidthMm] and [sensorHeightMm] are the sensor's full physical size —
 * Android's `SENSOR_INFO_PHYSICAL_SIZE`. The recording is 16:9 out of a sensor
 * that usually is not, so the field of view actually recorded is narrower than
 * this number implies. That is true of every phone's quoted figure too, which is
 * why the quoted convention is the right one to match: the guidance has to agree
 * with what the user has read about their own phone.
 */
fun equivalentFocalLengthMm(
    focalLengthMm: Double,
    sensorWidthMm: Double,
    sensorHeightMm: Double,
): Int {
    val diagonal = sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm)
    if (diagonal <= 0.0 || focalLengthMm <= 0.0) return 0
    return (focalLengthMm * FULL_FRAME_DIAGONAL_MM / diagonal).roundToInt()
}

/**
 * One lens, as PRD 6.5 needs it listed: "Each available lens is listed with its
 * 35 mm-equivalent focal length."
 *
 * [id] is the platform's own camera id, the same one [com.scenaristo.camera.domain.protocol.CaptureSettings.lensId]
 * carries, so a lens the user picks in a list and a lens the phone is using are
 * the same thing without a lookup table in between.
 */
data class Lens(
    val id: String,
    val equivalentFocalLengthMm: Int,
) {
    val advice: LensAdvice get() = adviceFor(equivalentFocalLengthMm)
}

/**
 * The lens PRD 6.5 would have the user choose, or null when none is better than
 * what they have.
 *
 * The longest recommended lens rather than the first: given a 48 mm and a 77 mm,
 * the 77 flatters a face more, and PRD 6.5's whole reason for listing lenses is
 * to move people off the wide one.
 */
fun List<Lens>.recommendedForTalkingHead(): Lens? =
    filter { it.advice == LensAdvice.RECOMMENDED_FOR_TALKING_HEAD }
        .maxByOrNull { it.equivalentFocalLengthMm }

/** The diagonal of a 36 x 24 mm frame, which is what "35 mm equivalent" is equivalent to. */
private const val FULL_FRAME_DIAGONAL_MM = 43.266615305567875

/** PRD 6.5: "If the equivalent focal length is 23-25 mm ... show persistent guidance". */
private val WIDE_BAND = 23..25

/** PRD 6.5: "If the device has a longer lens (48 mm+ telephoto)". */
private const val RECOMMENDED_FROM = 48
