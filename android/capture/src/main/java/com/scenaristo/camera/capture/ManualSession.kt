package com.scenaristo.camera.capture

import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.video.GroupableFeatures
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import android.util.Range
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The UHD30 session the whole product is built on, and the readout Phase 0 (#20)
 * needs from it.
 *
 * Three use cases are bound together because that is the combination the app
 * actually ships (ADR-0002): `Preview` feeds the phone screen, `VideoCapture`
 * records, and `ImageAnalysis` feeds both the metering loop (ADR-0005) and the
 * MJPEG preview (ADR-0008). Verifying the keys against a simpler session would
 * prove nothing about the one that matters -- stream combinations are exactly
 * where devices run out of capability.
 *
 * The manual keys go on the `Preview` builder, which carries the repeating
 * request, so the recording and the analysis stream inherit one locked sensor
 * state instead of each carrying its own copy.
 */
class ManualSession(
    private val request: ManualControls.Request,
    /**
     * Whether to bind `ImageAnalysis` alongside the recording.
     *
     * False is not a preference, it is a measured constraint: on the Pixel 10
     * (#20, 2026-09-04) UHD recording and `ImageAnalysis` are not a supported
     * stream combination — not through the feature group, not through
     * `QualitySelector`, and not with the analysis stream bounded to 960x540 or
     * 640x480. That breaks the premise ADR-0005 and ADR-0008 share, so the
     * resolution is an ADR, not a default flipped here. Until then this flag
     * exists so the interop keys can still be measured on a session the device
     * will actually accept.
     */
    private val includeAnalysis: Boolean = true,
) {

    val preview: Preview = Preview.Builder()
        .also { ManualControls.applyTo(it, request, ::record) }
        .build()

    val recorder: Recorder = Recorder.Builder().build()

    val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(recorder)

    val imageAnalysis: ImageAnalysis = ImageAnalysis.Builder()
        // Newest frame wins: metering and preview both want current, not complete.
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    /**
     * UHD at exactly 30 fps, required rather than preferred.
     *
     * `setRequiredFeatureGroup` means the bind fails loudly if the device cannot
     * do it, which is what a measurement wants: a session that silently fell back
     * to 1080p would still report keys echoing perfectly and would answer a
     * question nobody asked. `Range(30, 30)` pins the rate rather than allowing
     * 24-30, so the frame duration the sensor reports is comparable to what was
     * requested (PRD 6.1).
     */
    val sessionConfig: SessionConfig = SessionConfig.Builder(
        listOfNotNull(preview, videoCapture, imageAnalysis.takeIf { includeAnalysis }),
    )
        .setRequiredFeatureGroup(GroupableFeatures.UHD_RECORDING)
        .setFrameRateRange(Range(30, 30))
        .build()

    /** Latest capture result, for a live readout on the phone. */
    private val latest = MutableStateFlow<List<KeyEcho>>(emptyList())
    val latestEchoes: StateFlow<List<KeyEcho>> = latest.asStateFlow()

    private val lock = Any()

    /** Worst verdict per key rather than the latest; the rule is in [EchoAccumulator]. */
    private val accumulator = EchoAccumulator()

    private fun record(result: android.hardware.camera2.TotalCaptureResult) {
        val echoes = ManualControls.echoes(request, result)
        // The capture callback arrives on a camera thread; the accumulator is not
        // thread-safe by design, so the lock lives here.
        synchronized(lock) { accumulator.record(echoes) }
        latest.value = echoes
    }

    /**
     * Whether this device can actually run the session, asked of the whole
     * config rather than of the lens: in CameraX 1.6 UHD30 availability depends
     * on what else is bound alongside it, so a per-lens answer would be a guess.
     */
    fun isSupported(cameraInfo: CameraInfo): Boolean =
        cameraInfo.isSessionConfigSupported(sessionConfig)

    fun capabilities(cameraInfo: CameraInfo): LensCapabilities =
        ManualControls.probe(cameraInfo, supportsUhd30 = isSupported(cameraInfo))

    /**
     * The run so far, worst-frame-wins. Safe to call while recording; the counts
     * only grow.
     */
    fun report(cameraId: String, lensLabel: String): LensEchoReport =
        synchronized(lock) { accumulator.report(cameraId, lensLabel) }
}
