package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.lens.TStop
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The T-stop the remote shows beside the f/-number (#101).
 *
 * The number is an assumption, not a measurement, so what is testable is the
 * arithmetic and the honesty of the edges — not the value itself.
 */
class TStopTest {

    @Test
    fun `T is f over the square root of transmission`() {
        // 92% transmission on an f/1.7 lens.
        assertEquals(1.7 / 0.9591663, TStop.of(1.7)!!, absoluteTolerance = 1e-6)
    }

    /**
     * The correction goes under a square root because a stop is a ratio of
     * areas. Applying transmission linearly is the usual mistake and would
     * double the loss: 0.24 stops instead of 0.12.
     */
    @Test
    fun `the assumed loss is a sixth of a stop, not a third`() {
        val f = 1.7
        val stops = log2((TStop.of(f)!! / f) * (TStop.of(f)!! / f))
        assertEquals(log2(1.0 / TStop.TRANSMISSION), stops, absoluteTolerance = 1e-9)
        assertTrue(stops in 0.11..0.13, "expected about 0.12 EV, got $stops")
    }

    /** A perfect lens transmits everything, so its T-stop is its f/-number. */
    @Test
    fun `full transmission leaves the f-number alone`() {
        assertEquals(2.8, TStop.of(2.8, transmission = 1.0)!!, absoluteTolerance = 1e-12)
    }

    /**
     * Null rather than a number, for every input that cannot produce one. A
     * lens with no reported aperture must draw nothing, not `T0.0` — the whole
     * argument for showing this at all is that the reader can see what it is.
     */
    @Test
    fun `an impossible input produces nothing rather than a number`() {
        assertNull(TStop.of(0.0), "a lens with no reported aperture")
        assertNull(TStop.of(-1.0))
        assertNull(TStop.of(1.7, transmission = 0.0))
        assertNull(TStop.of(1.7, transmission = 1.5), "more light out than in")
    }

    @Test
    fun `a T-stop is always slower than the f-number that produced it`() {
        for (f in listOf(1.4, 1.7, 2.0, 2.8, 4.0)) {
            assertTrue(TStop.of(f)!! > f, "T must be slower than f/$f")
        }
    }
}
