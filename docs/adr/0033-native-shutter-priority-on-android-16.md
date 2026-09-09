# ADR-0033: Use the platform's shutter-priority mode where a camera declares it, and keep the in-app loop as the rung below

**Status:** Proposed
**Date:** 2026-09-09
**Deciders:** Davide Mendolia
**PRD sections:** 6.1, 6.2, 6.3, 8-Q3
**Related ADRs:** [ADR-0005](0005-exposure-control-own-metering-loop.md), [ADR-0011](0011-per-lens-capability-gating.md), [ADR-0013](0013-multiplatform-strategy.md), [ADR-0017](0017-phase-0-verification-matrix.md), [ADR-0018](0018-preview-tap-for-metering-and-preview-frames.md), [ADR-0022](0022-two-exposure-responsiveness-modes.md), [ADR-0023](0023-lock-exposure-for-the-take.md), [ADR-0029](0029-portrait-lighting-read.md), [ADR-0032](0032-minimum-android-16.md), [ADR-0034](0034-kelvin-white-balance-on-android-16.md)

## Context

PRD 6.3 states the fact the whole exposure design was built on:

> Neither iOS nor Android exposes a native shutter-priority mode, and Android gives no metering
> feedback once auto-exposure is off.

ADR-0005 rests on it: the app meters its own frames, runs its own damped ISO controller, and derives
both light warnings from its own numbers. ADR-0022 gave that loop two speeds and ADR-0023 gives it
an off switch for the duration of a take.

**The Android half of that sentence stopped being true at API 36.** Android 16 adds
`CONTROL_AE_PRIORITY_MODE`, whose value `CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY`
does exactly what PRD 6.3 asks for and says cannot be had: the app fixes `SENSOR_EXPOSURE_TIME` and
auto-exposure, which stays **on**, moves sensitivity alone. Availability is declared per camera in
`CONTROL_AE_AVAILABLE_PRIORITY_MODES`, and the mode echoes back in `CaptureResult`, so it is
gateable and verifiable by the same means as the six keys `ManualControls` already sets.

Verified against `android.jar` for `compileSdk 37` on 2026-09-09; every constant named here exists
and carries `since="36"`. **It has not been confirmed on a device** — no phone answered
`adb devices` when this was written, so nothing below is a claim about the reference Pixel 10.

**ADR-0032 puts the floor at API 36, so this is not a question about versions.** The key is reachable
on every device the app runs on. What it is not is universally *available*: priority modes are a
driver declaration, and a phone updated to Android 16 may report none. This is therefore a per-camera
capability question of exactly the kind ADR-0011 answers, and it takes the same shape as ADR-0034's
white-balance ladder: use the platform's own control where the camera declares it, and keep our
implementation as the rung below.

**This is not ADR-0005's Option B.** That option was platform AE with antibanding, and it was
rejected because the HAL chooses the shutter: it picks exposures that band, and it extends exposure
in low light, which is PRD 6.1's third acceptance criterion failing by design. Exposure-time priority
removes both failures by construction — the shutter is ours, the flicker-safe ladder still selects
it, and the HAL is left with the one variable PRD 6.3 already calls automatic. PRD 6.1's "frame rate
does not drop below 30 fps in low light" is in fact *safer* here than under our own loop, because the
sensor is being told the exposure time rather than being trusted to leave it alone.

Two things come back with AE switched on that the app gave up in ADR-0005:

- **`CONTROL_AE_STATE` and an exposure offset.** This is literally PRD 6.3's original wording,
  *"read the exposure offset from the device"*, which ADR-0005 amended to "meter from the analysis
  stream" because the value did not exist with AE off.
- **`STATISTICS_SCENE_FLICKER`, continuously.** ADR-0005 closes by reserving a one-second AE-on
  window at session start so the P1 flicker confirmation (PRD 6.2) has somewhere to live. Under
  priority mode there is no window to reserve; AE is on for the whole session.

**What the app must keep owning even on the platform path**, because handing over AE hands over more
than ISO if nothing is said:

- **The flicker-safe shutter ladder.** ADR-0005 point 3 steps to 1/100 or 1/120 when the scene is
  overexposed at minimum ISO. The HAL will not do this — it has no notion of a mains grid — and it
  cannot even ask for it. The signal is available without metering for it specially: the result
  reports `SENSOR_SENSITIVITY`, and a sensitivity pinned at the lens minimum with the frame still
  above target is the definition of the ladder's trigger.
- **The face-weighted target.** ADR-0022 fixes an 18 % grey target (0.45 encoded) on a face-weighted
  geometric mean, and a HAL's centre-weighted average is not that. Two handles exist: `CONTROL_AE_REGIONS`,
  fed from the `STATISTICS_FACES` rectangles the app already reads and maps, on cameras where
  `CONTROL_MAX_REGIONS_AE` is above zero; and `CONTROL_AE_EXPOSURE_COMPENSATION`, in the device's own
  `CONTROL_AE_COMPENSATION_STEP`, as a slow trim against the same target.
- **Both light warnings.** They are computed from reported `SENSOR_SENSITIVITY` and the app's own
  luma, and neither depends on who moved the ISO.

The luma those last two need is not new work. ADR-0029 reads the key ratio and background separation
off the same face-weighted statistics outside a take, whether or not the app is doing AE, and
ADR-0023 already stops them during one. The metering path survives this ADR either way; what changes
is who consumes it.

## Decision

We will **use `CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY` on every camera that declares
it, and fall back to the in-app loop on cameras that do not.** Two rungs, chosen per camera at probe
time and reported in the capability report (PRD 6.10):

**Rung 1 — the camera declares `SENSOR_EXPOSURE_TIME_PRIORITY` in `CONTROL_AE_AVAILABLE_PRIORITY_MODES`.**

- `CONTROL_AE_MODE_ON` with `CONTROL_AE_PRIORITY_MODE = SENSOR_EXPOSURE_TIME_PRIORITY`, and
  `SENSOR_EXPOSURE_TIME` set from the flicker-safe ladder exactly as today. The HAL moves sensitivity;
  the app moves nothing.
- **The ladder stays ours.** It steps when the reported `SENSOR_SENSITIVITY` sits at the lens minimum
  while the face-weighted luma is still above target, and the too-bright warning still follows the
  rung above it. `SENSOR_FRAME_DURATION` stops being the frame-rate pin, because AE owns frame
  duration with AE on; the pin is `Range(30, 30)` through CameraX, which the session already sets.
- **Face weighting is asked for natively.** `CONTROL_AE_REGIONS` is set from the mapped
  `STATISTICS_FACES` rectangles on cameras reporting `CONTROL_MAX_REGIONS_AE > 0`, and omitted
  elsewhere. This reuses `SensorFace` and `FaceMapping` unchanged.
- **`CONTROL_AE_EXPOSURE_COMPENSATION` is the target trim, and is not built until measured.** If the
  Phase 3 comparison shows the HAL settling away from the 0.45 face target by more than the criterion
  allows, a heavily damped outer loop nudges compensation in the device's own step. Building it before
  the measurement would be inventing a correction for an error nobody has seen.
- **ADR-0023's lock becomes `CONTROL_AE_LOCK`** where `CONTROL_AE_LOCK_AVAILABLE` says so — cheaper
  and more exact than stopping our own filter, and with the same user-visible meaning.
- **ADR-0022's two damping speeds do not apply.** Convergence is the vendor's. This is the sharpest
  thing the rung gives up and it is recorded as such below.

**Rung 2 — the camera declares no priority mode, and every camera on iOS.** ADR-0005's loop exactly
as it is today, with ADR-0022's two speeds and ADR-0023's lock. Nothing about it changes.

Both rungs publish through the same `ExposureState`, so the phone HUD and the browser show the same
shutter and ISO readouts and neither surface knows which rung is in force. The rung itself appears in
the capability report beside `manualSensor`, because it is the difference between a settle time the
product specifies and one it observes.

**PRD amendments.** Three passages:

- 6.3's opening sentence becomes: *"iOS exposes no shutter-priority mode. Android has one from
  API 36 (`CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY`), which cameras declare
  individually; the app uses it where it is declared and runs its own metering loop everywhere else,
  including on iOS. The shutter, the flicker-safe ladder and both light warnings are the app's on
  both rungs. ADR-0033 defines them."*
- 6.3's acceptance criteria (*"ISO settles within 2 seconds and does not oscillate by more than one
  stop"*) are scoped: on rung 2 they are asserted in host tests against our damping, as today; on
  rung 1 they are verified per device against the vendor's AE, and a camera that fails them is a
  capability report entry, not a code change.
- ADR-0022's and ADR-0023's PRD text narrows to rung 2, except ADR-0023's user-visible lock, which
  exists on both rungs by different means.

## Options Considered

### Option A: Two rungs — platform mode where declared, our loop below (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Medium: two exposure paths, chosen per camera, sharing one state and one ladder |
| Risk | Medium: rung 1's settle behaviour is the vendor's and is verified per device |
| Effort | About a week, plus the Phase 3 comparison |
| Reversibility | High: rung 2 is the current code and is not going anywhere |

**Pros:** Uses the platform control the product spent ADR-0005 emulating; restores `CONTROL_AE_STATE`
and continuous `STATISTICS_SCENE_FLICKER`; gets OEM-tuned convergence, which is faster than
ADR-0022's setup profile and is the thing creators feel while lighting a room; PRD 6.1's low-light
frame-rate criterion becomes structurally safe; ADR-0023's lock becomes a platform key; and the
fallback is code that already exists and ships, so the second rung costs nothing to keep.
**Cons:** The settle-and-oscillation criteria stop being host-testable on rung 1, and ADR-0016 is
explicit that a green CI run says nothing about what a camera does. ADR-0022's two speeds are lost
there. Exposure behaviour is no longer identical across platforms, which ADR-0013 built the shared
`:domain` loop to guarantee — iOS and rung-2 Android keep it, rung-1 Android does not.

### Option B: Keep the in-app loop everywhere and only record the capability

| Dimension | Assessment |
|---|---|
| Complexity | Lowest |
| Risk | Lowest today, and declines a platform capability indefinitely |
| Effort | Hours |
| Reversibility | High |

**Pros:** One exposure behaviour on both platforms; every PRD 6.3 criterion stays host-tested; nothing
about ADR-0022 or ADR-0023 narrows.
**Cons:** Declines the control the PRD wanted and could not have, on every device the app supports;
keeps the app owning AE quality for no benefit the user sees; and leaves `CONTROL_AE_STATE` and
continuous scene-flicker detection on the table, the second of which is the P1 flicker confirmation
PRD 6.2 asks for.

### Option C: Adopt the platform mode and delete the in-app loop on Android

**Pros:** One Android path, least code.
**Cons:** Not available. Priority modes are a driver declaration and ADR-0032 is explicit that the API
floor says nothing about what a camera supports, so a rung below is mandatory. The loop also has to
exist for iOS regardless (ADR-0013), which makes deleting its Android use a saving of nothing.

### Option D: Platform mode while setting up, in-app loop during the take

**Pros:** Takes the HAL's fast convergence for the job ADR-0022 wrote its setup profile for, and keeps
our determinism where the file is being written.
**Cons:** Rejected on the transition. The switch would land at record start, and ADR-0022 states that
nothing is pushed to the sensor there precisely because the loop has already settled — handing
exposure from the HAL's target to ours at that instant pushes a step at the one moment the product
guarantees stillness.

## Trade-off Analysis

Option B was the previous form of this ADR, and its case rested on two things. The first was version
fragmentation, which ADR-0032 removed. The second was that a HAL's AE is unspecifiable — and that is
true, but it is an argument about *how we verify*, not about what the creator gets. Weighed against
it: a shutter-priority mode is precisely what PRD 6.3 describes, and ADR-0005 exists only because the
platform did not offer it. Continuing to emulate a control that now exists, on every device the app
supports, needs a stronger reason than the tidiness of the test suite.

The three costs are real and are not waved away. Two of them are contained. The face-weighted target
survives through `CONTROL_AE_REGIONS`, which is the same rectangles the app already maps, with
exposure compensation held in reserve behind a measurement. The flicker-safe ladder survives intact
because the shutter never left, and its trigger is readable from the results the session already
delivers. What genuinely goes is ADR-0022's two damping speeds on rung 1 — and its own Context is
the reason that is acceptable: the setup profile exists because our loop was too slow while a creator
moves a lamp, which is exactly where an OEM-tuned AE is strongest.

The remaining cost is parity, and it is the one to watch. ADR-0013's guarantee was that exposure
behaves identically because both platforms run the same code; after this, that holds for iOS and
rung-2 Android and not for rung 1. The mitigation is that the *outputs* stay shared — one
`ExposureState`, one ladder, one pair of warnings, one set of readouts — so what diverges is the
convergence curve rather than the product's behaviour. The Phase 3 comparison exists to put a number
on how far it diverges before Phase 4 has to live with it.

## Consequences

- Easier: the control PRD 6.3 asked for is used where it exists; scene-flicker detection (PRD 6.2 P1)
  needs no AE-on window; ADR-0023's lock is one key; the low-light frame-rate criterion is structural
  rather than tuned.
- Easier: rung 2 is the code that ships today, so the fallback needs no new work and no new tests.
- Harder: two exposure paths to reason about, and PRD 6.3's settle criteria mean different things on
  each. The capability report has to say which rung a camera is on, and support questions will follow
  it.
- Harder: ADR-0013's shared-exposure guarantee narrows to rung 2 and iOS, and ADR-0022 narrows with
  it. Both are Proposed and can absorb the change in their own text if this is accepted.
- Harder: a bad vendor AE is now a bug we cannot fix in `:domain`. The escape hatch is per camera —
  demote it to rung 2 in the capability gate, which is the shape ADR-0011 already uses.
- Revisit when:
  - the Phase 3 comparison shows the HAL settling away from the 0.45 face target by more than
    PRD 6.3 allows, which turns the exposure-compensation trim from reserve into work;
  - a camera in the widened matrix (#29) declares the mode and then oscillates or pumps, which makes
    the demotion path above real rather than theoretical;
  - iOS gains an equivalent, at which point rung 1 becomes the shared path and ADR-0013's guarantee
    is restored at the higher level rather than the lower one.

## Action Items

1. [ ] Add `aePriorityModes`, `maxAeRegions` and `aeLockAvailable` to `LensCapabilities` and the probe
       in `ManualControls`, with `:domain` fixtures for a camera that declares the mode and one that
       does not.
2. [ ] Implement the rung selection: rung 1 sets `CONTROL_AE_MODE_ON`,
       `CONTROL_AE_PRIORITY_MODE`, `SENSOR_EXPOSURE_TIME` and `CONTROL_AE_REGIONS`; rung 2 is
       unchanged. Both feed one `ExposureState`.
3. [ ] Move the ladder's overexposure trigger onto reported `SENSOR_SENSITIVITY` at the lens minimum,
       so it is rung-independent, and cover it with `:domain` fixtures for both rungs.
4. [ ] Add `CONTROL_AE_PRIORITY_MODE` and `CONTROL_AE_MODE` to `echoes()` so #20 reports whether the
       camera honoured the rung it was given.
5. [ ] Amend the three PRD passages listed under Decision, citing this ADR, and narrow ADR-0022's and
       ADR-0023's text to rung 2 where it refers to damping.
6. [ ] Confirm on the reference Pixel 10 which priority modes the main camera declares, and record the
       answer here with the handset name (`adb -s <serial> shell getprop ro.product.model`), per
       ADR-0017.
7. [ ] Phase 3: record a step-change-in-light trace on both rungs on the same scene, and write the
       settle times, the ISO at rest, the face-target error and whether either pumps into the Revisit
       triggers above.
