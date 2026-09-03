# ADR-0006: Serve the web interface from an embedded Ktor server over plain HTTP bound to LAN interfaces

**Status:** Proposed (security scope Accepted 2026-09-03 per PRD decision log)
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 6.8 (discovery, security), 8 decision log
**Related ADRs:** ADR-0003, ADR-0007, ADR-0008, ADR-0009

## Context
The phone runs an HTTP and WebSocket server that any browser on the same network can open by URL or QR code. The PRD decided: open LAN access in v1, a pairing check in P1, plain HTTP in both, and "bind to the LAN interface only". Left open: which server library, how interface binding works with Wi-Fi versus hotspot, and what plain HTTP costs on the browser side.

The browser cost is concrete. A page served from `http://192.168.x.x` is not a secure context, so WebCodecs, `getUserMedia`, Service Workers, and Web Bluetooth are unavailable. WebSocket, `<img>`/Blob rendering, Media Source Extensions, and `RTCPeerConnection` remain usable. This decision therefore removes WebCodecs from the preview-transport options (ADR-0008). Chrome's HTTPS-first behaviour still falls back to HTTP for typed or scanned URLs. There is no way to obtain a browser-trusted certificate for a private IP without a public domain and a backend, which are non-goals.

## Decision
We will:

- Use **Ktor server with the CIO engine** inside the foreground service (ADR-0003), serving the static web bundle (ADR-0009) and a single WebSocket endpoint for control and preview (ADR-0007, ADR-0008).
- Serve **plain HTTP** on a fixed port (default 8080, next free port if taken). The URL and QR code always include the explicit `http://` scheme and port.
- **Bind per interface**, not to `0.0.0.0`: enumerate `NetworkInterface`s and bind to each non-loopback, site-local IPv4 address (Wi-Fi client, Wi-Fi hotspot, USB/Ethernet tethering), rebinding on connectivity changes via `ConnectivityManager`. Cellular and VPN interfaces are excluded. Requests arriving with a `Host` header that is not one of our bound addresses or `scenaristo.local` are rejected with 403, which defends against DNS-rebinding from a malicious website on the same LAN.
- Advertise `_http._tcp` via `NsdManager` with the instance name `scenaristo`; the IP URL remains the primary path as the PRD states.
- Show the **connected-client count** on the phone and in the notification, and raise a short on-phone toast when a new client connects, so open access is at least visible (PRD 6.8).
- Reserve the protocol shape for the P1 pairing check now: every client gets a `clientId` cookie on first load, and the server has a `role` per client (`viewer` or `controller`) that is `controller` for everyone in v1.

## Options Considered

### Option A: Ktor CIO (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low-Medium |
| Risk | Low; supported on Android by JetBrains |
| Effort | Low |
| Reversibility | High; the protocol is library-independent |

**Pros:** Kotlin coroutines end to end, first-class WebSockets, static content serving, maintained. Same mental model as the rest of the app.
**Cons:** Adds several MB to the APK; the Netty engine is not usable on Android (CIO is).

### Option B: NanoHTTPD + NanoWSD
**Pros:** Tiny, no dependencies.
**Cons:** Effectively unmaintained, thread-per-connection, WebSocket implementation with known edge-case bugs; we would be patching it.

### Option C: Java-WebSocket for WS plus a hand-rolled HTTP file server
**Pros:** Small.
**Cons:** Two servers, two ports, more CORS and lifecycle surface for no gain.

### Option D: Self-signed TLS
**Pros:** Secure context unlocks WebCodecs and removes mixed-content edge cases.
**Cons:** Every browser shows a full-page warning on first visit, which fails PRD goal 4 (setup under two minutes for a first-time user); some mobile browsers do not allow proceeding past it for WebSocket connections. Rejected, as the PRD already concluded.

## Trade-off Analysis
Plain HTTP is the only option that meets the setup-time goal; its main cost is the preview-transport constraint, which ADR-0008 absorbs. Ktor is the least surprising server for a Kotlin codebase and its cost is APK size, which is irrelevant next to the app's purpose. Per-interface binding plus Host validation is cheap and turns "not reachable off-LAN" from an intention into a checked property.

## Consequences
- Easier: one process, one port, one endpoint; the same static bundle and protocol serve iOS later.
- Harder: WebCodecs and any secure-context API are off the table until a trusted-cert story exists (there is none planned); per-interface rebind logic must be tested against Wi-Fi to hotspot switches.
- Revisit when: a trusted-certificate mechanism for LAN devices becomes practical, or the P1 pairing check is scheduled (the `role` field is ready for it).

## Action Items
1. [ ] Prototype Ktor CIO static serving plus WebSocket in the foreground service; measure idle CPU with two clients connected.
2. [ ] Test binding across Wi-Fi client, hotspot, and USB tethering on both reference devices.
3. [ ] Add "Host header validation" and "not bound on cellular" to the PRD 6.8 acceptance criteria.
4. [ ] Verify `RTCPeerConnection` availability on `http://` origins in current Chrome, Safari, and Firefox, so the P2 WebRTC option is not silently dead.
