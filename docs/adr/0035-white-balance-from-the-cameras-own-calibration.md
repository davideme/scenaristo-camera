# ADR-0035: Turn Kelvin into white balance with the camera's own colour calibration, not a generic curve

**Status:** Proposed
**Date:** 2026-09-10
**Deciders:** Davide Mendolia
**PRD sections:** 6.4, 6.10, 8-Q2
**Related ADRs:** [ADR-0002](0002-android-capture-stack.md), [ADR-0011](0011-per-lens-capability-gating.md), [ADR-0013](0013-multiplatform-strategy.md), [ADR-0017](0017-phase-0-verification-matrix.md), [ADR-0033](0033-native-shutter-priority-on-android-16.md), [ADR-0034](0034-kelvin-white-balance-on-android-16.md), [ADR-0036](0036-measure-white-balance-then-lock-it-for-the-take.md)

## Context

ADR-0034 gave white balance three rungs: the platform's Kelvin mode where a camera declares `CCT`,
ADR-0011's gains path where it declares `MANUAL_POST_PROCESSING` without `CCT`, and the nearest
preset otherwise. The reference Pixel 10 (Android 17, API 37, build `CP2A.260805.005`) lands on the
middle rung: both logical cameras declare `MANUAL_POST_PROCESSING`, neither declares `CCT` (measured
2026-09-09 through `CameraCharacteristics`). The middle rung has never been built, so **every take on
the reference phone uses the preset approximation** — the path PRD 6.4 reserves for *"a device
without manual WB gains"*. The phone has them.

ADR-0034 deferred that rung on the grounds that no available hardware selected it, and its Action
Item 6 asked whether the deferral still stood once the measurement said otherwise. **Decided
2026-09-10 (Davide): build it now, and build it this way.**

ADR-0011 had specified the method:

> Presets map to `COLOR_CORRECTION_GAINS` through a single generic Kelvin-to-RGB-gain curve
> normalised by the device's reported gains under `DAYLIGHT` AWB (sampled once at probe time, which
> calibrates the curve's 5600 K point per device).

It has three weaknesses, and the third would have produced wrong colour on its own:

1. **One calibrated point.** The curve is anchored at 5600 K and its shape everywhere else is
   borrowed. 3200 K — the one temperature PRD 6.4 writes its acceptance criterion for — is the preset
   furthest from the anchor.
2. **"Generic" never names a source.** No curve was identified, and #24's own body concedes that one
   device passing is weak evidence the curve generalises.
3. **Gains are half of it.** With `CONTROL_AWB_MODE_OFF`, `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX`
   makes the camera apply *both* `COLOR_CORRECTION_GAINS` and `COLOR_CORRECTION_TRANSFORM`. ADR-0011
   sets only the gains, which leaves the colour matrix at whatever it last was — the same failure
   `ManualControls` already documents for AWB `OFF` without gains.

**The camera already carries a better answer.** Its static metadata on the reference Pixel 10 (read
2026-09-09 via `dumpsys media.camera`; not yet through `CameraCharacteristics`) holds a complete
two-illuminant colour calibration:

| Key | Value |
|---|---|
| `SENSOR_REFERENCE_ILLUMINANT1` | `STANDARD_A` — CIE illuminant A, ≈ 2856 K |
| `SENSOR_REFERENCE_ILLUMINANT2` | `D65` (21) — ≈ 6504 K |
| `SENSOR_COLOR_TRANSFORM1`/`2`, `SENSOR_CALIBRATION_TRANSFORM1`/`2`, `SENSOR_FORWARD_MATRIX1`/`2` | present, with values, on every camera |

These are the DNG colour-calibration tags: `DngCreator` writes them into every RAW file, and raw
converters use them to render a RAW at any colour temperature. Both logical cameras declare RAW
(capability 3). Two properties make them the right source:

- **They bracket every preset.** 3200, 4500, 5600 and 6500 K all lie inside 2856–6504 K, so no
  preset needs extrapolation.
- **They are measurements, at temperatures that are exact.** Each is the maker's per-sensor
  calibration against a standard illuminant — which is precisely what a generic curve lacks.

**Calibration belongs to a sensor, not to a logical camera.** The same dump shows each physical
sensor carrying its own matrices, with each logical camera mirroring its default sensor. The first
row of `SENSOR_FORWARD_MATRIX1`:

| Camera | First row |
|---|---|
| 0 (logical, back) and 2 (physical) | 0.6116, 0.1736 |
| 4 (physical) | 0.7391, 0.1888 |
| 3 (physical) | 0.7391, 0.1889 |
| 1 (logical, front) and 6 (physical) | 0.6217, 0.2079 |

A white balance computed from the logical camera's matrices while the HAL serves frames from the
telephoto would apply one sensor's colour science to another. **This is the opposite of ADR-0033's
constraint**, where the priority-modes key exists only on the logical camera and must be read there.
The two rules sit next to each other in `ManualControls` and must not be merged.

## Decision

We will **compute rung 2 from the camera's own two-illuminant calibration**, replacing ADR-0011's
generic-curve method. ADR-0011's gating and ADR-0034's three rungs are unchanged; only how the middle
rung turns a temperature into a request changes.

- **The arithmetic is the DNG specification's** (Adobe DNG Specification, chapter 6, "Mapping Camera
  Color Space to CIE XYZ Space"). For a target temperature: weight the two calibrations linearly in
  inverse temperature (mired) between the illuminants; compute the camera-space neutral for that
  temperature's white point; take the gains as its reciprocal, normalised to green; build the colour
  transform from the interpolated forward matrix into linear sRGB. Outside the calibrated range the
  weight clamps to the nearer illuminant, as the specification does.
- **It runs both ways.** Inverted, the same interpolation finds the temperature whose neutral matches
  a set of gains the camera reports. ADR-0036 needs that direction, to tell a creator what the camera
  measured; nothing else here does.
- **It lives in `:domain`** as pure arithmetic on matrices, host-tested against published reference
  values (ADR-0010, ADR-0015). It is not shared behaviour in ADR-0013's sense: iOS asks the OS for the
  same conversion (`AVCaptureDevice.deviceWhiteBalanceGains(for:)`), so the platforms agree at the
  level of "Kelvin in, gains out", not code.
- **It reads from the sensor producing the frames:** the pinned physical id when a stream is pinned,
  otherwise the active physical sensor, which `ManualControls.activePhysicalId` already reads from
  `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`. When the active sensor changes, the gains and transform
  are recomputed for the new one.
- **The request carries all four keys:** `CONTROL_AWB_MODE_OFF`,
  `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX`, `COLOR_CORRECTION_GAINS` and `COLOR_CORRECTION_TRANSFORM`,
  set in `ManualControls` like every other interop key (ADR-0002).
- **No calibration, no rung 2.** A camera with `MANUAL_POST_PROCESSING` but without both illuminant
  sets takes rung 3, the labelled preset approximation. The generic curve is not built as a fallback:
  it would be an uncalibrated rung reached only by hardware we do not have.
- **It ships behind a rendering gate.** Taking the colour transform over replaces the vendor's own
  matrix, so the picture can change in saturation and hue, not only in warmth. Before rung 2 is
  enabled on a camera, the same scene is recorded with today's preset approximation and with rung 2
  at the matching temperature, and the two are compared side by side on the reference device. If
  rung 2 renders skin or saturation visibly worse, it does not ship on that camera, which stays on
  rung 3. The comparison is recorded here.
- **#24 becomes a verification, not a fit.** Grey card at 3200 K and 5600 K through rung 2, against
  PRD 6.4's ±300 K. A miss means a calibration reading or implementation defect to find, not a curve
  to tune — the shape ADR-0034 wanted for its own rung 1. Before a grey card is available, a sanity
  check needs none: in daylight, the gains this computes for 5500–6500 K should sit near the gains the
  camera's own `AUTO` white balance reports for the same scene.

**This reverses ADR-0034's deferral of rung 2 and replaces ADR-0011's rung-2 method.** Both ADRs are
Accepted and stay unedited; this ADR governs how rung 2 is computed and that it is built now. No PRD
text changes: 6.4's Android note, as amended by ADR-0034, already says "a device-calibrated curve",
which this makes literally true.

## Options Considered

### Option A: The camera's own calibration (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Medium: matrix interpolation, two directions, per-sensor inputs |
| Risk | Medium: the transform takeover can change the rendering; gated before shipping |
| Effort | About a week, plus the rendering comparison and #24 |
| Reversibility | High: the rung is one branch in `ManualControls` and a pure function in `:domain` |

**Pros:** Exact at every preset, from the maker's own per-sensor measurements at exact temperatures;
no curve to invent or fit; #24 becomes a verification whose failure means something specific; the
inverse gives ADR-0036 its readout for free.
**Cons:** Owning the colour transform is a real risk to the look, which is why it is gated; the
calibration must be read per sensor; the arithmetic has to be right, which is why it is host-tested
against reference values.

### Option B: ADR-0011's generic curve, normalised at `DAYLIGHT`

| Dimension | Assessment |
|---|---|
| Complexity | Low to medium |
| Risk | High: one anchor, unnamed shape, and no colour transform |
| Effort | Days, plus a curve fitted by #24 |
| Reversibility | High |

**Pros:** Already Accepted; needs no new ADR.
**Cons:** One calibrated point at 5600 K and a borrowed shape at the other three presets; no source
named for "generic"; sets gains without the transform the camera applies alongside them; #24 would
have to *fit* it, which is the weak form of evidence #24's own body warns about.

### Option C: Sample the camera's AWB presets and interpolate between them

Read the gains and transform the camera reports under `INCANDESCENT`, `FLUORESCENT`, `DAYLIGHT` and
`CLOUDY_DAYLIGHT`, and interpolate in mired between them.

**Pros:** Uses the vendor's tuned numbers, colour matrix included, so the look is the vendor's.
**Cons:** The anchors are the presets' *nominal* temperatures, which nobody has measured — the exact
error this ADR exists to remove. The vendor's measured values have a better home: ADR-0036's "Keep
this", which locks what `AUTO` measured without converting it at all.

### Option D: Keep deferring

Rejected by the Action Item 6 decision. It leaves the only phone in the matrix on the degraded path
PRD 6.4 reserves for phones without manual gains.

## Trade-off Analysis

Against Option B the case is that B is not really calibrated. Its one anchor is at the temperature
furthest from the one the PRD tests, its shape is unsourced, and it omits half of what the camera
applies. Option A replaces all three problems with data the maker already measured, per sensor, at
exact temperatures that bracket every preset.

The cost that A adds — owning the colour transform — is real, and B carries it too (B would set a
transform as well, or leave a stale one). So the rendering risk is not a reason to prefer B; it is a
reason to gate whichever is built, which this ADR does.

Against Option C, the decisive point is that C's anchors are unmeasured. It trades the generic
curve's unknown shape for four unknown temperatures.

## Consequences

- Easier: exact presets on the reference phone, without a curve to invent; #24 closes as a
  verification; ADR-0036's readout comes from the same arithmetic.
- Easier: the "≈ approximated" label can come off on the reference phone once the rendering gate
  passes, which is what PRD 6.4 intends for a phone with manual gains.
- Harder: the app owns the colour transform on rung 2, and a poor one changes how skin looks.
- Harder: calibration is per sensor. A lens switch recomputes, and the probe must never read it from
  the logical camera — the reverse of the priority-modes rule in the same class.
- Revisit when:
  - the rendering gate fails on the reference device — rung 2 then stays off there, and the choice
    between living with the approximation and a vendor-derived transform becomes the open question;
  - #24's grey card misses ±300 K through rung 2 — investigate the calibration reading and the
    arithmetic before touching the tolerance, which is Davide's;
  - a device in #29 declares `MANUAL_POST_PROCESSING` without a usable calibration — it takes rung
    3, and whether that camera deserves a fallback curve becomes a real question with hardware
    behind it.

## Action Items

1. [ ] Confirm the calibration through `CameraCharacteristics` on the reference device: extend
       `CameraCapabilityReadTest` to report `SENSOR_REFERENCE_ILLUMINANT1`/`2` and the presence of the
       colour, calibration and forward matrices on every camera, logical and physical.
2. [ ] `:domain`: temperature → (gains, transform) and gains → temperature, with fixtures against
       published reference values and against the reference device's matrices.
3. [ ] `ManualControls`: read the calibration from the sensor producing frames; the rung-2 request
       path with all four keys; echo the gains and transform back through `echoes()`.
4. [ ] The rendering gate on the reference device, recorded here.
5. [ ] The daylight sanity check against the gains `AUTO` reports, recorded here.
6. [ ] Once this ADR is Accepted, re-scope #24 from fitting a curve to verifying rung 2.
