package com.scenaristo.camera.server

/**
 * How long to wait after sending a preview frame, so the stream runs at the rate
 * ADR-0008 allows rather than at that rate minus the time each write took.
 *
 * The loop used to `delay(FRAME_INTERVAL_MS)` after every flush, which makes the
 * interval **write time plus the delay** rather than a period. Measured on the
 * reference device on 2026-09-07: a 56 KiB frame writes in about 18 ms over
 * Wi-Fi, so a 66 ms sleep produced an 84 ms median interval and **11.7 fps
 * against a 15 fps cap** — a fifth of the preview's smoothness given away to an
 * accounting mistake. It also got worse exactly when it hurt most: a slower link
 * writes for longer, so it also waited longer, and the rate fell faster than the
 * link degraded.
 *
 * Pacing to a deadline instead makes the delay the *remainder* of the interval.
 * A write that already overran sends the next frame immediately.
 *
 * Its own class because the pacing lives inside a Ktor response writer, and a
 * rule reachable only through a running server is a rule nothing asserts —
 * `:server` has no Ktor test host (noted on #110 for the same reason).
 *
 * Not thread-safe, and does not need to be: one instance belongs to one
 * response, and one response is written by one coroutine.
 */
internal class FramePacer(private val intervalMs: Long) {

    /** When the next frame is due. Unset until the first send establishes the phase. */
    private var nextAt: Long? = null

    /**
     * How long to wait after sending a frame at [nowMs].
     *
     * Zero when the send already overran its slot, and the deadline is then
     * re-based on the present rather than on the slot that was missed. Letting
     * missed slots accumulate would make the stream burst frames once a slow
     * patch cleared — paying back a debt of latency with a flood, which is worse
     * for a live preview than simply having been late.
     */
    fun afterSend(nowMs: Long): Long {
        val due = (nextAt ?: nowMs) + intervalMs
        val wait = due - nowMs
        return if (wait > 0) {
            nextAt = due
            wait
        } else {
            nextAt = nowMs
            0
        }
    }
}
