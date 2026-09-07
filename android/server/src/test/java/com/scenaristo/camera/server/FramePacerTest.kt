package com.scenaristo.camera.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pacing that decides the preview's delivered frame rate (#109, ADR-0008).
 *
 * The numbers here are the reference device's, measured 2026-09-07: a 66 ms
 * interval and an 18 ms write, which the old fixed sleep turned into an 84 ms
 * period and 11.7 fps against a 15 fps cap.
 */
class FramePacerTest {

    private val interval = 66L

    /** The first send has no history, so it establishes the phase and waits a full interval. */
    @Test
    fun `the first frame waits a whole interval`() {
        assertEquals(interval, FramePacer(interval).afterSend(1_000))
    }

    /**
     * The bug, as arithmetic. A frame sent 18 ms after the previous slot must
     * wait the remaining 48 ms, not another 66 — otherwise the period is 84 ms
     * and the stream runs at 11.9 fps instead of 15.
     */
    @Test
    fun `the wait is the remainder of the interval, not a fresh one`() {
        val pacer = FramePacer(interval)
        pacer.afterSend(1_000)
        // Woken at 1066, wrote for 18 ms, so this send lands at 1084.
        assertEquals(48, pacer.afterSend(1_084))
    }

    /** Held to the period across many frames, rather than drifting by the write time each round. */
    @Test
    fun `the period holds over a long run`() {
        val pacer = FramePacer(interval)
        var now = 0L
        val writeMs = 18L
        var first = -1L
        var last = 0L
        repeat(100) { i ->
            val wait = pacer.afterSend(now)
            if (i == 0) first = now
            last = now
            now += wait + writeMs
        }
        val period = (last - first) / 99.0
        assertEquals(interval.toDouble(), period, 0.5)
    }

    /**
     * A write that overran its slot sends the next frame at once, and the
     * deadline re-bases on the present.
     *
     * Letting the missed slots accumulate would make the stream burst once a
     * slow patch cleared — paying back a latency debt with a flood, which is
     * worse for a live preview than simply having been late.
     */
    @Test
    fun `an overrun does not accumulate a debt`() {
        val pacer = FramePacer(interval)
        pacer.afterSend(1_000)
        // A 400 ms stall: five slots missed.
        assertEquals(0, pacer.afterSend(1_400))
        // The next frame is due one interval from now, not five frames at once.
        assertEquals(interval, pacer.afterSend(1_400))
    }

    /** A send exactly on its deadline still waits a full interval, never zero. */
    @Test
    fun `a send exactly on time waits a full interval`() {
        val pacer = FramePacer(interval)
        pacer.afterSend(1_000)
        assertEquals(interval, pacer.afterSend(1_066))
    }
}
