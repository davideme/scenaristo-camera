package com.scenaristo.camera.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MJPEG framing (ADR-0008). Small, and the one part of the preview path that
 * fails silently: a browser given a malformed part shows a broken image with no
 * error anywhere, so the bytes are worth asserting rather than eyeballing.
 */
class MjpegTest {

    @Test
    fun `the content type names the boundary the parts use`() {
        assertTrue(Mjpeg.CONTENT_TYPE.startsWith("multipart/x-mixed-replace"))
        assertTrue(Mjpeg.CONTENT_TYPE.contains("boundary=${Mjpeg.BOUNDARY}"))
    }

    // The blank line between headers and body is what separates them; without it
    // the browser reads the JPEG as more headers and renders nothing.
    @Test
    fun `a part header ends with a blank line`() {
        val header = Mjpeg.partHeader(1234).decodeToString()
        assertTrue("headers must be terminated by a blank line", header.endsWith("\r\n\r\n"))
    }

    // ADR-0008 note: without Content-Length the browser scans for the next
    // boundary, which costs latency per frame and breaks outright if the JPEG
    // happens to contain the boundary bytes.
    @Test
    fun `a part header declares the exact frame size`() {
        val header = Mjpeg.partHeader(65_432).decodeToString()
        assertTrue(header.contains("Content-Length: 65432\r\n"))
        assertTrue(header.contains("Content-Type: image/jpeg\r\n"))
    }

    @Test
    fun `a part starts on its own boundary line`() {
        val header = Mjpeg.partHeader(1).decodeToString()
        assertTrue(header.startsWith("\r\n--${Mjpeg.BOUNDARY}\r\n"))
    }

    // The boundary must not be able to occur inside JPEG data, or a frame would
    // split itself in half. ASCII letters and hyphens cannot appear in the binary
    // structure a decoder reads, and this pins that property.
    @Test
    fun `the boundary is plain ASCII with no JPEG marker bytes`() {
        assertTrue(Mjpeg.BOUNDARY.all { it.code in 0x20..0x7E })
        assertTrue(Mjpeg.BOUNDARY.none { it == '\r' || it == '\n' })
    }

    @Test
    fun `the tail closes the multipart stream`() {
        assertEquals("\r\n--${Mjpeg.BOUNDARY}--\r\n", Mjpeg.tail().decodeToString())
    }
}
