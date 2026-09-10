# ADR-0022: Two exposure responsiveness modes — quick while lighting, damped while recording

**Status:** Proposed
**Date:** 2026-09-06
**Deciders:** Davide Mendolia
**PRD sections:** 6.1, 6.3
**Related ADRs:** [ADR-0005](0005-exposure-control-own-metering-loop.md), [ADR-0021](0021-remove-too-bright-and-bump-the-protocol.md)

## Context

ADR-0005 gave the app one exposure loop with one set of damping constants, tuned for the thing that matters most: not moving visibly during a take. A sixth of a stop per 167 ms, a ±0.15 EV dead band, a five-frame average. PRD 6.3's acceptance criteria are written against exactly that behaviour.

Those constants are wrong for the other half of the session. Before recording, a creator is *lighting the scene* — moving a lamp, closing a blind, turning a key light down — and every one of those is a question with the preview as its answer. At one stop per second a two-stop change takes two and a half seconds to read back, which turns "is this better?" into a conversation nobody can have. The loop is behaving exactly as designed and it is unusable for the task.

The manual ISO lock (#51, briefly shipped in #81) was an attempt to give the user control of this, and it solved the wrong problem: it let someone freeze a value the loop could have found on its own, while doing nothing about how long the loop took to find it.

PRD 6.3's own wording contains the answer. Its criterion reads *"Given a constant scene, **when recording**, then ISO settles within 2 seconds and does not oscillate by more than one stop."* The guarantee is already scoped to a take. Nothing in the PRD asks the loop to be slow while nobody is recording.

## Decision

> **Scope, from ADR-0033 (accepted 2026-09-10).** These damping profiles belong to the app's own
> metering loop, which ADR-0033 makes rung 2: cameras that do not declare
> `SENSOR_EXPOSURE_TIME_PRIORITY`, and every camera on iOS. On rung 1 the platform's auto-exposure
> converges at its own rate and neither profile applies. On the reference Pixel 10 every camera is
> rung 2 (measured 2026-09-09), so nothing below changes on current hardware.

We will run the same loop with **two sets of damping constants**, selected by whether a take is running:

| | Setup (idle) | Recording |
|---|---|---|
| Dead band | 0.10 EV | 0.15 EV |
| Max slew | 4 stops/s | 1 stop/s |
| Step interval | 42 ms | 167 ms |
| EMA window | 3 frames | 5 frames |
| Settles a 2-stop change in | ~0.6 s | ~2.5 s |

Only the damping differs. **A mode is a speed limit, not a different opinion about correct exposure** — the same 18 % grey target (0.45 encoded), the same face-weighted geometric mean, the same sixth-of-a-stop step, the same warnings. Both modes settle on the same ISO; they differ only in how long they take to get there, and a test asserts that.

The switch is driven from the state document's `recording` flag, so a take started from the browser changes the mode exactly as one started on the phone does. Nothing is pushed to the sensor at the transition: by the time anyone presses record the loop has settled, so the new damping has nothing to undo.

`Damping` is a type of its own rather than three loose fields, so the two modes cannot drift apart in shape, and `ExposureState` carries the flag so the loop stays a pure function of its own state — replayable frame by frame in a test with the mode switching part-way through.

**This amends PRD 6.3.** The line *"ISO manual lock available (phone and web) for users who want a fixed value"* is replaced by the two modes; the lock was removed in #87 before it reached a release.

## Options Considered

### Option A: Two damping profiles, switched on `recording` (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Low: one extra type, one flag, no new control |
| Risk | Low: the recording mode is byte-for-byte ADR-0005's behaviour |
| Effort | Hours |
| Reversibility | High; delete the setup profile and the flag |

**Pros:** No user-visible control to learn, explain or get wrong. The fast mode exists exactly when there is no take to spoil, and PRD 6.3's guarantee is untouched because it was already scoped to recording. Both halves of the session get the behaviour they need.
**Cons:** Behaviour changes under the user without a visible cause; someone who notices the preview settling differently before and during a take has nothing in the UI explaining it.

### Option B: Manual ISO lock (what #81 shipped, and #87 removed)

| Dimension | Assessment |
|---|---|
| Complexity | Medium: protocol field, validation, a control on two surfaces |
| Risk | Medium |
| Effort | Days, most of it UI |
| Reversibility | Low once the field is public |

**Pros:** Absolute certainty — the value cannot move at all.
**Cons:** Solves the wrong problem. It controls *where* ISO sits, not how quickly the loop gets there, so it does nothing for someone lighting a scene; it needs a control on both surfaces; and a user who forgets to release it records a take at a fixed ISO in a room whose light has changed. PRD's own non-goals say fewer options is the product.

### Option C: One faster set of constants for both modes

**Pros:** Simplest possible — one profile, no flag.
**Cons:** Fails PRD 6.3 directly. Four stops per second is visible pumping on camera, which is the exact failure ADR-0005's damping exists to prevent, and 6.1 promises a locked look for the whole take.

### Option D: A user-facing "responsive / smooth" toggle

**Pros:** Explicit; the user is never surprised.
**Cons:** Asks the user to understand a control loop to answer a question the app already knows the answer to — whether a take is running. A setting that the recording state can always infer is a setting that should not exist.

## Trade-off Analysis

B is what was tried and it aimed at the wrong variable. C is disallowed by an acceptance criterion. D turns an inference into a question.

A's real cost is the one named in its Cons: the loop behaves differently before and during a take, with nothing on screen to say so. That is judged acceptable because the difference appears exactly where the user's attention is elsewhere — while lighting they are watching the light, and while recording they are watching themselves — and because the alternative is a control that exists only to explain an implementation detail. If it turns out to confuse people, UI-5's readout strip is where it becomes visible, not the settings.

## Consequences

- Easier: lighting a scene is interactive; the preview answers within a second of moving a lamp. PRD 6.3's recording guarantee is untouched and still tested.
- Harder: two sets of constants for Phase 0 #25 to tune rather than one, and the setup mode's numbers have no acceptance criterion to check them against — they are judged by feel until #25 measures them.
- The mode is not in the protocol. A browser can infer it from `recording`, and adding a field for it would be recording state under a second name.
- Revisit when: #25 measures the loop against a real step change in light, or a user reports the preview behaving differently before and during a take without knowing why.

## Action Items

1. [ ] Phase 0 #25: measure settle time and overshoot for **both** modes, and record the tuned constants in `Damping.SETUP` and `Damping.RECORDING`.
2. [ ] Amend PRD 6.3 to replace the manual ISO lock sentence with the two modes.
3. [ ] Decide, once someone has used it for a session, whether the mode needs to be visible in UI-5's readout strip.
