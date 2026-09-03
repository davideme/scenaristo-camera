package com.scenaristo.camera.capture

/**
 * The single place `Camera2Interop` is allowed to appear.
 *
 * ADR-0002 confines all interop here because the API is deprecated in CameraX
 * 1.7 in favour of a configurator API and Kotlin DSL, and the 1.7 revisit
 * migrates this class alone. CI enforces the confinement
 * (.github/workflows/ci.yml, job "guards"); a `Camera2Interop` reference in any
 * other file fails the build.
 *
 * The keys this class owns, once implemented (ADR-0002):
 *   CONTROL_AE_MODE = OFF, SENSOR_EXPOSURE_TIME, SENSOR_SENSITIVITY,
 *   SENSOR_FRAME_DURATION, CONTROL_AWB_MODE = OFF,
 *   COLOR_CORRECTION_MODE = TRANSFORM_MATRIX, COLOR_CORRECTION_GAINS,
 *   LENS_OPTICAL_STABILIZATION_MODE = OFF.
 *
 * Phase 0 (issue: "Verify Camera2Interop manual keys are honoured") must confirm
 * through the session capture callback that the first three echo the requested
 * values on both reference devices while recording UHD at [30, 30]. If a key is
 * not honoured, the recorded response is to wait for CameraX 1.7 -- not to fall
 * back to Camera2 direct.
 */
class ManualControls {
    // Implemented in Phase 1. Kept as the interop boundary from the first commit
    // so the CI guard has something to protect and nobody scatters interop calls.
}
