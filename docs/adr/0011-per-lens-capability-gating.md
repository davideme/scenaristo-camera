# ADR-0011: Gate per lens: require MANUAL_SENSOR to record, degrade white balance without MANUAL_POST_PROCESSING

**Status:** Accepted (2026-09-03, Davide; PRD 6.10 and Open Question 1 amended)
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 6.4, 6.5, 6.10, 8-Q1, 8-Q2
**Related ADRs:** ADR-0002, ADR-0005, ADR-0007

## Context
Manual shutter and ISO on Android require the Camera2 `MANUAL_SENSOR` capability; app-set colour gains require `MANUAL_POST_PROCESSING`. Both are declared per camera ID by the OEM driver and are independent of the Android version and of the hardware level. A phone commonly has both on the main camera and neither on the ultrawide or telephoto. The PRD's blocking Open Question 1 asks whether to refuse recording on a lens without each flag or to ship a degraded mode, and recommends refusing for `MANUAL_SENSOR` and degrading for `MANUAL_POST_PROCESSING`. The product's core promise is flicker-free footage; a take with rolling bands is worse than no take. White balance, by contrast, has a workable platform fallback (`CONTROL_AWB_MODE` presets) whose main defect is drift, which `CONTROL_AWB_LOCK` mostly removes.

A secondary question (8-Q2) is how to map Kelvin presets to gains on devices that do support `MANUAL_POST_PROCESSING`, since Android has no Kelvin API.

## Decision
We will probe every camera at first launch through `Camera2CameraInfo` characteristics and `CameraInfo.isFeatureGroupSupported` (ADR-0002) and cache the result (re-probed on app update), producing the capability report of PRD 6.10 with these fields per lens: `uhd30`, `manualSensor`, `manualPostProcessing`, `hevcHardware` (device-wide), `equivalentFocalLengthMm`, `minIso`, `maxIso`, `exposureTimeRange`, `stableFrameDuration30` (whether [30, 30] is in `CameraInfo.getSupportedFrameRateRanges(sessionConfig)` for the UHD session). Then:

- **`manualSensor` false → recording is disabled on that lens.** The lens stays listed with the reason "No manual shutter on this lens: flicker cannot be prevented" and a button to switch to a lens that has it. If no lens on the device has it, the app records nothing and shows the capability report with a clear message (PRD 5, edge cases). There is no "record anyway" toggle in v1.
- **`manualPostProcessing` false → white balance degrades.** Presets map to the nearest `CONTROL_AWB_MODE` (3200 K → `INCANDESCENT`, 4500 K → `FLUORESCENT`, 5600 K → `DAYLIGHT`, 6500 K → `CLOUDY_DAYLIGHT`), then `CONTROL_AWB_LOCK` is set so the mode's result does not drift. The UI labels the preset "≈ approximated (Daylight mode)" as PRD 6.4 requires. Tint control is unavailable (it is fixed at 0 in v1 anyway).
- **`manualPostProcessing` true → Kelvin via a generic curve first.** Presets map to `COLOR_CORRECTION_GAINS` through a single generic Kelvin-to-RGB-gain curve normalised by the device's reported gains under `DAYLIGHT` AWB (sampled once at probe time, which calibrates the curve's 5600 K point per device). A per-device table is only added if Phase 0 grey-card tests miss the ±300 K acceptance on a reference device.
- **`uhd30` false → 1080p30** with a persistent notice, as PRD 6.1 allows. **`stableFrameDuration30` false → recording disabled** on that lens, same treatment as `manualSensor`, because constant 30 fps is a P0 requirement.
- All gating lives in `:domain` (ADR-0010) as a pure function from the capability report to the allowed control set, so the same rules apply on iOS with its own probe (custom exposure mode support, locked white balance with device gains).
- `uhd30` is answered by the `UHD_RECORDING` feature group check rather than by stream-combination tables.

## Options Considered

### Option A: Refuse on `manualSensor`, degrade on `manualPostProcessing` (chosen; PRD recommendation)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | Low: honest about what the device can do |
| Effort | Low |
| Reversibility | High |

**Pros:** Never ships flicker; keeps the app usable on every device with at least one capable lens, which covers the main camera on nearly all targets. WB approximation is visible and documented.
**Cons:** Some ultrawide and telephoto lenses become "view only"; users may not understand why until they read the report.

### Option B: Degrade both (platform AE with antibanding on lenses without `manualSensor`)
**Pros:** Every lens records.
**Cons:** Reintroduces the exact failure the product exists to remove, on the lenses users would pick for a better look (telephoto). Violates goal 2 silently.

### Option C: Refuse both
**Pros:** Purist.
**Cons:** Excludes devices whose main camera lacks `manualPostProcessing` but has `manualSensor`, for a defect (WB approximation) that is minor and visible.

### Option D: Gate on hardware level or OS version instead of flags
**Cons:** The PRD already rejects this: flags are per lens and independent of level and version. Not viable.

## Trade-off Analysis
Option A follows the product's own priority order: flicker is non-negotiable, colour is approximable. The cost, lenses that cannot record, is exactly the case the edge-case user story asks to be told about plainly.

## Consequences
- Easier: a single pure gating function drives both the phone UI and the web UI via the capability section of the state document (ADR-0007).
- Harder: reference-device selection must include a phone whose secondary lenses lack `manualSensor` (the PRD already asks for a Samsung); support inbox will see "why can't I record on 3×".
- Revisit when: Phase 0 grey-card results on the generic curve; or an OEM is found to declare `manualSensor` but ignore `SENSOR_EXPOSURE_TIME` (then add a runtime verification that the `CaptureResult` echoes the requested exposure time and treat mismatch as `manualSensor` false).

## Action Items
1. [x] Mark Open Question 1 as decided in the PRD decision log, referencing this ADR.
2. [ ] Implement the probe and the `:domain` gating function with fixtures for Pixel and Samsung reference devices.
3. [ ] Phase 0: grey-card test of the generic Kelvin curve at 3200 K and 5600 K on both reference devices.
4. [ ] Add the `CaptureResult` echo check for exposure time and sensitivity to the probe.
