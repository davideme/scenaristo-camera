/**
 * The T-stop, derived here rather than sent (UI-18, #101).
 *
 * A derived number on the wire is one iOS could derive differently, so the
 * protocol carries the f/-number the platform reports and the arithmetic lives
 * with the surface that draws it — the same surface on both platforms
 * (ADR-0013).
 *
 * Kept in step with `TStop` in `:domain`, which is the phone's copy of the same
 * two lines. Two copies of *arithmetic* is a different thing from two copies of
 * a protocol: this one cannot drift silently, because the fixture carries the
 * f/-number and the expected T-stop is checked against `log2(1 / 0.92)` on the
 * Kotlin side rather than against a literal.
 */
interface TStopArithmetic {
  TRANSMISSION: number
  of(fNumber: number, transmission?: number): number | null
}

export const TStop: TStopArithmetic = {
  /**
   * Assumed light transmission through the lens (Davide, 2026-09-06).
   *
   * No phone reports its own transmission, and measuring it would mean a grey
   * card at a known illuminance, per lens, per device — a fact about one
   * handset rather than about phones (ADR-0017). 92 % costs about a sixth of a
   * stop: `log2(1 / 0.92)` is 0.12 EV. This is an assumption, not a
   * measurement, which is why UI-18 draws the f/-number beside every T-stop.
   */
  TRANSMISSION: 0.92,

  /**
   * `T = N / sqrt(transmission)`.
   *
   * Under a square root because a stop is a ratio of *areas*. Applying
   * transmission linearly is the usual mistake and would double the loss it
   * describes.
   *
   * Null for anything that cannot produce a number, so an unreported lens draws
   * nothing rather than `T0.0`.
   */
  of(fNumber: number, transmission: number = TStop.TRANSMISSION): number | null {
    if (!(fNumber > 0) || !(transmission > 0) || transmission > 1) return null
    return fNumber / Math.sqrt(transmission)
  },
}
