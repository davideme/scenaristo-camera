# ADR-0024: Guard a settings command on a settings revision, not on the document revision

**Status:** Proposed
**Date:** 2026-09-06
**Deciders:** Davide Mendolia
**PRD sections:** 6.3, 6.8
**Related ADRs:** ADR-0005, ADR-0007, ADR-0022

## Context

[ADR-0007](0007-control-protocol.md) gives every state broadcast a revision number and lets a client
attach `expectRev` to a command as a concurrency guard. Its stated use is specific: *"the web UI sets
it for settings changes so a stale tab cannot silently undo a change made on the phone, and
deliberately omits it for record start/stop, where acting on the latest state is always what the user
meant."* The reasoning is sound and this ADR does not disturb it.

What the design did not anticipate is how fast `rev` moves. `Session.update` bumps it whenever
anything in the state document changes, and the ADR-0005 exposure loop writes ISO into that document
continuously — up to six times a second by the ADR's own account, and faster in practice once
warnings, the audio meter, battery and the #97 histogram are counted.

**Measured on the reference device (Pixel 10, 2026-09-06): `rev` advances 27 times a second, against
roughly one broadcast a second.** A client's `rev` is therefore stale before the snapshot carrying it
has finished arriving. Every `expectRev`-guarded settings change was refused, always — including one
sent in the same millisecond a snapshot landed.

This was not theoretical. Davide hit it on the remote control the first time he changed white
balance and got *"The phone had already moved on — try that again"*, which was the honest rendering
of `nack: stale` and was never going to stop being true. It made PRD 6.8's browser controls
unusable, which is Phase 2's exit criterion.

The guard was comparing the wrong thing. What a settings command must not race is **another settings
change**. It has no reason to care that ISO moved.

## Decision

We will add a second revision, `settingsRev`, that advances **only when a field a client can actually
set changes**, and a matching optional `expectSettingsRev` on `Command`. The web UI guards settings
changes with `expectSettingsRev` and stops sending `expectRev` at all.

Both additions are additive fields with defaults, so an older client decodes a newer snapshot and an
older phone ignores the new guard, per ADR-0007's compatibility rule. `expectRev` keeps its meaning
and its behaviour; a command may carry both and is then guarded by both.

**"A field a client can actually set" means exactly the fields of `SettingsPatch`** — at the time of
writing `grid`, `whiteBalanceKelvin`, `lensId`, `saveToGallery`, `shutterLock` and
[ADR-0023](0023-lock-exposure-for-the-take.md)'s `lockExposureWhileRecording` — exposed as
`CaptureSettings.settable`. It is deliberately *not* the whole of `CaptureSettings`:

- `shutterHz` and `iso` are in there and are **outputs** of the exposure loop (ADR-0005 and
  [ADR-0022](0022-two-exposure-responsiveness-modes.md)), which is the entire problem. A first
  attempt at this fix compared all of `CaptureSettings` and reproduced the bug exactly, at six
  revisions a second instead of twenty-seven.
- `focus` is set by its own command, unguarded, because it is allowed during a take
  (ADR-0007; unused since focus became automatic, but the rule stands).

A change made on the phone's own screen advances `settingsRev` too. That is the case the guard exists
for.

A test asserts `settable` against `SettingsPatch`'s own serializer descriptor, so adding a patch
field without adding it to `settable` fails the build. Otherwise the failure mode is a new setting
that is silently unguarded — the original bug, reintroduced quietly.

It has already paid for itself twice, both times on fields added by someone who had never heard of
it: `zoomRatio` (#77) and ADR-0023's `lockExposureWhileRecording`, which arrived on `main` while this
branch was open and would otherwise have shipped as a user choice a stale tab could silently undo.

## Options Considered

### Option A: A second revision counting only settable fields (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low — one counter, one optional field |
| Risk | Low — additive, and `expectRev` is untouched |
| Effort | Low |
| Reversibility | High |

**Pros:** Says what the guard actually means. Additive, so no client breaks. Keeps ADR-0007's
protection intact — a stale tab still cannot undo somebody's change.
**Cons:** Two revision numbers on the wire, and a client has to know which to send. Mitigated by the
web UI being the only client and by both being documented at their definition.

### Option B: Stop bumping `rev` for reported-only changes
**Pros:** One revision number; no protocol addition at all.
**Cons:** Rejected, and it is the tempting one. `rev` moving is how a browser knows the phone is alive
and something happened; ADR-0007 has the server send a snapshot every 2 s precisely so silence is
diagnostic. ISO moving *is* a real change a remote wants to see. This option fixes the guard by
blinding the staleness detector.

### Option C: Per-field revisions, refusing only when the field in the patch has moved
**Pros:** The most precise answer. Two clients changing different settings never conflict.
**Cons:** `Session` would carry a revision per field, and the wire would carry a map. No evidence
anyone needs it: the conflict this protects against is two people setting the *same* thing, and
Phase 2's reference matrix is one laptop. Option A can become this later without changing the
client's shape.

### Option D: Drop the guard on settings entirely
**Pros:** Nothing to build; the symptom disappears immediately.
**Cons:** Re-opens exactly what ADR-0007 wrote the guard for. Two producers and a phone, and the last
stale tab to speak wins silently.

## Trade-off Analysis

Option B is the one to argue about, because it is the smallest change and it is wrong for a reason
that is easy to miss: it treats `rev`'s churn as noise, when `rev`'s churn is a signal a different
consumer depends on. Two consumers wanted two different questions answered by one number. The fix is
a second number, not a quieter first one.

Option A's cost is a second concept on the wire. That is a real cost and it is why this is an ADR
rather than a bug fix, but it is bounded: `expectRev` is untouched, and the two are told apart by
what they count rather than by convention.

## Consequences

- Easier: the browser's settings controls work. PRD 6.8's *"changes sync both ways"* becomes testable
  rather than blocked.
- Easier: adding a reported field to the state document no longer risks breaking every client's
  ability to change a setting. Before this, #97's histogram made the failure worse without anyone
  connecting the two.
- Harder: two revisions to keep straight. `CaptureSettings.settable` is now load-bearing, and the
  descriptor test is what stops it rotting.
- iOS (Phase 4) implements both counters against the same fixtures (ADR-0013).
- Revisit when: a second remote is a real scenario rather than a supported one, at which point Option
  C's per-field revisions may be worth the map on the wire.

## Action Items

1. [x] `settingsRev` on `StateMessage`, `expectSettingsRev` on `Command`, both defaulted.
2. [x] `CaptureSettings.settable`, with a descriptor test binding it to `SettingsPatch`.
3. [x] The web UI guards on `expectSettingsRev` and sends no `expectRev`.
4. [x] Verify on the reference device that a white balance change made a second after a snapshot is
   accepted.
5. [ ] Amend ADR-0007's Decision text, which still says the web UI sets `expectRev` for settings
   changes. Held until this ADR is Accepted, since the amendment states this decision as settled.
