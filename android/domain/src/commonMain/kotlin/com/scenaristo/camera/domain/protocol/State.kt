package com.scenaristo.camera.domain.protocol

import com.scenaristo.camera.domain.exposure.GridFrequency
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The state document the phone owns and every browser mirrors (ADR-0007, PRD 6.8).
 *
 * One source of truth, sent whole on every change. A client never sends one of
 * these back: it sends a [Command] and waits for the phone to decide, which is
 * what makes "last write wins" mean "last *accepted* command wins" rather than
 * "whichever stale tab spoke last".
 */
@Serializable
data class State(
    val settings: CaptureSettings,
    val recording: RecordingState,
    val device: DeviceStatus,
    /** What the user should be told about the shot right now (PRD 6.3, 6.5). */
    val warnings: List<Warning> = emptyList(),
    /** How many browsers are attached, so a user can tell they are not alone (PRD 6.8). */
    val clients: Int = 0,
    /**
     * The microphone and what it is hearing (PRD 6.6). Defaulted, so a snapshot
     * written before audio existed still decodes -- ADR-0007's rule for added
     * fields.
     */
    val audio: AudioState = AudioState(),
    /**
     * The phone's clock when this snapshot was built. Elapsed recording time is
     * derived from this rather than sent directly, so it stays right across a
     * reconnect and does not drift with the browser's clock (ADR-0007).
     */
    val serverTimeMs: Long,
)

/** What the camera is set to. Every field is something PRD 6.8 lets the browser change. */
@Serializable
data class CaptureSettings(
    /** Mains frequency, which fixes the flicker-safe ladder (PRD 6.2). */
    val grid: GridFrequency,
    /**
     * The rung actually in use, as reciprocal seconds: 50 means 1/50 s. Reported
     * rather than set, because PRD 6.3 lets the app step to the next flicker-safe
     * rung on its own and the browser must show what is really happening.
     */
    val shutterHz: Int,
    val iso: Int,
    /**
     * The ISO the user pinned, or null when the loop is choosing (PRD 6.3).
     *
     * Separate from [iso], which always reports what the sensor is actually
     * doing. Collapsing the two would lose the difference between "the loop
     * settled here" and "the user said here", and the browser has to show which
     * (UI-9's reported versus control styles).
     */
    val isoLock: Int? = null,
    /** The shutter rung the user pinned, or null when the ladder is free (PRD 6.3). */
    val shutterLock: Int? = null,
    val whiteBalanceKelvin: Int,
    /** Camera id of the active lens, as reported by the capability probe (ADR-0011). */
    val lensId: String,
    /**
     * Where the camera is focusing. Defaulted so that a snapshot written before
     * focus existed still decodes, which is the compatibility rule ADR-0007 sets
     * for added fields.
     */
    val focus: Focus = Focus(),
)

/**
 * Where the camera is focusing (PRD 6.1 "Continuous AF with face priority,
 * lockable"; PRD 6.8 "focus (tap on preview, lock)").
 *
 * [x] and [y] are normalised in the frame: 0.0 is the left or top edge, 1.0 the
 * right or bottom. Normalised rather than pixels because the browser sees a
 * 960 × 540 preview of a 3840 × 2160 recording (PRD 6.8), and normalised
 * *in the frame* rather than in the preview image because the preview is cropped
 * to the recording's aspect ratio — which is what makes one pair of numbers mean
 * the same point on the phone, in the browser, and in the file.
 *
 * Both are null in [FocusMode.CONTINUOUS], and both are set in
 * [FocusMode.LOCKED] when the lock came from a tap. A lock with no point means
 * "hold it where it already is", which is the other half of PRD 6.1's "lockable".
 */
@Serializable
data class Focus(
    val mode: FocusMode = FocusMode.CONTINUOUS,
    val x: Double? = null,
    val y: Double? = null,
)

@Serializable
enum class FocusMode {
    /** PRD 6.1's default: continuous autofocus, face priority. */
    @SerialName("continuous")
    CONTINUOUS,

    /** Held, either where it was or at the point of the tap that set it. */
    @SerialName("locked")
    LOCKED,
}

@Serializable
data class RecordingState(
    val recording: Boolean,
    /**
     * Phone clock at the moment recording started, or null when idle. The browser
     * computes elapsed time as `serverTimeMs - startedAtMs`; sending a duration
     * instead would be wrong the moment a snapshot is late.
     */
    val startedAtMs: Long? = null,
)

/** PRD 6.8's status line. */
@Serializable
data class DeviceStatus(
    val batteryPercent: Int,
    val charging: Boolean,
    val thermal: ThermalState,
    /**
     * Free storage expressed as minutes of recording left at the current bitrate,
     * because "14.2 GB" does not tell a creator whether they can finish the take.
     */
    val storageMinutesRemaining: Int,
)

/**
 * The microphone, as PRD 6.6 needs it shown on both surfaces.
 *
 * A silent take is discovered afterwards, when it is too late, which is why 6.6
 * asks for a meter rather than a setting.
 */
@Serializable
data class AudioState(
    /**
     * How loud it is right now, 0.0 to 1.0, already normalised by the platform.
     *
     * Android reports this every 200 ms, which is the 5 Hz meter ADR-0002
     * accepted for the MVP against PRD 6.6's eventual 10 Hz.
     */
    val level: Double = 0.0,
    /**
     * True when the signal reached the top of the scale. Its own field rather
     * than `level >= 1.0`, because clipping is a thing that *happened* and
     * should survive a quieter frame arriving straight after it.
     */
    val clipping: Boolean = false,
    /** Which microphone the system routed to (PRD 6.6). */
    val input: AudioInput = AudioInput.UNKNOWN,
    /**
     * False when there is no meter to show rather than silence to show.
     *
     * The distinction matters: a meter reading zero says the room is quiet, and
     * a meter that is not running says nothing at all, and drawing the second as
     * the first is how someone concludes their microphone is dead.
     */
    val metering: Boolean = false,
)

/**
 * The microphone in use, named for a person rather than for an Android constant.
 *
 * The app does not *choose* this. ADR-0002 accepted system default routing for
 * the MVP -- which prefers a plugged-in microphone, so the priority PRD 6.6 asks
 * for is what usually happens -- and the app's job is to say which one won.
 */
@Serializable
enum class AudioInput {
    BUILT_IN,
    WIRED,
    USB,

    /**
     * Works, and is worth warning about: hands-free Bluetooth is a 8-16 kHz
     * voice codec, which is audibly worse than the built-in microphone it
     * usually replaces (PRD 6.6).
     */
    BLUETOOTH,

    /** Nothing has told us yet, which is not the same as "the built-in one". */
    UNKNOWN,
}

/**
 * Android's `PowerManager` thermal status, named for a person (PRD 6.8).
 *
 * **Only [SERIOUS] and [CRITICAL] are shown to anyone** (decision 2026-09-05,
 * Davide): if the throttling neither impacts the experience nor drops frames,
 * the interface says nothing about it. A phone getting warm while recording 4K
 * is a phone doing its job, and telling a creator about it mid-take spends their
 * attention on something they cannot act on and that is not hurting the take.
 *
 * Measured on the reference device (#23): a 10:42 take at 4K30 reaches Android's
 * `MODERATE` after eight minutes and holds **29.990 fps, constant, with no
 * dropped frames** throughout. That is the case this rule exists for.
 *
 * The four levels stay in the protocol even though two of them draw nothing,
 * because a browser reading the state document is also the diagnostic view, and
 * "warm but fine" is worth having in a bug report.
 */
@Serializable
enum class ThermalState {
    /** Android `NONE` or `LIGHT`: not throttling, or throttling nobody can tell. */
    NOMINAL,

    /**
     * Android `MODERATE`: throttling that the platform documents as not largely
     * impacting the experience, and that #23 measured as costing no frames.
     * **Displays nothing.**
     */
    FAIR,

    /** Android `SEVERE`: the platform says the experience *is* impacted. Shown. */
    SERIOUS,

    /** Android `CRITICAL` and worse: the platform has done all it can. Shown. */
    CRITICAL,
    ;

    /** Whether the interface says anything at all about this (PRD 6.8, UI-9). */
    val worthShowing: Boolean get() = this == SERIOUS || this == CRITICAL
}

/** Things the app tells the user about the shot (PRD 6.3, 6.5). */
@Serializable
enum class Warning {
    TOO_DARK,
    TOO_CLOSE_TO_LENS,
    /** Overexposed even after the one flicker-safe shutter step ADR-0005 allows. */
    OVEREXPOSED_AT_BASE_ISO,
}
