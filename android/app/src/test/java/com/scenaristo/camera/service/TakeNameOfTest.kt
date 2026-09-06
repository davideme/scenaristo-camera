package com.scenaristo.camera.service

import com.scenaristo.camera.domain.recording.TakeName
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRD 6.7: takes are named `Scenaristo_YYYY-MM-DD_HH-MM-SS.mp4`.
 *
 * `:domain` owns the shape and tests it there. What is tested here is the one
 * thing only this side can get wrong: handing `LocalDateTime`'s fields across in
 * the wrong order. `monthValue` and `dayOfMonth` are adjacent integers of
 * indistinguishable type, and the mistake is invisible for eleven days of every
 * month.
 */
class TakeNameOfTest {

    @Test
    fun `the month is the month and the day is the day`() {
        assertEquals(
            "Scenaristo_2026-09-06_14-32-05",
            takeNameOf(LocalDateTime.of(2026, 9, 6, 14, 32, 5)),
        )
    }

    @Test
    fun `it produces the shape domain specifies`() {
        assertTrue(TakeName.PATTERN.matches(takeNameOf(LocalDateTime.of(2026, 1, 1, 0, 0, 0))))
    }
}
