package com.scenaristo.camera.domain.exposure

import com.scenaristo.camera.domain.protocol.Warning
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The app's own auto-exposure loop: shutter held on the flicker-safe ladder, ISO
 * the only variable, damped so it does not pump (PRD 6.3, ADR-0005).
 *
 * It lives in `:domain` rather than in `:capture` because ADR-0005 requires the
 * same loop on both platforms — "the same metering code runs on iOS so that both
 * platforms agree" — and because a controller is far easier to trust when it can
 * be replayed against a synthetic light trace on the host than when it can only
 * be watched on a phone.
 *
 * The loop owns no camera. It is a pure transformer of [ExposureState]: the
 * capture layer meters a frame, hands the number here, and applies whatever
 * [ExposureState.iso] and [ExposureState.shutterHz] say afterwards. That is also
 * why it does not know what a face is — the face-weighted window is the metering
 * step that runs over the tapped preview frames (ADR-0005, sourced as ADR-0018
 * now requires), and all that reaches here is its single output number.
 *
 * What it deliberately does not do is slow the shutter in the dark or raise it
 * past the one flicker-safe rung in the light. Those are the two failures the
 * product exists to prevent (PRD 6.1, 6.3), so they are structurally impossible
 * here: the shutter can only ever be an index into [shutterLadder].
 */
class ExposureLoop(
    /** What the active sensor says it can do, per lens (ADR-0011). */
    private val iso: IsoRange,
    private val config: ExposureConfig = ExposureConfig(),
) {

    /**
     * A session starts at the sensor's lowest ISO and the grid's default rung —
     * PRD 6.3's "lowest possible" as a starting position rather than a target to
     * approach from above, so the first frames of a take are never the noisy ones.
     */
    fun start(grid: GridFrequency): ExposureState =
        ExposureState(grid = grid, iso = iso.min)

    /**
     * Fold one metered frame in and decide what to do about it.
     *
     * [luma] is the face-weighted mean of the frame as it was rendered, in
     * 0.0..1.0 and gamma-encoded — that is, the number a preview pixel carries,
     * not scene luminance. [config] converts it (see [ExposureConfig.toneGamma]).
     *
     * Frames that arrive while a change is still in flight are dropped, not
     * metered: they were exposed with the *old* ISO, and metering them is exactly
     * how a control loop with pipeline latency teaches itself to oscillate
     * (ADR-0005 point 2).
     */
    fun onFrame(state: ExposureState, luma: Double, nowMs: Long): ExposureState {
        if (state.awaitingEcho) return state

        val sample = errorEvOf(luma)
        val acquiring = !state.acquired
        val error =
            if (acquiring) sample
            else state.errorEv + config.emaAlpha * (sample - state.errorEv)

        val metered = state.copy(errorEv = error, acquired = true)
        val decided = decide(metered, acquiring, nowMs)
        return decided.copy(warnings = warningsFor(decided))
    }

    /**
     * A `CaptureResult` reported what the sensor actually used.
     *
     * Only a result that carries the values we asked for releases the loop:
     * anything else is a frame that was already in the pipeline when the request
     * went out. A device that *refuses* the request would stall the loop here,
     * which is deliberate rather than overlooked — [ExposureLoop] never asks for
     * an ISO outside the range the sensor reported, and a lens that ignores
     * `SENSOR_SENSITIVITY` altogether cannot record at all (ADR-0011).
     */
    fun onSensorEcho(state: ExposureState, iso: Int, shutterHz: Int): ExposureState =
        if (!state.awaitingEcho || iso != state.iso || shutterHz != state.shutterHz) state
        else state.copy(awaitingEcho = false)

    /**
     * The user changed the mains frequency (PRD 6.2's override, mandatory in a
     * mixed-grid country).
     *
     * The rung index is kept, so a session that had stepped to 1/100 s steps to
     * 1/120 s rather than dropping back to the default. The two rungs are not the
     * same exposure, so the outstanding error is corrected by the difference
     * instead of being re-learned from the next few frames.
     */
    fun onGridChanged(state: ExposureState, grid: GridFrequency, nowMs: Long): ExposureState {
        if (grid == state.grid) return state
        val before = state.shutterHz
        val moved = state.copy(grid = grid)
        val after = moved.shutterHz
        if (after == before) return moved
        val next = moved.copy(
            // Exposure time is 1/hz, so the change in stops is log2(before/after)
            // and the error owes the opposite of it.
            errorEv = state.errorEv - log2(before.toDouble() / after.toDouble()),
            awaitingEcho = true,
            changedAtMs = nowMs,
        )
        return next.copy(warnings = warningsFor(next))
    }

    /**
     * The order here is the priority order, and it is not arbitrary.
     *
     * Returning to the default rung comes first because it is free: it changes no
     * exposure, only which half of it the shutter carries, and doing it before
     * anything else keeps the app on PRD 6.1's stated default whenever the light
     * allows. The ladder step comes before the ISO move because when ISO is
     * already at the floor there is no ISO move to make.
     */
    private fun decide(state: ExposureState, acquiring: Boolean, nowMs: Long): ExposureState {
        val ladder = shutterLadder(state.grid)

        // Exposure-neutral: the slower rung is exactly one stop, and halving ISO
        // pays for it. The full stop of ISO headroom is what keeps this from
        // trading places with the step below forever -- that one fires only with
        // ISO on the floor, this one only with ISO a full stop above it, so no
        // state satisfies both. It runs before the dead band deliberately: a
        // climbing loop should hand the light back to the default shutter on the
        // way past, not overshoot into ISO it is about to give up.
        if (state.rung > 0 && state.iso >= iso.min * 2) {
            return state.copy(
                rung = state.rung - 1,
                iso = (state.iso / 2.0).roundToInt().coerceAtLeast(iso.min),
                awaitingEcho = true,
                changedAtMs = nowMs,
            )
        }

        if (abs(state.errorEv) <= config.deadBandEv) return state

        // PRD 6.3: overexposed at base ISO steps the shutter one flicker-safe
        // rung and shows no warning. The rung is exactly one stop faster, so the
        // error it leaves behind is the error plus one stop.
        if (state.errorEv < 0 && state.iso <= iso.min && state.rung < ladder.lastIndex) {
            return state.copy(
                rung = state.rung + 1,
                errorEv = state.errorEv + 1.0,
                awaitingEcho = true,
                changedAtMs = nowMs,
            )
        }

        return moveIso(state, acquiring, nowMs)
    }

    /**
     * ISO in sixth-of-a-stop steps, one step per sixth of a second.
     *
     * That pairing *is* ADR-0005's "maximum slew of 1 stop per second": six
     * steps of a sixth of a stop, and the loop cannot bank unused time to spend
     * later. Rate-limiting by a per-frame cap instead would have failed — a sixth
     * of a stop per second at 30 fps is 1/180 stop per frame, smaller than the
     * smallest step, so the loop would never move at all.
     *
     * The first metered frame of a session is exempt and goes straight to the
     * metered value. Nothing is on screen to pump yet, and PRD 6.3's "settles
     * within 2 s" is measured from a cold start, which a rate-limited climb out
     * of base ISO would miss in any dim room.
     */
    private fun moveIso(state: ExposureState, acquiring: Boolean, nowMs: Long): ExposureState {
        val moveEv = if (acquiring) {
            state.errorEv
        } else {
            val sinceMs = state.changedAtMs?.let { nowMs - it } ?: Long.MAX_VALUE
            val minIntervalMs = 1000.0 * config.stepEv / config.maxSlewEvPerSecond
            if (sinceMs < minIntervalMs) return state
            if (state.errorEv > 0) config.stepEv else -config.stepEv
        }

        val target = (state.iso * 2.0.pow(moveEv)).roundToInt().coerceIn(iso.min, iso.max)
        if (target == state.iso) return state

        // Charge the loop what it actually got, not what it asked for: rounding
        // to an integer ISO and clamping at the sensor's limits both make those
        // differ, and an error that is never reconciled is a standing offset.
        val applied = log2(target.toDouble() / state.iso.toDouble())
        return state.copy(
            iso = target,
            errorEv = state.errorEv - applied,
            awaitingEcho = true,
            changedAtMs = nowMs,
        )
    }

    /**
     * The two warnings the exposure loop is entitled to raise (PRD 6.3).
     *
     * Recomputed from scratch on every frame, which is what makes them clear
     * themselves when the condition ends. [Warning.TOO_CLOSE_TO_LENS] is not the
     * loop's to raise, and neither is [Warning.TOO_BRIGHT]: PRD 6.3 defines one
     * too-much-light message and it is the one that follows a failed ladder step.
     */
    private fun warningsFor(state: ExposureState): Set<Warning> {
        val ladder = shutterLadder(state.grid)
        return buildSet {
            if (state.iso > config.noiseWarningIso) add(Warning.TOO_DARK)
            if (state.iso <= iso.min &&
                state.rung == ladder.lastIndex &&
                state.errorEv < -config.deadBandEv
            ) {
                add(Warning.OVEREXPOSED_AT_BASE_ISO)
            }
        }
    }

    /**
     * Metered luma to an exposure error in stops, positive meaning "needs more
     * light".
     *
     * Both sides are linearised first. Preview pixels are gamma-encoded, so the
     * ratio of two encoded values is not a ratio of exposures, and skipping this
     * would put a constant factor of [ExposureConfig.toneGamma] on the loop gain
     * — which is to say it would silently change the settle time PRD 6.3 puts a
     * number on.
     */
    private fun errorEvOf(luma: Double): Double {
        val measured = luma.coerceIn(BLACK_FLOOR, 1.0).pow(config.toneGamma)
        val target = config.targetLuma.pow(config.toneGamma)
        return log2(target / measured)
    }

    private companion object {
        /**
         * A frame that meters as pure black is a lens cap or a dead sensor, not
         * an infinite exposure error. The floor bounds it at a large but finite
         * number of stops so the loop asks for max ISO and stops there.
         */
        const val BLACK_FLOOR = 1e-4
    }
}

/**
 * The ISO range the active sensor reports: Android's
 * `SENSOR_INFO_SENSITIVITY_RANGE`, iOS's `activeFormat.minISO`/`maxISO`.
 *
 * Per lens, not per device (ADR-0011), and the reason [ExposureLoop] never asks
 * for a value outside it.
 */
data class IsoRange(val min: Int, val max: Int)

/**
 * Everything ADR-0005 left as a number to tune, in one place.
 *
 * The defaults are the ADR's stated starting values. Phase 0 issue #25 replaces
 * them with measured ones and captures the traces as fixtures, so this class is
 * the thing that changes when it reports — not the loop.
 */
data class ExposureConfig(
    /**
     * The mid-tone the face should land on, gamma-encoded (ADR-0005: "initially
     * 45 % luma"). 0.45 encoded is about 18 % in linear light, which is the grey
     * card every other exposure convention is built on.
     */
    val targetLuma: Double = 0.45,
    /** Encoding gamma of the metered frames, used to linearise before comparing. */
    val toneGamma: Double = 2.2,
    /** Errors smaller than this are noise in the scene, not a change in it. */
    val deadBandEv: Double = 0.15,
    /** ISO moves in sixths of a stop. Deliberately larger than [deadBandEv]. */
    val stepEv: Double = 1.0 / 6.0,
    /** Frames in the moving average that damps the metering. */
    val emaFrames: Int = 5,
    /** How fast ISO may travel. Above this the change is visible on camera. */
    val maxSlewEvPerSecond: Double = 1.0,
    /**
     * Above this ISO the image is noisy enough to tell the user about (PRD 6.3's
     * "per-device noise threshold, default ISO 800"). Phase 0 sets it per device.
     */
    val noiseWarningIso: Int = 800,
) {
    /** The standard exponential-moving-average weight for an [emaFrames] window. */
    val emaAlpha: Double get() = 2.0 / (emaFrames + 1)
}

/**
 * Everything the loop knows between frames.
 *
 * Pure data, so a light trace can be replayed frame by frame in a test and the
 * result compared against PRD 6.3's numbers — which is how the acceptance
 * criteria are checked without a room, a lamp and a phone.
 */
data class ExposureState(
    val grid: GridFrequency,
    /** Index into [shutterLadder]. 0 is PRD 6.1's default; 1 is ADR-0005's step. */
    val rung: Int = 0,
    val iso: Int,
    /** Damped exposure error in stops, positive meaning the frame needs more light. */
    val errorEv: Double = 0.0,
    /** False until the first frame has been metered, which is the one that may snap. */
    val acquired: Boolean = false,
    /** A change is in flight; frames metered now would still show the old one. */
    val awaitingEcho: Boolean = false,
    /** When the last change was requested, for the slew limit. */
    val changedAtMs: Long? = null,
    /** The exposure warnings only (PRD 6.3); the caller merges in the rest. */
    val warnings: Set<Warning> = emptySet(),
) {
    /**
     * The shutter in use, as reciprocal seconds. Never longer than the grid's
     * default rung, which is what keeps 30 fps constant in the dark (PRD 6.1).
     */
    val shutterHz: Int get() = shutterLadder(grid)[rung]

    /** True when the shutter is on ADR-0005's step rather than PRD 6.1's default. */
    val stepped: Boolean get() = rung > 0
}
