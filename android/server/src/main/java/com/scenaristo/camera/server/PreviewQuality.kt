package com.scenaristo.camera.server

/**
 * JPEG quality that follows the link, so a congested network costs sharpness
 * rather than the picture (PRD 6.8, ADR-0008).
 *
 * PRD 6.8 asks that "quality and frame rate degrade automatically under
 * bandwidth pressure", and ADR-0008 accepts the consequence in as many words:
 * "a congested 2.4 GHz network shows 5 fps and lower quality". Without this the
 * only thing that gives is time — the suspending write stalls, the interval
 * stretches, and the preview freezes on a stale frame while the socket drains.
 * A frozen preview is the one failure a framing aid must not have, because it
 * looks exactly like a still room.
 *
 * **The write duration is the signal, and it is the honest one.** The server
 * cannot see the link, but it can see how long its own flush took, and a write
 * that outlasts the frame interval means frames are being produced faster than
 * the socket drains. Nothing else has to be measured or guessed.
 *
 * Its own class for the reason `FramePacer` is: the loop lives inside a Ktor
 * response writer and `:server` has no test host, so a rule reachable only
 * through a running server is a rule nothing asserts.
 *
 * **One instance per server, not per response**, because there is one encoder
 * and therefore one quality. Per-response controllers would imply a per-response
 * quality that does not exist, and two viewers on different links would fight
 * over the single knob -- the good link raising what the bad link just lowered,
 * forever. Sharing it means any viewer that cannot keep up lowers the quality
 * for everyone, and it climbs back only once every viewer is comfortable, which
 * is the honest consequence of one shared encoder.
 *
 * That sharing is why [onFrameWritten] is synchronised: responses are written by
 * different coroutines on different threads.
 */
internal class PreviewQuality(
    private val intervalMs: Long,
    /**
     * The floor.
     *
     * 35 is low enough to roughly halve the bytes of an 80 and still show a
     * face well enough to frame it. Below that the preview stops being able to
     * do its job, and dropping further would trade the last of its usefulness
     * for a link that is already failing — at which point the frame rate, not
     * the quality, is the thing that should give.
     */
    private val floor: Int = 35,
    /** ADR-0008's starting point: a 960x540 JPEG at 80 is 50-100 KB. */
    private val ceiling: Int = 80,
    private val step: Int = 10,
) {
    var quality: Int = ceiling
        private set

    private var calmFrames = 0

    /**
     * Reports how long the last frame took to write, and returns true when the
     * quality changed as a result.
     *
     * Recovery is deliberately slower than degradation, and asymmetric on
     * purpose. Dropping happens on a single slow write, because by then the
     * preview is already behind and waiting for confirmation spends the very
     * latency being defended. Climbing back needs [CALM_FRAMES] consecutive fast
     * ones — about a second and a half at 15 fps — because a link that just
     * recovered for one frame has not recovered, and a quality that chases every
     * fluctuation is a preview that visibly pulses.
     */
    @Synchronized
    fun onFrameWritten(writeMs: Long): Boolean {
        if (writeMs > intervalMs) {
            calmFrames = 0
            if (quality <= floor) return false
            quality = maxOf(floor, quality - step)
            return true
        }
        // Half the interval, not the whole of it: a write taking most of its
        // slot is keeping up only just, and raising the quality then is what
        // starts the oscillation this hysteresis exists to prevent.
        if (writeMs * 2 > intervalMs) {
            calmFrames = 0
            return false
        }
        calmFrames++
        if (calmFrames < CALM_FRAMES) return false
        calmFrames = 0
        if (quality >= ceiling) return false
        quality = minOf(ceiling, quality + step)
        return true
    }

    private companion object {
        /** About a second and a half of comfortable writes at ADR-0008's 15 fps. */
        const val CALM_FRAMES = 20
    }
}
