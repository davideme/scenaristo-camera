package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.mount.GravitySample
import com.scenaristo.camera.domain.mount.MountFilter
import com.scenaristo.camera.domain.mount.ScreenRotation
import com.scenaristo.camera.domain.mount.attitudeOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRD 6.11's mount level, which is one piece of geometry and three thresholds.
 *
 * The geometry is what these tests exist for. Every axis convention in it has a
 * plausible-looking wrong version — a sign flip, a swapped pair, a rotation
 * applied the wrong way round — and each of them produces a number that is the
 * right *size*, so nothing here would look broken. It would simply tell someone
 * to level their tripod in the wrong direction.
 *
 * That is why the load-bearing case below is not synthetic: it is the reading
 * taken off the reference Pixel 10 on 2026-09-07 while it sat on its mount,
 * together with what a person standing next to it could see. A test written
 * from the same algebra as the code cannot catch the algebra being wrong.
 */
class MountLevelTest {

    /**
     * The accelerometer on the reference Pixel 10, landscape on its mount,
     * averaged over 128 samples spanning 35 s (2026-09-07).
     *
     * `dumpsys sensorservice` reported x = +9.7373, y = -0.0970, z = -0.8836,
     * with the display at `ROTATION_90`. Observed at the same time: the phone
     * was very slightly off level, and leaning back so the rear camera pointed
     * a few degrees above horizontal.
     */
    private val referencePixel10 = GravitySample(x = 9.7373, y = -0.0970, z = -0.8836)

    @Test
    fun `PRD 6_11 - a level landscape phone reads zero roll and zero pitch`() {
        val attitude = assertNotNull(
            attitudeOf(GravitySample(9.81, 0.0, 0.0), ScreenRotation.DEGREES_90),
        )
        assertEquals(0.0, attitude.rollDegrees)
        assertEquals(0.0, attitude.pitchDegrees)
    }

    @Test
    fun `PRD 6_11 - the reference Pixel 10 reads a fraction of a degree off level`() {
        val attitude = assertNotNull(attitudeOf(referencePixel10, ScreenRotation.DEGREES_90))
        assertClose(-0.57, attitude.rollDegrees)
        assertClose(5.19, attitude.pitchDegrees)
    }

    /**
     * The sign, stated as a person would see it rather than as the formula
     * produces it.
     *
     * Landscape, and the phone is rolled so that the *right* of frame drops.
     * With the display at `ROTATION_90` the right of frame is the device's -y
     * axis, so dropping it tips the sample towards +y. The world's up then leans
     * towards the left of the picture, which this convention calls a positive
     * roll.
     *
     * If this test and the overlay ever disagree, the overlay is what is wrong:
     * this is the direction that was checked against a real phone.
     */
    @Test
    fun `PRD 6_11 - the right of frame dropping reads as a positive roll`() {
        val attitude = assertNotNull(
            attitudeOf(GravitySample(9.66097, 1.70349, 0.0), ScreenRotation.DEGREES_90),
        )
        assertClose(10.0, attitude.rollDegrees)
    }

    /**
     * Turning the phone and its interface together must not change the picture.
     *
     * The same physical attitude, reached the other way up: the phone rotated
     * 180° on its mount (which negates x and y) with the display following it to
     * `ROTATION_270`. The horizon in the recorded frame is in exactly the same
     * place, and the reading has to agree — otherwise the overlay would point
     * the wrong way for half of the people who set their tripod up the other way
     * round.
     */
    @Test
    fun `PRD 6_11 - the same shot reached the other way up reads the same`() {
        val ninety = assertNotNull(attitudeOf(referencePixel10, ScreenRotation.DEGREES_90))
        val upsideDown = GravitySample(
            x = -referencePixel10.x,
            y = -referencePixel10.y,
            z = referencePixel10.z,
        )
        val twoSeventy = assertNotNull(attitudeOf(upsideDown, ScreenRotation.DEGREES_270))
        assertClose(ninety.rollDegrees, twoSeventy.rollDegrees)
        assertClose(ninety.pitchDegrees, twoSeventy.pitchDegrees)
    }

    /**
     * The same phone with the interface left in the *other* landscape is a
     * different picture: the frame is upside down, so the horizon is too.
     */
    @Test
    fun `PRD 6_11 - the interface turned without the phone puts the horizon upside down`() {
        val ninety = assertNotNull(attitudeOf(referencePixel10, ScreenRotation.DEGREES_90))
        val twoSeventy = assertNotNull(attitudeOf(referencePixel10, ScreenRotation.DEGREES_270))
        assertClose(180.0 + ninety.rollDegrees, twoSeventy.rollDegrees)
    }

    @Test
    fun `PRD 6_11 - portrait reads the roll off the other axis`() {
        val attitude = assertNotNull(
            attitudeOf(GravitySample(0.0, 9.81, 0.0), ScreenRotation.DEGREES_0),
        )
        assertEquals(0.0, attitude.rollDegrees)
    }

    /**
     * A phone flat on a desk has no horizon to be level with, and saying
     * anything about its roll would be reporting sensor noise as advice.
     */
    @Test
    fun `PRD 6_11 - a phone lying flat reports no attitude rather than a roll`() {
        assertNull(attitudeOf(GravitySample(0.02, -0.01, 9.81), ScreenRotation.DEGREES_90))
        assertNull(attitudeOf(GravitySample(0.02, -0.01, -9.81), ScreenRotation.DEGREES_90))
    }

    @Test
    fun `PRD 6_11 - nothing is published before there is enough to average`() {
        val filter = MountFilter()
        filter.onSample(referencePixel10, ScreenRotation.DEGREES_90, 0L)
        assertEquals(false, filter.reading().measuring)
    }

    @Test
    fun `PRD 6_11 - a phone on a mount reads level, steady and measuring`() {
        val filter = MountFilter()
        feedStatic(filter, seconds = 1)
        val reading = filter.reading()
        assertTrue(reading.measuring)
        assertTrue(reading.steady)
        assertClose(-0.6, reading.rollDegrees, tolerance = 0.06)
        assertClose(5.2, reading.pitchDegrees, tolerance = 0.06)
    }

    /**
     * ADR-0024's failure mode, as a test.
     *
     * The histogram advanced `rev` 27 times a second by publishing every change
     * of a value that never stops changing. A tilt angle is the same shape of
     * thing, so the filter has to hold still when the phone does — with the
     * *measured* noise, not with a clean signal that would pass either way.
     */
    @Test
    fun `PRD 6_11 - a still phone publishes one reading and then stops changing`() {
        val filter = MountFilter()
        feedStatic(filter, seconds = 1)
        val first = filter.reading()

        repeat(20) { tick ->
            feedStatic(filter, seconds = 1, startMs = 1_000L + tick * 1_000L)
            val next = filter.reading()
            assertEquals(first.rollDegrees, next.rollDegrees, "roll moved on tick $tick")
            assertEquals(first.pitchDegrees, next.pitchDegrees, "pitch moved on tick $tick")
        }
    }

    @Test
    fun `PRD 6_11 - a real tilt is published even though noise is not`() {
        val filter = MountFilter()
        feedStatic(filter, seconds = 1)
        val before = filter.reading().rollDegrees

        // A degree and a half of roll -- far less than anyone would call
        // obviously crooked, and far more than the deadband.
        feedStatic(filter, seconds = 1, startMs = 1_000L, extraRollDegrees = 1.5)
        val after = filter.reading().rollDegrees

        assertClose(before + 1.5, after, tolerance = 0.15)
    }

    @Test
    fun `PRD 6_11 - a shaken mount reads unsteady and clears only after it settles`() {
        val filter = MountFilter()
        feedStatic(filter, seconds = 1)
        assertTrue(filter.reading().steady)

        // One second of the mount moving about a degree, which is roughly a
        // finger tapping the tripod leg.
        var at = 1_000L
        repeat(SAMPLES_PER_SECOND) { i ->
            val wobble = if (i % 2 == 0) 1.0 else -1.0
            filter.onSample(rolled(wobble), ScreenRotation.DEGREES_90, at)
            at += SAMPLE_INTERVAL_MS
        }
        assertTrue(!filter.reading().steady, "a shaken mount should not read steady")

        // It does not clear the instant the shaking stops: a reading that
        // flickers while a hand is near the tripod is worth nothing.
        feedStatic(filter, seconds = 1, startMs = at)
        assertTrue(!filter.reading().steady, "cleared too eagerly")

        feedStatic(filter, seconds = 3, startMs = at + 1_000L)
        assertTrue(filter.reading().steady, "never went back to steady")
    }

    /**
     * The mount is not the only thing that stops: the camera does too
     * (ADR-0025), and a take switches the sensor off entirely (ADR-0023). What
     * must never happen is the last angle measured before that surviving as if
     * it were current.
     */
    @Test
    fun `PRD 6_11 - a reset leaves nothing behind to publish`() {
        val filter = MountFilter()
        feedStatic(filter, seconds = 1)
        assertTrue(filter.reading().measuring)

        filter.reset()

        val reading = filter.reading()
        assertEquals(false, reading.measuring)
        assertEquals(0.0, reading.rollDegrees)
    }

    /** Feeds samples at the rate the phone delivers them, with the noise it delivers. */
    private fun feedStatic(
        filter: MountFilter,
        seconds: Int,
        startMs: Long = 0L,
        extraRollDegrees: Double = 0.0,
    ) {
        var at = startMs
        repeat(seconds * SAMPLES_PER_SECOND) { i ->
            filter.onSample(noisy(i, extraRollDegrees), ScreenRotation.DEGREES_90, at)
            at += SAMPLE_INTERVAL_MS
        }
    }

    /**
     * The reference sample plus noise of the size the reference device actually
     * produced: 0.14° rms, 0.36° worst case.
     *
     * Deterministic rather than random, so a failure is reproducible, and shaped
     * so successive samples do not repeat -- an oscillation the filter could
     * average to exactly zero would make the deadband test pass for the wrong
     * reason.
     */
    private fun noisy(i: Int, extraRollDegrees: Double): GravitySample {
        val phase = (i * 7 % 11) - 5 // -5..5, and never the same twice in a row
        return rolled(extraRollDegrees + phase * 0.03)
    }

    /**
     * The reference sample rolled by [degrees] in the image plane.
     *
     * At `ROTATION_90` the image's up is the device's +x and its right is -y, so
     * a roll rotates the sample in the x-y plane. Written out rather than
     * calling the production code, so the test's inputs do not inherit the
     * convention they are checking. Note that it leaves z alone, so the noise
     * this generates is roll-only -- which is enough, because roll is the axis
     * the thresholds are written in.
     */
    private fun rolled(degrees: Double): GravitySample {
        val radians = degrees * 3.141592653589793 / 180.0
        val sin = kotlin.math.sin(radians)
        val cos = kotlin.math.cos(radians)
        val x = referencePixel10.x
        val y = referencePixel10.y
        return GravitySample(
            x = x * cos - y * sin,
            y = x * sin + y * cos,
            z = referencePixel10.z,
        )
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 0.01) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "expected $expected +/- $tolerance but was $actual",
        )
    }

    private companion object {
        /** `SENSOR_DELAY_GAME`, which is what `MountSensor` asks Android for. */
        const val SAMPLES_PER_SECOND = 50
        const val SAMPLE_INTERVAL_MS = 20L
    }
}
