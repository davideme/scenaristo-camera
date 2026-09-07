package com.scenaristo.camera.server

import com.scenaristo.camera.domain.protocol.ClientMessage
import com.scenaristo.camera.domain.protocol.Command
import com.scenaristo.camera.domain.protocol.Hello
import com.scenaristo.camera.domain.protocol.Platform
import com.scenaristo.camera.domain.protocol.ProtocolJson
import com.scenaristo.camera.domain.protocol.ServerMessage
import com.scenaristo.camera.domain.protocol.Session
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
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
 * Wi-Fi-to-hotspot transition (ADR-0006). That per-request check is [LanGuard],
 * installed below on the whole application rather than route by route; the rule
 * it applies lives in `:domain` so iOS applies the identical one in Phase 4.
 *
 * Whether the port opens at all is not this class's decision: ADR-0026 has the
 * caller hold it closed unless the phone is on a local network, which is the one
 * thing the per-request rule cannot check — an address is admitted for its shape,
 * and a carrier's RFC 1918 subscriber address has the same shape as a laptop's.
 */
class ControlServer(
    private val session: Session,
    private val frames: PreviewFrames,
    private val port: Int = ConnectionUrl.DEFAULT_PORT,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Told how many browsers are pulling the preview, whenever that changes
     * (ADR-0025). The service uses it to stop encoding for nobody.
     *
     * Runs on a Ktor thread, so it must not block.
     */
    onViewersChanged: (Int) -> Unit = {},
    /**
     * Asked for a JPEG quality when the link's behaviour says it should change
     * (PRD 6.8, ADR-0008). Default does nothing, so a caller that does not care
     * about degradation gets the old behaviour.
     *
     * A callback rather than a reference to the encoder, because `:server` must
     * not know what produces the frames -- `PreviewFrames` is deliberately the
     * whole of that contract, and Phase 4's iOS server implements the same one.
     */
    private val onQualityChanged: (Int) -> Unit = {},
) {
    private val clients = CopyOnWriteArraySet<Client>()

    /**
     * Browsers pulling `/preview.mjpg`, which is not the same set as [clients]
     * (ADR-0025).
     *
     * Private because the count leaves through [onViewersChanged] and nothing
     * needs to poll it: every consumer so far cares about the *edges* -- the
     * first viewer arriving, the last one leaving -- rather than the number.
     */
    private val viewers = ViewerCount(onViewersChanged)

    /**
     * PRD 6.8's degradation: quality follows the link rather than the preview
     * freezing. One instance for the server, because there is one encoder and
     * therefore one quality -- see [PreviewQuality].
     */
    private val quality = PreviewQuality(FRAME_INTERVAL_MS)
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

    /**
     * Opens the port, if it is not open already.
     *
     * Idempotent because ADR-0026 has the caller start and stop this as the
     * phone joins and leaves a local network, and "is it running" is a question
     * only this class can answer without racing itself.
     */
    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            // First, and at the application level rather than per route: ADR-0006
            // asks for one plugin, and one plugin is what makes the rule hold for
            // the static bundle, for `/ws` before it upgrades, and for whatever
            // route is added next without anyone rereading this file.
            install(LanGuard)
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
                get("/preview.mjpg") { streamPreview(call) }
                webSocket("/ws") { serve() }
            }
        }.also { it.start(wait = false) }
    }

    /**
     * Closes the port and drops every client, if it is open.
     *
     * The stream and socket handlers' `finally` blocks run as the engine winds
     * down, so the viewer and client counts fall to zero on their own -- which
     * matters, because those counts are what ADR-0025 keeps the camera awake for
     * and what ADR-0019 keeps the service alive for.
     */
    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        engine = null
    }

    /** Whether the port is open (ADR-0026). */
    val listening: Boolean get() = engine != null

    /**
     * One browser's preview stream, counted for its whole life (ADR-0025).
     *
     * The count is taken before the body starts and dropped in a `finally`,
     * because every way this ends -- the tab closing, the laptop sleeping, Wi-Fi
     * dropping mid-frame -- arrives as either a closed channel or a throw out of
     * the frame write, and only a `finally` catches both. A viewer that is counted
     * forever is a preview encoder that never stops.
     */
    private suspend fun streamPreview(call: ApplicationCall) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        viewers.enter()
        try {
            streamFrames(call)
        } finally {
            viewers.leave()
        }
    }

    private suspend fun streamFrames(call: ApplicationCall) {
        val pacer = FramePacer(FRAME_INTERVAL_MS)
        // The frame last put on the wire, by identity. `latest()` hands back the
        // same array until the producer replaces it, so without this the loop
        // re-sends a frame the client already has whenever it comes back before
        // a new one was encoded -- paying full bandwidth for a duplicate. It
        // became reachable when the pacing tightened below.
        var sent: ByteArray? = null
        call.respondBytesWriter(contentType = io.ktor.http.ContentType.parse(Mjpeg.CONTENT_TYPE)) {
            // The channel closes when the browser navigates away or the tab is
            // shut, which is the only signal that a viewer has gone.
            while (!isClosedForWrite) {
                val jpeg = frames.latest()
                if (jpeg == null || jpeg === sent) {
                    delay(FRAME_POLL_MS)
                    continue
                }
                val startedAt = now()
                writeFully(Mjpeg.partHeader(jpeg.size))
                writeFully(jpeg)
                // The suspending write is the backpressure: a slow client stalls
                // here rather than queueing stale frames (ADR-0008). Unchanged
                // by the pacing below -- what changed is only that the wait
                // afterwards is the remainder of the interval rather than a
                // fixed sleep added on top of however long the write took.
                flush()
                sent = jpeg
                val finishedAt = now()
                // How long that write took is the only view this server has of
                // the link, and it is enough: a write outlasting the frame
                // interval means frames are arriving faster than the socket
                // drains.
                if (quality.onFrameWritten(finishedAt - startedAt)) {
                    onQualityChanged(quality.quality)
                }
                val wait = pacer.afterSend(finishedAt)
                if (wait > 0) delay(wait)
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

    /**
     * Applies a command that came from the phone's own screen rather than from a
     * remote (PRD 6.9).
     *
     * The phone goes through the same path a browser does -- same lock, same
     * idempotency, same broadcast -- because ADR-0007's premise is one state
     * document with one writer. A phone that mutated `Session` directly would be
     * a second writer, and the first symptom would be a remote whose Record
     * button disagrees with the phone's.
     */
    suspend fun applyLocal(command: Command) {
        val outcome = lock.withLock { session.apply(command, now()) }
        if (outcome.broadcast) broadcastSnapshot()
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
