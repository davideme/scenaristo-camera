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

/**
 * PRD 6.5: "If the equivalent focal length is 23-25 mm ... show persistent guidance".
 *
 * Public because the remote control applies the same rule to the same number,
 * and it is generated into `web/src/protocol.ts` rather than written down twice
 * (ADR-0009). A browser deciding at 26 mm what the phone decides at 25 is two
 * surfaces disagreeing about the shot in front of them.
 */
val WIDE_BAND = 23..25

/** PRD 6.5: "If the device has a longer lens (48 mm+ telephoto)". */
const val RECOMMENDED_FROM = 48

/**
 * The T-stop the app shows beside the f/-number (PRD 6.8; Davide, 2026-09-06).
 *
 * **This is a stated assumption, not a measurement, and the interface says so by
 * showing both numbers.** A real T-stop is the f/-number corrected for how much
 * light the glass actually passes, and no phone reports its own transmission:
 * Android offers `LENS_INFO_AVAILABLE_APERTURES` and nothing about efficiency.
 * Measuring it would mean a grey card at a known illuminance, per lens, per
 * device — the method #24 used for the Kelvin curve — and the result would be a
 * fact about one Pixel 10 rather than about phones (ADR-0017).
 *
 * So the app assumes [TRANSMISSION] and is honest about it. That is a defensible
 * thing to draw *only* because the f/-number is drawn next to it: a reader who
 * knows what a T-stop is can see the assumption in the gap between the two, and
 * a reader who does not is looking at a number labelled informational either
 * way. It is not defensible on its own, which is why nothing here returns a
 * T-stop without the f/-number that produced it.
 */
object TStop {

    /**
     * Assumed light transmission through the lens (Davide, 2026-09-06).
     *
     * 92 % is the neighbourhood of a modern coated multi-element phone lens and
     * costs about a sixth of a stop: `log2(1 / 0.92)` is 0.12 EV. Not measured
     * on any device in this project's matrix.
     */
    const val TRANSMISSION: Double = 0.92

    /**
     * `T = N / sqrt(transmission)`, the standard definition.
     *
     * A stop is a ratio of *areas*, and transmission scales the light passing an
     * area — so the correction goes under a square root. Applying it linearly is
     * the usual mistake and would double the error it is trying to describe.
     */
    fun of(fNumber: Double, transmission: Double = TRANSMISSION): Double? {
        if (fNumber <= 0.0 || transmission <= 0.0 || transmission > 1.0) return null
        return fNumber / kotlin.math.sqrt(transmission)
    }
}

/**
 * The framings to offer for a device's zoom range (PRD 6.5, #77).
 *
 * A phone's other lenses are reached by zoom ratio rather than by camera id
 * (Davide, 2026-09-06), so "which lenses does this device have" becomes "which
 * ratios are worth putting in front of someone". The answer here is a small
 * ladder rather than the whole range: a slider from 0.5x to 30x is the stock
 * camera app's answer and PRD section 1 is a rejection of that.
 *
 * The ladder is [LADDER], clamped to what the device reports, with the device's
 * own minimum always included -- that is where an ultrawide lives when there is
 * one, and it is a real optic on every phone that has it.
 *
 * **What this deliberately does not claim is which ratios are optical.** CameraX
 * 1.6.2 will not say where the physical sensors hand over; `LensSweep` finds out
 * empirically by reading the sensor id off capture results, and that takes a
 * sweep nobody wants to run at every launch. So each choice is labelled by the
 * field of view it produces, which is true whether glass or a crop delivers it,
 * and is also the number PRD 6.5's guidance is written in.
 */
fun framingsFor(
    minZoomRatio: Double,
    maxZoomRatio: Double,
    baseEquivalentFocalLengthMm: Int,
): List<com.scenaristo.camera.domain.protocol.LensChoice> {
    if (baseEquivalentFocalLengthMm <= 0 || maxZoomRatio <= 0.0) return emptyList()
    val ratios = LinkedHashSet<Double>()
    if (minZoomRatio in 0.0..1.0) ratios.add(minZoomRatio)
    LADDER.filterTo(ratios) { it in minZoomRatio..maxZoomRatio }
    return ratios.sorted().map { ratio ->
        com.scenaristo.camera.domain.protocol.LensChoice(
            zoomRatio = ratio,
            // Zooming scales the field of view, so it scales the equivalent
            // focal length by the same factor. That is what the number means.
            equivalentFocalLengthMm = (baseEquivalentFocalLengthMm * ratio).roundToInt(),
        )
    }
}

/**
 * The ratios phone makers put physical lenses at, plus 1x.
 *
 * Stops beyond 5x are left out on purpose: past the longest real lens a phone
 * has, further zoom is a crop of a sensor already cropped to 16:9 at 4K, and
 * offering it as a "lens" would be offering a softer picture as a choice.
 */
private val LADDER = listOf(1.0, 2.0, 5.0)
