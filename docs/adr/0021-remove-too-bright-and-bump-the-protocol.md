# ADR-0021: Remove `TOO_BRIGHT` and bump the protocol to 2

**Status:** Proposed
**Date:** 2026-09-05
**Deciders:** Davide Mendolia
**PRD sections:** 6.3, 6.8
**Related ADRs:** [ADR-0005](0005-exposure-control-own-metering-loop.md), [ADR-0007](0007-control-protocol.md)

## Context

`State.warnings` carries four values, and one of them has never had a producer.

PRD 6.3 defines exactly one too-much-light message — *"Too much light: reduce light or close blinds"* — and it is shown only **after** the flicker-safe shutter step has been taken and is still not enough. That condition is `OVEREXPOSED_AT_BASE_ISO`, whose own documentation says so. PRD 6.3 and UI-5 both state explicitly that a *successful* step raises no warning, so there is no second brightness condition left for `TOO_BRIGHT` to describe.

It was raised in #51 rather than deleted quietly, because removing an enum value is not additive and ADR-0007 reserves that for a version bump. Davide answered on 2026-09-05: **remove it.**

The cost of leaving it is not zero. `CameraScreen` already carries copy for a warning that can never appear, `web/src/protocol.ts` will generate a union member no phone can send, and the Phase 4 iOS port would inherit a value it has to decide how to raise. A dead enum member is a question every reader has to answer once.

## Decision

We will:

1. **Remove `Warning.TOO_BRIGHT`.** `OVEREXPOSED_AT_BASE_ISO` is the too-much-light warning, and it keeps PRD 6.3's copy.
2. **Bump `PROTOCOL_VERSION` from 1 to 2**, because ADR-0007 says renaming or removing does exactly that.
3. Regenerate `web/src/protocol.ts` from `:domain` rather than editing it, per ADR-0009.

**This costs nothing today and will not be free later.** ADR-0007 has a client refuse an unknown major, so a bump is a hard break for anything already talking version 1 — and nothing is. The web UI is not built yet (Phase 2), the iOS server does not exist (Phase 4), and the only client is a browser that is served by the same phone it talks to, so the two can never disagree about the version. The moment any of that stops being true, a removal like this one stops being a one-line change and starts needing a migration.

That is the argument for doing it now rather than deferring it, and it is also the reason to bundle: if anything else non-additive is wanted, it belongs in this bump. Nothing else is queued.

## Options Considered

### Option A: Remove it and bump to 2 (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Low: one enum member, one constant, one regenerated file |
| Risk | Low **now**, high if deferred past the first shipped client |
| Effort | Under an hour |
| Reversibility | High while nothing speaks the protocol; low afterwards |

**Pros:** The protocol says what the product does. No dead copy on the phone, no phantom union member in TypeScript, nothing for the iOS port to interpret. Uses the one window in which a breaking change is genuinely free.
**Cons:** Spends a major version on a small cleanup, and the version number is a shared fact that has to be right in both generated and hand-written places.

### Option B: Keep it, unused and documented

| Dimension | Assessment |
|---|---|
| Complexity | None |
| Risk | Low, accumulating |
| Effort | None |
| Reversibility | — |

**Pros:** No version bump; nothing to coordinate.
**Cons:** Every future reader asks what raises it, and the honest answer — "nothing, and nothing ever will" — is one that has to be given repeatedly rather than once. It also invites a well-meaning future change to *find* a use for it, which would put two warnings on one condition.

### Option C: Keep the name, change its meaning to "overexposed even after the step"

**Pros:** No removal, so no bump.
**Cons:** Two names for one condition, and the survivor would be the less descriptive one. `OVEREXPOSED_AT_BASE_ISO` says which state the camera is in; `TOO_BRIGHT` says how the room feels. The warning's *copy* is the user-facing sentence, and it is already right.

## Trade-off Analysis

B is free today and pays for itself in confusion; the value's existence is already costing a paragraph in `ExposureLoop` explaining why nothing raises it. C keeps the version stable at the cost of the clearer name. A spends the one moment when a breaking protocol change is free — no shipped client, no second implementation — on making the protocol match the product, and that window closes at Phase 2.

## Amendment, 2026-09-06 (before release)

`isoLock` was added to `CaptureSettings` and `SettingsPatch` in #81 and removed again the same day, when Davide replaced the manual ISO lock with two automatic responsiveness modes (ADR-0022). **The version stays at 2** rather than becoming 3.

The rule in ADR-0007 — "renaming or removing bumps this number" — exists to protect clients from a field disappearing underneath them. There are no clients: the browser UI is Phase 2 and does not exist, `web/src/protocol.ts` is generated rather than written, and version 2 has never been in anyone's hands. A field that was added and removed between two merges on the same afternoon was never part of the contract v2 describes, so bumping to 3 would record a compatibility event that never happened.

If v2 ships anywhere before another non-additive change, this reasoning stops applying and the next removal bumps.

## Consequences

- Easier: `Warning` has four members and four producers. The Phase 2 browser and the Phase 4 iOS port both inherit a protocol with nothing to explain.
- Harder: `PROTOCOL_VERSION` is now 2, so any fixture, document or client that names 1 has to move with it. The golden fixtures are the guard.
- Anything else non-additive should ride along in this bump rather than causing a second.
- Revisit when: a real second brightness condition appears — for example a lens whose base ISO overexposes before the ladder is exhausted — at which point it wants its own name and its own copy, not this one back.

## Action Items

1. [ ] Remove the enum member and its dead copy in `CameraScreen`.
2. [ ] Bump `PROTOCOL_VERSION` and regenerate `web/src/protocol.ts` (ADR-0009).
3. [ ] Update the `TOO_BRIGHT` mention in `spec-phone-and-remote-ui.md` UI-5.
4. [ ] Confirm the golden fixtures still round-trip, since they are what the iOS port is tested against (ADR-0013).
