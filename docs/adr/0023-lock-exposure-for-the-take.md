# ADR-0023: An opt-in mode that stops the exposure loop entirely for the duration of a take

**Status:** Proposed
**Date:** 2026-09-07
**Deciders:** Davide Mendolia
**PRD sections:** 6.1, 6.3
**Related ADRs:** [ADR-0005](0005-exposure-control-own-metering-loop.md), [ADR-0018](0018-preview-tap-for-metering-and-preview-frames.md), [ADR-0022](0022-two-exposure-responsiveness-modes.md)

## Context

ADR-0022 gave the loop two speeds, and the slower of the two is what runs during a take: a sixth of a
stop per 167 ms, a ±0.15 EV dead band, a five-frame average. That is deliberately slow enough that
exposure movement is not visible on camera, and PRD 6.3's acceptance criteria are written against it.

Slow is not still. Two forces push past ADR-0022 towards actually stopping.

**The promise.** PRD 6.1 opens by describing a locked look, and 6.3's guarantee is
*"settles within 2 seconds and does not oscillate by more than one stop"* — a bound on movement, not
an absence of it. A creator recording a piece to camera under lighting they have already set has no
use for the remaining movement: the loop's job was finished before they pressed record. What it can
still do is respond to something that is not a lighting change at all — a hand passing the key light,
the subject leaning forward, a phone screen waking on the desk — and put a slow ISO drift in the
middle of an otherwise clean file.

**The cost.** ADR-0018 put metering, the browser preview's JPEG encode and the viewfinder's own GL
pass on one thread. Per frame, at 30 fps, the metering half of that thread walks about 32 000 sampled
pixels and takes a natural logarithm of each — roughly a million logarithms a second, on the thread
that also owes the viewfinder a frame every 33 ms. It is the largest single piece of per-frame CPU
work in the app, and every bit of it is discarded during a take that was never going to move.

This force is **directional rather than quantified, and deliberately so** (decision 2026-09-07,
Davide). The mode can only ever remove work: with it off the frame path is what it is today, with it
on the metering is skipped, and the only thing it adds anywhere is a single volatile read per frame.
Its worst case is the current behaviour. There is therefore nothing to measure *before* shipping it —
a saving of unknown size but known sign needs no number to be safe, and ADR-0018 action item 4's
sustained-4K thermal measurement stands on its own account rather than as a gate on this.

Two smaller facts made this cheap to build. `Session` already refuses settings changes while
recording, so a mode chosen before a take cannot change during it; and `ExposureState` already
carries `recording` as its own field (ADR-0022), so "locked" is a derived property rather than new
machinery.

## Decision

We will add **an opt-in mode in which the exposure filter runs no code at all while a take is
running**. At record start, ISO and the shutter rung hold the values they had; `FaceWeightedMeter`
is not called, `ExposureLoop.onFrame` returns immediately, and nothing is pushed to the sensor until
the take ends. The GL pass and the browser preview's JPEG encode are unaffected — they are not the
filter.

It is **off by default** (decision 2026-09-07, Davide). Tracking is what every take has had so far,
and a mode whose failure mode is an unrecoverable file is not one to opt somebody into on their
behalf. It is a stored preference, exposed as `SettingsPatch.lockExposureWhileRecording` and as one
control on each surface — `Exposure: Track / Lock` on the phone, a two-button fieldset in the
browser — and both are disabled during a take like every other setting.

**The two light warnings freeze with everything else** (decision 2026-09-07, Davide).
`TOO_DARK` and `OVEREXPOSED_AT_BASE_ISO` are recomputed per metered frame, so with no frames metered
they hold whatever they said at record start. This is the honest reading of "nothing runs": the
alternative considered was a 1 Hz meter kept alive purely to raise them, which preserves about 97 %
of the saving, and it was rejected as still-something-running. The consequence is stated plainly
below and is covered by a test rather than left to be discovered.

**Ending a locked take clears `acquired`,** so the first metered frame afterwards snaps to what is
actually there. The damped error was measured before the take and a whole take has passed; averaging
against it would crawl to the right answer through a visibly wrong one. This is the same exemption a
cold start already gets, and PRD 6.3's no-oscillation promise is scoped to recording, so it is not
weakened.

**This amends PRD 6.3.** Its acceptance criteria describe a loop that meters throughout a take. They
should be scoped to the default mode with a sentence added for the locked one: *"When exposure is
locked for the take, ISO and shutter do not change for its duration, and no exposure warning is
raised or cleared during it."*

**Also corrected here, because this mode makes it load-bearing:** ADR-0022's damping switch was
driven from `CaptureService`'s one-second status tick, so a take could run for up to a second before
the mode changed. For damping that was cosmetic; for a lock that promises stillness it would put up
to a second of setup-speed ISO movement at the head of the file. The transition moves to the
recorder's own 200 ms edge detector, immediately before `startRecording()`, and a controller built
mid-take is told the current recording state rather than assuming false.

## Options Considered

### Option A: An opt-in mode that stops the filter for the take (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Low: one derived flag, two guards, one additive protocol field, one control per surface |
| Risk | Low by construction, medium in the hands of a user: the failure is a whole ruined take |
| Effort | Hours |
| Reversibility | High: delete the field and the guards and the default behaviour is untouched |

**Pros:** The strongest possible form of PRD 6.1's locked look, and the only one that is a promise
rather than a bound. Removes the app's largest per-frame CPU cost from exactly the moment the thermal
budget is tightest (#23, ADR-0018 action item 4). Costs nothing to anyone who does not turn it on,
because it is off by default and the tracking path is byte-for-byte unchanged.
**Cons:** A light change mid-take is unrecoverable and, with the warnings frozen too, silent — the
creator finds out in the edit. It is a second thing to understand about exposure in a product whose
non-goals say fewer options is the point.

### Option B: Freeze exposure, but keep a 1 Hz meter alive for the warnings

| Dimension | Assessment |
|---|---|
| Complexity | Medium: a second cadence on the tap thread, and a metering path that does not feed the loop |
| Risk | Low |
| Effort | Hours |
| Reversibility | High |

**Pros:** Keeps ~97 % of the thermal saving (one frame in thirty) while still telling the creator
mid-take that the room has changed under them — which is the only moment they can still do anything
about it. The exposure in the file is just as still.
**Cons:** Not what was asked for: something still runs in the filter, and a warning the app raises
while refusing to act on it is a new thing to explain. **Rejected by Davide, 2026-09-07.**

### Option C: Always lock, replacing ADR-0022's recording damping

| Dimension | Assessment |
|---|---|
| Complexity | Lowest: `Damping.RECORDING` and the whole recording branch of the loop disappear |
| Risk | High |
| Effort | Hours |
| Reversibility | Medium: ADR-0022's constants would have to come back |

**Pros:** One behaviour, no control, no setting to explain, and the loop gets smaller rather than
larger.
**Cons:** No opt-out from a failure that costs a whole take. A knocked lamp, a cloud, a door opening
onto a bright hall — each ruins the file with nothing the app can do and nothing it will say. PRD
6.3's acceptance criteria would have to be deleted rather than scoped.

### Option D: Lock automatically once the loop has settled

| Dimension | Assessment |
|---|---|
| Complexity | Medium: a convergence test, and a mode that changes during a take |
| Risk | Medium |
| Effort | Hours |
| Reversibility | High |

**Pros:** No control to explain, no surprise on a cold start, and the thermal saving arrives on its
own for most takes.
**Cons:** Whether exposure can still recover becomes a property of something invisible. ADR-0022
already accepted one invisible behaviour change and noted the cost; two is a product nobody can
predict. It also cannot be what someone chooses deliberately, which is the whole ask here.

## Trade-off Analysis

The real contest is A against B, and it is entirely about the frozen warnings rather than about the
frozen exposure — both options hold ISO equally still, at nearly the same cost.

B's case is that the warnings are the last line of defence: exposure is locked either way, so the
only remaining question is whether the creator learns during the take that the room changed, while
they can still stop and go again, or afterwards in the edit, when they cannot. One metered frame a
second buys that for about 3 % of the work.

A's case, and the reason it was chosen, is that a mode called "locked" whose behaviour is "mostly
locked, with a background process" is a worse thing to reason about than one that does nothing — and
that a warning raised by an app which has explicitly been told not to act is close to noise. The
person who turns this on is someone who has lit their scene and does not want the app's opinion for
the next ten minutes.

That is a defensible call, and it is Davide's; it is recorded here with B's argument intact so that
the first time a locked take comes back dark and silent, the alternative is one line away rather
than a rediscovery.

Against C and D: both remove the user's ability to choose, and this is exactly a choice about how
much risk a particular shoot can carry. A studio with controlled light should lock; a room with a
window should not.

## Consequences

- **Easier:** exposure in the file is genuinely fixed for the whole take, not merely slow. The
  largest per-frame CPU cost in the app disappears during recording, which is the one window where
  #23's thermal budget is under real pressure. ADR-0022's transition lag is fixed on the way past.
- **Harder:**
  - **A light change mid-take is unrecoverable and unannounced.** This is the accepted cost of the
    frozen warnings and the single most likely source of a bad report about this feature. Option B is
    the fix if it happens.
  - **A dropped manual key stops being self-healing.** The runtime request path replaces the whole
    set of options set through it, so today every push re-asserts ISO, shutter, frame duration and
    white balance together — which means that if the sensor ever stops honouring them, the next
    metered frame silently puts them back. A locked take pushes once at record start and never again.

    To be clear about where the fault would lie: a manual request that is set once and then quietly
    stops being honoured, with nothing else touching the camera, is a CameraX or device defect rather
    than anything this mode causes (decision 2026-09-07, Davide). No mechanism is asserted here — in
    particular it is *not* established that starting a `Recorder` on an already-bound `VideoCapture`
    reconfigures the session at all. What is certain is narrower and is the only reason this appears
    under Harder: continuous re-assertion currently **masks** that entire class of failure, and the
    lock removes the mask. The repo has already met one instance of the class — `echoPatience` exists
    because the reference device pinned ISO at the top of its range and never echoed the requested
    value — and that was made survivable rather than argued about.

    No defence is built for it. Capture results keep arriving during a locked take, so `ManualKeyEcho`
    observes it for free; if it is ever seen, re-asserting the same unchanged values on a slow tick
    restores the repair without putting anything back in the filter.
  - Two exposure behaviours to describe, on top of ADR-0022's two speeds that nobody sees. The phone
    control says `Track` / `Lock` and the browser says it in words; neither mentions metering.
  - **A locked take reports the loop's belief, not the sensor's.** During the 2026-09-07 run the
    HUD read ISO 2109 while the sensor was delivering 1879, because the state document is published
    from `ExposureState` and a locked loop stops folding echoes into it. It did not matter for the
    take that was measured -- by record start the two had converged on 1879 -- but a locked take is
    exactly the case where nothing later reconciles them. Worth its own decision rather than a
    silent fix.
  - The size of the thermal saving is unknown, and stays unknown. That is a deliberate gap rather
    than an outstanding task: the mode's worst case is today's behaviour, so the only thing a
    measurement could change is how loudly the saving is advertised. If it turns out to be nil, the
    locked look alone still justifies the mode.
- **Revisit when:**
  - **The first take comes back wrong because the light moved and the app said nothing** — that is
    Option B's trigger, and it should be adopted rather than re-argued.
  - **#23 or ADR-0018 action item 4 reports a tap thread that is comfortable under a sustained 4K
    encode even while metering.** That would not undo this mode — the locked look is its own
    argument — but it would remove the second of the two forces above, and the default is worth
    reopening only if the first one has also weakened.

## Action Items

1. [ ] Amend PRD 6.3 with the locked-mode sentence under Decision, once this ADR is Accepted.
2. [x] **Measured 2026-09-07 on the Pixel 10** (`frankel`, Android 17, SDK 37, build
       `CP2A.260805.005`; wide lens, 24 mm), under constant light. **The sensor holds a manual
       request that is never re-asserted, for a full take.** The take was a real 3840x2160 at
       29.99 fps (`90000/3001`), 3127 frames, 104.27 s, H.264 + AAC, 475 MB. Across the locked
       window, 104 samples of the capture results at 1 Hz -- one result in thirty, so roughly
       3 100 results in all:

       | Key | Requested | Reported | Deviation | Tolerance | Verdict |
       |---|---|---|---|---|---|
       | `SENSOR_SENSITIVITY` | 1879 | 1879 | 0.0000 % | 1 % | **EXACT on every sample** |
       | `SENSOR_EXPOSURE_TIME` | 20 000 000 ns | 19 995 066 ns | 0.0247 % | 1 % | QUANTISED |
       | `SENSOR_FRAME_DURATION` | 33 333 333 ns | 33 340 454 ns | 0.0214 % | 0.1 % | QUANTISED (29.994 fps) |
       | `CONTROL_AWB_MODE` | 5 (`DAYLIGHT`, the locked preset for 5600 K under ADR-0011) | 5 | -- | exact | HONOURED |

       Not one value moved for the length of the take, and `awaitingEcho` was false throughout, so
       the loop was settled rather than stalled. The Harder bullet above stands as a **risk that did
       not materialise on this device**: no defence is needed, and none was built. Resume was
       checked too -- the take stopped at 09:36:32.004 and the first capture result after it already
       carried a new request, which is the `acquired` snap, inside one second.

       Two limits on this result. It is one lens on one handset (ADR-0017), and the probe read four
       of `ManualKey`'s six keys -- `CONTROL_AE_MODE` and `LENS_OPTICAL_STABILIZATION_MODE` were not
       covered, so ADR-0018 action item 3 is advanced rather than closed. The measurement used a
       temporary log line in `ExposureController.onCaptureResult` which is **not** part of this
       change; the binary differed from the shipped one by that statement alone.
3. [ ] Decide, after a session of real use, whether the frozen warnings need Option B after all.
