# ADR-0017: Run Phase 0 on one reference device — a Pixel 10 — with a MacBook browser as the only remote client

**Status:** Proposed
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 9 (phasing, dependencies and risks), 8-Q5, 6.8, 6.10
**Related ADRs:** ADR-0002, ADR-0003, ADR-0005, ADR-0008, ADR-0011, ADR-0012, ADR-0016

## Context

PRD section 9 asks for a reference device list "chosen before Phase 0" containing three phones: a
Pixel (LEVEL_3, all flags), a Samsung (main camera typically has `MANUAL_SENSOR`, secondary lenses
often do not), and one device from an OEM known to restrict Camera2 manual controls. Davide
[decided on 2026-09-03](../ROADMAP.md) not to add the third. The exit criterion is still written
against two: "flicker-free 10-minute 4K30 clip on **both** devices, no throttling, interop keys
honoured throughout", and every Phase 0 action item in ADR-0002, ADR-0003, ADR-0005 and ADR-0011
is phrased "on both reference devices". ADR-0016 states the same premise: "the reference devices
are a physical Pixel and a physical Samsung".

The hardware that actually exists for this project is one phone — a **Pixel 10** — and one remote
client, a **MacBook**. There is no Samsung, no iPhone and no iPad. Phase 0 exists to retire the
project's two largest risks (are the interop keys honoured, is there thermal headroom at 4K30 with
preview encoding); both are measurable on one device. Waiting on a second phone would stall the
only phase everything else is built on, and would buy coverage of a risk — OEM variation — that
does not need retiring until there are users on other OEMs.

The same applies on the browser side. PRD 8-Q5 and ADR-0008 make "does iPhone Safari render
`multipart/x-mixed-replace`" a Phase 0 check with a defined fallback (JPEG over WebSocket). The
remote client Phase 2's exit criterion names is a laptop — "a solo creator completes a take from a
laptop without touching the phone" — and iOS is Phase 4. macOS Safari is WebKit, so it exercises
the same MJPEG decode path; what it does not cover is iOS-specific media policy, which is a
Phase 4 question about a Phase 4 platform.

## Decision

We will run Phase 0 against **one reference device, a Pixel 10** (on the Android version it ships
with; the exact model and build go in the pull request hardware table, ADR-0016), and **one remote
client, a MacBook** — Safari and Chrome on macOS. A second phone and the iOS browser check move to
a later phase; the trigger is in Consequences.

Phase 0's exit criterion becomes: *a flicker-free 10-minute 4K30 clip on the Pixel 10, no
throttling, interop keys honoured throughout, previewed in a macOS browser.*

This narrows, but does not overturn, the device scope of Phase 0 action items in ADR-0002 (2, 3,
4), ADR-0003 (2), ADR-0005 (2, 3), ADR-0008 (1) and ADR-0011 (3): each reads "the reference
device" rather than "both reference devices" until the matrix widens. No decision in those ADRs
changes.

**PRD text to amend** (section 9 and 8-Q5):

| PRD text today | Amend to |
|---|---|
| 9, Phase 0 scope: "on two Android reference devices (one Pixel, one Samsung)" | "on the Pixel 10 reference device" |
| 9, Phase 0 exit: "Flicker-free 10-minute 4K30 clip on both devices" | "…on the reference device, previewed in a macOS browser" |
| 9, Dependencies bullet 1: "The reference device list should be chosen before Phase 0 and include a Pixel …, a Samsung …, and one device from an OEM known to restrict Camera2 manual controls" | Phase 0 runs on a Pixel 10 alone; the Samsung and the restrictive-OEM device become the pre-beta matrix (ADR-0017) |
| 9, Dependencies bullet 3: "Phase 0 verifies they are honoured on both reference devices" | "…on the reference device" |
| 8-Q5: "Phase 0 checks iPhone Safari rendering" | "Phase 0 checks macOS Safari and Chrome; iOS Safari is checked in Phase 4" |

## Options Considered

### Option A: One Pixel 10 plus a MacBook browser (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Lowest: the hardware is on the desk |
| Risk | Medium: OEM variation and iOS rendering stay unmeasured, and are named as such |
| Effort | None beyond rewording the criteria |
| Reversibility | High: adding a device widens the matrix, it does not invalidate a measurement |

**Pros:** Phase 0 starts now, on the risks that block every later phase. Both of the two "largest
technical risks" (interop keys, thermal headroom) are properties of one device and are fully
measurable on one. A Pixel is the best single choice available: PRD section 9 already characterises
it as LEVEL_3 with all flags, so it is the device most likely to honour the keys — which makes a
*failure* there maximally informative, since a key CameraX drops on a Pixel it drops everywhere.
macOS Safari is WebKit and covers the MJPEG decode path that ADR-0008's fallback trigger is about.

**Cons:** A Pixel is also the most permissive device, so a *pass* proves the least about the rest of
the fleet. Nothing in Phase 0 exercises a lens that lacks `MANUAL_SENSOR`, which is exactly the case
ADR-0011's gating exists for; that path stays fixture-tested until a second device arrives. The
"anything that works on only one of them is a bug" rule has nothing to compare against.

### Option B: Hold Phase 0 until a Samsung is acquired (status quo)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | High: schedule risk replaces coverage risk |
| Effort | The cost of a second phone, plus the delay |
| Reversibility | High |

**Pros:** Keeps every ADR action item and the PRD exit criterion literally true. Exercises OEM
variation and the secondary-lens gating path on real hardware, in the phase that was designed to
find it.

**Cons:** Buys coverage of a risk that first bites at public beta (Phase 3) by delaying the phase
that retires the risks biting *now*. Thermal and interop results from a Pixel do not become less
valid for having been taken first, so the sequencing costs nothing but the delay.

### Option C: Pixel 10 locally, Samsung through a cloud device farm
| Dimension | Assessment |
|---|---|
| Complexity | Medium: a second toolchain, and ADR-0016 keeps device runs out of CI |
| Risk | Medium-High: farm devices are shared, thermally unrepresentative, and pointed at a wall |
| Effort | Medium |
| Reversibility | High |

**Pros:** Covers the capability-flag questions (#20's key echoes, #21's codec profile) without
buying a phone; those are metadata reads that do not care what the camera is looking at.

**Cons:** Fails on the parts that matter most. Thermal headroom (#23) is meaningless on a
rack-mounted phone under forced airflow; the grey-card test (#24) and the ISO loop (#25) need a
controlled scene in front of the lens; flicker (6.2) needs a real 60 Hz panel. It would cover the
cheap half of the matrix and leave the expensive half exactly as unmeasured.

## Trade-off Analysis

Against Option B, the strongest alternative: the two risks Phase 0 was created to retire are
single-device properties, and a Pixel is a sufficient — indeed the most diagnostic — device for
both. What one device cannot retire is OEM variation, and that risk is not on Phase 0's critical
path: it first has consequences when strangers install the app, which is Phase 3. Option B pays a
schedule cost now for coverage whose deadline is two phases away. The honest cost of Option A is
that a Phase 0 pass means less than the PRD implies it does, and the mitigation is to say so
plainly rather than to let a green Phase 0 read as fleet-wide confidence — the same argument
ADR-0016 makes about a green CI run.

## Consequences

- **Easier:** Phase 0 can start against hardware that exists. Every issue in the milestone loses
  the "— Samsung" half of its checklist. The PR hardware table has one row to fill.
- **Harder:** A Phase 0 pass is evidence about a Pixel 10, not about Android. Three specific gaps
  follow, and none of them may be quietly closed by assumption:
  - **ADR-0011's per-lens gating is fixture-tested only.** Its action item 2 asks for fixtures for
    "Pixel and Samsung reference devices"; the Samsung fixture (a secondary lens without
    `MANUAL_SENSOR`) stays synthetic until a device with one exists. The refusal path in 6.10 ships
    unverified against real hardware.
  - **ADR-0008's fallback trigger cannot fire in Phase 0.** macOS Safari covers WebKit's MJPEG
    decode; it does not cover iOS media policy. If iOS Safari fails in Phase 4, the JPEG-over-
    WebSocket fallback arrives after the protocol is public — a worse moment than Phase 0, and the
    price of this decision.
  - **ADR-0012's Android 14 (API 34) floor has no device that runs it.** A Pixel 10 ships well
    above the minimum, so nothing in Phase 0 exercises the floor. The Play Console measurement
    ADR-0012 already schedules before public beta is now also the first on-device check of it.
- **Revisit when:** a second reference device is on the desk, or **before Phase 3 (public beta)
  opens** — whichever is first. #29 holds the checklist: re-run #20, #21, #23, #24 and #25 on the
  Samsung, add the real secondary-lens fixture to ADR-0011, and run ADR-0008 action item 1 on iOS
  Safari (which Phase 4 needs regardless). Widening the matrix supersedes this ADR; it does not
  invalidate the Pixel 10 numbers.

## Action Items

1. [ ] Amend PRD section 9 and 8-Q5 per the table under Decision, once this ADR is Accepted.
2. [ ] Record the exact Pixel 10 model and Android build with the first Phase 0 measurement, here
       and in the PR hardware table (ADR-0016).
3. [x] Opened #29 (Phase 3 milestone): re-run #20, #21, #23, #24 and #25 on a second OEM device,
       run ADR-0008 action item 1 on iOS Safari, replace ADR-0011's synthetic secondary-lens
       fixture with a captured one, and supersede this ADR with the result. **It must close before
       the beta opens.**
