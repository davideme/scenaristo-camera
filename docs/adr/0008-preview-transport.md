# ADR-0008: Preview is JPEG frames over the control WebSocket, behind a transport abstraction

**Status:** Proposed (confirms PRD 6.8 drafting position, records why alternatives are excluded)
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 6.8 (preview), 6.12 (WebRTC P2), 8-Q4, 8-Q5, 9 (thermal risk)
**Related ADRs:** ADR-0002, ADR-0006, ADR-0007

## Context
The PRD drafts a 960×540, up to 15 fps, JPEG-over-WebSocket preview with < 500 ms glass-to-glass latency, adaptive under bandwidth pressure, never stealing encoder time from the 4K recording, and lists WebRTC as P2. Open Question 5 asks whether JPEG can meet the latency target or WebRTC is needed in v1. Two constraints from other ADRs narrow the field: the page is served over plain HTTP (ADR-0006), so WebCodecs is unavailable; and the thermal budget is the biggest risk (ADR-0002), so preview encoding cost matters as much as latency.

Rough numbers for the JPEG path: a 960×540 frame at JPEG quality 70 is 50–100 KB; 15 fps is 0.75–1.5 MB/s, about 6–12 Mbit/s, which a healthy Wi-Fi 5 link carries but a congested 2.4 GHz link may not. Software JPEG encoding of that frame costs a few milliseconds on one core; at 15 fps that is well under 10 % of a core.

## Decision
We will ship v1 preview as **JPEG frames over the existing WebSocket** (binary frames, ADR-0007), produced from the `ImageAnalysis` NV21 stream (ADR-0002) via `YuvImage.compressToJpeg`, which takes NV21 directly so no conversion step is needed, displayed in the browser by swapping a Blob URL on an `<img>` (or drawing to a canvas for overlays). Behind a small `PreviewTransport` interface on both the phone and the web side, so a second transport can be added without touching the control protocol.

Adaptation rules:
- The server sends the next frame only after the client acknowledges the previous one (a one-byte binary ack), giving natural backpressure; it never queues more than one frame per client.
- Quality steps 80 → 60 → 40 and frame rate 15 → 10 → 5 fps when the round-trip ack time exceeds 150 ms for two consecutive frames; steps back up after 3 s of headroom.
- When the device thermal state is `serious` or worse, preview drops to 5 fps regardless of network (PRD 8-Q4).
- Each frame carries a 12-byte header: frame sequence, capture timestamp (ms), and flags, so the client can measure latency and the phone can report "connection quality" in the state document.

Framing overlays (rule of thirds, eye line) are drawn client-side over the image, never burned into the JPEG.

## Options Considered

### Option A: JPEG over WebSocket (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | Low; measurable in Phase 0 |
| Effort | Days |
| Reversibility | High |

**Pros:** Works in every browser over plain HTTP; no native library; trivial to reason about; per-frame backpressure gives adaptation for free; the same frames feed metering.
**Cons:** 10× the bandwidth of a video codec; CPU encode adds some heat; 15 fps ceiling is the practical limit.

### Option B: H.264 elementary stream over WebSocket decoded with WebCodecs
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Risk | Blocked |
| Effort | Medium |
| Reversibility | High |

**Pros:** Hardware encode on the phone (second `MediaCodec` instance at 540p costs almost nothing), ~1 Mbit/s, 30 fps, ~100 ms latency.
**Cons:** WebCodecs requires a secure context; unavailable on `http://` LAN origins. Dead until ADR-0006 changes.

### Option C: Fragmented MP4 over WebSocket into Media Source Extensions
**Pros:** Hardware encode, works on plain HTTP in desktop browsers.
**Cons:** MSE buffering adds 0.5–1.5 s of latency by design; iPhone Safari support is limited to `ManagedMediaSource` on recent versions; the PRD requires phone-sized browsers to work. Latency target likely missed.

### Option D: WebRTC (P2 in the PRD)
| Dimension | Assessment |
|---|---|
| Complexity | High |
| Risk | Medium |
| Effort | Weeks; libwebrtc adds ~20–30 MB to the APK |
| Reversibility | Medium |

**Pros:** Best latency and bandwidth; hardware codecs; works over plain HTTP in current browsers (to be verified, ADR-0006 action item).
**Cons:** Signalling, ICE on multi-interface hosts, a large native dependency, and a second concurrent encoder session all before v1 has proven the capture engine. Right upgrade path, wrong first step.

## Trade-off Analysis
With WebCodecs excluded by the HTTP decision and MSE failing the latency and mobile-Safari requirements, the realistic v1 choice is JPEG or WebRTC. JPEG meets the stated latency target on a healthy network with days of work and no native dependency; its cost is bandwidth and modest CPU, both of which Phase 0 measures. WebRTC remains the P2 path and the transport interface keeps it cheap to add.

## Consequences
- Easier: preview works in every browser on day one; thermal contribution is measurable and bounded by the fps cap.
- Harder: preview looks like 15 fps MJPEG, which is acceptable for framing but not for judging motion; congested 2.4 GHz networks will see 5 fps.
- Revisit when: Phase 0 measures > 500 ms glass-to-glass on a typical home network, or JPEG encoding raises steady-state temperature measurably, or WebRTC is scheduled.

## Action Items
1. [ ] Phase 0: measure glass-to-glass latency and phone temperature delta with preview on versus off during a 10-minute 4K30 HEVC recording.
2. [ ] Compare `YuvImage.compressToJpeg` against an NDK libjpeg-turbo build if CPU cost exceeds 10 % of a core.
3. [ ] Define the binary frame header in `docs/protocol/v1.md`.
