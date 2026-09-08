package com.scenaristo.camera.capture

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.scenaristo.camera.domain.exposure.FrameRect
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a face is and where the person ends, for a studio look to shape against
 * (PRD 6.11, ADR-0029).
 *
 * An `ImageAnalysis.Analyzer`, which is ML Kit's documented shape and the one
 * Google's own `camerax-greenscreen` sample uses for this pair of concerns. It
 * exists on this path and not on the ADR-0018 tap because `ImageAnalysis` cannot
 * sit beside a UHD recording on every device — `AnalysisRecordingProbe` asks each
 * one what it will allow, and the look records at that.
 *
 * **It is not expected to see every frame.** `STRATEGY_KEEP_ONLY_LATEST` drops
 * what this cannot keep up with, by design. Measured on the reference Pixel 10 at
 * 640×360 on 2026-09-08: mesh 40–50 ms, mask 17–27 ms, so the pair sustains
 * roughly 14 Hz against a 30 fps capture. What the shader does between refreshes
 * — hold or interpolate — is its decision, not this class's, and this publishes
 * nothing to say a frame was skipped because a skipped frame is not news.
 */
class LookSource : ImageAnalysis.Analyzer {

    /**
     * One analysed frame: where the face is, and where the person is.
     *
     * [face] is normalised in the analysed frame, which is not the recording's
     * frame — the shader scales it, because only the shader knows what it is
     * drawing into.
     *
     * [mask] is ML Kit's confidence buffer, floats in 0..1, one per mask pixel.
     * Handed on rather than copied: it is the largest thing here and the segmenter
     * gives a fresh one each time.
     */
    data class Look(
        val face: FrameRect?,
        val splitX: Double?,
        val mask: ByteBuffer?,
        val maskWidth: Int,
        val maskHeight: Int,
        val frameWidth: Int,
        val frameHeight: Int,
    )

    private val meshDetector = FaceMeshDetection.getClient(
        FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
            .build(),
    )

    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            // The camera is a stream, not an album: STREAM_MODE lets the
            // segmenter use the previous mask as a prior, which is both faster
            // and steadier frame to frame.
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .build(),
    )

    private val _look = MutableStateFlow<Look?>(null)
    val look: StateFlow<Look?> = _look.asStateFlow()

    private val inFlight = AtomicBoolean(false)

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        // One frame in flight. Without this the two models queue behind each
        // other and the numbers stop being latencies, which is also how a slow
        // frame turns into a growing backlog rather than a dropped frame.
        if (!inFlight.compareAndSet(false, true)) {
            proxy.close()
            return
        }

        val rotation = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        // Rotation swaps which axis is long, and everything published here is
        // normalised against the *upright* frame the models saw.
        val upright = rotation % 180 != 0
        val width = if (upright) proxy.height else proxy.width
        val height = if (upright) proxy.width else proxy.height

        meshDetector.process(input)
            .addOnCompleteListener { meshes ->
                val points = meshes.result?.firstOrNull()?.allPoints
                val face = points?.takeIf { it.isNotEmpty() }?.let { boundsOf(it, width, height) }
                val splitX = points?.takeIf { it.isNotEmpty() }?.let { splitOf(it, width) }

                segmenter.process(input)
                    .addOnCompleteListener { mask ->
                        val m = mask.result
                        _look.value = Look(
                            face = face,
                            splitX = splitX,
                            mask = m?.buffer,
                            maskWidth = m?.width ?: 0,
                            maskHeight = m?.height ?: 0,
                            frameWidth = width,
                            frameHeight = height,
                        )
                        if (mask.exception != null) {
                            Log.w(TAG, "segmentation failed", mask.exception)
                        }
                        // Closed here and nowhere else: both models read from the
                        // same media image, so releasing it after the first would
                        // hand the second a buffer the camera has taken back.
                        inFlight.set(false)
                        proxy.close()
                    }
            }
    }

    private fun boundsOf(
        points: List<com.google.mlkit.vision.facemesh.FaceMeshPoint>,
        width: Int,
        height: Int,
    ): FrameRect? {
        if (width <= 0 || height <= 0) return null
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (p in points) {
            val x = p.position.x
            val y = p.position.y
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        if (right <= left || bottom <= top) return null
        return FrameRect(
            left = (left / width).toDouble().coerceIn(0.0, 1.0),
            top = (top / height).toDouble().coerceIn(0.0, 1.0),
            right = (right / width).toDouble().coerceIn(0.0, 1.0),
            bottom = (bottom / height).toDouble().coerceIn(0.0, 1.0),
        )
    }

    /**
     * Where the face's two halves divide, from the mesh's own midline.
     *
     * The mean of every point is a better nose line than the middle of a bounding
     * box for the same reason ADR-0028 used the eye midpoint and for more of it: a
     * head turned three-quarters has most of its mesh on the near side, so the
     * mean follows the turn instead of sitting where the box says the middle is.
     */
    private fun splitOf(
        points: List<com.google.mlkit.vision.facemesh.FaceMeshPoint>,
        width: Int,
    ): Double? {
        if (width <= 0 || points.isEmpty()) return null
        var sum = 0.0
        for (p in points) sum += p.position.x
        return (sum / points.size / width).coerceIn(0.0, 1.0)
    }

    fun release() {
        meshDetector.close()
        segmenter.close()
        _look.value = null
    }

    private companion object {
        const val TAG = "LookSource"
    }
}
