package com.scenaristo.camera.domain.protocol

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
        val patch = command.args
            ?: return Outcome(Nack(command.id, NackReason.INVALID), broadcast = false)
        if (state.recording.recording) {
            return Outcome(Nack(command.id, NackReason.INVALID), broadcast = false)
        }
        if (patch.whiteBalanceKelvin != null && patch.whiteBalanceKelvin !in KELVIN_RANGE) {
            return Outcome(Nack(command.id, NackReason.INVALID), broadcast = false)
        }

        val updated = state.settings.copy(
            grid = patch.grid ?: state.settings.grid,
            whiteBalanceKelvin = patch.whiteBalanceKelvin ?: state.settings.whiteBalanceKelvin,
            lensId = patch.lensId ?: state.settings.lensId,
        )
        if (updated == state.settings) return remember(command, nowMs, changed = false)
        state = state.copy(settings = updated, serverTimeMs = nowMs)
        return remember(command, nowMs, changed = true)
    }

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
    }
}
