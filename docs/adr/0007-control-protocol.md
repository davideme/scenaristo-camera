# ADR-0007: Control protocol is JSON over one WebSocket with revisioned state and idempotent commands

**Status:** Accepted (2026-09-03, Davide; PRD 6.8 amended)
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 6.8 (controls, state), 6.10, 9 (Phase 2, Phase 4)
**Related ADRs:** ADR-0006, ADR-0008, ADR-0009, ADR-0010

## Context
PRD 6.8 requires a single source of truth on the phone, every change broadcast to all clients within 200 ms, multiple browsers, "last write wins", and a browser that reconnects mid-recording to show the current state. The same protocol must later be spoken by the iOS app without changes to the web UI (PRD 8-Q7). "Last write wins" without a version means a client with a stale view can overwrite a newer change, and a double-tapped or retried "record" message can toggle recording off again. The protocol is the real shared artifact between Android, iOS, and the web UI, so it deserves a specification independent of any implementation.

## Decision
We will define the protocol in `docs/protocol/` as a versioned document with JSON Schema for every message, and implement it as follows:

- **One WebSocket** at `/ws`. Text frames carry JSON control messages; binary frames carry preview media (ADR-0008). Text and binary are multiplexed on the same socket so the client has one connection state.
- **State snapshot model.** The phone owns a single `State` document (capture settings, capabilities of the active lens, recording status and elapsed time, warnings, device status, connected clients, shutter in use). Every snapshot carries a monotonically increasing `rev`. On connect the server sends `{type:"state", rev, state}` in full; afterwards it sends the full snapshot on every change (the document is small; diffs are not worth their bugs in v1).
- **Commands, not state writes.** Clients send `{type:"cmd", id, name, args}` with a client-generated `id` (UUID). The phone validates against capabilities, applies, bumps `rev`, broadcasts state, and replies `{type:"ack", id, rev}` or `{type:"nack", id, reason}`. The phone never accepts a state document from a client. "Last write wins" thus means the last accepted command, which is well defined.
- **Idempotent recording commands.** `record.start` and `record.stop` are separate commands (no toggle). A repeated `id` within 10 s returns the original ack without re-applying. `record.start` while recording is a no-op ack with the current state.
- **Optional concurrency guard.** A command may carry `expectRev`; if the phone's `rev` differs, the command is nacked with `stale` so the UI can refresh. The web UI uses this for settings changes, not for record start/stop.
- **Heartbeat.** Server sends `{type:"ping", t}` every 2 s; the client answers `pong`. Two missed pongs close the socket and decrement the client count. Elapsed recording time is derived client-side from `recordingStartedAt` in the state plus the last ping's server clock offset, so it stays correct after a reconnect.
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
- Harder: the protocol document must be kept current with the same discipline as an ADR; PRD 6.8 "last write wins" must be reworded.
- Revisit when: the state document grows beyond a few tens of KB, or a second media channel (WebRTC signalling) needs to share the socket.

## Action Items
1. [ ] Write `docs/protocol/v1.md` with the message catalogue and JSON Schemas; include the `State` document shape and the capability report (ADR-0011).
2. [ ] Reword PRD 6.8 "last write wins" to "last accepted command wins; see protocol v1".
3. [ ] Add a fixture-based conformance test that both the Android server and the web client run in CI.
