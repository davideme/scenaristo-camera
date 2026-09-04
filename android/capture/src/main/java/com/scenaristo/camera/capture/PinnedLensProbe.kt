package com.scenaristo.camera.capture

import android.content.Context
import android.util.Range
import androidx.camera.core.CameraSelector
import androidx.camera.core.SessionConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.GroupableFeatures
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay

/**
 * One UHD30 session pinned to one physical sensor, for #20's per-lens verdict.
 *
 * Why this is not [ManualSession]: pinning means rebinding once per lens, and
 * the sweep has to be able to run with no activity on screen -- a `Preview` with
 * no surface provider produces no frames, so a session built around the phone
 * viewfinder cannot be measured while the phone is locked. This binds
 * `VideoCapture` alone and puts the manual keys on its builder, since the keys
 * ride the repeating request either way.
 *
 * **That is a deliberate narrowing and it must be written up as one.** It answers
 * "does this sensor honour the keys while recording UHD30", not "does the shipped
 * three-stream session honour them" -- which is what the main-lens measurement in
 * ADR-0002 action item 2 already answered, with the preview and tap bound.
 */
class PinnedLensProbe(
    private val request: ManualControls.Request,
    /** Null measures the logical camera, letting it choose its own sensor. */
    private val physicalCameraId: String?,
) {

    val recorder: Recorder = Recorder.Builder().build()

    val videoCapture: VideoCapture<Recorder> = VideoCapture.Builder(recorder)
        .also { ManualControls.applyTo(it, request, ::record, physicalCameraId) }
        .build()

    /**
     * UHD30 required, not preferred: a session that quietly fell back to 1080p
     * would report the keys echoing perfectly and answer a question nobody asked.
     * A refused bind is itself a #20 result for that lens.
     */
    val sessionConfig: SessionConfig = SessionConfig.Builder(listOf(videoCapture))
        .setRequiredFeatureGroup(GroupableFeatures.UHD_RECORDING)
        .setFrameRateRange(Range(30, 30))
        .build()

    private val lock = Any()
    private val accumulator = EchoAccumulator()

    private fun record(result: android.hardware.camera2.TotalCaptureResult) {
        val echoes = ManualControls.echoes(request, result)
        synchronized(lock) { accumulator.record(echoes) }
    }

    fun report(lensLabel: String): LensEchoReport =
        synchronized(lock) { accumulator.report(physicalCameraId ?: "logical", lensLabel) }

    /**
     * What the stream actually bound at, read back after binding.
     *
     * Required, not decorative: every CameraX capability query on the reference
     * device has proved optimistic (#20, ADR-0018), and a session that reported
     * UHD support and bound at 720p would produce a table of perfectly honoured
     * keys about the wrong resolution.
     */
    val boundResolution: String?
        get() = videoCapture.resolutionInfo?.resolution?.let { "${it.width}x${it.height}" }
}

/**
 * Binds each physical sensor of the back camera in turn and reports whether the
 * manual keys hold on each (#20).
 *
 * Sequential and rebinding between lenses because a physical camera id is fixed
 * at bind time. The caller's own session is unbound for the duration; restoring
 * it is the caller's job, since only the caller knows what it had.
 */
object LensSweepRunner {

    /** Per lens. Long enough that a device re-enabling AE after a beat is caught. */
    private const val SECONDS_PER_LENS = 6

    suspend fun run(
        context: Context,
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        request: ManualControls.Request,
        logicalCameraId: String,
    ): String {
        val ids = ManualControls.physicalIdsOf(context, logicalCameraId).sorted()
        val results = mutableListOf<Pair<String, Result<LensEchoReport>>>()

        // The logical camera first, as the control: it is what the product binds.
        for (id in listOf(null) + ids) {
            val label = id?.let { "physical id $it" } ?: "logical id $logicalCameraId (unpinned)"
            results += label to runCatching {
                val probe = PinnedLensProbe(request, id)
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, probe.sessionConfig)
                delay(SECONDS_PER_LENS * 1000L)
                probe.report("$label at ${probe.boundResolution ?: "unknown resolution"}")
            }
        }
        provider.unbindAll()
        return markdown(logicalCameraId, results)
    }

    private fun markdown(
        logicalCameraId: String,
        results: List<Pair<String, Result<LensEchoReport>>>,
    ): String = buildString {
        appendLine("Pinned lens sweep on logical camera `$logicalCameraId`, UHD30, VideoCapture only.")
        appendLine()
        for ((label, result) in results) {
            result.fold(
                onSuccess = { appendLine(it.markdown()) },
                // A lens that will not bind UHD30 is a result, not an error to
                // swallow: ADR-0011 gates on what a lens can actually do.
                onFailure = { appendLine("**$label** — bind refused: ${it.message}") },
            )
            appendLine()
        }
    }
}
