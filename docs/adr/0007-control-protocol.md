# ADR-0007: Control protocol is JSON over one WebSocket with revisioned state and idempotent commands

**Status:** Accepted (2026-09-03, Davide; PRD 6.8 amended)
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 6.8 (controls, state), 6.10, 9 (Phase 2, Phase 4)
**Related ADRs:** ADR-0006, ADR-0008, ADR-0009, ADR-0010

## Context
PRD 6.8 requires a single source of truth on the phone, every change broadcast to all clients within 200 ms, multiple browsers, "last write wins", and a browser that reconnects mid-recording to show the current state. The same protocol must later be spoken by the iOS app without changes to the web UI (PRD 8-Q7). "Last write wins" without a version means a client with a stale view can overwrite a newer change, and a double-tapped or retried "record" message can toggle recording off again. The protocol is the real shared artifact between Android, iOS, and the web UI, so it deserves a specification independent of any implementation.

## Decision
We will define the protocol in `docs/protocol/v1.md` as a versioned document whose message types are the `@Serializable` classes in `:domain` (ADR-0010); those classes are the single source of truth. TypeScript types for the web UI are generated from them (ADR-0009). A language-neutral JSON Schema is generated from the same classes with JetBrains `kotlinx-schema` when a non-Kotlin consumer (the iOS server, Phase 4) needs it; nothing is hand-written twice. The protocol is implemented as follows:

- **One WebSocket** at `/ws` carrying JSON text frames only. Preview is a separate MJPEG HTTP stream (ADR-0008).
- **State snapshot model.** The phone owns a single `State` document (capture settings, capabilities of the active lens, recording status and elapsed time, warnings, device status, connected clients, shutter in use). Every snapshot carries a monotonically increasing `rev`. On connect the server sends `{type:"state", rev, state}` in full; afterwards it sends the full snapshot on every change (the document is small; diffs are not worth their bugs in v1).
- **Commands, not state writes.** Clients send `{type:"cmd", id, name, args}` with a client-generated `id` (UUID). The phone validates against capabilities, applies, bumps `rev`, broadcasts state, and replies `{type:"ack", id, rev}` or `{type:"nack", id, reason}`. The phone never accepts a state document from a client. "Last write wins" thus means the last accepted command, which is well defined.
- **Idempotent recording commands.** `record.start` and `record.stop` are separate commands (no toggle). A repeated `id` within 10 s returns the original ack without re-applying. `record.start` while recording is a no-op ack with the current state.
- **Optional concurrency guard.** A command may carry `expectRev`; if the phone's `rev` differs, the command is nacked with `stale` so the UI can refresh. The web UI uses this for settings changes, not for record start/stop.
- **Liveness.** No application-level ping. The Ktor WebSockets plugin is installed with `pingPeriod = 2.seconds` and `timeout = 4.seconds`; it sends RFC 6455 ping frames, browsers answer them with no JavaScript, and on timeout Ktor closes the session so the `/ws` handler's `finally` block decrements the client count. Because a browser cannot send pings itself, the server broadcasts a state snapshot at least every 2 s (battery, thermal, and elapsed time change anyway) and the client shows "disconnected" after 5 s without a message. Every snapshot carries `serverTime`; elapsed recording time is `serverTime - recordingStartedAt` corrected by the client's receive offset, so it stays correct after a reconnect.
- **Versioning.** The first server message is `{type:"hello", protocol:1, app, platform}`; the client refuses unknown major versions. Adding fields is backward compatible; renaming or removing bumps the major.

## Options Considered

### Option A: Revisioned snapshots + commands (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low-Medium |
| Risk | Low |
| Effort | Low |
| Reversibility | High |

**Pros:** Trivially correct with many clients; reconnect is "send snapshot"; easy to record fixtures for conformance tests shared by Android, iOS, and the web UI.
**Cons:** Full snapshots on every change; fine at this size (a few KB) but not a general pattern.

### Option B: Client-writable state document, last write wins (PRD wording taken literally)
**Pros:** Simplest to describe.
**Cons:** Stale clients clobber fresh changes; no way to reject invalid values for the active lens; record toggle races.

### Option C: JSON Patch / CRDT diffs
**Pros:** Bandwidth.
**Cons:** Bandwidth is not a problem; bugs in patch ordering are.

### Option D: REST for commands, WebSocket for push
**Pros:** Familiar.
**Cons:** Two channels with separate failure modes; the "which command was applied" question becomes harder; more code on iOS.

## Trade-off Analysis
Option A is the minimal design that makes PRD 6.8's guarantees (one source of truth, 200 ms broadcast, safe reconnect) true rather than aspirational, and it produces a spec the iOS port can be tested against before a line of Swift exists.

## Consequences
- Easier: multi-client correctness; reconnect; conformance testing with recorded message fixtures; the P1 pairing check adds a `role` and a `pair.*` command family without restructuring.
- Harder: the protocol document must be kept current with the same discipline as an ADR.
- Revisit when: the state document grows beyond a few tens of KB, or a second media channel (WebRTC signalling) needs to share the socket.

## Action Items
1. [ ] Write `docs/protocol/v1.md` with the message catalogue as a description of the `:domain` classes; include the `State` document shape and the capability report (ADR-0011).
2. [x] Reword PRD 6.8 "last write wins" to "last accepted command wins; see protocol v1".
3. [ ] Add a fixture-based conformance test that both the Android server and the web client run in CI.
