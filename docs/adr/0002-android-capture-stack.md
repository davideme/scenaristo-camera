# ADR-0002: Build the Android capture engine on CameraX 1.6.2 with the stock Recorder, pinned, revisit at CameraX 1.7

**Status:** Accepted (2026-09-03, Davide). Revised the same day from a Camera2-direct draft after checking CameraX 1.5 and 1.6 release notes and sources.
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 6.1, 6.5, 6.6, 6.7, 6.10, 8-Q7, 9 (Phase 0–1)
**Related ADRs:** ADR-0003, ADR-0005, ADR-0008, ADR-0010, ADR-0011

## Context
The product promise is manual, locked capture: fixed 30 fps frame duration, fixed shutter, app-controlled ISO, locked white-balance gains, hardware HEVC, and a preview path that must not steal encoder time. Three consumers need frames: the encoder at 3840×2160, the phone screen, and an analysis path at roughly 960×540 for metering (ADR-0005) and web preview (ADR-0008). PRD Open Question 7 calls native Kotlin with Camera2 "the natural choice". The first draft of this ADR chose Camera2 and `MediaCodec` directly, mainly because CameraX could not produce a fragmented file, could not force HEVC, and hid audio.

Checked on 2026-09-03 against CameraX 1.6.2 (stable, 26 Aug 2026) and 1.7.0-alpha03:

- **1.5.0** added `SessionConfig` with feature groups and a deterministic frame-rate API: `CameraInfo.getSupportedFrameRateRanges(sessionConfig)` plus `SessionConfig.Builder.setFrameRateRange`, so [30, 30] is a guaranteed range rather than a hint. `ImageAnalysis` can output NV21 directly.
- **1.6.0** moved CameraX onto CameraPipe (the Pixel camera stack, no opt-out), made a Media3 muxer the default for `VideoCapture`, added `UHD_RECORDING` and `VIDEO_STABILIZATION` feature groups with `isFeatureGroupSupported`, and made frame rate set through interop respected.
- **1.6 muxer detail:** `Recorder` writes through Media3 `MediaMuxerCompat`, which wraps the non-fragmented `Mp4Muxer`; `moov` is written at close and the source shows no periodic write or recovery path. The "crash resilience" release note refers to fixed corruption bugs, not to a file that survives a missing close.
- **1.6 codec detail:** no public way to force HEVC for SDR; the codec follows the device encoder profiles for the selected quality. It can be read before recording via `Recorder.getVideoCapabilities(cameraInfo).getProfiles(quality)`.
- **1.6 audio detail:** amplitude reported every 200 ms through `RecordingStats.getAudioStats()`; no PCM access; no input-device selection; audio source selectable by `MediaRecorder.AudioSource` constant only.
- **1.7.0-alpha02/03:** `Recorder.Builder.setVideoMimeType` / `setAudioMimeType` and `getSupportedVideoMimeTypes` (experimental); legacy `Camera2Interop`, `Camera2CameraControl`, `Camera2CameraInfo`, `CaptureRequestOptions` deprecated in favour of a configurator API and Kotlin DSL.

Against that, Camera2-direct costs the most code, and CameraX's stream-combination handling and device quirk database matter on Samsung, one of the two reference devices. The developer is one person and the MVP goal is to prove the capture defaults and the remote, not to own a media pipeline.

## Decision
We will build the Android capture engine on **CameraX 1.6.2, pinned**, with the stock `Recorder`:

- **Use cases:** `Preview` (phone screen), `VideoCapture<Recorder>` (file output via `MediaStoreOutputOptions`), `ImageAnalysis` (NV21, ≈960×540, `STRATEGY_KEEP_ONLY_LATEST`) for metering and the web preview. All bound to a `LifecycleOwner` owned by the foreground service (ADR-0003), through a `SessionConfig` with required feature group `UHD_RECORDING` and `setFrameRateRange(30, 30)`; if `isFeatureGroupSupported` fails for UHD, fall back to FHD as PRD 6.1 allows.
- **Manual control:** `CONTROL_AE_MODE_OFF`, `SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`, `SENSOR_FRAME_DURATION`, `CONTROL_AWB_MODE_OFF`, `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX`, `COLOR_CORRECTION_GAINS`, `LENS_OPTICAL_STABILIZATION_MODE OFF` set through `Camera2Interop` at bind time and updated at runtime through `Camera2CameraControl.setCaptureRequestOptions`. `CaptureResult`s are read through the interop session capture callback for the ISO echo check (ADR-0005) and `STATISTICS_FACES`. We never use `ImageCapture`, exposure compensation, torch, or AE-affecting focus actions, so CameraX's own 3A has no reason to overwrite the keys. All interop code lives in one class, `ManualControls`, because the API is replaced in 1.7.
- **Encoding:** `Recorder.Builder().setQualitySelector(UHD, fallback FHD).setTargetVideoEncodingBitRate(45 Mbps)`; codec is whatever the device profile provides and is read and shown before recording (PRD 6.7 readout stays). Stabilisation off via `setVideoStabilizationEnabled(false)` and the OIS interop key.
- **Audio:** `withAudioEnabled()`; level meter from `RecordingStats.audioStats.audioAmplitude` at 5 Hz; clipping shown when amplitude ≥ 0.99 for two consecutive samples; active input read from `AudioManager.getActiveRecordingConfigurations()` for the readout.
- **Lens selection:** `VideoCapture.Builder.setPhysicalCameraId` where the device exposes physical cameras, otherwise separate `CameraSelector`s per camera ID; capability probing per ADR-0011 through `Camera2CameraInfo` characteristics plus the feature-group check.

**Accepted losses for the MVP** (decision 2026-09-03):

| PRD requirement | MVP status |
|---|---|
| 6.7 crash-resilient file | Not provided. Force-kill leaves an unplayable file. PRD acceptance "25 s playable of 30 s" and the edge-case user story move to P1. Plan in Option B below. |
| 6.7 HEVC whenever hardware exists | Codec follows the device profile. Readout kept. Metric "100 % HEVC on capable devices" suspended until 1.7. |
| 6.6 level meter ≥ 10 Hz | 5 Hz. |
| 6.6 input priority | System routing; readout only. |
| Encoder details (GOP, B-frames, profile) | Not settable. |

**Revisit pin:** when CameraX 1.7.0 reaches stable. Checklist: adopt `setVideoMimeType` with `getSupportedVideoMimeTypes` to enforce HEVC; migrate `ManualControls` to the new interop configurator API; re-check whether `Recorder` output is fragmented or otherwise survives a force-kill; if not, evaluate Option B below for the crash requirement. Do not bump the CameraX version before that review.

The Gradle layout stays `:domain` (pure Kotlin, ADR-0010), `:capture` (CameraX, `ManualControls`, audio stats, analysis frame fan-out), `:server` (ADR-0006, ADR-0007), `:app` (Compose, service, wiring). `:capture` exposes an interface so Options B and C remain drop-in.

## Options Considered

### Option A: CameraX 1.6.2, stock `Recorder` (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low: session, streams, rotation, MediaStore output, quirks handled |
| Risk | Medium: interop keys are best-effort; interop API churn in 1.7 |
| Effort | Smallest |
| Reversibility | High behind the `:capture` interface |

**Pros:** Weeks less code; guaranteed frame-rate range and UHD feature group; CameraPipe quirk handling on Samsung; `ImageAnalysis` NV21 feeds metering and JPEG preview directly.
**Cons:** The accepted losses above; codec and container are not ours to choose until 1.7.

### Option B: CameraX 1.6.2 with a custom `VideoOutput`
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Risk | Medium: `VideoOutput` is advanced API without the stability promise of the rest |
| Effort | Medium: own `MediaCodec`, Media3 `FragmentedMp4Muxer`, own `AudioRecord` |
| Reversibility | High |

**Pros:** Recovers HEVC selection, fragmented MP4, a real level meter and input selection, while keeping CameraX session management. For the fragmented file: Media3 `FragmentedMp4Muxer` (H.265, H.264, AV1, AAC; `setFragmentDurationMs`; no out-of-order B-frames, so configure the encoder with `KEY_MAX_B_FRAMES = 0`), fragment duration 1 000 ms aligned with the keyframe interval, `fsync` per fragment, and an editor import test (Premiere, Resolve, Final Cut, CapCut, iMovie) before shipping. iOS gets the same shape from `AVAssetWriter.movieFragmentInterval`.
**Cons:** Owns encoder, muxer, and audio, which is most of the code Option A avoids. The natural next step if 1.7 does not close the crash-resilience gap.

### Option C: Camera2 + `MediaCodec` direct (first draft of this ADR)
| Dimension | Assessment |
|---|---|
| Complexity | High: Camera2 state machine, stream combinations, codec lifecycle |
| Risk | Medium: OEM quirks per lens handled by us |
| Effort | Largest |
| Reversibility | Low once built |

**Pros:** Every control reachable and stable; zero-copy encoder input; nothing best-effort.
**Cons:** Most code and testing for a solo developer before the product is proven. Kept as the escape hatch if interop proves unreliable on a reference device.

### Option D: Single 4K stream with GPU fan-out
Rejected: adds GPU heat to the tightest budget; CameraX already negotiates the three-stream combination.

### Option E: Flutter / React Native with a native camera plugin
Rejected: the plugin would be the whole app; the web UI (ADR-0009) is the cross-platform surface.

## Trade-off Analysis
The MVP has to prove that locked shutter, app-driven ISO, locked WB, and the browser remote produce footage people prefer to a webcam. None of the accepted losses affects that proof; two of them (HEVC selection, interop API) are scheduled to close in 1.7, and the third (crash resilience) has a known path in Option B. Option A therefore buys the most validation per week of work, and the `:capture` interface keeps the cost of changing course to one module.

## Consequences
- Easier: Phase 0 and 1 shrink; stream combinations, quirks, rotation, and MediaStore are CameraX's problem; UHD support and frame-rate ranges are queryable before binding.
- Harder: PRD 6.6 and 6.7 acceptance criteria must be amended for the MVP; the 1.7 interop migration is scheduled work; a device where interop keys are overridden has no workaround short of Option C.
- Revisit when: CameraX 1.7.0 stable ships (checklist above); or Phase 0 shows `CONTROL_AE_MODE_OFF` or `SENSOR_FRAME_DURATION` not honoured through interop on a reference device (then Option C for that path); or the crash-resilience story must ship (then Option B).

## Action Items
1. [ ] Pin `androidx.camera:*:1.6.2` in the version catalog with a comment pointing at this ADR's revisit pin.
2. [ ] Phase 0: verify through the session capture callback that `SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`, and `SENSOR_FRAME_DURATION` echo the requested values on Pixel and Samsung while recording UHD at [30, 30].
3. [ ] Phase 0: log the codec chosen by the device profile for UHD on both reference devices; record here.
4. [ ] Phase 0: force-kill a recording at 30 s and record whether the file plays; record here (expected: no).
5. [ ] Amend PRD 6.6 (level meter 5 Hz), 6.7 (crash resilience to P1, HEVC "when the device profile provides it" until 1.7), and 6.1 (OIS off).
6. [ ] Create a tracking issue "CameraX 1.7 revisit" with the checklist from the revisit pin.
