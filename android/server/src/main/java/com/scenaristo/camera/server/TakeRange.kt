package com.scenaristo.camera.server

import io.ktor.http.parseRangesSpecifier

/**
 * What a `Range` header asks for, decided without touching a file (ADR-0028).
 *
 * A separate, pure function for the same reason [FramePacer] and
 * [PreviewQuality] are: `:server` has no Ktor test host, so a decision reachable
 * only through a running server is a decision nothing asserts cheaply. The
 * interesting cases here -- an unsatisfiable start, a suffix range, a
 * multi-range request -- are all one-liners to state and awkward to provoke over
 * a socket.
 *
 * Range support is not a nicety on this route. ADR-0026 closes the port whenever
 * the phone leaves a local network, so a Wi-Fi blip during a 2.5 GB take is an
 * ordinary event; without resume it costs the whole transfer.
 */
sealed interface TakeRange {

    /** Send the whole file: no `Range`, an unparseable one, or one we decline to honour. */
    data object Whole : TakeRange

    /** Send `range` as a 206, inclusive on both ends. */
    data class Partial(val range: LongRange) : TakeRange

    /** The request cannot be satisfied: answer 416 with `Content-Range: bytes *​/length`. */
    data object Unsatisfiable : TakeRange

    companion object {

        /**
         * Decides what to send for [header] against a file of [length] bytes.
         *
         * A zero-length file is always [Whole]: there is no byte position that
         * could satisfy a range, and 416 for an empty take would be a confusing
         * way to say "there is nothing here".
         */
        fun of(header: String?, length: Long): TakeRange {
            if (header == null || length <= 0L) return Whole
            val specifier = runCatching { parseRangesSpecifier(header) }.getOrNull() ?: return Whole

            // Only `bytes`. RFC 9110 allows other units and nothing sends them;
            // an unknown unit is a header to ignore, not an error to report.
            if (!specifier.unit.equals("bytes", ignoreCase = true)) return Whole

            // A genuine multi-range request is answered whole. `mergeToSingle`
            // would otherwise collapse disjoint ranges into the one span that
            // covers them and we would serve that as a 206 -- a wrong answer
            // reported as a right one, which is worse than the 200 that RFC 9110
            // explicitly permits when a Range is not honoured.
            if (specifier.ranges.size != 1) return Whole

            val merged = specifier.mergeToSingle(length) ?: return Unsatisfiable
            // `mergeToSingle` clamps to the file, so this catches the start being
            // at or past the end -- the case a resuming client hits when it
            // already has the whole file.
            if (merged.isEmpty() || merged.first >= length) return Unsatisfiable
            return Partial(merged.first..minOf(merged.last, length - 1))
        }
    }
}
