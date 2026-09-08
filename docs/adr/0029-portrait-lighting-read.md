# ADR-0029: Read the light on the subject from the frames already being metered, and report it

**Status:** Proposed
**Date:** 2026-09-08
**Deciders:** Davide Mendolia
**PRD sections:** 6.3, 6.11
**Related ADRs:** [ADR-0005](0005-exposure-control-own-metering-loop.md), [ADR-0018](0018-preview-tap-for-metering-and-preview-frames.md), [ADR-0023](0023-lock-exposure-for-the-take.md), [ADR-0024](0024-guard-settings-on-a-settings-revision.md), [ADR-0027](0027-mount-level-from-the-accelerometer.md)

## Context

The app resolves the three capture variables it can *set*: shutter (PRD 6.2), ISO (6.3) and white
balance (6.4). It sets nothing about the light in the room — where it comes from, how hard it is, and
how the subject sits against the background — and those are what separate footage that looks lit from
footage that looks recorded. `docs/research/studio-lighting.md` is the desk research; its §2 gives the
two relationships that matter for a talking head: a key-to-fill ratio near **2:1**, and a background
**one to two stops under the face**.

Two things make this measurable now rather than hypothetical. Face rectangles reach the meter, with
the eye landmarks needed to divide a face on its nose line rather than down the middle of its bounding
box. And `FaceWeightedMeter.measure` already walks every sampled pixel once, already classifies each
as inside or outside a window, and already has its normalised coordinates in hand — so the readings
are accumulators in a walk that is being paid for anyway. The precedent that this is affordable is the
histogram: `docs/spec-phone-and-remote-ui.md:248` records that adding it to the same walk cost no
frames.

The countervailing force is that this budget is tight and known to be tight. ADR-0023 removed metering
*entirely* from a locked take, and rejected even a 1 Hz meter kept alive for two light warnings as
"still-something-running". Anything added to the per-frame path has to justify itself against that.

This is also the first measurement in the product that is **of the room rather than of the
equipment**. ADR-0027 rejected an optical horizon on exactly that ground: *"Every other measurement in
this product is of the equipment … and each is right regardless of what is in front of the lens. An
optical horizon is a measurement of the room, and rooms lie."* That argument does not forbid this one
— a lighting read is *about* the room, so a measurement of the room is the only kind available — but
it does set the bar: the reading must say when it is not measuring, rather than produce a plausible
number from whatever is in front of the lens.

## Decision

We will compute three readings from the frames the exposure loop already meters, publish them as a
**reported-only** `PortraitLightingState` on the state document, and decide nothing with them.

- **Key ratio.** The geometric mean of the subject's brighter half against its dimmer half, divided at
  the midpoint of the reported eye positions where the device gives them and at the face box's centre
  where it does not. Reported in tenths, with the side of the **frame** the key is on.
- **Background separation.** The face's mean against the mean of everything outside every face window,
  in tenths of a stop, **linearised first** — the meter works in gamma-encoded space deliberately, but
  a stop is a doubling of light, and the ratio of two encoded numbers understates the separation by
  roughly half. Positive means the background is below the face; negative is a window behind the
  speaker.
- **Enough light.** Whether the settled ISO is at or below **400**, a stop of headroom over PRD 6.3's
  noise threshold. Read off ISO rather than off pixels, so it is true of the room even with no face.

Three rules go with them.

1. **No face, no reading.** With no face the meter falls back to `MeteringConfig.centreWindow`, and a
   ratio measured across a fixed rectangle is a fact about a rectangle. `measuring` is false and every
   other field is meaningless rather than stale — the refusal `AudioState.metering`,
   `ExposureReadout.metering` and `MountAttitude.measuring` already make.
2. **Nothing during a take.** The reading exists so somebody can move a lamp, and a take in progress
   is exactly when nobody is going to. `measuring` goes false for the take's duration, following
   ADR-0027's mount level, and keeping ADR-0023's promise that a recording is quiet.
3. **Deadbanded before the wire.** A face box jitters by a few pixels between frames even on a tripod.
   Published raw at the tap's rate this would advance the settings revision constantly and refuse
   every settings change the user made, which is the bug ADR-0024 exists to fix.

This amends no PRD statement. It adds one bullet to **6.11 (P1)** and one entry to the UI spec
(UI-25), which is ADR-0027's precedent for new product surface.

## Options Considered

### Option A: Accumulate in the existing metering walk, report only — chosen

| Dimension | Assessment |
|---|---|
| Complexity | Low: three accumulator pairs beside the two already there |
| Risk | Low for frames; the reading itself may not mean what a person sees |
| Effort | Low |
| Reversibility | High: a reported field with no consumer can be deleted |

**Pros:** The expensive part — reading a pixel out of a platform buffer — is already paid, and the
histogram precedent measured that cost as zero dropped frames. The verdicts live in `:domain`, so iOS
inherits them rather than re-deriving them (ADR-0013). Shipping the reading before anything acts on it
is the cheapest possible way to discover the number is wrong.

**Cons:** Adds arithmetic to the one path that runs on every preview frame, which ADR-0023 spent a
whole ADR emptying. A vertically split box is a crude model of a face turned three-quarters to camera,
even divided on the eye line.

### Option B: A second pass over the frame, off the metering path

| Dimension | Assessment |
|---|---|
| Complexity | Medium: another walk, another cadence to choose |
| Risk | Medium: two readings of the same frame that can disagree |
| Effort | Medium |
| Reversibility | High |

**Pros:** The metering path stays exactly as it is, and the lighting read can run at 1 Hz instead of
30 Hz.

**Cons:** Doubles the cost of the one thing that is actually expensive — the pixel reads — to avoid
arithmetic that is nearly free. `Metering.measure`'s own KDoc rejects this for the histogram in the
same words. And UI-17 already states the principle it violates: *"an exposure aid that disagrees with
the thing it advises about is worse than no aid."*

### Option C: Derive it from the histogram already on the wire

| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | High: the histogram cannot answer the question |
| Effort | Low |
| Reversibility | High |

**Pros:** No new per-frame work at all, and the browser could compute it.

**Cons:** The histogram is deliberately unweighted and unwindowed — *"every sampled pixel counts once,
wherever it is"* — so it has no idea which pixels are the face, let alone which half. It can say a
frame contains both bright and dark pixels; it cannot say the bright ones are one cheek.

## Trade-off Analysis

B is the strongest alternative, and it loses on the force that actually binds: cost. The thing ADR-0023
found expensive was the per-pixel work — *"roughly a million logarithms a second"* — not the handful of
divisions at the end of a walk. B pays that cost twice to avoid a fraction of it once, and buys a
second reading of the same frame that can disagree with the first.

Against A's real weakness — that the number may not describe what a person sees — the answer is the
ordering rather than a different design. The reading ships **before** anything acts on it, so it can be
watched against real setups. A verdict built on a ratio nobody has checked would be the expensive
mistake; a reported number that turns out to be wrong costs a field.

## Consequences

- **Easier:** any later lighting feature — a warning, a guided setup, a look — reads a number that
  already exists and has been watched. The browser can show the two relationships §2 of the research
  note describes without the phone deciding anything.
- **Harder:** `Metered` now carries three nullable fields, and every caller has to mean something by
  their absence. The split geometry is now a thing both platforms must implement identically
  (ADR-0013), so Phase 4 has one more fixture to pass.
- **Harder:** the per-frame path grew, against ADR-0023's direction of travel. The saving grace is that
  it grew by arithmetic and not by pixel reads, and that it stops entirely during a take.
- **Revisit when:** the reading has been watched against real setups for long enough to say whether the
  eye-line split tracks a turned head. If it does not, the next step is landmarks beyond the eyes or a
  narrower band inside the box — decided with the evidence, not now.

## Action Items

1. [ ] Watch the reading against real setups: a single key moved front to side, a window behind the
   subject, and a face turned three-quarters, and record whether the ratio matches what a person sees.
2. [ ] Confirm on the reference device that the added accumulators cost no dropped frames, against
   #23's 29.990 fps baseline — the same check the histogram passed.
3. [ ] Decide whether `enoughLight`'s ISO 400 line is right once real rooms have been logged; it is a
   product decision taken on 2026-09-08 without room measurements behind it.
4. [ ] Before the matrix widens (#29): decide what the face mapping does when
   `SENSOR_INFO_ACTIVE_ARRAY_SIZE` and `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE` differ, since
   `DISTORTION_CORRECTION_MODE` selects which one a face is reported against. It cannot arise on the
   Pixel 10, whose crop region at 1x is the whole array, so the fallback path is never exercised there
   — which is exactly why it needs deciding before a second device rather than after.
