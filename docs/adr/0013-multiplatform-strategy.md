# ADR-0013: Multiplatform strategy: native capture per platform, shared web UI, shared protocol, platform-free domain

**Status:** Accepted (2026-09-03, Davide; consolidates ADR-0002, ADR-0007, ADR-0009, ADR-0010)
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 9 (Phase 4), 6.8, 8-Q7
**Related ADRs:** ADR-0002, ADR-0007, ADR-0009, ADR-0010

## Context
The product ships on Android first and iOS second, with identical capture behaviour and an identical browser remote. Earlier ADRs each decided one piece of how that is achieved, but no record states the whole strategy, so the question "what is shared and what is written twice" had no single answer. This ADR gives that answer and evaluates the cross-platform toolkits that were rejected or deferred, so the reasoning is in one place.

The forces: every control the app exists for is a native camera call (Camera2 on Android, AVFoundation on iOS) with no abstraction that preserves manual exposure, locked frame duration, and hardware encoder configuration; the user-facing surface where most interaction happens is a browser page, which is already cross-platform; the phone screen UI is deliberately minimal (preview, record, QR, settings sheet); one developer builds it.

## Decision
We will share what is platform-neutral and write natively what touches hardware:

| Layer | Android | iOS | Shared? |
|---|---|---|---|
| Capture engine (camera session, exposure/WB application, recording, audio) | Kotlin, CameraX 1.6.2 (`Recorder`, `ImageAnalysis`, `Camera2Interop`) | Swift, AVFoundation, `AVAssetWriter` | No. Ported by hand in Phase 4. |
| Domain logic (grid table, exposure controller maths, Kelvin mapping, capability gating, state document, warnings) | `:domain`, a single-target Kotlin Multiplatform module | Add an iOS target, or port to Swift; decided in Phase 4 | Behaviour shared via golden fixture tests; code sharing is a one-line target addition (ADR-0010). |
| Phone-to-browser protocol | Ktor server | Network.framework or equivalent | Yes: one spec with JSON Schemas and fixtures (ADR-0007). |
| Web remote UI | Static bundle in app resources | Same bundle in the app bundle | Yes, byte-identical (ADR-0009). |
| Phone UI | Jetpack Compose | SwiftUI | No. It is small; native is cheaper than interop. |

Cross-platform app frameworks (Flutter, React Native) are rejected: the native plugin would be the whole product. `:domain` is already a Kotlin Multiplatform module with one target (ADR-0010); adding the iOS target, and Compose Multiplatform for the phone UI, are deferred to Phase 4 planning, not rejected.

## Options Considered

### Option A: Native capture, shared web UI and protocol, fixture-tested domain (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Two capture engines, one contract |
| Risk | Low; each engine uses its platform's first-class API |
| Effort | Capture written twice; the rest once |
| Reversibility | High; adding KMP later is incremental |

**Pros:** No abstraction fights the camera; the surface users touch most is shared; the contract is testable before iOS exists.
**Cons:** Two capture codebases to keep behaviourally aligned; the fixtures carry that burden.

### Option B: Kotlin Multiplatform for domain now, native capture and UI
**Pros:** One domain implementation; no drift risk in that slice.
**Cons:** Kotlin/Native toolchain, Xcode framework export, and Swift interop paid before the Android engine works. Deferred (ADR-0010).

### Option C: Compose Multiplatform for the phone UI, plus KMP domain
**Pros:** One phone UI as well; Compose Multiplatform on iOS is stable.
**Cons:** The camera preview view requires UIKit interop on iOS; the phone UI is a few screens; savings are small relative to the toolchain cost. Deferred to Phase 4 with the same trigger as B.

### Option D: Flutter or React Native
**Pros:** Shared UI and app shell.
**Cons:** Camera2 and AVFoundation manual control would be re-exposed through a plugin the size of the app; the browser UI already covers the cross-platform need. Rejected (ADR-0002).

### Option E: Web-first phone UI (WebView on both platforms rendering the same bundle)
**Pros:** Phone UI shared with the web remote.
**Cons:** The phone screen must render the camera preview surface and handle permissions and lifecycle natively anyway; a WebView-over-native-preview hybrid adds interop for a minimal screen. Not worth it in v1; revisit with the teleprompter (PRD 6.12), where a shared web rendering may help.

## Trade-off Analysis
The cost that matters is capture code written twice, and no option removes it. Given that, the cheapest correct strategy is to share the artifacts that are already platform-neutral (the bundle, the protocol) and enforce behavioural parity through fixtures, leaving code-sharing tooling as a Phase 4 optimisation once the size of `:domain` is known.

## Consequences
- Easier: Phase 4 has a precise list: port `:capture`, implement the server against an existing spec, copy `web/dist`, pass the fixtures.
- Harder: fixture coverage must be treated as the parity contract; a behaviour not covered by a fixture can drift.
- Revisit when: Phase 4 planning, with measured `:domain` size and churn; or when a second shared native screen (teleprompter) appears.

## Action Items
1. [ ] Link this ADR from the PRD Phase 4 row.
2. [ ] Track `:domain` line count and change frequency during Phases 1–3 to inform the KMP decision.
