package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.exposure.ExposureLoop
import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.exposure.IsoRange
import com.scenaristo.camera.domain.protocol.Ack
import com.scenaristo.camera.domain.protocol.CaptureSettings
import com.scenaristo.camera.domain.protocol.Command
import com.scenaristo.camera.domain.protocol.CommandName
import com.scenaristo.camera.domain.protocol.DeviceStatus
import com.scenaristo.camera.domain.protocol.Nack
import com.scenaristo.camera.domain.protocol.NackReason
import com.scenaristo.camera.domain.protocol.RecordingState
import com.scenaristo.camera.domain.protocol.Session
import com.scenaristo.camera.domain.protocol.SettingsPatch
import com.scenaristo.camera.domain.protocol.State
import com.scenaristo.camera.domain.protocol.ThermalState
import com.scenaristo.camera.domain.protocol.Warning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRD 6.3's manual shutter lock (#51).
 *
 * The ISO lock that briefly sat beside it was dropped (Davide, 2026-09-06): two
 * automatic responsiveness modes replace it. The shutter lock stays, because it
 * pins a *rung* of the flicker-safe ladder — the one thing the ladder cannot
 * infer from the scene, since both rungs are correct exposures and only the user
 * knows whether they want 1/50's motion blur or 1/100's crispness.
 */
class ShutterLockTest {

    private val loop = ExposureLoop(IsoRange(50, 6_400))



    // PRD 6.3, as amended by ADR-0005: "Never raise shutter beyond that one step;
    //           a manually locked shutter disables the step."
    @Test
    fun `PRD 6_3 - a locked shutter disables the flicker-safe step`() {
        // A scene two stops brighter than base ISO can hold at 1/50 s: an
        // unlocked loop steps the shutter to 1/100 s.
        val blown = 0.95
        val free = loop.start(GridFrequency.HZ_50)
        var unlocked = free
        repeat(10) { i -> unlocked = loop.onFrame(unlocked, blown, nowMs = 33L + i * 33) }
        assertEquals(100, unlocked.shutterHz, "precondition: an unlocked loop steps")

        val locked = loop.onShutterLockChanged(free, shutterLock = 50, nowMs = 0)
        var state = loop.onSensorEcho(locked, iso = locked.iso, shutterHz = 50)
        repeat(30) { i -> state = loop.onFrame(state, blown, nowMs = 100L + i * 33) }

        assertEquals(50, state.shutterHz, "the ladder stepped under a locked shutter")
    }

    // The step being unavailable is exactly when the user needs telling. PRD 6.3
    // disables the step for a locked shutter; it does not disable the warning.
    @Test
    fun `PRD 6_3 - overexposed with the shutter locked still warns`() {
        val locked = loop.onShutterLockChanged(
            loop.start(GridFrequency.HZ_50),
            shutterLock = 50,
            nowMs = 0,
        )
        var state = loop.onSensorEcho(locked, iso = locked.iso, shutterHz = 50)
        repeat(30) { i -> state = loop.onFrame(state, 0.95, nowMs = 100L + i * 33) }

        assertTrue(Warning.OVEREXPOSED_AT_BASE_ISO in state.warnings)
        assertEquals(50, state.shutterHz)
    }


    // A shutter lock is a rung, and only the grid's own ladder has rungs.
    // Anything else bands, so it is refused rather than clamped -- the same rule
    // the Kelvin range follows.
    @Test
    fun `PRD 6_3 - a shutter lock off the ladder is refused`() {
        val session = session()

        assertTrue(session.apply(patch(SettingsPatch(shutterLock = 100)), 0).reply is Ack)
        assertEquals(100, session.state.settings.shutterLock)

        val bad = session.apply(patch(SettingsPatch(shutterLock = 75)), 1).reply
        assertTrue(bad is Nack && bad.reason == NackReason.INVALID, "1/75 s bands on a 50 Hz grid")
        assertEquals(100, session.state.settings.shutterLock, "the refused value was not applied")
    }

    // Changing the grid moves the ladder under an existing lock. A lock left
    // pointing at the old ladder is the off-ladder shutter refused above.
    @Test
    fun `PRD 6_2 - changing the grid under a shutter lock is refused, not retuned`() {
        val session = session()
        session.apply(patch(SettingsPatch(shutterLock = 100)), 0)

        val moved = session.apply(patch(SettingsPatch(grid = GridFrequency.HZ_60)), 1).reply
        assertTrue(moved is Nack, "1/100 s is not a rung of the 60 Hz ladder")
        assertEquals(GridFrequency.HZ_50, session.state.settings.grid)
    }

    // CLEAR_LOCK is the third state a plain nullable cannot carry: null already
    // means "this patch says nothing about it".
    @Test
    fun `PRD 6_3 - CLEAR_LOCK releases a lock and absence leaves it alone`() {
        val session = session()
        session.apply(patch(SettingsPatch(shutterLock = 100)), 0)
        assertEquals(100, session.state.settings.shutterLock)

        session.apply(patch(SettingsPatch(whiteBalanceKelvin = 3200)), 1)
        assertEquals(100, session.state.settings.shutterLock, "an unrelated patch cleared the lock")

        session.apply(patch(SettingsPatch(shutterLock = SettingsPatch.CLEAR_LOCK)), 2)
        assertNull(session.state.settings.shutterLock)
    }

    private fun session() = Session(
        State(
            settings = CaptureSettings(
                grid = GridFrequency.HZ_50,
                shutterHz = 50,
                iso = 100,
                whiteBalanceKelvin = 5600,
                lensId = "0",
            ),
            recording = RecordingState(recording = false),
            device = DeviceStatus(50, false, ThermalState.NOMINAL, 100),
            serverTimeMs = 0,
        ),
    )

    private var next = 0
    private fun patch(args: SettingsPatch) =
        Command(id = "lock-${next++}", name = CommandName.SETTINGS_SET, args = args)
}
