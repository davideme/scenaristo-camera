import { WHITE_BALANCE_PRESETS } from './protocol'

/**
 * PRD 6.4's white balance, as the two questions UI-12 fixes.
 *
 * The **numbers** are generated from `:domain` (`WHITE_BALANCE_PRESETS`), so the
 * remote can only ever offer what the phone accepts. The **words** are here,
 * because they are UI copy: "Daylight in the room" and "Lamps only" are the
 * plain-language form of the PRD's "natural light present" and "artificial light
 * only", and UI-12 requires that the user does not meet the word Kelvin before
 * the preset value.
 */

export interface Preset {
  kelvin: number
  /** What this temperature is, in light a person can picture. */
  name: string
}

export interface Scenario {
  id: keyof typeof WHITE_BALANCE_PRESETS
  name: string
  presets: Preset[]
}

/**
 * What each temperature is called.
 *
 * Keyed by Kelvin rather than listed per scenario, because the two lists overlap
 * at 4500 K and 5600 K on purpose — a room with a window and a lamp is both
 * scenarios — and the same temperature must not acquire two names depending on
 * which question you answered.
 */
const NAMES: Record<number, string> = {
  3200: 'Tungsten lamps',
  4500: 'Mixed light',
  5600: 'Daylight',
  6500: 'Overcast or shade',
}

export const SCENARIOS: Scenario[] = [
  {
    id: 'NATURAL_LIGHT',
    name: 'Daylight in the room',
    presets: WHITE_BALANCE_PRESETS.NATURAL_LIGHT.map(toPreset),
  },
  {
    id: 'ARTIFICIAL_LIGHT',
    name: 'Lamps only',
    presets: WHITE_BALANCE_PRESETS.ARTIFICIAL_LIGHT.map(toPreset),
  },
]

function toPreset(kelvin: number): Preset {
  return { kelvin, name: NAMES[kelvin] ?? `${kelvin} K` }
}

/** The preset at this temperature, or null when the phone is on something else. */
export function presetFor(kelvin: number): Preset | null {
  for (const scenario of SCENARIOS) {
    const found = scenario.presets.find((p) => p.kelvin === kelvin)
    if (found) return found
  }
  return null
}

/**
 * Which scenario a temperature belongs to, or null when it belongs to both.
 *
 * The overlap is not a defect to resolve: 4500 K and 5600 K are in both lists so
 * that a user who answered the question "wrong" still finds the temperature they
 * need. Nothing should therefore claim a scenario for them.
 */
export function scenarioOf(kelvin: number): Scenario | null {
  const owning = SCENARIOS.filter((s) => s.presets.some((p) => p.kelvin === kelvin))
  return owning.length === 1 ? owning[0] : null
}

/**
 * What each platform AWB mode is called, and the temperature it nominally sits
 * at (PRD 6.4, ADR-0011).
 *
 * Named for a person rather than echoing the platform constant, the way
 * `AwbApproximation` itself is: "Daylight" is a thing a room can be, and
 * `CONTROL_AWB_MODE_DAYLIGHT` is not.
 *
 * The Kelvin values are the platform's nominal ones and **not measurements** —
 * what each mode actually produces on a given device is #24's grey-card work.
 * That is why the copy says "approximately".
 */
const APPROXIMATIONS: Record<string, { name: string; kelvin: number }> = {
  INCANDESCENT: { name: 'Incandescent', kelvin: 3000 },
  FLUORESCENT: { name: 'Fluorescent', kelvin: 4000 },
  DAYLIGHT: { name: 'Daylight', kelvin: 5500 },
  CLOUDY: { name: 'Cloudy', kelvin: 6500 },
}

/**
 * PRD 6.4: "the app shows which preset is approximated and by which platform
 * mode."
 *
 * Null when the lens applies the preset exactly, which is the only case where
 * the interface should stay quiet.
 */
export function approximationNote(mode: string | null | undefined): string | null {
  if (!mode) return null
  const m = APPROXIMATIONS[mode]
  if (!m) return `Approximated by the ${mode.toLowerCase()} mode — this lens cannot set exact gains`
  return `Approximated by ${m.name} (about ${m.kelvin} K) — this lens cannot set exact gains`
}
