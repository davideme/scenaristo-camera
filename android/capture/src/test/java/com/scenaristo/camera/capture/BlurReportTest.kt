package com.scenaristo.camera.capture

import com.scenaristo.camera.domain.blur.BlurCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the background-blur measurement (ADR-0031).
 *
 * These are the rules the instrument's numbers pass through before they become
 * a claim, so they are worth having right before a phone is anywhere near it:
 * a probe that mis-buckets its phases would produce a confident table saying
 * the wrong thing, which is worse than no table.
 */
class BlurReportTest {

    private fun modes(vararg modes: ExtendedSceneMode, control: Boolean = true) =
        ExtendedSceneModes(modes.toList(), offersSceneModeControl = control)

    private fun mode(
        mode: Int,
        label: String = "BOKEH_CONTINUOUS",
        width: Int = 1920,
        height: Int = 1080,
    ) = ExtendedSceneMode(mode, label, width, height, zoomMin = 1.0, zoomMax = 2.0)

    @Test
    fun `ADR-0031 - a mode that survives a request which stopped asking for it reads as persisted`() {
        // What the reference Pixel 10 actually did on 2026-09-09: CameraX merges
        // runtime options *over* the ones set when the use case was built, so a
        // key set at bind time is still in the request even when a later runtime
        // one does not mention it. That is the device keeping its promise, and
        // reporting it as a failure would have sent the feature the wrong way.
        val accumulator = BlurAccumulator()
        repeat(60) { accumulator.record(BlurPhase.BIND_TIME, 2, null, 2, 1) }
        repeat(90) { accumulator.record(BlurPhase.AFTER_PUSH_WITHOUT_KEY, null, null, 2, 1) }

        val report = accumulator.report()
        assertEquals(2, report.size)
        assertTrue("blur held at bind time", report[0].held)
        assertTrue("the mode stayed on", report[1].persisted)
        assertFalse("and that is not a drop", report[1].dropped)
        assertEquals("PERSISTED", report[1].verdict)
    }

    @Test
    fun `ADR-0031 - a mode that goes when the request stops asking is off, not dropped`() {
        val accumulator = BlurAccumulator()
        repeat(90) { accumulator.record(BlurPhase.AFTER_PUSH_WITHOUT_KEY, null, null, SCENE_MODE_DISABLED, 1) }
        val phase = accumulator.report().single()
        assertFalse(phase.persisted)
        // Nothing was asked for and nothing happened. Not a failure.
        assertFalse(phase.dropped)
        assertEquals("off, as asked", phase.verdict)
    }

    @Test
    fun `ADR-0031 - a mode asked for and not delivered is the only real failure`() {
        val accumulator = BlurAccumulator()
        repeat(90) { accumulator.record(BlurPhase.AFTER_PUSH_WITH_KEY, 2, null, SCENE_MODE_DISABLED, 1) }
        val phase = accumulator.report().single()
        assertTrue(phase.dropped)
        assertFalse(phase.held)
        assertEquals("**dropped**", phase.verdict)
    }

    @Test
    fun `ADR-0031 - the key carried on every runtime request keeps the mode active`() {
        val accumulator = BlurAccumulator()
        repeat(30) { accumulator.record(BlurPhase.BIND_TIME, 2, null, 2, 1) }
        repeat(30) { accumulator.record(BlurPhase.AFTER_PUSH_WITHOUT_KEY, null, null, 2, 1) }
        repeat(30) { accumulator.record(BlurPhase.AFTER_PUSH_WITH_KEY, 2, null, 2, 1) }

        val report = accumulator.report()
        assertEquals(3, report.size)
        assertTrue("re-asking brings the mode back", report[2].held)
    }

    @Test
    fun `ADR-0031 - a mode that engages and then lapses is not reported as held`() {
        // The reason this bucket keeps a distribution rather than a worst frame:
        // a mode that holds for a second and then goes, and a mode that never
        // engaged, are different devices and must not read alike.
        val accumulator = BlurAccumulator()
        repeat(10) { accumulator.record(BlurPhase.BIND_TIME, 2, null, 2, 1) }
        repeat(80) { accumulator.record(BlurPhase.BIND_TIME, 2, null, SCENE_MODE_DISABLED, 1) }

        val phase = accumulator.report().single()
        assertFalse(phase.held)
        assertEquals(SCENE_MODE_DISABLED, phase.settledSceneMode)
        assertEquals(90, phase.frames)
    }

    @Test
    fun `ADR-0031 - a result that carries no scene mode at all is not a disabled one`() {
        // Absent and disabled mean different things, for the reason EchoVerdict
        // keeps ABSENT and MISMATCH apart: one camera never got the request, the
        // other got it and declined.
        val accumulator = BlurAccumulator()
        repeat(20) { accumulator.record(BlurPhase.BIND_TIME, 2, null, null, null) }

        val phase = accumulator.report().single()
        assertNull(phase.settledSceneMode)
        assertFalse(phase.held)
    }

    @Test
    fun `ADR-0031 - a phase that was never entered is not reported as empty`() {
        val accumulator = BlurAccumulator()
        repeat(5) { accumulator.record(BlurPhase.BIND_TIME, 2, null, 2, 1) }
        assertEquals(listOf(BlurPhase.BIND_TIME), accumulator.report().map { it.phase })
    }

    @Test
    fun `PRD 6_1 - measured frames per second span the frames, not the bind that preceded them`() {
        // A mode can halve the capture rate and still echo a perfect frame
        // duration on each frame it does deliver. PRD 6.1 asks for 30.00 fps of
        // footage, so the count is carried beside the echo rather than instead.
        //
        // Spanning the frames rather than the candidate is the correction the
        // first run forced: wall-clock elapsed included binding the session and
        // reported 27.5 fps for streams that were holding 30.
        val result = BlurCandidateResult(
            label = "C1",
            requestedSize = "3840x2160",
            echoes = LensEchoReport("0", "C1", emptyList(), framesObserved = 151),
            elapsedMs = 14_000,
            frameSpanMs = 10_000,
        )
        // 151 frames span 150 intervals.
        assertEquals(15.0, result.measuredFps!!, 0.001)
    }

    @Test
    fun `PRD 6_1 - a candidate with no frames reports no frame rate rather than zero`() {
        val nothing = BlurCandidateResult(
            label = "C0",
            requestedSize = "3840x2160",
            echoes = LensEchoReport("0", "C0", emptyList(), framesObserved = 0),
        )
        // Zero would read as "measured, and it was zero". It was not measured.
        assertNull(nothing.measuredFps)
    }

    @Test
    fun `a candidate whose bind was refused is a row in the table, not a thrown error`() {
        val refused = BlurCandidateResult(
            label = "C1 UHD30 + blur",
            requestedSize = "3840x2160",
            failure = "IllegalArgumentException: no supported surface combination",
        )
        assertFalse(refused.bound)
        val markdown = listOf(refused).markdown("0", "Pixel 10", modes(), BlurCapability())
        assertTrue(markdown.contains("C1 UHD30 + blur"))
        assertTrue(markdown.contains("**refused**"))
        assertTrue(markdown.contains("no supported surface combination"))
    }

    @Test
    fun `ADR-0031 - the report names the advertised ceiling and the size actually bound`() {
        // The two disagreeing is the single most interesting outcome the probe
        // can have, so both are always printed rather than one being derived.
        val advertised = modes(mode(2, width = 1920, height = 1080))
        val bound = BlurCandidateResult(
            label = "C1 UHD30 + blur",
            requestedSize = "3840x2160",
            boundResolution = "1920x1080",
        )
        val markdown = listOf(bound).markdown(
            "0",
            "Pixel 10",
            advertised,
            BlurCapability(advertised = true, continuousMode = true, maxWidthPx = 1920, maxHeightPx = 1080),
        )
        assertTrue("advertised ceiling", markdown.contains("1920x1080"))
        assertTrue("what was asked for", markdown.contains("3840x2160"))
        assertTrue("the zoom band the mode lives in", markdown.contains("1.0x-2.0x"))
    }

    @Test
    fun `ADR-0031 - still-capture bokeh is advertised but is not a continuous mode`() {
        val stillsOnly = modes(mode(1, label = "BOKEH_STILL_CAPTURE"))
        assertTrue(stillsOnly.advertised)
        assertNull(stillsOnly.modeFor(2))
        assertTrue(stillsOnly.markdown().contains("BOKEH_STILL_CAPTURE"))
    }

    @Test
    fun `ADR-0031 - a camera offering the mode but not the control value says so`() {
        // The fact that decides on its own whether the scene-mode key can be
        // asserted the way AOSP describes.
        val noControl = modes(mode(2), control = false)
        assertTrue(noControl.markdown().contains("Scene-mode control value offered: **no**"))
    }
}
