# ADR-0018: Take metering and preview frames from a `CameraEffect` tap on the preview stream, not from `ImageAnalysis`

**Status:** Accepted (2026-09-04, Davide)
**Date:** 2026-09-04
**Deciders:** Davide Mendolia
**PRD sections:** 6.1, 6.3, 6.5, 6.8, 6.10
**Related ADRs:** ADR-0002, ADR-0005, ADR-0008, ADR-0011, ADR-0017

## Context

ADR-0005 meters in-app from the `ImageAnalysis` stream, because Android reports no exposure offset
with AE off — that stream *is* the light meter. ADR-0008 serves the browser preview by JPEG-encoding
frames from the same NV21 buffers. Both rest on one assumption: that `ImageAnalysis` runs alongside
the 4K recording.

Phase 0 (#20) measured that assumption on the Pixel 10 (ADR-0017) and it is false. Nine
configurations containing both a UHD recording and an `ImageAnalysis` were bound on camera 0 — the
required feature group with and without `Preview`, `QualitySelector`, the `bindToLifecycle` vararg
path, and analysis at 960×540, 640×480 and default — and **every one is refused**. `UHD` with
`Preview` and `VideoCapture` alone binds at a real 3840×2160.

The reason is stream class, not pixel count. Camera2's guaranteed combinations are defined over
surface types: `Preview` and `VideoCapture` are both `PRIV` (opaque, GPU), while `ImageAnalysis` is
`YUV` (CPU-readable). At a `MAXIMUM`-sized recording this device offers `PRIV`+`PRIV` and not
`PRIV`+`YUV`, so a 640×480 analysis stream is refused next to 4K while a 1600×1200 preview is not.
CameraX's own `StreamSharing` does not rescue it: the failure text names
`ImageAnalysisConfig + StreamSharingConfig`, so sharing had already been applied to preview and
video and analysis stayed outside it — as it must, since sharing hands out a GPU surface and
`ImageAnalysis` needs CPU-readable memory.

A `CameraEffect` carrying a `SurfaceProcessor` was then bound for real, twice, at 3840×2160. It
intercepts the preview stream CameraX already opens rather than asking the camera for another one,
so the surface combination is unchanged.

Also measured, and load-bearing for what follows: **every capability query on this device is
optimistic**. `getSupportedQualities` lists UHD, `getSupportedFrameRateRanges(sessionConfig)` offers
`[30, 30]` for the very session in question, and `isSessionConfigSupported` returns true — for a
session that then binds at 720p. They are per-lens answers, not per-combination ones.

## Decision

We will source **every derived frame from a `CameraEffect` with a `SurfaceProcessor` on the
`PREVIEW` target**, and we will not bind `ImageAnalysis` alongside a UHD recording. One GL pass
samples the external-OES texture CameraX provides and renders it twice: to the viewfinder surface
CameraX asks for, and to an `ImageReader` we own at the preview size, whose frames feed both the
metering loop and the MJPEG encoder.

This **narrows two Accepted ADRs**, which is why this ADR exists rather than a code comment:

- **ADR-0005** keeps its damped ISO loop, its flicker-safe ladder and its face-weighted metering
  unchanged. Only the *source* of the luminance changes, from `ImageAnalysis` NV21 to the tapped
  texture. Metering may be computed on the GPU rather than by walking NV21 on the CPU.
- **ADR-0008** keeps MJPEG over HTTP, the conflated channel, the quality ladder and the thermal
  fps drop unchanged. Only the *source* of the JPEG input changes, from `ImageAnalysis` NV21 to the
  `ImageReader` fed by the tap. `YuvImage.compressToJpeg` is replaced by encoding whatever format
  that reader is configured for.

**PRD text to amend:** none. No user-visible default changes, and 6.3, 6.5 and 6.8 describe
behaviour rather than frame sources.

**Revisit at every CameraX release, and explicitly at 1.7.** Whether a device refuses UHD plus
`ImageAnalysis` is a property of CameraX's stream-combination resolution as much as of the hardware.
If a release makes that combination bindable, this ADR should be superseded and the GL pass deleted
rather than kept out of sunk cost.

## Options Considered

### Option A: `CameraEffect` tap on the preview stream (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | High: an EGL context, an external-OES shader, transform and timestamp plumbing |
| Risk | Medium: bound at 3840×2160 on the reference device, but never yet run with a real shader |
| Effort | The largest single piece of code Phase 1 carries |
| Reversibility | High: it is one frame source behind two existing interfaces |

**Pros:** Keeps a true 3840×2160 recording (PRD 6.1) *and* metering, warnings and the browser
preview (6.3, 6.5, 6.8) at once — the only measured configuration that does. Metering from a GPU
texture is cheaper than iterating NV21 per frame. The pipeline stays inside CameraX's graph, so
`Preview`, `VideoCapture` and the recorder are untouched.

**Cons:** We own a GL pass, which is what ADR-0002 chose CameraX to avoid ("not to own a media
pipeline"). Its thermal cost is unmeasured and lands in the budget #23 is already worried about.
Everything proven so far used a stub processor that renders nothing, so "it binds" is not "it
sustains 30 fps".

### Option B: `setPreferredFeatureGroup(UHD_RECORDING, FHD_RECORDING)` and keep `ImageAnalysis`
| Dimension | Assessment |
|---|---|
| Complexity | Lowest: two lines, no new machinery |
| Risk | Low, and measured: binds at video 1080×1920, preview 1080×1920, analysis 640×480 |
| Effort | Hours |
| Reversibility | High |

**Pros:** Works today. ADR-0005 and ADR-0008 stand unchanged. The preview is 16:9, matching the
recording, so no crop is needed and the overlays land where they should.

**Cons:** Gives up 4K whenever metering or the browser preview is live, which is most takes — and
4K is the first line of PRD 6.1. Note the ladder must be spelled out: `preferred(UHD)` alone drops
to **720p**, not 1080p, because dropping the only feature leaves the resolution pass unconstrained.

### Option C: Bind `ImageAnalysis` only between takes
| Dimension | Assessment |
|---|---|
| Complexity | Medium: rebinding on every record start and stop |
| Risk | Medium: an unmeasured camera restart at the worst possible moment |
| Effort | Low |
| Reversibility | High |

**Pros:** Full 4K while recording; metering, warnings and browser preview while framing. Davide's
initial position was that losing metering and the warnings *at record time* is acceptable.

**Cons:** The browser preview dies exactly when it is needed — during the take — and PRD 6.8's
solo creator is framing themselves from a laptop. Rebinding mid-session may show a visible camera
restart; that is unmeasured, and the moment it would happen is the instant recording begins.

### Option D: Wait for CameraX 1.7
Not an option on its own — Phase 1 cannot block on an unreleased version — but it is this ADR's
revisit trigger. As of 2026-09-03, 1.7.0 was at alpha03 (#27).

## Trade-off Analysis

Against Option B, the strongest alternative: B is honest, cheap and available today, and it is the
right answer if 4K is negotiable. It is not. PRD 6.1 opens with 4K30 and the product's whole claim
is opinionated capture defaults; shipping 1080p whenever the remote is connected would mean the
browser remote — the other half of the product — degrades the thing it exists to help you make.
Option A is the only measured way to keep both promises, and its cost is bounded and local: one
file, behind interfaces that already exist, deletable the day CameraX makes it unnecessary.

The honest risk is that Option A's cost is not yet fully known. Binding a stub proves the session is
accepted, not that a real shader sustains 30 fps at 1600×1200 while a 4K encode runs. If #23 shows
the extra GPU pass breaks the thermal budget, Option B is the fallback and this ADR is superseded.

## Consequences

- **Easier:** 4K30, metering, both warnings and the browser preview coexist. Metering gets cheaper
  (GPU luminance instead of CPU NV21). One frame source instead of two.
- **Harder:**
  - **The preview stream is 4:3 and the recording is 16:9.** Measured: preview 1600×1200 against
    video 3840×2160, and on this sensor the 4:3 stream is the *wider* field of view. Streaming it
    raw would show the operator more than is recorded, so someone framing themselves in the browser
    would be cropped tighter in the take, and ADR-0008's rule-of-thirds and eye-line overlays would
    sit in the wrong places. **The tap must crop to the recording's aspect ratio**, using
    `SurfaceOutput.updateTransformMatrix`. Cheap in a pass that is already scaling; silent and
    user-visible if forgotten.
  - Thermal cost is unmeasured and additive to #23's baseline.
  - `ImageAnalysis`-shaped APIs (ML Kit's `MlKitAnalyzer`) no longer apply directly; face detection
    reads from our `ImageReader` instead.
  - Capability reporting (PRD 6.10, ADR-0011) cannot be built from `getSupportedQualities`,
    `getSupportedFrameRateRanges` or `isSessionConfigSupported`, all three of which were measured
    lying about this session. The probe must bind and read `resolutionInfo` back.
- **Revisit when:** any CameraX release, and **explicitly at 1.7** (#27). The trigger is a device
  binding `UHD_RECORDING` together with `ImageAnalysis`; re-run the #20 probe on each upgrade. If it
  binds, supersede this ADR and delete the GL pass.
- **Also revisit when:** the reference matrix widens (#29). One device decided this; a second phone
  may refuse the effect tap where this one accepts it.

## Action Items

1. [ ] Implement the `SurfaceProcessor`: EGL context, external-OES shader, `SurfaceTexture`
       plumbing, transform matrix via `SurfaceOutput.updateTransformMatrix`, render to the
       viewfinder surface and to an owned `ImageReader`.
2. [ ] Crop to the recording's aspect ratio in that pass, and add a test that fails if the tapped
       frame's aspect ratio differs from `VideoCapture.resolutionInfo`'s.
3. [ ] Re-run #20's key-echo measurement against this session shape, while recording, for a full
       take length — the numbers so far were taken without analysis and without recording.
4. [ ] Measure the tap's thermal and frame-rate cost as part of #23, against the same baseline.
5. [ ] Add "re-run the #20 stream-combination probe" to the CameraX 1.7 checklist (#27) and to
       every subsequent upgrade.
6. [ ] Feed the "bind and read back, never trust a capability query" rule into ADR-0011's probe
       design before the capability report ships (PRD 6.10).
