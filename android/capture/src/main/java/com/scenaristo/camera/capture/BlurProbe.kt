package com.scenaristo.camera.capture

import android.content.Context
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.GroupableFeatures
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.lifecycle.LifecycleOwner
import com.scenaristo.camera.domain.blur.BlurCapability
import java.io.File
import kotlinx.coroutines.delay

/**
 * One candidate session for the background-blur measurement (ADR-0031).
 *
 * The same shape as [PinnedLensProbe], and narrowed the same way: `VideoCapture`
 * alone by default, because a `Preview` with no surface provider produces no
 * frames and the sweep has to run with nothing on screen. One candidate binds a
 * `Preview` as well, to check whether the extra stream changes what the device
 * offers -- **that candidate proves availability, not that anything was drawn.**
 *
 * What this measures that characteristics cannot: whether asking for blur costs
 * the six manual keys, whether it costs the frame rate, what the session
 * actually binds at, and whether the mode survives the exposure loop. ADR-0018
 * records that every capability query on the reference device has proved
 * optimistic, which is why nothing here trusts one.
 */
class BlurProbe(
    request: ManualControls.Request,
    private val features: List<GroupableFeature>,
    private val withPreview: Boolean = false,
) {

    /**
     * The request currently in force.
     *
     * Mutable because the phases move it: a candidate can be bound asking for
     * blur and then have a runtime request made that does not, which is the
     * measurement [BlurPhase] exists for. Read on a camera thread, so volatile.
     */
    @Volatile
    private var active: ManualControls.Request = request

    @Volatile
    private var phase: BlurPhase = BlurPhase.BIND_TIME

    /**
     * Whether capture results are being counted yet.
     *
     * False until [startCounting], and the reason is a mistake this probe made
     * on its first run: CameraX's runtime request state belongs to the camera
     * id, not to the session, so a freshly bound candidate inherits whatever the
     * app's exposure loop last asked for. Six of nine candidates reported the
     * manual keys lost on that first run, and every one of them was measuring
     * the previous session's ISO rather than anything to do with blur.
     *
     * So a candidate now asserts its own request through the runtime path,
     * settles, and only then starts counting.
     */
    @Volatile
    private var counting: Boolean = false

    @Volatile
    private var firstFrameAtMs: Long = 0

    @Volatile
    private var lastFrameAtMs: Long = 0

    val recorder: Recorder = Recorder.Builder().build()

    val videoCapture: VideoCapture<Recorder> = VideoCapture.Builder(recorder)
        .also { ManualControls.applyTo(it, request, ::record) }
        .build()

    private val preview: Preview? = if (withPreview) Preview.Builder().build() else null

    val sessionConfig: SessionConfig =
        SessionConfig.Builder(listOfNotNull(videoCapture, preview))
            .setRequiredFeatureGroup(*features.toTypedArray())
            .setFrameRateRange(Range(30, 30))
            .build()

    private val lock = Any()
    private val echoes = EchoAccumulator()
    private val blur = BlurAccumulator()

    private fun record(result: TotalCaptureResult) {
        if (!counting) return
        val now = System.currentTimeMillis()
        if (firstFrameAtMs == 0L) firstFrameAtMs = now
        lastFrameAtMs = now
        val request = active
        val keyEchoes = ManualControls.echoes(request, result)
        val sceneMode = ManualControls.observedSceneMode(result)
        val controlMode = ManualControls.observedControlMode(result)
        synchronized(lock) {
            echoes.record(keyEchoes)
            blur.record(
                phase = phase,
                requestedSceneMode = request.extendedSceneMode,
                requestedControlMode = request.controlMode,
                observedSceneMode = sceneMode,
                observedControlMode = controlMode,
            )
        }
    }

    /**
     * Moves to a new phase, optionally changing what is being asked for.
     *
     * The runtime request is made through [ManualControls] and nowhere else:
     * this class never builds options of its own, so the thing being measured is
     * the path the product actually uses.
     */
    fun enter(newPhase: BlurPhase, request: ManualControls.Request, cameraControl: androidx.camera.core.CameraControl) {
        active = request
        phase = newPhase
        ManualControls.apply(cameraControl, request)
    }

    /** Begins counting, after the candidate's own request has been asserted and settled. */
    fun startCounting() {
        counting = true
    }

    /**
     * Milliseconds between the first counted frame and the last.
     *
     * Not wall-clock elapsed, which is what this measured at first and which
     * included binding the session — enough to report 27.5 fps for a stream that
     * was holding 30. PRD 6.1 asks about the footage, so the span is the
     * footage's own.
     */
    val frameSpanMs: Long
        get() = if (firstFrameAtMs == 0L) 0 else lastFrameAtMs - firstFrameAtMs

    fun echoReport(cameraId: String, label: String): LensEchoReport =
        synchronized(lock) { echoes.report(cameraId, label) }

    fun phaseReports(): List<BlurPhaseReport> = synchronized(lock) { blur.report() }

    /** What the stream actually bound at, read back rather than asked for. */
    val boundResolution: String?
        get() = videoCapture.resolutionInfo?.resolution?.let { "${it.width}x${it.height}" }

    /**
     * Records to [file] for [seconds], without audio.
     *
     * The reason this exists at all: an echoed mode proves the camera *selected*
     * blur, not that the footage is blurred. Nobody can tell those apart from a
     * table, and the difference is the entire feature -- so every candidate that
     * binds leaves a file to be looked at.
     *
     * Audio is off deliberately. It would add a runtime permission to a
     * measurement that has nothing to do with sound, and a probe that cannot run
     * because a permission was not granted is a probe that does not get run.
     */
    suspend fun recordFor(context: Context, file: File, seconds: Int) {
        val recording: Recording = recorder
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
            .start(context.mainExecutor) { }
        try {
            delay(seconds * 1000L)
        } finally {
            recording.stop()
        }
    }
}

/**
 * Runs the candidate sweep and returns the Markdown for ADR-0031.
 *
 * Sequential, rebinding between candidates, because a feature group and the
 * keys on a use-case builder are both fixed at bind time. The caller's own
 * session is unbound for the duration; restoring it is the caller's job, since
 * only the caller knows what it had.
 */
object BlurProbeRunner {

    /** Per candidate. Long enough to catch a device that engages blur and then lapses. */
    private const val SECONDS_PER_PHASE = 3

    /**
     * After binding and asserting the request, before counting anything.
     *
     * Two jobs. It lets the runtime request the candidate just made actually
     * reach the sensor, so the six keys are measured against what this candidate
     * asked for rather than against what the app's exposure loop left behind.
     * And it gives the first candidate a session that is genuinely streaming:
     * on the first run C0 bound, recorded nothing and reported zero frames,
     * which is a control that controls for nothing.
     */
    private const val SETTLE_MS = 1_500L

    /** Long enough to be watchable, short enough that nine of them is not a chore. */
    private const val RECORD_SECONDS = 5

    suspend fun run(
        context: Context,
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        base: ManualControls.Request,
        cameraInfo: androidx.camera.core.CameraInfo,
        cameraId: String,
        model: String,
    ): String {
        val advertised = ManualControls.extendedSceneModes(cameraInfo)
        val capability: BlurCapability = ManualControls.blurCapability(cameraInfo)

        val uhd = listOf(GroupableFeatures.UHD_RECORDING)
        val fhd = listOf(GroupableFeatures.FHD_RECORDING)
        val unconstrained = emptyList<GroupableFeature>()

        val blurOnly = base.copy(extendedSceneMode = ManualControls.BOKEH_CONTINUOUS)
        val blurAndControl = base.copy(
            extendedSceneMode = ManualControls.BOKEH_CONTINUOUS,
            controlMode = ManualControls.CONTROL_MODE_SCENE,
        )

        val candidates = listOf(
            // The control. Proves the rig and gives a six-key baseline that every
            // other row is diffed against -- without it a lost key cannot be told
            // apart from a probe that was never working.
            Candidate("C0 UHD30, no blur", "3840x2160", uhd, base, false),
            Candidate("C1 UHD30 + blur", "3840x2160", uhd, blurOnly, false),
            Candidate("C2 UHD30 + blur + scene control", "3840x2160", uhd, blurAndControl, false),
            Candidate("C3 FHD30 + blur", "1920x1080", fhd, blurOnly, false),
            Candidate("C4 FHD30 + blur + scene control", "1920x1080", fhd, blurAndControl, false),
            // Nothing required: what does the device hand back when blur is asked
            // for and the resolution is left to it.
            Candidate("C5 unconstrained + blur", "device choice", unconstrained, blurOnly, false),
            // The two that measure the exposure loop rather than the camera.
            Candidate("C6 blur at bind, dropped by a runtime request", "1920x1080", fhd, blurOnly, false, wipe = true),
            Candidate("C7 blur carried on every runtime request", "1920x1080", fhd, blurOnly, false, carry = true),
            Candidate("C8 preview + recording + blur", "1920x1080", fhd, blurOnly, true),
        )

        val results = candidates.map { run(context, provider, owner, it, cameraId) }
        provider.unbindAll()
        return results.markdown(cameraId, model, advertised, capability)
    }

    private data class Candidate(
        val label: String,
        val requestedSize: String,
        val features: List<GroupableFeature>,
        val request: ManualControls.Request,
        val withPreview: Boolean,
        val wipe: Boolean = false,
        val carry: Boolean = false,
    )

    private suspend fun run(
        context: Context,
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        candidate: Candidate,
        cameraId: String,
    ): BlurCandidateResult {
        val probe = BlurProbe(candidate.request, candidate.features, candidate.withPreview)
        val file = File(context.getExternalFilesDir(null), fileNameFor(candidate.label))
        val startedAt = System.currentTimeMillis()

        val failure = runCatching {
            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                owner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                probe.sessionConfig,
            )
            // Assert this candidate's own request through the runtime path
            // before measuring anything. Without it a candidate inherits the
            // options the app's exposure loop last set for this camera id, and
            // reports them as its own result.
            probe.enter(BlurPhase.BIND_TIME, candidate.request, camera.cameraControl)
            delay(SETTLE_MS)
            probe.startCounting()
            probe.recordFor(context, file, RECORD_SECONDS)

            // A runtime request that omits the key, then one that carries it.
            // Both phases are entered on every candidate that asks for either, so
            // C6 and C7 differ only in which request they end on -- which is the
            // difference a shipped feature would live or die by.
            if (candidate.wipe || candidate.carry) {
                probe.enter(
                    BlurPhase.AFTER_PUSH_WITHOUT_KEY,
                    candidate.request.copy(extendedSceneMode = null, controlMode = null),
                    camera.cameraControl,
                )
                delay(SECONDS_PER_PHASE * 1000L)
            }
            if (candidate.carry) {
                probe.enter(BlurPhase.AFTER_PUSH_WITH_KEY, candidate.request, camera.cameraControl)
                delay(SECONDS_PER_PHASE * 1000L)
            }
            camera.cameraInfo.zoomState.value?.zoomRatio?.toDouble()
        }

        return BlurCandidateResult(
            label = candidate.label,
            requestedSize = candidate.requestedSize,
            // A refused bind is a row, not an error to swallow: what a device
            // will not do is as much of a measurement as what it will.
            failure = failure.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" },
            boundResolution = probe.boundResolution,
            echoes = probe.echoReport(cameraId, candidate.label),
            phases = probe.phaseReports(),
            elapsedMs = System.currentTimeMillis() - startedAt,
            frameSpanMs = probe.frameSpanMs,
            zoomRatio = failure.getOrNull(),
            takeFile = file.name.takeIf { file.exists() && file.length() > 0 },
        )
    }

    private fun fileNameFor(label: String): String =
        "blur-probe-" + label.substringBefore(' ').lowercase() + ".mp4"
}
