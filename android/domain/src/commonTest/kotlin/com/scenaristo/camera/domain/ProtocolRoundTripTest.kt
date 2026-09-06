package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.protocol.Hello
import com.scenaristo.camera.domain.protocol.PROTOCOL_VERSION
import com.scenaristo.camera.domain.protocol.Platform
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolRoundTripTest {

    private val json = Json { encodeDefaults = true }

    // ADR-0007: the first server message is
    // {type:"hello", protocol:2, app, platform}, and the client refuses
    // unknown majors -- so the version must be on the wire.
    @Test
    fun `ADR-0007 - hello serialises with an explicit protocol version`() {
        val encoded = json.encodeToString(Hello(app = "Scenaristo Camera", platform = Platform.ANDROID))
        assertTrue(
            encoded.contains("\"protocol\":$PROTOCOL_VERSION"),
            "protocol version missing from: $encoded",
        )
        assertTrue(encoded.contains("\"platform\":\"android\""), "platform discriminator wrong: $encoded")
    }

    @Test
    fun `ADR-0007 - hello round-trips unchanged`() {
        val original = Hello(app = "Scenaristo Camera", platform = Platform.ANDROID)
        assertEquals(original, json.decodeFromString<Hello>(json.encodeToString(original)))
    }

    // ADR-0007: "Adding fields is backward compatible." A client on the current
    // must tolerate a field a later minor version adds.
    @Test
    fun `ADR-0007 - an unknown field does not break a current-version client`() {
        val lenient = Json { ignoreUnknownKeys = true }
        val fromFuture = """{"protocol":$PROTOCOL_VERSION,"app":"Scenaristo Camera","platform":"android","batteryPct":91}"""
        assertEquals(PROTOCOL_VERSION, lenient.decodeFromString<Hello>(fromFuture).protocol)
    }
}
