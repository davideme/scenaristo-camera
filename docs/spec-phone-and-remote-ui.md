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

Warnings are `State.warnings` (`TOO_DARK`, `TOO_BRIGHT`, `TOO_CLOSE_TO_LENS`, `OVEREXPOSED_AT_BASE_ISO`) mirrored on both surfaces (PRD §6.3, §6.5, §6.8).

- [ ] Each warning is a single line beginning with the action: "Add light — ISO 1600 will look noisy", "Sit further back — 1.5–2 m for the 24 mm lens", "Too much light — close the blinds or move the key light back".
- [ ] A warning carries an icon; a control never does. This is what separates warning orange (`oklch(.74 .165 48)`) from control amber at a glance.
- [ ] A warning does not restate a value that is visible in the readout strip within the same screen.
- [ ] **A warning is a chip and nothing else** (decision 2026-09-04, Davide). No readout, status value or number is recoloured to signal one: the chip is the single channel, which is UI-2 applied to warnings. The one exception is the thermal dot, which names a four-level state rather than raising a warning.
- [ ] Warnings appear below the top strip on the phone and above the preview in the browser, and never over the subject.
- [ ] The flicker-safe shutter step of §6.3 is **not** a warning and is not drawn as one: it is a `Stepped` marker in the reported style on the shutter readout, and PRD §6.3 says explicitly that no warning is shown when the step succeeds.

---
**UI-6 — Recording state is impossible to misread**

- [ ] A red inset border frames the whole preview while recording.
- [ ] The timecode goes to full contrast and gains a filled red dot and the word `Recording`.
- [ ] The record control becomes a stop control (rounded square, same position, same size).
- [ ] Red (`oklch(.63 .21 26)`) appears nowhere else in the interface.
- [ ] **Every setting is locked for the duration of the take, and focus is not.** `Session.settings` already refuses any `settings.set` while `recording` is true, answering `nack` / `INVALID`, on PRD §6.1's promise of a locked look for the whole take — so the lens lock Davide confirmed on 2026-09-04 is the shipped behaviour and needs no change. Focus is the deliberate exception (UI-16). Whether white balance should be carved out too is **Q5**.
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
**UI-16 — Tap to focus**

PRD §6.1 ("continuous AF with face priority, lockable; tap-to-focus and lock on both phone and web") and §6.8. Landed in `:domain` as the `focus.set` command, with `docs/protocol/fixtures/cmd-focus-set.json` as its golden fixture.

- [ ] A tap on the preview — phone or browser — focuses there and locks. A control returns to continuous autofocus.
- [ ] The tap point is sent normalised in the frame, `0.0` to `1.0` on each axis. Not pixels: the browser is looking at a 960 × 540 preview of a 3840 × 2160 recording. Normalised **in the frame** rather than in the preview image, which is only unambiguous because §6.8 crops the preview to the recording's aspect ratio — that crop is what makes one pair of numbers mean the same point on both surfaces and in the file.
- [ ] Focus works **while recording**. It is the one control that does, and the reason is that refocusing leaves nothing in the file an editor has to explain, where a lens switch or a colour shift does.
- [ ] The current focus state is in the snapshot, so a second remote sees where focus went.
- [ ] A lens that cannot focus on a region reports `NOT_CAPABLE` and the control is labelled unavailable rather than inert ([ADR-0011](adr/0011-per-lens-capability-gating.md), UI-12). `:domain` cannot make that call — it holds no capability table — so the capture layer does.
- [ ] Not drawn on any artboard yet: the mockups show the preview without a focus indicator. Whatever is drawn must not sit over the subject's face, which is exactly where the tap will usually land.

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
| Tap to focus, and focus lock | Its own command rather than a patch field: it is allowed while recording, carries no `expectRev`, and a point and a mode only mean anything together | §6.1 "Tap-to-focus and lock on both phone and web", §6.8 | **Landed.** `focus.set` carrying `Focus(mode, x, y)`, `focus` on `CaptureSettings`, validation in `Session`, and `cmd-focus-set.json` as its golden fixture |
| Framing-guide toggles (thirds, eye line) | `SettingsPatch` plus a field on `CaptureSettings`, or client-local state if the guides are not meant to be shared between remotes | §6.8 "Preview shows framing overlays … toggleable from the web UI" | Open |
| Preview-link quality | `DeviceStatus` | §6.8 "connection quality" | Open |

The guides row has a design question inside it rather than a shape question: two remotes watching one phone may reasonably want different overlays, in which case the toggle is not protocol at all.

Focus's validation belongs here rather than in UI-16, because it is protocol and not layout. A point outside `0.0..1.0`, half a point, or a point handed to continuous autofocus is answered `INVALID` rather than repaired — the same reason the Kelvin range is refused rather than clamped: a value the phone silently repairs is a bug the client never learns it has. `NOT_CAPABLE` for a lens without focus regions is the capture layer's to send; `:domain` is platform-free and holds no capability table.

## 9. Verification

Per [ADR-0017](adr/0017-phase-0-verification-matrix.md) the reference matrix is one Pixel 10 and one MacBook. Nothing in this spec may be described as verified on Android or on the web generally.

- [ ] Every UI-n acceptance criterion above is checked on the Pixel 10 for the phone surface, and in Safari and Chrome on the MacBook for the remote.
- [ ] UI-3's cutout and gesture-inset criteria are checked in **both** landscape orientations.
- [ ] UI-1's 2 m legibility claim is checked at 2 m, not at a desk.
- [ ] The `.dc.html` mockups are the reference for spacing and colour where this document is silent, not a substitute for it: where they disagree, this document wins and the mockups are updated.

## 10. PRD text to amend

| PRD text | Amendment |
|---|---|
| §6.8 Controls: *"Shutter (1/50, 1/60, override), grid frequency, ISO (auto / manual value) …"* | The browser sets grid frequency, white balance and lens. Shutter and ISO are **reported**, not settable: they are outputs of the [ADR-0005](adr/0005-exposure-control-own-metering-loop.md) loop and [ADR-0007](adr/0007-control-protocol.md)'s `SettingsPatch` deliberately excludes them. A manual ISO lock, if it is still wanted (§6.3 offers one), needs a protocol field and belongs in §8. |
| §6.8 Controls: *"… orientation"* | Not drawn on any surface. Either specify where the orientation control lives, or drop it from the control list. |
| §6.9 *"The phone UI is intentionally minimal: preview, record button, the QR/URL panel, and a settings sheet"* | Still true, and now specific: four reported values, two status values, two controls, record, and the connect button. Battery and thermal state are explicitly **not** on the phone, because the OS draws them (UI-3). |

The first row is a candidate for the *Challenges to positions stated in the PRD* table in [docs/adr/README.md](adr/README.md), attributed to ADR-0005 and ADR-0007. It is not added here because that table is the ADR index's, and this is not an ADR.

## 11. Phasing

| Phase | Lands |
|---|---|
| Parent Phase 1 | UI-1 to UI-8, UI-11, UI-12 on the phone. The connect sheet (UI-7) can ship ahead of the browser page, since it is what makes the server discoverable. |
| Parent Phase 2 | UI-9, UI-10, and the browser halves of UI-1, UI-2, UI-5, UI-11, UI-12. §8's three protocol additions land here. |
| Parent Phase 3 | UI-13 to UI-15. |
| Parent Phase 4 | The remote control is reused byte-identically on iOS ([ADR-0013](adr/0013-multiplatform-strategy.md)); the phone HUD is re-implemented natively against this spec. UI-3 has no iOS equivalent — iOS must state that the app has to stay in the foreground (PRD §6.9). |
