package com.scenaristo.camera.domain.protocol

import com.scenaristo.camera.domain.exposure.GridFrequency
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Protocol major version. The first message the server sends is [Hello]; a client
 * refuses an unknown major. Adding fields is backward compatible, renaming or
 * removing bumps this number. See ADR-0007.
 */
const val PROTOCOL_VERSION: Int = 2

/**
 * The one `Json` both ends use.
 *
 * `classDiscriminator = "type"` is what makes the wire format ADR-0007 specifies
 * — `{"type":"cmd", ...}` — fall out of the sealed hierarchies rather than being
 * hand-written. `ignoreUnknownKeys` is the other half of "adding fields is
 * backward compatible": an older client must survive a newer phone's snapshot.
 */
val ProtocolJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}

@Serializable
enum class Platform {
    @SerialName("android")
    ANDROID,

    @SerialName("ios")
    IOS,
}

/**
 * Anything the phone sends.
 *
 * These `@Serializable` classes are the single source of truth for the protocol:
 * the TypeScript types in `web/src/protocol.ts` are generated from them
 * (ADR-0009) and the iOS server in Phase 4 is tested against the same fixtures
 * (ADR-0013). Nothing is hand-written twice.
 */
@Serializable
sealed interface ServerMessage

/** First message on `/ws`, before any state snapshot (ADR-0007). */
@Serializable
@SerialName("hello")
data class Hello(
    val protocol: Int = PROTOCOL_VERSION,
    val app: String,
    val platform: Platform,
) : ServerMessage

/**
 * A full snapshot. Sent on connect, on every change, and at least every 2 s so a
 * browser can tell the difference between "nothing changed" and "the phone is
 * gone" — a browser cannot send WebSocket pings itself (ADR-0007).
 */
@Serializable
@SerialName("state")
data class StateMessage(val rev: Int, val state: State) : ServerMessage

/** The command was applied; [rev] is the revision it produced. */
@Serializable
@SerialName("ack")
data class Ack(val id: String, val rev: Int) : ServerMessage

/** The command was not applied, and why. */
@Serializable
@SerialName("nack")
data class Nack(val id: String, val reason: NackReason) : ServerMessage

@Serializable
enum class NackReason {
    /** `expectRev` did not match; the client's view was out of date. */
    @SerialName("stale")
    STALE,

    /** The active lens cannot do this (ADR-0011). */
    @SerialName("not_capable")
    NOT_CAPABLE,

    /** The value is outside what the app allows, e.g. a shutter off the ladder. */
    @SerialName("invalid")
    INVALID,
}

/** Anything a browser sends. */
@Serializable
sealed interface ClientMessage

/**
 * A request, never a state write (ADR-0007).
 *
 * [id] is client-generated and makes the command idempotent: a retry after a
 * dropped connection replays the same id and gets the original answer instead of
 * recording twice. [expectRev] is the optional concurrency guard — the web UI
 * sets it for settings changes so a stale tab cannot silently undo a change made
 * on the phone, and deliberately omits it for record start/stop, where acting on
 * the latest state is always what the user meant.
 */
@Serializable
@SerialName("cmd")
data class Command(
    val id: String,
    val name: CommandName,
    val expectRev: Int? = null,
    val args: SettingsPatch? = null,
    /**
     * Argument of [CommandName.FOCUS_SET], and of nothing else.
     *
     * A field of its own rather than a member of [SettingsPatch] because focus is
     * not one of the settings: it is allowed while recording, it is not guarded
     * by `expectRev`, and it does not survive being merged field-by-field the way
     * a patch does — a point and a mode only mean anything together.
     */
    val focus: Focus? = null,
) : ClientMessage

@Serializable
enum class CommandName {
    /** Idempotent, and not a toggle: starting while recording is a no-op ack (ADR-0007). */
    @SerialName("record.start")
    RECORD_START,

    @SerialName("record.stop")
    RECORD_STOP,

    @SerialName("settings.set")
    SETTINGS_SET,

    /**
     * Tap to focus, and focus lock (PRD 6.1, 6.8). Carries [Command.focus].
     *
     * Its own command rather than a settings field because it is the one control
     * that stays live during a take: refocusing mid-recording leaves nothing in
     * the file an editor has to explain, where a lens switch or a white balance
     * change does.
     */
    @SerialName("focus.set")
    FOCUS_SET,
}

/**
 * The subset of [CaptureSettings] a browser may change, all optional so a client
 * can send one field without restating the rest.
 *
 * `shutterHz` and `iso` are absent, and stay absent: they are outputs of the
 * exposure loop (ADR-0005), not inputs. What PRD 6.3 does offer is a *lock* on
 * each, which is a different thing — it says "stop choosing" rather than "choose
 * this", and it is the only way to set either. Focus is absent for the opposite
 * reason: it is settable, but through [CommandName.FOCUS_SET], because it obeys
 * none of the rules a patch does.
 *
 * Every lock is tri-state on the wire and the third state is why they are boxed:
 * absent means "leave it as it is", a value means "lock here", and
 * [SettingsPatch.CLEAR_LOCK] means "go back to automatic". A plain nullable
 * cannot say the last one, because null is already how a patch says nothing.
 */
@Serializable
data class SettingsPatch(
    val grid: GridFrequency? = null,
    val whiteBalanceKelvin: Int? = null,
    val lensId: String? = null,
    /**
     * Lock ISO to this value, [CLEAR_LOCK] to return to the loop
     * (PRD 6.3: "ISO manual lock available (phone and web)").
     */
    val isoLock: Int? = null,
    /**
     * Lock the shutter to this rung, as reciprocal seconds, or [CLEAR_LOCK].
     *
     * Only a rung of the grid's own ladder is accepted — 50 or 100 on a 50 Hz
     * grid, 60 or 120 on 60 Hz. Anything else bands, which is the one thing the
     * product exists to prevent, so it is refused rather than clamped for the
     * same reason the Kelvin range is: a value the phone silently repaired is a
     * bug the client never learns it has.
     *
     * A locked shutter also disables ADR-0005's flicker-safe step, which PRD 6.3
     * states directly.
     */
    val shutterLock: Int? = null,
) {
    companion object {
        /**
         * "Go back to automatic", for either lock.
         *
         * Zero rather than a sentinel string because both fields are integers on
         * the wire and no lock could ever legitimately be zero — ISO 0 is not a
         * sensitivity and 1/0 s is not an exposure.
         */
        const val CLEAR_LOCK: Int = 0
    }
}
