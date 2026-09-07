package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.protocol.PROTOCOL_VERSION
import com.scenaristo.camera.domain.tooling.GenerateProtocolTypes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fails when `web/src/protocol.ts` is not what the `@Serializable` classes would
 * generate today (ADR-0009, #84).
 *
 * ADR-0009 makes generation the mechanism that stops the Kotlin and TypeScript
 * copies of the protocol drifting, but generation only helps if something
 * notices when nobody ran it. Nothing did, and by #83 the committed file was two
 * features behind: `focus`, `focus.set` and the whole of `AudioState` existed in
 * `:domain` and had never reached the browser. That drift is invisible from the
 * web side -- `pnpm run check` type-checks whatever file is there, and a stale
 * file is internally consistent -- so it surfaces as a control that silently
 * does nothing, which is the failure mode this project has already been bitten
 * by once (#56).
 *
 * It is a test rather than a `git diff --exit-code` step in CI so that it fails
 * on the machine where the drift is introduced, before the push, with the
 * command that fixes it. It runs in the `android` job because `:domain` is what
 * invalidates the file; `tools/changed-scopes.sh` also pulls that job in when
 * the generated file itself is edited, which is the other way drift can arrive.
 *
 * `jvmTest`, not `commonTest`: reading a file needs a platform API, and
 * `commonMain`/`commonTest` stay platform-free (ADR-0015).
 */
class GeneratedProtocolTypesTest {

    private val generated = File(System.getProperty("scenaristo.protocol.typescript"))

    @Test
    fun `the generated file is where the build says it is`() {
        assertTrue(generated.isFile, "not found at ${generated.canonicalPath}")
    }

    @Test
    fun `web protocol ts is what the domain classes generate today`() {
        assertEquals(
            GenerateProtocolTypes.render(),
            generated.readText(),
            "web/src/protocol.ts is stale. Regenerate it in the same change that " +
                "moved a @Serializable class:\n" +
                "    cd android && ./gradlew :domain:generateProtocolTypes",
        )
    }

    /**
     * The browser refuses a `hello` whose major it does not know (ADR-0007), and
     * it needs a number to compare against. Emitting it is what stops that
     * number being written down a second time by hand -- which is exactly how
     * the remote control ended up refusing every connection after ADR-0021
     * moved the phone to 2 and the literal in `connection.ts` stayed at 1.
     */
    @Test
    fun `the protocol version is generated, not hand-written`() {
        assertTrue(
            "export const PROTOCOL_VERSION = $PROTOCOL_VERSION;" in GenerateProtocolTypes.render(),
            "the generator must emit the protocol major for the browser to check against",
        )
    }

    /**
     * The check above is only worth having if the generator answers the same
     * thing twice. It walks `SerialDescriptor`s in declaration order and
     * accumulates into a `LinkedHashMap`, so it does; this asserts it, because
     * a CI failure caused by member ordering rather than by content would be
     * worse than the drift it exists to catch.
     */
    @Test
    fun `the generator is deterministic`() {
        assertEquals(GenerateProtocolTypes.render(), GenerateProtocolTypes.render())
    }
}
