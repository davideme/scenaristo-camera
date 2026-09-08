package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.exposure.Histogram
import com.scenaristo.camera.domain.lens.LensAdvice
import com.scenaristo.camera.domain.lens.TStop
import com.scenaristo.camera.domain.lens.adviceFor
import com.scenaristo.camera.domain.protocol.Ack
import com.scenaristo.camera.domain.protocol.ClientMessage
import com.scenaristo.camera.domain.protocol.Command
import com.scenaristo.camera.domain.protocol.CommandName
import com.scenaristo.camera.domain.protocol.AudioInput
import com.scenaristo.camera.domain.protocol.FocusMode
import com.scenaristo.camera.domain.protocol.Hello
import com.scenaristo.camera.domain.protocol.Nack
import com.scenaristo.camera.domain.protocol.NackReason
import com.scenaristo.camera.domain.protocol.PROTOCOL_VERSION
import com.scenaristo.camera.domain.protocol.Platform
import com.scenaristo.camera.domain.protocol.ProtocolJson
import com.scenaristo.camera.domain.protocol.ServerMessage
import com.scenaristo.camera.domain.protocol.KeySide
import com.scenaristo.camera.domain.protocol.State
import com.scenaristo.camera.domain.protocol.StateMessage
import com.scenaristo.camera.domain.protocol.StudioLook
import com.scenaristo.camera.domain.protocol.ThermalState
import com.scenaristo.camera.domain.protocol.VideoCodec
import com.scenaristo.camera.domain.protocol.Warning
import com.scenaristo.camera.domain.recording.TakeName
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    // ADR-0007: the first server message is {type:"hello", protocol:2, app, platform}.
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
        // PRD 6.6: the meter is on both surfaces, so it is in the snapshot both
        // surfaces read -- and `metering` is what lets a browser tell a quiet
        // room from a meter that is not running.
        assertEquals(AudioInput.WIRED, state.audio.input)
        assertEquals(0.42, state.audio.level, absoluteTolerance = 1e-9)
        assertTrue(state.audio.metering)
        assertFalse(state.audio.clipping)
        assertEquals(listOf(Warning.TOO_DARK), state.warnings)
        assertEquals(2, state.clients)
        // PRD 6.7: the codec in use is displayed on phone and web *before*
        // recording, so it is state rather than something reported at the end.
        assertEquals(VideoCodec.HEVC, state.encoding.codec)
        assertEquals(3840, state.encoding.widthPx)
        assertEquals(2160, state.encoding.heightPx)
        assertEquals(30, state.encoding.frameRate)
        assertEquals(45_000_000, state.encoding.bitrate)
        // #97: the exposure aids. Negative is under-exposed, which is the
        // opposite sign to the loop's own `errorEv` and the reason that
        // conversion has a test of its own.
        assertEquals(-0.4, state.exposure.stopsFromTarget, absoluteTolerance = 1e-9)
        assertTrue(state.exposure.metering)
        assertEquals(
            Histogram.BINS,
            state.exposure.histogram.size,
            "the fixture must carry a full histogram, not a truncated one",
        )
        // #101: the lens as an optic. The T-stop is derived, never sent -- it is
        // an assumption about transmission (TStop.TRANSMISSION), and a derived
        // number on the wire is one iOS could derive differently.
        assertEquals(24, state.optics.equivalentFocalLengthMm)
        assertEquals(1.7, state.optics.apertureFNumber!!, absoluteTolerance = 1e-9)
        assertEquals(1.7 / kotlin.math.sqrt(TStop.TRANSMISSION), TStop.of(1.7)!!, absoluteTolerance = 1e-12)
        // #77: the lens list is a list of framings, because a phone's other
        // lenses are reached by zoom ratio and not as separate cameras. These
        // are the reference device's own, measured 2026-09-06.
        assertEquals(4, state.lenses.size)
        assertEquals(listOf(13, 24, 48, 120), state.lenses.map { it.equivalentFocalLengthMm })
        assertEquals(1.0, state.settings.zoomRatio, absoluteTolerance = 1e-9)
        // PRD 6.5's list exists to move people off the wide lens, and on this
        // device only zooming reaches the recommended band.
        assertEquals(
            LensAdvice.WIDE_DISTANCE_GUIDANCE,
            adviceFor(state.lenses.first { it.zoomRatio == 1.0 }.equivalentFocalLengthMm),
        )
        assertEquals(
            LensAdvice.RECOMMENDED_FOR_TALKING_HEAD,
            adviceFor(state.lenses.first { it.zoomRatio == 5.0 }.equivalentFocalLengthMm),
        )
        // PRD 6.11's mount level, and the reason it reads as nothing here: this
        // fixture is a snapshot of a take in progress, and the accelerometer is
        // deliberately not running during one (ADR-0023). `measuring = false`
        // with a take running is the promise, in the golden file, where a change
        // to it has to be argued for rather than merged.
        assertFalse(state.mount.measuring)
        assertEquals(0.0, state.mount.rollDegrees, absoluteTolerance = 1e-9)
        // PRD 6.11's lighting read, false here for the same reason (ADR-0028):
        // it exists so somebody can move a lamp, and a take in progress is
        // exactly when nobody is going to. `enoughLight` survives because it is
        // read off ISO rather than off a face, and stays true of the room.
        assertFalse(state.lighting.measuring)
        assertEquals(0, state.lighting.keyRatioTenths)
        assertEquals(KeySide.NONE, state.lighting.keySide)
        assertNull(state.lighting.backgroundStopsTenths)
        assertTrue(state.lighting.enoughLight)
        // PRD 6.11: a look is chosen once and remembered, and OFF is the product's
        // own answer -- the app records what is in front of the lens until asked
        // otherwise. The golden file pins that default rather than leaving it to
        // whichever platform decodes the snapshot.
        assertEquals(StudioLook.OFF, state.settings.studioLook)
        // PRD 6.10, ADR-0029: what a studio look would record at *here*. The two
        // together are the point, and the fixture carries the reference Pixel 10's
        // pair: this device records UHD, and cannot do it with an analysis stream
        // beside it. A platform that reported one without the other would be
        // telling a user they can have both.
        assertTrue(state.capabilities.uhd30)
        assertEquals(1080, state.capabilities.analysisRecordingHeight)
        // PRD 6.7's naming, which UI-9's transport row shows next to the timecode.
        assertEquals("Scenaristo_2026-09-06_14-32-05", state.recording.fileName)
        assertTrue(
            TakeName.PATTERN.matches(state.recording.fileName!!),
            "the fixture's own name must be the shape :domain produces",
        )
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
