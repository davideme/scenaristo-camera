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

/** Mirrors Android's `PowerManager` thermal status, named for a person (PRD 6.8). */
@Serializable
enum class ThermalState { NOMINAL, FAIR, SERIOUS, CRITICAL }

/** Things the app tells the user about the shot (PRD 6.3, 6.5). */
@Serializable
enum class Warning {
    TOO_DARK,
    TOO_BRIGHT,
    TOO_CLOSE_TO_LENS,
    /** Overexposed even after the one flicker-safe shutter step ADR-0005 allows. */
    OVEREXPOSED_AT_BASE_ISO,
}
