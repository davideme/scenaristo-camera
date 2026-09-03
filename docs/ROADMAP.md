# Roadmap

PRD section 9 defines the phases and their exit criteria. This file maps them onto the issue
tracker. It is the answer to "what should I work on?".

**Picking something up cold? Take an open Phase 0 issue.** Phase 0 exists to retire the project's
two largest risks — whether the CameraX manual-control keys are honoured on real devices, and
whether there is thermal headroom for 4K30 with simultaneous preview encoding. Everything in
Phases 1–2 is built on assumptions Phase 0 either confirms or destroys.

Phases are GitHub **milestones**. The `P0`/`P1` labels are PRD priority ("must ship in v1"), not
phase: a `P0` story can land in Phase 2.

A `spike` issue is a measurement, not a feature. Its output is a number written back into the ADR
or PRD section it came from, not shipped code.

## Phase 0 — Android spike

**Exit criterion (PRD section 9):** a flicker-free 10-minute 4K30 clip on both reference devices,
no throttling, interop keys honoured throughout.

Reference devices are one Pixel and one Samsung. PRD section 9 also suggests a third device from
an OEM known to restrict Camera2 manual controls; **Davide decided on 2026-09-03 not to add one
now**, on the basis that the project uses `Camera2Interop` only and waits for CameraX 1.7 rather
than dropping to Camera2 direct. Phase 0's exit criterion therefore runs against two devices and
is not provisional on a third.

> ⚠️ That position narrows ADR-0002, which currently keeps Camera2-direct (its Option C) as the
> escape hatch if interop proves unreliable. If a Phase 0 measurement fails, the recorded response
> is now **wait for CameraX 1.7**, not switch to Option C. Reconciling ADR-0002's text is a
> follow-up ADR once Phase 0 reports.

| Issue | Work | Traces to |
|---|---|---|
| #20 | Verify `Camera2Interop` manual keys echo on both reference devices | ADR-0002 action 2 |
| #21 | Record the UHD codec each device's encoder profile selects | ADR-0002 action 3 |
| #22 | Measure the take length at which a force-killed recording stops being playable | ADR-0002 action 4, PRD 6.7 |
| #23 | Measure thermal headroom at 4K30 with simultaneous preview encoding | PRD 8-Q4, section 9 |
| #24 | Grey-card the Kelvin-to-gains curve at 3200 K and 5600 K | ADR-0011 action 3, PRD 6.4 |
| #25 | Tune the damped ISO loop against the PRD 6.3 criteria | PRD 8-Q3, ADR-0005 |
| #26 | Measure MJPEG preview latency and verify iPhone Safari rendering | PRD 8-Q5, ADR-0008 |

## Phase 1 — Android capture engine and phone UI

**Exit criterion:** internal users record real content with defaults only.

| Issue | Story | PRD | Notes |
|---|---|---|---|
| #2 | Correct shutter, ISO and white balance by default | 6.1–6.4 | The core promise. Split into task issues. |
| #15 | Override the mains frequency in mixed-grid countries | 6.2 | Phone-side override here; the web half is Phase 2. |
| #6 | Choose a light scenario and white balance preset | 6.4 | Depends on the Phase 0 grey-card result. |
| #8 | Warning when the room is too dark or too bright | 6.3 | Phone-side here; mirrored to the browser in Phase 2. |
| #5 | Be told when I am too close to the lens | 6.5 | |
| #9 | Record audio from the best mic, with a level meter | 6.6 | MVP ships a 5 Hz meter, not 10 Hz (ADR-0002). |
| #13 | Know which codec will be used before recording | 6.7 | Phone readout here; browser readout in Phase 2. |
| #17 | Partial file is playable after a crash or dead battery | 6.7 | Scope is set by the Phase 0 measurement. |
| #14 | Clear capability report per device and lens | 6.10 | Gating rules in ADR-0011. |

## Phase 2 — Web control

**Exit criterion:** a solo creator completes a take from a laptop without touching the phone.

| Issue | Story | PRD | Notes |
|---|---|---|---|
| #3 | See the phone's view in a laptop browser | 6.8 | MJPEG over HTTP (ADR-0008). |
| #4 | Start and stop recording from the laptop | 6.8 | Idempotent commands (ADR-0007). |
| #7 | Recording timer and remaining storage | 6.8 | |
| #10 | Full control from the browser | 6.8 | |
| #11 | Battery, thermal state and free storage | 6.8 | |
| #12 | Changes sync both ways | 6.8 | Revisioned snapshots (ADR-0007). |
| #16 | Recording continues when the browser disconnects | 6.8 | |
| | Browser halves of #8, #13 and #15 | 6.3, 6.7, 6.2 | Those three issues are not fully done until this lands. |

Also in Phase 2: wire the web bundle into `:app` via AGP 9's `androidComponents` Sources API, and
add the `kxstsgen` task that generates `web/src/protocol.ts` (ADR-0009 actions 1 and 3, deferred
from the bootstrap by ADR-0014).

## Phase 3 — Android polish and P1

**Exit criterion:** public Android beta.

Scope: PRD 6.11, plus the CameraX 1.7 revisit (ADR-0002) and, gated behind it,
[docs/spec-chapter-markers.md](spec-chapter-markers.md) CM-1. Play Store submission.

## Phase 4 — iOS

**Exit criterion:** parity with the Android beta.

Scope: port `:capture` to AVFoundation on iOS 16+, implement the server against the existing
protocol spec, reuse `web/dist` byte-identically, and pass the shared fixtures (ADR-0013). Add the
iOS target to `:domain`, at which point `java.*` becomes a compile error in `commonMain` and
`tools/check-domain-platform-free.sh` can be deleted (ADR-0015).

## How a spike finishes

Each Phase 0 issue carries its method, a done-when checklist, and the ADR or PRD text the result
must be written back into. **A spike is not finished when the measurement is taken — it is
finished when the number is recorded in the document that asked for it.** Several PRD and ADR
passages currently contain placeholders ("a length Phase 0 measures") that these issues exist to
replace.

Tracked outside Phase 0: **#27 CameraX 1.7 revisit** (Phase 3), which holds ADR-0002's revisit
checklist. Do not bump CameraX before it; CI enforces the pin.
