package com.scenaristo.camera.server

import java.io.Closeable
import java.io.InputStream

/**
 * A source of finished takes for the download route (PRD 6.11, ADR-0028).
 *
 * The second capture-side contract this module holds, after [PreviewFrames], and
 * deliberately the same shape: a name and an offset in, bytes out. `:server`
 * never learns where a take is stored, never sees a `File` or a `Uri`, and never
 * turns a client's string into a path -- so Phase 4's iOS server implements this
 * one interface rather than reproducing a storage decision (ADR-0013).
 *
 * Answering "is there a take called this" is the implementation's job because
 * only it knows. That is not a division of labour for its own sake: it is what
 * makes the name a lookup key rather than a path fragment, which is the whole
 * of the traversal defence (PRD 6.8).
 */
fun interface Takes {

    /**
     * The take called [name], positioned at [fromByte], or null when there is no
     * such take.
     *
     * [name] carries no extension and no path -- `Scenaristo_YYYY-MM-DD_HH-MM-SS`
     * exactly. The caller has already checked it against the pattern, and the
     * implementation checks again: two cheap checks are worth one missed one
     * when the string came off the network.
     */
    fun open(name: String, fromByte: Long): TakeStream?
}

/**
 * One take, open for reading.
 *
 * [Closeable] because this is the first thing `:server` serves that holds an
 * operating-system resource. The preview stream has nothing to leak -- it writes
 * frames somebody else owns -- but a download holds a file descriptor for as
 * long as the transfer lasts, and a browser cancelling a download is the
 * *ordinary* case, not the exceptional one: every user who changes their mind,
 * closes a tab, or walks out of Wi-Fi range produces one. A route that leaks a
 * descriptor per cancelled download runs out of them.
 */
interface TakeStream : Closeable {

    /**
     * The length of the whole file, not of what remains after `fromByte`.
     *
     * The whole length is what a `Content-Range` has to end with, and it is the
     * only number a resuming client can check its own arithmetic against.
     */
    val totalBytes: Long

    /** Positioned at the requested offset; reading it yields the rest of the take. */
    val bytes: InputStream
}
