/**
 * Framing overlays on the preview (PRD 6.8, UI-9).
 *
 * An SVG laid over the image rather than lines drawn into it: the preview is an
 * MJPEG `<img>` the browser paints itself (ADR-0008), and touching its pixels
 * would mean a canvas, a decode, and a copy of every frame — for two straight
 * lines.
 *
 * `preserveAspectRatio="none"` with a 0–100 viewBox in both axes, so the
 * fractions stay fractions whatever shape the stage is. The guides are about
 * *proportions* of the frame, and a thirds line that lands at 31% because the
 * viewBox was square is not a thirds line.
 *
 * Not mirrored with the preview, and it does not need to be: every line here is
 * symmetric about the centre, so a flip maps the set onto itself.
 */
export function FramingGuides({ thirds, eyeLine }: { thirds: boolean; eyeLine: boolean }) {
  if (!thirds && !eyeLine) return null

  return (
    <svg
      class="guides"
      viewBox="0 0 100 100"
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      {thirds ? (
        <g class="guide-thirds">
          <line x1="33.333" y1="0" x2="33.333" y2="100" />
          <line x1="66.667" y1="0" x2="66.667" y2="100" />
          <line x1="0" y1="33.333" x2="100" y2="33.333" />
          <line x1="0" y1="66.667" x2="100" y2="66.667" />
        </g>
      ) : null}
      {/*
        The eye line sits at the upper third, which is where a talking head's
        eyes belong -- above centre, not on it. Drawn dashed so it is
        distinguishable from the thirds grid when both are on, since it lies
        exactly on top of one of those lines.
      */}
      {eyeLine ? <line class="guide-eye" x1="0" y1="33.333" x2="100" y2="33.333" /> : null}
    </svg>
  )
}
