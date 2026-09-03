# Feature Spec: Chapter Markers from the Remote Control

| | |
|---|---|
| **Status** | Draft v0.1 |
| **Date** | 2026-09-03 |
| **Author** | Davide Mendolia |
| **Parent** | [PRD: Scenaristo Camera — Talking-Head Recording App](PRD-talking-head-camera.md) |
| **Priority in parent** | **P1 (nice-to-have).** Replaces the one-line P1 in PRD §6.11: *"Marker: press a key in the browser to timestamp a good take in a sidecar file."* |
| **Phase** | Parent Phase 3, **with one dependency that lands in Phase 1** (see §8) |

> **Priorities below are relative to shipping this feature**, not to the parent PRD. The feature as a whole is a P1 there.

---

## 1. Problem Statement

A solo creator recording a talking head does not get it right first time. They say the paragraph, flub it, pause, and say it again — four, five, eight times — and only the last one is any good. Today they have two bad options: stop and restart the recording for every attempt (which means walking back to the phone, or at best a round trip through the browser, and which produces a folder of near-identical files with no clue which is the keeper), or leave the camera rolling and hand their editor a 20-minute file with eight buried attempts and no map.

The cost lands in the edit. Finding the good take in an unmarked 20-minute 4K file means scrubbing it in real time; on a two-hour shoot that is an hour of an editor's day, or an hour of the creator's own evening. It is also the moment the phone-as-camera story breaks down: a webcam user has the same problem, so this is not an upgrade, and a real camera operator has been solving it with a slate and a notepad for a century.

Marking takes from the browser closes the loop the remote control opened. The creator already has the laptop in front of them and their hands free between attempts; one keypress at the moment they know the take is dead is nearly free, and it turns an undifferentiated file into a chaptered one the editor can navigate.

## 2. Goals

1. **Leaving the camera rolling becomes the default workflow.** Marked sessions produce measurably fewer, longer files than unmarked ones — one recording per topic, not one per attempt.
2. **The delivered file carries its own map.** Chapters open in the editor and the player without a separate import step or a companion file the user has to keep track of.
3. **A marker costs one keypress and no attention.** No dialog, no field to fill, no mode to enter. Pressing it must never risk the recording.
4. **The marker lands where the user meant it.** ≥ 95 % of markers within ±250 ms of the frame the user was looking at when they pressed the key, despite up to 500 ms of preview latency (parent §6.8).
5. **Markers survive what the recording survives.** The parent PRD promises a playable file after a force-kill (§6.7). Markers should not be the one thing that is silently lost.

## 3. Non-Goals

- **Editing, trimming, or deleting takes in the app.** The app marks; the editor cuts. Building a review-and-cut UI is a different product and would pull the app past its "raw takes for an editor" scope (parent §3).
- **Playback or scrubbing of the marked recording in the browser.** Serving 4K video back over the LAN while recording competes with the encoder for exactly the thermal headroom the parent PRD's biggest technical risk is about.
- **Marking from the phone screen.** The premise of the feature is that the user is at the laptop, away from the phone. Adding a second trigger surface doubles the state-sync and input plumbing for a case the feature exists to avoid. Revisit only if §6 CM-8 (on-screen browser button) proves insufficient.
- **Automatic take detection** (silence detection, face-left-frame, clapper recognition). Interesting, unproven, and a research project rather than a feature. Listed as Future.
- **Marker sync across multiple recordings or sessions.** No backend in v1 (parent §3); markers belong to one file.
- **Retroactive marking of a recording after it has stopped.** Once the file is finalised the app is done with it; re-opening and re-muxing finished files is a file-manager feature, not a camera feature.

## 4. Prior Art: how OBS does it

The user's brief named the format directly — "Hybrid MP4" is [OBS Studio's term](https://obsproject.com/kb/hybrid-mp4), introduced in OBS 30.2, and chapter markers are the feature it was built to enable. The design below copies it, with one deliberate deviation. What OBS actually does, per [their engineering write-up](https://obsproject.com/blog/obs-studio-hybrid-mp4):

- **During recording** the file is a fragmented MP4: an incomplete `moov` at the head, then `moof` + `mdat` fragment pairs. An interrupted file is readable up to the last fragment.
- **On stop** it performs a *soft remux*: it writes a complete `moov` at the end of the file indexing every sample, and overwrites a placeholder `free` box at the head with an `mdat` header. The result is a file that standard MP4 software treats as an ordinary progressive MP4, while remaining fragmented inside. No full rewrite, so finalisation is fast and does not need double the free space.
- **Chapters** are written into that finalisation step, following **Apple's QuickTime chapter-track convention** rather than anything in the MP4 base spec — a text track carrying one sample per chapter, referenced from the video track by a `tref` box of type `chap`. OBS adapted FFmpeg's `mov` muxer implementation.
- **Triggers** are a hotkey — which names chapters `Unnamed <N>` — or the `CreateRecordChapter` request over obs-websocket, which takes a `chapterName` and is how remote controls and stream-deck integrations drive it. Our feature is the direct analogue of that second path.

Two limitations of the OBS implementation are load-bearing for our design, and are the reason this spec is not a straight copy:

1. **Chapters are held in memory and written only at finalisation, so a crash loses every marker** — OBS documents this plainly. The video survives; the map does not. That is a poor trade for us specifically, because the parent PRD sells crash resilience as a promise (§6.7) and a solo creator with no assistant is the person least able to reconstruct the take list from memory.
2. **Premiere Pro and Final Cut Pro do not read QuickTime chapter tracks.** DaVinci Resolve, QuickTime Player and VLC do. OBS notes XMP export as a possible future answer. For an OBS audience skewed to Resolve this is survivable; for a creator audience it may not be, and it is the blocking open question in §7.

A third, worth noting only because it constrains a future feature: chapter timestamps [were computed against total elapsed time rather than per-file time when automatic file splitting was on](https://github.com/obsproject/obs-studio/issues/12714) (OBS 32.0, since fixed). We have no file splitting; if we ever add it, this is the bug to write the test for first.

## 5. User Stories

### Solo creator
- As a solo creator, I want to press one key on my laptop when I flub a line so that I can immediately start the take again without stopping the recording.
- As a solo creator, I want to see the list of takes I have marked so far, with their durations, so that I know the marker registered and I have not lost count.
- As a solo creator, I want to flag the take I just nailed so that I can find it later without watching the others.
- As a solo creator, I want the marks to open as chapters when I drag the file into my editor so that I do not have to keep a separate notes file in sync with the video.
- As a solo creator, I want the marker to land on the moment I reacted to, not half a second later, so that the mark is at the start of the take and not inside it.

### Producer
- As a producer running the camera for a speaker, I want to name a take as I mark it so that the editor gets "Intro — v3", not "Take 7".
- As a producer, I want the take list to be identical on my laptop and on a second browser so that a colleague watching can see the same shot list.

### Edge cases
- As a user whose phone died mid-recording, I want the takes I had already marked to still be there so that a crash costs me the last take, not the whole session's notes.
- As a user whose browser dropped its Wi-Fi connection for ten seconds, I want the markers I placed before the drop to be intact and the recording to be unaffected.
- As a user who presses the marker key before recording has started, I want nothing to happen and to be told why, rather than to silently believe I marked something.
- As a user who presses the marker key twice by accident, I want a zero-length take to be discarded rather than to appear in my chapter list.

## 6. Requirements

### Must-have

---
**CM-1 — Hybrid MP4 container (fragmented write, soft-remux finalisation)**

The recording muxer writes fragmented MP4 during capture and finalises with a soft remux on stop, exactly as described in §4. This subsumes and supersedes the parent PRD's §6.7 line *"write fragmented MP4 (or periodic moov updates) so that a file truncated by a crash is still playable"* — the "or" is now decided, and decided in favour of the option that also carries chapters.

*Technical note.* Android's `MediaMuxer` exposes neither fragmented MP4 output nor chapter tracks, so this almost certainly means the app owns its MP4 writer or bundles one, which is the path OBS took. Confirm in parent Phase 0. This is the reason CM-1 must be decided in Phase 1 and not deferred with the rest of the feature (§8).

- [ ] A recording stopped normally produces a file that `ffprobe` reports as an ordinary MP4, playable in QuickTime Player, VLC, Resolve, Premiere and Final Cut.
- [ ] The same file's internal structure shows `moof` fragments (verifiable with `mp4box -info` or `ffprobe -show_frames`).
- [ ] Given the app is force-killed 30 s into a recording, the un-finalised file plays for at least the first 25 s (inherited from parent §6.7).
- [ ] Finalisation of a 30-minute 4K30 HEVC recording completes in under 3 s on the reference devices and does not require free space beyond the file's own size.

---
**CM-2 — Take-boundary marker from the browser**

A single keypress in the web UI closes the take in progress and opens the next one. Boundaries, not point flags: take *n* runs from boundary *n* to boundary *n+1*.

- The start of the recording is implicitly boundary 1 at t = 0; a recording with no keypresses yields a single chapter, "Take 1", and is indistinguishable to a player from an unchaptered file.
- Default key: **`M`**. Ignored when focus is in a text field. Requires no modifier.
- Auto-name is `Take <N>`, N counting from 1 within the recording. (We deviate from OBS's `Unnamed <N>` — the whole point is that the user knows what these are.)
- The action is fire-and-forget: no dialog, no confirmation, no focus change.
- Pressing the key while not recording is a no-op with a brief inline explanation ("Not recording — markers need a running take"). It must never start a recording.
- A boundary placed less than 1 s after the previous one replaces it rather than creating a zero-length take.

- [ ] Given a recording is running, when the user presses `M`, then a new take appears in the web UI take list within 200 ms and the phone's take count increments.
- [ ] Given `M` is pressed three times during a 60 s recording, then the finalised file contains four chapters named `Take 1`–`Take 4` with contiguous, non-overlapping ranges covering the full duration.
- [ ] Given the user presses `M` twice within 1 s, then only one boundary exists in the finalised file.
- [ ] Given the cursor is in the take-rename field, when the user types the letter `m`, then no marker is created.
- [ ] Given recording has not started, when `M` is pressed, then no marker is created, no recording starts, and the UI explains why.

---
**CM-3 — Marker timing is corrected for preview latency**

The parent PRD budgets up to 500 ms glass-to-glass preview latency (§6.8), plus network delay on the control channel. A marker stamped when the keypress *arrives at the phone* would therefore land consistently late — inside the next take rather than at its head, which is the one place a boundary must not be.

Each preview frame delivered over the WebSocket carries the source presentation timestamp of the frame it was rendered from. The browser stamps a marker against the PTS of the frame currently on screen, and sends that timestamp with the request; the phone trusts it after clamping it to `[recording start, now]`. Preview latency and control-channel latency both fall out of the calculation.

- [ ] Measured against a visible running timecode filmed by the phone, ≥ 95 % of markers land within ±250 ms of the timecode value visible in the browser at the moment of the keypress, on a healthy Wi-Fi network.
- [ ] Given the preview has degraded to 5 fps under bandwidth pressure, then marker accuracy degrades to ±500 ms and no worse (the frame interval dominates).
- [ ] Given a browser sends a timestamp before the recording start or in the future, then the phone clamps it and logs the clamp.
- [ ] No marker is ever placed outside the recorded duration.

---
**CM-4 — Chapters embedded in the finalised MP4**

At finalisation, markers are written into the file as a QuickTime chapter track — a text track with one sample per chapter, referenced from the video track by a `tref` box of type `chap` — following FFmpeg's `mov` muxer behaviour, as OBS does.

- [ ] The finalised file's chapters are listed by `ffprobe -show_chapters` with correct start times and titles.
- [ ] The chapters appear in QuickTime Player's chapter menu, in VLC, and in DaVinci Resolve.
- [ ] Adding a chapter track does not alter the video or audio tracks: bit-identical `mdat` payload compared to the same recording made with markers disabled.
- [ ] A file with chapters plays normally in Premiere and Final Cut, which ignore the chapter track (see §7 Q1 for what those users get instead).

---
**CM-5 — Crash-safe sidecar (our one deviation from OBS)**

This is the deliberate departure from the OBS design. OBS holds chapters in memory until finalisation and documents that they are lost in a crash. We append each marker to a sidecar as it is created, and use that sidecar as the source of truth at finalisation.

- `Scenaristo_YYYY-MM-DD_HH-MM-SS.markers.jsonl` sits beside the recording. One JSON object per line: `{"index": 2, "pts_ms": 61840, "name": "Take 2", "keeper": false, "created_at": "..."}`.
- Each line is appended and flushed before the marker is acknowledged to the browser. A single short line per keypress; the cost is negligible against a 45 Mbps video write.
- Finalisation reads the sidecar, writes the chapter track from it, and **leaves the sidecar in place** — it is the answer for editors that cannot read chapter tracks, and deleting it to keep the folder tidy would throw away the only machine-readable copy.
- Renames and keeper flags (CM-6, CM-7) append a new line rather than rewriting; last write per index wins.

- [ ] Given a recording with 5 markers is force-killed before stop, then the sidecar on disk contains all 5 markers with correct timestamps.
- [ ] Given the app is relaunched after such a crash, then it offers to finalise the orphaned recording and write its chapters, and doing so produces a file matching CM-4's criteria.
- [ ] Given the user declines recovery, then both the fragmented file and the sidecar are left untouched and neither is deleted.
- [ ] Sidecar writes never block or delay the capture pipeline; recorded frame rate stays at 30 fps while markers are being placed (parent §6.1).

---
**CM-6 — Take list in the web UI**

- Live list of takes for the current recording: number, start timestamp, running duration for the take in progress, keeper flag.
- Newest at the top; the in-progress take is visually distinct.
- The list is part of the phone's single-source-of-truth state and is broadcast to every connected client within 200 ms, per parent §6.8. A second browser sees the same list.
- On reconnect after a dropped connection, the browser recovers the full list, not just markers placed since reconnecting.

- [ ] Given two browsers are connected, when one presses `M`, then both take lists update within 200 ms.
- [ ] Given a browser disconnects for 30 s during which 2 markers are placed from another browser, when it reconnects, then it shows the complete list.
- [ ] The take list is legible and the marker control reachable on a phone-sized browser window (parent §6.8 responsive requirement) — see §7 Q2.

---
**CM-7 — Keeper flag**

One key — **`K`** — toggles a "keeper" flag on the take currently in progress. This is the "so we know the takes" half of the brief: a boundary says where a take starts, the flag says which one to actually watch.

- Reflected in the chapter title as `Take 3 ★` and as `"keeper": true` in the sidecar.
- Toggling is idempotent and can be done any time before the next boundary.

- [ ] Given `K` is pressed during take 3, then take 3 shows as a keeper on every connected client and its chapter title in the finalised file ends with `★`.
- [ ] Given `K` is pressed twice during the same take, then the take is not a keeper.

---

### Nice-to-have

- **CM-8 — On-screen marker button.** A large tap target in the web UI, needed for tablets and phone-sized browsers, which have no keyboard. See §7 Q2: this is arguably a Must, and is listed here only because the trigger decision was keyboard-only.
- **CM-9 — Inline take rename.** Click a take in the list, type a name, and it replaces `Take <N>` in the chapter title — the equivalent of `CreateRecordChapter`'s `chapterName`. Serves the producer stories directly.
- **CM-10 — Editor-friendly sidecar export.** A marker CSV/EDL for Resolve and an XMP sidecar for Premiere, written alongside the JSONL. The answer to §7 Q1 if the editor mix demands it; OBS lists the same idea as a possibility.
- **CM-11 — Retro-marker.** `Shift+M` places the boundary a fixed offset (default 3 s) earlier, for the very common case of realising the take died a beat after it did.
- **CM-12 — Countdown integration.** When the parent PRD's 3-2-1 countdown (§6.11) is used, the boundary lands at the end of the countdown rather than at the keypress.
- **CM-13 — Keyboard shortcut discoverability.** A `?` overlay listing the shortcuts, and a first-run hint on the record screen.
- **CM-14 — Marker count on the phone.** The phone screen shows "Take 4" so the talent can glance at it. Read-only; not a trigger surface (§3).

### Future considerations

Design so these are not blocked:

- **Automatic take detection** — silence gaps, face leaving frame, or a spoken keyword proposing boundaries the user confirms.
- **Per-take export** — cutting the finalised file into one file per take, or per keeper take, on the phone or in a desktop companion.
- **Markers across split recordings**, if file splitting is ever added. Compute timestamps per-file, not against total elapsed time; see the OBS bug in §4.
- **Marker-driven upload** — uploading only keeper takes when cloud upload arrives (parent §6.12).
- **Chapter metadata beyond a title** — take ratings, notes, the script line being read.

## 7. Open Questions

### Blocking (answer before build starts)

**Q1 — Premiere and Final Cut cannot read the chapter track we are about to write. Is an embedded chapter track enough, or is CM-10 a Must?**
QuickTime chapter tracks are read by QuickTime Player, VLC and DaVinci Resolve, and ignored by Premiere Pro and Final Cut Pro. If a majority of our creators edit in Premiere, then CM-4 — the headline of the feature — delivers them nothing, and the sidecar in CM-5 is doing all the real work. This needs the editor mix for our actual audience before we set priorities, not after.
*Recommendation:* ship CM-4 and CM-5 regardless — the sidecar is required for crash safety anyway and costs nothing extra — and treat CM-10 as a Must the moment research shows Premiere above roughly a third of users. — *Product / user research*

**Q2 — Keyboard-only triggering conflicts with the parent PRD's responsive-layout requirement.**
The trigger decision for this spec was keyboard shortcut only, but parent §6.8 requires the web UI to be usable on a phone-sized browser, where there is no keyboard. As written, a user controlling the camera from a tablet cannot mark takes at all. Either CM-8 is promoted to Must, or §6.8's promise is narrowed to exclude marking.
*Recommendation:* promote CM-8. The cost is one button. — *Product / Design*

### Non-blocking (resolve during implementation)

**Q3 — Does the app need to own its MP4 writer?** CM-1 assumes Android's `MediaMuxer` gives us neither fragmented output nor chapter tracks, which would mean writing or bundling a muxer — a materially larger job than the rest of this feature combined. Confirm in parent Phase 0, on real devices, before the Phase 1 muxer decision is locked. — *Engineering*

**Q4 — Is `M` the right key, and does it collide with anything?** `M` is free of browser and OS defaults on Chrome, Safari and Firefox, but the web UI may want it for "mute". Also decide whether `K` for keeper is memorable enough or whether the two actions should share one key with a modifier. — *Design*

**Q5 — Crash-recovery UX.** Automatic on next launch, or an explicit "an interrupted recording was found — finalise it?" prompt? Automatic is friendlier but silently touching a file the user has not asked about is the kind of behaviour that erodes trust in a recording app. — *Design / Engineering*

**Q6 — Does the ★ in a chapter title survive every reader?** Some tools handle non-ASCII chapter titles poorly. Test against QuickTime, VLC and Resolve; fall back to `(keep)` if it does not. — *Engineering*

**Q7 — Finalisation time on long recordings.** The soft remux writes a `moov` indexing every sample, so its cost scales with duration. Confirm CM-1's 3 s budget holds for a 60-minute take, and decide what the UI shows if it does not. — *Engineering*

## 8. Timeline Considerations

The feature belongs in the parent PRD's **Phase 3 (Android polish and P1)** — with one exception that matters more than the rest of the schedule.

**CM-1 must be decided in Phase 1, not Phase 3.** The parent PRD already requires a crash-resilient container in §6.7 and leaves the mechanism open ("fragmented MP4 *or* periodic moov updates"). If Phase 1 picks periodic-moov, or ships a plain fragmented file with no finalisation step, then adding chapters in Phase 3 means replacing the muxer in a shipped app — the single most dangerous component to swap, since every recording flows through it. Choosing hybrid MP4 in Phase 1 costs Phase 1 almost nothing extra (the fragmented write is required either way; the soft remux is a bounded addition) and turns Phase 3's work into UI plus a chapter track.

| Phase | Work | Exit criterion |
|---|---|---|
| **0 — Android spike** | Answer Q3: can `MediaMuxer` do fragmented output? If not, scope the custom writer. | A go/no-go on owning the muxer, with an estimate. |
| **1 — Capture engine** | CM-1 in full: fragmented write, soft-remux finalisation, crash recovery of the video itself. No markers yet. | Finalised files pass CM-1's criteria; force-killed files are playable. |
| **3 — Web control polish** | CM-2 through CM-7, plus CM-8 if Q2 resolves as recommended. | A creator records six attempts in one file, marks them from the laptop, and opens the result in Resolve with six named chapters. |
| **3+ / 4** | CM-9 to CM-14, and CM-10 if Q1 demands it. | — |

**Dependencies and risks**
- Depends on the parent PRD's §6.8 web control channel and state broadcast; nothing here works before Phase 2.
- CM-3's latency correction depends on preview frames carrying source PTS. That is a small addition to the preview protocol, but it has to be in the protocol from the start — retrofitting a per-frame field is worse than including it in Phase 2.
- If parent Open Question 5 resolves toward WebRTC preview instead of JPEG-over-WebSocket, CM-3's mechanism needs revisiting: PTS would come from the RTP timestamp rather than a field we control.
- Risk: the Q1 answer could hollow out the feature's headline. Answer it before Phase 3 starts, not during.

## 9. Success Metrics

**Leading (first 30 days after the feature ships)**

| Metric | Success | Stretch | How measured |
|---|---|---|---|
| Browser-connected sessions that place ≥ 1 marker | ≥ 35 % | ≥ 50 % | Opt-in analytics, marker-created event |
| Median takes per marked recording | ≥ 3 | ≥ 5 | Marker count per recording |
| Marker placement accuracy | ≥ 95 % within ±250 ms | ≥ 99 % | Timecode rig test, pre-launch and per release; not analytics |
| Markers recovered after an interrupted recording | ≥ 95 % | 100 % | Crash-recovery test suite + opt-in recovery event |
| Recordings where chapters are present in the finalised file, given ≥ 1 marker | 100 % | — | File metadata check in the test suite |
| Recordings interrupted or damaged in sessions using markers | No higher than the unmarked baseline | — | Recording error events, compared against parent §7's ≥ 97 % target |

**Lagging (90 days)**

| Metric | Success | How measured |
|---|---|---|
| Files per session, marker users vs non-marker users | ≥ 30 % fewer | Opt-in analytics — the real test of goal 1 |
| Median recording length, marker users vs non | ≥ 2× longer | Opt-in analytics |
| 30-day continued use among users who tried a marker | ≥ 60 % | Opt-in analytics |
| Support contacts about finding takes or losing markers | 0 | Support inbox |

Consistent with parent §7, analytics are opt-in and local-first; nothing here requires a backend.

## Sources

- [Hybrid MP4 & Hybrid MOV Formats — OBS Knowledge Base](https://obsproject.com/kb/hybrid-mp4)
- [Writing an MP4 Muxer for Fun and Profit — OBS blog](https://obsproject.com/blog/obs-studio-hybrid-mp4)
- [OBS Studio 30.2 Release Notes](https://obsproject.com/blog/obs-studio-30-2-release-notes)
- [Chapter markers not working for split recordings — obs-studio #12714](https://github.com/obsproject/obs-studio/issues/12714)
- [Hybrid MP4 Chapters in DaVinci Resolve Studio — OBS Forums](https://obsproject.com/forum/threads/hybrid-mp4-chapters-in-davinci-resolve-studio.178330/)
