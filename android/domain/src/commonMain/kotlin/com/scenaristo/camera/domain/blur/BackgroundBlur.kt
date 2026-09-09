package com.scenaristo.camera.domain.blur

/**
 * Whether this device may offer background blur on a recording, and at what
 * size (ADR-0031).
 *
 * Pure, and here rather than in `:capture`, because ADR-0011 says gating is
 * "a pure function from the capability report to the allowed control set" and
 * ADR-0013 makes that the shape Phase 4 inherits. iOS reaches the same question
 * through a different door -- `AVCaptureDevice`'s portrait effect rather than a
 * streaming scene mode -- but the rules about what earns the toggle are the
 * same, and are these.
 *
 * The distinction the whole file turns on: **advertised is not measured.** A
 * camera's characteristics say what a mode is for; only running it says what it
 * costs. [BlurCapability] therefore carries both halves and [blurVerdict]
 * refuses to call the advertised half an answer.
 *
 * On the reference Pixel 10 that distinction turned out to be the whole story,
 * and not in the direction anyone expected. The device advertises
 * `BOKEH_CONTINUOUS` at 1920x1080, accepts it, and **echoes the mode back on
 * every capture result while applying no blur whatsoever** -- measured against a
 * face on 2026-09-09 at both 3840x2160 and 1920x1080, background sharpness
 * unchanged to within noise.
 *
 * That is why [BlurCapability.appliesToFootage] exists and why it is the last
 * check. Everywhere else in this codebase an echoed key is the end of the
 * argument: ADR-0002 action item 2 verifies the manual keys precisely by asking
 * whether the camera echoed them. This mode is the case where that is not
 * enough, so the capability requires positive evidence that the *picture*
 * changed, which only a recording can give.
 */

/**
 * What one camera can do about background blur.
 *
 * The first four fields are read from the camera's characteristics and are
 * cheap. The last two can only come from a run, and default to false for the
 * same reason `Capabilities.probed` exists: not having looked is a different
 * state from having looked and found nothing, and only one of them may be
 * reported to a user as an offer.
 */
data class BlurCapability(
    /** The camera advertises at least one streaming scene mode. */
    val advertised: Boolean = false,
    /**
     * The advertised mode is the *continuous* one.
     *
     * A still-capture bokeh is not this feature. It blurs a photograph, and this
     * app has no photograph to blur -- it records, and ADR-0002 says plainly
     * "we never use `ImageCapture`". A device offering only that is a no
     * wearing a yes.
     */
    val continuousMode: Boolean = false,
    /**
     * The largest stream the device *says* it will blur.
     *
     * **Reported, not enforced** (Davide, 2026-09-09). The reference Pixel 10
     * advertises 1920x1080 and was then measured applying blur at 3840x2160,
     * so the ceiling is the vendor's statement rather than the device's
     * behaviour, and it does not decide what the app records at. Keeping it
     * because it is evidence: a device whose ceiling and behaviour disagree is
     * worth knowing about, and PRD 6.10's report says so.
     */
    val maxWidthPx: Int = 0,
    val maxHeightPx: Int = 0,
    /**
     * The six manual keys still echoed correctly while the mode was active
     * (ADR-0002 action item 2).
     *
     * Unknowable from characteristics: the scene-mode family shares
     * `CONTROL_MODE` with the 3A routines, and a device that takes exposure back
     * when asked for blur has not got this feature as far as this product is
     * concerned. Davide decided on 2026-09-09 that manual exposure wins.
     */
    val manualKeysHeld: Boolean = false,
    /** 30.00 fps constant still held while it was active (PRD 6.1). */
    val frameRateHeld: Boolean = false,
    /**
     * The footage was measurably different with the mode on.
     *
     * The check the reference device failed, and the reason this is not simply
     * inferred from the other flags. On the Pixel 10 the mode is advertised,
     * accepted, and echoed back on every frame -- and the recording is
     * pixel-for-pixel as sharp as the control. An echo says the camera *heard*
     * the request; only a file says it did anything.
     *
     * Set from a comparison of a take with the mode against a take without it,
     * never from characteristics and never from a capture result.
     */
    val appliesToFootage: Boolean = false,
)

/**
 * Why blur is or is not offered. Ordered as the reasons are checked, so the
 * first true one is the answer and the enum reads as the decision tree it is.
 */
enum class BlurVerdict {
    /** No streaming scene mode at all. The commonest answer, and not a fault. */
    NOT_ADVERTISED,

    /** Still-capture bokeh only, which cannot reach a recording. */
    NO_CONTINUOUS_MODE,

    /** Blurs, but takes manual shutter and ISO with it. Davide's rule: refuse. */
    MANUAL_KEYS_LOST,

    /** Blurs and keeps the keys, but not at a constant 30.00 fps (PRD 6.1). */
    FRAME_RATE_LOST,

    /**
     * Advertised, accepted, echoed -- and the footage is unchanged.
     *
     * The reference Pixel 10's answer (2026-09-09). Distinct from
     * [NOT_ADVERTISED] because the device claims the mode and a client asking
     * only what it supports would believe it.
     */
    NOT_APPLIED,

    /** Offer it. */
    SUPPORTED,
}

/**
 * The verdict for one camera.
 *
 * The order of the checks is the contract, not an implementation detail, and it
 * is tested as one. [BlurVerdict.MANUAL_KEYS_LOST] outranking an otherwise
 * perfect capability is the whole reason this is a function and not a flag:
 * a device can advertise the mode, bind it at UHD and blur beautifully, and
 * still be refused because the shutter stopped being ours.
 */
fun blurVerdict(capability: BlurCapability): BlurVerdict = when {
    !capability.advertised -> BlurVerdict.NOT_ADVERTISED
    !capability.continuousMode -> BlurVerdict.NO_CONTINUOUS_MODE
    !capability.manualKeysHeld -> BlurVerdict.MANUAL_KEYS_LOST
    !capability.frameRateHeld -> BlurVerdict.FRAME_RATE_LOST
    // Last, because it is the most expensive to establish and the only one that
    // needs two recordings compared against each other.
    !capability.appliesToFootage -> BlurVerdict.NOT_APPLIED
    else -> BlurVerdict.SUPPORTED
}

/**
 * The ceiling the device advertises, for the report, or null when it advertises
 * no streaming blur at all.
 *
 * **Nothing gates on this.** It is here so PRD 6.10's report can say what the
 * device claims beside what it was measured doing, which on the reference
 * Pixel 10 are not the same thing.
 */
fun advertisedCeiling(capability: BlurCapability): String? =
    if (!capability.advertised || !capability.continuousMode) {
        null
    } else {
        "${capability.maxWidthPx}x${capability.maxHeightPx}"
    }

/** Whether the toggle may be offered at all (PRD 6.10, ADR-0011). */
fun canBlur(capability: BlurCapability): Boolean =
    blurVerdict(capability) == BlurVerdict.SUPPORTED

/**
 * The verdict as PRD 6.10's report says it, in the same voice as
 * `LensReport.line()`: what the user gets, or why not.
 *
 * PRD 6.10 asks for controls that are unavailable to be labelled
 * "Not supported on this lens" *rather than pretending*, and #125 is the issue
 * that will render this. A reason is more use than a cross, so each one says
 * which wall it hit.
 */
fun blurLine(capability: BlurCapability): String = when (blurVerdict(capability)) {
    BlurVerdict.NOT_ADVERTISED -> "background blur: not supported on this lens"
    BlurVerdict.NO_CONTINUOUS_MODE -> "background blur: stills only on this lens, not while recording"
    BlurVerdict.MANUAL_KEYS_LOST -> "background blur: unavailable - it would unlock the shutter"
    BlurVerdict.FRAME_RATE_LOST -> "background blur: unavailable - it would drop below 30 fps"
    // PRD 6.10 asks for "not supported on this lens" rather than pretending, and
    // this is the case the sentence was written for: the device is the one
    // pretending, and the app declines to pass it on.
    BlurVerdict.NOT_APPLIED -> "background blur: not supported on this lens"
    BlurVerdict.SUPPORTED -> "background blur: ok"
}
