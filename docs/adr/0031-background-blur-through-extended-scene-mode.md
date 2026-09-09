# ADR-0031: Reach background blur through the streaming scene mode, not through camera extensions, and measure it before offering it

**Status:** Proposed
**Date:** 2026-09-09
**Deciders:** Davide Mendolia
**PRD sections:** 3 (Non-Goals), 6.1, 6.10, 6.11
**Related ADRs:** [ADR-0002](0002-android-capture-stack.md), [ADR-0011](0011-per-lens-capability-gating.md), [ADR-0013](0013-multiplatform-strategy.md), [ADR-0017](0017-phase-0-verification-matrix.md), [ADR-0018](0018-preview-tap-for-metering-and-preview-frames.md), [ADR-0030](0030-studio-look-at-1080p-with-ml-kit.md)

## Outcome: the mode does nothing on the reference device, 2026-09-09

**The Pixel 10 advertises `BOKEH_CONTINUOUS`, accepts the request, echoes the mode back on every
capture result, and applies no blur at all.** Measured against a face at both 3840×2160 and
1920×1080 — the latter being exactly the size the device advertises the mode for — with background
sharpness unchanged to within noise against a control take.

There is no feature to offer here, so no toggle is proposed and `blurWhileRecording` stays false. The
route in this ADR is still the right one — vendor extensions genuinely cannot reach a recording, and
this is the only other door — but the door does not open on this handset.

**The part worth keeping is the way it failed.** Everywhere else in this repository an echoed key is
the end of the argument: ADR-0002 action item 2 verifies the manual keys precisely by asking whether
the camera echoed them, across 18,027 capture results. This is the case where the echo is a lie. It is
ADR-0018's *"every capability query on this device is optimistic"* one level deeper than that ADR
found it — not the capability query, the capture result. The defence is
`BlurCapability.appliesToFootage`, which no characteristic and no capture result can set: only two
recordings compared against each other.

**An earlier draft of this ADR said the opposite, and it was wrong.** The first run was taken against
a blank wall and `blurdetect` read 7.99 against 8.80, which was written up as blur at UHD and acted on
— Davide decided the vendor's ceiling should not be trusted over that measurement. Against a face the
same comparison gives 9.14 against 9.29 on the background and 4.09 against 4.06 on the subject, which
is noise. On a flat wall with continuous AF the earlier delta was almost certainly focus. **A
low-detail scene cannot support this measurement**, and the conclusion drawn from one has been
withdrawn along with the code that implemented it.

## Context

The request was "if the camera extension Bokeh is available, offer it, in the UI and the remote
control". Three things stand between that sentence and a feature.

**The extensions route cannot reach a recording.** Android's documentation is explicit: *"Camera2 and
CameraX extensions are only available for the preview and image capture use cases, not video
capture."* CameraX's own extensions guide describes the mode as applied "on the image capture and
preview use cases". This app has no `ImageCapture` at all — ADR-0002 says so in as many words, and
relies on it: *"We never use `ImageCapture`, exposure compensation, torch, or AE-affecting focus
actions, so CameraX's own 3A has no reason to overwrite the keys."* Adding
`androidx.camera:camera-extensions` would buy a blurred viewfinder over an unblurred take, at the cost
of a new dependency and, on the evidence of that sentence, the manual keys as well.

**There is a second route, and it was built for streams rather than stills.** Camera2 carries
`CONTROL_EXTENDED_SCENE_MODE`, whose `BOKEH_CONTINUOUS` value AOSP describes as continuous video
streaming with bokeh applied in real time, as against `BOKEH_STILL_CAPTURE` for single frames. It is a
capture-request key, which means it rides the path ADR-0002 already confines to `ManualControls`, and
its availability is advertised through one characteristic — verified against `android-36` rather than
assumed, because the two key names AOSP's prose uses are `@hide`:

```
CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES : Key<Capability[]>
android.hardware.camera2.params.Capability
    int getMode();  Size getMaxStreamingSize();  Range<Float> getZoomRatioRange();
```

The third accessor is worth noticing: availability is advertised **per zoom band**, which the prose
does not mention and which #77 makes relevant, since a user may sit anywhere in the zoom range.

**ADR-0030 parked the adjacent feature on 2026-09-08, and named the condition for unparking it:**

> Unparking this means **either a depth or matte source better than a 640×480 mask**, or a CameraX
> release that changes what can bind beside a UHD recording (#27, #62). Neither is a shader problem.

A HAL bokeh is that better source, and it is cheaper than the thing that was parked rather than dearer:
the vendor blurs from depth data the app cannot otherwise reach, with no ML Kit, no `ImageAnalysis`, no
shader, and none of the 12 MP-per-frame render #139 measured. That is the argument for looking at all.

**Three things are unmeasured, and each can end the feature.** Whether the reference Pixel 10
advertises the mode. What `getMaxStreamingSize()` it advertises for it — commonly below UHD, which
collides with `GroupableFeatures.UHD_RECORDING` and with PRD §3's Non-Goal, *"resolutions other than
4K UHD… Lower fallbacks exist only for devices that cannot do 4K/30"*. And whether the six manual keys
survive the mode: AOSP says the mode is selected by setting `CONTROL_MODE` to the
extended-scene-mode value, and that control's sibling `USE_SCENE_MODE` is documented to make
`CONTROL_AE_MODE` *ignored*, while `SENSOR_EXPOSURE_TIME` and `SENSOR_SENSITIVITY` are honoured only
when AE is off. If that is how it behaves, blur costs PRD 6.1–6.3, which is the product.

ADR-0018 is the standing warning against answering any of this from characteristics:

> Capability reporting (PRD 6.10, ADR-0011) cannot be built from `getSupportedQualities`,
> `getSupportedFrameRateRanges` or `isSessionConfigSupported`, all three of which were measured lying
> about this session. The probe must bind and read `resolutionInfo` back.

Building the toggle and finding out afterwards is exactly what ADR-0030 did.

## Decision

We will reach background blur through Camera2's `CONTROL_EXTENDED_SCENE_MODE`, carried on
`ManualControls.Request` so it is re-stated on every runtime request, and **we will not add
`androidx.camera:camera-extensions`**. CameraX stays pinned at 1.6.2 and no dependency is added.

We will **measure before offering**. This change ships the instrument, the gating rule and the wire
field, and offers nothing: `Capabilities.blurWhileRecording` is additive, defaulted false, and gated
behind a `BLUR_VERIFIED` constant that is false until this ADR carries a measurement. The toggle —
a `SettingsPatch` field, a phone control and a browser panel — is a separate change, written only if
the numbers below justify one.

Three rules decide whether blur may ever be offered, and they live in `:domain` as a pure function
(`blurVerdict`) because ADR-0011 requires gating to be *"a pure function from the capability report to
the allowed control set"* and ADR-0013 makes that what Phase 4 inherits:

1. **A still-capture bokeh is not this feature.** Without `BOKEH_CONTINUOUS` there is nothing to apply
   to a take, and the app has no photograph to blur.
2. **Manual exposure wins.** If the six keys stop echoing while the mode is active, blur reports
   unsupported and no toggle appears — decided by Davide on 2026-09-09. A flicker-free locked shutter
   is why this app exists; a blurred background is not.
3. **The footage has to change.** `appliesToFootage` is the last check and the one the reference
   device fails: advertised, accepted and echoed is not support, and the capability requires a
   recording with the mode compared against one without it. Nothing here gates on the advertised
   ceiling — not because the ceiling was disproved (it was not; nothing blurs at any size on this
   device) but because it is redundant beside a check on the picture itself. The advertised ceiling
   was reported for PRD 6.10 and gated nothing.

**No PRD amendment is proposed**, and on this device the question does not arise: §3's Non-Goal on
resolutions and §6.1's resolution row are untouched, because there is no effect to trade a resolution
for. If a device is ever found that genuinely applies the mode, the resolution question comes back and
is Davide's then.

## Measurement, Pixel 10, 2026-09-09

Reference device: **Pixel 10** (`frankel`), **Android 17, API 37**, build
`google/frankel/frankel:17/CP2A.260805.005/15828068`. Back logical camera, id `0` — the one
`CameraSelector.DEFAULT_BACK_CAMERA` binds. Per ADR-0017 this is evidence about one Pixel 10, and a
Pixel is the most permissive device in the fleet.

### What the camera advertises

| Advertised mode | Max streaming size | Zoom band |
|---|---|---|
| `DISABLED` (0) | 0×0 | 0.56×–20.0× |
| `BOKEH_CONTINUOUS` (2) | **1920×1080** | **1.0×–3.0×** |

`BOKEH_STILL_CAPTURE` is **not** advertised, which is convenient: the one mode on offer is the one
that can reach a recording. Raw, from `dumpsys media.camera` on camera 0:
`availableExtendedSceneModeMaxSizes = [0 0 0, 2 1920 1080]`,
`availableExtendedSceneModeZoomRatioRanges = [1.0 3.0]`.

**`CONTROL_AVAILABLE_MODES = [0 1 2]` — `USE_EXTENDED_SCENE_MODE` (4) is not offered.** The route
AOSP's prose describes does not exist on this device, and asking for it is a no-op: candidates C2 and
C4 requested `CONTROL_MODE = 4` and the camera reported `CONTROL_MODE = 1` (AUTO) on every frame,
while the scene mode engaged anyway. **The scene-mode key alone is the route**, and making that
control a measured variable rather than a fixed constant of the route is the reason this ADR has an
answer instead of a wrongly-concluded "no".

### What it does

Nine candidates, `VideoCapture` alone except C8, ~5 s recorded each, 30 fps requested.

| Candidate | Asked for | Bound at | Frames | Measured fps | Manual keys |
|---|---|---|---|---|---|
| C0 UHD30, no blur (control) | 3840×2160 | 3840×2160 | 150 | 30.00 | held |
| C1 UHD30 + blur | 3840×2160 | 3840×2160 | 150 | 30.05 | held |
| C2 UHD30 + blur + scene control | 3840×2160 | 3840×2160 | 151 | 30.03 | held |
| C3 FHD30 + blur | 1920×1080 | 1920×1080 | 150 | 29.96 | held |
| C4 FHD30 + blur + scene control | 1920×1080 | 1920×1080 | 150 | 30.01 | held |
| C5 unconstrained + blur | device choice | 1920×1080 | 150 | 29.94 | held |
| C6 blur at bind, dropped by a runtime request | 1920×1080 | 1920×1080 | 241 | 30.03 | held |
| C7 blur carried on every runtime request | 1920×1080 | 1920×1080 | 330 | 29.99 | held |
| C8 preview + recording + blur | 1920×1080 | 1920×1080 | 150 | 30.03 | held |

**Blur costs neither the manual keys nor the frame rate.** Every candidate honoured all six keys —
`SENSOR_SENSITIVITY`, `CONTROL_AE_MODE`, `CONTROL_AWB_MODE` and `LENS_OPTICAL_STABILIZATION_MODE`
exact; `SENSOR_EXPOSURE_TIME` at −247 ppm and `SENSOR_FRAME_DURATION` at +214 ppm, both inside
`ManualKey`'s tolerances and both identical to the no-blur control. The rule Davide set on
2026-09-09 — manual exposure wins, and blur is refused if it costs the keys — **is not triggered on
this device.**

**A key set at bind time survives a runtime request that omits it.** C6 asked for the mode at bind,
then made a runtime request without it, and the camera stayed in mode 2 for all 91 following results
(`PERSISTED`). C7 re-asserted it and it held. This *disproves* the assumption this change was first
written on — that the runtime path replacing its own option set would wipe the mode — and the code
comments that asserted it have been corrected rather than left as folklore.

### Is the footage actually blurred?

An echoed mode proves the camera *selected* blur, not that anything is blurred, so every candidate
left a file. On the same background region of the UHD pair, `ffmpeg blurdetect` gives a blur mean of
**7.99 for C0 (no blur)** against **8.80 for C1 (blur)** — measurably softer, and visible frame to
frame as the ceiling-to-wall junction losing its edge while the nearer monitor bezel keeps its own.

**So the device applies blur at 3840×2160, above the 1920×1080 ceiling it advertises.** That is the
opposite of ADR-0018's usual finding: here the device's own claim is *pessimistic* rather than
optimistic. It should not be trusted on that basis alone — the vendor's advertised ceiling is the
vendor's own statement of where the effect is supported, and "something is applied at UHD" is not
"the effect is supported at UHD".

**And the scene was a blank wall.** The phone was where it was sitting: a wall, a ceiling junction and
the corner of a monitor. That is enough to show the mode does something and nothing at all about how
it treats a face at a metre with a room behind it — which is the only scene this product cares about.
The quality question is open and needs the camera pointed at a person.

### Second run, against a face — 2026-09-09

The run above was taken against a blank wall. Repeated with a subject at roughly a metre, a room
behind, at 1× zoom (inside the 1.0×–3.0× band), exposure seeded from the app's own metered values
rather than the loop's starting point so the face is correctly exposed:

| Region | C0, no blur | C1, blur (3840×2160) | Delta |
|---|---|---|---|
| Background (door, wall, ceiling) | 9.14 | 9.29 | +1.6 % |
| Subject's face | 4.09 | 4.06 | −0.6 % |
| Whole frame | 5.43 | 5.33 | −1.8 % |

And at the size the device actually advertises, against the control downscaled to match:

| Region | C0, no blur (downscaled) | C3, blur (1920×1080) |
|---|---|---|
| Background | 6.45 | **6.40** |

**Nothing is blurred, at either resolution.** The background delta is smaller than the frame-to-frame
variation, and the whole frame reads marginally *sharper* with the mode on. Confirmed by eye on both
pairs: door mouldings, mirror frame, recessed ceiling lights and the wall-to-ceiling line are equally
crisp with and without.

**What little difference there is between the takes is a lighting change, not an optical one** (Davide,
looking at the pair). The candidates run seconds apart with the sensor pinned — AE off, fixed shutter
and ISO — so the exposure does not move, but the room does: daylight through a window shifts the
contrast of a scene between one take and the next, and edge-energy metrics read contrast as much as
they read focus. That is the trap the wall run fell into, and it is the reason a metric like
`blurdetect` is only usable here on a scene with real depth separation and enough detail that a few
percent cannot be bought by the light changing. Where the two effects are hard to tell apart, the
answer is a better scene, not a better statistic.

Meanwhile the capture results said mode 2 on all 150 frames of each take. **The mode was selected and
never applied.**

The only remaining lead on this handset is a private vendor tag, `videoBokehBlurLevel` in
`com.google.pixel.experimental2023`, which appears in the camera service's tag registry. That is
undocumented, unsupported, Pixel-only and outside anything ADR-0002 would sanction; it is recorded
here as an observation, not a proposal.

## Options Considered

### Option A: CameraX Extensions (`ExtensionMode.BOKEH`)

| Dimension | Assessment |
|---|---|
| Complexity | Low to write, and it does not work |
| Risk | Certain failure: extensions do not apply to `VideoCapture` |
| Effort | Low |
| Reversibility | High |

**Pros:** The documented, most-supported API; one `ExtensionsManager` call to check availability; the
vendor's own tuned implementation.
**Cons:** Rejected on a documented fact rather than a judgement — *"only available for the preview and
image capture use cases, not video capture"*. It also needs an `ImageCapture` this app does not have
and ADR-0002 relies on not having, adds a dependency at a version CI pins, and takes over the 3A the
manual keys depend on.

### Option B: Camera2 `CONTROL_EXTENDED_SCENE_MODE = BOKEH_CONTINUOUS` (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Low — two nullable fields on an existing request type |
| Risk | Medium, and *measurable*: availability, size ceiling, and the cost to the manual keys |
| Effort | Medium: the instrument is most of it |
| Reversibility | High — the keys are unset by default, so not asking for blur is the current behaviour exactly |

**Pros:** Designed for streaming rather than stills. No dependency, no CameraX bump, no `ImageCapture`.
Rides the confined interop path, so ADR-0002's rule is kept rather than bent. Composes with the
existing `SessionConfig` and the ADR-0018 preview tap. The vendor supplies the matte, which is
precisely what ADR-0030 said was missing.
**Cons:** Device support is not guaranteed and may well be absent on the reference Pixel 10. The
control value AOSP names for selecting it may cost the manual keys. The advertised ceiling may be
below UHD.

### Option C: A segmentation shader in the ADR-0018 tap

| Dimension | Assessment |
|---|---|
| Complexity | High |
| Risk | Known — it was built |
| Effort | High |
| Reversibility | Medium |

**Pros:** Works on every device, needs nothing from the vendor.
**Cons:** This is ADR-0030, and it was parked after being looked at: a 640×480 mask composited into a
1080p frame *"aliases into a staircase"* thresholded hard and *"stops separating"* blurred soft. Doing
it again for blur rather than for relighting would reach the same wall by the same route.

### Option D: No background blur

| Dimension | Assessment |
|---|---|
| Complexity | None |
| Risk | None |
| Effort | None |
| Reversibility | — |

**Pros:** The product is a locked-down talking-head recorder and blur is not in the PRD.
**Cons:** Gives up a feature the hardware may simply have, for free, at no cost to the picture — and
leaves PRD 6.10's report unable to say anything true about it, which #125 needs.

## Trade-off Analysis

Option A loses on a fact, not a preference: it cannot apply to the thing this app records. Option C is
the strongest *alternative*, because it works everywhere, and it is exactly the option that was tried
and parked eight days into this month — its wall is the quality of the matte, and no amount of effort
in this repository moves it. Option D is the honest default and remains the outcome if the measurement
disappoints.

Option B wins because it is the only route whose failure modes are *findable in advance and cheaply*.
Every one of its risks is a number the instrument in this change produces in about ninety seconds on a
phone, and if any of the three comes back wrong the answer is Option D with a table explaining why —
which is a better outcome than today, because PRD 6.10's report and #125's "Not supported on this
lens" label then have something true to say. Against ADR-0030's cost profile it is also the cheap
direction: no model, no analyser stream, no shader on a 12 MP surface, and the mode either exists in
the HAL or does not.

The decisive constraint is the one Davide set: manual exposure wins. That turns what would otherwise
be a tempting trade — a nicer-looking background against a slightly less locked camera — into a
straightforward gate, and it is why the verdict is a function with an ordered set of refusals rather
than a boolean flag.

## Consequences

- **Easier:** PRD 6.10's capability report can finally say something about blur, true either way.
  ADR-0011's gating gains a rule that lives where ADR-0011 says gating should live, in `:domain`,
  which is a small step back from the pre-existing `LensGate`-in-`:capture` deviation. And the
  `Request` type now carries per-request mode keys, which is the shape any future capture-request
  setting needs.
- **Harder:** `ManualControls.Request` has two more fields, and anything that constructs one has to
  understand that a key not carried on every runtime request is a key that lasts until the next
  exposure move. The probe unbinds the session while it runs, so the browser preview drops for its
  duration — the same cost the lens sweep already has.
- **Unchanged:** no dependency, no CameraX version change, no `PROTOCOL_VERSION` bump, and — once the
  instrument came back out of the tree — no change to the shipped app at all. `Capabilities` has no
  blur field, so nothing was added to the wire and nothing has to be carried forward compatibly.
- **Revisit when:** the probe has run on the reference Pixel 10 and its table is in Action Item 1
  below. If the device advertises nothing, this ADR is superseded by a one-line "not available on the
  reference device" and the feature waits for #29 to widen the matrix. If it advertises the mode at a
  sub-UHD ceiling, the resolution question goes to Davide before any toggle is written. Also revisit at
  the CameraX 1.7 review (#27), which may change what can bind beside a UHD recording and would reopen
  Option C's constraint as well.

## Action Items

1. [x] **Run the probe on the reference Pixel 10** — done 2026-09-09. Pixel 10, Android 17 (API 37),
       back logical camera id 0.
2. [x] **Record whether the scene-mode control value is offered, and whether the mode holds without
       it.** It is *not* offered (`CONTROL_AVAILABLE_MODES = [0 1 2]`), and the mode is accepted and
       echoed without it. This ADR's route is therefore the scene-mode key alone.
3. [x] **Record the zoom band.** 1.0×–3.0×, which excludes two of PRD 6.5's four framings (the 0.56×
       ultrawide and the 5×). Moot on this device, since nothing is applied at any zoom; it matters
       again only if a device is found that works.
4. [x] **Re-run against a face** — done 2026-09-09, at 1× zoom with metered exposure. **No blur at
       3840×2160 and none at 1920×1080.** Tables above.
5. [x] **Withdraw the resolution decision taken on the wall measurement.** The +10 % that prompted
       "trust the measurement, not the vendor ceiling" was noise on a low-detail scene, most likely
       continuous AF. The claim that blur runs at UHD is retracted, in this ADR and in the code
       comments that repeated it; the resolution ladder it displaced was not restored, because
       `appliesToFootage` answered the question better and then the whole lot came out of the tree.
6. [ ] **Davide: park this, or keep it open?** The recommendation is to **park it**, in the shape
       ADR-0030 was parked: the route is right, the reference device does not implement it, and there
       is nothing further to build without different hardware. Unparking means a device whose
       `appliesToFootage` comes back true — worth re-testing when #29 widens the matrix to a second
       OEM, since this is a HAL feature and Samsung and Xiaomi are the vendors most likely to have
       implemented it.
7. [x] **Remove the instrument once the measurement is taken**, per ADR-0030's precedent — a parked
       feature reaches `main` as an ADR, not as code. Done; see below for where it is preserved.
8. [ ] Phase 4: if this is unparked, name the iOS equivalent (`AVCaptureDevice`'s portrait effect is a
       different shape) so `blurVerdict` stays the shared rule ADR-0013 requires.

## The instrument is not in the tree

Following ADR-0030's precedent -- a parked feature reaches `main` as an ADR, not as code -- the probe,
the gating rule and the wire field were removed once the measurement was taken. What is left is this
document. Nothing in the app knows the mode exists, `Capabilities` has no blur field, and
`ManualControls` is as it was.

**The instrument is preserved at commit `48e2741`** on `claude/camera-bokeh-extension-c84b76`, and is
worth recovering rather than rewriting if #29 brings a device to re-test on. It is:

- `capture/BlurProbe.kt` -- nine candidates, bind, settle, record ~5 s, read the echoes back
- `capture/BlurReport.kt` -- the pure half: phase bucketing, verdicts, Markdown
- `domain/blur/BackgroundBlur.kt` -- `BlurCapability`, `blurVerdict`, and the `appliesToFootage` check
  that is the whole lesson of this ADR
- two nullable fields on `ManualControls.Request`, an `ACTION_BLUR_PROBE` intent, and a
  `meteredRequest()` that seeds the run from the exposure loop's outputs

## Notes for whoever runs the probe next

The activity's launch mode is `standard`, so the intent only reaches it through `onCreate`: start it
from a cold app rather than expecting a running one to pick the action up. The probe waits up to 15 s
for the first camera bind rather than refusing outright, which is what makes the cold start work.

```bash
adb shell am force-stop com.scenaristo.camera
adb shell am start -n com.scenaristo.camera/.MainActivity -a com.scenaristo.camera.BLUR_PROBE
adb logcat -s BlurProbe:I
adb pull /sdcard/Android/data/com.scenaristo.camera/files/   # the files matter as much as the table
```

Two traps this probe fell into on its first run, both now fixed, both worth knowing about if the
instrument is extended: CameraX's runtime request state belongs to the **camera id**, not the session,
so a freshly bound candidate inherits whatever the app's exposure loop last asked for — six of nine
candidates reported the manual keys lost, and every one was measuring the previous session's ISO. And
frames-per-second taken over wall-clock elapsed includes binding the session, which reported 27.5 fps
for streams that were holding 30.
