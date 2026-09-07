package com.scenaristo.camera.domain.protocol

import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.whitebalance.AwbApproximation
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
     * What a take is written as (PRD 6.7), for the remote control's transport
     * row (UI-9). Defaulted for the same compatibility reason as [audio].
     */
    val encoding: Encoding = Encoding(),
    /** The exposure aids on the remote control (PRD 6.3, 6.8; #97). Defaulted, as above. */
    val exposure: ExposureReadout = ExposureReadout(),
    /** What the active lens is, optically (PRD 6.5, 6.8; #101). Defaulted, as above. */
    val optics: Optics = Optics(),
    /**
     * What the camera in use can and cannot do (PRD 6.10, ADR-0011; #14).
     * Defaulted, so a snapshot written before it existed still decodes.
     */
    val capabilities: Capabilities = Capabilities(),
    /**
     * The framings a client may choose between (PRD 6.5, 6.8; #77). Empty until
     * the camera has bound and reported its zoom range.
     */
    val lenses: List<LensChoice> = emptyList(),
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
    /** The shutter rung the user pinned, or null when the ladder is free (PRD 6.3). */
    val shutterLock: Int? = null,
    val whiteBalanceKelvin: Int,
    /**
     * The platform mode standing in for [whiteBalanceKelvin], or null when the
     * lens takes colour gains and the preset is simply applied (PRD 6.4,
     * ADR-0011).
     *
     * PRD 6.4 requires the app to *admit* an approximation rather than present
     * it as the real thing, and both surfaces have to say so. It matters more
     * than the wording suggests: on the reference device **every lens takes the
     * approximated path today**, because the Kelvin-to-gains curve is #24 in
     * Phase 3 — so the case this exists for is currently the only case there is,
     * and a remote that stayed silent would be telling the user a temperature
     * the camera is not holding.
     *
     * Defaulted to null, which reads as "exact or not yet probed". A client that
     * wants to tell those apart reads it alongside `lensId`, which is null-ish
     * only before the camera binds.
     */
    val whiteBalanceApproximatedBy: AwbApproximation? = null,
    /** Camera id of the active lens, as reported by the capability probe (ADR-0011). */
    val lensId: String,
    /**
     * Whether takes are saved to the shared gallery instead of the app's own
     * folder (PRD 6.7, ADR-0020).
     *
     * False by default, which is the quieter neighbour: a take lands in the
     * app's directory and nothing else on the phone sees it. True puts it in
     * `Movies/Scenaristo Camera/`, where the gallery and a laptop over MTP find
     * it — at the cost of writing multi-gigabyte files into shared storage that
     * the user has to clean up themselves.
     */
    val saveToGallery: Boolean = false,
    /**
     * Whether exposure is held where it was at record start, instead of tracking
     * the light through the take (ADR-0023).
     *
     * False by default, which is the behaviour every take has had so far: the
     * loop keeps metering, damped, and follows the room. True stops the metering
     * loop outright for the duration of the take — the strongest form of PRD
     * 6.1's locked look, and the one that cannot recover if the light changes.
     */
    val lockExposureWhileRecording: Boolean = false,
    /**
     * Where the camera is focusing. Defaulted so that a snapshot written before
     * focus existed still decodes, which is the compatibility rule ADR-0007 sets
     * for added fields.
     */
    val focus: Focus = Focus(),
    /**
     * The framing in use, as a zoom ratio (PRD 6.5, 6.8; #77).
     *
     * 1.0 is the base lens. Settable, unlike [lensId], because on a phone this
     * is what choosing a lens actually is — see [LensChoice]. Defaulted, so an
     * older snapshot still decodes (ADR-0007).
     */
    val zoomRatio: Double = 1.0,
) {
    /**
     * The fields a client can actually ask for, which is what a settings guard
     * counts changes to (ADR-0024).
     *
     * **This is not "all of `CaptureSettings`", and the difference is the whole
     * point.** `shutterHz` and `iso` live here too, and they are outputs of the
     * ADR-0005 exposure loop — moving up to six times a second, measured at 27
     * revisions a second on the reference device. Counting them would make a
     * settings guard advance constantly and refuse every change a user ever
     * made, which is the bug ADR-0024 exists to fix. `focus` is excluded for a
     * different reason: it is set by its own command, unguarded, because it is
     * allowed during a take (UI-16 leaves it unused, but the rule stands).
     *
     * The list must stay exactly the fields of [SettingsPatch]; a test asserts
     * that against the serializer's own descriptor, so adding a patch field
     * without adding it here fails the build rather than silently leaving that
     * field unguarded.
     */
    val settable: List<Any?>
        get() = listOf(
            grid,
            whiteBalanceKelvin,
            lensId,
            saveToGallery,
            shutterLock,
            lockExposureWhileRecording,
            zoomRatio,
        )
}

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
    /**
     * The take being written, or the last one finished, without its extension
     * (PRD 6.7 `Scenaristo_YYYY-MM-DD_HH-MM-SS`).
     *
     * It survives the stop deliberately. "Did that save, and as what?" is the
     * question a creator asks in the second after they stop, and a transport row
     * that blanks the moment the answer matters is answering the wrong one. Null
     * only before the first take of a session.
     *
     * The name cannot be shown *before* a take, because PRD 6.7 derives it from
     * the moment recording starts. That is the timestamp's whole value: two
     * takes a minute apart sort correctly in a directory listing, which a
     * counter would not guarantee across a reinstall.
     */
    val fileName: String? = null,
)

/**
 * What a take is written as (PRD 6.7), so the remote control can say so *before*
 * recording rather than after -- which is what the PRD asks for, and the reason
 * this is state rather than something reported at the end.
 *
 * Every field is read from the same `CamcorderProfile` the recorder itself
 * follows, so this is a report and not a request: CameraX 1.6.2 has no SDR codec
 * selector, and PRD 6.7 only promises to *show* which codec will be used until
 * the 1.7 revisit (ADR-0002, #27). A resolution below UHD here is ADR-0011's
 * fallback having fired, which PRD 6.10 requires be visible before recording.
 *
 * All fields default to a zeroed report, which reads as "not probed yet" -- the
 * state before the camera has bound, and the compatibility default ADR-0007
 * requires for an added field.
 */
@Serializable
data class Encoding(
    val codec: VideoCodec = VideoCodec.UNKNOWN,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val frameRate: Int = 0,
    /**
     * Bits per second, as the profile declares it. Bits rather than the megabits
     * UI-9 displays, because the profile reports bits and a unit converted at
     * the producer is a unit the consumer has to trust.
     */
    val bitrate: Int = 0,
)

/**
 * PRD 6.10's capability report, for the camera the app is bound to.
 *
 * **Per camera, not per framing.** The framings of [LensChoice] are zoom ratios
 * on one logical camera (UI-21), so they share its characteristics — a report
 * per framing would be the same four answers repeated four times, and would
 * imply a per-lens gate that this device does not have.
 *
 * Every field defaults false, which reads as "not probed yet" and is the safe
 * way round: the interface says a thing is unavailable before it knows, rather
 * than claiming a capability nobody has checked.
 */
@Serializable
data class Capabilities(
    /** True once a camera has been probed, so "not yet" is distinguishable from "no". */
    val probed: Boolean = false,
    /** PRD 6.10: 4K at 30 fps. False means ADR-0011's 1080p fallback applies. */
    val uhd30: Boolean = false,
    /**
     * `MANUAL_SENSOR` — the shutter and ISO can be held.
     *
     * ADR-0011 gates recording on this and does not degrade: without it the
     * shutter drifts and the picture bands, which is the one thing the product
     * exists to prevent, so the lens is unusable rather than reduced.
     */
    val manualShutter: Boolean = false,
    /**
     * `MANUAL_POST_PROCESSING` — white balance can be set as colour gains.
     *
     * Note this says what the **lens** offers, not what the app currently does:
     * every lens takes the approximated-preset path until #24 lands the
     * Kelvin-to-gains curve, which is what `whiteBalanceApproximatedBy` reports.
     * Both are true and they answer different questions.
     */
    val manualWhiteBalance: Boolean = false,
    /** A hardware HEVC encoder exists. Whether the profile *chooses* it is [Encoding.codec]. */
    val hardwareHevc: Boolean = false,
)

/**
 * One framing the user can pick, as a zoom ratio and the field of view it gives
 * (PRD 6.5, 6.8).
 *
 * **A phone's other lenses are not separate cameras.** On the reference device
 * the ultrawide and telephoto sit behind the back logical camera and cannot be
 * selected at all: lens choice is a zoom ratio, and the HAL decides which sensor
 * serves it — possibly mid-session (confirmed by Davide, 2026-09-06; see
 * `PinnedLensProbe`). So the control is a ratio, and `lensId` keeps meaning the
 * camera device it always meant.
 *
 * It is labelled by [equivalentFocalLengthMm] rather than by a lens name,
 * because that is the honest thing to say: the app knows the field of view the
 * ratio produces, and CameraX 1.6.2 will not tell it whether a given ratio is
 * served by glass or by a crop. A focal length is true either way, and it is
 * also the number PRD 6.5's guidance is written in.
 */
@Serializable
data class LensChoice(
    val zoomRatio: Double,
    /**
     * The 35 mm equivalent at this ratio — the base lens's equivalent scaled by
     * the ratio, which is what zooming does to a field of view.
     */
    val equivalentFocalLengthMm: Int,
)

/**
 * The active lens as an optic, rather than as a thing to switch (PRD 6.5, 6.8).
 *
 * Reported: none of it is settable, and all of it is fixed for the lens the
 * `lensId` names. It is separate from [CaptureSettings] for exactly that reason
 * -- every field there is something a browser may change, and none of these is.
 *
 * Null rather than zero when unknown. A lens that has not been probed has no
 * focal length; drawing that as `0 mm` would be a claim, and 0 is a value an
 * aperture cannot have.
 */
@Serializable
data class Optics(
    /** 35 mm-equivalent focal length, the only focal length worth showing (PRD 6.5). */
    val equivalentFocalLengthMm: Int? = null,
    /** The lens's fixed f/-number, from the platform's own characteristics. */
    val apertureFNumber: Double? = null,
)

/**
 * What the exposure loop can see, for someone judging the shot from a laptop
 * (PRD 6.3, 6.8; #97).
 *
 * Reported, never set: everything here is an output of the ADR-0005 loop, which
 * is why it belongs to the spec's **Reported** grammar and has no control beside
 * it (spec-phone-and-remote-ui §5).
 *
 * Measured on the phone, in the same frame walk the loop already runs
 * (ADR-0018), rather than in the browser off the MJPEG preview. Decision by
 * Davide, 2026-09-06: a reading taken from a downscaled JPEG can disagree with
 * both the recording and the app's own metering, and an exposure aid that
 * disagrees with the thing it is advising about is worse than none. Measuring
 * once on the phone also means every remote sees the same numbers, and Phase 4
 * gets them on iOS for free (ADR-0013).
 */
@Serializable
data class ExposureReadout(
    /**
     * How far the metered scene is from correct, in stops. **Negative is
     * under-exposed**, which is the direction anyone who has metered a shot
     * expects, and zero is exactly where the ADR-0005 loop is trying to be.
     *
     * The sign is flipped from `ExposureState.errorEv`, which is stated as what
     * the *loop* must do ("needs this much more light") rather than what the
     * *picture* is. Same number, opposite audience.
     *
     * Damped, not raw: the loop's own damped error, so the reading does not
     * flicker faster than the correction it describes (ADR-0022).
     */
    val stopsFromTarget: Double = 0.0,
    /**
     * Sampled pixel counts across the tonal scale, gamma-encoded, low to high.
     * Empty when nothing has been metered yet.
     */
    val histogram: List<Int> = emptyList(),
    /**
     * False when there is no reading to show rather than a reading of zero.
     *
     * The same distinction [AudioState.metering] makes, for the same reason: a
     * meter reading zero says the scene is correctly exposed, and a meter that
     * is not running says nothing at all. Drawing the second as the first is how
     * someone trusts an aid that is not measuring anything.
     */
    val metering: Boolean = false,
)

/**
 * The video codec of the recorded file (PRD 6.7).
 *
 * Named rather than carrying the platform's media-type string, because the two
 * platforms spell the same codec differently -- Android says `video/hevc`, iOS
 * says `hvc1` -- and a browser that has to know both is a browser that has to be
 * updated when either changes (ADR-0013).
 */
@Serializable
enum class VideoCodec {
    HEVC,

    /** PRD 6.7's fallback: H.264 High profile, when the device profile picks it. */
    H264,

    /** Nothing has probed the profile yet, or it declared something else entirely. */
    UNKNOWN,
}

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
    /**
     * Whether preview frames are actually being produced (PRD 6.8; #116).
     *
     * False in one situation that looks like a fault and is not: **the phone's
     * screen is off.** The preview stream hangs off CameraX's `Preview` use
     * case, whose surface belongs to the phone's viewfinder — when the activity
     * stops there is no surface, so no frames, so nothing for the ADR-0018 tap
     * to tap. Recording is entirely unaffected.
     *
     * Without this the browser cannot tell that case apart from a dark room: the
     * page still loads, the state document still updates, the timer still runs,
     * and only the picture is missing. Decision by Davide, 2026-09-07 — the
     * behaviour is accepted, and the interface owes the user an explanation
     * rather than a black rectangle.
     *
     * Defaulted false, which is the safe way round: it says "no preview" before
     * it knows rather than promising one that never arrives.
     */
    val previewProducing: Boolean = false,
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
