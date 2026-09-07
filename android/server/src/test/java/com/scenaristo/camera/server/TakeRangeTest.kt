package com.scenaristo.camera.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PRD 6.11's download, resumed. ADR-0028.
 *
 * A take is ~250 MB per minute (PRD 6.7) and ADR-0026 closes the port the moment
 * the phone leaves a local network, so an interrupted transfer is an ordinary
 * event rather than an edge case. These are the decisions that make the second
 * attempt cost the remainder instead of the whole file.
 */
class TakeRangeTest {

    private val length = 1_000L

    @Test
    fun `PRD 6_11 - no Range header sends the whole take`() {
        assertEquals(TakeRange.Whole, TakeRange.of(null, length))
    }

    @Test
    fun `PRD 6_11 - an open-ended range sends the rest of the take`() {
        assertEquals(TakeRange.Partial(400L..999L), TakeRange.of("bytes=400-", length))
    }

    @Test
    fun `PRD 6_11 - a closed range sends exactly that span`() {
        assertEquals(TakeRange.Partial(100L..199L), TakeRange.of("bytes=100-199", length))
    }

    /** `curl -C -` and every download manager send this to ask for the tail. */
    @Test
    fun `PRD 6_11 - a suffix range sends the last bytes`() {
        assertEquals(TakeRange.Partial(900L..999L), TakeRange.of("bytes=-100", length))
    }

    @Test
    fun `PRD 6_11 - a range that runs past the end stops at the end`() {
        assertEquals(TakeRange.Partial(900L..999L), TakeRange.of("bytes=900-5000", length))
    }

    /**
     * What a client that already has the whole file sends when it thinks it does
     * not. Answering 200 would re-send the take; answering 206 would claim a
     * span that does not exist. 416 is the only honest reply.
     */
    @Test
    fun `PRD 6_11 - a range starting at or past the end is unsatisfiable`() {
        assertEquals(TakeRange.Unsatisfiable, TakeRange.of("bytes=1000-", length))
        assertEquals(TakeRange.Unsatisfiable, TakeRange.of("bytes=5000-6000", length))
    }

    /**
     * The dangerous case, and the reason this is not one call to
     * `mergeToSingle`: that function collapses disjoint ranges into the single
     * span covering them, so honouring this as a 206 would send bytes 0..299 --
     * two hundred of which the client never asked for -- while labelling it a
     * partial response the client will splice into a file. RFC 9110 allows a
     * server to ignore a Range it does not wish to honour, so it does.
     */
    @Test
    fun `PRD 6_11 - a multi-range request is answered whole, not merged`() {
        assertEquals(TakeRange.Whole, TakeRange.of("bytes=0-99,200-299", length))
    }

    @Test
    fun `PRD 6_11 - a unit that is not bytes is ignored`() {
        assertEquals(TakeRange.Whole, TakeRange.of("items=0-99", length))
    }

    @Test
    fun `PRD 6_11 - a malformed range is ignored rather than refused`() {
        listOf("bytes=", "bytes=abc-def", "nonsense", "bytes=-", "bytes=10-5")
            .forEach { assertEquals(it, TakeRange.Whole, TakeRange.of(it, length)) }
    }

    /**
     * A take with no bytes yet. 416 would be a strange way to say "there is
     * nothing here", and the empty 200 is what a client can act on.
     */
    @Test
    fun `PRD 6_11 - an empty take is always whole`() {
        assertEquals(TakeRange.Whole, TakeRange.of("bytes=0-", 0L))
    }

    @Test
    fun `PRD 6_11 - the first byte is a valid start`() {
        assertEquals(TakeRange.Partial(0L..999L), TakeRange.of("bytes=0-", length))
        assertEquals(TakeRange.Partial(999L..999L), TakeRange.of("bytes=999-", length))
    }
}
