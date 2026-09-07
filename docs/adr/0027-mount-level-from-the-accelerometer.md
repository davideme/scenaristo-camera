# ADR-0027: Read the accelerometer to report mount level and steadiness, and only while a take is not running

**Status:** Proposed
**Date:** 2026-09-07
**Deciders:** Davide Mendolia
**PRD sections:** 6.1, 6.8, 6.11
**Related ADRs:** [0023](0023-lock-exposure-for-the-take.md), [0025](0025-camera-standby-when-nothing-is-watching.md), [0024](0024-guard-settings-on-a-settings-revision.md), [0018](0018-preview-tap-for-metering-and-preview-frames.md), [0013](0013-multiplatform-strategy.md)

## Context

PRD 6.1 turns both stabilisers off, and says why: *"Stabilisation | Off, both EIS
and OIS | Phone is on a tripod; EIS crops and can wobble, OIS drifts."* That is
the right decision and it has an unstated premise — the phone really is on a
tripod, and the tripod really is level and really is still. The product never
checks the premise it depends on, and offers nothing to help anyone satisfy it.

The failure is asymmetric, which is what makes it worth code. A crooked horizon
costs nothing to fix in the ten seconds before a take and cannot be fixed after
one without cropping into the frame — and it is exactly the kind of small error
that is invisible on a phone screen at arm's length and obvious on a 27-inch
monitor in the edit. The same is true of a mount that is transmitting the desk it
is clamped to.

The reference Pixel 10 makes the case concretely. Measured on its own mount on
2026-09-07 by dumping `sensorservice` over `adb`: perfectly steady — 0.14° rms
angular deviation, which is the sensor's own noise floor — but **0.6° off level**
and aimed **5.2° above horizontal**. Nothing in the app knew either fact.

Three forces push back, and they set the shape of the decision rather than
opposing it:

- **ADR-0023 says nothing runs during a take.** It rejected even a 1 Hz meter
  kept alive to raise two light warnings as *"still-something-running"*, having
  removed the app's largest per-frame CPU cost from the moment #23's thermal
  budget is under most pressure. A motion sensor running through a locked take is
  the same argument with a different sensor.
- **ADR-0025 says nothing runs while nothing is watching.** Its correction of the
  earlier thermal framing applies here word for word: the case is *"battery and
  held hardware, not heat"*, on *"a device the user believes is doing nothing"*.
- **ADR-0024 says a fast-moving reported field is a protocol problem.** The
  histogram advanced `rev` 27 times a second and broke `expectRev` for every
  client. A tilt angle read at 50 Hz is the same shape of value.

There is no PRD requirement to implement here. This is new product surface, so
6.11 gains an entry rather than 6.1 gaining a criterion.

## Decision

We will read the phone's **raw accelerometer** at 50 Hz, in `:capture`, and turn
it into a level and steadiness reading with a pure, host-tested filter in
`:domain`; the reading is published on the existing one-second status tick as a
reported-only `MountAttitude` on the state document, and drawn by the browser as
an optional horizon overlay on the preview. **The listener is registered only
while the camera is bound and a take is not running** — it is unregistered on the
recording edge in `followRecordingState`, beside the exposure mode switch
ADR-0022 put there, so the sensor is already gone before the file's first frame.
While it is not measuring, the reading says so (`measuring = false`) rather than
holding its last angle, which is the same refusal `AudioState.metering` and
`ExposureReadout.metering` make.

This amends no PRD statement. It adds one bullet to **6.11 (P1)** and one entry
to the UI spec (UI-23). It deliberately does not touch 6.1's stabilisation row:
this is the affordance that makes that row's premise checkable, not a challenge
to it.

## Options Considered

### Option A: Raw accelerometer, setup only (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Low. One `SensorEventListener`, one pure filter, one reported field, one overlay. |
| Risk | Low. Nothing runs during a take, so the take cannot be affected by it. The residual risk is a sign error in the axis conversion, which is a wrong instruction rather than a lost file. |
| Effort | Small. No new dependency: `SensorManager` is framework. |
| Reversibility | High. Deleting the listener leaves a defaulted protocol field that decodes as "not measuring". |

**Pros:** costs nothing at the moment the thermal and battery budgets are
tightest, because it is not running then; consistent with ADR-0023 and ADR-0025
rather than arguing with either; the raw stream answers both questions at once,
since vibration is the part of the signal an angle filter would discard.
**Cons:** a tripod knocked at minute six of a ten-minute take is not reported,
which is the single failure the feature would most like to catch. The operator
gets a level phone at the start of the take and a promise about nothing after
that.

### Option B: Fused sensor (`TYPE_GRAVITY` / `TYPE_ROTATION_VECTOR`), running through the take

| Dimension | Assessment |
|---|---|
| Complexity | Low for the angle; the steadiness half needs a second, raw stream anyway. |
| Risk | Medium. It puts a running sensor and a live protocol field inside a take that ADR-0023 promised would be quiet, and the promise is load-bearing for the thermal argument. |
| Effort | Small, plus the ADR-0023 amendment and a drain measurement to justify it. |
| Reversibility | High technically, low socially: a mid-take warning is hard to withdraw once someone has relied on it. |

**Pros:** catches the bumped tripod, which is the expensive failure; a fused
sensor is already computed by the hub for other clients, so the marginal cost is
small. **Cons:** the fusion is a low-pass filter, so it removes exactly the
vibration the steadiness reading is looking for and would report a shaking
tripod as steady; and it reopens a decision ADR-0023 made deliberately, on a
weaker case than ADR-0023's own — it would be *adding* something to a take
rather than removing it.

### Option C: Measure the tilt optically, from the existing preview tap

| Dimension | Assessment |
|---|---|
| Complexity | High. Line detection on a downscaled frame, with no ground truth. |
| Risk | High. It is wrong exactly when it matters: a talking head against a plain wall has no horizon in it. |
| Effort | Large. |
| Reversibility | Medium. |

**Pros:** no new platform API family, so no new ADR trigger; works on a device
with no accelerometer. **Cons:** costs per-frame CPU on the ADR-0018 tap, which
is the cost ADR-0023 spent a whole ADR removing; and it measures the *scene*
rather than the phone, so a level phone in front of a crooked bookshelf reads as
crooked. A level aid that is confidently wrong is worse than none.

## Trade-off Analysis

B is the option that catches the failure everybody actually fears, and it loses
anyway — twice. Its own mechanism is wrong for half the feature: a fused gravity
vector is a low-pass filter, and steadiness lives in precisely the band it
removes, so the option that sounds like it does more would silently do less.
And the part that is genuinely better about it — a warning during the take —
cannot be bought without reopening ADR-0023 on a weaker case than ADR-0023
itself made. That ADR turned down a 1 Hz meter that would have preserved 97 % of
its saving, on the principle that a locked take is quiet. Arriving straight
afterwards with a 50 Hz sensor and a live protocol field would be asking for the
exception rather than making the argument.

C loses on truth rather than on cost. Every other measurement in this product is
of the equipment — shutter, ISO, focal length, level — and each is right
regardless of what is in front of the lens. An optical horizon is a measurement
of the room, and rooms lie.

So A: take the honest, cheap measurement at the only moment the product is
allowed to take it, and be explicit on both surfaces that it stops. What A gives
up is real and is stated in Consequences rather than hidden — and the trigger for
revisiting it is written down.

## Consequences

- **Easier:** the tripod premise PRD 6.1 depends on is now checkable in ten
  seconds, from the laptop, without walking to the phone. The thresholds and the
  geometry are one pure function in `:domain`, so iOS reaches the same answers
  from `CMDeviceMotion` in Phase 4 (ADR-0013) without a second opinion about what
  "level" means. A device with no accelerometer degrades to the same reading a
  take produces, which the interface already draws.
- **Harder:** there are now two reasons a reading says "not measuring" — a take
  is running, or the camera is asleep — and the interface has to distinguish
  them, because the first is normal and the second is nothing at all. Anyone
  adding a state field must now also keep it out of `CaptureSettings.settable`
  and out of `rev`'s way; `MountFilter`'s deadband is the only thing standing
  between a 50 Hz sensor and ADR-0024's failure mode.
- **Harder:** the axis conversion consumes the *display's* rotation, so it is
  coupled to `applyDisplayRotation`. A future portrait mode (PRD 6.11) changes
  what "level" means and this has to be re-checked on a device, not reasoned
  about.
- **Revisit when:** ADR-0025's action item 6 measures 30-minute backgrounded idle
  drain — that measurement should be taken with the sensor registered and gives
  the first real number for what setup-time levelling costs. Or sooner, on the
  first take lost to a mount that moved during it: that is the evidence that
  would justify reopening Option B, and it should be recorded against this ADR
  when it happens rather than argued from first principles again.

## Verification

The geometry is host-tested against a real reading rather than against its own
algebra: `MountLevelTest` asserts the reference Pixel 10's measured sample
`(9.7373, −0.0970, −0.8836)` at `ROTATION_90` produces −0.57° of roll and +5.19°
of pitch, which is what a person standing next to the phone could see. Every
plausible wrong version of the conversion — a swapped axis pair, a sign flip, a
rotation applied backwards — produces a number of the right *size*, so a test
written from the same algebra as the code would pass on all of them.

On the device: the accelerometer's client count in
`adb shell dumpsys sensorservice` must drop by one for the duration of a take and
recover when it stops. That is the check that this ADR's central promise is real
rather than merely intended.

## Action Items

1. [ ] Davide to decide the status, and whether 6.11 is the right home for a
   feature that ships rather than a feature that is deferred.
2. [ ] Confirm on the reference Pixel 10 that the overlay's tilt direction
   matches a physical tilt, mirrored and unmirrored — the one failure the host
   tests cannot catch.
3. [ ] Take the sensor-registered idle drain figure as part of ADR-0025's action
   item 6, and record it here.
4. [ ] Re-check the axis conversion on a device if portrait (PRD 6.11) is ever
   built.
