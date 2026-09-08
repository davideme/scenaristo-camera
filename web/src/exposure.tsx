import { stops } from './format'
import type { ExposureReadout, PortraitLightingState } from './protocol'

/**
 * The exposure aids (UI-17, #97): how far the shot is from correct, and where
 * its tones fall.
 *
 * Both are **Reported** grammar (spec §5) — outputs of the ADR-0005 loop, with
 * nothing to press — so they are dimmed and unframed, and carry no amber.
 */

/**
 * How far the scene is from the 18 % target the exposure loop is aiming at.
 *
 * The 18 % is named on the scale rather than beside the reading, because goal 2
 * says no fact appears twice, and because "0 EV" and "18 % grey" are the same
 * fact said two ways.
 */
export function ExposureScale({ readout }: { readout: ExposureReadout }) {
  if (!readout.metering) return <Unmetered label="Exposure" heading />

  const value = readout.stopsFromTarget ?? 0
  // The scale runs ±2 stops; beyond that the marker pins to the end rather than
  // leaving the track, because "further under than this scale draws" is still
  // the useful reading.
  const clamped = Math.max(-2, Math.min(2, value))
  const percent = ((clamped + 2) / 4) * 100

  return (
    <div class="aid">
      {/* The panel's heading and this reading share one row: drawing
          "Exposure" as a panel title *and* as this aid's label put the same word
          on screen twice, which is goal 2 with the volume turned down. */}
      <div class="aid-head">
        <h2>Exposure</h2>
        <span class="value mono">{stops(value)}</span>
      </div>
      <div class="scale" role="img" aria-label={`${stops(value)} from correct exposure`}>
        <div class="scale-track" />
        <div class="scale-target" />
        <div class="scale-marker" style={{ left: `${percent}%` }} />
      </div>
      <div class="scale-legend">
        <span>−2</span>
        <span>18% grey</span>
        <span>+2</span>
      </div>
    </div>
  )
}

/**
 * Where the frame's tones fall (UI-17).
 *
 * Drawn as an SVG polyline rather than 64 bars: at the width this sits in, bars
 * are a pixel or two each and the shape is what is being read, not the
 * individual counts.
 *
 * Normalised to the tallest bin, not to the sample count. A histogram is read
 * for its *shape* — where the mass sits, whether anything piles up at either
 * end — and scaling to the total would flatten every frame that is not
 * extraordinarily peaked into an unreadable line.
 */
export function Histogram({ readout }: { readout: ExposureReadout }) {
  const bins = readout.histogram ?? []
  if (!readout.metering || bins.length === 0) return <Unmetered label="Histogram" />

  const peak = Math.max(...bins, 1)
  const w = 100
  const h = 34
  const step = w / (bins.length - 1 || 1)
  const points = bins.map((n, i) => `${(i * step).toFixed(2)},${(h - (n / peak) * h).toFixed(2)}`)

  // Clipping is what a histogram is looked at for, so the end bins get their own
  // reading rather than being left for the eye to catch on a 34 px tall drawing.
  const total = bins.reduce((a, b) => a + b, 0) || 1
  const crushed = (bins[0] / total) * 100
  const blown = (bins[bins.length - 1] / total) * 100

  return (
    <div class="aid">
      <div class="aid-head">
        <span class="label">Histogram</span>
        <span class="value mono clip">
          {crushed >= CLIP_PERCENT ? <em>{crushed.toFixed(0)}% crushed</em> : null}
          {blown >= CLIP_PERCENT ? <em>{blown.toFixed(0)}% blown</em> : null}
        </span>
      </div>
      <svg
        class="histogram"
        viewBox={`0 0 ${w} ${h}`}
        preserveAspectRatio="none"
        role="img"
        aria-label="Luminance distribution of the metered frame"
      >
        <polygon points={`0,${h} ${points.join(' ')} ${w},${h}`} />
      </svg>
    </div>
  )
}

/**
 * How much of the frame has to pile up at an end before it is worth saying.
 *
 * Not zero: a real scene almost always has a specular highlight or a shadow at
 * the limit, and an indicator that is always lit is one nobody reads.
 */
const CLIP_PERCENT = 1

/**
 * Nothing measured yet.
 *
 * Drawn as an explicit absence rather than as an empty graph, for the reason
 * `ExposureReadout.metering` exists at all: a histogram of zeroes says the frame
 * is black, and a meter that is not running says nothing — and showing the
 * second as the first is how somebody trusts an aid that is measuring nothing.
 */
function Unmetered({ label, heading }: { label: string; heading?: boolean }) {
  return (
    <div class="aid">
      <div class="aid-head">
        {heading ? <h2>{label}</h2> : <span class="label">{label}</span>}
        <span class="value dim">not metering</span>
      </div>
    </div>
  )
}

/**
 * How the room is lighting the subject (UI-24, PRD 6.11, ADR-0028).
 *
 * **Reported** grammar like everything else here: dimmed, unframed, nothing to
 * press. Two numbers and no verdict — 1:1 is a legitimate deliberate choice, so
 * a product that called it "flat" would be wrong more often than the user is.
 *
 * `recording` comes from the caller rather than from the reading, because a
 * reading that has stopped and a reading that never started look identical on
 * the wire (`measuring: false`) and mean different things to a person.
 */
export function LightingRead({
  lighting,
  recording,
}: {
  lighting: PortraitLightingState
  recording: boolean
}) {
  if (!lighting.measuring) {
    return (
      <div class="aid">
        <div class="aid-head">
          <span class="label">Light on you</span>
          <span class="value dim">
            {recording ? 'not measured while recording' : 'no face — not measuring'}
          </span>
        </div>
      </div>
    )
  }

  // Tenths arrive as integers so the phone's deadband survives the wire
  // (ADR-0024). Divided here and never re-rounded, or the drawing would flicker
  // between two values the phone deliberately held still.
  const ratio = (lighting.keyRatioTenths ?? 0) / 10
  const stopsTenths = lighting.backgroundStopsTenths
  const separation =
    stopsTenths === undefined || stopsTenths === null
      ? null
      : stopsTenths / 10

  return (
    <div class="aid">
      <div class="aid-head">
        <span class="label">Light on you</span>
        <span class="value">{ratio.toFixed(1)}:1</span>
      </div>
      <div class="aid-head">
        <span class="label">Background</span>
        <span class="value">
          {separation === null
            ? '—'
            : separation < 0
              ? `${Math.abs(separation).toFixed(1)} stops brighter than you`
              : `${separation.toFixed(1)} stops below you`}
        </span>
      </div>
    </div>
  )
}
