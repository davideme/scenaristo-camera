package com.scenaristo.camera.capture

import android.util.Range
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import android.util.Size
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.video.GroupableFeatures
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import java.util.concurrent.Executors

/**
 * Which UHD30 session shapes a device will actually accept.
 *
 * The first Pixel 10 run of #20 failed to bind with "Feature group is not
 * supported", which is true but useless: it does not say whether the obstacle is
 * UHD, the pinned frame rate, or the third stream. This probe asks
 * `CameraInfo.isSessionConfigSupported` about one variation at a time so the
 * answer is attributable, and it needs no binding and no permission-gated
 * recording to run.
 *
 * The candidates are ordered most-wanted first: [candidates] `.first { supported }`
 * is the best session this device can give us, and how far down the list that
 * lands is itself the Phase 0 result.
 */
object SessionSupportProbe {

    data class Candidate(
        val label: String,
        /** What this candidate changes relative to the one above it. */
        val varies: String,
        val config: SessionConfig,
    )

    data class Result(val candidate: Candidate, val supported: Boolean)

    /**
     * What CameraX actually assigned after a bind. `isSessionConfigSupported` is
     * CameraX's own model and appears to answer before a `ResolutionSelector` is
     * resolved, so for anything resolution-dependent the only honest test is to
     * bind and read the streams back.
     */
    fun resolutions(config: SessionConfig): String = resolutionsOf(config.useCases)

    private fun preview() = Preview.Builder().build()

    private fun video() = VideoCapture.withOutput(Recorder.Builder().build())

    /**
     * The pre-feature-group way to ask for a resolution: a quality on the
     * Recorder, with no `GroupableFeature` involved. Whether this reaches UHD
     * where the feature group refuses is the question that decides how much of
     * ADR-0005 and ADR-0008 survives.
     */
    private fun videoAt(quality: Quality) = VideoCapture.withOutput(
        Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build(),
    )

    private fun analysis() = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    /**
     * Analysis at a bounded size. The default asks for whatever CameraX picks,
     * which on a 4K session can be large enough to blow the stream combination on
     * its own -- and neither the metering loop (ADR-0005) nor the 960x540 MJPEG
     * preview (ADR-0008) wants a big frame anyway.
     */
    private fun analysisAt(size: Size) = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                )
                .build(),
        )
        .build()

    /**
     * Enough of a [SurfaceProcessor] to construct a [CameraEffect] and ask
     * whether the session would be accepted. It renders nothing: a support query
     * never runs it, and a bind only needs it to exist. The real one -- the GL
     * pass that would feed the viewfinder, the meter and the MJPEG encoder from
     * one preview stream -- is the work this probe exists to justify or kill.
     */
    private object StubProcessor : SurfaceProcessor {
        override fun onInputSurface(request: SurfaceRequest) = Unit
        override fun onOutputSurface(output: SurfaceOutput) = Unit
    }

    private class TapEffect(targets: Int) : CameraEffect(
        targets,
        Executors.newSingleThreadExecutor(),
        StubProcessor,
        {},
    )

    private fun config(
        useCases: List<androidx.camera.core.UseCase>,
        feature: GroupableFeature?,
        fps: Int?,
        effect: CameraEffect? = null,
    ): SessionConfig = SessionConfig.Builder(useCases)
        .apply {
            feature?.let { setRequiredFeatureGroup(it) }
            fps?.let { setFrameRateRange(Range(it, it)) }
            effect?.let { addEffect(it) }
        }
        .build()

    /**
     * Ordered by how much of the product each one preserves. Dropping
     * `ImageAnalysis` costs the metering loop (ADR-0005) and the MJPEG preview
     * (ADR-0008); dropping the frame-rate pin costs PRD 6.1's "30.00 fps
     * constant"; dropping UHD costs PRD 6.1 outright. None of those are free, so
     * the first supported row is a decision for Davide, not a fallback to take
     * silently.
     */
    fun candidates(): List<Candidate> = listOf(
        Candidate(
            "UHD + 30fps pinned + preview/video/analysis",
            "what the product needs",
            config(listOf(preview(), video(), analysis()), GroupableFeatures.UHD_RECORDING, 30),
        ),
        Candidate(
            "UHD + preview/video/analysis",
            "drops the [30,30] pin",
            config(listOf(preview(), video(), analysis()), GroupableFeatures.UHD_RECORDING, null),
        ),
        Candidate(
            "UHD + 30fps pinned + preview/video",
            "drops ImageAnalysis, keeps the pin",
            config(listOf(preview(), video()), GroupableFeatures.UHD_RECORDING, 30),
        ),
        Candidate(
            "UHD + preview/video",
            "drops ImageAnalysis and the pin",
            config(listOf(preview(), video()), GroupableFeatures.UHD_RECORDING, null),
        ),
        Candidate(
            "UHD + video only",
            "drops Preview too",
            config(listOf(video()), GroupableFeatures.UHD_RECORDING, null),
        ),
        Candidate(
            "UHD + 30fps pinned + video/analysis@960x540, no preview",
            "on-device viewfinder traded away for the analysis stream",
            config(
                listOf(video(), analysisAt(Size(960, 540))),
                GroupableFeatures.UHD_RECORDING,
                30,
            ),
        ),
        Candidate(
            "UHD + video/analysis@960x540, no preview",
            "same, without the fps pin",
            config(listOf(video(), analysisAt(Size(960, 540))), GroupableFeatures.UHD_RECORDING, null),
        ),
        Candidate(
            "UHD + video/analysis(default), no preview",
            "same, analysis unbounded",
            config(listOf(video(), analysis()), GroupableFeatures.UHD_RECORDING, null),
        ),
        Candidate(
            "FHD + 30fps pinned + preview/video/analysis",
            "same streams, lower recording quality",
            config(listOf(preview(), video(), analysis()), GroupableFeatures.FHD_RECORDING, 30),
        ),
        Candidate(
            "no feature group + 30fps pinned + preview/video/analysis",
            "same streams, no quality demand at all",
            config(listOf(preview(), video(), analysis()), null, 30),
        ),
        Candidate(
            "no feature group + preview/video/analysis",
            "the loosest session that still binds all three",
            config(listOf(preview(), video(), analysis()), null, null),
        ),
        Candidate(
            "QualitySelector UHD + 30fps pinned + preview/video/analysis",
            "UHD asked for the old way, not as a feature group",
            config(listOf(preview(), videoAt(Quality.UHD), analysis()), null, 30),
        ),
        Candidate(
            "QualitySelector UHD + preview/video/analysis",
            "same, without the pin",
            config(listOf(preview(), videoAt(Quality.UHD), analysis()), null, null),
        ),
        Candidate(
            "UHD + 30fps pinned + preview/video/analysis@960x540",
            "analysis bounded to the MJPEG preview size (ADR-0008)",
            config(
                listOf(preview(), video(), analysisAt(Size(960, 540))),
                GroupableFeatures.UHD_RECORDING,
                30,
            ),
        ),
        Candidate(
            "UHD + 30fps pinned + preview/video + effect(PREVIEW)",
            "no analysis stream; frames tapped off Preview instead",
            config(
                listOf(preview(), video()),
                GroupableFeatures.UHD_RECORDING,
                30,
                TapEffect(CameraEffect.PREVIEW),
            ),
        ),
        Candidate(
            "UHD + 30fps pinned + preview/video + effect(PREVIEW|VIDEO_CAPTURE)",
            "same tap, also targeting the recording stream",
            config(
                listOf(preview(), video()),
                GroupableFeatures.UHD_RECORDING,
                30,
                TapEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE),
            ),
        ),
        Candidate(
            "UHD + 30fps pinned + preview/video/analysis@640x480",
            "analysis bounded smaller still",
            config(
                listOf(preview(), video(), analysisAt(Size(640, 480))),
                GroupableFeatures.UHD_RECORDING,
                30,
            ),
        ),
    )

    /**
     * The pre-SessionConfig way to bind: hand `bindToLifecycle` the use cases and
     * let CameraX resolve the combination, including inserting its own internal
     * stream sharing when the native combination is unsupported.
     *
     * This is a different code path from a `SessionConfig` with a *required*
     * feature group, which fails loudly by design rather than falling back. The
     * resolutions are read back after binding because "it bound" is not the
     * question -- whether the recorder actually got UHD is.
     */
    /**
     * A *preferred* feature group, which is the opposite instruction from the
     * required one used everywhere else: CameraX drops the feature rather than
     * refusing the session. It always binds, so the question is never whether it
     * works but what it silently settles for -- which is why this returns the
     * selected features alongside the resolutions.
     */
    fun preferredCandidates(): List<Triple<String, SessionConfig, List<androidx.camera.core.UseCase>>> {
        fun build(vararg features: GroupableFeature): Pair<SessionConfig, List<androidx.camera.core.UseCase>> {
            val useCases = listOf(preview(), video(), analysis())
            return SessionConfig.Builder(useCases).setPreferredFeatureGroup(*features).build() to useCases
        }
        val uhd = build(GroupableFeatures.UHD_RECORDING)
        val uhdThenFhd = build(GroupableFeatures.UHD_RECORDING, GroupableFeatures.FHD_RECORDING)
        return listOf(
            Triple("preferred(UHD) + preview/video/analysis", uhd.first, uhd.second),
            Triple("preferred(UHD, FHD) + preview/video/analysis", uhdThenFhd.first, uhdThenFhd.second),
        )
    }

    fun varargCandidates(): List<Triple<String, List<androidx.camera.core.UseCase>, String>> = listOf(
        Triple(
            "vararg: preview + video(UHD) + analysis@960x540",
            listOf(preview(), videoAt(Quality.UHD), analysisAt(Size(960, 540))),
            "quality on the Recorder, analysis bounded small",
        ),
        Triple(
            "vararg: preview + video(UHD) + analysis(default)",
            listOf(preview(), videoAt(Quality.UHD), analysis()),
            "same, analysis unbounded",
        ),
        Triple(
            "vararg: video(UHD) + analysis@960x540, no preview",
            listOf(videoAt(Quality.UHD), analysisAt(Size(960, 540))),
            "two surfaces only; phone viewfinder would draw the analysis frames",
        ),
        Triple(
            "vararg: video(UHD) + analysis@640x480, no preview",
            listOf(videoAt(Quality.UHD), analysisAt(Size(640, 480))),
            "same, smaller",
        ),
        Triple(
            "vararg: preview + video(default) + analysis@960x540",
            listOf(preview(), video(), analysisAt(Size(960, 540))),
            "no quality demand; what does the Recorder pick?",
        ),
    )

    fun resolutionsOf(useCases: List<androidx.camera.core.UseCase>): String {
        val video = useCases.filterIsInstance<VideoCapture<*>>()
            .firstNotNullOfOrNull { it.resolutionInfo?.resolution }
        val analysis = useCases.filterIsInstance<ImageAnalysis>()
            .firstNotNullOfOrNull { it.resolutionInfo?.resolution }
        val preview = useCases.filterIsInstance<Preview>()
            .firstNotNullOfOrNull { it.resolutionInfo?.resolution }
        return "video=${video ?: "-"} preview=${preview ?: "-"} analysis=${analysis ?: "-"}"
    }

    fun run(cameraInfo: CameraInfo): List<Result> =
        candidates().map { Result(it, cameraInfo.isSessionConfigSupported(it.config)) }

    /** For pasting into #20: which shapes this device accepts, and which it refuses. */
    fun markdown(cameraId: String, results: List<Result>): String = buildString {
        appendLine("Session shapes accepted on camera id `$cameraId`:")
        appendLine()
        appendLine("| Session | Varies | Supported |")
        appendLine("|---|---|---|")
        for (r in results) {
            appendLine("| ${r.candidate.label} | ${r.candidate.varies} | ${if (r.supported) "yes" else "**no**"} |")
        }
    }
}
