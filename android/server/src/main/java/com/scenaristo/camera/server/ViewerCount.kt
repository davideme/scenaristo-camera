package com.scenaristo.camera.server

import java.util.concurrent.atomic.AtomicInteger

/**
 * How many browsers are pulling the MJPEG preview right now (ADR-0025).
 *
 * The WebSocket client count already in the state document does not answer this.
 * They are genuinely different populations: `/preview.mjpg` is a plain `<img>`
 * URL, so a browser can be watching the picture with no control socket at all,
 * and the remote can hold a control socket open while showing no picture. The
 * preview encoder is paid for by the first population and by nobody else.
 *
 * Its own class rather than an `AtomicInteger` inside [ControlServer] for one
 * reason: `:server` has no Ktor test host, so a counter reachable only through a
 * running server is a counter nothing asserts. The disconnect path is the half
 * that matters and the half that is easy to get wrong, and here it can be tested
 * directly.
 *
 * [onChange] is called on whichever thread entered or left -- Ktor's, in
 * production -- so what it does must be safe from any of them.
 */
class ViewerCount(private val onChange: (Int) -> Unit = {}) {

    private val count = AtomicInteger(0)

    /** How many are attached. */
    val current: Int get() = count.get()

    /** Whether the preview is worth encoding at all. */
    val watching: Boolean get() = count.get() > 0

    /** A browser opened the stream. */
    fun enter() = onChange(count.incrementAndGet())

    /**
     * A browser's stream ended, however it ended.
     *
     * Clamped at zero rather than trusted to balance. The call sites are
     * `finally` blocks around a network read, and the cost of an unbalanced
     * decrement is a negative count that reads as "nobody watching" forever --
     * a preview that never comes back, diagnosed as a camera fault. Clamping
     * turns that into an over-count that self-corrects on the next disconnect.
     */
    fun leave() = onChange(count.updateAndGet { if (it > 0) it - 1 else 0 })
}
