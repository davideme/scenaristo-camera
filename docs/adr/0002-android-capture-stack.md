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
- **1.6 muxer detail (corrected after review):** `Recorder` writes through Media3 `MediaMuxerCompat`, which wraps the non-fragmented `Mp4Muxer` with defaults. Media3's `Mp4Writer` reserves a 400 KB `free` box after `ftyp` and rewrites `moov` there every 1 s of media time (`MOOV_BOX_UPDATE_INTERVAL_US`), keeping the `mdat` size current, until `moov` outgrows the reserve, after which it falls back to writing `moov` at the end on close. A force-killed recording is therefore playable up to the last rewrite as long as the take is short enough for `moov` to fit the reserve; sample tables for 4K30 video plus AAC grow by a few hundred bytes per second, so the covered length is on the order of ten to fifteen minutes and must be measured. The 1.6 "crash resilience" release note refers to this behaviour.
- **1.6 codec detail:** no public way to force HEVC for SDR; the codec follows the device encoder profiles for the selected quality. It can be read before recording via `Recorder.getVideoCapabilities(cameraInfo).getProfiles(quality)`.
- **1.6 audio detail:** amplitude reported every 200 ms through `RecordingStats.getAudioStats()`; no PCM access; no input-device selection; audio source selectable by `MediaRecorder.AudioSource` constant only.
- **1.7.0-alpha02/03:** `Recorder.Builder.setVideoMimeType` / `setAudioMimeType` and `getSupportedVideoMimeTypes` (experimental); legacy `Camera2Interop`, `Camera2CameraControl`, `Camera2CameraInfo`, `CaptureRequestOptions` deprecated in favour of a configurator API and Kotlin DSL.

Against that, Camera2-direct costs the most code, and CameraX's stream-combination handling and device quirk database matter on Samsung, one of the two reference devices. The developer is one person and the MVP goal is to prove the capture defaults and the remote, not to own a media pipeline.

## Decision
We will build the Android capture engine on **CameraX 1.6.2, pinned**, with the stock `Recorder`:

- **Use cases:** `Preview` (phone screen), `VideoCapture<Recorder>` (file output via `MediaStoreOutputOptions`), `ImageAnalysis` (NV21, ≈960×540, `STRATEGY_KEEP_ONLY_LATEST`) for metering and the web preview. `Preview` is rendered on the phone by the `CameraXViewfinder` composable from `androidx.camera:camera-compose`. All use cases are bound to the foreground service, which extends `LifecycleService` (ADR-0003), through a `SessionConfig` with required feature group `UHD_RECORDING` and `setFrameRateRange(30, 30)`; if `isFeatureGroupSupported` fails for UHD, fall back to FHD as PRD 6.1 allows.
- **Manual control:** `CONTROL_AE_MODE_OFF`, `SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`, `SENSOR_FRAME_DURATION`, `CONTROL_AWB_MODE_OFF`, `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX`, `COLOR_CORRECTION_GAINS`, `LENS_OPTICAL_STABILIZATION_MODE OFF` set through `Camera2Interop` at bind time and updated at runtime through `Camera2CameraControl.setCaptureRequestOptions`. `CaptureResult`s are read through the interop session capture callback for the ISO echo check (ADR-0005) and `STATISTICS_FACES`. We never use `ImageCapture`, exposure compensation, torch, or AE-affecting focus actions, so CameraX's own 3A has no reason to overwrite the keys. All interop code lives in one class, `ManualControls`, because the API is replaced in 1.7.
- **Encoding:** `Recorder.Builder().setQualitySelector(UHD, fallback FHD).setTargetVideoEncodingBitRate(45 Mbps)`; codec is whatever the device profile provides and is read and shown before recording (PRD 6.7 readout stays). Stabilisation off via `setVideoStabilizationEnabled(false)` and the OIS interop key.
- **Audio:** `withAudioEnabled()`; level meter from `RecordingStats.audioStats.audioAmplitude` at 5 Hz; clipping shown when amplitude ≥ 0.99 for two consecutive samples; active input read from `AudioManager.getActiveRecordingConfigurations()` for the readout.
- **Lens selection:** `VideoCapture.Builder.setPhysicalCameraId` where the device exposes physical cameras, otherwise separate `CameraSelector`s per camera ID; capability probing per ADR-0011 through `Camera2CameraInfo` characteristics plus the feature-group check.

**Accepted losses for the MVP** (decision 2026-09-03):

| PRD requirement | MVP status |
|---|---|
| 6.7 crash-resilient file | Provided by the stock `Recorder` up to the take length at which `moov` outgrows the 400 KB reserve (Phase 0 measures it; expected on the order of 10–15 min). Beyond that length a force-kill leaves an unplayable file. Guaranteed resilience for any length stays P1. |
| 6.7 HEVC whenever hardware exists | Codec follows the device profile. Readout kept. Metric "100 % HEVC on capable devices" suspended until 1.7. |
| 6.6 level meter ≥ 10 Hz | 5 Hz. |
| 6.6 input priority | System routing; readout only. |
| Encoder details (GOP, B-frames, profile) | Not settable. |

**Revisit pin:** when CameraX 1.7.0 reaches stable. Checklist: adopt `setVideoMimeType` with `getSupportedVideoMimeTypes` to enforce HEVC; migrate `ManualControls` to the new interop configurator API; re-check whether `Recorder` exposes a fragmented or otherwise length-independent crash-safe output; decide `docs/spec-chapter-markers.md` CM-1 (fragmented write plus soft-remux finalisation and a chapter track), deferred to this revisit on 2026-09-03; if neither is available from the library and the measured coverage is too short for real takes, the only library-supported path is Option C for the recording pipeline, because Option B is not a supported extension point (see below). Do not bump the CameraX version before that review.

The Gradle layout stays `:domain` (single-target Kotlin Multiplatform module, ADR-0010), `:capture` (CameraX, `ManualControls`, audio stats, analysis frame fan-out), `:server` (ADR-0006, ADR-0007), `:app` (Compose, `LifecycleService`, wiring). `:capture` exposes an interface so Option C remains drop-in.

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

### Option B: CameraX 1.6.2 with a custom `VideoOutput` (not a supported extension point)
| Dimension | Assessment |
|---|---|
| Complexity | High |
| Risk | High: only `onSurfaceRequested(SurfaceRequest)` is public; `getMediaSpec`, `getStreamInfo`, `onSourceStateChanged`, `getMediaCapabilities`, `getEncoderProfilesResolver`, `isSourceStreamRequired`, `onValidateConfig`, `isQualitySelectorDefault` and the three-argument `onSurfaceRequested` are `@RestrictTo(Scope.LIBRARY)` |
| Effort | High: own `MediaCodec`, Media3 `FragmentedMp4Muxer`, own `AudioRecord`, and no way to declare quality or observe source state |
| Reversibility | Low-Medium |

**Pros:** Recovers HEVC selection, fragmented MP4, a real level meter and input selection, while keeping CameraX session management. For the fragmented file: Media3 `FragmentedMp4Muxer` (H.265, H.264, AV1, AAC; `setFragmentDurationMs`; no out-of-order B-frames, so configure the encoder with `KEY_MAX_B_FRAMES = 0`), fragment duration 1 000 ms aligned with the keyframe interval, `fsync` per fragment, and an editor import test (Premiere, Resolve, Final Cut, CapCut, iMovie) before shipping. iOS gets the same shape from `AVAssetWriter.movieFragmentInterval`.
**Cons:** Owns encoder, muxer, and audio, which is most of the code Option A avoids, and because the rest of the interface is library-restricted a third-party output cannot set UHD or take part in feature-group resolution, so in practice this collapses into Option C with CameraX supplying only a `Surface`. Not a fallback; recorded so nobody rediscovers it.

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
2. [x] Phase 0: verify through the session capture callback that `SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`, and `SENSOR_FRAME_DURATION` echo the requested values on Pixel and Samsung while recording UHD at [30, 30].

   **Measured 2026-09-04, Pixel 10, camera id 0 (rear main), recording UHD at [30, 30] for ten minutes.** All six keys honoured across **18027 capture results**, worst-frame-wins — no frame in the take degraded any key:

   | Key | Requested | Reported | Deviation | Verdict |
   |---|---|---|---|---|
   | `SENSOR_EXPOSURE_TIME` | 20000000 | 19995066 | −247 ppm | quantised |
   | `SENSOR_SENSITIVITY` | 100 | 100 | 0 | exact |
   | `SENSOR_FRAME_DURATION` | 33333333 | 33340454 | +214 ppm | quantised |
   | `CONTROL_AE_MODE` | 0 (off) | 0 | 0 | exact |
   | `CONTROL_AWB_MODE` | 0 (off) | 0 | 0 | exact |
   | `LENS_OPTICAL_STABILIZATION_MODE` | 0 (off) | 0 | 0 | exact |

   The two quantised values are the sensor's own step, far inside tolerance: 4934 ns off 1/50 s is 0.05 % of a 50 Hz half-cycle, and the frame duration reads as 29.994 fps against PRD 6.1's "30.00 fps constant".

   **Ticked for one of the two devices this item names.** There is no Samsung (ADR-0017); #29 re-runs this before the beta. Main lens only — secondary lenses are not covered. And the session had no `ImageAnalysis`, because the reference device refuses it alongside UHD (ADR-0018); frames came from the preview tap.
3. [x] Phase 0: log the codec chosen by the device profile for UHD on both reference devices; record here.

   **Measured 2026-09-04, Pixel 10, camera id 0.** The UHD profile selects `video/avc` at 3840x2160, and a hardware HEVC encoder is present and unused:

   | Encoder | Type | Hardware |
   |---|---|---|
   | `c2.google.hevc.encoder` | video/hevc | yes |
   | `c2.android.hevc.encoder` | video/hevc | no |
   | `c2.google.avc.encoder` | video/avc | yes |
   | `c2.android.avc.encoder` | video/avc | no |
   | `OMX.google.h264.encoder` | video/avc | no |

   Confirmed against a real take rather than only the query: `ffprobe` on a 4K30 recording reports `h264`, High profile, 3840x2160 at 36.5 Mbps. So on the one reference device PRD 6.7's HEVC default is **not** what gets recorded, and the "otherwise H.264 High" fallback is the live path.

   Three API findings that size #27, all checked against the 1.6.2 artifacts rather than from memory:

   - `CameraInfo` (public) exposes no `encoderProfilesProvider`; it is on `CameraInfoInternal`. `VideoCapabilities.getProfiles` is `@RestrictTo` and lint fails the build on it. The report therefore reads `CamcorderProfile.getAll(cameraId, QUALITY_2160P)`, which is the same source CameraX consults.
   - `VideoSpec.Builder.setMimeType(String)` **already exists in 1.6.2**, but `VideoSpec` carries `@RestrictTo` and `Recorder.Builder` has no method that accepts one. The plumbing is there and only the entry point is missing, so the 1.7 change is smaller than this ADR assumed.
   - `VideoSpec.Builder` has no `setVideoEncoder`, and `Recorder.Builder` no `setVideoSpec`. There is no supported codec selection in 1.6.2 by any route.

   **Ticked for one of the two devices this item names** — there is no Samsung (ADR-0017). Main lens only.
4. [ ] Phase 0: force-kill a recording at 30 s and confirm the file plays to about 29 s (expected: yes); then find the take length at which `moov` outgrows the 400 KB reserve by force-killing at increasing durations, and record the covered length here and in PRD 6.7.

   **First half measured 2026-09-04, Pixel 10, camera id 0, UHD 30 fps, `am force-stop` mid-take.** Every length survives, and the loss is the frames in flight rather than a chunk of the take:

   | Take length | Recovered | Lost | Playable |
   |---|---|---|---|
   | 1.3 s | 1.03 s | 0.26 s | yes |
   | 2.2 s | 2.03 s | 0.17 s | yes |
   | 5.2 s | 5.04 s | 0.17 s | yes |
   | 20.2 s | 20.04 s | 0.15 s | yes |
   | 60.2 s | 60.05 s | 0.18 s | yes |

   Take length is measured from the moment the file appears, not from the command ack: ADR-0007's session is pure, so a start is acked before the camera has bound, and charging that bind latency to the muxer overstates what a kill costs. The 60 s file decodes end to end (`ffmpeg -f null -`, 1801 frames, no errors) — not merely a readable header.

   The mechanism is visible in the container: top-level boxes are `ftyp`, `moov`, `free`, `mdat`, with `moov` **before** the media data and `moov + free` equal to exactly **400,008 bytes** in every file measured. The muxer updates the index in place inside that reserve, which is why a killed file is complete to within one update interval.

   **The second half is not measured, and the estimate in PRD 6.7 is wrong.** `moov` growth is strongly sub-linear, because the sample tables pack better as a take lengthens:

   | Frames | `moov` bytes | Bytes/frame since previous |
   |---|---|---|
   | 31 | 1035 | — |
   | 151 | 2491 | 12.1 |
   | 811 | 10507 | 12.1 |
   | 1801 | 22511 | 12.1 |
   | 8398 | 38911 | 2.5 |

   Extrapolating the 12.1 B/frame slope of the short takes gives ~18 min; the 4 min 40 s point rules that out, and the slope between the last two points would put the limit past an hour. Neither number is trustworthy, so **no covered length is recorded yet** and PRD 6.7 keeps its placeholder. Killing at guessed lengths is the wrong instrument — it costs one recording per point. The remaining work is one long undisturbed take sampled every 30 s (`free` shrinking to zero is the answer); an attempt on 2026-09-04 was cut short at 4 min 40 s when the phone was picked up.
5. [x] Amend PRD 6.6 (level meter 5 Hz), 6.7 (crash resilience covered up to a measured length, HEVC "when the device profile provides it" until 1.7), and 6.1 (OIS off).
6. [ ] Create a tracking issue "CameraX 1.7 revisit" with the checklist from the revisit pin.
