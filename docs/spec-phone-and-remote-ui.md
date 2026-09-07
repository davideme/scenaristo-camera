# Feature Spec: Phone HUD and Remote Control UI

| | |
|---|---|
| **Status** | Draft v0.1 |
| **Date** | 2026-09-04 |
| **Author** | Davide Mendolia |
| **Parent** | [PRD: Scenaristo Camera — Talking-Head Recording App](PRD-talking-head-camera.md) |
| **Priority in parent** | **P0.** Gives shape to §6.9 (on-device UI) and the *Controls* and *Preview* halves of §6.8 (local web interface). Adds no requirement the parent does not already carry. |
| **Phase** | Phone HUD in parent Phase 1; remote control in parent Phase 2. §11 lists what each phase must land. |
| **Mockups** | [Scenaristo Camera UI canvas](https://claude.ai/code/artifact/fec0df91-2263-4e10-89c5-541d37de6e23) — nine artboards, one per state described below. |

> **This is a layout and copy specification, so it is not an ADR** (repository [CLAUDE.md](../CLAUDE.md): *"Do not write an ADR for … UI copy and layout"*). Where it takes a position that contradicts PRD text, that position is already carried by an Accepted ADR; §10 lists the PRD amendments that follow. It introduces no dependency, no new channel, and no change to any capture default.

---

## 1. What this spec settles

The PRD says what the interface must contain. It does not say what the interface *is*, and the gap is not cosmetic: two surfaces (phone and browser) show overlapping information about the same camera, and the parent's own guidance pulls in opposite directions — §6.9 wants the phone "intentionally minimal", §6.8 wants the browser to carry "every setting listed in 6.1–6.7 plus" a status line, warnings and a preview.

Without a rule, the obvious thing happens: every value gets drawn on both surfaces, and on each surface it gets drawn twice — once as a status readout, once on the control that changes it. That is the state the first draft of the mockups was in. This spec exists to fix the rule, not to enumerate widgets.

## 2. Goals

1. **A user can tell, without trying, what they are allowed to change.** The app's promise is that it chooses shutter, ISO and white balance (§6.1–6.4). The interface should make that obvious at a glance rather than by tapping something and finding it inert.
2. **No fact appears twice on one screen.** Each value has exactly one home.
3. **The speaker's face is never covered.** The person on camera is centre-frame; chrome that overlaps them makes the preview useless for the thing it exists for — framing yourself.
4. **The phone and the browser speak one language.** The same labels, the same order, the same colour meanings, so a producer moving between the two is not re-learning.
5. **Nothing in the interface claims a capability the protocol cannot carry.** A control that cannot be honoured is worse than a missing one.

## 3. Non-goals

- **A settings screen that exposes every camera parameter.** The parent PRD's product thesis is that the stock camera app's exhaustive controls are the problem (§1). Adding them back defeats it.
- **Theming, light mode, or an accent the user picks.** The interface sits on top of a live camera image; the palette is a legibility decision, not a taste one.
- **A phone-side take list, review, or playback.** Out of scope for the parent (see [spec-chapter-markers.md](spec-chapter-markers.md) §3).
- **Tablet or desktop-app layouts.** The browser page is responsive down to phone width (§8); it is not a separate design.
- **Animation and transition design.** Deliberately unspecified in v1.

## 4. Prior art: the pro camera app HUD, and why this inverts it

The visual reference is the professional phone-camera app: Filmic Pro, Blackmagic Camera, Protake. Their shared conventions are worth keeping, because they are the result of a decade of shooting on phones:

- Chrome is translucent and sits **on** the preview, not beside it — screen area is the product.
- Numbers are corner-anchored in fixed positions, so the operator finds them without reading.
- Numerals are tabular, so a changing value does not shift its neighbours.
- One colour means "recording" and nothing else.
- A padlock marks a value the camera is holding rather than tracking.

One convention is deliberately **not** kept. In those apps every number on screen is also a button, because in those apps every number is settable — the top strip of a Blackmagic Camera screen is nine tappable parameter columns. Scenaristo's entire proposition is the opposite: the app decides shutter (§6.2), ISO (§6.3) and white balance (§6.4), and [ADR-0007](adr/0007-control-protocol.md)'s `SettingsPatch` carries three fields, with shutter and ISO excluded on purpose because they are outputs of the [ADR-0005](adr/0005-exposure-control-own-metering-loop.md) exposure loop, not inputs.

Drawing those as buttons would be a lie the user discovers by pressing one. So the density and legibility conventions are kept, and the uniform "everything is a control" grammar is replaced by §5.

## 5. The rule

**Two grammars, and each fact drawn once.**

| | **Reported** | **Yours** |
|---|---|---|
| What it is | What the camera settled on, and what the app cannot help you with | The things you decide |
| Where | Top strip on the phone; the row above the preview in the browser | Bottom strip on the phone; the right-hand column in the browser |
| How it looks | Dimmed, no frame, tabular numerals, small padlock on values the app is deliberately holding still | Framed, amber label, carries its own current value |
| Touchable | Never | Always |

The second half is what makes it work: **a value you chose lives on the control that sets it, and nowhere else.** The white balance is on the Light control, not also in the status strip; the lens is on the Lens control; the mains frequency is a caption on the shutter readout, because 1/50 is derived from it and the pair is what makes an automatic step to 1/100 legible rather than alarming.

The corollary at the OS boundary is the same rule: **the app does not redraw what the system already draws.** Battery, charging and thermal state are Android status-bar items, one swipe away (UI-3), so they appear only on the remote control, where the phone's own screen is out of sight.

## 6. Requirements

### Must-have

---
**UI-1 — Two chrome classes, visually distinct at 2 m**

The talent reads this screen from across a room, not from arm's length. The distinction between reported and settable must survive that distance without reading the values.

- [ ] Reported values render at ≤ 58 % opacity, unframed, in tabular numerals.
- [ ] Settable controls render framed, with an amber label, at full text contrast.
- [ ] No reported value is a touch target, and no settable control is drawn without a frame.
- [ ] Amber (`oklch(.80 .14 82)`) is used for nothing except "you can change this".

---
**UI-2 — Each fact appears once per surface**

- [ ] No value appears both as a reported readout and on a control on the same screen.
- [ ] A control displays its own current value; the status strip does not repeat it.
- [ ] The phone does not display battery percentage, charging state or thermal state (UI-3). The browser does (UI-9).

---
**UI-3 — Phone HUD: landscape, edge to edge, system bars hidden**

The phone renders the preview edge to edge with the system status and navigation bars hidden; the user reveals the status bar with a swipe from the top edge when they want it (decision 2026-09-04, Davide). This is why UI-2 can drop battery and temperature from the app's own chrome.

Two consequences are load-bearing:

- The reveal gesture starts at the **top** edge, which under §5 is the strip that contains no touch targets. The gesture cannot steal a control.
- Hiding the navigation bar makes the **bottom** edge the home and back gesture zone. Record is the one control the system must never intercept.

- [ ] The app runs edge to edge with both system bars hidden while the camera screen is foregrounded.
- [ ] A swipe from the top edge reveals the status bar transiently; the HUD does not reflow when it appears.
- [ ] No control in the bottom strip comes within 28 dp of the bottom edge, nor inside the system gesture insets reported by the window, whichever is larger.
- [ ] No chrome overlaps the centre 60 % of the frame width between the top and bottom strips.
- [ ] Verified on the reference device that no readout is occluded by the display cutout in either landscape orientation ([ADR-0017](adr/0017-phase-0-verification-matrix.md); in landscape the Pixel 10 punch-hole sits mid-way up one long edge).

---
**UI-4 — Phone HUD contents**

| Zone | Contents |
|---|---|
| Top left, reported | Format `4K · 30`; Shutter `1/50` with padlock and a dimmed `50 Hz` caption; `ISO`; Codec |
| Top centre | Timecode, and the state word beneath it (`Ready` / `Recording`) |
| Top right, reported | `Space left` in minutes; `Remotes` count |
| Bottom left | Audio meter, two channels, with the active input named |
| Bottom centre, settable | `Light` (scenario and Kelvin preset); `Lens` |
| Bottom right | Connect / QR button; record button |

- [ ] Free storage is expressed as **minutes remaining at the current bitrate**, never as gigabytes (`State.kt`'s `storageMinutesRemaining`; PRD §6.8).
- [ ] The shutter readout shows the rung actually in use, including the flicker-safe step of §6.3, marked `Stepped` when it is not the base rung.
- [ ] Mains frequency is **not** a HUD control by default: it is a set-once-per-region choice and its value already reads as the shutter caption. It lives in the settings sheet, and is promoted to a bottom-strip control only where the region has two grids (PRD §6.2: Japan, parts of Brazil and Saudi Arabia).
- [ ] The record control is at least 64 dp; every other phone touch target is at least 44 dp.

---
**UI-5 — Warnings name the fix, not the fault**

Warnings are `State.warnings` (`TOO_DARK`, `TOO_CLOSE_TO_LENS`, `OVEREXPOSED_AT_BASE_ISO`) mirrored on both surfaces (PRD §6.3, §6.5, §6.8). `OVEREXPOSED_AT_BASE_ISO` is the only too-much-light warning; `TOO_BRIGHT` was removed in ADR-0021 because nothing ever raised it.

- [ ] Each warning is a single line beginning with the action: "Add light — ISO 1600 will look noisy", "Sit further back — 1.5–2 m for the 24 mm lens", "Too much light — close the blinds or move the key light back".
- [ ] A warning carries an icon; a control never does. This is what separates warning orange (`oklch(.74 .165 48)`) from control amber at a glance.
- [ ] A warning does not restate a value that is visible in the readout strip within the same screen.
- [ ] **A warning is a chip and nothing else** (decision 2026-09-04, Davide). No readout, status value or number is recoloured to signal one: the chip is the single channel, which is UI-2 applied to warnings. The one exception is the thermal dot, which names a four-level state rather than raising a warning.
- [ ] **Thermal says nothing until it costs something** (decision 2026-09-05, Davide). The dot appears only for `SERIOUS` and `CRITICAL`. `NOMINAL` and `FAIR` draw nothing at all — not a green dot, not a grey one. #23 measured a 10:42 4K30 take reaching Android's `MODERATE` after eight minutes while holding 29.990 fps with no dropped frames: if the throttling neither impacts the experience nor costs frames, the interface has nothing to report, and a warm phone recording 4K is a phone doing its job.
- [ ] Warnings appear below the top strip on the phone and above the preview in the browser, and never over the subject.
- [ ] The flicker-safe shutter step of §6.3 is **not** a warning and is not drawn as one: it is a `Stepped` marker in the reported style on the shutter readout, and PRD §6.3 says explicitly that no warning is shown when the step succeeds.

---
**UI-6 — Recording state is impossible to misread**

- [ ] A red inset border frames the whole preview while recording.
- [ ] The timecode goes to full contrast and gains a filled red dot and the word `Recording`.
- [ ] The record control becomes a stop control (rounded square, same position, same size).
- [ ] Red (`oklch(.63 .21 26)`) appears nowhere else in the interface.
- [ ] **Every setting is locked for the duration of the take.** `Session.settings` already refuses any `settings.set` while `recording` is true, answering `nack` / `INVALID`, on PRD §6.1's promise of a locked look for the whole take — so the lens lock Davide confirmed on 2026-09-04 is the shipped behaviour and needs no change. Whether white balance should be carved out is **Q5**. Focus used to be the exception here; since 2026-09-06 it is not a control at all (UI-16), so there is nothing to exempt.
- [ ] Every locked control is drawn as locked. The nack is the mechanism — [ADR-0007](adr/0007-control-protocol.md) has clients send requests rather than state writes, so a second remote or a stale tab cannot walk past a greyed control — but a user should never discover the rule by being refused.
- [ ] Controls locked for the duration of the take are dimmed with the caption "Locked while recording" — not with the padlock glyph, which means "held still by the app" (§5) and must not acquire a second meaning.

---
**UI-7 — Connect sheet**

Discovery per PRD §6.8: QR code plus the `http://<ip>:<port>` address.

- [ ] The address is rendered in the monospace face at ≥ 17 px and is copyable.
- [ ] The connected-remote count is shown, so an unexpected client is visible.
- [ ] The open-LAN consequence is stated in plain words on this sheet: "Anyone on this network can monitor and control the camera. Turn the server off when you are done." (v1 security position, PRD §6.8.)
- [ ] It is drawn as an **orange box with an icon, deliberately at the loud end** (decision 2026-09-04, Davide: people should notice). This is the one sanctioned use of orange outside `State.warnings`, and it obeys UI-5's shape rule — orange is always a bordered block with an icon, never a tint on a value. When the pairing check of PRD §6.11 lands, this box is what it replaces.
- [ ] "Recording keeps running if the laptop drops off Wi-Fi" is stated where the user first connects, not only in documentation.
- [ ] With no local network the sheet says the remote is off and how to turn it on ("Join this phone to Wi-Fi, or turn on its hotspot"), and names mobile data as not being one. The server is not listening in that state ([ADR-0026](adr/0026-serve-only-on-a-local-network.md)), so the orange box, the "recording keeps running" promise and the off-switch sentence — all three of which describe a running server — are not shown.

---
**UI-8 — Lens and capability report**

One screen, per PRD §6.10, gated per lens per [ADR-0011](adr/0011-per-lens-capability-gating.md).

- [ ] Each lens is a card carrying its 35 mm-equivalent focal length and four capability lines: 4K · 30, manual shutter, manual white balance, hardware HEVC.
- [ ] A capability that is approximated rather than absent is marked as approximated, and the sheet names what it is approximated with (PRD §6.4: "Daylight ≈ 5500 K").
- [ ] A lens without `MANUAL_SENSOR` is shown, disabled, with the reason and the remedy: "Cannot record: the shutter would drift and flicker. Use the main lens."
- [ ] An unavailable capability is drawn in neutral grey, not red (UI-6).
- [ ] A lens of 48 mm equivalent or longer carries "Recommended for talking head" (PRD §6.5).

---
**UI-9 — Remote control layout**

The browser is the producer's surface, and the one place where density is the point (PRD §4, §6.8).

- [ ] Layout is a preview stage plus a fixed right-hand control column.
- [ ] The row above the preview carries the same reported values in the same order as the phone, plus the preview's own resolution and frame rate.
- [ ] The control column carries, in order: **Phone** (battery and charging, temperature, space left, preview-link quality), **Light**, **Mains frequency**, **Lens**, **Sound**, **Framing guides**.
- [ ] The transport row under the preview carries the timecode and file name on the left, one unambiguous record control in the centre with its keyboard shortcut named, and minutes remaining plus codec and bitrate on the right.
- [ ] Thermal state is spelled with the four names of `ThermalState`: nominal, fair, serious, critical.
- [ ] Free space is minutes, as UI-4. Gigabytes are not shown.
- [ ] The lens list is the only way to switch lens, so its selectable rows are framed (UI-1); the unavailable row is not.

---
**UI-10 — The remote control works at phone width**

PRD §6.8 acceptance criterion: *"Given the browser is on a phone-sized screen, then the controls are usable."*

- [ ] At ≤ 480 px the layout is a single column: preview, reported values in a two-column grid, then the control panels.
- [ ] The record control and timecode are pinned to the bottom of the viewport on an opaque bar, reachable without scrolling.
- [ ] Touch targets are at least 44 px at this width.
- [ ] No fake phone chrome is drawn: the real browser and OS chrome render on top.

---
**UI-11 — Tokens**

| Token | Value | Meaning |
|---|---|---|
| Ground | `#0a0b0c` | |
| Panel | `#131519` | |
| Text | `#f2f0ed` | |
| Dim | `rgba(242,240,237,.58)` | reported values |
| Dimmer | `rgba(242,240,237,.34)` | labels and captions |
| Amber | `oklch(.80 .14 82)` | you can change this |
| Orange | `oklch(.74 .165 48)` | warning; always with an icon |
| Red | `oklch(.63 .21 26)` | recording, and nothing else |
| Green | `oklch(.76 .13 155)` | nominal, and audio meter safe range |

- [ ] Type is Archivo (400/500/600/700) for the interface and IBM Plex Mono for values that change character by character: timecode, addresses, file names.
- [ ] All numerals that update live are set with `font-variant-numeric: tabular-nums`.
- [ ] Labels are 9 px, 700 weight, uppercase, `.16em` tracking. Values are 15 px, 500 weight.
- [ ] Icons are stroke-based inline SVG on a 24 px grid. No emoji, no dingbat glyphs.

---
**UI-12 — Copy**

- [ ] Sentence case throughout. No title case, no shouting.
- [ ] Terminology is fixed (decision 2026-09-04, Davide): the browser surface is the **remote control**, a connected browser is a **remote**, and the count reads "2 remotes connected". Never *viewer*, and never *client* — a viewer watches, and this one can start a recording.
- [ ] The two light scenarios are "Daylight in the room" and "Lamps only" — the plain-language form of PRD §6.4's "natural light present" and "artificial light only". The user does not meet the word Kelvin before the preset value.
- [ ] A control that a lens cannot honour is labelled with what is missing, never hidden and never silently inert (PRD §6.10).
- [ ] Distance guidance reads "Wide lens — sit 1.5–2 m back" and is dismissible for the session (PRD §6.5).

---
**UI-16 — Focus is automatic, and has no control**

PRD §6.1, as amended on 2026-09-06: continuous AF with face priority, and nothing to press.

- [ ] Neither surface draws a focus control, a focus indicator, or a tap target on the preview. A tap on the preview does nothing.
- [ ] Focus state stays in the snapshot, because a remote showing what the camera is doing is worth having even when it cannot change it.

Verified on the reference device rather than assumed: with no `CONTROL_AF_MODE` set anywhere in the app, CameraX's default continuous AF runs with the HAL's own face detection, reporting `ROI kFace` and `status kFocused` with a subject in frame. The feature was already working before anything was built for it.

**What was tried, and why it was dropped.** #86 implemented tap-to-focus against the original §6.1 wording. On the device it made things worse: a tap locked focus and silently disabled face priority, which is the one behaviour a talking-head app must not lose. Tapping the same point again to release, plus an `AF LOCK` badge, made it survivable — but a control whose main risk is turning off the thing that was already working does not earn its place. Closed unmerged.

The protocol half remains and is unused: `focus.set`, `Focus(mode, x, y)`, validation in `Session`, and `cmd-focus-set.json`. It is left alone deliberately — removing it is a non-additive protocol change for no gain, and it is what tap-to-focus would be rebuilt on if §6.11 ever brings it back for a subject the face detector cannot find.

---
**UI-17 — Exposure aids on the remote control**

Requested by Davide on 2026-09-06: a grey indicator and a histogram on the browser surface. PRD §6.3 and §6.8; issue #97.

- [ ] Both live in the **Reported** grammar, not *Yours* (§5): they are what the camera settled on, they are outputs of the [ADR-0005](adr/0005-exposure-control-own-metering-loop.md) loop, and there is nothing to press. Dimmed, unframed, no amber label.
- [ ] The reading is **stops from correct**, not percent grey: `-0.4 EV`, negative under. It reads zero exactly when the exposure loop says the shot is right, which makes the loop's own work legible, and stops are the unit anyone who has metered a shot thinks in. Percent grey is perceptually non-linear — equal-looking errors read as very different numbers — and does not say which way to move. (Decision 2026-09-06, Davide.)
- [ ] The 18 % target is named **on the scale**, not as a second number beside the reading. Goal 2: no fact appears twice.
- [ ] The histogram is drawn on a gamma-encoded scale, low to high, 64 bins. Not linear: equal bin widths must be equal perceived steps, or everything a talking head cares about is squeezed into the leftmost eighth.
- [ ] Neither is drawn when `ExposureReadout.metering` is false. A meter reading zero says the scene is correctly exposed; a meter that is not running says nothing at all, and drawing the second as the first is how someone trusts an aid that is measuring nothing.

**Where the numbers come from is settled and is not a browser concern.** Both are measured on the phone, in the frame walk `FaceWeightedMeter` already runs on every tapped frame ([ADR-0018](adr/0018-preview-tap-for-metering-and-preview-frames.md)). The rejected alternative was reading the MJPEG preview back off a canvas in the browser: it costs the phone nothing, but it measures a downscaled JPEG and can therefore disagree with both the recording and the app's own metering — and an exposure aid that disagrees with the thing it advises about is worse than no aid. Measuring once on the phone also means every remote sees the same numbers, and Phase 4 gets them on iOS unchanged ([ADR-0013](adr/0013-multiplatform-strategy.md)).

Measured on the reference device: adding the histogram to the metering walk cost **no frames** — a 72 s 4K30 take records 2161 frames at 29.990 fps, which is #23's baseline exactly.

> **Open, for Davide:** where in UI-9's column order these sit. They are Reported, so the row above the preview is the natural home, but that row is currently one line and a histogram is not. The alternative is a block at the top of the control column, above **Phone**, which breaks §5's "Reported lives above the preview" rule for the one value that needs vertical space.

---
**UI-18 — The lens as an optic: f/-number and T-stop**

Requested by Davide on 2026-09-06: *"add T-Stops in the web interface"*. Issue #101.

- [ ] The remote shows the active lens's **f/-number and T-stop together**, on the Lens control, as reported values with the 35 mm-equivalent focal length. Both, never one alone.
- [ ] Both are labelled **informational** (Davide, 2026-09-06). Neither is a control and neither changes: a phone's aperture is fixed, so this is a lens constant, not a live readout.
- [ ] The T-stop is derived in the browser from `Optics.apertureFNumber`, never sent. A derived number on the wire is one iOS could derive differently.
- [ ] Rounded to one decimal: `f/1.7 · T1.8`. The platform reports `1.7000000476837158`.
- [ ] Nothing is drawn when the lens reports no aperture. Not `T0.0` — the entire argument for drawing this is that a reader can see what it is.
- [ ] **Shown only at the framing it was probed at (1×).** `LENS_INFO_AVAILABLE_APERTURES` describes the logical camera and is `float[1]` on the reference device: one number, whichever sensor the HAL is using. Once the lens is a zoom ratio (UI-21), a longer framing is served by different glass with a different and unreported aperture, so drawing `f/1.7` at 5× would be a number about the wrong lens. At any other framing the readout says the device does not report it — the same choice as a thermal state that costs nothing and a meter that is not running.

**The T-stop is an assumption, and showing both numbers is what makes it honest.** A real T-stop is the f/-number corrected for how much light the glass actually passes, and no phone reports its transmission — Android offers `LENS_INFO_AVAILABLE_APERTURES` and nothing about efficiency. Measuring it would mean a grey card at a known illuminance, per lens, per device (the method #24 used for the Kelvin curve), and per [ADR-0017](adr/0017-phase-0-verification-matrix.md) the answer would be a fact about one Pixel 10 rather than about phones. So the app assumes **92 % transmission** — about a sixth of a stop, `log2(1 / 0.92)` = 0.120 EV — and draws the f/-number beside it, so the assumption is visible in the gap between the two rather than hidden inside one number.

Measured on the reference device: the Pixel 10's main lens reports **24 mm equivalent at f/1.70**, which gives **T1.77**.

---
**UI-21 — The lens list is a list of framings**

Corrects UI-8 and UI-9 for what a phone actually is (Davide, 2026-09-06: *"the other lens are zoom accessible"*). Issue #77.

**A phone's other lenses are not selectable cameras.** On the reference device the ultrawide and telephoto sit behind the back logical camera and cannot be chosen at all: lens choice is a **zoom ratio**, and the HAL decides which sensor serves it — possibly mid-session, while recording (`PinnedLensProbe`). UI-8's "each lens is a card" and UI-9's "the lens list is the only way to switch lens" were written against a device model that does not exist.

- [ ] The Lens control offers **zoom ratios, labelled by the field of view they produce**: `13 mm · 0.6×`, `24 mm · 1×`, `48 mm · 2×`, `120 mm · 5×` on the reference device.
- [ ] The label is a focal length and never a lens name. The app knows the field of view a ratio produces; CameraX 1.6.2 will not say whether glass or a crop delivers it, and a focal length is true either way — it is also the number PRD §6.5's guidance is written in.
- [ ] `48 mm+` carries "recommended" (PRD §6.5). On this device that is only reachable by zooming, which is exactly what the list is for.
- [ ] Nothing beyond 5× is offered however far the device zooms: past the longest real lens, more zoom is a crop of a sensor already cropped to 16:9 at 4K, and offering it as a lens would be offering a softer picture as a choice.
- [ ] A device with fewer than two framings gets a **reported** panel, not a control — goal 5.
- [ ] Locked during a take, like every other setting (UI-6, Q1).
- [ ] A ratio the phone never offered is answered `INVALID`, not clamped.

`lensId` keeps meaning the camera device it always meant; the new `zoomRatio` is what a client sets.

---
**UI-22 — Distance guidance is not a warning**

PRD §6.5 and UI-12: *"Distance guidance reads 'Wide lens — sit 1.5–2 m back' and is dismissible for the session."* Issue #5.

- [ ] Shown when the framing in use is in PRD §6.5's 23–25 mm band, above the preview.
- [ ] Drawn **quieter than a warning chip**: no orange, no warning icon, a plain bordered line. UI-5 reserves that shape for something that just became true; this is a standing fact about the lens for the whole session.
- [ ] Dismissible, and dismissed for the **session only** — the next session is a different shot with a different person in front of the camera, and a preference that outlived it would silence PRD §6.5 permanently after one click.
- [ ] The band comes from `:domain` (`LENS_WIDE_BAND`), generated rather than written down twice: a browser deciding at 26 mm what the phone decides at 25 is two surfaces disagreeing about the shot in front of them.

---
**UI-23 — The horizon overlay**

PRD §6.11, ADR-0027. PRD §6.1 switches both stabilisers off because *"phone is on a tripod"*; this is the only thing in the product that checks that premise.

- [ ] A **fourth switch in the same View panel** as UI-19 and UI-20, off by default and client-local for §8's reason. It is a setup aid, and clutter over someone's face once the tripod is placed.
- [ ] **A short mark at the centre of the frame, not a line across it** (Davide, 2026-09-07): a fixed horizontal reference, the horizon as the camera sees it, and the angle beside them. Level is when the two coincide. Thin and dimmed like UI-20's, and never bright enough to compete with the subject — the reading is wanted for the ten seconds a tripod is being placed, and an aid that draws over the speaker's face for the rest of the session is one people switch off and forget to switch back on (goal 3).
- [ ] **The angle sits on the mark**, not in a caption elsewhere. The mark says which way and the number says how far; they are one reading, and levelling a tripod should not mean looking in two places for it.
- [ ] **Not drawn when the preview is not producing frames.** With the phone's screen off, #116's note already occupies the middle of the same empty rectangle, and two notices fighting for one centre is worse than the one that says what to do. A horizon over a black frame is not a shot anyone can level.
- [ ] **Drawn in pixel space, not in UI-20's SVG.** That SVG is stretched to the stage (`preserveAspectRatio="none"`), which is harmless for lines that are axis-aligned and ruinous for one that is not: a tilt of 0.6° would render at whatever angle the stage's shape made of it.
- [ ] **Mirrored with the preview**, unlike UI-20 — the first overlay in this app of which that is true. UI-20 gets to ignore UI-19 because its lines are symmetric about the centre; a tilted line is not, and one drawn the wrong way round tells someone to correct their tripod in the wrong direction.
- [ ] **No left or right in the copy, anywhere.** A tilt is a left-or-right fact and UI-19 takes left and right away per viewer, so *"raise the right side"* is the right instruction and the wrong one depending on a preference the phone knows nothing about. The words carry the size (`1.4° off level`), the line carries the direction, and the phone is levelled by turning it until the line lies flat.
- [ ] Under a third of a degree it reads `Level` rather than a number: a readout that never quite reads level is one somebody keeps adjusting against.
- [ ] An unsteady mount is the **same line, drawn broken** — the same device UI-20 uses to tell two lines apart without colour — reading `Unsteady — check the mount`.
- [ ] **This raises no warning chip and recolours nothing** (UI-5). A tilt is a standing fact about the setup rather than something that just became true, which is UI-22's distinction, and it is the *only* surface: there is no phone-side level readout, on UI-3's grounds that the phone shows what the phone is uniquely placed to show, and someone levelling a tripod is looking at the tripod.
- [ ] Pitch — where the lens is aimed relative to horizontal — is reported beside it and **never framed as wrong**. Aiming slightly up at a seated speaker is a decision as often as an accident. Up and down also survive the mirror, which is why this one names a direction.
- [ ] **It says when it is not measuring, and does not freeze.** The accelerometer is unregistered for the duration of a take (ADR-0023), so the overlay reads *"Levelling pauses while recording — the take is unaffected"*: the same register as the empty-preview note, saying what is happening and what is not wrong. With the camera released (ADR-0025) it draws nothing at all, because there is nothing to say.

---
**UI-20 — Framing guides**

PRD §6.8: *"Preview shows framing overlays (rule-of-thirds, eye-line guide) toggleable from the web UI."* Issue #3.

- [ ] Rule of thirds and the eye line are **two switches, not one**, in the same View panel as UI-19's mirror. They are used at different moments — thirds while placing the shot, the eye line while the speaker settles into the chair, when the other five lines are clutter over their face.
- [ ] The eye line sits at the **upper** third, which is where a talking head's eyes belong, and is dashed so it stays tellable apart from the thirds line it lies exactly on top of.
- [ ] Lines are thin, dimmed, and never bright enough to compete with the subject (goal 3).
- [ ] Client-local, like UI-19 and for §8's stated reason.
- [ ] Drawn as an overlay, not into the preview: the preview is an MJPEG `<img>` the browser paints itself (ADR-0008), and drawing into it would mean a canvas and a copy of every frame for two straight lines.
- [ ] The overlay is not mirrored with the preview, and does not need to be — every line is symmetric about the centre, so a flip maps the set onto itself.

---
**UI-19 — Mirror the preview, per browser**

Requested by Davide on 2026-09-06.

- [ ] The remote control can flip its preview horizontally, from a **View** panel in the control column.
- [ ] The switch carries the sentence "Preview only — the recording is never mirrored", next to the switch and not in a help page.
- [ ] The setting is **client-local**: stored in this browser, never sent, and a second remote is unaffected.
- [ ] It survives a reload, and a browser that cannot store it still honours it for the session.
- [ ] The flip is on the preview image, not on its frame — UI-6's recording border lives on the frame, and mirroring that would put its rounding on the wrong corners.

**Why it is not protocol.** §8 already reasons this out for the framing guides: *"two remotes watching one phone may reasonably want different overlays, in which case the toggle is not protocol at all."* Mirroring is the same shape of thing and more so — a speaker framing themselves wants the flip, and at the same moment a producer reading the whiteboard behind them does not. Whether the preview should be flipped depends on who is looking at it.

**Why the copy matters.** A mirror control that turned out to have flipped the take would be discovered in an edit, which is far too late. Verified on the reference device rather than argued: with mirroring on in the browser, a recorded file's first frame matches the *unmirrored* view, and the container carries no display matrix or rotation side data. The transform is a CSS property on an `<img>`, three processes away from the encoder, so it could not reach the file — but the interface states the boundary rather than leaving the user to work that out.

---
### Nice-to-have

- **UI-13** Countdown before record (3-2-1), on both surfaces (PRD §6.11).
- **UI-14** Face-size indicator on the preview when the subject is inside the wide lens's distortion zone (PRD §6.11), replacing the persistent text guidance of UI-12 once it is reliable.
- **UI-15** A one-glance "everything is right" state — the case where there are no warnings deserves a positive signal, not merely the absence of orange.

## 7. Questions

### Decided

| # | Question | Decision (2026-09-04, Davide) |
|---|---|---|
| Q1 | Does the lens stay changeable mid-take? | **No.** Lens and mains frequency are locked for the duration of the take; white balance stays changeable. Specified in UI-6, and it needs the phone to refuse the command, not only the browser to grey the control. |
| Q2 | Warning as a chip, or also as a tint on the readout that caused it? | **Chip only.** One warning, one place. No number is recoloured to raise one, which also settles that the flicker-safe shutter step is reported rather than warned. Specified in UI-5. |
| Q3 | How loudly should the open-LAN consequence be stated (UI-7)? | **Loudly.** Keep the orange box: people should notice. Specified in UI-7. |
| Q4 | Is "remote" the right user-facing word? | **Yes, as it stands.** The surface is the *remote control*; a connected browser is a *remote*, and the count reads "2 remotes connected". Specified in UI-12. |

### Open

| # | Question | Why it is not mine to settle |
|---|---|---|
| Q5 | Should white balance be changeable mid-take? `Session` locks **every** setting while recording, which is stricter than Q1 asked about. Q1's answer confirmed the lens; it did not say whether the Kelvin preset should be the exception. | A trade the user makes, not the protocol: a colour jump mid-file against having to stop and restart when the light changes under you. Carving it out means relaxing `Session.settings` and a test that currently asserts the opposite. |

## 8. Protocol additions

All are **additive**, so no ADR is required ([CLAUDE.md](../CLAUDE.md): *"additive protocol fields"*).

| Need | Where it belongs | PRD | Status |
|---|---|---|---|
| Tap to focus, and focus lock | Its own command rather than a patch field: it is allowed while recording, carries no `expectRev`, and a point and a mode only mean anything together | §6.11 (moved out of §6.1, decision 2026-09-06) | **Landed but unused.** `focus.set`, `Focus(mode, x, y)`, `focus` on `CaptureSettings`, validation in `Session`, and `cmd-focus-set.json`. Nothing sends it and nothing applies it: focus is automatic (UI-16). Kept rather than removed — deleting it is a non-additive protocol change for no gain, and it is what §6.11 would rebuild on |
| Framing-guide toggles (thirds, eye line) | `SettingsPatch` plus a field on `CaptureSettings`, or client-local state if the guides are not meant to be shared between remotes | §6.8 "Preview shows framing overlays … toggleable from the web UI" | Open |
| Preview-link quality | `DeviceStatus` | §6.8 "connection quality" | Open |
| The lens list, as selectable framings | `LensChoice` list on `State`, `zoomRatio` on `CaptureSettings` and `SettingsPatch` | §6.5, §6.8 (UI-21, #77) | **Landed.** A phone's other lenses are zoom ratios, not cameras |
| The lens as an optic: focal length and f/-number | `Optics` on `State` | §6.5, §6.8 (UI-18, #101) | **Landed.** `equivalentFocalLengthMm`, `apertureFNumber`. The T-stop is derived in the browser and deliberately not on the wire |
| How the phone sits on its mount: level and steadiness | `MountAttitude` on `State` | §6.11 (UI-23, ADR-0027) | **Landed.** `rollDegrees`, `pitchDegrees`, `steady`, `measuring`. Reported only, and quantised with a deadband before it reaches the wire so a still phone costs no revision (ADR-0024). The overlay's *toggle* is client-local, like UI-20's; only the measurement is protocol |
| Exposure aids: stops from correct, and a histogram | `ExposureReadout` on `State` | §6.3, §6.8 (UI-17, #97) | **Landed.** `stopsFromTarget`, `histogram` (64 bins), `metering`. Measured in the metering walk of ADR-0018, so the reading and the loop cannot disagree |

The guides row has a design question inside it rather than a shape question: two remotes watching one phone may reasonably want different overlays, in which case the toggle is not protocol at all.

Focus's validation belongs here rather than in UI-16, because it is protocol and not layout. A point outside `0.0..1.0`, half a point, or a point handed to continuous autofocus is answered `INVALID` rather than repaired — the same reason the Kelvin range is refused rather than clamped: a value the phone silently repairs is a bug the client never learns it has. `NOT_CAPABLE` for a lens without focus regions is the capture layer's to send; `:domain` is platform-free and holds no capability table.

## 9. Verification

Per [ADR-0017](adr/0017-phase-0-verification-matrix.md) the reference matrix is one Pixel 10 and one MacBook. Nothing in this spec may be described as verified on Android or on the web generally.

- [ ] Every UI-n acceptance criterion above is checked on the Pixel 10 for the phone surface, and in Safari and Chrome on the MacBook for the remote.
  - Chrome: the remote control of UI-9, UI-10, UI-17 to UI-22 was built and driven against the phone in a Chromium browser (2026-09-06/07).
  - **Safari: verified by Davide, 2026-09-07** — "working fully tested". The one that mattered is MJPEG in an `<img>`: [ADR-0008](adr/0008-preview-transport.md)'s whole argument is that the browser renders `multipart/x-mixed-replace` natively, and a Safari that stalled on the first frame would have taken the transport decision with it.
- [ ] UI-3's cutout and gesture-inset criteria are checked in **both** landscape orientations.
- [ ] UI-1's 2 m legibility claim is checked at 2 m, not at a desk.
- [ ] The `.dc.html` mockups are the reference for spacing and colour where this document is silent, not a substitute for it: where they disagree, this document wins and the mockups are updated.

## 10. PRD text to amend

| PRD text | Amendment |
|---|---|
| §6.8 Controls: *"Shutter (1/50, 1/60, override), grid frequency, ISO (auto / manual value) …"* | **Amended 2026-09-07.** §6.8 now splits the list into *settable* (grid frequency, white balance, lens, and ADR-0023's exposure mode) and *reported* (shutter including §6.3's step, ISO, codec, audio, focal length and aperture). Shutter and ISO are outputs of the [ADR-0005](adr/0005-exposure-control-own-metering-loop.md) loop and [ADR-0007](adr/0007-control-protocol.md)'s `SettingsPatch` deliberately excludes them. The manual ISO lock §6.3 once offered was removed in #87 and replaced by [ADR-0022](adr/0022-two-exposure-responsiveness-modes.md)'s two damping modes. |
| §6.8 Controls: *"… orientation"* | **Amended 2026-09-07: dropped.** Portrait moved to §6.11 on 2026-09-04, so v1 is landscape only and there is nothing for an orientation control to switch between. It survived from a draft that predated that decision. |
| §6.8 Controls: *"… lens"* | **Amended 2026-09-07.** Lens selection is a **zoom ratio**, not a camera: the ultrawide and telephoto sit behind one logical camera and cannot be selected (UI-21). The browser offers the framings the device reports, each labelled by its 35 mm equivalent. |
| §6.7 *"Target bitrate: HEVC 4K30 ≈ 45 Mbps; H.264 4K30 ≈ 80 Mbps"* | **Amended 2026-09-07.** Measured at **33.4 Mbit/s** on the reference device (#21), less than half the figure named. CameraX 1.6.2's `Recorder` derives the bitrate and offers no supported override, so the app does not set one; a target is P1 and belongs with the 1.7 revisit (#27), where codec enforcement also lands. |
| §6.9 *"The phone UI is intentionally minimal: preview, record button, the QR/URL panel, and a settings sheet"* | Still true, and now specific: four reported values, two status values, two controls, record, and the connect button. Battery and thermal state are explicitly **not** on the phone, because the OS draws them (UI-3). |

The first row is a candidate for the *Challenges to positions stated in the PRD* table in [docs/adr/README.md](adr/README.md), attributed to ADR-0005 and ADR-0007. It is not added here because that table is the ADR index's, and this is not an ADR.

## 11. Phasing

| Phase | Lands |
|---|---|
| Parent Phase 1 | UI-1 to UI-8, UI-11, UI-12 on the phone. The connect sheet (UI-7) can ship ahead of the browser page, since it is what makes the server discoverable. |
| Parent Phase 2 | UI-9, UI-10, and the browser halves of UI-1, UI-2, UI-5, UI-11, UI-12. §8's three protocol additions land here. |
| Parent Phase 3 | UI-13 to UI-15. |
| Parent Phase 4 | The remote control is reused byte-identically on iOS ([ADR-0013](adr/0013-multiplatform-strategy.md)); the phone HUD is re-implemented natively against this spec. UI-3 has no iOS equivalent — iOS must state that the app has to stay in the foreground (PRD §6.9). |
