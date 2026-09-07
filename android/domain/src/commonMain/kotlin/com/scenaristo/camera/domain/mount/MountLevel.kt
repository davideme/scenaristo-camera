package com.scenaristo.camera.domain.mount

import com.scenaristo.camera.domain.protocol.MountAttitude
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Whether the phone is level on its mount, and whether the mount is holding
 * still (PRD 6.11).
 *
 * PRD 6.1 turns both stabilisers off — "Phone is on a tripod; EIS crops and can
 * wobble, OIS drifts" — which makes a steady, level mount a *precondition* of
 * every take rather than something the app helps anyone reach. A crooked
 * horizon costs nothing to fix before the take and cannot be fixed after it
 * without cropping the frame, so the only useful moment to say so is during
 * setup. This is the measurement behind that.
 *
 * In `:domain` because the arithmetic and the thresholds are the same on both
 * platforms (ADR-0013) and neither is allowed to reach a different answer about
 * the phone in front of it. What is not the same is where the numbers come
 * from: Android registers a `TYPE_ACCELEROMETER` listener, iOS reads
 * `CMDeviceMotion`. Neither appears here.
 *
 * No copy lives here either. What the operator is told is the UI spec's to word.
 */

/**
 * Where the top of the interface is, relative to the phone's natural
 * orientation, counter-clockwise.
 *
 * The accelerometer answers in the device's *natural* frame — the one the phone
 * was designed portrait in — and the browser is looking at a frame the camera
 * stack has already rotated. Without this the maths is right about a phone
 * nobody is holding.
 *
 * Deliberately not Android's `Surface.ROTATION_*`, which is an `Int` from a
 * platform class this module may not name (ADR-0010).
 */
enum class ScreenRotation(
    /** Counter-clockwise degrees from natural. */
    val degrees: Int,
) {
    DEGREES_0(0),
    DEGREES_90(90),
    DEGREES_180(180),
    DEGREES_270(270),
}

/**
 * One accelerometer reading in the device's natural frame, m/s².
 *
 * An accelerometer at rest reads the *reaction* to gravity, so this vector
 * points at the sky rather than at the floor. Everything below depends on that,
 * and a platform that hands over a gravity vector pointing down has to negate it
 * before it gets here.
 */
data class GravitySample(val x: Double, val y: Double, val z: Double) {
    val magnitude: Double get() = sqrt(x * x + y * y + z * z)
}

/**
 * The phone's attitude in the frame the *recording* is made in.
 *
 * [rollDegrees] is the tilt of the horizon within the picture and is the number
 * anyone means by "level": positive when the world's up leans towards the left
 * of frame, which is the same as saying the right of frame has dropped.
 *
 * [pitchDegrees] is where the rear camera is aimed relative to horizontal,
 * positive upwards. It is reported and never judged — a camera aimed a few
 * degrees up at a seated speaker is a deliberate choice as often as it is an
 * accident, and PRD 6.11 says only roll is ever framed as wrong.
 */
data class Attitude(val rollDegrees: Double, val pitchDegrees: Double)

/**
 * The attitude a sample implies, or null when the phone is pointed too close to
 * straight up or straight down for roll to mean anything.
 *
 * The geometry, for a screen rotation of `r` counter-clockwise:
 *
 * ```
 * image-up      u = ( sin r,  cos r, 0)
 * image-right   v = ( cos r, -sin r, 0)
 * optical axis  o = (     0,      0, -1)   // the rear camera looks out of the back
 * ```
 *
 * Project the sample — which points at the sky — onto the image plane, and the
 * horizon is the perpendicular of what comes out:
 *
 * ```
 * roll  = atan2(-(a . v), a . u)
 * pitch = asin(-az / |a|)
 * ```
 *
 * **The degenerate case is the reason for the null.** As the optical axis
 * approaches vertical — a phone face-up on a desk, or aimed at the ceiling —
 * both projections collapse towards zero and `atan2` starts reporting the
 * sensor's noise as a roll angle, swinging through tens of degrees between one
 * sample and the next. Below [MIN_TILT_FRACTION] there is no horizon in the
 * picture to be level with, so this says nothing rather than something
 * confident and wrong.
 */
fun attitudeOf(sample: GravitySample, rotation: ScreenRotation): Attitude? {
    val magnitude = sample.magnitude
    if (magnitude <= 0.0) return null

    // sin and cos of a right-angle multiple, exactly, because 90 degrees in
    // radians is irrational and `cos(PI / 2)` is 6.1e-17 rather than zero. That
    // error is far below the sensor's noise and would never show — but an exact
    // zero makes the tests assert exact zeros, and a level phone reading
    // "0.0000000000000001 degrees off" is a worse thing to explain than this
    // comment.
    val (sin, cos) = when (rotation) {
        ScreenRotation.DEGREES_0 -> 0.0 to 1.0
        ScreenRotation.DEGREES_90 -> 1.0 to 0.0
        ScreenRotation.DEGREES_180 -> 0.0 to -1.0
        ScreenRotation.DEGREES_270 -> -1.0 to 0.0
    }

    val up = sample.x * sin + sample.y * cos
    val right = sample.x * cos - sample.y * sin

    if (sqrt(up * up + right * right) / magnitude < MIN_TILT_FRACTION) return null

    // The trailing `+ 0.0` turns a negative zero into a zero. `atan2(-0.0, 9.81)`
    // is -0.0, which is a perfectly good number right up until it is formatted
    // for someone, and "-0.0 degrees off level" reads as a fault rather than as
    // the absence of one.
    return Attitude(
        rollDegrees = atan2(-right, up).toDegrees() + 0.0,
        pitchDegrees = asin((-sample.z / magnitude).coerceIn(-1.0, 1.0)).toDegrees() + 0.0,
    )
}

/**
 * Turns a stream of accelerometer samples into something worth putting on the
 * wire.
 *
 * Three jobs, and each of them exists because the raw stream cannot be sent as
 * it arrives:
 *
 * 1. **Average.** A single sample carries the sensor's noise, measured on the
 *    reference Pixel 10 at 0.14° rms with a 0.36° worst case while the phone sat
 *    still, 2026-09-07. Averaging a second of them takes that to roughly
 *    0.02°, which is well under anything a person can see in a frame.
 * 2. **Deadband.** ADR-0024 records what happens when a continuously-varying
 *    reading goes straight onto the wire: the histogram made `rev` advance 27
 *    times a second and broke `expectRev` for every client. A published angle
 *    that only moves when it has really moved keeps a still phone at one
 *    revision.
 * 3. **Judge steadiness**, which is the one thing the averaging would otherwise
 *    destroy: vibration is exactly the part of the signal a low-pass filter
 *    throws away, so it is measured before the averaging rather than after.
 *
 * Not thread-safe, and does not need to be: it is fed from the one thread the
 * platform delivers sensor events on, and the value it produces is published
 * through a `StateFlow` that is.
 */
class MountFilter {

    private val samples = ArrayDeque<Timed>()
    private var unsteady = false
    private var quietSinceMs: Long? = null
    private var published: Attitude? = null

    /**
     * Takes one reading. [nowMs] is the sample's own timestamp, not the moment
     * it was handled, so a batch delivered late from the sensor hub is still
     * measured over the second it actually spans.
     */
    fun onSample(sample: GravitySample, rotation: ScreenRotation, nowMs: Long) {
        val attitude = attitudeOf(sample, rotation)
        if (attitude == null) {
            // Face up, face down, or being carried. Nothing here is measurable,
            // and holding the old samples would let a stale angle reappear the
            // moment the phone is stood back up.
            reset()
            return
        }

        samples.addLast(Timed(sample, attitude, nowMs))
        while (samples.size > MAX_SAMPLES ||
            (samples.size > 1 && nowMs - samples.first().atMs > WINDOW_MS)
        ) {
            samples.removeFirst()
        }

        updateSteadiness(nowMs)
    }

    /** Forgets everything, so nothing measured before a gap can survive across it. */
    fun reset() {
        samples.clear()
        unsteady = false
        quietSinceMs = null
        published = null
    }

    /**
     * What to publish, or a not-measuring reading while there is not yet enough
     * to say.
     *
     * The angles hold their previously published values until they move by more
     * than [DEADBAND_DEGREES], which is what stops a still phone bumping `rev`
     * once a second forever.
     */
    fun reading(): MountAttitude {
        if (samples.size < MIN_SAMPLES) return MountAttitude()

        val mean = meanAttitude()
        val next = Attitude(
            rollDegrees = quantise(mean.rollDegrees),
            pitchDegrees = quantise(mean.pitchDegrees),
        )
        val last = published
        if (last == null ||
            abs(next.rollDegrees - last.rollDegrees) >= DEADBAND_DEGREES ||
            abs(next.pitchDegrees - last.pitchDegrees) >= DEADBAND_DEGREES
        ) {
            published = next
        }
        val shown = published ?: next

        return MountAttitude(
            rollDegrees = shown.rollDegrees,
            pitchDegrees = shown.pitchDegrees,
            steady = !unsteady,
            measuring = true,
        )
    }

    /**
     * How far the samples in the window stray from their own average direction.
     *
     * The angle between each sample and the window's mean vector, rms'd. It
     * measures *movement* and not attitude, so a phone held perfectly still at
     * 30° off level reads as steady, which is the intent: the two questions are
     * separate, and the answer to one is not evidence about the other.
     */
    private fun updateSteadiness(nowMs: Long) {
        if (samples.size < MIN_SAMPLES) return

        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        for (s in samples) {
            sx += s.sample.x
            sy += s.sample.y
            sz += s.sample.z
        }
        val meanMagnitude = sqrt(sx * sx + sy * sy + sz * sz)
        if (meanMagnitude <= 0.0) return

        var sumSquares = 0.0
        for (s in samples) {
            val magnitude = s.sample.magnitude
            if (magnitude <= 0.0) continue
            val cosine = (s.sample.x * sx + s.sample.y * sy + s.sample.z * sz) /
                (magnitude * meanMagnitude)
            val degrees = acos(cosine.coerceIn(-1.0, 1.0)).toDegrees()
            sumSquares += degrees * degrees
        }
        val rms = sqrt(sumSquares / samples.size)

        // Latches on immediately and leaves slowly, on purpose. A door slamming
        // is worth showing the instant it happens; a reading that flickers back
        // and forth while someone's hand is near the tripod is worth nothing to
        // anybody.
        if (rms >= UNSTEADY_ENTER_DEGREES) {
            unsteady = true
            quietSinceMs = null
            return
        }
        if (!unsteady) return
        if (rms >= UNSTEADY_EXIT_DEGREES) {
            quietSinceMs = null
            return
        }
        val quietSince = quietSinceMs ?: nowMs.also { quietSinceMs = it }
        if (nowMs - quietSince >= UNSTEADY_CLEAR_MS) {
            unsteady = false
            quietSinceMs = null
        }
    }

    /**
     * The average of the window's angles rather than the angle of the window's
     * average vector.
     *
     * The two agree to well within the sensor's noise over the fractions of a
     * degree this deals in, and averaging the angles is the one that stays right
     * if the window ever spans a wrap at ±180°.
     */
    private fun meanAttitude(): Attitude {
        var roll = 0.0
        var pitch = 0.0
        for (s in samples) {
            roll += s.attitude.rollDegrees
            pitch += s.attitude.pitchDegrees
        }
        return Attitude(roll / samples.size, pitch / samples.size)
    }

    private data class Timed(
        val sample: GravitySample,
        val attitude: Attitude,
        val atMs: Long,
    )
}

private fun quantise(degrees: Double): Double =
    round(degrees / STEP_DEGREES) * STEP_DEGREES

private fun Double.toDegrees(): Double = this * 180.0 / PI

/**
 * How much of the sample has to lie in the image plane before roll means
 * anything: `sin(10°)`.
 *
 * Ten degrees rather than one, because the failure is not gradual. Within a few
 * degrees of vertical the reported roll swings through the whole circle on
 * sensor noise alone, and an overlay that spins is worse than an overlay that
 * admits it has nothing to say.
 */
private const val MIN_TILT_FRACTION = 0.17364817766693033

/** A second of samples: long enough to average, short enough to feel live. */
private const val WINDOW_MS = 1_000L

/** A ceiling on the window, so an unexpectedly fast sensor cannot grow it without bound. */
private const val MAX_SAMPLES = 200

/**
 * Enough samples to average before saying anything.
 *
 * At the 50 Hz this is fed at, it is the first 160 ms after the camera binds.
 */
private const val MIN_SAMPLES = 8

/**
 * The reading is published to a tenth of a degree.
 *
 * A tenth is 6.7 px of rise across a 3840-wide frame — below what anyone can
 * see, and comfortably above the 0.02° the averaging leaves behind. Finer would
 * be publishing the sensor's noise; coarser would round away a real tilt.
 */
private const val STEP_DEGREES = 0.1

/**
 * How far the reading must move before a new one is published (ADR-0024).
 *
 * Two steps rather than one, so a value sitting exactly on a boundary cannot
 * alternate between them and bump `rev` on every tick. 0.2° is 13 px across the
 * frame, which is still less than a person notices.
 */
private const val DEADBAND_DEGREES = 0.15

/**
 * The rms movement that counts as an unsteady mount.
 *
 * About three and a half times the 0.14° rms noise floor measured on the
 * reference Pixel 10 sitting still, so the sensor cannot raise this by itself;
 * low enough that a tripod leg being nudged does.
 */
private const val UNSTEADY_ENTER_DEGREES = 0.5

/** Below this it counts as quiet again — separated from the entry threshold so it cannot chatter. */
private const val UNSTEADY_EXIT_DEGREES = 0.3

/** How long it has to stay quiet before the reading goes back to steady. */
private const val UNSTEADY_CLEAR_MS = 3_000L
