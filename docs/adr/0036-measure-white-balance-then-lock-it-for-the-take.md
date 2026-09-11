# ADR-0036: Measure white balance automatically while setting up, and lock it for the take

**Status:** Proposed
**Date:** 2026-09-10
**Deciders:** Davide Mendolia
**PRD sections:** 6.1, 6.4, 6.8, 6.11
**Related ADRs:** [ADR-0007](0007-control-protocol.md), [ADR-0009](0009-web-ui-static-bundle.md), [ADR-0011](0011-per-lens-capability-gating.md), [ADR-0022](0022-two-exposure-responsiveness-modes.md), [ADR-0023](0023-lock-exposure-for-the-take.md), [ADR-0025](0025-camera-standby-when-nothing-is-watching.md), [ADR-0033](0033-native-shutter-priority-on-android-16.md), [ADR-0034](0034-kelvin-white-balance-on-android-16.md), [ADR-0035](0035-white-balance-from-the-cameras-own-calibration.md)

## Context

PRD 6.4 rules auto white balance out, and says why:

> Two scenarios, each with three Kelvin presets. WB is always locked; auto WB is not the default
> because it drifts mid-take.

The objection is drift **during a take**. Nothing in it argues against the camera measuring the light
while nobody is recording.

The preset design asks the creator to know their light. `WhiteBalancePresets.kt` puts it plainly —
*"a creator knows what their room is lit by and does not know what a Kelvin is"* — and answers with
two scenarios of three presets. That has two costs. Mixed window-and-lamp light, the commonest
domestic setup, rarely matches one of four values. And on the reference phone the presets are
approximations today: 4500 K lands on `FLUORESCENT`, nominally 4000 K, before anyone measures
anything (ADR-0034, ADR-0035). PRD 6.4's P1 "auto once" button — sample AWB, snap to the nearest
preset, lock — and its repeat in 6.11 were the planned remedy.

Exposure already has the shape this needs: fast while the creator lights the room (ADR-0022), still
for the take (ADR-0023, and `CONTROL_AE_LOCK` on ADR-0033's rung 1). White balance can follow the same
rule, and for the same reason.

The reference Pixel 10 (Android 17, API 37, build `CP2A.260805.005`) supports both halves:
`CONTROL_AWB_AVAILABLE_MODES` includes `AUTO`, and `CONTROL_AWB_LOCK_AVAILABLE` is `TRUE` on both
logical cameras (static metadata, read 2026-09-09 via `dumpsys media.camera`; not yet through
`CameraCharacteristics`).

**Davide decided the product shape on 2026-09-10:**

1. Measuring is the default starting state; presets override it.
2. "Keep this" locks exactly what was measured — not the nearest preset — and holds across takes
   until the creator re-measures.
3. Pressing record without choosing locks what was measured, for that take.

## Decision

White balance has **three states**:

| State | What the camera does | What the creator sees |
|---|---|---|
| **Measuring** | `CONTROL_AWB_MODE_AUTO` | The preview follows the light. A readout: *"≈ 3400 K — closest preset 3200 K"*. |
| **Kept** | `AUTO` with `CONTROL_AWB_LOCK` | Frozen as measured, across takes, until "Re-measure". |
| **Preset** | ADR-0034's ladder | Exactly the chosen temperature where the camera allows it; otherwise the labelled approximation. |

- **The app opens Measuring.** This replaces PRD 6.1's "locked preset, default 5600 K".
- **Record while Measuring** engages `CONTROL_AWB_LOCK` at record start and releases it at record
  end, returning to Measuring. The implicit lock belongs to the take; Kept persists because the
  creator asked for it to.
- **Record while Kept or Preset** changes nothing: white balance is already locked.
- **White balance never moves during a take, in any state.** `Session` already refuses settings
  changes while recording, so no transition can happen mid-take. PRD 6.4's *"the recorded WB does not
  shift"* holds in all three states.
- **Kept is `CONTROL_AWB_LOCK`, not read-back gains.** Freezing the camera's own auto white balance
  keeps the vendor's colour rendering, so Kept carries none of ADR-0035's rendering risk. It also
  works on any camera that declares `CONTROL_AWB_LOCK_AVAILABLE`, including lenses without
  `MANUAL_POST_PROCESSING`. Measure-and-keep is therefore available on *more* cameras than exact
  Kelvin, and helps rung-3 cameras most: the ones whose presets can only be approximated.
- **The readout** turns the gains the camera reports in `AUTO` into a temperature with ADR-0035's
  inverse, and names the closest preset. On a camera without a usable calibration it says the light
  was measured and gives no number; the lock is unaffected.
- **Surviving a camera release.** `CONTROL_AWB_LOCK` lasts as long as the capture session. When
  ADR-0025 releases the camera and it reopens, a Kept lock would be lost. On reopen, Kept is restored
  by re-applying the last reported gains and colour transform with `CONTROL_AWB_MODE_OFF` and
  `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` on cameras with `MANUAL_POST_PROCESSING`. That path renders
  through the app's matrix rather than the vendor's, so ADR-0035's rendering gate covers it too.
  Elsewhere, reopening falls back to Measuring and says so on both surfaces.
- **The protocol change is additive** (ADR-0007: adding fields is backward compatible). The state
  document gains `whiteBalanceMode` and a nullable `measuredKelvin`; the settings command gains
  `measure` and `keep`. `whiteBalanceKelvin` stays, and its meaning narrows from "the white balance
  in force" to "the selected preset", in force only in Preset. A client that ignored
  `whiteBalanceMode` would show the last preset as if it applied — which cannot happen in practice,
  because the browser UI ships inside the app (ADR-0009), so phone and browser always change
  together. Nothing is renamed or removed, and `PROTOCOL_VERSION` stays at 2.

**PRD amendments**, to apply on acceptance:

- **6.1**, White balance row: *"Locked preset, default 5600 K"* → *"Measured automatically while
  setting up, and locked for every take; presets override. See 6.4."*
- **6.4**, opening: *"Two scenarios, each with three Kelvin presets. WB is always locked; auto WB is
  not the default because it drifts mid-take."* → *"While setting up, the camera measures white
  balance automatically and the preview follows the light. White balance is locked for every take:
  auto white balance never runs during a recording, because it drifts mid-take. The creator can keep
  the measurement, pick a preset, or just press record, which locks what was measured for that take.
  Presets come in two scenarios of three."* The preset table stays; its 5600 K default becomes the
  picker's initial selection.
- **6.4**, remove the P1 bullet *"'auto once' button that samples AWB, snaps to the nearest preset,
  and locks"*: the default now does this, without snapping.
- **6.4**, acceptance criteria: *"Given any preset is selected, when the scene changes, then the
  recorded WB does not shift"* → *"Given any white-balance state, when the scene changes during a
  take, then the recorded WB does not shift."* Add: *"Given the app is measuring, when the creator
  presses record, then WB is locked to the measured value for the whole take, and measuring resumes
  after it."* Add: *"Given the creator has kept a measurement, when they record several takes, then
  all use the same WB until they re-measure."*
- **6.8**, "Settable from the browser": *"white balance scenario and preset"* → *"white balance
  (measure, keep, or a scenario and preset)"*.
- **6.11**, remove *"'Auto once' white balance snap to nearest preset"*: it has become 6.4's default
  behaviour.

## Options Considered

### Option A: Measure while setting up, lock for the take, as the default (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Medium: three states, two lock mechanisms, one restore path |
| Risk | Low to medium: the lock is the platform's; the restore path shares ADR-0035's gate |
| Effort | About a week across `:domain`, `ManualControls`, the protocol and both UIs |
| Reversibility | High until release: presets remain, and the default is one line |

**Pros:** The creator no longer has to know their light, and mixed light is measured rather than
rounded. Kept renders with the vendor's colour. It works on more cameras than exact Kelvin. White
balance follows the same shape as exposure, for the same reason.
**Cons:** Auto white balance can be fooled by a strongly coloured scene — a saturated wall filling
the frame. The creator sees it in the preview and can pick a preset, but a creator who never looks
will lock it. Three states are more to explain than one picker.

### Option B: "Auto once" beside the presets, snapping to the nearest (PRD 6.4 P1 as written)

**Pros:** The smallest change; presets stay the default.
**Cons:** Snapping throws away the measurement in exactly the case it helps — mixed light between
presets — and the creator still has to know to press it. Davide chose against both halves.

### Option C: Presets only (status quo)

**Pros:** No new states.
**Cons:** Asks the creator to know their light, and on the reference phone every preset is an
approximation until ADR-0035 ships.

### Option D: Auto white balance running during the take

**Pros:** Always "right" for the moment.
**Cons:** Drifts mid-take — the one colour fault an editor cannot fix, because no single correction
fits both halves of the file. PRD 6.4 rules it out, correctly.

### Option E: Keep by reading back the gains and re-applying them with AWB off

**Pros:** One mechanism for Kept and for the restore path.
**Cons:** Renders through the app's colour transform instead of the vendor's, importing ADR-0035's
rendering risk into the everyday path; and it only works where `MANUAL_POST_PROCESSING` exists. It is
the right tool for restoring after a camera release, where the lock is already gone, and the wrong
one for the common case.

## Trade-off Analysis

The forces behind PRD 6.4's rule — no drift, one consistent white balance per file — are fully kept:
nothing moves during a take in any state. What changes is where the white balance comes from before
the take starts. The strongest alternative is Option B, the PRD's own P1. It helps only a creator who
knows to press it, and then rounds the answer off to the nearest preset. That is a poor trade in the
room the product sees most often, where light comes from both a window and a lamp.

Option E is the tempting simplification, and the reason not to take it is that the vendor's lock is
strictly better when it is available: same measurement, vendor rendering, more cameras. The app's own
matrix is kept for the one case where the lock no longer exists.

## Consequences

- Easier: a creator gets correct white balance in mixed light without knowing a Kelvin; a take never
  drifts; the reference phone's weak presets stop being the default.
- Easier: rung-3 cameras, which can only approximate presets, still get an exact measured lock.
- Harder: three states on two surfaces, and UI copy that has to make "Measuring / Kept / Preset"
  obvious (UI work, not this ADR).
- Harder: `whiteBalanceKelvin` narrows in meaning, and every reader of it must now consult
  `whiteBalanceMode` first.
- Revisit when:
  - `CONTROL_AWB_LOCK` fails to hold across a long take on a device — then Kept falls back to the
    restore path everywhere, and ADR-0035's rendering gate becomes the everyday gate;
  - creators lock visibly wrong white balance in the first sessions because a coloured scene fooled
    auto white balance — then whether to warn on an implausible measurement becomes a product
    question;
  - the Pixel 10's `AUTO` measurement and ADR-0035's inverse disagree by more than the readout's
    rounding, which would mean one of them is wrong.

## Action Items

1. [ ] Confirm `AUTO` and `CONTROL_AWB_LOCK_AVAILABLE` through `CameraCharacteristics` on the reference
       device (extend `CameraCapabilityReadTest`).
2. [ ] On the reference device: does auto white balance converge with `CONTROL_AE_MODE_OFF`, and does
       `CONTROL_AWB_LOCK` hold a 10-minute take? Echo `CONTROL_AWB_STATE` and the reported gains
       across the whole take; record the result here.
3. [ ] `:domain`: the three-state machine, with transitions at record start and end and host tests
       citing PRD 6.4's acceptance criteria.
4. [ ] `ManualControls`: the `AUTO`, lock and restore request paths; echo them through `echoes()`.
5. [ ] Protocol fields and commands; phone and browser UI.
6. [ ] The camera release-and-reopen path (ADR-0025), tested on the reference device.
7. [ ] Apply the PRD amendments above on acceptance.
