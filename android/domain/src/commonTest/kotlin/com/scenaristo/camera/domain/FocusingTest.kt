package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.protocol.Focus
import com.scenaristo.camera.domain.protocol.FocusMode
import com.scenaristo.camera.domain.protocol.focusAfterTap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PRD 6.1's "lockable", as the interaction Davide chose on 2026-09-06: tap to
 * lock, tap the same spot again to release.
 *
 * Shared with the browser (PRD 6.8, ADR-0013), which is why the rule is here and
 * not in the phone's gesture handler.
 */
class FocusingTest {

    // PRD 6.1: "Continuous AF with face priority, lockable."
    @Test
    fun `PRD 6_1 - a tap while continuous locks focus at the tap`() {
        val locked = focusAfterTap(Focus(), x = 0.3, y = 0.7)
        assertEquals(FocusMode.LOCKED, locked.mode)
        assertEquals(0.3, locked.x)
        assertEquals(0.7, locked.y)
    }

    // The other half of "lockable": a lock nobody can clear is a feature that
    // silently disables face priority, which is the whole point of the app.
    @Test
    fun `PRD 6_1 - tapping the locked point again returns to continuous`() {
        val locked = focusAfterTap(Focus(), x = 0.3, y = 0.7)
        val released = focusAfterTap(locked, x = 0.3, y = 0.7)

        assertEquals(FocusMode.CONTINUOUS, released.mode)
        assertNull(released.x)
        assertNull(released.y)
    }

    // A finger does not land twice on the same pixel, so "again" has to mean
    // "near enough" -- the radius is about the size of the reticle drawn.
    @Test
    fun `PRD 6_1 - a tap near the locked point still counts as tapping it again`() {
        val locked = focusAfterTap(Focus(), x = 0.5, y = 0.5)
        val released = focusAfterTap(locked, x = 0.53, y = 0.47)

        assertEquals(FocusMode.CONTINUOUS, released.mode)
    }

    // Re-aiming must not cost two taps. A tap across the frame is plainly not
    // "again", so it moves the lock rather than releasing it.
    @Test
    fun `PRD 6_1 - a tap elsewhere moves the lock rather than releasing it`() {
        val locked = focusAfterTap(Focus(), x = 0.2, y = 0.2)
        val moved = focusAfterTap(locked, x = 0.8, y = 0.6)

        assertEquals(FocusMode.LOCKED, moved.mode)
        assertEquals(0.8, moved.x)
        assertEquals(0.6, moved.y)
    }

    // A lock with no point is "hold it where it is" (PRD 6.1). There is no point
    // to tap again, so a tap aims it rather than releasing it.
    @Test
    fun `PRD 6_1 - a tap under a point-less lock aims it`() {
        val held = Focus(mode = FocusMode.LOCKED)
        val aimed = focusAfterTap(held, x = 0.4, y = 0.4)

        assertEquals(FocusMode.LOCKED, aimed.mode)
        assertEquals(0.4, aimed.x)
    }
}
