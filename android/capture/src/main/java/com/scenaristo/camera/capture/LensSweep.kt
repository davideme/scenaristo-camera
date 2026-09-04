package com.scenaristo.camera.capture

/**
 * Which physical lens was actually live, and did the manual keys survive the
 * swap? (Phase 0, #20's remaining boxes.)
 *
 * #20 asks for the manual keys "on the main lens and every manual-capable
 * secondary lens the Pixel 10 exposes". The reference device exposes **two**
 * camera devices, back and front: its ultrawide and telephoto are physical
 * cameras behind the back logical camera and cannot be selected as lenses at
 * all. Lens choice there is a zoom ratio, and the HAL decides which sensor
 * serves it -- possibly mid-session, while recording.
 *
 * That makes the interesting question sharper than "does each lens honour the
 * keys". It is whether the keys survive the device switching sensors underneath
 * a locked session, because a swap that resets exposure is a visible flicker in
 * the middle of a take (PRD 6.2). So the sweep walks the zoom range, buckets
 * every capture result by the physical id the result itself reports, and judges
 * each bucket separately.
 *
 * Pure Kotlin and host-tested, like [ManualKeyEcho]: the device-facing half only
 * sets a zoom ratio and feeds results in.
 */
data class ZoomStop(
    val ratio: Float,
    /** What to call this rung in the report. The lens name comes from the results, not from here. */
    val label: String,
)

object LensSweep {

    /**
     * Rungs across a logical camera's zoom range.
     *
     * Deliberately not a guess at where the physical cameras hand over: the
     * capture results name the active sensor, so the sweep does not need to know
     * the boundaries and does not encode Pixel-specific ratios into the code.
     * It only has to be dense enough to land on every sensor, which doubling is.
     *
     * `min` below 1.0 is its own rung because that is where an ultrawide lives
     * when a device has one.
     */
    fun stops(minRatio: Float, maxRatio: Float): List<ZoomStop> {
        val ratios = sortedSetOf(minRatio, maxRatio)
        var r = 1.0f
        while (r < maxRatio) {
            if (r > minRatio) ratios.add(r)
            r *= 2f
        }
        return ratios.map { ZoomStop(it, "%.2fx".format(it)) }
    }
}

/**
 * Worst-frame-wins per physical lens, rather than per run.
 *
 * [EchoAccumulator] already answers "did any frame in this take degrade a key".
 * Asking it once per lens is what turns a single recording into a per-lens
 * result, which is what #20 wants to tick and what ADR-0002 action item 2 wants
 * to record.
 */
class SweepAccumulator {
    private val perLens = linkedMapOf<String, EchoAccumulator>()
    private val ratiosSeen = linkedMapOf<String, MutableSet<Float>>()

    /**
     * [physicalId] is `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` from the result.
     * A device that does not report it is not a failure -- a single-sensor camera
     * has nothing to report -- so those frames are bucketed under the logical id
     * the caller passes as [fallbackId].
     */
    fun record(physicalId: String?, fallbackId: String, zoomRatio: Float, echoes: List<KeyEcho>) {
        val id = physicalId ?: fallbackId
        perLens.getOrPut(id) { EchoAccumulator() }.record(echoes)
        ratiosSeen.getOrPut(id) { sortedSetOf() }.add(zoomRatio)
    }

    /** True when the device served the sweep from more than one sensor. */
    val sawMultipleLenses: Boolean get() = perLens.size > 1

    fun reports(): List<SweepLensReport> = perLens.map { (id, acc) ->
        SweepLensReport(
            report = acc.report(cameraId = id, lensLabel = labelFor(id)),
            zoomRatios = ratiosSeen[id].orEmpty().sorted(),
        )
    }

    /**
     * The zoom range a sensor covered is the only lens name available: the
     * physical id is an opaque string and the framework offers no display name,
     * so calling id 4 "telephoto" would be this file inventing a fact.
     */
    private fun labelFor(id: String): String {
        val ratios = ratiosSeen[id].orEmpty().sorted()
        val range = when {
            ratios.isEmpty() -> "no zoom recorded"
            ratios.size == 1 -> "%.2fx".format(ratios.first())
            else -> "%.2fx-%.2fx".format(ratios.first(), ratios.last())
        }
        return "physical id $id ($range)"
    }
}

data class SweepLensReport(
    val report: LensEchoReport,
    val zoomRatios: List<Float>,
)

/** For pasting into #20 and ADR-0002 action item 2. */
fun List<SweepLensReport>.markdown(): String = buildString {
    // Qualified: inside buildString an unqualified isEmpty() is the
    // StringBuilder's, which is always true here.
    if (this@markdown.isEmpty()) {
        appendLine("No capture results observed.")
        return@buildString
    }
    appendLine("Sensors that served the sweep: ${this@markdown.size}.")
    appendLine()
    for (lens in this@markdown) {
        appendLine(lens.report.markdown())
        appendLine()
    }
}
