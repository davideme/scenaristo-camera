# Architecture Decision Records

Decisions that shape the Scenaristo Camera architecture, one per file, numbered in order of creation. The process is defined in [ADR-0001](0001-record-architecture-decisions.md) and the requirement to write one is in the repository [CLAUDE.md](../../CLAUDE.md). Copy [0000-template.md](0000-template.md) to start a new one.

## Writing one

1. Copy [0000-template.md](0000-template.md) to `NNNN-short-title.md` with the next free number. Numbers are never reused, including withdrawn ones (see 0004).
2. Status starts as `Proposed`. **Only Davide sets `Accepted`.** Never edit an Accepted ADR's decision: write a new ADR with `Supersedes ADR-NNNN` and set the old one to `Superseded by ADR-MMMM`.
3. Fill every section. Name at least two real options with a dimension table each. State a concrete "revisit when" trigger in Consequences — a measurement, a release, a date. If the ADR changes PRD text, list the amendment under Decision.
4. Add the row to the Index below in the same change, and to the Challenges table if it amends the PRD, or to Open conflicts if it contradicts another ADR.
5. Reference the ADR number in the commit body and the pull request description (`ADR-0007`).
6. Run `./tools/check-adr-index.sh`; CI runs it too.

When a PRD statement and an ADR disagree, the ADR's Status decides: Accepted ADRs win and the PRD is pending amendment; Proposed ADRs are a challenge awaiting Davide's decision, so do not build against them as settled.

## Index

| ADR | Title | Status | PRD |
|---|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted | all |
| [0002](0002-android-capture-stack.md) | Android capture engine on CameraX 1.6.2 (pinned) with the stock `Recorder`; accepted MVP losses; revisit at CameraX 1.7 | Accepted | 6.1, 6.6, 6.7, 6.10, 8-Q7 |
| [0003](0003-foreground-service-for-capture-and-server.md) | Run capture and the web server in a foreground service; screen-off recording on Android | Accepted | 6.8, 6.9 |
| 0004 | Withdrawn (fragmented MP4 recording). The plan lives in ADR-0002 Option B and its 1.7 revisit pin; the number is not reused. | Withdrawn | 6.7 |
| [0005](0005-exposure-control-own-metering-loop.md) | In-app metering, damped ISO loop, flicker-safe shutter ladder | Accepted | 6.2, 6.3 |
| [0006](0006-local-web-server.md) | Ktor CIO, plain HTTP, single bind with request-time LAN checks, no mDNS | Accepted | 6.8 |
| [0007](0007-control-protocol.md) | JSON over one WebSocket, revisioned snapshots, idempotent commands | Accepted | 6.8 |
| [0008](0008-preview-transport.md) | MJPEG HTTP stream rendered natively by the browser; JPEG over WebSocket as fallback | Accepted | 6.8, 6.12 |
| [0009](0009-web-ui-static-bundle.md) | Web UI as one static bundle (Vite + TypeScript + Preact) built into app resources; TS types generated from `:domain` | Accepted | 6.8, 9 |
| [0010](0010-platform-free-domain-defer-kmp.md) | `:domain` as a single-target Kotlin Multiplatform module; iOS target deferred | Superseded by [0015](0015-domain-module-build-shape.md) | 9 |
| [0011](0011-per-lens-capability-gating.md) | Require `MANUAL_SENSOR` to record; degrade WB without `MANUAL_POST_PROCESSING` | Accepted | 6.4, 6.10, 8-Q1 |
| [0012](0012-minimum-os-versions.md) | Minimum OS Android 14 / iOS 16, with a measurement trigger | Accepted | 6.10, 8 |
| [0013](0013-multiplatform-strategy.md) | Multiplatform: native capture per platform, shared web UI and protocol, fixture-tested domain; KMP and Compose Multiplatform deferred | Accepted | 9, 8-Q7 |
| [0014](0014-build-toolchain.md) | Gradle 9.7.1 / AGP 9.4.0 / Kotlin 2.4.10 on Java 17; `android` CLI provisions the SDK; wrapper committed; `targetSdk` stated explicitly | Proposed | 6.1, 6.10, 8-Q7, 9 |
| [0015](0015-domain-module-build-shape.md) | `:domain` with two JVM-family targets under the Android KMP library plugin; platform-freeness enforced by compiler plus lint. Supersedes 0010 | Proposed | 9, 6.2, 6.3, 6.4, 6.10 |
| [0016](0016-continuous-integration.md) | PRs gated on compilation, host tests and repo invariants; device verification stays manual | Proposed | 6.1-6.7, 6.10, 9 |
| [0017](0017-phase-0-verification-matrix.md) | Phase 0 runs on one reference device (Pixel 10) with a MacBook browser; second phone and iOS Safari deferred | Accepted | 9, 8-Q5, 6.8, 6.10 |
| [0018](0018-preview-tap-for-metering-and-preview-frames.md) | Metering and preview frames come from a `CameraEffect` tap on the preview stream; no `ImageAnalysis` alongside UHD recording | Accepted | 6.1, 6.3, 6.5, 6.8, 6.10 |
| [0019](0019-stop-the-service-when-idle.md) | Stop the capture service when the user leaves the app and neither a recording nor a remote is using it; explicit stop action | Proposed | 6.8, 6.9 |
| [0020](0020-record-into-mediastore.md) | Record into MediaStore `Movies/Scenaristo Camera/` rather than the app's private directory; takes become visible and survive uninstall | Proposed | 6.7, 3 |
| [0021](0021-remove-too-bright-and-bump-the-protocol.md) | Remove the unused `TOO_BRIGHT` warning; `PROTOCOL_VERSION` becomes 2 | Proposed | 6.3, 6.8 |

## Challenges to positions stated in the PRD

Each row is a technical statement in the PRD that an ADR proposes to amend, and needs a decision by Davide. Once decided, update the PRD text and set the ADR to Accepted.

| PRD text | Challenge | ADR |
|---|---|---|
| 6.9 "The app must stay in the foreground to record (both OSes suspend the camera in the background)" | False on Android. A `camera\|microphone` foreground service keeps recording and the web server alive with the screen locked, which also removes the screen from the thermal budget. | 0003 |
| 6.3 "Do not silently raise shutter speed" on overexposure | At 1/50 s and base ISO, a main camera at f/1.8 is overexposed above ~400–800 lux, i.e. most daylit desks. 1/100 (50 Hz) and 1/120 (60 Hz) are also band-free; use them as one visible ladder rung before warning. | 0005 |
| 6.3 "read the exposure offset from the device" | Android provides no metering feedback with AE off. Meter in-app from the analysis stream on both platforms. | 0005 |
| 6.8 "Multiple browsers may connect; last write wins" | Without a revision, stale clients clobber fresh changes and a retried record message toggles recording off. Use commands with ids and revisioned snapshots. | 0007 |
| 6.8 Controls "Shutter (1/50, 1/60, override) … ISO (auto / manual value)" | Shutter and ISO are outputs of the in-app exposure loop, not inputs, so `SettingsPatch` carries grid frequency, white balance and lens only. The browser reports shutter and ISO, including the flicker-safe step, and cannot set them. A manual ISO lock, which 6.3 still offers, would need its own field. | 0005, 0007 |
| 6.8 "Plain HTTP" (kept) | Consequence not stated in the PRD: no secure context, so WebCodecs is unavailable; preview transport is limited to JPEG/WebSocket now and WebRTC later. Decision stands. | 0006, 0008 |
| 6.8 "bind to the LAN interface only" and "mDNS name advertised" | Bind once and enforce LAN-only per request (private remote address, IP-literal `Host`); `NsdManager` on API 34 cannot register a hostname, so mDNS is dropped from v1. | 0006 |
| 6.8 "JPEG frames over WebSocket" | Browsers render MJPEG natively from an `<img>`; serve `multipart/x-mixed-replace` over HTTP and write no preview protocol. | 0008 |
| 6.7 "the partial file appears in the camera roll or Movies folder" and 3 "files land in the device's camera roll or Movies folder" | Both are already true of the intent and false of the code: takes go to the app's private directory, are invisible to the gallery and are deleted on uninstall. ADR-0020 records into MediaStore `Movies/`, and asks whether the PRD should be narrowed from "camera roll or Movies" to the one chosen. | 0020 |
| 6.7 "fragmented MP4 (or periodic moov updates)" and the crash-resilience acceptance criterion | The CameraX 1.6.2 stock `Recorder` rewrites `moov` every second within a 400 KB reserve, so resilience is covered up to a take length Phase 0 measures; guaranteed resilience for any length is P1. | 0002 |
| 6.7 "HEVC if a hardware encoder is present" | CameraX 1.6.2 offers no SDR codec selector; codec follows the device profile and is shown before recording. Enforced again at CameraX 1.7 via `setVideoMimeType`. | 0002 |
| 6.6 "Level meter updates at ≥ 10 Hz" | CameraX reports amplitude every 200 ms. MVP ships 5 Hz. | 0002 |
| 8-Q7 "Native Android (Kotlin, Camera2)" | Kotlin yes; Camera2 reached through CameraX 1.6.2 and `Camera2Interop`, not directly. Camera2-direct kept as the escape hatch. | 0002 |
| 6.1 "Stabilisation: Off" | Extend to OIS (`LENS_OPTICAL_STABILIZATION_MODE OFF`); OIS on a tripod produces drift. | 0002 |
| 9 Phase 4 "Port the capture engine to AVFoundation" | Only the capture layer is ported; the web bundle and protocol are shared as-is, domain logic is platform-free, and parity is enforced by shared fixture tests. | 0010, 0013 |
| 8 Open Question 1 (blocking) | Answered: refuse recording on lenses without `MANUAL_SENSOR`; degrade WB via locked AWB modes without `MANUAL_POST_PROCESSING`. | 0011 |
| 8 Decision log "Minimum OS: Android 14" | No rationale recorded and no capture API needs API 34. Kept for the smaller test matrix, with a Play Console measurement before public beta. | 0012 |
| 8-Q7 tech stack | Decided: Kotlin + CameraX 1.6.2, Compose, Ktor, static web bundle. Cross-platform UI frameworks rejected for the capture path. | 0002, 0006, 0009 |
| 9 Phase 0 "two Android reference devices (one Pixel, one Samsung)" and its "on both devices" exit criterion; 9 dependencies "the reference device list ... include a Pixel, a Samsung, and one device from an OEM known to restrict Camera2 manual controls" | The hardware that exists is one Pixel 10 and one MacBook. Phase 0's two target risks (interop keys, thermal headroom) are single-device properties; OEM variation first bites at public beta. Run Phase 0 on the Pixel 10 and make the Samsung plus the restrictive-OEM device the pre-beta matrix. | 0017 |
| 8-Q5 "Phase 0 checks iPhone Safari rendering" | There is no iPhone or iPad. macOS Safari is WebKit and covers the MJPEG decode path; iOS media policy is a Phase 4 question about a Phase 4 platform. Phase 0 checks macOS Safari and Chrome. | 0017 |
| ADR-0009 "a Gradle `Exec` task in `:app` runs `npm run build`" | `web/` uses pnpm 10, pinned via `packageManager`. ADR-0009's decision is unchanged; only the command is. Read every `npm run build` in ADR-0009 as `pnpm run build`. | 0014 |

## Open conflicts

| Documents | Conflict | Resolution needed |
|---|---|---|
| ADR-0015 vs ADR-0010 | ADR-0010 specifies a single `androidTarget()` and justifies it by compiler-enforced platform-freeness. AGP 9 removed that API, and a single target enforces nothing (measured 2026-09-03: `android.*` and `java.*` both compile in `commonMain`). ADR-0015 proposes two JVM-family targets plus a lint. | **Awaiting Davide.** ADR-0015 is Proposed; ADR-0010 is marked Superseded pending its acceptance. |
| ADR-0018 vs ADR-0005, ADR-0008 | Both source frames from `ImageAnalysis`; Phase 0 (#20) measured that a UHD recording and `ImageAnalysis` cannot coexist on the Pixel 10 in any of nine configurations. ADR-0018 keeps both decisions intact and changes only the frame source to a `CameraEffect` tap on the preview stream. | **Resolved 2026-09-04 (Davide):** ADR-0018 Accepted. ADR-0005 and ADR-0008 keep their decisions and their text; only the frame source changes, and ADR-0018 governs it until a CameraX release makes UHD + `ImageAnalysis` bindable (#27). |
| ADR-0017 vs ADR-0002, 0003, 0005, 0008, 0011, 0016 | Those ADRs phrase their Phase 0 action items "on both reference devices" and ADR-0016's context states the reference devices are a Pixel and a Samsung. ADR-0017 narrows the device scope of ADR-0002 (2, 3, 4), ADR-0003 (2), ADR-0005 (2, 3), ADR-0008 (1) and ADR-0011 (3) to one Pixel 10 plus a macOS browser. No decision in those ADRs changes. | **Resolved 2026-09-03 (Davide):** ADR-0017 Accepted. Their action items are ticked against one device; their own text is left unedited, and ADR-0017 governs the device scope until #29 widens the matrix and supersedes it. |
| ADR-0002 vs `docs/spec-chapter-markers.md` CM-1 | CM-1 requires an app-owned fragmented muxer with soft-remux finalisation in Phase 1; ADR-0002 chose the CameraX stock `Recorder` with no muxer access. | **Resolved 2026-09-03 (Davide):** CM-1 deferred to the CameraX 1.7 revisit; added to the ADR-0002 revisit checklist. |

## PRD amendments

All amendments listed by the ADRs above were applied to the PRD on 2026-09-03: the first set in
Draft v0.3, and ADR-0017's (section 9 phasing and dependencies, 8-Q5) in Draft v0.4. The PRD cites the ADR next to each amended passage. Every ADR the PRD cites is Accepted as of 2026-09-03. If a future Proposed ADR amends the PRD before acceptance, mark the passage provisional here; when such an ADR is rejected, revert the cited passage and mark the ADR Deprecated.
