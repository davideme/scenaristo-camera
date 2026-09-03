# ADR-0010: Keep domain logic free of Android dependencies; defer Kotlin Multiplatform

**Status:** Proposed
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 9 (Phase 4 "port the capture engine"), 6.2, 6.3, 6.4, 6.10
**Related ADRs:** ADR-0002, ADR-0005, ADR-0007, ADR-0011

## Context
The PRD plans to "port the capture engine to AVFoundation" in Phase 4. The capture engine proper (camera session, encoder, muxer) is platform-bound and will be rewritten. But a meaningful slice of the app is pure logic with no platform surface: the country-to-grid table and its fallback order, the exposure controller (ADR-0005), Kelvin-to-preset mapping, the capability report model and gating rules (ADR-0011), the state document and command validation (ADR-0007), the storage-remaining and bitrate arithmetic, warning thresholds. Writing that twice risks the two platforms drifting on behaviour the PRD requires to be identical. Kotlin Multiplatform (KMP) can compile that slice into an iOS framework, but it adds Gradle-to-Xcode integration, a second toolchain to keep green, and Swift interop constraints, for a solo developer who has not yet proven the Android engine.

## Decision
We will place all platform-independent logic in a Gradle module `:domain` that depends only on the Kotlin standard library and `kotlinx.serialization` (no `android.*`, no coroutines-Android, no Ktor). Inputs and outputs are plain data classes; platform layers adapt Camera2 results and AVFoundation values into them. We will **not** adopt KMP now. The module is written so that switching it to a multiplatform module later is a build-file change, not a refactor: no JVM-only APIs beyond what Kotlin/Native supports (no `java.*` imports, no reflection).

Behavioural parity is protected by **shared fixture tests**: golden JSON files under `docs/protocol/fixtures/` and `domain/fixtures/` (grid-table cases, AE step traces, capability-gating cases) that the Android tests run now and the iOS tests must run in Phase 4, whether the iOS domain code is Swift or KMP.

## Options Considered

### Option A: Pure-Kotlin `:domain` now, KMP decision deferred to Phase 4 (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | Low |
| Effort | Discipline only |
| Reversibility | High |

**Pros:** Zero toolchain cost now; keeps both futures open; fixture tests give parity guarantees either way.
**Cons:** If KMP is chosen later, the Phase 4 team pays the integration cost then.

### Option B: KMP from day one
**Pros:** One implementation of the domain slice.
**Cons:** Xcode framework export, Swift interop ergonomics, and CI for two toolchains before the Android engine works; a distraction from Phase 0's actual risk.

### Option C: Rewrite domain logic in Swift, no shared code
**Pros:** Idiomatic on both sides.
**Cons:** Drift is inevitable without the fixture tests; with them, this is a valid Phase 4 outcome and remains available.

## Trade-off Analysis
The cost of Option A is a lint rule; the cost of B is real and paid at the wrong time. The fixture-test contract, not code sharing, is what guarantees the PRD's "identical behaviour" claims across platforms.

## Consequences
- Easier: unit testing without a device; the Phase 4 port has an explicit list of what is logic versus what is capture.
- Harder: a lint or Gradle check must enforce the no-Android-imports rule so it does not erode.
- Revisit when: Phase 4 planning; measure `:domain` size and how much of it changed during Phases 1–3.

## Action Items
1. [ ] Create `:domain` with a Gradle check that fails on `android.*` or `java.*` imports.
2. [ ] Seed fixtures for the grid table (including Japan, Saudi Arabia, Brazil) and capability gating.
