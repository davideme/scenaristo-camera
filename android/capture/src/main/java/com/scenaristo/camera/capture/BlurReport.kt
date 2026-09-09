package com.scenaristo.camera.capture

import com.scenaristo.camera.domain.blur.BlurCapability
import com.scenaristo.camera.domain.blur.blurLine

/**
 * What the background-blur measurement saw (ADR-0031), as pure Kotlin.
 *
 * The same split as [ManualKeyEcho]: the device-facing half ([BlurProbe]) binds
 * cameras and reads capture results, and everything here -- bucketing, verdicts,
 * arithmetic, the Markdown -- runs on the host and is tested without a phone.
 *
 * This file interprets no platform constants. The numbers arrive already
 * labelled from [ManualControls], which is the only file that may know what
 * they mean, so a mode value here is a number and a name and nothing more.
 */

/** "No scene mode". Zero in every camera stack the app targets, and the value a
 * device reports once a request stops asking for blur -- which is what
 * [BlurPhase.AFTER_PUSH_WITHOUT_KEY] exists to catch. */
const val SCENE_MODE_DISABLED: Int = 0

/**
 * One streaming scene mode a camera advertises, flattened out of the platform's
 * capability array into plain numbers.
 *
 * Flattened rather than carried whole because the platform's own type is a
 * device class: keeping it would put every rule that reads it behind a phone.
 */
data class ExtendedSceneMode(
    val mode: Int,
    /** How [ManualControls] names this mode, e.g. "BOKEH_CONTINUOUS". */
    val label: String,
    /** The largest stream the device says it will apply this mode to. */
    val maxWidthPx: Int,
    val maxHeightPx: Int,
    /**
     * The zoom band the mode is available in.
     *
     * Advertised per mode and easy to miss: #77 lets a user sit anywhere in the
     * zoom range, so a mode offered only from 1.0x to 2.0x is a mode that
     * disappears when they frame tighter.
     */
    val zoomMin: Double,
    val zoomMax: Double,
) {
    fun line(): String =
        "`$label` ($mode) up to ${maxWidthPx}x$maxHeightPx, zoom ${zoomMin}x-${zoomMax}x"
}

/**
 * Everything one camera says about streaming scene modes, before anything has
 * been bound.
 *
 * [offersSceneModeControl] is carried separately from the modes on purpose: a
 * device can advertise the mode without offering the control value that AOSP
 * says selects it, and that fact alone decides whether the probe's C1 or C2
 * candidate is the one that can work.
 */
data class ExtendedSceneModes(
    val modes: List<ExtendedSceneMode>,
    /** Whether the control value AOSP names for scene modes is even offered. */
    val offersSceneModeControl: Boolean,
) {
    val advertised: Boolean get() = modes.any { it.mode != SCENE_MODE_DISABLED }

    fun modeFor(mode: Int): ExtendedSceneMode? = modes.firstOrNull { it.mode == mode }

    fun markdown(): String = buildString {
        appendLine("| Advertised mode | Max streaming size | Zoom band |")
        appendLine("|---|---|---|")
        if (modes.isEmpty()) {
            appendLine("| _none_ | — | — |")
        } else {
            for (m in modes) {
                appendLine("| `${m.label}` (${m.mode}) | ${m.maxWidthPx}x${m.maxHeightPx} | ${m.zoomMin}x-${m.zoomMax}x |")
            }
        }
        appendLine()
        appendLine(
            "Scene-mode control value offered: " +
                if (offersSceneModeControl) "**yes**" else "**no**",
        )
    }
}

/**
 * When in a candidate's life a capture result was seen.
 *
 * The three phases exist to measure one thing the whole feature rests on, and
 * the answer was not what this file first assumed. The runtime request path in
 * [ManualControls] replaces the whole set of options previously set *through
 * it*, and the exposure loop makes that call six times a second -- so the
 * expectation was that a key set once at bind time would not survive.
 *
 * Measured on the reference Pixel 10 on 2026-09-09: it does. CameraX merges the
 * runtime set over the ones the extender set when the use case was built, so a
 * mode asked for at bind and then left out of a runtime request stayed on for
 * every following capture result. That is [BlurPhaseReport.persisted], and it
 * is why that is a verdict of its own rather than a failure.
 *
 * (Naming the platform API would fail the ADR-0002 invariant check, which greps
 * these files with their comments included.)
 */
enum class BlurPhase {
    /** Bound with the key, before any runtime request has been made. */
    BIND_TIME,

    /** After a runtime request that does **not** carry the key. */
    AFTER_PUSH_WITHOUT_KEY,

    /** After a runtime request that carries the key, which is what a shipped feature would do. */
    AFTER_PUSH_WITH_KEY,
}

/** What the camera did with the blur keys in one phase. */
data class BlurPhaseReport(
    val phase: BlurPhase,
    val requestedSceneMode: Int?,
    val requestedControlMode: Int?,
    val frames: Int,
    /** Observed scene mode to how many results carried it. Null key means the result had none. */
    val observedSceneModes: Map<Int?, Int>,
    val observedControlModes: Map<Int?, Int>,
) {
    /** The mode the camera settled on, or null when it reported none at all. */
    val settledSceneMode: Int? = observedSceneModes.maxByOrNull { it.value }?.key

    /** True when the camera spent this phase in the mode that was asked for. */
    val held: Boolean =
        requestedSceneMode != null && settledSceneMode == requestedSceneMode

    /**
     * The mode stayed on through a phase that stopped asking for it.
     *
     * A real and different answer from "not held", and the first run needed it:
     * a runtime request that omitted the key left the mode running anyway,
     * because CameraX merges runtime options *over* the ones set when the use
     * case was built rather than replacing the whole request. Calling that
     * "not held" reads as a failure, when it is the device keeping its promise.
     */
    val persisted: Boolean =
        requestedSceneMode == null &&
            settledSceneMode != null &&
            settledSceneMode != SCENE_MODE_DISABLED

    /** The mode was asked for and the camera went somewhere else. The only real failure. */
    val dropped: Boolean =
        requestedSceneMode != null && settledSceneMode != requestedSceneMode

    val verdict: String = when {
        held -> "HELD"
        persisted -> "PERSISTED"
        dropped -> "**dropped**"
        else -> "off, as asked"
    }

    fun line(): String = buildString {
        append("| ").append(phase.name)
        append(" | ").append(requestedSceneMode?.toString() ?: "—")
        append(" | ").append(requestedControlMode?.toString() ?: "—")
        append(" | ").append(distribution(observedSceneModes))
        append(" | ").append(distribution(observedControlModes))
        append(" | ").append(frames)
        append(" | ").append(verdict)
        append(" |")
    }

    private fun distribution(counts: Map<Int?, Int>): String =
        if (counts.isEmpty()) {
            "—"
        } else {
            counts.entries
                .sortedByDescending { it.value }
                .joinToString(", ") { "${it.key ?: "absent"}x${it.value}" }
        }
}

/**
 * Accumulates capture results into one phase's report.
 *
 * Distributions rather than a worst-frame verdict, unlike [EchoAccumulator].
 * "Worst wins" is right for a key that must hold a single value, but the
 * question here is what the camera *did over time* -- a mode that holds for two
 * seconds and then lapses, and a mode that never engaged, both fail a worst-frame
 * test while meaning entirely different things.
 *
 * Not thread-safe: the caller owns the lock, because capture results arrive on a
 * camera thread.
 */
class BlurAccumulator {
    private val sceneModes = mutableMapOf<BlurPhase, MutableMap<Int?, Int>>()
    private val controlModes = mutableMapOf<BlurPhase, MutableMap<Int?, Int>>()
    private val frames = mutableMapOf<BlurPhase, Int>()
    private val requestedScene = mutableMapOf<BlurPhase, Int?>()
    private val requestedControl = mutableMapOf<BlurPhase, Int?>()

    fun record(
        phase: BlurPhase,
        requestedSceneMode: Int?,
        requestedControlMode: Int?,
        observedSceneMode: Int?,
        observedControlMode: Int?,
    ) {
        frames[phase] = (frames[phase] ?: 0) + 1
        requestedScene[phase] = requestedSceneMode
        requestedControl[phase] = requestedControlMode
        sceneModes.getOrPut(phase) { mutableMapOf() }
            .let { it[observedSceneMode] = (it[observedSceneMode] ?: 0) + 1 }
        controlModes.getOrPut(phase) { mutableMapOf() }
            .let { it[observedControlMode] = (it[observedControlMode] ?: 0) + 1 }
    }

    /** Phases in declaration order, so two runs are diffable. Phases never seen are omitted. */
    fun report(): List<BlurPhaseReport> = BlurPhase.entries
        .filter { frames.containsKey(it) }
        .map {
            BlurPhaseReport(
                phase = it,
                requestedSceneMode = requestedScene[it],
                requestedControlMode = requestedControl[it],
                frames = frames[it] ?: 0,
                observedSceneModes = sceneModes[it].orEmpty().toMap(),
                observedControlModes = controlModes[it].orEmpty().toMap(),
            )
        }
}

/**
 * One candidate configuration, run to completion or refused.
 *
 * A refused bind is a row and not a thrown error, for the reason
 * [LensSweepRunner] gives about lenses: what a device will not do is as much of
 * a measurement as what it will.
 */
data class BlurCandidateResult(
    val label: String,
    val requestedSize: String,
    val failure: String? = null,
    val boundResolution: String? = null,
    val echoes: LensEchoReport? = null,
    val phases: List<BlurPhaseReport> = emptyList(),
    val elapsedMs: Long = 0,
    /** First counted frame to last. See [measuredFps]. */
    val frameSpanMs: Long = 0,
    val zoomRatio: Double? = null,
    /** The file this candidate recorded, so the blur can be *looked at*. */
    val takeFile: String? = null,
) {
    val bound: Boolean get() = failure == null

    /**
     * Frames per second as counted, not as echoed.
     *
     * Carried beside `SENSOR_FRAME_DURATION`'s own verdict because they can
     * disagree: a mode that halves the capture rate can still echo a perfectly
     * correct frame duration on each of the frames it does deliver, and PRD 6.1
     * asks for 30.00 fps of actual footage.
     */
    val measuredFps: Double?
        get() {
            val frames = echoes?.framesObserved ?: return null
            if (frameSpanMs <= 0 || frames < 2) return null
            // n frames span n-1 intervals. With 140 frames over ~4.6 s the
            // difference is under a percent, but PRD 6.1's criterion is 30.00
            // and an off-by-one that lands at 29.8 would fail it for arithmetic.
            return (frames - 1) * 1000.0 / frameSpanMs
        }
}

/**
 * The whole run as Markdown, for pasting into ADR-0031.
 *
 * A measurement is finished when its numbers are in the document that asked for
 * them (docs/ROADMAP.md), so the instrument produces the paste rather than
 * leaving it to be transcribed out of a log by hand.
 */
fun List<BlurCandidateResult>.markdown(
    cameraId: String,
    model: String,
    advertised: ExtendedSceneModes,
    capability: BlurCapability,
): String = buildString {
    appendLine("# Background blur probe (ADR-0031)")
    appendLine()
    appendLine("Device `$model`, camera id `$cameraId`.")
    appendLine()
    appendLine("## What the camera advertises")
    appendLine()
    append(advertised.markdown())
    appendLine()
    appendLine("Gate on the advertised half alone: ${blurLine(capability)}")
    appendLine()
    appendLine("> Advertised is not measured. The table below is the measurement.")
    appendLine()
    appendLine("## Candidates")
    appendLine()
    appendLine("| Candidate | Asked for | Bound at | Frames | Measured fps | Zoom | Manual keys | File |")
    appendLine("|---|---|---|---|---|---|---|---|")
    for (result in this@markdown) {
        append("| ").append(result.label)
        append(" | ").append(result.requestedSize)
        append(" | ").append(result.failure?.let { "**refused**" } ?: (result.boundResolution ?: "unknown"))
        append(" | ").append(result.echoes?.framesObserved?.toString() ?: "—")
        append(" | ").append(result.measuredFps?.let { formatFps(it) } ?: "—")
        append(" | ").append(result.zoomRatio?.let { "${it}x" } ?: "—")
        append(" | ").append(
            when {
                result.echoes == null -> "—"
                result.echoes.honoured -> "held"
                else -> "**lost**"
            },
        )
        append(" | ").append(result.takeFile ?: "—")
        appendLine(" |")
    }
    appendLine()
    for (result in this@markdown) {
        appendLine("### ${result.label}")
        appendLine()
        if (!result.bound) {
            appendLine("Bind refused: ${result.failure}")
            appendLine()
            continue
        }
        if (result.phases.isNotEmpty()) {
            appendLine("| Phase | Asked scene | Asked control | Observed scene | Observed control | Frames | Verdict |")
            appendLine("|---|---|---|---|---|---|---|")
            for (phase in result.phases) appendLine(phase.line())
            appendLine()
        }
        result.echoes?.let { appendLine(it.markdown()) }
        appendLine()
    }
}

private fun formatFps(fps: Double): String {
    val hundredths = ((fps * 100) + 0.5).toLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
