package com.scenaristo.camera.domain.recording

/**
 * What a take is called on disk (PRD 6.7: `Scenaristo_YYYY-MM-DD_HH-MM-SS.mp4`).
 *
 * Here rather than in `:capture` because both platforms have to produce the same
 * name from the same instant, and a second implementation is a second chance to
 * get the separators wrong (ADR-0013). It matters more than a filename usually
 * does: the timestamp is what makes two takes a minute apart sort correctly in a
 * directory listing, and it is the ordering the remote control's take list uses
 * as well (PRD 6.11). The phone's own screen still shows no list -- that is a
 * non-goal of the phone UI spec, not an oversight -- so on the phone the file
 * browser remains the take list.
 *
 * The platform supplies the calendar fields rather than an instant, because
 * turning an instant into a local date needs a time zone database and
 * `commonMain` is platform-free (ADR-0010, ADR-0015). Local time, not UTC: the
 * name exists to be recognised by the person who shot it.
 *
 * The name is also the handle the remote control downloads a take by, which is
 * why [PATTERN] is load-bearing rather than only a test aid -- see [path].
 */
object TakeName {

    /**
     * The name without an extension, so a caller can add `.mp4` or hand it to a
     * `MediaStore` entry that appends its own.
     */
    fun of(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String =
        "Scenaristo_${pad(year, 4)}-${pad(month)}-${pad(day)}_" +
            "${pad(hour)}-${pad(minute)}-${pad(second)}"

    /**
     * The shape [of] produces, for the platform tests that assert their clock
     * plumbing did not quietly reorder or re-separate the fields.
     *
     * A regex rather than a format string because it is used to check a result,
     * and a format string that both sides share proves only that they share a
     * bug.
     */
    val PATTERN = Regex("""Scenaristo_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}""")

    /** The extension PRD 6.7 fixes for both platforms. */
    const val EXTENSION: String = "mp4"

    /**
     * Where the remote control downloads a take from (PRD 6.11).
     *
     * The route, the browser's link and the Phase 4 iOS server all read the
     * path from here rather than each spelling it out, so there is one string to
     * change and no way for the three to drift (ADR-0013). It is emitted into
     * TypeScript from these two constants, not re-typed there.
     *
     * Not carried in the state document per take: it is the same shape for every
     * take, and a field that repeats a derivable value is a field that can
     * disagree with itself.
     */
    const val PATH_PREFIX: String = "/takes/"

    /** [PATH_PREFIX] and [EXTENSION] applied to [name]. */
    fun path(name: String): String = "$PATH_PREFIX$name.$EXTENSION"

    private fun pad(value: Int, width: Int = 2): String = value.toString().padStart(width, '0')
}
