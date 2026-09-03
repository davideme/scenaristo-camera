# ADR-0008: Preview is an MJPEG HTTP stream rendered natively by the browser

**Status:** Accepted (2026-09-03, Davide; revised the same day after review: MJPEG over HTTP replaces JPEG over WebSocket)
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 6.8 (preview), 6.12 (WebRTC P2), 8-Q4, 8-Q5, 9 (thermal risk)
**Related ADRs:** ADR-0002, ADR-0006, ADR-0007

## Context
The PRD drafts a 960×540, up to 15 fps JPEG preview with < 500 ms glass-to-glass latency, adaptive under bandwidth pressure, never stealing encoder time from the 4K recording, and lists WebRTC as P2. Two constraints from other ADRs narrow the field: the page is served over plain HTTP (ADR-0006), so WebCodecs is unavailable; and the thermal budget is the biggest risk (ADR-0002), so preview encoding cost matters as much as latency.

The first draft of this ADR sent JPEG frames as binary WebSocket messages with a custom 12-byte header, a one-byte per-frame acknowledgement for backpressure, Blob-URL swapping on an image element, and a `PreviewTransport` interface on both sides. Review pointed out that browsers render a `multipart/x-mixed-replace` MJPEG stream natively from a plain `<img>` element, that Ktor writes such a stream from one route, and that the suspending write already provides backpressure, so every piece of that custom protocol was code the platform provides.

Rough numbers: a 960×540 frame at JPEG quality 70 is 50–100 KB; 15 fps is 6–12 Mbit/s, fine on Wi-Fi 5, marginal on a congested 2.4 GHz link. `YuvImage.compressToJpeg` encodes through the platform's libjpeg-turbo and costs a few milliseconds per frame on one core.

## Decision
We will serve the preview as an **MJPEG stream over HTTP**: a Ktor route `/preview.mjpg` responding with `multipart/x-mixed-replace` through `respondBytesWriter`, and the web UI renders it with `<img src="/preview.mjpg">`. No custom framing, no acknowledgement protocol, no Blob handling, no transport interface.

- **Frames** come from the `ImageAnalysis` NV21 stream (ADR-0002) through `YuvImage.compressToJpeg` (NV21 in, no conversion) into a per-client `Channel(CONFLATED)`, so a slow client always receives the newest frame and never a queue.
- **Backpressure** is the suspending write on the response channel; TCP does the rest.
- **Adaptation:** the producer caps at 15 fps; if a client's write blocks for more than 150 ms twice in a row, that client's quality steps 80 → 60 → 40, stepping back after 3 s of headroom. When the thermal state is `serious` or worse, the producer drops to 5 fps for all clients (PRD 8-Q4).
- **Latency measurement** is done in Phase 0 with a clapper; the state document (ADR-0007) reports the producer's current fps and quality as "connection quality". There is no per-frame timestamp channel.
- **Overlays** (rule of thirds, eye line) are an absolutely positioned SVG over the image, never burned in.
- The control WebSocket (ADR-0007) carries text only; preview does not share it.

**Fallback:** if Phase 0 shows an iPhone browser cannot render the stream (WebKit has a history of MJPEG regressions), the fallback is JPEG frames as binary WebSocket messages with the client swapping a Blob URL, adopted then and only then.

## Options Considered

### Option A: MJPEG over HTTP (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Lowest: one route, one `<img>` |
| Risk | Low-Medium: iOS Safari rendering must be checked once |
| Effort | A day |
| Reversibility | High |

**Pros:** Zero client code; the browser decodes, repaints, and reconnects; backpressure and "newest frame wins" come from a conflated channel and a suspending write; the same route serves iOS later.
**Cons:** Worst-case latency is bounded by socket buffers rather than by one frame in flight; no per-frame timestamps; a second connection alongside the control socket.

### Option B: JPEG over WebSocket with custom header and ack (first draft)
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Risk | Low |
| Effort | Days, on both sides, again on iOS |
| Reversibility | High |

**Pros:** Exactly one frame in flight; per-frame latency numbers; single connection.
**Cons:** Frame codec, ack state machine, Blob lifecycle, text/binary demux, and a header spec, all replacing what `<img>` gives for free. Kept as the fallback.

### Option C: H.264 over WebSocket decoded with WebCodecs
Blocked: WebCodecs requires a secure context; unavailable on `http://` LAN origins (ADR-0006).

### Option D: Fragmented MP4 into Media Source Extensions
| Dimension | Assessment |
|---|---|
| Complexity | Medium-High |
| Risk | Medium: iPhone Safari limited to `ManagedMediaSource` |
| Effort | Medium |
| Reversibility | High |

**Pros:** Hardware encode; low bandwidth; low latency is achievable with one fragment per frame.
**Cons:** Needs a second encoder session and a fragmented muxer the MVP does not otherwise own (ADR-0002 Option A), plus iPhone-specific MSE handling. Worth revisiting together with WebRTC.

### Option E: WebRTC (P2 in the PRD)
| Dimension | Assessment |
|---|---|
| Complexity | High |
| Risk | Medium |
| Effort | Weeks; native libwebrtc dependency |
| Reversibility | Medium |

**Pros:** Best latency and bandwidth; hardware codecs; congestion control built in; `RTCPeerConnection` works on `http://` origins. Signalling rides on the existing control socket.
**Cons:** ICE on multi-interface hosts and a large native dependency before the capture engine is proven. Right upgrade path, wrong first step.

## Trade-off Analysis
With WebCodecs excluded and MSE and WebRTC both requiring encoder and muxer work the MVP does not own, the choice is between two JPEG deliveries. MJPEG over HTTP is the one with no protocol to write or maintain, and its single real risk (iPhone rendering) is a one-hour Phase 0 check with a defined fallback.

## Consequences
- Easier: the preview is a route and a tag; nothing to specify in `docs/protocol`; iOS reuses the route unchanged.
- Harder: no per-frame latency telemetry; a congested 2.4 GHz network shows 5 fps and lower quality.
- Revisit when: Phase 0 measures > 500 ms glass-to-glass on a typical home network or an iPhone browser fails to render; or WebRTC is scheduled.

## Action Items
1. [ ] Phase 0: render the stream in Safari on an iPhone and an iPad, and in Chrome, Firefox, and Edge on a laptop; record results here.
2. [ ] Phase 0: measure glass-to-glass latency with a clapper and the phone temperature delta with preview on versus off during a 10-minute 4K30 recording.
