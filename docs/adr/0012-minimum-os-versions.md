# ADR-0012: Minimum OS is Android 14 (API 34) and iOS 16

**Status:** Accepted (decided 2026-09-03 per PRD decision log); rationale reconstructed here and open to challenge
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 6.10, 8 decision log, 9 (Phase 1, Phase 4)
**Related ADRs:** ADR-0002, ADR-0003, ADR-0011

## Context
The PRD decision log fixes Android 14 and iOS 16 as floors but records no rationale. The PRD itself notes that the floor "does not guarantee manual controls", which come from per-lens flags (ADR-0011). Every Camera2 and MediaCodec API this design uses is available from API 29 (`isHardwareAccelerated`, `isSessionConfigurationSupported`, mandatory stream combinations) or API 31 (`MediaCodec` improvements, `PerformancePoints`). A high floor therefore buys mostly non-capture benefits and costs installed base. What API 34 does buy: mandatory foreground-service types (ADR-0003) are the same code path on all supported devices; predictable behaviour of `NsdManager` and the platform mDNS resolver; recent HAL versions on the devices that ship it, which correlates with better `MANUAL_SENSOR` honesty; and a much smaller OEM-quirk matrix for a solo developer. iOS 16 excludes only devices without the required hardware encoders and is uncontroversial.

## Decision
We keep Android 14 (API 34) and iOS 16 as the minimums for v1, on the grounds that the constrained test matrix is worth more than the excluded installed base while one person builds and validates the capture engine on two reference devices. The number of excluded devices is to be measured, not assumed.

## Options Considered

### Option A: API 34 / iOS 16 (chosen, as decided)
| Dimension | Assessment |
|---|---|
| Complexity | Lowest test matrix |
| Risk | Lost users on older, otherwise capable phones |
| Effort | Lowest |
| Reversibility | High: lowering a floor later is a build change plus testing |

### Option B: API 29 / iOS 16
**Pros:** Every API this design needs exists; large additional installed base.
**Cons:** Foreground-service typing differences across API 29–33, older HALs with more `MANUAL_SENSOR` lies, five extra OS versions to test.

### Option C: API 31 / iOS 16
**Pros:** Middle ground; API 31 is where most modern codec and camera behaviour stabilised.
**Cons:** Still widens the matrix during the phase where the engine is unproven.

## Trade-off Analysis
Raising a floor after launch is painful; lowering it is cheap. Starting high during Phases 0–3 and lowering to API 31 for the public beta if Play Console data shows a large excluded share is the reversible ordering. The decision stands, with a measurement trigger.

## Consequences
- Easier: one foreground-service code path; fewer OEM quirks in Phase 0–2.
- Harder: some users with capable 2021–2022 phones cannot install; store listing must state the requirement.
- Revisit when: before Phase 3 (public beta), using Play Console device-catalogue numbers for API 31–33 among phones with a `MANUAL_SENSOR` main camera. If that share is material, lower to API 31 and add one reference device on Android 12.

## Action Items
1. [ ] Add the rationale above to the PRD decision log entry.
2. [ ] Before Phase 3: pull Play Console device-catalogue data for API 31–33 and record the excluded share in this ADR.
