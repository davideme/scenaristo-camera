# ADR-0034: Set white balance in Kelvin where the device offers it, and keep the gains curve as the rung below

**Status:** Proposed
**Date:** 2026-09-09
**Deciders:** Davide Mendolia
**PRD sections:** 6.4, 6.10, 8-Q2
**Related ADRs:** [ADR-0002](0002-android-capture-stack.md), [ADR-0011](0011-per-lens-capability-gating.md), [ADR-0013](0013-multiplatform-strategy.md), [ADR-0017](0017-phase-0-verification-matrix.md), [ADR-0033](0033-native-shutter-priority-on-android-16.md), [ADR-0032](0032-minimum-android-16.md)

## Context

PRD 6.4 states a platform limit and builds a workaround on it:

> Android note: there is no direct Kelvin API. The app converts Kelvin to RGB gains with a
> device-calibrated curve, falling back to the platform AWB modes (INCANDESCENT ≈ 3000 K,
> FLUORESCENT ≈ 4000 K, DAYLIGHT ≈ 5500 K, CLOUDY ≈ 6500 K) on devices that do not support manual
> colour gains.

ADR-0011 turned that into two rungs: `MANUAL_POST_PROCESSING` gets `COLOR_CORRECTION_GAINS` from a
generic Kelvin-to-gain curve normalised per device by the gains reported under `DAYLIGHT` AWB, and a
lens without the capability gets the nearest named preset with the UI saying so. Only the second
rung is built. The first is #24 — a grey card at 3200 K and 5600 K — and `WhiteBalancePresets.kt`
says out loud that the Kelvin numbers in `AwbApproximation` are "the platform's nominal ones, not
measurements".

**The premise is false at API 36.** Android 16 adds `COLOR_CORRECTION_MODE_CCT` together with
`COLOR_CORRECTION_COLOR_TEMPERATURE` (an `Integer`, in Kelvin) and `COLOR_CORRECTION_COLOR_TINT`.
The supported modes are declared per camera in `COLOR_CORRECTION_AVAILABLE_MODES`, the reachable
temperatures in `COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE`, and both values echo back in
`CaptureResult`. There is now a direct Kelvin API, and it is the same kind of interop key as the six
`ManualControls` already sets.

Verified against `android.jar` for `compileSdk 37` on 2026-09-09; every constant named here exists
and carries `since="36"`.

> **Measured on the reference device, 2026-09-09 — rung 1 does not exist here.** `Pixel 10`, serial
> Android 17 / API 37, build `CP2A.260805.005`, confirmed with
> `getprop ro.product.model` and read with `adb shell dumpsys media.camera`.
> `android.colorCorrection.availableModes` is `[0 1 2]` — `TRANSFORM_MATRIX`, `FAST`,
> `HIGH_QUALITY` — on every camera. `COLOR_CORRECTION_MODE_CCT` is `3` and is not in the list, and
> `android.colorCorrection.colorTemperatureRange` does not appear in the dump at all. **The
> reference device selects rung 2**, so PRD 6.4's Kelvin promise still rests on the gains curve
> nobody has built. Per ADR-0017 this is a claim about one handset, and an absent mode is a driver
> declaration that a later build could change.
>
> **Confirmed through the API, 2026-09-09.** The reading above is `dumpsys`, i.e. static HAL
> metadata, where a conclusion drawn from a *missing* key is one inference short of proof.
> `CameraCapabilityReadTest` (`:app` instrumented test) asks through
> `CameraCharacteristics.getKeys()` and `.get()`, and the answer is stronger: on **all seven**
> cameras — both logical and all five physical — `COLOR_CORRECTION_AVAILABLE_MODES` is present and
> reads `[0, 1, 2]`. `CCT` (3) is declared unsupported rather than merely unmentioned, and
> `COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE` is absent from `getKeys()` and reads `null` on every
> one. Rung 1 is unreachable on this handset by the device's own enumeration.
>
> The methodological caution was still worth having, and this same run proved it: for
> `CONTROL_AUTOFRAMING_AVAILABLE`, `dumpsys` prints `[FALSE]` in the HAL metadata while `getKeys()`
> does not list the key at all and `get()` returns `false` on the logical cameras and `null` on the
> physical ones. The two layers genuinely disagree about what exists, which is exactly why a
> capability decision belongs on the API side.
>
> This is the case the ADR's own Context anticipates: an API 36 floor guarantees the keys exist and
> says nothing about what a camera supports. The device is on **API 37**, a level past the one that
> introduced the key, and still does not declare it — which is the clearest possible evidence that
> the rungs below are not a formality.

Three facts shape how far this reaches.

**It sits inside ADR-0011's gate, not beside it.** `COLOR_CORRECTION_MODE` is only consulted with
`CONTROL_AWB_MODE_OFF`, and AWB off is the `MANUAL_POST_PROCESSING` tier. So this changes what the
capable rung *does*; it does not promote any lens that ADR-0011 sends to the preset fallback. The
degrade rule, which is the part of ADR-0011 that Davide accepted as an answer to Open Question 1,
is untouched.

**It is at the floor, and that changes what the fallback is for.** ADR-0032 sets `minSdk` to
API 36, so the keys exist on every device the app runs on and no rung is a version fallback. What
survives is the distinction PRD 6.10 already draws for `MANUAL_SENSOR`: an OS version guarantees an
API exists, a driver declares whether the camera supports it. A phone updated to Android 16 can
report no `CCT` in `COLOR_CORRECTION_AVAILABLE_MODES` at all, and a device that supports it on the
main camera may not on the ultrawide. The rungs below are therefore per-camera fallbacks, which is
precisely the shape ADR-0011 already has, and they must not be simplified away on the grounds that
the floor is Android 16.

**The matrix cannot test the fallback.** ADR-0017's reference device is one Pixel 10 on Android 16,
which is expected to be the rung-1 case. Whatever is written for rungs 2 and 3 is fixture-tested and
nothing more, exactly as ADR-0011's per-lens gating already is, until #29 widens the matrix.

## Decision

We will add a **top rung to ADR-0011's white balance ladder** and leave the two below it as they
are. Per lens, in order:

1. **`COLOR_CORRECTION_AVAILABLE_MODES` contains `CCT`, and AWB off is available** →
   `CONTROL_AWB_MODE_OFF` with `COLOR_CORRECTION_MODE_CCT`,
   `COLOR_CORRECTION_COLOR_TEMPERATURE` set to the selected preset and
   `COLOR_CORRECTION_COLOR_TINT` set to `0`. PRD 6.4's "tint fixed at 0 in v1" becomes a value the
   app states rather than one it inherits.
2. **`MANUAL_POST_PROCESSING` without `CCT`** → ADR-0011's `COLOR_CORRECTION_GAINS` curve,
   unchanged, still unbuilt, still #24.
3. **Neither** → ADR-0011's nearest-preset approximation with the label PRD 6.4 requires,
   unchanged and already shipping.

This is the same shape ADR-0033 gives exposure, and deliberately so: use the platform's own control where the camera declares it, keep ours as the rung below, and let the capability report say which one a lens got.

A preset outside `COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE` is clamped to the range and **labelled
the way rung 3 labels an approximation**. A device that cannot reach 3200 K is the same honesty
problem as a device that has to approximate it, and PRD 6.4 already requires the app to say which
preset is approximated and by what.

Rung 2 stays specified and stays unbuilt until a device that needs it exists in the reference matrix
(#29). Writing an uncalibrated curve for a rung no available hardware selects would be a measurement
we cannot take defended by a test that proves nothing.

The rung in use joins the capability report (PRD 6.10) beside `manualSensor` and
`manualPostProcessing`, because it is what the UI's "≈ approximated" label is derived from and
because rung 1 is the only one that can honestly drop that label.

**This amends PRD 6.4's Android note.** Replace it with:

> Android note: from API 36 the app sets Kelvin directly
> (`COLOR_CORRECTION_MODE_CCT` with `COLOR_CORRECTION_COLOR_TEMPERATURE`) on cameras that declare
> it. Below that, or on a camera that does not, it converts Kelvin to RGB gains with a
> device-calibrated curve, and falls back to the platform AWB modes (INCANDESCENT ≈ 3000 K,
> FLUORESCENT ≈ 4000 K, DAYLIGHT ≈ 5500 K, CLOUDY ≈ 6500 K) on devices without manual colour gains.
> ADR-0034 defines the three rungs and which one each lens gets.

**#24 changes shape on a rung-1 device.** It stops being "calibrate our curve against a grey card"
and becomes "verify the vendor's key against PRD 6.4's ±300 K criterion". One grey card either way,
and the same two temperatures — but a failure now means a device defect to report and gate on,
rather than a curve to fit. On the reference Pixel 10, which is rung 1, that is the measurement #24
should take.

## Options Considered

### Option A: Three rungs, CCT on top (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Low: one characteristic check, two keys, one report field |
| Risk | Low: rungs 2 and 3 are unchanged and rung 1 is verifiable by grey card |
| Effort | Days, plus #24 |
| Reversibility | High: the rung is a branch in one class |

**Pros:** Exact Kelvin on the reference device, which is what PRD 6.4 promises and what the ±300 K
criterion was written for; retires the calibration guesswork where the platform now does it;
`COLOR_CORRECTION_COLOR_TEMPERATURE` is a `CaptureResult` key, so the app can verify what it asked
for the way `echoes()` already verifies exposure; the ladder degrades in the order ADR-0011 already
established, so nothing about the gate changes.
**Cons:** Three paths to reason about, two of which no available hardware exercises; the exactness
is the vendor's and has to be trusted until grey-carded.

### Option B: CCT only — adopt it and abandon the gains curve

| Dimension | Assessment |
|---|---|
| Complexity | Lowest |
| Risk | Medium: a documented promise silently narrows |
| Effort | Days |
| Reversibility | High |

**Pros:** Two rungs instead of three, and #24 disappears entirely. In practice identical to Option A
today, since rung 2 is unbuilt and no device in the matrix selects it.
**Cons:** PRD 6.4's ±300 K acceptance criterion would hold only on API 36 devices that declare CCT,
and every other device would drop straight to four named presets whose real temperatures nobody has
measured. That is a narrowing of a stated promise, and it is Davide's to make, not this ADR's.
Option A reaches the same place today while leaving the promise standing.

### Option C: Defer to the CameraX 1.7 revisit

**Pros:** One interop migration instead of two.
**Cons:** Nothing here needs 1.7. These are capture request options exactly like the six already
set, they go in the one class ADR-0002 confines interop to, and they migrate with everything else
when 1.7 lands. Deferring would leave the PRD asserting something untrue for a release with no
benefit.

### Option D: Take the API 36 floor as sufficient and set the CCT keys unconditionally

**Pros:** One path, no capability check, no rungs to describe.
**Cons:** Wrong for the same reason ADR-0011 exists. `COLOR_CORRECTION_AVAILABLE_MODES` is a driver
declaration and ADR-0032 is explicit that its floor guarantees the keys exist and nothing about what
a camera supports. Setting `COLOR_CORRECTION_MODE_CCT` on a camera that does not list it is a
request the HAL is free to ignore, which produces the failure this product cannot tolerate: an
unlocked white balance that looks locked, drifting mid-take with nothing in the UI to say so.

## Trade-off Analysis

The real choice is A against B, and it is about what the PRD is allowed to promise. Both put exact
Kelvin on the reference device and both leave the shipping fallback exactly as it is today. B is
honest about the fact that rung 2 has never been built and might never be; A is honest about the
fact that PRD 6.4's ±300 K criterion is written for every device, not for the ones that happen to
run Android 16.

A wins because the two are indistinguishable in code today — rung 2 is unbuilt under both — and
differ only in what the document claims. Keeping the rung specified costs a paragraph and preserves
a promise; deleting it would quietly rescope an acceptance criterion in an ADR about a different
subject. If Davide wants that promise narrowed, it deserves its own decision, taken against a device
that actually lands on rung 2 rather than against the reference Pixel 10 that does not.

Against Option C, the argument is that a false sentence in the PRD is the defect being fixed, and
waiting a release to fix it buys nothing.

## Consequences

- Easier: exact white balance on devices that offer it, with no curve to calibrate and no per-device
  table; the requested Kelvin is verifiable against the reported Kelvin the same way exposure is.
- Easier: the P1 "auto once" of PRD 6.4 — sample AWB, snap to the nearest preset, lock — becomes a
  read of `COLOR_CORRECTION_COLOR_TEMPERATURE` from a result taken with AWB in auto, instead of
  inverting gains through a curve that does not exist yet.
- Easier: a tint control becomes reachable if it is ever wanted. It is not wanted in v1 — PRD 6.4
  fixes tint at 0 and this ADR sets that value explicitly rather than proposing a control.
- Harder: three rungs to describe in the capability report and in the UI's label logic, two of which
  the reference matrix cannot exercise.
- Harder: iOS has its own path (locked white balance with device gains, ADR-0011), and the rung
  structure is Android's alone. `:domain` keeps only the Kelvin-to-preset arithmetic that both share.
- Revisit when: #24's grey card on the reference device misses ±300 K with rung 1 — which would make
  the vendor key no better than the presets and put rung 2 back on the critical path; or when #29
  brings a device that selects rung 2 or 3, at which point the unbuilt rung acquires hardware and a
  reason to exist.

## Action Items

1. [ ] Add the rung selection to `ManualControls` — `COLOR_CORRECTION_AVAILABLE_MODES` and
       `COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE` in the capability probe, the CCT keys on the
       request path, with the Kelvin-to-rung decision in `:domain` and fixtures for all three rungs.
2. [ ] Amend PRD 6.4's Android note as quoted under Decision, citing this ADR.
3. [ ] Add the rung to the capability report (PRD 6.10) and drop the "≈ approximated" label on
       rung 1.
4. [x] **Confirm on the reference Pixel 10 which colour-correction modes the main camera declares.**
       Measured 2026-09-09 on the reference `Pixel 10`, Android 17 (API 37):
       `colorCorrection.availableModes = [0 1 2]`, no `CCT`; `colorTemperatureRange` absent. Rung 1
       is unreachable there. Written into Context above.
5. [ ] ~~Re-scope #24 to "grey-card the CCT key"~~ — **does not apply.** That re-scoping was
       predicated on the reference device being rung 1, and it is rung 2. #24 keeps its original
       shape: calibrate the Kelvin-to-gains curve. The re-scoping becomes live only if #29 brings a
       camera that declares `CCT`.
6. [ ] Reconsider deferring rung 2. This ADR left ADR-0011's gains curve unbuilt on the grounds that
       no available hardware selects it; the measurement above says the reference device does, and it
       is therefore the path every take on the only phone in the matrix actually takes. That is an
       argument for building #24 that this ADR did not have when it wrote the deferral — and it is a
       scope call for Davide, not a correction.
