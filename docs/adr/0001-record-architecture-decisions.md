# ADR-0001: Record architecture decisions

**Status:** Accepted
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** all
**Related ADRs:** none

## Context
The PRD (`docs/PRD-talking-head-camera.md`) fixes product behaviour but also embeds technical positions: native Kotlin with Camera2, fragmented MP4, a custom auto-exposure loop, JPEG-over-WebSocket preview, plain HTTP on the LAN, a static web bundle shared with iOS. Some are marked as drafting positions, some are logged decisions, and some are stated as facts without a rationale. Several will be revisited after the Phase 0 spike. Without a durable record, the reasoning behind each choice is lost, contradicting changes slip in silently, and the iOS port (Phase 4) cannot tell which Android choices were principled and which were convenient.

## Decision
We will keep Architecture Decision Records in `docs/adr/`, one file per decision, numbered sequentially (`NNNN-short-title.md`), using the template in `0000-template.md`. A decision is recorded when it is made, including decisions that merely confirm a PRD position, so that the rationale is written down. An ADR is never edited to say something different after it is Accepted; it is Superseded by a new ADR that links back. `docs/adr/README.md` is the index and must be updated in the same change as the ADR. The repository `CLAUDE.md` states when an ADR is required.

## Options Considered

### Option A: ADRs in the repository (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | Low; the cost is discipline |
| Effort | Small per decision |
| Reversibility | Trivial |

**Pros:** Versioned with the code, reviewable in pull requests, readable by tools and agents working in the repo.
**Cons:** Requires discipline to keep the index current.

### Option B: Decision log inside the PRD
**Pros:** One document.
**Cons:** The PRD is a product document; technical trade-off tables would bloat it, and superseding a decision means rewriting history in place.

### Option C: No formal record
**Pros:** Zero overhead.
**Cons:** The PRD already shows the failure mode: assumptions tagged "drafting position" with nobody recording when they were confirmed or dropped.

## Trade-off Analysis
Option A costs a few minutes per decision and pays back the first time a Phase 0 measurement contradicts a PRD assumption, because the ADR states the trigger for revisiting and the alternatives already evaluated.

## Consequences
- Easier: onboarding, the iOS port, and agent-assisted work, since the constraints are explicit.
- Harder: nothing material; small changes that do not touch an architectural boundary do not need an ADR (see `CLAUDE.md`).
- Revisit when: the number of ADRs makes a flat index unwieldy (unlikely before v1).

## Action Items
1. [x] Create `docs/adr/` with template and index.
2. [x] Add the ADR requirement to `CLAUDE.md`.
3. [ ] Reference the relevant ADR number in every pull request that touches an architectural boundary.
