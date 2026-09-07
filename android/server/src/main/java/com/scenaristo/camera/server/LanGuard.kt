package com.scenaristo.camera.server

import com.scenaristo.camera.domain.net.LanOnly
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.response.respond

/**
 * ADR-0006's LAN-only rule, as the application plugin that ADR specifies:
 * "enforce LAN-only at request time with one Ktor application plugin".
 *
 * It intercepts the `Plugins` phase, which every request passes through before
 * routing resolves anything, so the coverage is the server rather than a list of
 * routes somebody remembered to annotate. That distinction is not academic: the
 * per-route version of this check missed `staticResources("/", "web")`, and the
 * whole web bundle was served to a request carrying an attacker's `Host` header
 * — the one request PRD 6.8 names as the thing to refuse.
 *
 * The refusal uses `finish()` rather than only responding, because a response
 * alone does not stop the pipeline: without it the WebSocket route would still
 * run and negotiate the upgrade. That was the second half of the same bug — a
 * rebound `/ws` answered 101 and only then closed, where PRD 6.8 asks for 403.
 *
 * The rule itself is [LanOnly], in `:domain`, so iOS applies the identical one
 * in Phase 4 (ADR-0013). This file is only where it is applied.
 */
val LanGuard: ApplicationPlugin<Unit> = createApplicationPlugin(name = "LanGuard") {
    application.intercept(ApplicationCallPipeline.ApplicationPhase.Plugins) {
        if (LanOnly.allows(call.request.origin.remoteAddress, call.request.host())) return@intercept
        call.respond(HttpStatusCode.Forbidden, REFUSAL)
        // Terminates the pipeline. Everything after this phase -- routing, the
        // static content resolver, the WebSocket upgrade -- is never reached.
        finish()
    }
}

/**
 * What a refused request is told.
 *
 * Deliberately says nothing about which of the two checks failed: a probe from
 * off-LAN learns only that the door is shut.
 */
internal const val REFUSAL = "LAN only"
