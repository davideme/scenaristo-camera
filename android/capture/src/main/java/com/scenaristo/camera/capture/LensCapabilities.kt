package com.scenaristo.camera.capture

/**
 * What one lens can do, as probed from `Camera2CameraInfo` characteristics plus
 * the CameraX feature-group check (ADR-0002). Populated by [ManualControls];
 * the gating rules in [LensGate] are pure so they can be tested without a device.
 */
data class LensCapabilities(
    val cameraId: String,
    val hasManualSensor: Boolean,
    val hasManualPostProcessing: Boolean,
    val supportsUhd30: Boolean,
)

/** How white balance is applied on a given lens (PRD 6.4). */
enum class WhiteBalanceMode {
    /** Full control: COLOR_CORRECTION_GAINS with a transform matrix. */
    GAINS,

    /** Degraded: locked AWB preset modes only. */
    LOCKED_PRESET,
}

/**
 * Per-lens capability gating (ADR-0011, answering PRD Open Question 1).
 */
object LensGate {

    /**
     * PRD 8-Q1 / ADR-0011: refuse to record on a lens without `MANUAL_SENSOR`.
     * Without it the shutter cannot be locked, which is the product's whole
     * promise, so degrading is not an option -- the lens is simply unusable.
     */
    fun canRecord(caps: LensCapabilities): Boolean = caps.hasManualSensor

    /**
     * PRD 6.4 / ADR-0011: without `MANUAL_POST_PROCESSING` the app cannot set
     * colour-correction gains, so it degrades to locked AWB presets rather than
     * refusing the lens.
     */
    fun whiteBalanceMode(caps: LensCapabilities): WhiteBalanceMode =
        if (caps.hasManualPostProcessing) WhiteBalanceMode.GAINS else WhiteBalanceMode.LOCKED_PRESET

    /**
     * PRD 6.10: "Given the device cannot do 4K at 30 fps, the app falls back to
     * 1080p and says so before recording."
     *
     * A fallback and not a refusal, unlike the `MANUAL_SENSOR` gate. 1080p at a
     * flicker-free shutter with a locked white balance is still the product;
     * 4K with rolling bands is not, which is why one degrades and the other
     * does not.
     */
    fun resolutionFor(caps: LensCapabilities): Resolution =
        if (caps.supportsUhd30) Resolution.UHD else Resolution.FHD

    /**
     * The lens to steer the user to, when the one they are on cannot record
     * (PRD 8 Open Question 1, answered by ADR-0011).
     *
     * "Refuse and steer" is only half an answer without somewhere to steer to.
     * First recordable in the order the platform lists them, which puts the main
     * camera first on both platforms -- and the main camera is the one PRD 6.1
     * defaults to anyway.
     */
    fun steerTo(lenses: List<LensCapabilities>): LensCapabilities? = lenses.firstOrNull(::canRecord)

    /**
     * One lens, in the terms PRD 6.10's report is written in.
     *
     * [hardwareHevc] is passed in rather than probed per lens, and the PRD is
     * slightly wrong about this: 6.10 asks to "probe **per lens** ... and
     * hardware HEVC", but the video encoder belongs to the device, not to a
     * camera. Every lens on a phone gets the same answer. Reported per lens
     * anyway, because the report is per lens and an absent row reads as a
     * failure, but the value comes from `CodecReport` once.
     */
    fun report(caps: LensCapabilities, label: String, hardwareHevc: Boolean): LensReport =
        LensReport(
            cameraId = caps.cameraId,
            label = label,
            canRecord = canRecord(caps),
            resolution = resolutionFor(caps),
            manualShutter = caps.hasManualSensor,
            whiteBalance = whiteBalanceMode(caps),
            hardwareHevc = hardwareHevc,
        )
}

/** What the app will actually record at on a given lens (PRD 6.1, 6.10). */
enum class Resolution(val width: Int, val height: Int) {
    UHD(3840, 2160),
    FHD(1920, 1080);

    override fun toString(): String = "${width}x$height"
}

/**
 * PRD 6.10's one-screen capability report, for one lens.
 *
 * The PRD's example is "Main camera: 4K30 checked, manual shutter checked,
 * manual WB approximated, HEVC checked. Ultrawide: manual shutter crossed", so
 * every field here is something that appears in that sentence -- and
 * [canRecord] is the one that decides whether the rest of the row is an offer or
 * an explanation.
 */
data class LensReport(
    val cameraId: String,
    val label: String,
    val canRecord: Boolean,
    val resolution: Resolution,
    val manualShutter: Boolean,
    val whiteBalance: WhiteBalanceMode,
    val hardwareHevc: Boolean,
) {
    /**
     * One line, in the shape PRD 6.10 asks for.
     *
     * A lens that cannot record says why and stops. Listing what else it can do
     * would be answering a question the user no longer has: ADR-0011 will not let
     * them record on it whatever the other three columns say.
     */
    fun line(): String = buildString {
        append(label)
        append(": ")
        if (!canRecord) {
            append("cannot record - no manual shutter on this lens")
            return@buildString
        }
        append(if (resolution == Resolution.UHD) "4K30 ok" else "1080p only (no 4K30)")
        append(", manual shutter ok, ")
        append(
            when (whiteBalance) {
                WhiteBalanceMode.GAINS -> "manual WB ok"
                WhiteBalanceMode.LOCKED_PRESET -> "manual WB approximated"
            },
        )
        append(if (hardwareHevc) ", HEVC ok" else ", H.264 only")
    }
}

/**
 * The whole report, one lens per line (PRD 6.10).
 *
 * A device where **no** lens can record is its own sentence rather than a list
 * of refusals: ADR-0011's gate is per lens, but a user whose every lens fails it
 * has a phone this app does not run on, and needs telling once.
 */
fun List<LensReport>.report(): String = when {
    isEmpty() -> "No cameras found."
    none { it.canRecord } ->
        "This phone cannot record with locked settings: no lens offers manual shutter control.\n" +
            joinToString("\n") { it.line() }
    else -> joinToString("\n") { it.line() }
}
