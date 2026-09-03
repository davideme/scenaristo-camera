package com.scenaristo.camera.domain.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Protocol major version. The first message the server sends is [Hello]; a client
 * refuses an unknown major. Adding fields is backward compatible, renaming or
 * removing bumps this number. See ADR-0007.
 */
const val PROTOCOL_VERSION: Int = 1

@Serializable
enum class Platform {
    @SerialName("android")
    ANDROID,

    @SerialName("ios")
    IOS,
}

/**
 * First message on `/ws`, sent by the phone before any state snapshot (ADR-0007).
 *
 * These `@Serializable` classes are the single source of truth for the protocol:
 * the TypeScript types in `web/src/protocol.ts` are generated from them
 * (ADR-0009) and the iOS server in Phase 4 is tested against the same fixtures
 * (ADR-0013). Nothing is hand-written twice.
 */
@Serializable
@SerialName("hello")
data class Hello(
    val protocol: Int = PROTOCOL_VERSION,
    val app: String,
    val platform: Platform,
)
