package com.scenaristo.camera.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PRD 6.11: "Download the last recording (or any recording from this session)
 * from the web UI."
 *
 * The listing half. [TakeFolder] is a pure function of a directory precisely so
 * these rules can be asserted without a service, a camera or a device -- and
 * they are rules a device test would be a poor way to check anyway: getting
 * eleven takes onto a phone to prove the cap holds is a slow way to ask a
 * question about `take(10)`.
 */
class TakeFolderTest {

    @get:Rule val temp = TemporaryFolder()

    /** Duration is the one field the folder cannot supply, so the real reader is Android's. */
    private fun folder(dir: File = temp.root) = TakeFolder(dir) { 1_000L }

    /**
     * Written-at times are set explicitly rather than left to the file system:
     * files created in a loop can share a millisecond, and a test that sorts by
     * a tie is a test that passes for the wrong reason.
     */
    private fun take(name: String, sizeBytes: Int = 8, writtenAtMs: Long): File =
        File(temp.root, "$name.mp4").apply {
            writeBytes(ByteArray(sizeBytes))
            check(setLastModified(writtenAtMs))
        }

    @Test
    fun `PRD 6_11 - an empty folder lists nothing`() {
        assertEquals(emptyList<Any>(), folder().list())
    }

    @Test
    fun `PRD 6_11 - a missing folder lists nothing rather than throwing`() {
        assertEquals(emptyList<Any>(), folder(File(temp.root, "never-created")).list())
    }

    @Test
    fun `PRD 6_11 - a take carries its name, size and written-at time`() {
        take("Scenaristo_2026-09-06_14-32-05", sizeBytes = 4096, writtenAtMs = 1_700_000_000_000)

        val take = folder().list().single()

        assertEquals("Scenaristo_2026-09-06_14-32-05", take.name)
        assertEquals(4096L, take.sizeBytes)
        assertEquals(1_700_000_000_000L, take.recordedAtMs)
        assertEquals(1_000L, take.durationMs)
    }

    /**
     * By when the file was written, not by name. The name is the moment
     * recording *started*, so a twenty-minute take begun at 14:00 finishes after
     * a two-second one begun at 14:10 -- sorting by name puts them in the order
     * they were started, which is not the order the user just saw them happen.
     */
    @Test
    fun `PRD 6_11 - takes are newest first, by when they finished`() {
        take("Scenaristo_2026-09-06_14-00-00", writtenAtMs = 3_000) // long take, finished last
        take("Scenaristo_2026-09-06_14-10-00", writtenAtMs = 2_000)

        assertEquals(
            listOf("Scenaristo_2026-09-06_14-00-00", "Scenaristo_2026-09-06_14-10-00"),
            folder().list().map { it.name },
        )
    }

    @Test
    fun `PRD 6_11 - only the newest ten are listed`() {
        repeat(14) { take("Scenaristo_2026-09-06_14-00-%02d".format(it), writtenAtMs = 1_000L + it) }

        val listed = folder().list()

        assertEquals(TakeFolder.LIMIT, listed.size)
        assertEquals("Scenaristo_2026-09-06_14-00-13", listed.first().name)
        assertEquals("Scenaristo_2026-09-06_14-00-04", listed.last().name)
    }

    /**
     * The spike screen writes `take-<epoch>.mp4` into the same directory
     * (`InteropEchoScreen`), and a user can drop anything there over USB. Only
     * PRD 6.7's shape is a take.
     */
    @Test
    fun `PRD 6_11 - anything that is not a take is not listed`() {
        take("Scenaristo_2026-09-06_14-32-05", writtenAtMs = 5_000)
        File(temp.root, "take-1788773821787.mp4").writeBytes(ByteArray(8))
        File(temp.root, "holiday.mp4").writeBytes(ByteArray(8))
        File(temp.root, "Scenaristo_2026-09-06_14-32-05.txt").writeBytes(ByteArray(8))
        File(temp.root, "Scenaristo_2026-09-06_14-32-05").writeBytes(ByteArray(8))
        File(temp.root, "notes").mkdir()

        assertEquals(listOf("Scenaristo_2026-09-06_14-32-05"), folder().list().map { it.name })
    }

    /**
     * The file exists for the whole of a recording -- CameraX writes
     * progressively, which is what makes #17's force-killed take playable -- so
     * a listing has to be told which one is still being written.
     */
    @Test
    fun `PRD 6_11 - the take being recorded is not listed`() {
        take("Scenaristo_2026-09-06_14-32-05", writtenAtMs = 5_000)
        take("Scenaristo_2026-09-06_14-40-00", writtenAtMs = 6_000)

        val listed = folder().list(inProgress = "Scenaristo_2026-09-06_14-40-00")

        assertEquals(listOf("Scenaristo_2026-09-06_14-32-05"), listed.map { it.name })
    }

    @Test
    fun `PRD 6_11 - open resolves a take that exists`() {
        val file = take("Scenaristo_2026-09-06_14-32-05", writtenAtMs = 5_000)

        assertEquals(file, folder().open("Scenaristo_2026-09-06_14-32-05"))
    }

    @Test
    fun `PRD 6_11 - open returns null for a take that does not exist`() {
        assertNull(folder().open("Scenaristo_2026-09-06_14-32-05"))
    }

    /**
     * PRD 6.8. This is the method that turns a string a client on the LAN chose
     * into a path, so it is the one place traversal has to be stopped.
     *
     * The escape it guards against is not hypothetical: `TakeName.PATTERN` is
     * unanchored, so every name below contains a legitimate take name and would
     * be accepted by `containsMatchIn` in place of `matches`.
     */
    @Test
    fun `PRD 6_8 - open refuses a name that is a path`() {
        // The takes live one level down, and a file with a *valid take name*
        // sits outside that directory. Without the escape being reachable the
        // assertion proves nothing: a traversal that resolves to nothing returns
        // null whether the guard works or not.
        val takes = File(temp.root, "Movies").apply { mkdir() }
        val inside = File(takes, "Scenaristo_2026-09-06_14-32-05.mp4")
            .apply { writeBytes(ByteArray(8)) }
        val outside = File(temp.root, "Scenaristo_2026-09-06_09-00-00.mp4")
            .apply { writeBytes(ByteArray(8)) }
        val folder = TakeFolder(takes) { 1_000L }

        val traversals = listOf(
            "../Scenaristo_2026-09-06_09-00-00",
            "./../Scenaristo_2026-09-06_09-00-00",
            "Scenaristo_2026-09-06_14-32-05/../../Scenaristo_2026-09-06_09-00-00",
            "/Scenaristo_2026-09-06_14-32-05",
            "Scenaristo_2026-09-06_14-32-05.mp4",
            "",
        )
        traversals.forEach { assertNull("resolved $it", folder.open(it)) }

        // Positive controls. Without these the test would pass on an `open` that
        // returned null for everything, and the escape it names has to be a real
        // escape or the negatives are vacuous.
        assertEquals(inside, folder.open("Scenaristo_2026-09-06_14-32-05"))
        assertTrue(
            "the file outside the takes folder must be reachable by traversal, or this proves nothing",
            File(takes, "../${outside.name}").isFile,
        )
    }
}
