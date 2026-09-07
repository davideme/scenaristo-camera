package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.recording.TakeName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PRD 6.7: "Filename: `Scenaristo_YYYY-MM-DD_HH-MM-SS.mp4`." */
class TakeNameTest {

    @Test
    fun `PRD 6_7 - the name is Scenaristo, the date, then the time`() {
        assertEquals(
            "Scenaristo_2026-09-06_14-32-05",
            TakeName.of(2026, 9, 6, 14, 32, 5),
        )
    }

    /**
     * The zero padding is the whole point of a timestamped name: without it
     * `2026-9-6` sorts after `2026-10-1` in the file browser that is the only
     * take list this product has (PRD 6.9).
     */
    @Test
    fun `every field is zero padded`() {
        assertEquals(
            "Scenaristo_2026-01-02_03-04-05",
            TakeName.of(2026, 1, 2, 3, 4, 5),
        )
    }

    @Test
    fun `midnight is 00, not 24`() {
        assertTrue(TakeName.of(2026, 12, 31, 0, 0, 0).endsWith("_00-00-00"))
    }

    @Test
    fun `the pattern matches what of produces`() {
        assertTrue(TakeName.PATTERN.matches(TakeName.of(2026, 9, 6, 14, 32, 5)))
    }

    /** A name a caller assembled by hand, with the PRD's separators swapped. */
    @Test
    fun `the pattern rejects a name with the wrong separators`() {
        assertTrue(!TakeName.PATTERN.matches("Scenaristo_2026-09-06_14:32:05"))
        assertTrue(!TakeName.PATTERN.matches("Scenaristo-2026-09-06-14-32-05"))
    }

    /**
     * PRD 6.11's download route resolves a take by name, so the pattern stopped
     * being only a test aid the moment a client on the LAN could choose the
     * string it is applied to.
     *
     * `matches` and not `containsMatchIn`: the pattern is unanchored, so a name
     * with a real take's name buried in it passes the second and fails the
     * first. That is the difference between a key lookup and a path traversal,
     * and it is one method call wide.
     */
    @Test
    fun `PRD 6_11 - the pattern rejects a name with a path in it`() {
        val traversals = listOf(
            "../Scenaristo_2026-09-06_14-32-05",
            "Scenaristo_2026-09-06_14-32-05/../../etc/passwd",
            "/Scenaristo_2026-09-06_14-32-05",
            "..%2fScenaristo_2026-09-06_14-32-05",
            "",
        )
        traversals.forEach { assertTrue(!TakeName.PATTERN.matches(it), "accepted $it") }
    }

    /**
     * PRD 6.11. The browser builds this from the generated TypeScript and the
     * server routes on it; this is the assertion that both are talking about the
     * same URL.
     */
    @Test
    fun `PRD 6_11 - the download path is the name under takes, with the extension`() {
        assertEquals(
            "/takes/Scenaristo_2026-09-06_14-32-05.mp4",
            TakeName.path(TakeName.of(2026, 9, 6, 14, 32, 5)),
        )
    }
}
