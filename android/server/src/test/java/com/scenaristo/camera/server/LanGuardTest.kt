package com.scenaristo.camera.server

import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.protocol.CaptureSettings
import com.scenaristo.camera.domain.protocol.DeviceStatus
import com.scenaristo.camera.domain.protocol.RecordingState
import com.scenaristo.camera.domain.protocol.Session
import com.scenaristo.camera.domain.protocol.State
import com.scenaristo.camera.domain.protocol.ThermalState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket

/**
 * PRD 6.8 / ADR-0006, on a real socket: "Given a request whose `Host` header is
 * not an IP literal, then the server answers 403."
 *
 * `LanOnlyTest` in `:domain` already pins the rule itself, so what is left to
 * prove is the wiring -- and the wiring is exactly what was wrong. The per-route
 * `admit()` this replaced was never called for `staticResources("/", "web")`,
 * so the whole web bundle answered 200 to a rebinding `Host`, and `/ws` answered
 * 101 and closed afterwards. Neither is visible in a unit test of the rule; both
 * are visible in a status line.
 *
 * It runs a real CIO server rather than `testApplication` because the test
 * engine reports a remote address of `localhost`, which is not an IPv4 literal
 * and would therefore refuse every request -- making an admitted request
 * impossible to assert and a "refuse everything" bug indistinguishable from a
 * correct guard. A loopback socket gives 127.0.0.1, and lets the request bytes,
 * `Host` header included, be written exactly as the curl cases in ADR-0006 do.
 */
class LanGuardTest {

    private lateinit var server: ControlServer
    private var port = 0

    @Before
    fun startServer() {
        port = freePort()
        server = ControlServer(
            session = Session(idle()),
            frames = PreviewFrames { null },
            port = port,
        )
        server.start()
        awaitListening()
    }

    @After
    fun stopServer() {
        server.stop()
    }

    // The bug: the static bundle was the one route with no admission check, so
    // this returned 200 and served the whole UI.
    @Test
    fun `PRD 6_8 - the static bundle is refused when Host is not an IP literal`() {
        assertEquals(FORBIDDEN, status(get("/", host = "evil.example.com")))
    }

    @Test
    fun `PRD 6_8 - a nested path in the bundle is refused too`() {
        assertEquals(FORBIDDEN, status(get("/assets/index.js", host = "evil.example.com")))
    }

    @Test
    fun `PRD 6_8 - the preview stream is refused when Host is not an IP literal`() {
        assertEquals(FORBIDDEN, status(get("/preview.mjpg", host = "evil.example.com")))
    }

    /**
     * The download route, added by ADR-0028, gets the guard for free: the plugin
     * intercepts before routing, so a new route is covered the moment it exists.
     * Asserted rather than assumed, because "for free" is exactly what was
     * believed about `staticResources` in the bug above.
     *
     * This route matters more than the others if the guard ever slips. The
     * bundle and the preview leak what the camera is pointed at now; this one
     * hands over the finished footage.
     */
    @Test
    fun `PRD 6_8 - a take download is refused when Host is not an IP literal`() {
        assertEquals(
            FORBIDDEN,
            status(get("/takes/Scenaristo_2026-09-06_14-32-05.mp4", host = "evil.example.com")),
        )
    }

    // The second half of the bug: `call.respond()` inside a `webSocket {}` handler
    // runs after the upgrade has been negotiated, so the answer was 101 followed
    // by a close frame. PRD 6.8 asks for 403, and a guard that has to let the
    // handshake finish first is a guard running too late.
    @Test
    fun `PRD 6_8 - a websocket upgrade is refused before it is negotiated`() {
        val line = status(upgrade("/ws", host = "evil.example.com"))
        assertEquals(FORBIDDEN, line)
        assertNotEquals("101", line.split(" ").getOrNull(1))
    }

    // The positive control, and the one that would catch a guard that refuses
    // everything: a loopback peer with a literal Host still gets its upgrade.
    @Test
    fun `PRD 6_8 - a LAN peer with a literal Host is admitted to the socket`() {
        assertEquals("HTTP/1.1 101", status(upgrade("/ws", host = "127.0.0.1:$port")))
    }

    // The bundle is not on the unit-test classpath unless `web/dist` was built,
    // so this asserts what the guard decides rather than what the resolver finds:
    // anything but 403 means the request got past the guard.
    @Test
    fun `PRD 6_8 - a LAN peer with a literal Host is admitted to the bundle`() {
        assertNotEquals(FORBIDDEN, status(get("/", host = "127.0.0.1:$port")))
    }

    private fun get(path: String, host: String): List<String> = listOf(
        "GET $path HTTP/1.1",
        "Host: $host",
        "Connection: close",
    )

    private fun upgrade(path: String, host: String): List<String> = listOf(
        "GET $path HTTP/1.1",
        "Host: $host",
        "Upgrade: websocket",
        "Connection: Upgrade",
        // A fixed key is fine: nothing here checks the accept hash, only the code.
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
        "Sec-WebSocket-Version: 13",
    )

    /**
     * The status line, cut to `HTTP/1.1 NNN` so the reason phrase cannot make a
     * test fail on wording.
     */
    private fun status(request: List<String>): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.getOutputStream().apply {
                write((request.joinToString("\r\n") + "\r\n\r\n").toByteArray())
                flush()
            }
            // Only the first line is read, and the socket is closed straight
            // after: `/preview.mjpg` never ends on its own, and an admitted
            // WebSocket would sit there until the ping timeout.
            val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
            return line.split(" ").take(2).joinToString(" ")
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

    /**
     * A port nothing is using, released again before the server claims it.
     *
     * Racy in principle and not in practice, and the alternative -- binding port
     * 0 and asking the engine what it got -- would mean widening `ControlServer`'s
     * API for a test.
     */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun idle() = State(
        settings = CaptureSettings(
            grid = GridFrequency.HZ_50,
            shutterHz = 50,
            iso = 100,
            whiteBalanceKelvin = 5600,
            lensId = "0",
        ),
        recording = RecordingState(recording = false),
        device = DeviceStatus(
            batteryPercent = 80,
            charging = false,
            thermal = ThermalState.NOMINAL,
            storageMinutesRemaining = 120,
        ),
        serverTimeMs = 0,
    )

    private companion object {
        const val FORBIDDEN = "HTTP/1.1 403"
        const val TIMEOUT_MS = 5_000
    }
}
