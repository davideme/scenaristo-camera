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
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.ExtendableBuilder

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
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
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
            MODE_OFF,
            result.get(CaptureResult.CONTROL_AWB_MODE)?.toLong(),
        ),
        KeyEcho(
            ManualKey.LENS_OPTICAL_STABILIZATION_MODE,
            MODE_OFF,
            result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)?.toLong(),
        ),
    )

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
