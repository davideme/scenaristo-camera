package com.scenaristo.camera.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is watching the preview, which is what decides whether it is encoded at
 * all (ADR-0025).
 *
 * PRD 6.8: "Preview is separate from the recording pipeline: recording is always
 * full resolution and frame rate regardless of preview quality or whether a
 * browser is connected." These tests are the other half of that sentence — the
 * preview *may* depend on whether a browser is connected, and this is the signal
 * it depends on.
 */
class ViewerCountTest {

    @Test
    fun `nobody is watching before anyone connects`() {
        val count = ViewerCount()
        assertEquals(0, count.current)
        assertFalse(count.watching)
    }

    @Test
    fun `one browser opening the stream is one viewer`() {
        val count = ViewerCount()
        count.enter()
        assertEquals(1, count.current)
        assertTrue(count.watching)
    }

    // PRD 6.8: "Multiple browsers may connect". The second must not be masked by
    // the first leaving, or a two-laptop session loses its picture.
    @Test
    fun `PRD 6_8 - a second browser keeps the preview alive when the first leaves`() {
        val count = ViewerCount()
        count.enter()
        count.enter()
        count.leave()
        assertEquals(1, count.current)
        assertTrue("the remaining browser is still watching", count.watching)
    }

    @Test
    fun `the last browser leaving stops the preview`() {
        val count = ViewerCount()
        count.enter()
        count.leave()
        assertEquals(0, count.current)
        assertFalse(count.watching)
    }

    /**
     * The abrupt-disconnect path: an unbalanced `leave` must not drive the count
     * negative, because a negative count reads as "nobody watching" for every
     * viewer that follows — a preview that never comes back, and one that would
     * be diagnosed as a camera fault rather than as arithmetic.
     */
    @Test
    fun `an unbalanced disconnect cannot drive the count negative`() {
        val count = ViewerCount()
        count.leave()
        count.leave()
        assertEquals(0, count.current)
        assertFalse(count.watching)

        count.enter()
        assertTrue("a real viewer after a stray disconnect still counts", count.watching)
    }

    @Test
    fun `every change is reported, with the new total`() {
        val seen = mutableListOf<Int>()
        val count = ViewerCount { seen += it }
        count.enter()
        count.enter()
        count.leave()
        count.leave()
        assertEquals(listOf(1, 2, 1, 0), seen)
    }

    // The service drops the last encoded frame on this exact edge, so that the
    // next browser is not shown a stale one. If zero is never reported, that
    // never happens and the staleness is silent.
    @Test
    fun `reaching zero is reported so the stale frame can be dropped`() {
        var last = -1
        val count = ViewerCount { last = it }
        count.enter()
        count.leave()
        assertEquals(0, last)
    }
}
