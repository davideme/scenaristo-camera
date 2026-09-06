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
