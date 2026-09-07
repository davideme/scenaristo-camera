import type { MountAttitude } from './protocol'

/**
 * The horizon overlay (PRD 6.11, UI-23).
 *
 * PRD 6.1 turns both stabilisers off on the grounds that the phone is on a
 * tripod, which quietly makes a level mount the operator's problem and gives
 * them nothing to solve it with. This is that something: a line that lies flat
 * when the phone does.
 *
 * **Its own element rather than a group inside `FramingGuides`.** That SVG is
 * `preserveAspectRatio="none"` over a 0-100 viewBox in both axes, so it is
 * stretched to whatever shape the stage is. Every line in it is axis-aligned,
 * which is why that has never mattered -- but a line drawn at 0.6 degrees in
 * those coordinates comes out at whatever angle the stretch makes of it, which
 * is the one thing this overlay must not get wrong. So it draws in pixel space,
 * where a degree is a degree.
 */
export function LevelOverlay({
  mount,
  mirrored,
  recording,
  previewProducing,
}: {
  mount: MountAttitude | null | undefined
  mirrored: boolean
  recording: boolean
  previewProducing: boolean
}) {
  // Nothing to draw over, and something more important to read: with the
  // phone's screen off there are no frames to tap (#116) and the preview
  // already carries its own explanation in the middle of the same rectangle.
  // Two notices fighting for one centre is worse than the one that tells
  // somebody what to do, and a horizon over a black frame is not a shot anyone
  // can level anyway.
  if (!previewProducing) return null

  const measuring = mount?.measuring === true

  if (!measuring) {
    // Not measuring is the *normal* state during a take -- the accelerometer is
    // switched off for its duration (ADR-0023) -- so this says which of the two
    // things is happening. It follows the empty-preview note next door in
    // saying what is not wrong as well as what is: someone who turned the
    // overlay on and saw it go blank at the exact moment they pressed record
    // has every reason to think they broke the take.
    if (!recording) return null
    return (
      <p class="level-paused">
        <span>Levelling pauses while recording</span>
        <span class="level-paused-note">The take is unaffected</span>
      </p>
    )
  }

  const roll = mount?.rollDegrees ?? 0
  const pitch = mount?.pitchDegrees ?? 0
  const steady = mount?.steady !== false
  /*
   * The horizon slopes up towards whichever side of the frame has risen, and
   * CSS rotates clockwise while the roll is measured counter-clockwise -- hence
   * the negation. Mirroring the preview flips left for right, so the line has
   * to flip with it or it would lie about the picture it is drawn over. This is
   * the first overlay in the app for which that is true: `FramingGuides` gets
   * to ignore the mirror because every line in it is symmetric about the
   * centre.
   */
  const drawn = mirrored ? roll : -roll

  return (
    <div class="level" aria-hidden="true">
      <div class="level-mark">
        {/* Where level is. Short, centred and fixed -- the thing the horizon is
            read against, and the reason the indicator does not need to say
            which way to turn. */}
        <div class="level-reference" />
        <div
          class={steady ? 'level-horizon' : 'level-horizon unsteady'}
          style={{ transform: `rotate(${drawn.toFixed(2)}deg)` }}
        />
        {/*
          The number sits on the mark rather than in a caption somewhere else,
          because the two are one reading: the mark says which way and the
          number says how far, and a person levelling a tripod should not have
          to look in two places to get one answer.

          Never recoloured, in any state. UI-5 is explicit that a warning is a
          chip and nothing else and that no readout is recoloured to raise one,
          so 1.4 degrees is the same grey as 0. The reference camera this is
          modelled on turns its indicator yellow; that is the one thing here
          deliberately not copied.
        */}
        <span class="level-degrees">{format(roll)}°</span>
      </div>
      {/*
        One secondary line, and steadiness takes it when there is something to
        say -- a mount that is moving is worth more than where the lens is
        aimed. Pitch is reported and never judged: a camera aimed a few degrees
        up at a seated speaker is a decision as often as an accident, and PRD
        6.11 frames only roll as wrong. Up and down also survive the mirror,
        which is why this one names a direction and the roll does not.
      */}
      {!steady ? (
        <span class="level-note">Unsteady — check the mount</span>
      ) : Math.abs(pitch) >= PITCH_WORTH_SHOWING_DEGREES ? (
        <span class="level-note">
          aimed {format(pitch)}° {pitch > 0 ? 'up' : 'down'}
        </span>
      ) : null}
    </div>
  )
}

/**
 * One decimal place, unsigned, and rounded to nothing inside the level band.
 *
 * Under a third of a degree it reads `0.0` rather than a real measurement: a
 * readout that never quite reaches zero is one somebody keeps adjusting
 * against, so there has to be a point at which it stops asking.
 *
 * Deliberately no "left" or "right" anywhere in this component. A tilt is a
 * left-or-right fact, and left and right are exactly what the mirror switch
 * takes away: "raise the right side" is the correct instruction and the wrong
 * one depending on a per-viewer preference the phone knows nothing about, and
 * getting it backwards costs someone a take. The line does not have that
 * problem, because it is drawn over the picture it describes -- so the words
 * carry the size and the line carries the direction, and levelling the phone is
 * a matter of turning it until the line lies flat.
 */
function format(degrees: number): string {
  const magnitude = Math.abs(degrees)
  return (magnitude < LEVEL_WITHIN_DEGREES ? 0 : magnitude).toFixed(1)
}

/**
 * Close enough to level to call it level.
 *
 * Three times the 0.1-degree step the phone publishes, and about a fifth of what
 * shows on a straight edge across a 4K frame.
 */
const LEVEL_WITHIN_DEGREES = 0.3

/** Under half a degree of pitch is the mount settling, not an aim. */
const PITCH_WORTH_SHOWING_DEGREES = 0.5
