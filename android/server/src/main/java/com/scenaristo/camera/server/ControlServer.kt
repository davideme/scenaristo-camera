package com.scenaristo.camera.server

import com.scenaristo.camera.domain.net.LanOnly
import com.scenaristo.camera.domain.protocol.ClientMessage
import com.scenaristo.camera.domain.protocol.Command
import com.scenaristo.camera.domain.protocol.Hello
import com.scenaristo.camera.domain.protocol.Platform
import com.scenaristo.camera.domain.protocol.ProtocolJson
import com.scenaristo.camera.domain.protocol.ServerMessage
import com.scenaristo.camera.domain.protocol.Session
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.writeFully
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArraySet

/** A source of JPEG frames for the preview stream (ADR-0008, fed by the tap of ADR-0018). */
fun interface PreviewFrames {
    /**
     * The newest frame, or null when none is ready. Called at the producer's
     * pace; implementations must not block waiting for a fresh frame.
     */
    fun latest(): ByteArray?
}

/**
 * The phone's local server: one WebSocket for control, one HTTP route for
 * preview (ADR-0006, ADR-0007, ADR-0008).
 *
 * It binds once to every interface and enforces LAN-only per request, because
 * Ktor fixes its connectors when the server is built — binding per interface
 * would mean restarting the server, and dropping every WebSocket, on each
 * Wi-Fi-to-hotspot transition (ADR-0006). The admission rule itself lives in
 * `:domain` so iOS applies the identical one in Phase 4.
 */
class ControlServer(
    private val session: Session,
    private val frames: PreviewFrames,
    private val port: Int = ConnectionUrl.DEFAULT_PORT,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val clients = CopyOnWriteArraySet<Client>()
    private val lock = Mutex()
    private var engine: EmbeddedServer<*, *>? = null

    /**
     * One attached browser.
     *
     * The outbox is UNLIMITED rather than CONFLATED, which matters: acks and
     * snapshots share it, and conflation would silently drop an ack whenever a
     * snapshot arrived right behind it — the client would then wait forever for
     * an answer it was never going to get. Unbounded growth is bounded in
     * practice by the ping timeout, which closes a stuck client within 4 s.
     */
    private class Client(val outbox: Channel<String> = Channel(Channel.UNLIMITED))

    fun start() {
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                // RFC 6455 pings, which browsers answer with no JavaScript. On
                // timeout Ktor closes the session and the handler's finally block
                // decrements the client count (ADR-0007). The ADR writes these as
                // 2.seconds / 4.seconds; Ktor 3.5 takes milliseconds.
                pingPeriodMillis = 2_000
                timeoutMillis = 4_000
            }
            routing {
                // The UI itself, from the bundle inside the APK (ADR-0009). This
                // is what makes the remote zero-install: the laptop types an IP
                // and gets a page, with nothing to download and no store.
                staticResources("/", "web") { default("index.html") }
                get("/preview.mjpg") {
                    if (!admit(call)) return@get
                    streamPreview(call)
                }
                webSocket("/ws") {
                    if (!admit(call)) return@webSocket
                    serve()
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        engine = null
    }

    /**
     * Both checks from ADR-0006, applied to every request rather than at bind
     * time. The `Host` literal check is the one that defeats DNS rebinding: the
     * phone's own URLs are always IP literals, and a rebinding attack necessarily
     * arrives with a hostname.
     */
    private suspend fun admit(call: ApplicationCall): Boolean {
        val remote = call.request.origin.remoteAddress
        val host = call.request.host()
        if (LanOnly.allows(remote, host)) return true
        call.respond(HttpStatusCode.Forbidden, "LAN only")
        return false
    }

    private suspend fun streamPreview(call: ApplicationCall) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondBytesWriter(contentType = io.ktor.http.ContentType.parse(Mjpeg.CONTENT_TYPE)) {
            // The channel closes when the browser navigates away or the tab is
            // shut, which is the only signal that a viewer has gone.
            while (!isClosedForWrite) {
                val jpeg = frames.latest()
                if (jpeg == null) {
                    delay(FRAME_POLL_MS)
                    continue
                }
                writeFully(Mjpeg.partHeader(jpeg.size))
                writeFully(jpeg)
                // The suspending write is the backpressure: a slow client stalls
                // here rather than queueing stale frames (ADR-0008).
                flush()
                delay(FRAME_INTERVAL_MS)
            }
            writeFully(Mjpeg.tail())
        }
    }

    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.serve() {
        val client = Client()
        clients += client
        broadcastClientCount()
        try {
            send(Frame.Text(encode(Hello(app = APP_NAME, platform = Platform.ANDROID))))
            send(Frame.Text(encode(session.snapshot())))

            val pump = launch {
                for (text in client.outbox) send(Frame.Text(text))
            }
            try {
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    handle(client, text)
                }
            } finally {
                pump.cancel()
            }
        } finally {
            clients -= client
            broadcastClientCount()
        }
    }

    private suspend fun handle(client: Client, text: String) {
        val message = runCatching { ProtocolJson.decodeFromString<ClientMessage>(text) }.getOrNull()
            ?: return // Unparseable input is dropped, not answered: there is no id to answer to.
        val command = message as? Command ?: return

        val outcome = lock.withLock { session.apply(command, now()) }
        // The ack goes to the client that asked, and only to it: an ack names a
        // command id, and every other browser would be seeing an answer to a
        // question it never asked.
        client.outbox.trySend(encode(outcome.reply))
        if (outcome.broadcast) broadcastSnapshot()
    }

    /** Sends one message to every attached client. */
    private fun broadcast(message: ServerMessage) {
        val text = encode(message)
        clients.forEach { it.outbox.trySend(text) }
    }

    suspend fun broadcastSnapshot() {
        broadcast(lock.withLock { session.snapshot() })
    }

    /**
     * PRD 6.8 shows how many browsers are attached, so the client count is state
     * like any other and goes through the same revisioned path.
     */
    private suspend fun broadcastClientCount() {
        lock.withLock { session.update(now()) { it.copy(clients = clients.size) } }
        broadcast(lock.withLock { session.snapshot() })
    }

    private fun encode(message: ServerMessage): String = ProtocolJson.encodeToString(message)

    private companion object {
        const val APP_NAME = "Scenaristo Camera"

        /** 15 fps cap from ADR-0008; the producer never runs faster than the browser can paint. */
        const val FRAME_INTERVAL_MS = 66L

        /** How long to wait when no frame is ready yet, e.g. before the camera has bound. */
        const val FRAME_POLL_MS = 100L
    }
}
