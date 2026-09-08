# ADR-0029: A studio look is a 1080p mode, built on `ImageAnalysis` and ML Kit

**Status:** Proposed
**Date:** 2026-09-08
**Deciders:** Davide Mendolia
**PRD sections:** 3 (Non-Goals), 6.1, 6.11
**Related ADRs:** [ADR-0002](0002-android-capture-stack.md), [ADR-0018](0018-preview-tap-for-metering-and-preview-frames.md), [ADR-0023](0023-lock-exposure-for-the-take.md), [ADR-0028](0028-portrait-lighting-read.md)

## Context

ADR-0028 reads how the room is lighting the subject and reports it. The next step applies a look —
Rembrandt or Clamshell (PRD 6.11) — by shaping the light that is already there: a soft directional
gain across the face moving the measured ratio toward 2:1, and a gain on everything that is not the
subject, moving the background to the one-to-two stops under the face that `docs/research/studio-lighting.md`
§2 describes. Davide decided on 2026-09-08 that the look is **written into the recording**, not merely
previewed.

Three things have to be true for that, and on the 4K path each is expensive or unavailable.

**A face, better than a rectangle.** ADR-0028 divides a face at the midpoint of the HAL's two eye
landmarks. That is a proxy for the nose line and it is all the platform offers. A face contour would
let the ratio be measured across skin rather than across a box, and let a gain be shaped to a face
rather than to a gradient.

**A person mask.** Measuring background separation needs only the complement of the face box, which
ADR-0028 already has. *Applying* the background rule needs to know where the person ends, or the gain
that darkens the wall also darkens their shoulders and hair.

**Somewhere to run a model.** ADR-0018 exists because #20 measured that **no configuration on the
Pixel 10 binds a UHD recording alongside an `ImageAnalysis`**: `Preview` and `VideoCapture` are both
`PRIV`/opaque while `ImageAnalysis` is `YUV`, and the device refuses the combination. Everything since
has read frames from a `CameraEffect` tap instead. ML Kit can consume those frames — ADR-0018 says so
directly, *"face detection reads from our `ImageReader` instead"* — but the documented, least-custom
shape, `MlKitAnalyzer` on an `ImageAnalysis`, is unavailable at UHD.

And the cost of the look itself is worst exactly where it is least affordable. Measured on 2026-09-08
(#139): with the effect targeting `PREVIEW or VIDEO_CAPTURE`, CameraX supplies **one** surface at
**4000×3000** — the sensor's full 4:3 stream, not the recording's 3840×2160 — so a shader on the
recording path renders **12 MP per frame, 44 % more than the 8.3 MP recording**. Against a budget in
which ADR-0023 turned down a 1 Hz meter as "still-something-running", that is the wrong end to start
from.

Davide decided on 2026-09-08 that **the look may be a 1080p-only feature**. That reopens Option B of
ADR-0018, which was measured and rejected there for a different reason: *"binds at video 1080×1920,
preview 1080×1920, analysis 640×480"*.

## Decision

We will make the studio look a **1080p recording mode**. When `StudioLook` is anything but `OFF`, the
session binds at FHD with an `ImageAnalysis`, and the look is applied through ML Kit:

- **Selfie Segmentation** for the person mask. There is no reasonable custom alternative, and the
  background half of the look cannot be applied without it.
- **Face Mesh Detection** for the face, when its documented envelope holds — faces within about two
  metres and modest angle to camera, which is exactly PRD 6.5's "sit 1.5–2 m back". It supplies the
  contour the ratio and the gain both want.
- **Face Detection** as the fallback where Face Mesh declines, and as the thing that would let
  `FaceMapping` and `TapGeometry` be deleted: ML Kit reports coordinates in the input image, which on
  this path *is* the analysed frame, so the sensor-to-frame mapping disappears along with the
  `DISTORTION_CORRECTION_MODE` hazard ADR-0028 records.

4K recording keeps everything it has today, including ADR-0028's reading, which continues to run on
the tap. **The look is the only thing that requires 1080p**, and choosing it is how a user asks for
that trade.

**This amends PRD §3.** The non-goal reads: *"Frame rates other than 30 fps or resolutions other than
4K UHD. Fewer options is the product. Lower fallbacks exist only for devices that cannot do 4K/30."*
It gains: *"…except that a studio look (6.11) is recorded at 1920 × 1080, which is the one place a
user may choose a lower resolution rather than inherit one."* PRD 6.1's resolution row gains the same
exception, and 6.11's look entry states it as the first thing it says.

## Options Considered

### Option A: 1080p mode, `ImageAnalysis` + ML Kit — chosen

| Dimension | Assessment |
|---|---|
| Complexity | Low: the documented shape, `MlKitAnalyzer` on `ImageAnalysis` |
| Risk | Product, not technical: a user trades resolution for a look |
| Effort | Medium — three models to evaluate, one session shape to add |
| Reversibility | High: the mode is behind a setting that defaults to OFF |

**Pros:** binds, and was measured to bind (ADR-0018 Option B). The shader renders 2.1 MP rather than
12 MP, roughly a sixth of the work, which is the difference between a thermal question and a thermal
answer. ML Kit's coordinates are already in the analysed frame, so ~200 lines of the most error-prone
custom code in the stack can go. The dependency is one library serving all three needs.

**Cons:** contradicts a PRD non-goal, and does it as a *choice* rather than a device fallback — a user
can now record worse footage than the product's headline promises. Adds ML Kit's size and, for some
models, its Play-services delivery. Two session shapes to keep working instead of one.

### Option B: Keep 4K, run ML Kit on the existing tap frames

| Dimension | Assessment |
|---|---|
| Complexity | Medium: ML Kit off `ImageReader`, hand-fed |
| Risk | High: the thermal budget, unmeasured at 12 MP |
| Effort | High |
| Reversibility | Medium |

**Pros:** no PRD amendment, no second session shape, and 4K survives. ADR-0018 already says ML Kit can
read from our `ImageReader`.

**Cons:** the shader still renders 12 MP per recorded frame, into a budget where #23 saw `MODERATE` at
eight minutes with *no* pass on that path. Nothing about the mapping simplifies — the coordinates come
back in the tap frame and still need the crop and turn. And it puts model inference on the tap thread,
which owes the viewfinder a frame every 33 ms.

### Option C: Preview-only look at 4K, never written to the file

| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | Low technically; high in what it promises |
| Effort | Low |
| Reversibility | High |

**Pros:** costs the recording nothing, keeps 4K, and cannot damage a take.

**Cons:** shows the user something they are not getting. UI-19 spends a whole entry on the opposite
rule for the mirror — *"Preview only — the recording is never mirrored"* — and that works because a
mirror is a viewing convenience. A look is the thing being asked for; showing it and not recording it
is the discovery-in-the-edit failure that rule exists to prevent. Davide's decision of 2026-09-08 also
rules it out directly.

## Trade-off Analysis

B is the strongest technical alternative and it loses on the number measured in #139: the shared-target
pass renders 12 MP per frame, more than the recording itself, into the budget ADR-0023 emptied a whole
ADR to protect. A is a sixth of that work and reaches the documented API shape rather than a hand-fed
one.

The cost of A is a product cost, and it belongs to Davide rather than to this ADR: a user may now
choose 1080p. **The distinction from ADR-0018's rejection of Option B is that this is a choice and that
was a side effect.** ADR-0018 refused to let the *browser remote* silently degrade the recording —
*"shipping 1080p whenever the remote is connected would mean the browser remote … degrades the thing it
exists to help you make."* Nobody asked for that, and nobody would have seen it happen. Here the user
picks a look, is told it records at 1080p, and gets what they asked for. That is opinionated defaults
working, not being abandoned: the default is still 4K, and `StudioLook.OFF` is still the product's own
answer.

## Consequences

- **Easier:** the shader has a sixth of the pixels; ML Kit supplies face and mask through one
  dependency; `FaceMapping` and `TapGeometry` become deletable on this path, and with them the
  `DISTORTION_CORRECTION_MODE` hazard ADR-0028 had to write down rather than solve.
- **Easier:** the thermal question shrinks enough that the take-length disclaimer Davide accepted on
  2026-09-08 may not be needed. It stays until measured, not because it is expected to bind.
- **Harder:** two session shapes, and a rebind when the look changes — which is why a look is refused
  during a take, as PRD 6.1 already requires of every setting.
- **Harder:** PRD §3 loses a clean line. "Fewer options is the product" now has an exception, and the
  next feature that wants one will cite this.
- **Harder:** an ML Kit model that ships via Play services is a network dependency on first use, in a
  product whose headline is "no backend". Which models are bundled and which are not is an action item
  below, not an assumption here.
- **Revisit when:** CameraX 1.7 (#27) or #62's rebind spike changes whether `ImageAnalysis` can sit
  beside a UHD recording. If it can, the 1080p restriction is a cost with no cause and this ADR should
  be superseded rather than kept.

## Action Items

1. [ ] Measure, on the reference Pixel 10, that FHD + `ImageAnalysis` + `MlKitAnalyzer` binds and holds
   30 fps with a recording running — ADR-0018 measured the bind, not the sustained rate with a model
   in the loop.
2. [ ] For each of the three ML Kit models: APK size, whether it is bundled or delivered by Play
   services, and per-frame latency at 1080p. A first-run network dependency needs stating in the PRD if
   it exists.
3. [ ] Measure Face Mesh on a real talking head at 1.5–2 m, and at three-quarter angle, against its
   documented envelope. If it declines at the angles a speaker actually sits at, the contour is not
   available and the ratio keeps ADR-0028's eye-line split.
4. [ ] Confirm the recorded file is 1920×1080 at 29.99 fps with the look on, and that turning the look
   off returns a 3840×2160 file identical in geometry to today's.
5. [ ] Apply the PRD amendments named under Decision (§3, 6.1's resolution row, 6.11's look entry) in
   the same change as the code, and mark them provisional in `docs/adr/README.md` until this ADR is
   Accepted.
