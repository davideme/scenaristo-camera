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
 */

/** One recording size the app is willing to shoot at (PRD 6.1, 6.10). */
data class RecordingSize(val widthPx: Int, val heightPx: Int) {
    override fun toString(): String = "${widthPx}x$heightPx"
}

/**
 * The sizes the app records at, widest first (PRD 6.1's default and 6.10's
 * sanctioned fallback).
 *
 * This is a ladder and not a range: [bestBlurSize] walks it from the top and
 * takes the first rung the device's blur ceiling can hold, which is what
 * "record at the best resolution that blurs *here*" means in practice.
 */
val BLUR_SIZE_LADDER: List<RecordingSize> = listOf(
    RecordingSize(3840, 2160),
    RecordingSize(1920, 1080),
)

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
    /** The largest stream the device will blur, from the mode's own advertisement. */
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

    /** Blurs, but not at any size this app is willing to record (PRD 6.10). */
    TOO_SMALL,

    /** Blurs, but takes manual shutter and ISO with it. Davide's rule: refuse. */
    MANUAL_KEYS_LOST,

    /** Blurs and keeps the keys, but not at a constant 30.00 fps (PRD 6.1). */
    FRAME_RATE_LOST,

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
fun blurVerdict(
    capability: BlurCapability,
    ladder: List<RecordingSize> = BLUR_SIZE_LADDER,
): BlurVerdict = when {
    !capability.advertised -> BlurVerdict.NOT_ADVERTISED
    !capability.continuousMode -> BlurVerdict.NO_CONTINUOUS_MODE
    bestBlurSize(capability, ladder) == null -> BlurVerdict.TOO_SMALL
    !capability.manualKeysHeld -> BlurVerdict.MANUAL_KEYS_LOST
    !capability.frameRateHeld -> BlurVerdict.FRAME_RATE_LOST
    else -> BlurVerdict.SUPPORTED
}

/**
 * The size the app would record at with blur on, or null when the device blurs
 * nothing large enough to be worth recording.
 *
 * This is the resolution policy Davide chose on 2026-09-09, and it is the same
 * answer ADR-0030 got the day before: **derived from the device, not written
 * into the product.** The ladder is ours; the ceiling is theirs. A phone that
 * blurs at UHD costs nothing to turn blur on; one that stops at 1080p offers a
 * trade, and one that stops below 1080p offers nothing.
 *
 * Only the advertised ceiling is consulted here. Whether the device then
 * actually binds at that size is a separate claim, and one no characteristic
 * can make -- ADR-0018 measured this device's own answers to be optimistic, so
 * the probe binds and reads back.
 */
fun bestBlurSize(
    capability: BlurCapability,
    ladder: List<RecordingSize> = BLUR_SIZE_LADDER,
): RecordingSize? {
    if (!capability.advertised || !capability.continuousMode) return null
    return ladder.firstOrNull {
        it.widthPx <= capability.maxWidthPx && it.heightPx <= capability.maxHeightPx
    }
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
    BlurVerdict.TOO_SMALL -> "background blur: not supported below 1080p"
    BlurVerdict.MANUAL_KEYS_LOST -> "background blur: unavailable - it would unlock the shutter"
    BlurVerdict.FRAME_RATE_LOST -> "background blur: unavailable - it would drop below 30 fps"
    BlurVerdict.SUPPORTED -> "background blur: ok at ${bestBlurSize(capability)}"
}
