package com.scenaristo.camera.domain.lighting

import com.scenaristo.camera.domain.exposure.Metered
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * How the light in the room is falling on the person in front of the camera
 * (PRD 6.11).
 *
 * Every other measurement this product makes is of the equipment — shutter, ISO,
 * focal length, level — and each is right regardless of what is in front of the
 * lens. This one is a measurement of the room, and rooms lie: the numbers here
 * are only as good as the face box they were taken through, and they say nothing
 * at all when there is no face. That is why [measuring] exists and why every
 * reading carries it, the same refusal `AudioState.metering`,
 * `ExposureReadout.metering` and `MountAttitude.measuring` already make.
 *
 * Three readings, in the order they are worth having:
 *
 *  - [keyRatioTenths] — how much brighter the lit side of the face is than the
 *    shadow side. Studio practice puts a talking head near 2:1; 1:1 is flat and
 *    is also a legitimate deliberate choice, which is why this reports a number
 *    and not a verdict.
 *  - [backgroundStopsTenths] — how far the background sits below the face. One
 *    to two stops is the separation that reads as lit rather than recorded; a
 *    *negative* number is the common domestic failure, a window behind the
 *    speaker.
 *  - [ev100Tenths] — how much light is in the room at all, from the exposure the
 *    loop settled on rather than from pixels. Nothing here creates photons.
 *
 * Tenths, as integers, on purpose. A snapshot goes out at least twice a second
 * (ADR-0007) and a settings revision is counted off changes to it (ADR-0024):
 * a double that jitters in its last bits would advance a revision every frame
 * and refuse every settings change a user made, which is the bug ADR-0024
 * exists to fix. [PortraitLightingFilter] quantises before the wire.
 */
@Suppress("unused")
object PortraitLighting {

    /**
     * What studio practice puts a talking head at, in tenths: 2.0:1.
     *
     * Not a target the app steers toward — nothing here changes a light. It is
     * the number a reading is read against, and it lives in `:domain` so that
     * both platforms and the browser agree on what "already about right" means.
     */
    const val TARGET_KEY_RATIO_TENTHS: Int = 20

    /** The separation that reads as lit rather than recorded, in tenths of a stop. */
    const val BACKGROUND_MIN_STOPS_TENTHS: Int = 10
    const val BACKGROUND_MAX_STOPS_TENTHS: Int = 20

    /**
     * The ISO at or below which the room has a stop of headroom over the noise
     * threshold PRD 6.3 warns at (decision 2026-09-08, Davide).
     *
     * Shaping light means lifting one side of a face, which amplifies whatever
     * noise is already there; this is the line under which that is worth
     * offering. It is deliberately stricter than §6.3's ISO 800 warning and
     * deliberately looser than base ISO.
     */
    const val ENOUGH_LIGHT_MAX_ISO: Int = 400

    /**
     * A lighting reading from one metered frame, or an unmeasured one.
     *
     * Returns [Reading.unmeasured] whenever the halves are missing, which is
     * every frame with no face: the meter falls back to its centre window then,
     * and a ratio taken across a fixed rectangle is a fact about a rectangle.
     */
    fun read(metered: Metered, iso: Int): Reading {
        val left = metered.faceLeftLuma
        val right = metered.faceRightLuma
        if (left == null || right == null || left <= 0.0 || right <= 0.0) {
            return Reading.unmeasured(iso)
        }

        val brighter = maxOf(left, right)
        val dimmer = minOf(left, right)
        val ratio = brighter / dimmer

        val background = metered.backgroundLuma
        val face = (left + right) / 2.0
        val backgroundStops = if (background == null || background <= 0.0) {
            null
        } else {
            stopsBetween(face, background)
        }

        return Reading(
            measuring = true,
            keyRatioTenths = (ratio * 10).roundToInt(),
            keySide = if (left >= right) KeySide.LEFT else KeySide.RIGHT,
            backgroundStopsTenths = backgroundStops?.let { (it * 10).roundToInt() },
            enoughLight = iso <= ENOUGH_LIGHT_MAX_ISO,
        )
    }

    /**
     * Stops between two gamma-encoded luma means.
     *
     * Linearised first. The meter works in encoded space deliberately — its
     * KDoc says why — but a *stop* is a doubling of light, and taking the ratio
     * of two gamma-encoded numbers would report about half the separation that
     * is really there, which for a one-to-two-stop rule is the difference
     * between passing and failing.
     */
    fun stopsBetween(brighter: Double, dimmer: Double): Double {
        if (brighter <= 0.0 || dimmer <= 0.0) return 0.0
        val linearBright = linearise(brighter)
        val linearDim = linearise(dimmer)
        if (linearDim <= 0.0) return 0.0
        return ln(linearBright / linearDim) / LN_2
    }

    private fun linearise(encoded: Double): Double {
        // Rec.709 transfer, the inverse of what LumaScale encodes.
        val v = encoded.coerceIn(0.0, 1.0)
        return if (v < 0.081) v / 4.5 else pow((v + 0.099) / 1.099, 1.0 / 0.45)
    }

    private fun pow(base: Double, exponent: Double): Double =
        if (base <= 0.0) 0.0 else kotlin.math.exp(exponent * ln(base))

    private val LN_2 = ln(2.0)

    /** Which side of the *frame* the key is on. Never the subject's side. */
    enum class KeySide { LEFT, RIGHT, NONE }

    /**
     * One frame's answer.
     *
     * [keySide] names a side of the picture rather than of the person, for the
     * reason UI-23 gives about tilt: left and right are a property of what the
     * viewer is looking at, and the browser may be mirroring it (UI-19). Copy
     * that needs to instruct someone to move a lamp has to resolve that on the
     * surface that knows.
     */
    data class Reading(
        val measuring: Boolean = false,
        val keyRatioTenths: Int = 0,
        val keySide: KeySide = KeySide.NONE,
        val backgroundStopsTenths: Int? = null,
        val enoughLight: Boolean = false,
    ) {
        /** Within a quarter of a stop of studio practice's 2:1, and so not worth touching. */
        val alreadyShaped: Boolean
            get() = measuring && abs(keyRatioTenths - TARGET_KEY_RATIO_TENTHS) <= 5

        /** The background is brighter than the face: a window behind the speaker. */
        val subjectIsBacklit: Boolean
            get() = measuring && (backgroundStopsTenths ?: 0) < 0

        companion object {
            fun unmeasured(iso: Int) = Reading(
                measuring = false,
                enoughLight = iso <= ENOUGH_LIGHT_MAX_ISO,
            )
        }
    }
}

/**
 * Holds a reading still enough to put on the wire (ADR-0024).
 *
 * A face box jitters by a few pixels between frames even on a tripod, so the
 * ratio it produces jitters with it. Published raw at the tap's rate that would
 * advance the settings revision constantly and refuse every settings change the
 * user made — the failure ADR-0024 was written for, and the same reason
 * `MountFilter` deadbands the accelerometer before it reaches the wire.
 *
 * The deadband is applied to the quantised value, not to the raw one, so a
 * reading sitting exactly on a step boundary settles instead of alternating.
 */
class PortraitLightingFilter(
    private val ratioDeadbandTenths: Int = RATIO_DEADBAND_TENTHS,
    private val stopsDeadbandTenths: Int = STOPS_DEADBAND_TENTHS,
) {

    private var published = PortraitLighting.Reading()

    /** The reading to publish, which is the previous one unless something moved. */
    fun accept(reading: PortraitLighting.Reading): PortraitLighting.Reading {
        if (!reading.measuring) {
            published = PortraitLighting.Reading(
                measuring = false,
                enoughLight = reading.enoughLight,
            )
            return published
        }

        val ratioMoved = abs(reading.keyRatioTenths - published.keyRatioTenths) >= ratioDeadbandTenths
        val stopsMoved = movedBy(
            published.backgroundStopsTenths,
            reading.backgroundStopsTenths,
            stopsDeadbandTenths,
        )
        val changed = !published.measuring ||
            ratioMoved ||
            stopsMoved ||
            reading.keySide != published.keySide ||
            reading.enoughLight != published.enoughLight

        if (changed) published = reading
        return published
    }

    /** The last published reading, without accepting a new one. */
    fun reading(): PortraitLighting.Reading = published

    private fun movedBy(before: Int?, now: Int?, deadband: Int): Boolean = when {
        before == null && now == null -> false
        before == null || now == null -> true
        else -> abs(now - before) >= deadband
    }

    companion object {
        /** A tenth of a ratio point is below what anyone reads; three is not. */
        const val RATIO_DEADBAND_TENTHS: Int = 3

        /** A third of a stop, which is the smallest step worth redrawing. */
        const val STOPS_DEADBAND_TENTHS: Int = 3
    }
}
