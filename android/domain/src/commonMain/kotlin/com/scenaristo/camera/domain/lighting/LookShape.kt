package com.scenaristo.camera.domain.lighting

import com.scenaristo.camera.domain.protocol.StudioLook
import kotlin.math.abs

/**
 * What each studio look actually asks of the picture (PRD 6.11, ADR-0029).
 *
 * Numbers rather than pixels, and in `:domain` rather than beside the shader, so
 * that the iOS port reaches the same look from the same figures instead of a
 * second, differently-tuned one (ADR-0013). Nothing here draws anything; a
 * platform's shader reads these and does.
 *
 * **A look is a redistribution, never an addition.** `docs/research/studio-lighting.md`
 * §4: nothing here creates photons, which is why the whole feature is gated on
 * the room already having enough light (ISO ≤ 400, decision 2026-09-08). Every
 * gain below is centred on 1.0 — one side of the face comes up only as far as the
 * other goes down — so the exposure the ADR-0005 loop settled on stays the
 * exposure, and the histogram the user is looking at still means what it says.
 */
object LookShape {

    /**
     * How the two looks differ, which is less than their names suggest.
     *
     * Both move the key toward the same 2:1; what changes is *where the light
     * appears to come from*, and that is two numbers. Rembrandt puts it further
     * round and higher, so the gradient runs more steeply across the face and
     * carries a downward component; Clamshell keeps it near the lens axis and
     * lifts from below, which is why its vertical term is negative and its
     * horizontal one is gentle.
     */
    fun of(look: StudioLook): LookParameters? = when (look) {
        StudioLook.OFF -> null

        StudioLook.REMBRANDT -> LookParameters(
            horizontal = 0.85,
            vertical = 0.35,
            falloff = 1.6,
            targetRatioTenths = PortraitLighting.TARGET_KEY_RATIO_TENTHS,
            backgroundStopsTenths = 15,
        )

        StudioLook.CLAMSHELL -> LookParameters(
            horizontal = 0.30,
            vertical = -0.55,
            falloff = 2.4,
            targetRatioTenths = 14,
            backgroundStopsTenths = 10,
        )
    }

    /**
     * The gain to apply at a point on the face, given where the light is coming
     * from and how far across the face that point sits.
     *
     * [across] is −1 at the shadow edge of the face and +1 at the lit edge, along
     * the look's own axis; [ratio] is the gain at the lit edge, so the shadow edge
     * gets its reciprocal and the middle gets 1.0. A power curve rather than a
     * straight line because a straight ramp across a face reads as a printed
     * gradient — the exponent is what makes it look like falloff.
     */
    fun gainAt(across: Double, parameters: LookParameters, ratio: Double): Double {
        val clamped = across.coerceIn(-1.0, 1.0)
        val shaped = signedPow(clamped, parameters.falloff)
        // Half the ratio each way, so the mean gain over a symmetric face is 1.0
        // and the look costs no exposure.
        val half = ratio.coerceAtLeast(1.0).let { pow(it, 0.5) }
        return pow(half, shaped)
    }

    /**
     * How much the lit half must move to reach the look's target ratio from the
     * one the room is already giving, or 1.0 when it is already there.
     *
     * The feature's own rule, and the reason it is a rule: a face already near
     * 2:1 is left alone. Shaping it further would be the app overriding a
     * decision somebody made with a lamp.
     */
    fun ratioToApply(measuredTenths: Int, parameters: LookParameters): Double {
        if (measuredTenths <= 0) return 1.0
        val target = parameters.targetRatioTenths.toDouble()
        val measured = measuredTenths.toDouble()
        if (abs(measured - target) <= ALREADY_SHAPED_TENTHS) return 1.0
        return (target / measured).coerceIn(1.0 / MAX_CORRECTION, MAX_CORRECTION)
    }

    /**
     * Within a quarter of a stop of the target, in tenths of a ratio point.
     *
     * The same window `PortraitLighting.Reading.alreadyShaped` uses, and
     * deliberately the same constant rather than a second one that could drift:
     * a reading that says "already shaped" and a look that shapes it anyway would
     * be the product disagreeing with itself.
     */
    const val ALREADY_SHAPED_TENTHS: Int = 5

    /**
     * The most a look may move the ratio, as a multiplier.
     *
     * A flat 1:1 face asked for 2:1 needs 2.0, which is inside this. The cap
     * exists for the other end: a reading of 0.2:1 — which is not a lit face at
     * all but a measurement error or a face half out of frame — would otherwise
     * ask for a gain that turns one cheek white.
     */
    const val MAX_CORRECTION: Double = 2.5

    private fun signedPow(value: Double, exponent: Double): Double {
        val magnitude = pow(abs(value), exponent)
        return if (value < 0) -magnitude else magnitude
    }

    private fun pow(base: Double, exponent: Double): Double =
        if (base <= 0.0) 0.0 else kotlin.math.exp(exponent * kotlin.math.ln(base))
}

/**
 * One look, as numbers a shader can use.
 *
 * [horizontal] and [vertical] are the direction the key appears to come from, in
 * frame coordinates: positive [horizontal] is the right of frame, positive
 * [vertical] is *down*, matching image coordinates rather than intuition, because
 * every other coordinate in this codebase does.
 */
data class LookParameters(
    val horizontal: Double,
    val vertical: Double,
    /** The exponent on the gradient. Higher is a tighter, more sculpted falloff. */
    val falloff: Double,
    /** Where this look wants the key ratio, in tenths. */
    val targetRatioTenths: Int,
    /** Where this look wants the background, in tenths of a stop below the face. */
    val backgroundStopsTenths: Int,
)
