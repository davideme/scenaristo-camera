/**
 * UI-11: stroke-based inline SVG on a 24 px grid. No emoji, no icon font.
 *
 * Inline because ADR-0009 allows the bundle no second request, and stroke-based
 * because these sit at 58 % opacity beside dimmed text — a filled glyph at that
 * opacity reads as a smudge.
 */

const base = {
  width: 16,
  height: 16,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  'stroke-width': 2,
  'stroke-linecap': 'round' as const,
  'stroke-linejoin': 'round' as const,
  'aria-hidden': true,
}

/** UI-5: a warning carries an icon and a control never does. This is that icon. */
export const WarningIcon = () => (
  <svg {...base} class="icon">
    <path d="M12 9v4M12 17h.01" />
    <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z" />
  </svg>
)

export const BatteryIcon = () => (
  <svg {...base} class="icon">
    <rect x="2" y="7" width="16" height="10" rx="2" />
    <path d="M22 11v2" />
  </svg>
)

/** Shown only for SERIOUS and CRITICAL (UI-5); nominal and fair draw nothing at all. */
export const ThermalIcon = () => (
  <svg {...base} class="icon">
    <path d="M14 14.76V3.5a2.5 2.5 0 0 0-5 0v11.26a4 4 0 1 0 5 0Z" />
  </svg>
)

export const StorageIcon = () => (
  <svg {...base} class="icon">
    <path d="M22 12H2M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11Z" />
  </svg>
)

export const MicIcon = () => (
  <svg {...base} class="icon">
    <path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z" />
    <path d="M19 10v2a7 7 0 0 1-14 0v-2M12 19v3" />
  </svg>
)

export const LensIcon = () => (
  <svg {...base} class="icon">
    <circle cx="12" cy="12" r="9" />
    <circle cx="12" cy="12" r="3.5" />
  </svg>
)

export const LightIcon = () => (
  <svg {...base} class="icon">
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </svg>
)

export const WaveIcon = () => (
  <svg {...base} class="icon">
    <path d="M3 12h2l2-6 3 14 3-11 2 5 2-2h4" />
  </svg>
)

/** A shape and its reflection, which is the whole of what the control does. */
export const MirrorIcon = () => (
  <svg {...base} class="icon">
    <path d="M12 3v18" />
    <path d="M8 7 4 12l4 5V7Z" />
    <path d="M16 7l4 5-4 5V7Z" />
  </svg>
)
