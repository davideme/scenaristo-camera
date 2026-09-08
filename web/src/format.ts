import { TStop } from './optics'
import type { State, VideoCodec } from './protocol'

/**
 * Turning protocol values into the words UI-12 fixes.
 *
 * Apart together, and not in the components, because most of these are
 * decisions rather than formatting: what a missing value reads as, which unit a
 * number is shown in, and when the interface says nothing at all. Those are the
 * parts the spec argues about, and they are worth reading in one place.
 */

/** UI-9, UI-4: what a value reads as when there is no value. Never a zero. */
export const ABSENT = '—'

/** `mm:ss`, or `h:mm:ss` past an hour. Tabular numerals stop it jittering (UI-11). */
export function timecode(seconds: number | null): string {
  if (seconds == null) return '--:--'
  const s = Math.max(0, Math.floor(seconds))
  const mm = pad(Math.floor(s / 60) % 60)
  const ss = pad(s % 60)
  const h = Math.floor(s / 3600)
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

const pad = (n: number) => String(n).padStart(2, '0')

/**
 * The recording format, as UI-4 writes it on the phone: `4K · 30`.
 *
 * 4K rather than 3840×2160 because the phone says 4K and the two surfaces speak
 * one language (goal 4). A resolution that is *not* UHD is spelled out, because
 * then it is ADR-0011's fallback having fired and PRD 6.10 requires that be
 * visible rather than glossed.
 */
export function format(state: State): string {
  // Every added field is optional in the generated types, because ADR-0007
  // defaults them so an older client survives a newer phone -- and the mirror
  // of that is a newer page surviving an older phone, which is what these
  // guards are.
  const { widthPx, heightPx, frameRate } = state.encoding ?? {}
  if (!widthPx || !heightPx || !frameRate) return ABSENT
  const name = widthPx >= 3840 ? '4K' : widthPx >= 1920 ? '1080p' : `${widthPx}×${heightPx}`
  return `${name} · ${frameRate}`
}

/** PRD 6.7's codecs, named the way a person says them. */
export function codecName(codec: VideoCodec): string {
  switch (codec) {
    case 'HEVC':
      return 'HEVC'
    case 'H264':
      return 'H.264'
    default:
      return ABSENT
  }
}

/** Megabits per second, from the bits per second the protocol carries. */
export function bitrate(bitsPerSecond: number): string {
  if (!bitsPerSecond) return ABSENT
  return `${Math.round(bitsPerSecond / 1_000_000)} Mbps`
}

/**
 * The lens as an optic (UI-18): `f/1.7 · T1.8`.
 *
 * Both or neither. The T-stop assumes a transmission the phone cannot report,
 * and the only thing that makes it honest to draw is the f/-number beside it —
 * so there is deliberately no way to ask this for one without the other.
 */
export function optics(fNumber: number | null | undefined): string | null {
  if (fNumber == null) return null
  const t = TStop.of(fNumber)
  if (t == null) return null
  return `f/${fNumber.toFixed(1)} · T${t.toFixed(1)}`
}

/**
 * Exposure error as UI-17 writes it: `-0.4 EV`, negative under, `0.0 EV` at
 * target.
 *
 * The sign is always drawn, including the plus, so the reading's direction is
 * legible without comparing it to a remembered zero.
 */
export function stops(value: number): string {
  const rounded = Math.abs(value) < 0.05 ? 0 : value
  const sign = rounded > 0 ? '+' : rounded < 0 ? '−' : '±'
  return `${sign}${Math.abs(rounded).toFixed(1)} EV`
}

/**
 * UI-12: the count of attached browsers reads "2 remotes connected". Never
 * *viewer* and never *client* — a viewer watches, and this one can start a
 * recording.
 */
export function remotes(count: number): string {
  if (count <= 0) return 'no remotes connected'
  return `${count} remote${count === 1 ? '' : 's'} connected`
}

/**
 * UI-4: **free space** is minutes at the current bitrate, never gigabytes.
 *
 * Scoped to free space on 2026-09-07, because it used to say gigabytes were
 * never shown at all and PRD 6.11's take list shows them. The rule was always
 * about the question rather than the unit: "how much room is left" is answered
 * in minutes because a creator is deciding whether to start a take, and no
 * amount of arithmetic turns 4.2 GB into that answer. "How big is this file"
 * is a different question, asked when a take is already shot and about to be
 * copied somewhere, and bytes are its honest unit.
 */
export function minutesLeft(minutes: number): string {
  return minutes > 0 ? `${minutes} min` : ABSENT
}

/**
 * A take's size, for the download list (PRD 6.11).
 *
 * Decimal units, because that is what every file manager the user will compare
 * this against reports -- macOS Finder and the browser's own download shelf
 * both call 1000 bytes a kilobyte. Being right about powers of two here would
 * only mean disagreeing with the number beside it.
 *
 * One decimal place below 100, none above: "1.4 GB" is worth a digit, "847 MB"
 * is not, and the varying width of a right-aligned column is worse than the
 * precision is worth.
 */
export function fileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return ABSENT
  if (bytes < 1000) return `${bytes} B`
  const units = ['kB', 'MB', 'GB', 'TB']
  let value = bytes / 1000
  let unit = 0
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000
    unit += 1
  }
  return `${value >= 100 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`
}
