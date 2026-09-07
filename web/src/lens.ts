import { LENS_RECOMMENDED_FROM, LENS_WIDE_BAND } from './protocol'
import type { State } from './protocol'

/**
 * PRD 6.5's rules about a focal length, applied to the same number the phone
 * applies them to.
 *
 * The bands are generated from `:domain` rather than written down here
 * (ADR-0009). A browser deciding at 26 mm what the phone decides at 25 is two
 * surfaces disagreeing about the shot in front of them, and the disagreement
 * would show as guidance appearing on one screen and not the other.
 */

/** PRD 6.5: "If the device has a longer lens (48 mm+ telephoto)". */
export function isRecommended(equivalentFocalLengthMm: number | null | undefined): boolean {
  return equivalentFocalLengthMm != null && equivalentFocalLengthMm >= LENS_RECOMMENDED_FROM
}

/**
 * PRD 6.5: "If the equivalent focal length is 23-25 mm … show persistent
 * guidance".
 *
 * Persistent rather than a warning, and that distinction is the whole reason it
 * is not in `State.warnings`: it is true of the lens for the entire session, not
 * of this moment. UI-5's chips are things that just became true; this is a
 * standing fact about the framing, and it is dismissible for exactly that reason
 * — once read, it has been read.
 */
export function needsDistanceGuidance(
  equivalentFocalLengthMm: number | null | undefined,
): boolean {
  return (
    equivalentFocalLengthMm != null &&
    equivalentFocalLengthMm >= LENS_WIDE_BAND.min &&
    equivalentFocalLengthMm <= LENS_WIDE_BAND.max
  )
}

const DISMISSED_KEY = 'scenaristo.distanceGuidanceDismissed'

/**
 * Whether the guidance has been dismissed (UI-12: "dismissible for the
 * session").
 *
 * `sessionStorage`, not `localStorage`, and the difference is the requirement:
 * "for the session" means it comes back next time the remote is opened, because
 * the next session is a different shot with a different person in front of the
 * camera. A preference that outlived the session would silence PRD 6.5's
 * guidance permanently after one click.
 */
export function distanceGuidanceDismissed(): boolean {
  try {
    return window.sessionStorage.getItem(DISMISSED_KEY) === '1'
  } catch {
    // Storage can throw outright in a private window. Guidance that shows once
    // too often is a far better failure than a page that does not render.
    return false
  }
}

export function dismissDistanceGuidance(): void {
  try {
    window.sessionStorage.setItem(DISMISSED_KEY, '1')
  } catch {
    // Nothing to tell anyone: it stays dismissed in memory for this render.
  }
}

/**
 * The focal length actually in use, which is the framing's and not the base
 * lens's.
 *
 * `Optics.equivalentFocalLengthMm` is probed once at bind and describes the
 * base lens — 24 mm on the reference device. Every rule PRD 6.5 states is about
 * the field of view being *recorded*, so once the framing is a zoom ratio
 * (UI-21) those rules have to read the framing.
 *
 * Getting this wrong is not subtle: it showed "Wide lens — sit 1.5-2 m back" on
 * the 13 mm ultrawide and on the 120 mm telephoto alike, because both were being
 * judged as the 24 mm lens underneath them.
 */
export function activeFocalLengthMm(state: State): number | null {
  const ratio = state.settings.zoomRatio ?? 1
  const framing = (state.lenses ?? []).find((l) => l.zoomRatio === ratio)
  return framing?.equivalentFocalLengthMm ?? state.optics?.equivalentFocalLengthMm ?? null
}
