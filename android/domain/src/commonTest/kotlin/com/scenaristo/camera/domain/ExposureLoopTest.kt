package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.exposure.ExposureConfig
import com.scenaristo.camera.domain.exposure.ExposureLoop
import com.scenaristo.camera.domain.exposure.ExposureState
import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.exposure.IsoRange
import com.scenaristo.camera.domain.exposure.shutterLadder
import com.scenaristo.camera.domain.protocol.Warning
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRD 6.3's acceptance criteria, checked against a simulated room.
 *
 * The room is one number — the ISO that would land the face on target at the
 * grid's default shutter — and the simulator inverts exactly the model the loop
 * assumes, so any error the loop fails to remove is a fault in the controller
 * rather than a disagreement about photometry. Everything PRD 6.3 puts a number
 * on (settle within 2 s, oscillation under one stop, the warning within 1 s) is
 * a number here too.
 *
 * These are the traces Phase 0 #25 re-runs against the reference device; when it
 * reports, the constants in [ExposureConfig] change and these tests are what say
 * whether the promises still hold.
 */
class ExposureLoopTest {

    // PRD 6.3: "Given a constant scene, when recording, then ISO settles within
    //           2 seconds and does not oscillate by more than one stop."
    @Test
    fun `PRD 6_3 - a constant scene settles within 2 s and does not oscillate by more than one stop`() {
        val room = Room(needsIso = 400.0)

        room.runFor(2_000)
        assertTrue(
            abs(log2(room.state.iso / 400.0)) <= 0.25,
            "after 2 s ISO is ${room.state.iso}, not within a quarter stop of 400",
        )

        val settled = room.record(3_000)
        assertTrue(
            log2(settled.max().toDouble() / settled.min().toDouble()) <= 1.0,
            "ISO oscillated between ${settled.min()} and ${settled.max()}, more than one stop",
        )
    }

    // PRD 6.3: "Given the scene brightens, then ISO decreases, shutter stays
    //           fixed, frame rate stays at 30."
    @Test
    fun `PRD 6_3 - when the scene brightens ISO decreases and the shutter stays fixed`() {
        val room = Room(needsIso = 800.0)
        room.runFor(1_000)
        val before = room.state.iso
        val shutterBefore = room.state.shutterHz

        room.needsIso = 200.0 // two stops brighter
        room.runFor(4_000)

        assertTrue(room.state.iso < before, "ISO did not fall: $before -> ${room.state.iso}")
        assertEquals(shutterBefore, room.state.shutterHz, "the shutter moved when only ISO should have")
        assertTrue(
            abs(log2(room.state.iso / 200.0)) <= 0.25,
            "ISO settled at ${room.state.iso}, not near 200",
        )
    }

    // PRD 6.3: "Given the scene is overexposed at base ISO at 1/50 s, then the
    //           shutter steps to 1/100 s, the readout shows it on phone and web,
    //           and no warning is shown." (ADR-0005 added this rung.)
    @Test
    fun `PRD 6_3 - overexposed at base ISO steps to the flicker-safe rung with no warning`() {
        // A stop brighter than base ISO can hold at 1/50 s, and exactly what
        // 1/100 s at base ISO absorbs.
        val room = Room(needsIso = 25.0)
        room.runFor(2_000)

        assertEquals(100, room.state.shutterHz, "the shutter did not step to 1/100 s")
        assertTrue(room.state.stepped, "the step is what the readout reports (UI-5)")
        assertEquals(BASE_ISO, room.state.iso, "ISO left base when the shutter should have absorbed the light")
        assertTrue(room.state.warnings.isEmpty(), "PRD 6.3 shows no warning when the step succeeds")
    }

    // PRD 6.3: "Given the scene is overexposed at base ISO and at the flicker-safe
    //           step, then the warning appears within 1 second on phone and web."
    @Test
    fun `PRD 6_3 - overexposed even at the flicker-safe step warns within 1 s`() {
        val room = Room(needsIso = 12.5) // two stops past what base ISO can hold

        val warnedAtMs = room.runUntil(3_000) { Warning.OVEREXPOSED_AT_BASE_ISO in it.warnings }

        assertTrue(warnedAtMs != null && warnedAtMs <= 1_000, "warned at $warnedAtMs ms, not within 1 s")
        assertEquals(100, room.state.shutterHz, "the app warns only after taking its one step")
        assertEquals(BASE_ISO, room.state.iso)
    }

    // PRD 6.3: "if ISO exceeds a per-device noise threshold (default ISO 800),
    //           show 'Low light: add light to reduce noise'."
    // Issue #8: "Warnings clear automatically when the condition ends."
    @Test
    fun `PRD 6_3 - ISO above the noise threshold warns and the warning clears itself`() {
        val room = Room(needsIso = 3_200.0)
        room.runFor(1_000)

        assertTrue(room.state.iso > 800, "the room should have driven ISO past the threshold")
        assertContains(room.state.warnings, Warning.TOO_DARK)

        room.needsIso = 200.0
        room.runFor(6_000)

        assertFalse(Warning.TOO_DARK in room.state.warnings, "the warning outlived the low light")
    }

    // PRD 6.1: "Frame rate does not drop below 30 fps in low light (the driver
    //           must not extend exposure past the locked shutter)."
    // PRD 6.3: "Too-dark ... Do not slow the shutter."
    @Test
    fun `PRD 6_1 - the shutter never slows in the dark however dark it gets`() {
        val room = Room(needsIso = 100_000.0) // far past anything the sensor can reach
        room.runFor(10_000)

        assertEquals(50, room.state.shutterHz, "the shutter was extended past the locked value")
        assertEquals(MAX_ISO, room.state.iso, "ISO should be pinned at the sensor's ceiling")
        assertContains(room.state.warnings, Warning.TOO_DARK)
    }

    // ADR-0005: "a maximum slew of 1 stop per second".
    @Test
    fun `ADR-0005 - ISO never travels faster than one stop per second`() {
        val room = Room(needsIso = 100.0)
        room.runFor(1_000)

        room.needsIso = 6_400.0 // six stops darker, so the loop is at full tilt
        val trace = room.record(5_000)

        val perSecond = FRAMES_PER_SECOND
        for (i in 0 until trace.size - perSecond) {
            val travelled = abs(log2(trace[i + perSecond].toDouble() / trace[i].toDouble()))
            assertTrue(
                travelled <= 1.0 + TOLERANCE_EV,
                "ISO moved $travelled stops in one second, from ${trace[i]} to ${trace[i + perSecond]}",
            )
        }
    }

    // ADR-0005: "After each change the controller waits until a CaptureResult
    //            from the interop callback reports the new SENSOR_SENSITIVITY
    //            before measuring again, so pipeline latency does not cause
    //            oscillation."
    @Test
    fun `ADR-0005 - frames metered while a change is in flight are ignored`() {
        val loop = ExposureLoop(IsoRange(BASE_ISO, MAX_ISO))
        val inFlight = loop.start(GridFrequency.HZ_50)
            .copy(iso = 400, acquired = true, awaitingEcho = true, changedAtMs = 0)

        val after = loop.onFrame(inFlight, luma = 0.01, nowMs = 33)
        assertEquals(inFlight, after, "a frame exposed with the old ISO moved the loop")

        // Only the matching echo releases it; an in-flight result for the old
        // value must not.
        assertEquals(inFlight, loop.onSensorEcho(inFlight, iso = 100, shutterHz = 50))
        val released = loop.onSensorEcho(inFlight, iso = 400, shutterHz = 50)
        assertFalse(released.awaitingEcho)
    }

    // ADR-0005: "a +/- 0.15 EV dead-band". Small scene noise is not a change to chase.
    @Test
    fun `ADR-0005 - an error inside the dead band moves nothing`() {
        val room = Room(needsIso = 400.0)
        room.runFor(1_000)
        val settled = room.state.iso

        room.needsIso = 400.0 * 2.0.pow(0.1) // a tenth of a stop
        room.runFor(3_000)

        assertEquals(settled, room.state.iso, "the loop chased a tenth of a stop")
    }

    // PRD 6.1 makes 1/50 s (or 1/60) the default and ADR-0005 makes the step an
    // exception, so the app owes the user a return to the default when the light
    // no longer needs the step. The swap is exposure-neutral, so it must not
    // disturb what the loop already settled on.
    @Test
    fun `PRD 6_1 - the shutter returns to the default rung once the light allows`() {
        val room = Room(needsIso = 25.0)
        room.runFor(2_000)
        assertEquals(100, room.state.shutterHz, "precondition: the loop is on the step")

        room.needsIso = 400.0 // the room darkened; the step is no longer earned
        room.runFor(8_000)

        assertEquals(50, room.state.shutterHz, "the shutter never came back to PRD 6.1's default")
        assertFalse(room.state.stepped)
        assertTrue(
            abs(log2(room.state.iso / 400.0)) <= 0.25,
            "the return to 1/50 s changed the exposure; ISO ended at ${room.state.iso}",
        )
    }

    // PRD 6.2: the grid override is available at any time, and the shutter
    // follows it. Whichever rung the session is on, it stays on it.
    @Test
    fun `PRD 6_2 - a grid override moves the shutter to the same rung of the other ladder`() {
        val loop = ExposureLoop(IsoRange(BASE_ISO, MAX_ISO))
        val stepped = loop.start(GridFrequency.HZ_50).copy(rung = 1, iso = 100, acquired = true)
        assertEquals(100, stepped.shutterHz)

        val switched = loop.onGridChanged(stepped, GridFrequency.HZ_60, nowMs = 1_000)

        assertEquals(120, switched.shutterHz)
        assertEquals(1, switched.rung)
        assertTrue(switched.awaitingEcho, "the new shutter has to be applied and echoed")
    }

    /**
     * A room, expressed as the ISO that would put the face on target at the
     * grid's default shutter, plus the loop watching it.
     *
     * [luma] is the exact inverse of [ExposureLoop]'s own model: linear exposure
     * relative to the target, gamma-encoded back into a preview pixel value. The
     * sensor echo arrives [ECHO_DELAY_FRAMES] frames after a change, which is the
     * pipeline latency ADR-0005 says the loop must not meter through.
     */
    private class Room(
        var needsIso: Double,
        grid: GridFrequency = GridFrequency.HZ_50,
        private val config: ExposureConfig = ExposureConfig(),
    ) {
        private val loop = ExposureLoop(IsoRange(BASE_ISO, MAX_ISO), config)
        private val ladder = shutterLadder(grid)
        private var inFlightFrames = 0
        private var nowMs = 0L

        var state: ExposureState = loop.start(grid)
            private set

        fun tick() {
            nowMs += FRAME_MS
            if (state.awaitingEcho) {
                inFlightFrames++
                if (inFlightFrames >= ECHO_DELAY_FRAMES) {
                    state = loop.onSensorEcho(state, state.iso, state.shutterHz)
                    inFlightFrames = 0
                }
            }
            state = loop.onFrame(state, luma(), nowMs)
            assertContains(ladder, state.shutterHz, "the shutter left the flicker-safe ladder")
        }

        fun runFor(ms: Long) {
            repeat((ms / FRAME_MS).toInt()) { tick() }
        }

        /** ISO at every frame for [ms], for the oscillation and slew assertions. */
        fun record(ms: Long): List<Int> = buildList {
            repeat((ms / FRAME_MS).toInt()) {
                tick()
                add(state.iso)
            }
        }

        /** Milliseconds until [predicate] first holds, or null if it never does. */
        fun runUntil(ms: Long, predicate: (ExposureState) -> Boolean): Long? {
            val start = nowMs
            repeat((ms / FRAME_MS).toInt()) {
                tick()
                if (predicate(state)) return nowMs - start
            }
            return null
        }

        private fun luma(): Double {
            val reference = needsIso / ladder.first()
            val actual = state.iso.toDouble() / state.shutterHz
            val linear = config.targetLuma.pow(config.toneGamma) * (actual / reference)
            return linear.coerceAtMost(1.0).pow(1.0 / config.toneGamma)
        }
    }

    private companion object {
        const val BASE_ISO = 50
        const val MAX_ISO = 6_400
        const val FRAME_MS = 33L
        const val FRAMES_PER_SECOND = 30
        const val ECHO_DELAY_FRAMES = 2
        const val TOLERANCE_EV = 1.0 / 6.0
    }
}
