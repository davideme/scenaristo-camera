package com.scenaristo.camera.server

import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.protocol.CaptureSettings
import com.scenaristo.camera.domain.protocol.DeviceStatus
import com.scenaristo.camera.domain.protocol.RecordingState
import com.scenaristo.camera.domain.protocol.Session
import com.scenaristo.camera.domain.protocol.State
import com.scenaristo.camera.domain.protocol.ThermalState
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * PRD 6.11's download route, on a real socket (ADR-0028).
 *
 * A real CIO server rather than `testApplication`, for the reason [LanGuardTest]
 * gives: the test engine reports `localhost` as the remote address, which
 * `LanOnly` refuses, so every request would 403 and nothing could be asserted.
 * Writing the request bytes also means the `Range` header is exactly what a
 * browser sends rather than what a client library decides to send.
 *
 * The take is a byte array, not a file. What is under test here is the route --
 * its refusals, its headers, its arithmetic and whether it closes what it opens
 * -- and a real file would only add a temp directory to the fixture. Reading
 * actual takes off actual storage is [TakeFolder][com.scenaristo.camera.service]'s
 * job in `:app`, and the phone's.
 */
class TakeRouteTest {

    private lateinit var server: ControlServer
    private var port = 0
    private var recording = false
    private val opened = AtomicInteger(0)
    private val closed = AtomicInteger(0)
    private val downloads = mutableListOf<Int>()

    /** 4 KB of a recognisable pattern, so a wrong offset shows up as wrong bytes. */
    private val take = ByteArray(4096) { (it % 251).toByte() }

    @Before
    fun startServer() {
        port = freePort()
        server = ControlServer(
            session = Session(idle()),
            frames = PreviewFrames { null },
            port = port,
            takes = { name, fromByte ->
                if (name != TAKE_NAME) null else {
                    opened.incrementAndGet()
                    object : TakeStream {
                        override val totalBytes = take.size.toLong()
                        override val bytes: InputStream =
                            ByteArrayInputStream(take, fromByte.toInt(), take.size - fromByte.toInt())
                        override fun close() { closed.incrementAndGet() }
                    }
                }
            },
            onDownloadsChanged = { synchronized(downloads) { downloads += it } },
        )
        server.start()
        awaitListening()
    }

    @After
    fun stopServer() {
        server.stop()
    }

    @Test
    fun `PRD 6_11 - a finished take downloads whole`() {
        val response = fetch(path(TAKE_NAME))

        assertEquals("HTTP/1.1 200", response.status)
        assertEquals("video/mp4", response.header("Content-Type")?.substringBefore(";"))
        assertEquals("${take.size}", response.header("Content-Length"))
        assertEquals("bytes", response.header("Accept-Ranges"))
        assertArrayEquals(take, response.body)
    }

    /**
     * The `download` attribute on a link only helps someone who clicks it.
     * Someone who pastes the URL gets an in-page player without this.
     */
    @Test
    fun `PRD 6_11 - the response is an attachment named after the take`() {
        val disposition = fetch(path(TAKE_NAME)).header("Content-Disposition").orEmpty()

        assertTrue(disposition, disposition.startsWith("attachment"))
        assertTrue(disposition, disposition.contains("$TAKE_NAME.mp4"))
    }

    /**
     * Without a validator no browser download manager will offer to resume, so
     * the range handling below would be reachable only from `curl` -- and
     * ADR-0026's dropped-network case, which is why any of it exists, would be
     * unserved.
     */
    @Test
    fun `PRD 6_11 - the response carries a validator so a download can resume`() {
        val response = fetch(path(TAKE_NAME))

        assertEquals("\"$TAKE_NAME-${take.size}\"", response.header("ETag"))
        // `no-store` would forbid the resume this route is built for. Copying it
        // from the preview route is the mistake this asserts against.
        assertFalse(response.header("Cache-Control").orEmpty().contains("no-store"))
    }

    @Test
    fun `PRD 6_11 - an open-ended range answers 206 with the rest of the take`() {
        val response = fetch(path(TAKE_NAME), range = "bytes=1000-")

        assertEquals("HTTP/1.1 206", response.status)
        assertEquals("bytes 1000-4095/4096", response.header("Content-Range"))
        assertEquals("3096", response.header("Content-Length"))
        assertArrayEquals(take.copyOfRange(1000, take.size), response.body)
    }

    @Test
    fun `PRD 6_11 - a closed range answers exactly that span`() {
        val response = fetch(path(TAKE_NAME), range = "bytes=10-19")

        assertEquals("HTTP/1.1 206", response.status)
        assertEquals("bytes 10-19/4096", response.header("Content-Range"))
        assertArrayEquals(take.copyOfRange(10, 20), response.body)
    }

    @Test
    fun `PRD 6_11 - a range past the end answers 416 and says how long the take is`() {
        val response = fetch(path(TAKE_NAME), range = "bytes=99999-")

        assertEquals("HTTP/1.1 416", response.status)
        assertEquals("bytes */4096", response.header("Content-Range"))
    }

    @Test
    fun `PRD 6_11 - a multi-range request answers the whole take, not a merged span`() {
        val response = fetch(path(TAKE_NAME), range = "bytes=0-99,200-299")

        assertEquals("HTTP/1.1 200", response.status)
        assertArrayEquals(take, response.body)
    }

    /**
     * The take being written exists on disk for the whole of a recording --
     * CameraX writes progressively, which is what makes #17's force-killed take
     * playable -- so "the file is there" cannot mean "the take is finished".
     * This is the check that stops a half-written take being served, and it is
     * also the IO and thermal argument: a 2.5 GB read alongside a 36 Mbit/s UHD
     * write is the load PRD 6.1's frame rate can least absorb.
     */
    @Test
    fun `PRD 6_11 - every take is refused while one is recording`() {
        recording = true
        server.stop()
        startWith(recording = true)

        val response = fetch(path(TAKE_NAME))

        assertEquals("HTTP/1.1 409", response.status)
        assertEquals("nothing was opened", 0, opened.get())
    }

    @Test
    fun `PRD 6_11 - a take that does not exist answers 404`() {
        assertEquals("HTTP/1.1 404", fetch(path("Scenaristo_2020-01-01_00-00-00")).status)
    }

    /**
     * PRD 6.8. Two layers refuse these and it is worth knowing which is which,
     * because only one of them is ours.
     *
     * A path containing a literal `..` is rejected by CIO with **400** before
     * routing runs at all. The percent-encoded and plainly-wrong names do reach
     * the route, and it answers **404** because `TakeName.PATTERN` is unanchored
     * and applied with `matches` -- `containsMatchIn` would accept every name
     * here that carries a real take name inside it.
     *
     * What both layers must have in common is the last assertion: none of them
     * reaches the take source. A refusal that still opened a file would be a
     * traversal that merely failed to be reported.
     */
    @Test
    fun `PRD 6_8 - a name that is a path is refused and opens nothing`() {
        listOf(
            "/takes/../secret.mp4",
            "/takes/../$TAKE_NAME.mp4",
            "/takes/..%2Fsecret.mp4",
            "/takes/%2e%2e%2f$TAKE_NAME.mp4",
            "/takes/$TAKE_NAME.mp4/../../secret.mp4",
        ).forEach {
            val status = fetch(it).status
            assertTrue("$it was not refused: $status", status.startsWith("HTTP/1.1 4"))
        }

        // The route's own refusal, with no help from the path parser: a
        // well-formed request for a name that is not a take.
        listOf("/takes/nonsense.mp4", "/takes/.mp4", "/takes/Scenaristo_2026-09-06.mp4")
            .forEach { assertEquals(it, "HTTP/1.1 404", fetch(it).status) }

        assertEquals("no refused request reached the take source", 0, opened.get())
    }

    /**
     * A download holds a file descriptor for as long as the transfer lasts, and
     * a cancelled download is the ordinary case: every closed tab, every
     * changed mind, every walk out of Wi-Fi range. Leaking one per cancellation
     * exhausts them on a long shoot.
     */
    @Test
    fun `ADR-0028 - the take is closed and uncounted however the download ends`() {
        fetch(path(TAKE_NAME))
        assertEquals("closed after a complete download", opened.get(), closed.get())

        // Hang up mid-body: ask for the take and read only the first bytes.
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.getOutputStream().apply {
                write(request(path(TAKE_NAME), null).toByteArray())
                flush()
            }
            socket.getInputStream().read(ByteArray(64))
        }

        awaitDrained()
        assertEquals("closed after a cancelled download", opened.get(), closed.get())
        assertEquals("the count came back to zero", 0, server.let { downloads.last() })
        assertTrue("the count rose while downloading", downloads.contains(1))
    }

    /**
     * ADR-0025: a download must not be mistaken for a viewer. Reading a file
     * needs no camera, and binding one would spend thermal budget producing
     * frames nobody is looking at.
     */
    @Test
    fun `ADR-0025 - a download does not count as a preview viewer`() {
        val viewers = mutableListOf<Int>()
        server.stop()
        port = freePort()
        server = ControlServer(
            session = Session(idle()),
            frames = PreviewFrames { null },
            port = port,
            onViewersChanged = { synchronized(viewers) { viewers += it } },
            takes = { _, _ ->
                object : TakeStream {
                    override val totalBytes = 1L
                    override val bytes: InputStream = ByteArrayInputStream(byteArrayOf(7))
                    override fun close() = Unit
                }
            },
        )
        server.start()
        awaitListening()

        fetch(path(TAKE_NAME))

        assertEquals("no viewer was counted", emptyList<Int>(), viewers)
    }

    @Test
    fun `PRD 6_11 - a take with no bytes is served as an empty 200`() {
        server.stop()
        port = freePort()
        server = ControlServer(
            session = Session(idle()),
            frames = PreviewFrames { null },
            port = port,
            takes = { _, _ ->
                object : TakeStream {
                    override val totalBytes = 0L
                    override val bytes: InputStream = ByteArrayInputStream(ByteArray(0))
                    override fun close() = Unit
                }
            },
        )
        server.start()
        awaitListening()

        val response = fetch(path(TAKE_NAME), range = "bytes=0-")

        assertEquals("HTTP/1.1 200", response.status)
        assertEquals("0", response.header("Content-Length"))
        assertNull(response.header("Content-Range"))
    }

    // --- harness ------------------------------------------------------------

    private fun path(name: String) = "/takes/$name.mp4"

    private fun startWith(recording: Boolean) {
        port = freePort()
        server = ControlServer(
            session = Session(idle(recording)),
            frames = PreviewFrames { null },
            port = port,
            takes = { _, _ ->
                opened.incrementAndGet()
                object : TakeStream {
                    override val totalBytes = take.size.toLong()
                    override val bytes: InputStream = ByteArrayInputStream(take)
                    override fun close() { closed.incrementAndGet() }
                }
            },
        )
        server.start()
        awaitListening()
    }

    private class Response(val status: String, val headers: List<String>, val body: ByteArray) {
        fun header(name: String): String? = headers
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
    }

    private fun request(path: String, range: String?) = buildString {
        append("GET $path HTTP/1.1\r\n")
        append("Host: 127.0.0.1:$port\r\n")
        if (range != null) append("Range: $range\r\n")
        append("Connection: close\r\n\r\n")
    }

    /** Reads the whole response, headers and body, off a raw socket. */
    private fun fetch(path: String, range: String? = null): Response {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.getOutputStream().apply {
                write(request(path, range).toByteArray())
                flush()
            }
            val all = socket.getInputStream().readBytes()
            val split = indexOfHeaderEnd(all)
            val head = String(all, 0, split).split("\r\n").filter { it.isNotEmpty() }
            return Response(
                status = head.first().split(" ").take(2).joinToString(" "),
                headers = head.drop(1),
                body = all.copyOfRange(minOf(split + 4, all.size), all.size),
            )
        }
    }

    private fun indexOfHeaderEnd(bytes: ByteArray): Int {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == C_R && bytes[i + 1] == L_F && bytes[i + 2] == C_R && bytes[i + 3] == L_F) return i
        }
        return bytes.size
    }

    /** The `finally` runs after the socket closes, so the counters settle a moment later. */
    private fun awaitDrained() {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (opened.get() == closed.get() && downloads.lastOrNull() == 0) return
            Thread.sleep(20)
        }
    }

    private fun awaitListening() {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("127.0.0.1", port).close()
                return
            } catch (_: ConnectException) {
                Thread.sleep(20)
            }
        }
        throw AssertionError("server never accepted a connection on port $port")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun idle(recording: Boolean = false) = State(
        settings = CaptureSettings(
            grid = GridFrequency.HZ_50,
            shutterHz = 50,
            iso = 100,
            whiteBalanceKelvin = 5600,
            lensId = "0",
        ),
        recording = RecordingState(recording = recording),
        device = DeviceStatus(
            batteryPercent = 80,
            charging = false,
            thermal = ThermalState.NOMINAL,
            storageMinutesRemaining = 120,
        ),
        serverTimeMs = 0,
    )

    private companion object {
        const val TAKE_NAME = "Scenaristo_2026-09-06_14-32-05"
        const val TIMEOUT_MS = 5_000
        const val C_R: Byte = 13
        const val L_F: Byte = 10
    }
}
