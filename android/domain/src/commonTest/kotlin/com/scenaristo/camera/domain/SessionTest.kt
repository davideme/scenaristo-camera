package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.protocol.Ack
import com.scenaristo.camera.domain.protocol.CaptureSettings
import com.scenaristo.camera.domain.protocol.Command
import com.scenaristo.camera.domain.protocol.CommandName
import com.scenaristo.camera.domain.protocol.DeviceStatus
import com.scenaristo.camera.domain.protocol.Focus
import com.scenaristo.camera.domain.protocol.FocusMode
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007's guarantees, as tests. Every one of these is a way PRD 6.8's
 * "multiple browsers, last write wins" goes wrong when taken literally.
 */
class SessionTest {

    private fun idle() = State(
        settings = CaptureSettings(
            grid = GridFrequency.HZ_50,
            shutterHz = 50,
            iso = 100,
            whiteBalanceKelvin = 5600,
            lensId = "0",
        ),
        recording = RecordingState(recording = false),
        device = DeviceStatus(
            batteryPercent = 80,
            charging = false,
            thermal = ThermalState.NOMINAL,
            storageMinutesRemaining = 120,
        ),
        serverTimeMs = 0,
    )

    private fun cmd(name: CommandName, id: String = "c1", expectRev: Int? = null, args: SettingsPatch? = null) =
        Command(id = id, name = name, expectRev = expectRev, args = args)

    private fun focusCmd(focus: Focus?, id: String = "f1") =
        Command(id = id, name = CommandName.FOCUS_SET, focus = focus)

    // ADR-0007: "A repeated id within 10 s returns the original ack without
    // re-applying." The failure this prevents: a browser resends after a dropped
    // connection and starts a second recording over the first.
    @Test
    fun `ADR-0007 - a repeated command id replays the original answer`() {
        val session = Session(idle())
        val first = session.apply(cmd(CommandName.RECORD_START), nowMs = 1_000)
        val revAfterFirst = session.rev

        val retry = session.apply(cmd(CommandName.RECORD_START), nowMs = 1_500)

        assertEquals(first.reply, retry.reply)
        assertEquals(revAfterFirst, session.rev)
        assertFalse(retry.broadcast, "a replay changed nothing, so no client needs telling")
    }

    // The window is 10 s; a genuinely new command reusing an old id after that is
    // treated as new, because holding ids forever would leak.
    @Test
    fun `a repeated id outside the window is applied again`() {
        val session = Session(idle())
        session.apply(cmd(CommandName.RECORD_START), nowMs = 0)
        session.apply(cmd(CommandName.RECORD_STOP, id = "c2"), nowMs = 1_000)

        val late = session.apply(cmd(CommandName.RECORD_START), nowMs = 20_000)

        assertTrue(session.state.recording.recording)
        assertTrue(late.broadcast)
    }

    // ADR-0007: record.start is not a toggle. Twice must not stop it.
    @Test
    fun `ADR-0007 - record_start while recording acks without changing anything`() {
        val session = Session(idle())
        session.apply(cmd(CommandName.RECORD_START), nowMs = 0)
        val revAfterStart = session.rev

        val again = session.apply(cmd(CommandName.RECORD_START, id = "c2"), nowMs = 1_000)

        assertTrue(session.state.recording.recording, "still recording")
        assertTrue(again.reply is Ack)
        assertEquals(revAfterStart, session.rev, "a no-op must not bump rev")
        assertFalse(again.broadcast)
    }

    @Test
    fun `record_stop clears the start time so elapsed cannot be computed from a stale value`() {
        val session = Session(idle())
        session.apply(cmd(CommandName.RECORD_START), nowMs = 5_000)
        assertEquals(5_000, session.state.recording.startedAtMs)

        session.apply(cmd(CommandName.RECORD_STOP, id = "c2"), nowMs = 9_000)

        assertFalse(session.state.recording.recording)
        assertNull(session.state.recording.startedAtMs)
    }

    // ADR-0007's concurrency guard: a stale tab must not silently undo a change
    // made elsewhere. This is the whole reason "last write wins" was reworded.
    @Test
    fun `ADR-0007 - a command carrying a stale expectRev is nacked, not applied`() {
        val session = Session(idle())
        session.apply(
            cmd(CommandName.SETTINGS_SET, args = SettingsPatch(whiteBalanceKelvin = 3200)),
            nowMs = 0,
        )
        val current = session.rev

        val stale = session.apply(
            cmd(
                CommandName.SETTINGS_SET,
                id = "c2",
                expectRev = current - 1,
                args = SettingsPatch(whiteBalanceKelvin = 6500),
            ),
            nowMs = 1_000,
        )

        assertEquals(Nack("c2", NackReason.STALE), stale.reply)
        assertEquals(3200, session.state.settings.whiteBalanceKelvin, "the stale value was not applied")
        assertEquals(current, session.rev)
    }

    @Test
    fun `a matching expectRev is applied`() {
        val session = Session(idle())
        val outcome = session.apply(
            cmd(CommandName.SETTINGS_SET, expectRev = 0, args = SettingsPatch(grid = GridFrequency.HZ_60)),
            nowMs = 0,
        )

        assertTrue(outcome.reply is Ack)
        assertEquals(GridFrequency.HZ_60, session.state.settings.grid)
    }

    // PRD 6.1 promises a locked look for the whole take. A white balance change
    // halfway through produces a jump in the file that no editor can undo.
    @Test
    fun `PRD 6_1 - settings cannot be changed while recording`() {
        val session = Session(idle())
        session.apply(cmd(CommandName.RECORD_START), nowMs = 0)

        val rejected = session.apply(
            cmd(CommandName.SETTINGS_SET, id = "c2", args = SettingsPatch(whiteBalanceKelvin = 3200)),
            nowMs = 1_000,
        )

        assertEquals(Nack("c2", NackReason.INVALID), rejected.reply)
        assertEquals(5600, session.state.settings.whiteBalanceKelvin)
    }

    @Test
    fun `a Kelvin value outside the preset range is refused rather than clamped`() {
        val session = Session(idle())
        val rejected = session.apply(
            cmd(CommandName.SETTINGS_SET, args = SettingsPatch(whiteBalanceKelvin = 50)),
            nowMs = 0,
        )

        assertEquals(Nack("c1", NackReason.INVALID), rejected.reply)
        assertEquals(5600, session.state.settings.whiteBalanceKelvin, "silently clamping would hide a bug")
    }

    // A patch that asks for what is already true is not an error, but it must not
    // bump rev either -- every bump costs a broadcast to every client.
    @Test
    fun `a settings patch that changes nothing does not bump rev`() {
        val session = Session(idle())
        val outcome = session.apply(
            cmd(CommandName.SETTINGS_SET, args = SettingsPatch(whiteBalanceKelvin = 5600)),
            nowMs = 0,
        )

        assertTrue(outcome.reply is Ack)
        assertEquals(0, session.rev)
        assertFalse(outcome.broadcast)
    }

    // rev is not a command counter: the phone changes state on its own -- battery,
    // thermal, warnings, the exposure loop moving ISO -- and clients must refresh.
    // PRD 6.1 and 6.8 both promise tap-to-focus, and it is the one control that
    // has to keep working mid-take: the speaker leans in, or continuous AF drifts
    // onto the bookcase, and stopping the recording to fix it is the workflow the
    // remote exists to remove.
    @Test
    fun `PRD 6_1 - focus can be set while recording, unlike every other setting`() {
        val session = Session(idle())
        session.apply(cmd(CommandName.RECORD_START), nowMs = 1_000)
        val revBefore = session.rev

        val outcome = session.apply(
            focusCmd(Focus(mode = FocusMode.LOCKED, x = 0.42, y = 0.33)),
            nowMs = 2_000,
        )

        assertTrue(outcome.reply is Ack, "focus is not blocked by the recording guard")
        assertTrue(outcome.broadcast, "the other remotes need to see where focus went")
        assertEquals(revBefore + 1, session.rev)
        assertEquals(
            Focus(mode = FocusMode.LOCKED, x = 0.42, y = 0.33),
            session.state.settings.focus,
        )
        assertTrue(session.state.recording.recording, "and the take is still running")
    }

    @Test
    fun `returning to continuous autofocus clears the point`() {
        val session = Session(idle())
        session.apply(focusCmd(Focus(FocusMode.LOCKED, 0.4, 0.4), id = "f1"), nowMs = 1_000)

        session.apply(focusCmd(Focus(FocusMode.CONTINUOUS), id = "f2"), nowMs = 2_000)

        assertEquals(Focus(FocusMode.CONTINUOUS), session.state.settings.focus)
        assertNull(session.state.settings.focus.x)
    }

    // Clamping would hide the bug. The same argument as the Kelvin range: a value
    // the phone silently repaired is one the client never learns it sent.
    @Test
    fun `a focus point outside the frame is refused rather than clamped`() {
        val session = Session(idle())

        val outcome = session.apply(focusCmd(Focus(FocusMode.LOCKED, 1.4, 0.5)), nowMs = 1_000)

        assertEquals(Nack("f1", NackReason.INVALID), outcome.reply)
        assertEquals(Focus(), session.state.settings.focus, "focus did not move")
        assertEquals(0, session.rev)
    }

    @Test
    fun `focus coordinates only mean anything as a pair`() {
        val session = Session(idle())

        val outcome = session.apply(focusCmd(Focus(FocusMode.LOCKED, x = 0.4, y = null)), nowMs = 1_000)

        assertEquals(Nack("f1", NackReason.INVALID), outcome.reply)
    }

    // "Focus everywhere, at this spot" has no reading, so it is a client bug.
    @Test
    fun `continuous autofocus carrying a point is refused`() {
        val session = Session(idle())

        val outcome = session.apply(focusCmd(Focus(FocusMode.CONTINUOUS, 0.4, 0.4)), nowMs = 1_000)

        assertEquals(Nack("f1", NackReason.INVALID), outcome.reply)
    }

    @Test
    fun `a focus command with no focus at all is refused`() {
        val session = Session(idle())

        assertEquals(Nack("f1", NackReason.INVALID), session.apply(focusCmd(null), nowMs = 1_000).reply)
    }

    // Two remotes tapping the same spot is not two changes.
    @Test
    fun `tapping the point focus is already on does not bump rev`() {
        val session = Session(idle())
        session.apply(focusCmd(Focus(FocusMode.LOCKED, 0.5, 0.5), id = "f1"), nowMs = 1_000)
        val revAfterFirst = session.rev

        val again = session.apply(focusCmd(Focus(FocusMode.LOCKED, 0.5, 0.5), id = "f2"), nowMs = 2_000)

        assertEquals(revAfterFirst, session.rev)
        assertFalse(again.broadcast)
    }

    @Test
    fun `phone-side updates bump rev so clients refresh`() {
        val session = Session(idle())
        session.update(nowMs = 1_000) { it.copy(warnings = listOf(Warning.TOO_DARK)) }

        assertEquals(1, session.rev)
        assertEquals(listOf(Warning.TOO_DARK), session.state.warnings)
        assertEquals(1_000, session.state.serverTimeMs)
    }

    @Test
    fun `a phone-side update that changes nothing does not bump rev`() {
        val session = Session(idle())
        session.update(nowMs = 1_000) { it }
        assertEquals(0, session.rev)
    }

    @Test
    fun `the snapshot carries the current revision`() {
        val session = Session(idle())
        session.apply(cmd(CommandName.RECORD_START), nowMs = 0)
        assertEquals(session.rev, session.snapshot().rev)
        assertEquals(session.state, session.snapshot().state)
    }
}
