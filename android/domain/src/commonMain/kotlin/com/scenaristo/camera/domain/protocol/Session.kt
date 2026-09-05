package com.scenaristo.camera.domain.protocol

import com.scenaristo.camera.domain.exposure.shutterLadder

/**
 * Applying a command to the state document (ADR-0007).
 *
 * This is the whole of the protocol's correctness — idempotent record commands,
 * the `expectRev` staleness guard, start-while-recording as a no-op — and it is
 * pure, so it is tested on the host and reused unchanged by the iOS server in
 * Phase 4 (ADR-0013). The Ktor handler's job is sockets and broadcast; the rules
 * live here.
 */
class Session(
    initial: State,
    /** How long a command id is remembered, so a retry replays rather than repeats. */
    private val idempotencyWindowMs: Long = 10_000,
) {
    var state: State = initial
        private set

    /** Bumped on every accepted change. Clients use it to detect a stale view. */
    var rev: Int = 0
        private set

    /** id -> (rev answered, when). Pruned by [prune] rather than by a timer. */
    private val applied = mutableMapOf<String, Pair<Int, Long>>()

    /** What [apply] decided, ready for the server to send back and broadcast. */
    data class Outcome(
        val reply: ServerMessage,
        /** True when the state changed and every client needs the new snapshot. */
        val broadcast: Boolean,
    )

    fun apply(command: Command, nowMs: Long): Outcome {
        prune(nowMs)

        // A retry of a command we already answered gets the original answer. This
        // is what stops a dropped connection mid-record from starting a second
        // recording when the client resends (ADR-0007).
        applied[command.id]?.let { (answeredRev, _) ->
            return Outcome(Ack(command.id, answeredRev), broadcast = false)
        }

        command.expectRev?.let { expected ->
            if (expected != rev) return Outcome(Nack(command.id, NackReason.STALE), broadcast = false)
        }

        return when (command.name) {
            CommandName.RECORD_START -> start(command, nowMs)
            CommandName.RECORD_STOP -> stop(command, nowMs)
            CommandName.SETTINGS_SET -> settings(command, nowMs)
            CommandName.FOCUS_SET -> focus(command, nowMs)
        }
    }

    /**
     * Starting while already recording is a no-op that acks, not an error: the
     * user asked for "be recording", and it is. Only a state change bumps [rev].
     */
    private fun start(command: Command, nowMs: Long): Outcome {
        if (state.recording.recording) return remember(command, nowMs, changed = false)
        state = state.copy(
            recording = RecordingState(recording = true, startedAtMs = nowMs),
            serverTimeMs = nowMs,
        )
        return remember(command, nowMs, changed = true)
    }

    private fun stop(command: Command, nowMs: Long): Outcome {
        if (!state.recording.recording) return remember(command, nowMs, changed = false)
        state = state.copy(
            recording = RecordingState(recording = false, startedAtMs = null),
            serverTimeMs = nowMs,
        )
        return remember(command, nowMs, changed = true)
    }

    /**
     * Settings changes are rejected while recording rather than applied.
     *
     * PRD 6.1 promises a locked look for the whole take; changing white balance
     * or lens mid-recording would produce a jump in the middle of the file that
     * no editor can undo. A browser that wants this must stop first, which is
     * also what makes the resulting file explainable.
     */
    private fun settings(command: Command, nowMs: Long): Outcome {
        val patch = command.args ?: return invalid(command)
        if (state.recording.recording) return invalid(command)
        if (patch.whiteBalanceKelvin != null && patch.whiteBalanceKelvin !in KELVIN_RANGE) {
            return invalid(command)
        }

        val grid = patch.grid ?: state.settings.grid

        // A lock is three states in one integer field: absent leaves it alone,
        // CLEAR_LOCK releases it, anything else pins it (PRD 6.3).
        val isoLock = when (patch.isoLock) {
            null -> state.settings.isoLock
            SettingsPatch.CLEAR_LOCK -> null
            else -> patch.isoLock.takeIf { it > 0 } ?: return invalid(command)
        }

        // The shutter may only be locked to a rung of the grid it is locked
        // under. Everything else bands, which is the failure the whole product
        // exists to prevent, so it is refused rather than clamped.
        val shutterLock = when (patch.shutterLock) {
            null -> state.settings.shutterLock
            SettingsPatch.CLEAR_LOCK -> null
            else -> patch.shutterLock.takeIf { it in shutterLadder(grid) } ?: return invalid(command)
        }

        // Changing the grid moves the ladder under an existing lock, and a lock
        // left pointing at the old ladder would be exactly the off-ladder
        // shutter refused above -- so it is refused here too, rather than
        // silently retuned to a rung the user did not choose.
        if (shutterLock != null && shutterLock !in shutterLadder(grid)) return invalid(command)

        val updated = state.settings.copy(
            grid = grid,
            whiteBalanceKelvin = patch.whiteBalanceKelvin ?: state.settings.whiteBalanceKelvin,
            lensId = patch.lensId ?: state.settings.lensId,
            isoLock = isoLock,
            shutterLock = shutterLock,
        )
        if (updated == state.settings) return remember(command, nowMs, changed = false)
        state = state.copy(settings = updated, serverTimeMs = nowMs)
        return remember(command, nowMs, changed = true)
    }

    /**
     * Focus is the one control that stays live while recording.
     *
     * Refocusing mid-take is ordinary — the speaker leans in, or continuous AF
     * drifted onto the bookcase — and unlike the changes [settings] refuses it
     * leaves nothing in the file an editor has to explain. So this deliberately
     * does not take the recording guard (PRD 6.1, 6.8).
     *
     * What it will not do is guess. Half a point, or a point handed to continuous
     * autofocus, is a client bug rather than an intention worth interpreting, and
     * a point outside the frame cannot be honoured by any lens. All three are
     * refused rather than clamped, for the reason the Kelvin range is: a value
     * the phone silently repaired is a bug the client never learns it has.
     *
     * A lens that cannot focus on a region answers [NackReason.NOT_CAPABLE]
     * (ADR-0011) — but not from here. `:domain` is platform-free and holds no
     * capability table, so that check belongs to the capture layer that owns one.
     */
    private fun focus(command: Command, nowMs: Long): Outcome {
        val requested = command.focus ?: return invalid(command)
        val x = requested.x
        val y = requested.y
        if ((x == null) != (y == null)) return invalid(command)
        if (x != null && y != null) {
            if (requested.mode == FocusMode.CONTINUOUS) return invalid(command)
            // NaN fails both of these, which is the answer we want for it too.
            if (x !in UNIT_INTERVAL || y !in UNIT_INTERVAL) return invalid(command)
        }

        if (requested == state.settings.focus) return remember(command, nowMs, changed = false)
        state = state.copy(
            settings = state.settings.copy(focus = requested),
            serverTimeMs = nowMs,
        )
        return remember(command, nowMs, changed = true)
    }

    private fun invalid(command: Command): Outcome =
        Outcome(Nack(command.id, NackReason.INVALID), broadcast = false)

    private fun remember(command: Command, nowMs: Long, changed: Boolean): Outcome {
        if (changed) rev++
        applied[command.id] = rev to nowMs
        return Outcome(Ack(command.id, rev), broadcast = changed)
    }

    private fun prune(nowMs: Long) {
        applied.entries.removeAll { (_, entry) -> nowMs - entry.second > idempotencyWindowMs }
    }

    /**
     * Replaces the state from the phone's own side — a new battery reading, a
     * warning appearing, the exposure loop moving ISO. Bumps [rev] so clients
     * refresh, and it is the reason `rev` is not simply a command counter.
     */
    fun update(nowMs: Long, transform: (State) -> State) {
        val proposed = transform(state)
        // The clock is excluded from the comparison on purpose. serverTimeMs
        // moves on every snapshot, so counting it as a change would bump rev
        // twice a second forever, broadcast to every client each time, and leave
        // rev meaning "time passed" rather than "something you care about moved".
        val changed = proposed.copy(serverTimeMs = state.serverTimeMs) != state
        state = proposed.copy(serverTimeMs = nowMs)
        if (changed) rev++
    }

    fun snapshot(): StateMessage = StateMessage(rev, state)

    private companion object {
        /** PRD 6.4's presets span tungsten to shade; outside this is a typo, not a choice. */
        val KELVIN_RANGE = 2000..10000

        /** Focus points are normalised in the frame, so this is the whole frame. */
        val UNIT_INTERVAL = 0.0..1.0
    }
}
