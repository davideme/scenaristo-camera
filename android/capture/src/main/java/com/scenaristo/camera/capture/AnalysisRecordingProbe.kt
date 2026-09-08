package com.scenaristo.camera.capture

import android.util.Range
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture

/**
 * How tall a recording this device will accept **with an analysis stream beside
 * it** (PRD 6.10, ADR-0029).
 *
 * A studio look needs a frame to run a model on, and `ImageAnalysis` is where
 * ML Kit's documented shape reads from. Whether that stream can sit beside the
 * recording is a device question on CameraX 1.6: `Preview` and `VideoCapture` are
 * both `PRIV`/opaque while `ImageAnalysis` is `YUV`, and #20 measured that the
 * reference Pixel 10 accepts no UHD combination containing one. Another handset
 * may accept it and give up nothing.
 *
 * **So it is asked, not assumed.** Writing 1080p into the product would turn one
 * phone's limit into everyone's, which is ADR-0017's warning read backwards: a
 * measurement on the Pixel 10 is evidence about a Pixel 10.
 *
 * Cheap to run — `isSessionConfigSupported` is a query against the camera's
 * stream configuration map, not a bind — so it runs once per camera alongside the
 * capability probe rather than being cached across launches, where a stale answer
 * would outlive the CameraX version that produced it.
 */
object AnalysisRecordingProbe {

    /**
     * The tallest of [CANDIDATES] this camera accepts, or null if none does.
     *
     * Tallest rather than first-supported: the list is ordered best-first, but
     * saying so in the name is what stops someone reordering it for readability
     * and silently changing what the product records at.
     */
    fun tallestSupportedHeight(cameraInfo: CameraInfo): Int? =
        CANDIDATES.firstOrNull { cameraInfo.isSessionConfigSupported(configFor(it)) }?.height

    /**
     * Best first. Only qualities the product would actually record at: PRD 6.1
     * fixes 30 fps and 6.10 allows the 1080p fallback, and anything below that is
     * not a recording this app makes, so a device that accepts only SD reports
     * null and the look is simply unavailable there.
     */
    private val CANDIDATES = listOf(
        Candidate(Quality.UHD, 2160),
        Candidate(Quality.FHD, 1080),
    )

    private data class Candidate(val quality: Quality, val height: Int)

    /**
     * The same three use cases the product binds, plus the frame-rate pin.
     *
     * The analysis stream carries no resolution of its own: CameraX picks one
     * that fits beside the others, which is the point — asking for a specific
     * analysis size would be asking a narrower question than "can a model run
     * here at all", and would report false on a device that would have offered a
     * different one.
     */
    private fun configFor(candidate: Candidate): SessionConfig =
        SessionConfig.Builder(
            listOf(
                Preview.Builder().build(),
                VideoCapture.withOutput(
                    Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(candidate.quality))
                        .build(),
                ),
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build(),
            ),
        )
            .apply { setFrameRateRange(Range(FPS, FPS)) }
            .build()

    /** PRD 6.1: 30 fps, constant. A look does not get to change that. */
    private const val FPS = 30
}
