package com.scenaristo.camera.capture

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.ExtendableBuilder
import com.scenaristo.camera.domain.exposure.IsoRange
import com.scenaristo.camera.domain.lens.equivalentFocalLengthMm
import com.scenaristo.camera.domain.whitebalance.AwbApproximation
import com.scenaristo.camera.domain.whitebalance.approximationFor

/**
 * The single place `Camera2Interop` is allowed to appear.
 *
 * ADR-0002 confines all interop here because the API is deprecated in CameraX
 * 1.7 in favour of a configurator API and Kotlin DSL, and the 1.7 revisit
 * migrates this class alone. CI enforces the confinement
 * (.github/workflows/ci.yml, job "guards"); an interop reference in any other
 * file fails the build -- and because the guard greps file *contents*, that
 * includes comments.
 *
 * Phase 0 (#20) must confirm through the session capture callback that the
 * requested values echo back while recording UHD at [30, 30] on the Pixel 10
 * (ADR-0017). The judgement of "did it echo" lives in ManualKeyEcho.kt, which
 * is pure Kotlin and host-tested; this file only sets the keys and reads the
 * results, because everything here needs a real camera.
 */
// The interop marker is a Java @RequiresOptIn, so the opt-in is androidx's
// markerClass form. Kotlin's own @OptIn compiles but does nothing here, and
// lint fails the build to say so.
@OptIn(markerClass = [ExperimentalCamera2Interop::class])
object ManualControls {

    /**
     * What the app asks the sensor for. Shutter and frame duration come from the
     * flicker-safe ladder (PRD 6.2, ADR-0005); ISO is the only variable the
     * metering loop moves once the shutter is locked (PRD 6.3).
     */
    data class Request(
        /** Nanoseconds. 1/50 s is 20_000_000. */
        val exposureTimeNs: Long,
        val sensitivity: Int,
        /** Nanoseconds. 30 fps is 33_333_333, which pins the frame rate (PRD 6.1). */
        val frameDurationNs: Long,
        /**
         * A **locked** `CONTROL_AWB_MODE`, not `OFF` (PRD 6.4, ADR-0011).
         *
         * `OFF` was what this asked for until now, and `OFF` with no
         * `COLOR_CORRECTION_GAINS` alongside it is not a white balance at all --
         * it leaves the correction at whatever the driver last had, which is
         * neither locked nor 5600 K. A named preset is locked by definition and
         * is exactly the fallback PRD 6.4 describes.
         *
         * [awbModeFor] maps a preset temperature onto one of these.
         */
        val awbMode: Int,
    )

    /** Off, for every mode the app takes over. Camera2 spells all three as 0. */
    private const val MODE_OFF = 0L

    /**
     * Applies the manual keys to a use-case builder and routes every capture
     * result to [onResult].
     *
     * The keys go on the *repeating* request, so this is called with the
     * `Preview` builder: the recording and the analysis stream then inherit the
     * same locked sensor state rather than each carrying its own copy.
     *
     * ADR-0002 extends PRD 6.1's "stabilisation off" to OIS, which drifts on a
     * tripod. AE and AWB go off explicitly: without that, setting an exposure
     * time is advisory and the device may quietly keep metering.
     *
     * [physicalCameraId] pins the stream to one sensor of a logical multi-camera.
     * #20 asks for a verdict per lens, and leaving the choice to zoom leaves it
     * to the HAL: it may serve a ratio from whichever sensor it prefers, so an
     * unpinned sweep can miss a lens entirely and can never prove which one a
     * failure belongs to. Null keeps the logical camera's own behaviour, which is
     * what the product ships.
     */
    fun <T> applyTo(
        builder: ExtendableBuilder<T>,
        request: Request,
        onResult: (TotalCaptureResult) -> Unit,
        physicalCameraId: String? = null,
    ) {
        Camera2Interop.Extender(builder)
            .also { extender -> physicalCameraId?.let { extender.setPhysicalCameraId(it) } }
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, request.awbMode)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, request.exposureTimeNs)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, request.sensitivity)
            .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, request.frameDurationNs)
            .setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
            .setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        captureRequest: CaptureRequest,
                        result: TotalCaptureResult,
                    ) = onResult(result)
                },
            )
    }

    /**
     * Changes the exposure on a bound camera, without rebinding.
     *
     * [applyTo] sets the keys when a use case is *built*, which is where the
     * session's opening state comes from and is no use to a loop that moves ISO
     * six times a second (ADR-0005). This is the runtime path, and CameraX merges
     * these over the ones the extender set.
     *
     * `CONTROL_AE_MODE_OFF` is repeated here rather than assumed. The extender
     * set it, and it is the precondition for the sensor honouring anything else,
     * so the runtime request states it too instead of depending on which set of
     * options wins a merge.
     */
    fun apply(cameraControl: CameraControl, request: Request) {
        Camera2CameraControl.from(cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, request.awbMode)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, request.exposureTimeNs)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, request.sensitivity)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, request.frameDurationNs)
                .build(),
        )
    }

    /**
     * What the sensor says it actually used, or null on a result that carries
     * neither key.
     *
     * This is the echo ADR-0005 makes the loop wait for before metering again.
     * [echoes] answers a different question -- "did every key survive" -- and
     * keeps its own shape because ADR-0002 action item 2 reports on all six.
     */
    fun reported(result: CaptureResult): Request? {
        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        return Request(
            exposureTimeNs = exposureTimeNs,
            sensitivity = sensitivity,
            frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L,
            // What the sensor says it used, so a caller comparing this to a
            // request sees the white balance too. Absent on a result that does
            // not carry it, which is not the same as OFF -- so it reports the
            // Camera2 sentinel for "unknown" rather than inventing a mode.
            awbMode = result.get(CaptureResult.CONTROL_AWB_MODE) ?: -1,
        )
    }

    /**
     * The ISO range this lens reports, which bounds everything the loop may ask
     * for (ADR-0005, ADR-0011).
     *
     * Null when the lens does not declare one, which for a lens that passed
     * ADR-0011's `MANUAL_SENSOR` gate should not happen -- the capability implies
     * the key. A caller that gets null has a device worth reporting, not a
     * default worth inventing.
     */
    fun isoRange(cameraInfo: CameraInfo): IsoRange? =
        Camera2CameraInfo.from(cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?.let { IsoRange(min = it.lower, max = it.upper) }

    /**
     * The requested and reported value of every key ADR-0002 action item 2 lists.
     *
     * A key the result does not carry becomes a null `observed`, which
     * [ManualKeyEcho] reports as ABSENT rather than as a mismatch: the two mean
     * different things, and only one of them is the camera disagreeing with us.
     */
    fun echoes(request: Request, result: CaptureResult): List<KeyEcho> = listOf(
        KeyEcho(
            ManualKey.SENSOR_EXPOSURE_TIME,
            request.exposureTimeNs,
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        ),
        KeyEcho(
            ManualKey.SENSOR_SENSITIVITY,
            request.sensitivity.toLong(),
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.toLong(),
        ),
        KeyEcho(
            ManualKey.SENSOR_FRAME_DURATION,
            request.frameDurationNs,
            result.get(CaptureResult.SENSOR_FRAME_DURATION),
        ),
        KeyEcho(
            ManualKey.CONTROL_AE_MODE,
            MODE_OFF,
            result.get(CaptureResult.CONTROL_AE_MODE)?.toLong(),
        ),
        KeyEcho(
            ManualKey.CONTROL_AWB_MODE,
            request.awbMode.toLong(),
            result.get(CaptureResult.CONTROL_AWB_MODE)?.toLong(),
        ),
        KeyEcho(
            ManualKey.LENS_OPTICAL_STABILIZATION_MODE,
            MODE_OFF,
            result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)?.toLong(),
        ),
    )

    /**
     * The platform white balance mode that stands in for a Kelvin preset
     * (PRD 6.4's Android note, ADR-0011).
     *
     * Which preset is nearest is decided in `:domain`, in mired, so both
     * platforms agree; this only carries the answer across to the Camera2
     * constant, which is the part that cannot leave this module.
     *
     * **Not the gains path.** ADR-0011 says a lens with `MANUAL_POST_PROCESSING`
     * should get `COLOR_CORRECTION_GAINS` computed from Kelvin, which is more
     * accurate than four fixed presets. That needs the device-calibrated curve
     * PRD 6.4 describes, and measuring it is #24 -- deferred to Phase 3 with the
     * grey card it requires. Until then every lens takes the preset path, which
     * is a locked white balance that is approximately right rather than an
     * unlocked one that is arbitrary.
     */
    fun awbModeFor(kelvin: Int): Int = when (approximationFor(kelvin)) {
        AwbApproximation.INCANDESCENT -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        AwbApproximation.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        AwbApproximation.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        AwbApproximation.CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
    }

    /**
     * Pins one stream to a physical sensor, without touching the manual keys.
     *
     * Separate from [applyTo] because the keys go on the repeating request once:
     * calling [applyTo] for a second use case would register a second capture
     * callback and count every frame twice, which would silently inflate the
     * frame counts #20 reports.
     */
    fun <T> pinTo(builder: ExtendableBuilder<T>, physicalCameraId: String) {
        Camera2Interop.Extender(builder).setPhysicalCameraId(physicalCameraId)
    }

    /**
     * The physical sensors behind a logical camera, or empty for a single-sensor
     * camera.
     *
     * Read from `CameraManager` rather than `Camera2CameraInfo`: the ids come
     * from `CameraCharacteristics.getPhysicalCameraIds()`, which is a method
     * rather than a `Key`, and `Camera2CameraInfo` only exposes keys.
     */
    fun physicalIdsOf(context: Context, logicalCameraId: String): Set<String> = runCatching {
        context.getSystemService(CameraManager::class.java)
            .getCameraCharacteristics(logicalCameraId)
            .physicalCameraIds
    }.getOrDefault(emptySet())

    /**
     * Which physical sensor produced this result, on a logical multi-camera.
     *
     * The reference device exposes its ultrawide and telephoto only as physical
     * cameras behind the back logical camera (#20), so this is the only way to
     * name the lens a frame actually came from -- and the only way to notice the
     * device swapping sensors mid-session, which is what [SweepAccumulator]
     * buckets on.
     *
     * Null on a camera with nothing to report, which is not a failure.
     */
    fun activePhysicalId(result: CaptureResult): String? =
        result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)

    /**
     * The active lens's focal length as a 35 mm equivalent (PRD 6.5), or null
     * when the characteristics do not say.
     *
     * Both halves are needed and neither is the answer on its own: a phone lens
     * is a number like 6.9 mm, which means nothing without the size of the
     * sensor behind it. The arithmetic is in `:domain` so iOS inherits it; this
     * only reads the two Camera2 keys.
     *
     * The **shortest** available focal length, when a lens reports several. A
     * lens that reports a range is a zoom, and PRD 6.5's distortion question is
     * about the widest thing it can do -- which is where a face is at risk.
     */
    fun equivalentFocalLength(cameraInfo: CameraInfo): Int? {
        val info = Camera2CameraInfo.from(cameraInfo)
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull() ?: return null
        val size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return null
        return equivalentFocalLengthMm(
            focalLengthMm = focal.toDouble(),
            sensorWidthMm = size.width.toDouble(),
            sensorHeightMm = size.height.toDouble(),
        ).takeIf { it > 0 }
    }

    /**
     * Probes one lens for the flags ADR-0011 gates on.
     *
     * `supportsUhd30` is left to the caller: in CameraX 1.6 that question is
     * asked of a whole `SessionConfig` through `CameraInfo.isSessionConfigSupported`,
     * not of a lens in isolation, because whether UHD30 is available depends on
     * what else is bound alongside it.
     */
    fun probe(cameraInfo: CameraInfo, supportsUhd30: Boolean): LensCapabilities {
        val info = Camera2CameraInfo.from(cameraInfo)
        val capabilities = info
            .getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet()
            .orEmpty()
        return LensCapabilities(
            cameraId = info.cameraId,
            hasManualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities,
            hasManualPostProcessing =
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities,
            supportsUhd30 = supportsUhd30,
        )
    }
}
