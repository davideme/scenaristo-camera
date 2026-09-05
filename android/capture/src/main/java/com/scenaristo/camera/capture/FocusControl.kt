package com.scenaristo.camera.capture

import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCase
import com.scenaristo.camera.domain.protocol.Focus
import com.scenaristo.camera.domain.protocol.FocusMode

/**
 * Applies PRD 6.1's "continuous AF with face priority, lockable" to the camera.
 *
 * The protocol half of focus has existed since the `focus.set` command landed —
 * the command, the `Focus` value, its validation in `Session`, its golden
 * fixture — and nothing ever applied it. This is the half that touches a lens.
 *
 * **Only autofocus is metered here, never exposure.** `FocusMeteringAction` can
 * carry AE and AWB flags too, and using them would be a bug rather than a bonus:
 * the session runs with `CONTROL_AE_MODE_OFF` and `CONTROL_AWB_MODE_OFF` so the
 * app can own exposure (ADR-0005) and white balance (PRD 6.4). Asking the HAL to
 * meter either from a tap point would either be ignored or quietly take back the
 * control the whole product is built on.
 */
class FocusControl(
    private val cameraControl: CameraControl,
    /**
     * The use case the normalised point is expressed against.
     *
     * `Focus.x` and `Focus.y` are normalised *in the recording frame* — that is
     * what makes one pair of numbers mean the same place on the phone, in the
     * browser and in the file. Handing the factory the recording use case is what
     * makes the sensor agree with them, rather than with whatever the viewfinder
     * happened to be showing.
     */
    private val frameOf: UseCase,
) {

    /** What the camera was last told, so a repeated snapshot is not a new action. */
    private var applied: Focus? = null

    fun apply(focus: Focus) {
        if (focus == applied) return
        applied = focus

        if (focus.mode == FocusMode.CONTINUOUS) {
            // Back to PRD 6.1's default. Cancelling is what returns the lens to
            // continuous AF; simply not sending an action would leave the last
            // one latched, because it was started with auto-cancel disabled.
            cameraControl.cancelFocusAndMetering()
            return
        }

        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f, frameOf)
        val point = factory.createPoint(
            (focus.x ?: CENTRE).toFloat(),
            (focus.y ?: CENTRE).toFloat(),
        )
        cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                // A lock that expires after five seconds is not a lock. PRD 6.1
                // says "lockable", and a talking head does not move.
                .disableAutoCancel()
                .build(),
        )
    }

    private companion object {
        /**
         * Where a point-less lock focuses.
         *
         * PRD 6.1's "lockable" also covers "hold it where it already is", which
         * this only approximates: it refocuses at the centre rather than freezing
         * the lens where it stood. Freezing properly needs `CONTROL_AF_MODE_OFF`
         * and a fixed `LENS_FOCUS_DISTANCE`, which are interop keys and so
         * belong to `ManualControls` (ADR-0002). For a subject sitting in front
         * of a tripod the two are the same result; for anything else they are
         * not, and this comment is the debt.
         */
        const val CENTRE = 0.5
    }
}
