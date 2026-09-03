package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.protocol.Hello
import com.scenaristo.camera.domain.protocol.PROTOCOL_VERSION
import com.scenaristo.camera.domain.protocol.Platform
import kotlinx.serialization.json.Json
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
 */
class ProtocolFixtureTest {

    private val fixtures = File(System.getProperty("scenaristo.protocol.fixtures"))
    private val json = Json { encodeDefaults = true; prettyPrint = true }

    @Test
    fun `the fixture directory is where the tests expect it`() {
        assertTrue(fixtures.isDirectory, "fixtures not found at ${fixtures.canonicalPath}")
    }

    // ADR-0007: the first server message is {type:"hello", protocol:1, app, platform}.
    @Test
    fun `ADR-0007 - hello_json decodes to the domain type`() {
        val hello = Json.decodeFromString<Hello>(File(fixtures, "hello.json").readText())
        assertEquals(PROTOCOL_VERSION, hello.protocol)
        assertEquals("Scenaristo Camera", hello.app)
        assertEquals(Platform.ANDROID, hello.platform)
    }

    // If the encoder drifts from the fixture, the web UI and the future iOS
    // server drift too. Compare parsed forms so formatting is not the contract.
    @Test
    fun `ADR-0007 - hello_json survives a decode-encode-decode cycle unchanged`() {
        val file = File(fixtures, "hello.json")
        val original = Json.decodeFromString<Hello>(file.readText())
        val reencoded = json.encodeToString(original)
        assertEquals(original, Json.decodeFromString<Hello>(reencoded))
    }
}
