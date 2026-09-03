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
}
