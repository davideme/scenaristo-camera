# ADR-0010: Keep domain logic free of Android dependencies; defer Kotlin Multiplatform

**Status:** Superseded by ADR-0015 (proposed 2026-09-03). Was Accepted 2026-09-03, Davide.
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 9 (Phase 4 "port the capture engine"), 6.2, 6.3, 6.4, 6.10
**Related ADRs:** ADR-0002, ADR-0005, ADR-0007, ADR-0011

## Context
The PRD plans to "port the capture engine to AVFoundation" in Phase 4. The capture engine proper (camera session, encoder, muxer) is platform-bound and will be rewritten. But a meaningful slice of the app is pure logic with no platform surface: the country-to-grid table and its fallback order, the exposure controller (ADR-0005), Kelvin-to-preset mapping, the capability report model and gating rules (ADR-0011), the state document and command validation (ADR-0007), the storage-remaining and bitrate arithmetic, warning thresholds. Writing that twice risks the two platforms drifting on behaviour the PRD requires to be identical. Kotlin Multiplatform (KMP) can compile that slice into an iOS framework, but it adds Gradle-to-Xcode integration, a second toolchain to keep green, and Swift interop constraints, for a solo developer who has not yet proven the Android engine.

## Decision
We will place all platform-independent logic in a Gradle module `:domain` declared with the `kotlin("multiplatform")` plugin and a **single `androidTarget()`**, with all code in `commonMain`, depending only on the Kotlin standard library and `kotlinx.serialization` (already multiplatform). Inputs and outputs are plain data classes; platform layers adapt CameraX results and AVFoundation values into them. A single-target multiplatform module pulls in no Kotlin/Native compiler, no Xcode export, and no second CI toolchain, while the compiler itself refuses `java.*`, reflection, and JVM-only standard-library members in `commonMain`, which is the rule a hand-written import check could only approximate. Adding `iosArm64()` in Phase 4 is then a one-line build change. We do **not** ship an iOS framework now, and Compose Multiplatform for the phone UI stays deferred (ADR-0013).

Behavioural parity is protected by **shared fixture tests**: golden JSON files under `docs/protocol/fixtures/` and `domain/fixtures/` (grid-table cases, AE step traces, capability-gating cases) that the Android tests run now and the iOS tests must run in Phase 4, whether the iOS domain code is Swift or KMP.

## Options Considered

### Option A: Single-target Kotlin Multiplatform `:domain` now, iOS target deferred to Phase 4 (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | Low |
| Effort | Discipline only |
| Reversibility | High |

**Pros:** Zero toolchain cost now; the compiler enforces platform-freeness instead of a custom lint; keeps both futures open; fixture tests give parity guarantees either way.
**Cons:** The multiplatform Gradle plugin's setup differs slightly from `kotlin("jvm")`; if the iOS domain is later written in Swift instead, the target is simply never added.

### Option B: Full KMP with an iOS target from day one
**Pros:** One implementation of the domain slice, exported to Swift immediately.
**Cons:** Xcode framework export, Swift interop ergonomics, and CI for two toolchains before the Android engine works; a distraction from Phase 0's actual risk.

### Option D: Plain `kotlin("jvm")` module with a custom import check (first draft)
**Pros:** Familiar plugin.
**Cons:** A hand-written Gradle task that fails on `android.*` or `java.*` imports still lets JVM-only standard-library usage through (`String.format`, `synchronized`, JVM `Regex` overloads), so the promised "build-file change" in Phase 4 becomes a first-time migration.

### Option C: Rewrite domain logic in Swift, no shared code
**Pros:** Idiomatic on both sides.
**Cons:** Drift is inevitable without the fixture tests; with them, this is a valid Phase 4 outcome and remains available.

## Trade-off Analysis
The cost of Option A is a lint rule; the cost of B is real and paid at the wrong time. The fixture-test contract, not code sharing, is what guarantees the PRD's "identical behaviour" claims across platforms.

## Consequences
- Easier: unit testing without a device; the Phase 4 port has an explicit list of what is logic versus what is capture.
- Harder: nothing material; the compiler does the enforcement.
- Revisit when: Phase 4 planning; measure `:domain` size and how much of it changed during Phases 1–3.

## Action Items
1. [ ] Create `:domain` as a single-target multiplatform module with all sources in `commonMain`.
2. [ ] Seed fixtures for the grid table (including Japan, Saudi Arabia, Brazil) and capability gating.
