# ADR-0026: Open the port only while the phone is on a local network, decided by transport rather than by address shape

**Status:** Proposed
**Date:** 2026-09-07
**Deciders:** Davide Mendolia
**PRD sections:** 6.8 (discovery, security)
**Related ADRs:** ADR-0006 (supplements), ADR-0013, ADR-0019, ADR-0025

## Context

ADR-0006 settled how the server binds: once, to `0.0.0.0`, with LAN-only enforced per request by two checks — the peer's address must be private or link-local, and the `Host` header must be an IPv4 literal. Its reasoning for not binding per interface stands: Ktor fixes its connectors when `embeddedServer` is built, so a per-interface bind would mean tearing the server down on every network change.

That ADR also wrote down an assumption that is not always true:

> Cellular interfaces sit behind carrier NAT and receive no inbound connections.

Carrier NAT is not one thing. Some mobile networks put subscribers on RFC 1918 addresses — 10.0.0.0/8 most often — and where they do, another subscriber on the same carrier is a *private* peer as far as `LanOnly.isPrivateAddress` can tell, and can reach the phone with an IPv4 literal in `Host`. Both of ADR-0006's checks pass. v1 ships open LAN access (PRD 6.8), so what that peer reaches is a preview of the room and a record button.

This is not hypothetical on the reference device. Measured on the Pixel 10 on 2026-09-07, with mobile data up and Wi-Fi off:

```
15: rmnet1  inet 100.96.103.101/32   ← CGNAT, outside RFC 1918
16: rmnet2  inet 10.2.229.79/32      ← RFC 1918, indistinguishable from a LAN address
```

`rmnet2` is a carrier interface holding a 10.0.0.0/8 address. Every check the server had would admit a peer from that subnet, and the phone would have advertised `http://10.2.229.79:8080` on its own screen as the address to type into a laptop.

The shape of an address is not evidence of a local network. It never was; on Wi-Fi it happens to correlate, which is why the gap is invisible until the phone is on mobile data. The same blind spot has a second, milder face: `LocalAddress` picked the phone's URL by the same rule, so on a cellular-only phone it could put a carrier-assigned `10.x` address on screen as though a laptop could open it.

Two smaller things are entangled with this and are cheap to settle here. ADR-0006 says the displayed IP is "refreshed on `ConnectivityManager` callbacks"; nothing ever refreshed it, because it was read once in `onCreate` and never again. And ADR-0006 left "a VPN peer reaching the port" as accepted out of scope for v1 — a tunnel's private addresses are as much a false positive as a carrier's.

The forces: PRD 6.8's promise that "the interface must never be reachable off-LAN"; PRD 6.8's hotspot path, which must keep working (the phone as access point is the answer when there is no Wi-Fi); ADR-0006's refusal to restart the server on every network change; and the reference matrix of one Pixel 10 (ADR-0017), which means anything decided here is verified on one handset and one carrier.

## Decision

We will **hold the port closed unless the phone is on a local network**, and decide "local" from the *transport* the platform attributes an interface to, never from the address on it.

- An interface is `LOCAL` unless `ConnectivityManager` attributes it to a network with `TRANSPORT_CELLULAR` or `TRANSPORT_VPN`. **An interface the platform has no opinion about is local**: the phone's own hotspot is not a `Network` at all, and PRD 6.8 requires it to work.
- The address to serve on is the first private, non-loopback IPv4 address on a `LOCAL` interface, preferring a routable private address over a 169.254 link-local one. That address is also the URL shown on the phone, in the notification and in the QR code, so the phone never advertises an address its own request guard would refuse.
- When there is such an address, the server listens; when there is none, it is stopped and the port is closed. The decision is re-taken on the existing 1 Hz service tick, so it follows Wi-Fi appearing and disappearing, a hotspot being switched on, and a VPN coming up, with at most a second of lag.
- This **supplements ADR-0006 and changes none of its decisions**. The bind is still one `0.0.0.0` bind; the per-request checks are unchanged and still the defence against a hostile peer on a real LAN. What changes is whether the socket exists at all.
- The decision itself (`LanLinks` in `:domain`) is platform-free and fixture-tested, so the iOS server applies the same rule in Phase 4 (ADR-0013). The platform half (`Lan` in `:server`) only gathers the three facts it needs per interface: name, IPv4 addresses, transport.
- `android.permission.ACCESS_NETWORK_STATE` is added to `:server`'s manifest. It is read-only and not a location permission.

PRD text to amend, both in 6.8: "cellular interfaces receive no inbound connections" is false where a carrier uses RFC 1918 addressing, and should read that the server does not listen on cellular at all. The acceptance criteria gain one line: *given the phone has no local network, then the server is not listening and the phone says the remote is off.*

## Options Considered

### Option A: Keep the request-time rule as the only defence (do nothing)
| Dimension | Assessment |
|---|---|
| Complexity | None |
| Risk | High on carriers that assign RFC 1918 addresses; unknown share of them |
| Effort | None |
| Reversibility | n/a |

**Pros:** No new code, no new permission, no new failure mode. ADR-0006 is untouched.
**Cons:** The one case it fails is the one PRD 6.8 promises hardest — reachable off the local network, by a stranger, with control. It also leaves the phone advertising a carrier address as a connection URL.

### Option B: Gate on transport, keep one bind (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low; one class per module and a decision function |
| Risk | Low. The failure mode is a server that does not start, which is loud, not silent |
| Effort | Low |
| Reversibility | High; deleting the gate restores ADR-0006's behaviour exactly |

**Pros:** Closes the hole without touching the bind, the protocol, or the per-request checks. Makes the URL follow the network, which ADR-0006 asked for and never got. Takes the VPN case out of "out of scope" for the cost of one enum case.
**Cons:** Stopping the server drops attached remotes, so a Wi-Fi flap costs the reconnect the browser would have had to do anyway. A second of lag before the port closes. A poll, where the platform offers callbacks.

### Option C: Bind to the LAN interface's address instead of `0.0.0.0`
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Risk | Medium; a rebind state machine across every transition |
| Effort | Medium |
| Reversibility | Medium |

**Pros:** The kernel enforces it: nothing on another interface can reach the socket, with no request-time rule needed at all.
**Cons:** Exactly what ADR-0006 rejected, for exactly the reason it gave — Ktor's connectors are fixed at build time, so every address change is a server restart. It also restarts on changes that do not matter, such as a DHCP renewal into a new address.

### Option D: Ask `ConnectivityManager` for the default network only
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | Medium; wrong in both directions |
| Effort | Low |
| Reversibility | High |

**Pros:** One callback, no interface enumeration, no deprecated call.
**Cons:** Wrong about the hotspot, which is never the default network and often no network at all — and the hotspot is PRD 6.8's answer to having no Wi-Fi. Also wrong the other way when Wi-Fi is up but the default route is cellular.

## Trade-off Analysis

Option A is only tenable if carrier NAT is uniformly public-facing, and it is not; the cost of being wrong is a stranger watching a room and starting recordings, which is the failure PRD 6.8's security section exists to prevent. Option C is the strongest technical answer and remains rejected for ADR-0006's original reason, which has not changed: it makes every network event a server restart, where Option B makes only the *presence or absence* of a local network one. Option D is simpler than B and wrong about the case the PRD explicitly requires.

Against B's own costs: the dropped remotes are not a real loss, because a phone that has left the network has already dropped them at the socket level. The poll is the honest choice rather than a lazy one — no `NetworkCallback` fires for the phone's own hotspot coming up, so a poll is needed whatever else is done, and one mechanism that is right about every transition is better than two that are each right about part of it. It rides the 1 Hz tick that already publishes battery, thermal and storage, so it adds no timer.

## Consequences

- Easier: the phone never shows a URL nothing can open; the URL follows a change of network; a VPN peer is no longer an accepted unknown; "not reachable off-LAN" stops depending on how a carrier chose to number its subscribers.
- Harder: one more reason the remote can be unavailable, and it has to be explained on the phone (UI-7 gains that state). A brief Wi-Fi drop now closes and reopens the port rather than leaving it open on nothing.
- The camera and the service follow: with no server there are no clients and no preview viewers, so ADR-0025 puts the camera into standby and ADR-0019's idle shutdown stops the service. On a phone with no local network and no one looking at it, the app now settles to nothing running, which is the intended behaviour of all three ADRs together.
- Revisit when: the P1 pairing check of PRD 6.11 lands — a paired client is a stronger statement than a local address, and the two rules should be read together — or when a measurement on a second carrier or a second device (#29) contradicts what the transport reports here.

## Action Items

1. [x] **Verified on the reference Pixel 10 (ADR-0017), 2026-09-07.** With Wi-Fi off and mobile data up — `rmnet2` holding `10.2.229.79/32` — `ss -ltn` on the phone listed no listener on 8080 at all; only `adbd` on 5555 and one loopback port. With Wi-Fi back, `*:8080` was listening again and the page loaded from the laptop. The service logged the transition both ways:

   ```
   13:42:49 Lan: no local network; the remote is off until there is one ·
            dummy0=LOCAL[] rmnet1=CELLULAR[100.96.103.101] rmnet2=CELLULAR[10.2.229.79]
   13:43:12 Lan: local network at http://192.168.0.109:8080; serving ·
            dummy0=LOCAL[] wlan0=LOCAL[192.168.0.109] rmnet1=CELLULAR[...] rmnet2=CELLULAR[10.2.229.79]
   ```

   The 23 s between them is the Wi-Fi bounce, not the reaction time; the port closed within a second of the interface going.
2. [ ] Verify the connect sheet's own copy in that state on the phone screen — the log and the closed port were checked, the sheet was not.
3. [ ] Verify the hotspot path: with Wi-Fi off and the phone's hotspot on, the port is open, the sheet shows the hotspot address, and a laptop joined to it loads the UI. This is the one case the whole "an interface the platform has no opinion about is local" rule exists for, and it is not yet measured.
4. [ ] Verify Wi-Fi to hotspot and back leaves the phone showing the address that is actually being served.
5. [ ] Verify a VPN over Wi-Fi still serves the Wi-Fi address, and a VPN over cellular serves nothing.
6. [ ] Amend PRD 6.8's "cellular interfaces receive no inbound connections" and add the acceptance criterion listed under Decision, once this ADR is Accepted.
