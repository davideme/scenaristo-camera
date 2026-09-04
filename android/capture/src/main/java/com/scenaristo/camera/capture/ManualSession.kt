package com.scenaristo.camera.capture

import androidx.camera.core.CameraEffect
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
import java.util.concurrent.Executors

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
     * Defaults to false, and that is a measured constraint rather than a
     * preference: on the Pixel 10 (#20, 2026-09-04) UHD recording and
     * `ImageAnalysis` are not a supported stream combination — not through the
     * feature group, not through `QualitySelector`, and not with the analysis
     * stream bounded to 960x540 or 640x480. ADR-0018 resolved that by sourcing
     * frames from [tap] instead. Setting this true asks for a session the
     * reference device refuses; it exists for the day a CameraX release makes
     * the combination bindable and #20's probe is re-run (#27).
     */
    private val includeAnalysis: Boolean = false,
    /**
     * The GL tap that replaces `ImageAnalysis` as the frame source (ADR-0018).
     * Null binds a session with no derived frames at all, which is what the #20
     * key-echo measurement used before the tap existed.
     */
    private val tap: PreviewTapProcessor? = null,
    /**
     * Pin every stream to one physical sensor of a logical multi-camera (#20).
     *
     * Null is what the product ships: the logical camera picks a sensor for the
     * zoom ratio in use. A non-null id is a measurement instrument -- it is the
     * only way to get a verdict that names the lens rather than naming whichever
     * sensor the HAL happened to serve.
     */
    private val physicalCameraId: String? = null,
    /**
     * Every capture result, for whoever else needs one.
     *
     * The exposure loop is the caller that matters: ADR-0005 has it wait for the
     * sensor to report the ISO it asked for before metering again, and this is
     * the only place that arrives. Runs on a camera thread.
     */
    private val onCaptureResult: (android.hardware.camera2.TotalCaptureResult) -> Unit = {},
) {

    val preview: Preview = Preview.Builder()
        .also { ManualControls.applyTo(it, request, ::record, physicalCameraId) }
        .build()

    val recorder: Recorder = Recorder.Builder().build()

    // Built through the builder rather than withOutput() so the recording stream
    // can be pinned to the same sensor as the preview. A session that pinned only
    // the preview would measure one lens while recording from another.
    val videoCapture: VideoCapture<Recorder> = VideoCapture.Builder(recorder)
        .also { builder -> physicalCameraId?.let { ManualControls.pinTo(builder, it) } }
        .build()

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
        .apply { tap?.let { addEffect(PreviewTapEffect(it)) } }
        .build()

    /** Latest capture result, for a live readout on the phone. */
    private val latest = MutableStateFlow<List<KeyEcho>>(emptyList())
    val latestEchoes: StateFlow<List<KeyEcho>> = latest.asStateFlow()

    private val lock = Any()

    /** Worst verdict per key rather than the latest; the rule is in [EchoAccumulator]. */
    private val accumulator = EchoAccumulator()

    /** The same results, split by the sensor that produced them (#20). */
    private val sweep = SweepAccumulator()

    /**
     * The zoom ratio the sweep last asked for, so a result can be attributed to
     * the rung that produced it. Written from the UI thread and read from a
     * camera thread, hence volatile; exactness does not matter because the
     * physical id, not this, decides the bucket.
     */
    @Volatile
    var sweepZoomRatio: Float = 1f

    /** Logical camera id, for results that report no physical sensor. */
    @Volatile
    var logicalCameraId: String = "?"

    private fun record(result: android.hardware.camera2.TotalCaptureResult) {
        val echoes = ManualControls.echoes(request, result)
        val physicalId = ManualControls.activePhysicalId(result)
        // The capture callback arrives on a camera thread; the accumulators are
        // not thread-safe by design, so the lock lives here.
        synchronized(lock) {
            accumulator.record(echoes)
            sweep.record(physicalId, logicalCameraId, sweepZoomRatio, echoes)
        }
        latest.value = echoes
        onCaptureResult(result)
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

    /** Per-sensor reports for the zoom sweep (#20). */
    fun sweepReports(): List<SweepLensReport> = synchronized(lock) { sweep.reports() }
}
