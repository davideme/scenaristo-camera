package com.scenaristo.camera.domain.protocol

import kotlin.math.abs

/**
 * What a tap on the preview means (PRD 6.1, decision 2026-09-06, Davide).
 *
 * "Continuous AF with face priority, **lockable**" leaves the interaction open,
 * and the answer chosen is the one a phone camera usually gives: a tap focuses
 * there and locks; tapping the same place again releases back to continuous.
 *
 * It lives in `:domain` because the browser has to mean the same thing by a tap
 * as the phone does (PRD 6.8, ADR-0013). Two implementations of "was that the
 * same spot?" would drift, and the drift would show up as a remote that cannot
 * unlock focus the phone locked.
 *
 * The rule, in order:
 *
 * - Locked, and the tap lands **on the existing point** → release to continuous.
 *   That is the "tap again" half, and it is why the radius exists at all: a
 *   finger does not land twice on the same pixel.
 * - Locked, and the tap lands **elsewhere** → move the lock there. Re-aiming
 *   should not cost two taps, and a tap across the frame is plainly not "again".
 * - Continuous → lock at the tap.
 *
 * @param releaseRadius how close counts as the same spot, in frame fractions.
 *   The default is about the size of the reticle the phone draws, so "tap the
 *   square again" and "tap within the radius" are the same gesture.
 */
fun focusAfterTap(
    current: Focus,
    x: Double,
    y: Double,
    releaseRadius: Double = DEFAULT_RELEASE_RADIUS,
): Focus {
    val at = current.takeIf { it.mode == FocusMode.LOCKED }
    val cx = at?.x
    val cy = at?.y
    val sameSpot = cx != null && cy != null &&
        abs(cx - x) <= releaseRadius && abs(cy - y) <= releaseRadius
    return if (sameSpot) Focus() else Focus(mode = FocusMode.LOCKED, x = x, y = y)
}

/**
 * Half the reticle, as a fraction of the frame.
 *
 * Wider and a user re-aiming nearby would release instead; narrower and "tap it
 * again" would miss, which is worse — a lock nobody can clear is the failure
 * this whole rule exists to prevent.
 */
const val DEFAULT_RELEASE_RADIUS: Double = 0.06
