package com.scenaristo.camera.domain.whitebalance

import kotlin.math.abs

/**
 * PRD 6.4's white balance, as two short lists instead of a colour picker.
 *
 * The product decision behind this is that a creator knows what their room is
 * lit by and does not know what a Kelvin is. So the question asked is "is there
 * daylight in the room?", and the answer narrows six numbers to three — the
 * three that can plausibly be right given the answer.
 *
 * White balance is always locked. Auto white balance is not a default here and
 * not an option: it drifts mid-take, and a shift in the middle of a recording is
 * the one colour problem an editor cannot fix afterwards, because there is no
 * single correction that fits both halves.
 */
enum class LightScenario {
    /** A window is contributing. Nothing below 4500 K is plausible. */
    NATURAL_LIGHT,

    /** Lamps only, so tungsten is on the table and 6500 K is not. */
    ARTIFICIAL_LIGHT,
}

/**
 * The presets PRD 6.4 offers for a scenario, warmest first.
 *
 * The lists overlap at 4500 K and 5600 K deliberately: a room with a window and
 * a lamp is both scenarios, and a user who picks the wrong one still finds the
 * temperature they need rather than being told they chose wrong.
 */
fun presetsFor(scenario: LightScenario): List<Int> = when (scenario) {
    LightScenario.NATURAL_LIGHT -> listOf(4500, 5600, 6500)
    LightScenario.ARTIFICIAL_LIGHT -> listOf(3200, 4500, 5600)
}

/** PRD 6.4's default in both scenarios, and PRD 6.1's capture default. */
const val DEFAULT_KELVIN: Int = 5600

/** PRD 6.4: "Tint fixed at 0 in v1." */
const val TINT: Int = 0

/**
 * The platform white balance modes a lens without manual colour gains has to
 * make do with (PRD 6.4's Android note, ADR-0011).
 *
 * ADR-0011 decided that a missing `MANUAL_POST_PROCESSING` degrades white
 * balance rather than disqualifying the lens — unlike `MANUAL_SENSOR`, which
 * refuses it outright. The difference is that an approximated white balance is
 * still a *locked* white balance, so the take is consistent even where it is not
 * exact; a lens that cannot hold a shutter produces banding no grade can remove.
 *
 * The Kelvin values are the platform's nominal ones, not measurements. What each
 * mode actually produces on the reference device is #24's grey-card measurement,
 * which is Phase 3.
 */
enum class AwbApproximation(val nominalKelvin: Int) {
    INCANDESCENT(3000),
    FLUORESCENT(4000),
    DAYLIGHT(5500),
    CLOUDY(6500),
}

/**
 * The closest platform mode to a preset, for a lens that cannot take gains.
 *
 * Closest in **mired** — a million over the temperature — rather than in Kelvin,
 * because Kelvin is not perceptually even: 3000 K to 3500 K is a large visible
 * shift and 6000 K to 6500 K is a small one, so nearest-in-Kelvin would pick the
 * wrong mode at the warm end while looking arithmetically reasonable. Mired is
 * roughly linear in how different two whites look, which is why every lighting
 * gel is graded in it.
 */
fun approximationFor(kelvin: Int): AwbApproximation =
    AwbApproximation.entries.minBy { abs(miredOf(it.nominalKelvin) - miredOf(kelvin)) }

/**
 * What PRD 6.4 requires a degraded lens to admit to: "the app shows which preset
 * is approximated and by which platform mode."
 *
 * [exact] is true when the lens takes colour gains and the preset is simply
 * applied. The distinction is the point — a locked-but-approximate white balance
 * is honest, and a silently approximate one is the drift the product exists to
 * remove, only harder to notice.
 */
data class WhiteBalanceSetting(
    val kelvin: Int,
    val exact: Boolean,
    /** Null when [exact]; the mode standing in for [kelvin] otherwise. */
    val approximatedBy: AwbApproximation? = null,
) {
    init {
        require(exact == (approximatedBy == null)) {
            "an approximated white balance names its mode, and an exact one has none"
        }
    }
}

/**
 * How a preset will actually be applied on a given lens (ADR-0011).
 *
 * [hasManualGains] is `MANUAL_POST_PROCESSING` on Android and device white
 * balance gains on iOS — a per-lens capability, which is why this takes it as an
 * argument rather than reading anything.
 *
 * Note what this does *not* do: convert Kelvin to RGB gains. That curve is
 * device-calibrated and its measurement is #24, deferred to Phase 3 with the
 * grey card it needs. Until then the exact path is exact in the sense that the
 * platform accepts the request, not in the sense that anyone has held a grey
 * card in front of it.
 */
fun settingFor(kelvin: Int, hasManualGains: Boolean): WhiteBalanceSetting =
    if (hasManualGains) {
        WhiteBalanceSetting(kelvin = kelvin, exact = true)
    } else {
        WhiteBalanceSetting(kelvin = kelvin, exact = false, approximatedBy = approximationFor(kelvin))
    }

/** A million over the temperature: the scale colour differences are even in. */
private fun miredOf(kelvin: Int): Double = 1_000_000.0 / kelvin
