package com.scenaristo.camera.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRD 6.8: "quality and frame rate degrade automatically under bandwidth
 * pressure" — rather than the preview freezing, which is the one failure a
 * framing aid must not have, because a frozen preview looks exactly like a
 * still room.
 *
 * The interval is ADR-0008's 66 ms throughout.
 */
class PreviewQualityTest {

    private fun controller() = PreviewQuality(intervalMs = 66)

    @Test
    fun `it starts at ADR-0008's quality`() {
        assertEquals(80, controller().quality)
    }

    /**
     * One slow write is enough. By the time a write has outlasted its own frame
     * interval the preview is already behind, and waiting for a second opinion
     * spends the very latency being defended.
     */
    @Test
    fun `a single write that outlasts the interval lowers the quality`() {
        val q = controller()
        assertTrue(q.onFrameWritten(writeMs = 90))
        assertEquals(70, q.quality)
    }

    @Test
    fun `sustained pressure walks it down to the floor and stops`() {
        val q = controller()
        repeat(20) { q.onFrameWritten(writeMs = 200) }
        assertEquals(35, q.quality)
        // At the floor it reports no further change, so nothing is told to
        // re-set a value that did not move.
        assertFalse(q.onFrameWritten(writeMs = 200))
        assertEquals(35, q.quality)
    }

    /**
     * Recovery is deliberately slower than degradation. A link that recovered
     * for one frame has not recovered, and a quality that chases every
     * fluctuation is a preview that visibly pulses.
     */
    @Test
    fun `one fast write does not raise it`() {
        val q = controller()
        q.onFrameWritten(writeMs = 90)
        assertFalse(q.onFrameWritten(writeMs = 5))
        assertEquals(70, q.quality)
    }

    @Test
    fun `a sustained calm run raises it a step`() {
        val q = controller()
        q.onFrameWritten(writeMs = 90)
        repeat(19) { assertFalse(q.onFrameWritten(writeMs = 5)) }
        assertTrue(q.onFrameWritten(writeMs = 5))
        assertEquals(80, q.quality)
    }

    /**
     * A write taking most of its slot is keeping up only just. Treating that as
     * calm is what starts the oscillation the hysteresis exists to prevent.
     */
    @Test
    fun `a write using most of its slot counts as neither`() {
        val q = controller()
        q.onFrameWritten(writeMs = 90)
        // 40 ms of a 66 ms interval: not slow enough to drop, not calm enough
        // to count towards a recovery.
        repeat(50) { assertFalse(q.onFrameWritten(writeMs = 40)) }
        assertEquals(70, q.quality)
    }

    /** A slow write in the middle of a calm run resets the run, rather than shortening it. */
    @Test
    fun `pressure during recovery restarts the count`() {
        val q = controller()
        q.onFrameWritten(writeMs = 90)
        repeat(19) { q.onFrameWritten(writeMs = 5) }
        q.onFrameWritten(writeMs = 200)
        assertEquals(60, q.quality)
        repeat(19) { assertFalse(q.onFrameWritten(writeMs = 5)) }
        assertTrue(q.onFrameWritten(writeMs = 5))
        assertEquals(70, q.quality)
    }

    @Test
    fun `it never climbs above the ceiling`() {
        val q = controller()
        repeat(200) { q.onFrameWritten(writeMs = 1) }
        assertEquals(80, q.quality)
    }
}
