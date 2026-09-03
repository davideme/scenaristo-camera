# ADR-0009: Build the web UI as one static bundle embedded unchanged in both apps

**Status:** Proposed (confirms PRD 8-Q7 recommendation)
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 6.8, 8-Q7, 9 (Phase 2, Phase 4)
**Related ADRs:** ADR-0006, ADR-0007, ADR-0008

## Context
The browser UI is the product's cross-platform surface: the same page must be served by the Android app in Phase 2 and the iOS app in Phase 4 with no changes. It must run in current and previous major versions of Chrome, Safari, Firefox, and Edge, on laptops, tablets, and phones, over plain HTTP from a LAN IP (no secure-context APIs, ADR-0006). It talks to exactly one WebSocket (ADR-0007) and renders JPEG preview frames (ADR-0008). There is no backend, no auth, no routing beyond one screen. The developer is one person; the stack must stay light to maintain.

## Decision
We will build the web UI as a separate repository directory `web/` producing a **single static bundle** (`index.html`, one JS file, one CSS file, inline SVG icons, no external requests) with **Vite + TypeScript + Preact**. The build output is copied into Android `assets/web/` and the iOS bundle by the respective app builds; the apps never modify it. The bundle has no runtime dependency on the host beyond the WebSocket URL, which it derives from `location`. The protocol types are generated from the JSON Schemas in `docs/protocol/` so that the UI, the Android server, and later the iOS server share one contract. The UI has zero network access other than `/ws` and same-origin static files, which also keeps the Host-validation rule in ADR-0006 simple.

Layout is responsive with a phone-width breakpoint (PRD 6.8 acceptance). State is the server snapshot; the UI holds no authoritative state and renders directly from the latest `rev`.

## Options Considered

### Option A: Vite + TypeScript + Preact (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | Low |
| Effort | Low |
| Reversibility | High; the protocol is the contract, not the framework |

**Pros:** ~4 KB runtime, React mental model and ecosystem, TypeScript for the protocol types, Vite makes the single-bundle output trivial.
**Cons:** Small ecosystem differences from React; negligible here.

### Option B: Vanilla TypeScript, no framework
**Pros:** No dependency at all.
**Cons:** A control panel with 20+ live-updating controls and warnings is exactly where a declarative renderer earns its keep; hand-written DOM diffing is where bugs go to live.

### Option C: React
**Pros:** Largest ecosystem.
**Cons:** ~10× the runtime size for no feature this UI needs; otherwise identical to A. Switching later is a one-line alias change.

### Option D: Svelte or SolidJS
**Pros:** Small, fast.
**Cons:** Equivalent to A on every dimension; A is chosen on familiarity with the React model. Not a strong preference.

### Option E: Native UI per platform (SwiftUI, Compose) for the remote
**Pros:** None for this product; the remote runs in a browser by definition.
**Cons:** Rejected by the PRD.

## Trade-off Analysis
Every framework option is acceptable; the decision that matters is "one static bundle, protocol-generated types, no host coupling", which makes Phase 4 a copy operation. Preact is chosen as the lightest option that keeps a declarative model.

## Consequences
- Easier: the iOS app reuses the UI by copying a directory; the UI can be developed against a mock WebSocket server from protocol fixtures without a phone.
- Harder: any host-specific behaviour (for example an iOS Local Network permission hint) must be expressed through the protocol's `platform` field, never by forking the bundle.
- Revisit when: the UI needs a second screen (teleprompter, file download) and routing becomes necessary, or a secure-context API becomes available.

## Action Items
1. [ ] Scaffold `web/` with Vite, TypeScript, Preact; add the JSON-Schema-to-TypeScript generation step.
2. [ ] Write a mock server that replays protocol fixtures (ADR-0007) for UI development.
3. [ ] Add a build step in the Android app that fails if `assets/web/` is stale relative to `web/dist/`.
