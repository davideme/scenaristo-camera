/**
 * View preferences that belong to this browser and not to the camera.
 *
 * Spec §8 already reasons this out for the framing guides: *"two remotes
 * watching one phone may reasonably want different overlays, in which case the
 * toggle is not protocol at all."* Mirroring is the same shape of thing, and
 * more so — whether the preview should be flipped depends on who is looking at
 * it and why, not on what the camera is doing. A producer reading the whiteboard
 * behind the speaker wants it unmirrored at the same moment the speaker wants it
 * mirrored to frame themselves.
 *
 * So it is stored here, per browser, and never sent. Nothing about the phone
 * changes, and **nothing about the recording changes** — see `MIRROR_PREVIEW`.
 */

const KEY = 'scenaristo.view'

export interface ViewPrefs {
  /**
   * Flip the preview horizontally.
   *
   * **Preview only. The recorded file is never mirrored**, and cannot be from
   * here: this is a CSS transform on an `<img>` in a browser, three processes
   * away from the encoder. That is worth stating rather than assuming, because
   * a mirror control that silently flipped the take would be discovered in an
   * edit, and the interface says so next to the switch for the same reason.
   */
  mirror: boolean
}

const DEFAULTS: ViewPrefs = { mirror: false }

/**
 * Reads the stored preferences, falling back to the defaults.
 *
 * Wrapped, because `localStorage` is not merely empty in a private window or
 * with site data blocked — the accessor itself throws, and an exception here
 * would take the whole page down over a checkbox.
 */
export function loadViewPrefs(): ViewPrefs {
  try {
    const raw = window.localStorage.getItem(KEY)
    if (!raw) return DEFAULTS
    const parsed = JSON.parse(raw) as Partial<ViewPrefs>
    return { mirror: parsed.mirror === true }
  } catch {
    return DEFAULTS
  }
}

export function saveViewPrefs(prefs: ViewPrefs): void {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(prefs))
  } catch {
    // A preference that cannot be remembered is still a preference that works
    // for this session. Nothing to tell the user about.
  }
}
