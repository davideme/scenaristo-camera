package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.protocol.Ack
import com.scenaristo.camera.domain.protocol.ClientMessage
import com.scenaristo.camera.domain.protocol.Command
import com.scenaristo.camera.domain.protocol.CommandName
import com.scenaristo.camera.domain.protocol.FocusMode
import com.scenaristo.camera.domain.protocol.Hello
import com.scenaristo.camera.domain.protocol.Nack
import com.scenaristo.camera.domain.protocol.NackReason
import com.scenaristo.camera.domain.protocol.PROTOCOL_VERSION
import com.scenaristo.camera.domain.protocol.Platform
import com.scenaristo.camera.domain.protocol.ProtocolJson
import com.scenaristo.camera.domain.protocol.ServerMessage
import com.scenaristo.camera.domain.protocol.StateMessage
import com.scenaristo.camera.domain.protocol.ThermalState
import com.scenaristo.camera.domain.protocol.Warning
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the golden fixtures in `docs/protocol/fixtures/` against the `:domain`
 * message classes.
 *
 * This lives in `jvmTest`, not `commonTest`, because reading a file needs a
 * platform API and `commonMain`/`commonTest` stay platform-free (ADR-0015).
 * The fixtures themselves are platform-neutral: Phase 4 adds an iOS runner over
 * the same files, which is what makes them the parity contract (ADR-0013).
 *
 * Every message is decoded through the **sealed** type rather than the concrete
 * one, because that is what a real client does — it reads `type` and dispatches.
 * Decoding straight into `Hello` would pass even if the discriminator were
 * missing, which is exactly the drift that let the old fixture disagree with
 * ADR-0007 unnoticed.
 */
class ProtocolFixtureTest {

    private val fixtures = File(System.getProperty("scenaristo.protocol.fixtures"))

    private fun server(name: String): ServerMessage =
        ProtocolJson.decodeFromString(File(fixtures, name).readText())

    private fun client(name: String): ClientMessage =
        ProtocolJson.decodeFromString(File(fixtures, name).readText())

    @Test
    fun `the fixture directory is where the tests expect it`() {
        assertTrue(fixtures.isDirectory, "fixtures not found at ${fixtures.canonicalPath}")
    }

    // ADR-0007: the first server message is {type:"hello", protocol:1, app, platform}.
    @Test
    fun `ADR-0007 - hello carries the protocol version and platform`() {
        val hello = server("hello.json") as Hello
        assertEquals(PROTOCOL_VERSION, hello.protocol)
        assertEquals("Scenaristo Camera", hello.app)
        assertEquals(Platform.ANDROID, hello.platform)
    }

    // The state document is the one every browser mirrors; if its shape drifts,
    // the web UI and the Phase 4 iOS server drift with it.
    @Test
    fun `ADR-0007 - a state snapshot decodes with its revision and contents`() {
        val message = server("state.json") as StateMessage
        assertEquals(7, message.rev)

        val state = message.state
        assertEquals(GridFrequency.HZ_50, state.settings.grid)
        assertEquals(100, state.settings.shutterHz, "the flicker-safe step in use, not the default")
        assertEquals(3200, state.settings.whiteBalanceKelvin)
        assertTrue(state.recording.recording)
        assertEquals(1788500000000, state.recording.startedAtMs)
        assertEquals(ThermalState.FAIR, state.device.thermal)
        assertEquals(84, state.device.storageMinutesRemaining)
        assertEquals(FocusMode.LOCKED, state.settings.focus.mode, "focus survives in the snapshot too")
        assertEquals(listOf(Warning.TOO_DARK), state.warnings)
        assertEquals(2, state.clients)
    }

    @Test
    fun `ADR-0007 - a record_start command decodes with no args and no guard`() {
        val command = client("cmd-record-start.json") as Command
        assertEquals(CommandName.RECORD_START, command.name)
        assertEquals(null, command.expectRev, "record start deliberately carries no expectRev")
        assertEquals(null, command.args)
    }

    @Test
    fun `ADR-0007 - a settings command carries expectRev and a partial patch`() {
        val command = client("cmd-settings-set.json") as Command
        assertEquals(CommandName.SETTINGS_SET, command.name)
        assertEquals(7, command.expectRev, "settings changes use the staleness guard")
        assertEquals(GridFrequency.HZ_60, command.args?.grid)
        assertEquals(5600, command.args?.whiteBalanceKelvin)
        assertEquals(null, command.args?.lensId, "an absent field means 'leave it alone'")
    }

    // PRD 6.1 and 6.8's tap-to-focus. The point is normalised in the frame, which
    // is what lets a tap on the browser's 960x540 preview mean the same place as
    // a tap on the phone.
    @Test
    fun `PRD 6_8 - a focus command carries a normalised point and no staleness guard`() {
        val command = client("cmd-focus-set.json") as Command
        assertEquals(CommandName.FOCUS_SET, command.name)
        assertEquals(null, command.expectRev, "focus acts on the latest state, like record")
        assertEquals(null, command.args, "focus is not a settings patch")
        assertEquals(FocusMode.LOCKED, command.focus?.mode)
        assertEquals(0.42, command.focus?.x)
        assertEquals(0.33, command.focus?.y)
    }

    @Test
    fun `ADR-0007 - ack and nack name the command they answer`() {
        val ack = server("ack.json") as Ack
        assertEquals(8, ack.rev)

        val nack = server("nack-stale.json") as Nack
        assertEquals(NackReason.STALE, nack.reason)
        assertEquals(ack.id != nack.id, true, "the fixtures answer different commands")
    }

    // If the encoder drifts from the fixtures, every consumer drifts too. Compare
    // parsed forms so whitespace and key order are not the contract.
    @Test
    fun `every fixture survives a decode-encode-decode cycle unchanged`() {
        for (name in listOf("hello.json", "state.json", "ack.json", "nack-stale.json")) {
            val original = server(name)
            val reencoded = ProtocolJson.encodeToString(original)
            assertEquals(original, ProtocolJson.decodeFromString<ServerMessage>(reencoded), name)
        }
        for (name in listOf("cmd-record-start.json", "cmd-settings-set.json", "cmd-focus-set.json")) {
            val original = client(name)
            val reencoded = ProtocolJson.encodeToString(original)
            assertEquals(original, ProtocolJson.decodeFromString<ClientMessage>(reencoded), name)
        }
    }

    // ADR-0007: "Adding fields is backward compatible." An older client must not
    // fall over when a newer phone sends a field it has never heard of.
    @Test
    fun `ADR-0007 - an unknown field does not break decoding`() {
        val withExtra = File(fixtures, "hello.json").readText()
            .trimEnd()
            .removeSuffix("}") + ""","futureField":"whatever"}"""
        val hello = ProtocolJson.decodeFromString<ServerMessage>(withExtra) as Hello
        assertEquals(PROTOCOL_VERSION, hello.protocol)
    }
}
