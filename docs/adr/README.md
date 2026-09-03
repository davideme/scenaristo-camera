# Architecture Decision Records

Decisions that shape the Scenaristo Camera architecture, one per file, numbered in order of creation. The process is defined in [ADR-0001](0001-record-architecture-decisions.md) and the requirement to write one is in the repository [CLAUDE.md](../../CLAUDE.md). Copy [0000-template.md](0000-template.md) to start a new one.

## Index

| ADR | Title | Status | PRD |
|---|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted | all |
| [0002](0002-android-capture-stack.md) | Android capture engine on CameraX 1.6.2 (pinned) with the stock `Recorder`; accepted MVP losses; revisit at CameraX 1.7 | Accepted | 6.1, 6.6, 6.7, 6.10, 8-Q7 |
| [0003](0003-foreground-service-for-capture-and-server.md) | Run capture and the web server in a foreground service; screen-off recording on Android | Accepted | 6.8, 6.9 |
| 0004 | Withdrawn (fragmented MP4 recording). The plan lives in ADR-0002 Option B and its 1.7 revisit pin; the number is not reused. | Withdrawn | 6.7 |
| [0005](0005-exposure-control-own-metering-loop.md) | In-app metering, damped ISO loop, flicker-safe shutter ladder | Accepted | 6.2, 6.3 |
| [0006](0006-local-web-server.md) | Ktor CIO, plain HTTP, per-interface LAN binding, Host validation | Proposed; security scope Accepted | 6.8 |
| [0007](0007-control-protocol.md) | JSON over one WebSocket, revisioned snapshots, idempotent commands | Accepted | 6.8 |
| [0008](0008-preview-transport.md) | JPEG over WebSocket behind a transport abstraction | Proposed, confirms 6.8 | 6.8, 6.12 |
| [0009](0009-web-ui-static-bundle.md) | Web UI as one static bundle (Vite + TypeScript + Preact) embedded in both apps | Proposed, confirms 8-Q7 | 6.8, 9 |
| [0010](0010-platform-free-domain-defer-kmp.md) | Platform-free `:domain` module; defer Kotlin Multiplatform | Proposed | 9 |
| [0011](0011-per-lens-capability-gating.md) | Require `MANUAL_SENSOR` to record; degrade WB without `MANUAL_POST_PROCESSING` | Accepted | 6.4, 6.10, 8-Q1 |
| [0012](0012-minimum-os-versions.md) | Minimum OS Android 14 / iOS 16, with a measurement trigger | Accepted | 6.10, 8 |
| [0013](0013-multiplatform-strategy.md) | Multiplatform: native capture per platform, shared web UI and protocol, fixture-tested domain; KMP and Compose Multiplatform deferred | Proposed | 9, 8-Q7 |

## Challenges to positions stated in the PRD

Each row is a technical statement in the PRD that an ADR proposes to amend, and needs a decision by Davide. Once decided, update the PRD text and set the ADR to Accepted.

| PRD text | Challenge | ADR |
|---|---|---|
| 6.9 "The app must stay in the foreground to record (both OSes suspend the camera in the background)" | False on Android. A `camera\|microphone` foreground service keeps recording and the web server alive with the screen locked, which also removes the screen from the thermal budget. | 0003 |
| 6.3 "Do not silently raise shutter speed" on overexposure | At 1/50 s and base ISO, a main camera at f/1.8 is overexposed above ~400–800 lux, i.e. most daylit desks. 1/100 (50 Hz) and 1/120 (60 Hz) are also band-free; use them as one visible ladder rung before warning. | 0005 |
| 6.3 "read the exposure offset from the device" | Android provides no metering feedback with AE off. Meter in-app from the analysis stream on both platforms. | 0005 |
| 6.8 "Multiple browsers may connect; last write wins" | Without a revision, stale clients clobber fresh changes and a retried record message toggles recording off. Use commands with ids and revisioned snapshots. | 0007 |
| 6.8 "Plain HTTP" (kept) | Consequence not stated in the PRD: no secure context, so WebCodecs is unavailable; preview transport is limited to JPEG/WebSocket now and WebRTC later. Decision stands. | 0006, 0008 |
| 6.8 "bind to the LAN interface only" | There are several LAN interfaces (Wi-Fi, hotspot, tethering); bind per interface, rebind on change, and validate the `Host` header against DNS rebinding. | 0006 |
| 6.7 "fragmented MP4 (or periodic moov updates)" and the crash-resilience acceptance criterion | Not achievable with the CameraX 1.6.2 stock `Recorder`. Accepted as an MVP loss; moves to P1 with ADR-0002 Option B as the plan. | 0002 |
| 6.7 "HEVC if a hardware encoder is present" | CameraX 1.6.2 offers no SDR codec selector; codec follows the device profile and is shown before recording. Enforced again at CameraX 1.7 via `setVideoMimeType`. | 0002 |
| 6.6 "Level meter updates at ≥ 10 Hz" | CameraX reports amplitude every 200 ms. MVP ships 5 Hz. | 0002 |
| 8-Q7 "Native Android (Kotlin, Camera2)" | Kotlin yes; Camera2 reached through CameraX 1.6.2 and `Camera2Interop`, not directly. Camera2-direct kept as the escape hatch. | 0002 |
| 6.1 "Stabilisation: Off" | Extend to OIS (`LENS_OPTICAL_STABILIZATION_MODE OFF`); OIS on a tripod produces drift. | 0002 |
| 9 Phase 4 "Port the capture engine to AVFoundation" | Only the capture layer is ported; the web bundle and protocol are shared as-is, domain logic is platform-free, and parity is enforced by shared fixture tests. | 0010, 0013 |
| 8 Open Question 1 (blocking) | Answered: refuse recording on lenses without `MANUAL_SENSOR`; degrade WB via locked AWB modes without `MANUAL_POST_PROCESSING`. | 0011 |
| 8 Decision log "Minimum OS: Android 14" | No rationale recorded and no capture API needs API 34. Kept for the smaller test matrix, with a Play Console measurement before public beta. | 0012 |
| 8-Q7 tech stack | Decided: Kotlin + CameraX 1.6.2, Compose, Ktor, static web bundle. Cross-platform UI frameworks rejected for the capture path. | 0002, 0006, 0009 |

## PRD amendments

All amendments listed by the ADRs above were applied to the PRD on 2026-09-03 (Draft v0.3). The PRD cites the ADR next to each amended passage. When a Proposed ADR is rejected, revert the cited passage and mark the ADR Deprecated.
