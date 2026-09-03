# ADR-0006: Serve the web interface from an embedded Ktor server over plain HTTP bound to LAN interfaces

**Status:** Proposed. The open-LAN-access and plain-HTTP decisions this ADR builds on were taken by Davide on 2026-09-03 in the PRD decision log; what this ADR proposes is the server library, the binding rule, and the client model.
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
- **Bind once to `0.0.0.0`** and enforce LAN-only at request time with one Ktor application plugin: reject with 403 any request whose remote address is not a private (RFC 1918) or link-local address, and any request whose `Host` header is not an IPv4 literal. The phone's URLs are always IP literals, and a DNS-rebinding attack arrives with a hostname in `Host`, so the second rule defeats it without the server knowing its own interfaces. Ktor connectors are fixed when `embeddedServer` is built, so the first draft's per-interface binding with rebinding on connectivity changes would have meant restarting the server, and dropping every WebSocket, on each Wi-Fi to hotspot transition. Cellular interfaces sit behind carrier NAT and receive no inbound connections; a VPN peer reaching the port is accepted as out of scope for v1.
- **No mDNS in v1.** `NsdManager` on API 34 registers a DNS-SD service record but has no public way to register a hostname, so `scenaristo.local` could never resolve in a browser from this app; the QR code and IP URL are the only discovery path. Amends PRD 6.8 "mDNS name advertised where the platform supports it".
- The QR code is generated with `com.google.zxing:core`; the URL's IP is read once from `NetworkInterface` when the panel is shown and refreshed on `ConnectivityManager` callbacks (display only, no rebinding).
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
Plain HTTP is the only option that meets the setup-time goal; its main cost is the preview-transport constraint, which ADR-0008 absorbs. Ktor is the least surprising server for a Kotlin codebase and its cost is APK size, which is irrelevant next to the app's purpose. One bind plus two request-time rules turns "not reachable off-LAN" from an intention into a checked property with no rebind state machine.

## Consequences
- Easier: one process, one port, one bind; the same static bundle and protocol serve iOS later; no rebind logic and no mDNS lifecycle.
- Harder: WebCodecs and any secure-context API are off the table until a trusted-cert story exists (there is none planned).
- Revisit when: a trusted-certificate mechanism for LAN devices becomes practical, or the P1 pairing check is scheduled (the `role` field is ready for it).

## Action Items
1. [ ] Prototype Ktor CIO static serving plus WebSocket in the foreground service; measure idle CPU with two clients connected.
2. [ ] Phase 0: confirm a hotspot client and a USB-tethered laptop reach the `0.0.0.0`-bound server and that the QR URL refreshes on the transition.
3. [x] Add "Host header validation" and "not reachable on cellular" to the PRD 6.8 acceptance criteria; drop the mDNS bullet.
4. [ ] Verify `RTCPeerConnection` availability on `http://` origins in current Chrome, Safari, and Firefox, so the P2 WebRTC option is not silently dead.
