package com.scenaristo.camera.service

import com.scenaristo.camera.domain.protocol.Take
import com.scenaristo.camera.domain.recording.TakeName
import java.io.File

/**
 * The takes on the phone, read off the directory they are written to (PRD 6.11).
 *
 * Derived, never accumulated. The obvious alternative is a registry the recorder
 * appends to as each take finishes, and it was the first design here; a
 * directory listing beats it on every axis that matters. It cannot go stale, so
 * a take deleted behind the app's back is absent rather than a row that 404s. It
 * has no lifetime, so it survives the service being stopped when idle
 * (ADR-0019) without anything being persisted. And it needs nothing from
 * `VideoRecordEvent.Finalize`, which is a callback with an error path and an
 * empty-URI case that a listing simply does not have.
 *
 * Only the app's own folder (ADR-0020's default). A take written to the gallery
 * instead is already in Photos and already reachable over USB, which is what
 * that opt-in is *for* -- this route exists for the destination nothing else can
 * see. The browser says so rather than showing an empty panel; see PRD 6.11 and
 * the note in [list].
 *
 * Every method is a pure function of [dir], so the listing rules are testable on
 * a host against a temp directory: no service, no camera, no device.
 */
class TakeFolder(
    private val dir: File,
    /**
     * How long a take runs, in milliseconds, or null when the file will not say.
     *
     * Injected because the real implementation is `MediaMetadataRetriever`, an
     * Android framework class with no host implementation -- and duration is the
     * one field a directory listing cannot supply. It is worth the extra read:
     * duration is what a creator picks a take by, and the alternative of showing
     * only a size collides with UI-4's "gigabytes are never shown".
     */
    private val durationOf: (File) -> Long?,
) {

    /**
     * The newest [LIMIT] takes, newest first.
     *
     * [inProgress] is the name of the take being recorded, if one is, and is
     * excluded. A file exists on disk for the whole of a recording -- CameraX
     * writes progressively, which is the premise of #17's promise that a
     * force-killed take is still playable -- so a listing taken mid-take would
     * otherwise offer a file that is still being written. The route refuses
     * every download while recording anyway (PRD 6.11); this keeps the *list*
     * honest as well, rather than relying on the refusal to cover for it.
     *
     * A name that does not match [TakeName.PATTERN] is not a take. That excludes
     * anything a user dropped in the folder, and -- less obviously -- it
     * excludes this app's **own** older recordings: before `TakeName` existed,
     * `CaptureService` wrote `take-<epoch millis>.mp4` (#40). Those are real
     * takes and this list will not show them, which is a deliberate trade: the
     * pattern is also the download route's traversal guard (PRD 6.8), and
     * widening it to admit a second historical shape would widen that. A user
     * upgrading from such a build finds those files where they always were.
     */
    fun list(inProgress: String? = null): List<Take> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && nameOf(it) != null && nameOf(it) != inProgress }
            // By the clock the file was last written, which is when the take
            // ended. Not by name: the name carries the moment recording
            // *started*, and sorting a list of takes by their start times puts a
            // twenty-minute take before a two-second one that came after it.
            .sortedByDescending { it.lastModified() }
            .take(LIMIT)
            .map { file ->
                Take(
                    name = nameOf(file)!!,
                    sizeBytes = file.length(),
                    durationMs = durationOf(file) ?: 0L,
                    recordedAtMs = file.lastModified(),
                )
            }

    /**
     * The file a take name refers to, or null when there is no such take.
     *
     * The pattern check is the path-traversal defence and it is the only one:
     * this is the method that turns a string a client on the LAN chose into a
     * path. [TakeName.PATTERN] is unanchored, so `matches` -- a full match --
     * is what is required, and `containsMatchIn` would accept
     * `../Scenaristo_2026-01-01_00-00-00`. `:server` never sees a path at all;
     * it holds a name and an offset, which is the contract Phase 4 implements on
     * iOS (ADR-0013).
     */
    fun open(name: String): File? {
        if (!TakeName.PATTERN.matches(name)) return null
        return File(dir, "$name.${TakeName.EXTENSION}").takeIf { it.isFile }
    }

    /** The take name a file carries, or null when the file is not a take. */
    private fun nameOf(file: File): String? {
        val name = file.name.removeSuffix(".${TakeName.EXTENSION}")
        if (name == file.name) return null // no extension to remove: not an mp4
        return name.takeIf { TakeName.PATTERN.matches(it) }
    }

    companion object {
        /**
         * How many takes the browser is shown.
         *
         * ADR-0007 broadcasts the whole state document on every change and asks
         * to be revisited when it passes "a few tens of KB". A `Take` is roughly
         * 90 bytes of JSON, so ten is under a kilobyte on a snapshot that is
         * already a couple -- the question is out of reach rather than close to
         * it. Ten is also about as far back as "which take was that" reaches;
         * anything older is found by date, and the phone is where you find it.
         */
        const val LIMIT: Int = 10
    }
}
