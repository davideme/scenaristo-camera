# ADR-0005: Meter in-app and run our own ISO loop with a flicker-safe shutter ladder

**Status:** Accepted (2026-09-03, Davide; PRD 6.3 amended)
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 6.1, 6.2, 6.3, 8-Q3
**Related ADRs:** ADR-0002, ADR-0011

## Context
PRD 6.3 specifies shutter-priority behaviour that neither platform offers natively: hold shutter at 1/50 or 1/60, pick the lowest ISO that reaches target exposure, and warn instead of changing shutter when the scene is too bright or too dark. It says the app should "read the exposure offset from the device".

Two facts change the design:

1. **Android gives no metering feedback with AE off.** Manual shutter and ISO require `CONTROL_AE_MODE_OFF`. In that mode the HAL stops reporting `CONTROL_AE_STATE` and there is no exposure-offset value. iOS does expose `exposureTargetOffset` in custom exposure mode. To behave identically on both platforms the app must meter the image itself.
2. **Base ISO at 1/50 s overexposes ordinary daylit rooms.** A main camera at f/1.8 with 1/50 s and ISO 100 is correctly exposed at roughly 400 lux incident (EV ≈ 7.3); a device with base ISO 50 reaches about 800 lux. A desk beside a window on an overcast day is often 1 000–3 000 lux. Under the PRD as written, the "Too much light: close blinds" warning would appear in a large share of first sessions, on the scenario (natural light) the product explicitly supports. Exposures that are an integer multiple of half the mains period are also band-free: 1/100 s at 50 Hz and 1/120 s at 60 Hz. For a seated speaker, the motion-blur difference between 1/50 and 1/100 is not visible.

## Decision
We will:

1. **Meter in the app.** Compute a face-weighted log-luminance from the `ImageAnalysis` NV21 stream (ADR-0002), using Camera2 `STATISTICS_FACES` rectangles from the interop session capture callback when available and a centre-weighted window otherwise. Target is a mid-tone on the face (initially 45 % luma, tunable). The same metering code runs on iOS so that both platforms agree.
2. **Run a damped ISO controller.** Exposure error in stops drives ISO in ⅙-stop steps, with a ±0.15 EV dead-band, an exponential moving average over 5 frames, and a maximum slew of 1 stop per second. ISO is applied through `Camera2CameraControl.setCaptureRequestOptions` (the `ManualControls` class, ADR-0002). After each change the controller waits until a `CaptureResult` from the interop callback reports the new `SENSOR_SENSITIVITY` before measuring again, so pipeline latency does not cause oscillation. This meets PRD 6.3 acceptance (settle within 2 s, no oscillation above one stop).
3. **Add a one-rung flicker-safe shutter ladder.** When the scene is overexposed at the device's minimum ISO, step the shutter to 1/100 (50 Hz) or 1/120 (60 Hz) before showing the too-bright warning. The warning appears only when even that rung is overexposed. The current rung is displayed on phone and web. Users who want a fixed 1/50 can lock shutter manually, which disables the ladder.

Point 3 amends PRD 6.3 "Do not silently raise shutter speed" to "Do not raise shutter speed beyond the flicker-safe ladder, and always show the shutter in use." Point 1 amends "read the exposure offset from the device" to "meter from the analysis stream".

Grid detection stays as PRD 6.2. For the P1 flicker confirmation, the app can run AE in `ON` mode for the first second of a session and read `STATISTICS_SCENE_FLICKER` before switching to `OFF`; this is noted so the session start sequence keeps room for it.

## Options Considered

### Option A: Own metering + own ISO loop + ladder (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Medium: a small control loop plus luma statistics |
| Risk | Medium: tuning per device; Phase 0 measures it |
| Effort | 1 week plus tuning |
| Reversibility | High; it is app code with no platform coupling |

**Pros:** Identical behaviour on both platforms; face-weighted exposure is exactly what a talking head needs; the too-bright and too-dark warnings fall out of the same numbers; the ladder removes the most common false warning.
**Cons:** We own the AE quality; poorly tuned loops pump visibly.

### Option B: Platform AE with antibanding and FPS lock
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | High |
| Effort | Low |
| Reversibility | High |

**Pros:** Zero AE code; OEM-tuned metering.
**Cons:** `CONTROL_AE_ANTIBANDING_MODE` is advisory; HALs still choose shutters that band, drift ISO, and extend exposure in low light, which is the exact webcam failure the product exists to fix. No ISO ceiling. No control of settle behaviour.

### Option C: Own loop without the ladder (PRD as written)
**Pros:** Simplest statement of "shutter is always 1/50 or 1/60".
**Cons:** Frequent, unfixable "too bright" warnings in daylit rooms; users will conclude the app is broken or override manually, which the success metric "≤ 25 % override defaults" is designed to catch.

### Option D: Auto-select a slower lens
Telephoto lenses are typically f/2.4–f/2.8, one stop slower, and are also the recommended talking-head lens (PRD 6.5). This is complementary, not an alternative: the lens recommendation already exists; the ladder covers the main camera.

## Trade-off Analysis
Option B is cheaper but fails the core promise on real devices. Between A and C, the ladder is a handful of lines that converts a common hard failure into a silent success with a visible readout, and it stays within the flicker-free set. Users who prefer the strict rule keep it by locking shutter.

## Consequences
- Easier: one metering implementation for both platforms; warnings derive from the same values; no dependence on HAL AE quirks.
- Harder: per-device tuning of dead-band and slew; the analysis stream becomes mandatory even when no browser is connected.
- Revisit when: Phase 0 shows visible pumping that the damping cannot remove, a reference device reports faces unreliably with AE off, or CameraX overrides the interop AE keys on a reference device (then ADR-0002 Option C for the control path).

## Action Items
1. [ ] Amend PRD 6.3 wording as stated above and add "shutter in use" to the status readouts in 6.8.
2. [ ] Phase 0: record the ISO trace for a step change in light and verify settle time and overshoot on both reference devices.
3. [ ] Phase 0: measure typical lux at a window-side desk with the reference devices' base ISO to confirm how often the ladder engages.
4. [ ] Decide the noise threshold per device (PRD default ISO 800) from Phase 0 samples.
