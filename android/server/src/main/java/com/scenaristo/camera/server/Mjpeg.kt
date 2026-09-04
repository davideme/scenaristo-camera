package com.scenaristo.camera.server

/**
 * The `multipart/x-mixed-replace` framing browsers render natively from a plain
 * `<img>` (ADR-0008).
 *
 * There is no preview protocol to write: the format is a boundary, a couple of
 * headers and the JPEG bytes, repeated. Keeping the framing here as pure
 * functions means the one part that can be got wrong silently — a missing CRLF,
 * a wrong Content-Length — is testable without a socket or a camera.
 */
object Mjpeg {

    /** Arbitrary but fixed; it only has to not appear in JPEG data, and it cannot. */
    const val BOUNDARY: String = "scenaristo-frame"

    /** The response content type, which is what makes the browser keep the connection open. */
    const val CONTENT_TYPE: String = "multipart/x-mixed-replace; boundary=$BOUNDARY"

    /**
     * The part header preceding one frame's bytes.
     *
     * `Content-Length` is not optional in practice: without it a browser has to
     * scan for the next boundary, which costs latency on every frame and breaks
     * outright if the JPEG happens to contain the boundary bytes.
     */
    fun partHeader(jpegSize: Int): ByteArray = buildString {
        append("\r\n--").append(BOUNDARY).append("\r\n")
        append("Content-Type: image/jpeg\r\n")
        append("Content-Length: ").append(jpegSize).append("\r\n\r\n")
    }.encodeToByteArray()

    /** Closes the stream politely when the producer stops. Browsers tolerate its absence. */
    fun tail(): ByteArray = "\r\n--$BOUNDARY--\r\n".encodeToByteArray()
}
