package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.exposure.ExposureLoop
import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.exposure.IsoRange
import com.scenaristo.camera.domain.protocol.Warning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Resuming the exposure loop after the camera was released and rebound
 * (ADR-0025).
 *
 * PRD 6.3 asks for the lowest noise the light allows, and PRD 6.1 fixes the
 * shutter. A wake that re-climbs from the sensor floor satisfies neither for the
 * first second a viewer is looking at.
 */
class ExposureResumeTest {

    private val range = IsoRange(min = 50, max = 6400)
    private val loop = ExposureLoop(range)

    // PRD 6.3: "lowest possible ISO for the light". A settled ISO is the answer
    // to that question, and it does not stop being the answer because the camera
    // was released while nobody was watching.
    @Test
    fun `PRD 6_3 - the settled ISO survives a release and rebind`() {
        val settled = loop.start(GridFrequency.HZ_50).copy(iso = 1600, acquired = true)
        val resumed = loop.resume(settled, GridFrequency.HZ_50)
        assertEquals(1600, resumed.iso)
    }

    @Test
    fun `a fresh start still opens at the sensor floor`() {
        assertEquals(range.min, loop.start(GridFrequency.HZ_50).iso)
    }

    // PRD 6.1 and 6.2: the rung is an index into the grid's own ladder, so the
    // same index is a different shutter on the other grid. Carrying it across a
    // grid change would be the one thing the product exists to prevent: banding.
    @Test
    fun `PRD 6_2 - the shutter rung is dropped when the grid changed while asleep`() {
        val stepped = loop.start(GridFrequency.HZ_50).copy(rung = 1, iso = 800)
        val resumed = loop.resume(stepped, GridFrequency.HZ_60)
        assertEquals(0, resumed.rung, "the rung must not carry across ladders")
        assertEquals(GridFrequency.HZ_60, resumed.grid)
        assertEquals(800, resumed.iso, "the ISO is still valid, and still carried")
    }

    @Test
    fun `the shutter rung survives when the grid is unchanged`() {
        val stepped = loop.start(GridFrequency.HZ_50).copy(rung = 1)
        assertEquals(1, loop.resume(stepped, GridFrequency.HZ_50).rung)
    }

    /**
     * An ISO carried from a lens with a wider range must land inside this one's,
     * or the first push asks the sensor for a sensitivity it does not have.
     */
    @Test
    fun `an out-of-range ISO is clamped to what this lens can do`() {
        val wide = loop.start(GridFrequency.HZ_50).copy(iso = 100_000)
        assertEquals(range.max, loop.resume(wide, GridFrequency.HZ_50).iso)

        val tiny = loop.start(GridFrequency.HZ_50).copy(iso = 1)
        assertEquals(range.min, loop.resume(tiny, GridFrequency.HZ_50).iso)
    }

    /**
     * Everything that describes a conversation with the previous sensor has to
     * start clean. An `awaitingEcho` carried over is the worst of them: the new
     * camera will never send the echo the old request was waiting for, so the
     * loop would drop every frame it metered and never move again.
     */
    @Test
    fun `nothing about the previous sensor is carried over`() {
        val mid = loop.start(GridFrequency.HZ_50).copy(
            iso = 3200,
            acquired = true,
            awaitingEcho = true,
            ignoredEchoes = 4,
            changedAtMs = 123_456L,
            errorEv = -1.5,
            warnings = setOf(Warning.TOO_DARK),
        )
        val resumed = loop.resume(mid, GridFrequency.HZ_50)

        assertFalse(resumed.awaitingEcho, "a stale in-flight request would stall the loop")
        assertEquals(0, resumed.ignoredEchoes)
        assertNull(resumed.changedAtMs)
        assertEquals(0.0, resumed.errorEv, absoluteTolerance = 0.0)
        assertTrue(resumed.warnings.isEmpty())
        assertFalse(resumed.acquired, "the first frame of the new session may snap")
    }

    // ADR-0022: the damping mode follows the take, and a resumed loop has no
    // take. The service pushes the real recording state straight after binding.
    @Test
    fun `a resumed loop is not recording until it is told otherwise`() {
        val during = loop.start(GridFrequency.HZ_50).copy(recording = true, iso = 400)
        assertFalse(loop.resume(during, GridFrequency.HZ_50).recording)
    }

    /**
     * ADR-0023's opt-in hold is the user's stored answer, not something the loop
     * discovered, so the caller's current value wins over whatever was true when
     * the camera was released -- exactly as it does on a cold start.
     */
    @Test
    fun `the take-long exposure hold comes from the caller, not the old state`() {
        val wasLocked = loop.start(GridFrequency.HZ_50, lockWhileRecording = true).copy(iso = 800)
        assertFalse(loop.resume(wasLocked, GridFrequency.HZ_50, lockWhileRecording = false).lockWhileRecording)

        val wasNot = loop.start(GridFrequency.HZ_50, lockWhileRecording = false).copy(iso = 800)
        assertTrue(loop.resume(wasNot, GridFrequency.HZ_50, lockWhileRecording = true).lockWhileRecording)
    }

    // PRD 6.3: a pinned shutter is the user's, not the loop's, and it is restored
    // from the state document after the bind rather than carried in the loop --
    // so a resumed state must not claim one.
    @Test
    fun `PRD 6_3 - a resumed loop carries no shutter lock of its own`() {
        val locked = loop.start(GridFrequency.HZ_50).copy(shutterLock = 100, rung = 1)
        assertNull(loop.resume(locked, GridFrequency.HZ_50).shutterLock)
    }
}
