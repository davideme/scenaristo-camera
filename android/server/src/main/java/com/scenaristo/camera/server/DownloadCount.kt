package com.scenaristo.camera.server

import java.util.concurrent.atomic.AtomicInteger

/**
 * How many takes are being downloaded right now (PRD 6.11, ADR-0028).
 *
 * Structurally identical to [ViewerCount], and a separate class on purpose. The
 * two counts mean opposite things to the rest of the app, and the difference is
 * the kind that is invisible at a call site:
 *
 * - A **viewer** is a reason to keep the camera bound. [ViewerCount] feeds
 *   ADR-0025's standby decision, and every use of it reads as "somebody is
 *   watching".
 * - A **download** is a reason to keep the *service* alive (ADR-0019) and to
 *   hold the wake and Wi-Fi locks, and is emphatically **not** a reason to bind
 *   the camera. Reading a file off disk needs no sensor, and waking the camera
 *   for it would spend power and thermal budget on nothing.
 *
 * A second `ViewerCount` instance would compile, work, and read at every call
 * site as though downloads were viewers -- so the next person to add a term to
 * the camera predicate would have no reason not to include it. The duplication
 * is the documentation.
 *
 * [onChange] is called on whichever thread started or finished the download --
 * Ktor's, in production -- so what it does must be safe from any of them.
 */
class DownloadCount(private val onChange: (Int) -> Unit = {}) {

    private val count = AtomicInteger(0)

    /** How many are in flight. */
    val current: Int get() = count.get()

    /** A download started. */
    fun enter() = onChange(count.incrementAndGet())

    /**
     * A download ended, however it ended.
     *
     * Clamped at zero for the same reason as [ViewerCount.leave]: the call site
     * is a `finally` around a network write, and an unbalanced decrement would
     * leave a negative count reading as "nothing downloading" forever. Here that
     * would mean the service could be stopped out from under a live transfer,
     * which is precisely what this count exists to prevent.
     */
    fun leave() = onChange(count.updateAndGet { if (it > 0) it - 1 else 0 })
}
