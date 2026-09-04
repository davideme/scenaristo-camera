package com.scenaristo.camera.capture

/**
 * Does a capture result echo what was requested? (Phase 0, issue #20, ADR-0002
 * action item 2.)
 *
 * CameraX applies the manual keys on a best-effort basis, so "the key was set"
 * and "the sensor did it" are different claims. This file holds the second one,
 * as pure Kotlin: the device-facing half reads the session capture callback and
 * feeds [KeyEcho] values in; everything here runs on the host, so the verdict
 * rules are testable without a phone and are reused unchanged by the AVFoundation
 * port in Phase 4, which asks the identical question of `exposureDuration`.
 *
 * The tolerances below are this spike's *input assumption*, not its result. The
 * measurement it exists to produce is the deviation actually observed on the
 * Pixel 10 (ADR-0017), which is why [KeyEcho.deviation] is carried separately
 * from the verdict and printed by [LensEchoReport.markdown]. Record those
 * numbers in ADR-0002 before ticking action item 2; if a real device quantises
 * wider than a tolerance here, the honest response is to record the number and
 * decide, not to widen the constant so the run goes green.
 */
enum class ManualKey(
    /**
     * How far the reported value may drift from the request and still count as
     * device quantisation rather than the device ignoring us. Null means the key
     * is a mode: an enum the device either selected or did not.
     */
    val tolerance: Double?,
) {
    /**
     * Nanoseconds. 1 % of 1/50 s is 200 us, which is 2 % of a 50 Hz half-cycle
     * (10 ms) -- residual ripple far below what a rolling band needs to be
     * visible, so a device quantising inside this is still flicker-free (PRD 6.2).
     */
    SENSOR_EXPOSURE_TIME(0.01),

    /** ISO. Sensors round to their own sensitivity steps; the metering loop reacts to the reported value either way (ADR-0005). */
    SENSOR_SENSITIVITY(0.01),

    /**
     * Nanoseconds. Tighter, because PRD 6.1 asks for metadata reading 30.00 fps
     * *constant*: 1 % here is 29.7 fps, which the acceptance criterion would
     * fail. 0.1 % is 33 us at 30 fps.
     */
    SENSOR_FRAME_DURATION(0.001),

    /** Must read OFF, or the app is not the one setting exposure (PRD 6.3). */
    CONTROL_AE_MODE(null),

    /** Must read OFF, or white balance drifts mid-take (PRD 6.4). */
    CONTROL_AWB_MODE(null),

    /** Must read OFF: ADR-0002 extends PRD 6.1's "stabilisation off" to OIS, which drifts on a tripod. */
    LENS_OPTICAL_STABILIZATION_MODE(null),
    ;

    /** Mode keys are enums, so only exact equality means anything. */
    val isMode: Boolean get() = tolerance == null
}

/** What one key did, ordered worst-last so `maxOf` over a lens is its verdict. */
enum class EchoVerdict {
    /** The reported value is the requested value. */
    EXACT,

    /** Off by less than [ManualKey.tolerance]: the sensor's own step, not a refusal. */
    QUANTISED,

    /** The device reported a different value. The key is not honoured. */
    MISMATCH,

    /**
     * The key was missing from the capture result. Distinct from [MISMATCH] on
     * purpose: it usually means the request never reached the camera, which is a
     * different bug from the camera overriding it.
     */
    ABSENT,
}

/**
 * One key, as requested and as reported back. Values are `Long` for every key so
 * one type covers nanosecond durations, ISO, and mode constants; the caller
 * widens the `Int` ones.
 */
data class KeyEcho(
    val key: ManualKey,
    val requested: Long,
    /** Null when the capture result did not carry the key at all. */
    val observed: Long?,
) {
    /** Signed, as a fraction of the request. Null when [observed] is null. */
    val deviation: Double? =
        when {
            observed == null -> null
            requested == 0L -> if (observed == 0L) 0.0 else Double.POSITIVE_INFINITY
            else -> (observed - requested).toDouble() / requested.toDouble()
        }

    val verdict: EchoVerdict =
        when {
            observed == null -> EchoVerdict.ABSENT
            observed == requested -> EchoVerdict.EXACT
            key.isMode -> EchoVerdict.MISMATCH
            kotlin.math.abs(deviation!!) <= key.tolerance!! -> EchoVerdict.QUANTISED
            else -> EchoVerdict.MISMATCH
        }
}

/**
 * Every key on one lens, from one recording run. A lens is the unit because
 * ADR-0011 gates capability per lens, not per device: the main camera honouring
 * the keys says nothing about the ultrawide.
 */
data class LensEchoReport(
    val cameraId: String,
    /** How the app names the lens to a user, e.g. "Rear main (wide)". */
    val lensLabel: String,
    val echoes: List<KeyEcho>,
    /**
     * How many capture results the [echoes] summarise. #20 asks for keys honoured
     * *throughout* a 10-minute take, so one sampled frame and eighteen thousand
     * are very different claims and the write-up has to say which it is.
     */
    val framesObserved: Int? = null,
) {
    /** Keys ADR-0002 action item 2 expects and this run did not report on. */
    val missingKeys: List<ManualKey> = ManualKey.entries - echoes.map { it.key }.toSet()

    val failures: List<KeyEcho> = echoes.filter {
        it.verdict == EchoVerdict.MISMATCH || it.verdict == EchoVerdict.ABSENT
    }

    /**
     * True only when every key ADR-0002 lists was reported and honoured. A run
     * that simply did not look at a key is not a pass -- that is what
     * [missingKeys] is for.
     */
    val honoured: Boolean = failures.isEmpty() && missingKeys.isEmpty()
}

/**
 * The report as a Markdown table, for pasting into issue #20 and ADR-0002.
 *
 * A spike is finished when its number is written into the document that asked
 * for it (docs/ROADMAP.md), so the harness produces the paste rather than
 * leaving it to be transcribed by hand from a log.
 */
fun LensEchoReport.markdown(): String = buildString {
    append("**").append(lensLabel).append("** (camera id `").append(cameraId).append("`) — ")
    append(if (honoured) "all keys honoured" else "**not honoured**")
    appendLine(framesObserved?.let { " over $it capture results" } ?: "")
    appendLine()
    appendLine("| Key | Requested | Reported | Deviation | Verdict |")
    appendLine("|---|---|---|---|---|")
    for (echo in echoes) {
        append("| `").append(echo.key.name).append("` | ").append(echo.requested).append(" | ")
        append(echo.observed?.toString() ?: "—").append(" | ")
        append(echo.deviation?.let { formatDeviation(it) } ?: "—").append(" | ")
        append(echo.verdict.name).appendLine(" |")
    }
    for (key in missingKeys) {
        appendLine("| `${key.name}` | — | — | — | NOT MEASURED |")
    }
}

/** Parts per million keeps a 0.001 tolerance readable without scientific notation. */
private fun formatDeviation(fraction: Double): String {
    if (fraction == 0.0) return "0"
    val ppm = fraction * 1_000_000
    val rounded = (if (ppm < 0) -1 else 1) * (kotlin.math.abs(ppm) + 0.5).toLong()
    return "${if (rounded > 0) "+" else ""}$rounded ppm"
}

/**
 * Accumulates capture results into one report, worst frame wins.
 *
 * #20 asks whether the keys are honoured *throughout* a 10-minute take, and the
 * cheap version of this measurement — read the last frame — would pass a device
 * that locks the shutter for a second and then quietly re-enables AE. Keeping
 * the worst verdict per key makes the report monotonic: once a key has
 * misbehaved, no later frame can clear it.
 *
 * Pure, so this rule is tested on the host; [ManualSession] only feeds it.
 * Not thread-safe: the caller owns the lock, because the capture callback
 * arrives on a camera thread.
 */
class EchoAccumulator {
    private val worst = mutableMapOf<ManualKey, KeyEcho>()

    var frames: Int = 0
        private set

    fun record(echoes: List<KeyEcho>) {
        frames++
        for (echo in echoes) {
            val seen = worst[echo.key]
            // EchoVerdict is declared best-first, so "greater" means "worse".
            if (seen == null || echo.verdict > seen.verdict) worst[echo.key] = echo
        }
    }

    /** Keys are reported in declaration order so two runs are diffable. */
    fun report(cameraId: String, lensLabel: String): LensEchoReport = LensEchoReport(
        cameraId = cameraId,
        lensLabel = lensLabel,
        echoes = ManualKey.entries.mapNotNull { worst[it] },
        framesObserved = frames,
    )
}
