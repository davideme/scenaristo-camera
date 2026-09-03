# PRD: Scenaristo Camera — Talking-Head Recording App

| | |
|---|---|
| **Status** | Draft v0.2 |
| **Date** | 2026-09-03 |
| **Author** | Davide Mendolia |
| **Platforms** | Android first (min Android 14), then iOS (min iOS 16) |
| **Scope** | v1 (capture engine + local web control) |

> **Assumptions still open.** Preview transport (JPEG over WebSocket), fragmented MP4 for crash resilience, and the behaviour on lenses that lack manual-control capabilities are drafting positions, listed in **Open Questions**. Audio scope, platform order, minimum OS versions, and web-interface security were decided on 2026-09-03; see the decision log in section 8.

---

## 1. Problem Statement

People who record talking-head video (course creators, founders, YouTubers, internal-comms teams) mostly record with a webcam, and the footage looks like it: soft, noisy, poorly exposed. The standard advice is to buy a DSLR or mirrorless camera, plus a capture card, plus lenses. But they already own a perfectly good 4K camera: their smartphone. Its sensor and lens beat any webcam, and it is sitting on the desk.

They do not use it because the stock camera app hides or auto-adjusts the settings that matter (shutter drifts and flickers under mains lighting, white balance shifts mid-take, ISO creeps up and adds noise, the wide lens distorts faces up close), and because once the phone is on a tripod pointed at them, they cannot see the framing or hit record without walking back to it. A webcam, for all its faults, shows you yourself on the screen you are already looking at.

The cost is creators settling for webcam quality, or spending €1,000+ on a camera setup to replace a device they already have. This app makes the phone the obvious upgrade path between the two.

## 2. Goals

1. **Correct settings by default.** A user who opens the app and hits record gets 4K/30 fps, flicker-free shutter, locked white balance, and lowest-noise ISO without touching a control.
2. **Zero-flicker footage in every mains-frequency region.** Shutter is matched to the local power grid automatically and the user can override it.
3. **Remote control from a second screen.** From a laptop or tablet browser on the same network, the user can see a live preview, adjust every exposed setting, and start and stop recording without touching the phone.
4. **Fast setup.** From app launch to first recording, including connecting the browser, takes under two minutes for a first-time user.
5. **Efficient files.** Recordings use hardware HEVC where available so a 10-minute 4K take fits comfortably on a phone and uploads quickly.

## 3. Non-Goals

- **Multi-camera or multi-phone sync.** Separate initiative; v1 is one phone, one subject.
- **Editing, trimming, captions, teleprompter.** The app produces raw takes for an editor. A teleprompter is a natural P2 but not required to solve the core problem.
- **Cloud upload, accounts, sync.** Files land in the device's camera roll or Movies folder. No backend in v1.
- **HDR / 10-bit / Dolby Vision / Log recording.** Talking-head content for the web is delivered in SDR Rec.709. HDR complicates the editing pipeline and is a separate decision.
- **Remote control over the internet.** The web interface is LAN-only by design. No relay, no tunnels.
- **Frame rates other than 30 fps or resolutions other than 4K UHD.** Fewer options is the product. Lower fallbacks exist only for devices that cannot do 4K/30.

## 4. Users

- **Solo creator (primary).** Records alone at a desk, today with a webcam, and has been eyeing a DSLR. Puts the phone on a tripod behind the monitor. Needs to see themselves and hit record from where they sit, exactly as the webcam let them. Moderate technical comfort; does not know what a Kelvin is but knows "the video looks orange".
- **Producer / assistant (secondary).** Operates the camera for a speaker from a laptop. Wants explicit settings and readouts, and to know the take is safely recorded.
- **Talent (indirect).** The person on camera. Benefits from distance guidance and consistent framing, never touches the app.

## 5. User Stories

Ordered by priority.

### Solo creator
- As a solo creator, I want the app to pick the right shutter, ISO, and white balance for me so that my footage is consistent without learning camera theory.
- As a solo creator, I want to open a page on my laptop that shows what the phone sees so that I can frame myself without walking back and forth.
- As a solo creator, I want to start and stop recording from my laptop so that takes do not begin with me reaching for the phone.
- As a solo creator, I want to be told when I am too close to the lens so that my face is not distorted.
- As a solo creator, I want to choose "natural light" or "artificial light" and pick from a short list of white balance presets so that skin tones look right without a colour picker.
- As a solo creator, I want a recording timer and remaining-storage readout on the laptop so that I know the take is running and will not be cut off.
- As a solo creator, I want a warning when the room is too dark or too bright for a clean image so that I fix lighting before recording, not after.
- As a solo creator, I want my voice recorded from the best microphone I have plugged in, with a level meter I can see, so that I do not discover a silent or clipped take afterwards.

### Producer
- As a producer, I want to see and set shutter, ISO, white balance, lens, and codec from the browser so that I control the camera precisely.
- As a producer, I want to see battery level, thermal state, and free storage so that I can avoid a take being interrupted.
- As a producer, I want changes I make in the browser to appear on the phone and vice versa so that there is one source of truth.
- As a producer, I want to know which codec the phone will use before I record so that I can plan the edit.

### Edge cases
- As a user whose phone cannot record 4K/30 or lacks manual exposure control, I want a clear message about what the app can and cannot do on this device rather than silent degradation.
- As a user in Japan (two mains frequencies in one country), I want to be able to override the shutter choice so that automatic region detection does not cause flicker.
- As a user whose browser loses the connection mid-take, I want the recording to continue on the phone so that a Wi-Fi blip does not ruin a take.
- As a user who accidentally closes the app or whose phone dies mid-recording, I want the partial file to be playable so that the take is not lost entirely.

## 6. Requirements

### 6.1 Capture defaults (P0)

| Setting | Default | Notes |
|---|---|---|
| Resolution | 3840 × 2160 (UHD) | Fallback 1920 × 1080 only if the device cannot do UHD at 30 fps. |
| Frame rate | 30 fps, constant | Min and max frame duration both locked to 1/30 s. No variable frame rate. |
| Shutter | 1/60 s (60 Hz grid) or 1/50 s (50 Hz grid) | See 6.2. |
| ISO | Auto, lowest that achieves target exposure | See 6.3. |
| White balance | Locked preset, default 5600 K | See 6.4. |
| Focus | Continuous AF with face priority, lockable | Tap-to-focus and lock on both phone and web. |
| Stabilisation | Off | Phone is on a tripod; stabilisation crops and can wobble. P1 toggle. |
| Orientation | Landscape | Portrait supported. Web UI shows orientation. |
| Camera | Rear main (wide) | Front camera selectable. |
| Audio | 48 kHz AAC stereo or mono, from the best available input | See 6.6. |
| Colour | SDR, Rec.709, 8-bit | HDR explicitly off. |

**Acceptance criteria**
- [ ] On a supported device, a fresh install records a clip whose metadata shows 3840×2160, 30.00 fps constant, shutter 1/50 or 1/60 matching region, WB locked.
- [ ] Recording a 60 Hz LED panel at 1/60 s shows no rolling bands; same for 50 Hz at 1/50 s.
- [ ] Frame rate does not drop below 30 fps in low light (the driver must not extend exposure past the locked shutter).

### 6.2 Mains-frequency detection (P0)

- Determine the local grid frequency from, in order: SIM country code (MCC), then device region setting, then timezone. Map via an embedded country → frequency table.
- Countries with mixed grids (Japan, parts of Saudi Arabia, Brazil historically) default to the majority frequency and surface a prominent "Grid: 50 Hz / 60 Hz" toggle.
- Manual override always available on phone and web. Override persists per device.
- Optional P1: flicker detection from the sensor to confirm or correct the choice.

**Acceptance criteria**
- Given the device region is Germany, when the app launches, then shutter defaults to 1/50 s and the UI reads "50 Hz".
- Given the device region is the United States, then shutter defaults to 1/60 s and the UI reads "60 Hz".
- Given the device region is Japan, then the UI shows the grid toggle prominently and the shutter follows the toggle.
- Given the user sets a manual override, when the app is relaunched, then the override is still applied.

### 6.3 ISO: auto at lowest possible (P0)

On a phone the aperture is fixed, so once shutter is locked, ISO is the only remaining exposure variable. "Lowest possible" means: run an auto-exposure loop that holds shutter fixed and picks the lowest ISO that hits the target exposure.

- Neither iOS nor Android exposes a native shutter-priority mode. The app runs its own AE loop: read the exposure offset from the device, adjust ISO within the device's supported range, hold shutter constant. Damped to avoid visible pumping.
- ISO manual lock available (phone and web) for users who want a fixed value.
- **Too-bright warning:** if the scene is overexposed at the device's base ISO, show "Too much light: reduce light or close blinds" (phones have no ND). Do not silently raise shutter speed.
- **Too-dark warning:** if ISO exceeds a per-device noise threshold (default ISO 800, tunable), show "Low light: add light to reduce noise". Do not slow the shutter.

**Acceptance criteria**
- Given a constant scene, when recording, then ISO settles within 2 seconds and does not oscillate by more than one stop.
- Given the scene brightens, then ISO decreases, shutter stays fixed, frame rate stays at 30.
- Given the scene is overexposed at base ISO, then the warning appears within 1 second on phone and web.

### 6.4 White balance presets (P0)

Two scenarios, each with three Kelvin presets. WB is always locked; auto WB is not the default because it drifts mid-take.

| Scenario | Presets (K) | Default |
|---|---|---|
| Natural light present | 4500, 5600, 6500 | 5600 |
| Artificial light only | 3200, 4500, 5600 | 5600 |

- Tint fixed at 0 in v1.
- Selecting a preset updates the preview immediately on phone and web.
- P1: "auto once" button that samples AWB, snaps to the nearest preset, and locks.
- Android note: there is no direct Kelvin API. The app converts Kelvin to RGB gains with a device-calibrated curve, falling back to the platform AWB modes (INCANDESCENT ≈ 3000 K, FLUORESCENT ≈ 4000 K, DAYLIGHT ≈ 5500 K, CLOUDY ≈ 6500 K) on devices that do not support manual colour gains.

**Acceptance criteria**
- Given "Artificial light only" and 3200 K, when recording under tungsten light, then a grey card reads neutral within ±300 K measured in post.
- Given any preset is selected, when the scene changes, then the recorded WB does not shift.
- Given a device without manual WB gains, then the app shows which preset is approximated and by which platform mode.

### 6.5 Lens detection and distance guidance (P0)

- Read the active lens's 35 mm-equivalent focal length (iOS: device format and EXIF focal length in 35 mm film; Android: `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` with sensor physical size).
- If the equivalent focal length is 23–25 mm (typical main and selfie cameras), show persistent guidance: "Wide lens: stand 1.5–2 m (5–6 ft) from the phone to avoid facial distortion."
- Guidance is dismissible per session but shown on every fresh launch and on the web UI.
- If the device has a longer lens (48 mm+ telephoto), list it as selectable and show "Recommended for talking head" next to it. Selecting a lens re-runs the guidance.
- P1: on-preview face-size estimate that says "Too close" when the face fills more than a threshold of the frame at a wide focal length.

**Acceptance criteria**
- Given the active lens is 24 mm equivalent, then the distance guidance is visible on phone and web before recording starts.
- Given the active lens is 77 mm equivalent, then no distance guidance is shown.
- Given the device reports multiple lenses, then each is listed with its equivalent focal length.

### 6.6 Audio (P0)

Confirmed in scope for v1 (decision 2026-09-03).

- Record audio with the video: AAC, 48 kHz, 128–256 kbps.
- Input priority: wired or USB-C/Lightning microphone, then built-in mic. Bluetooth mics are supported but flagged: "Bluetooth audio is low quality (HFP). Use a wired mic if possible."
- Live audio level meter on phone and web; clipping indicator.
- Gain: system auto in v1. Manual gain P1.

**Acceptance criteria**
- [ ] Recorded file contains an AAC track at 48 kHz in sync with video (< 40 ms drift over 10 min).
- [ ] Plugging in a USB-C mic switches input without restarting the session; web UI reflects the new input.
- [ ] Level meter updates at ≥ 10 Hz on the web UI.

### 6.7 Encoding (P0)

- Video: HEVC (H.265) Main profile, 8-bit, if a hardware encoder is present; otherwise H.264 High profile. Software HEVC is never used (thermal and battery).
- Detection: iOS — check available codec types on the movie output; Android — query `MediaCodecList` for a `video/hevc` encoder that reports hardware acceleration (API 29+).
- Target bitrate: HEVC 4K30 ≈ 45 Mbps; H.264 4K30 ≈ 80 Mbps. Tunable P1.
- Container: MP4 (`.mp4`) on both platforms. Keyframe interval 1 s.
- **Crash resilience:** write fragmented MP4 (or periodic moov updates) so that a file truncated by a crash or dead battery is still playable up to the last fragment.
- Files saved to the system camera roll (iOS Photos) or `Movies/Scenaristo` (Android MediaStore). Filename: `Scenaristo_YYYY-MM-DD_HH-MM-SS.mp4`.
- Codec in use is displayed on phone and web before recording.

**Acceptance criteria**
- Given a device with hardware HEVC, then the recorded file's video track is `hvc1`/`hev1`.
- Given a device without hardware HEVC, then the track is `avc1` and the UI said so before recording.
- Given the app is force-killed 30 s into a recording, then the file plays for at least the first 25 s.

### 6.8 Local web interface (P0)

The phone runs an HTTP + WebSocket server on the local network. Any modern browser (Chrome, Safari, Firefox, Edge, current and previous major version) on the same network can open it.

**Discovery and connection**
- Phone shows the URL (`http://<ip>:<port>`) and a QR code on its screen. mDNS name (`scenaristo.local`) advertised where the platform supports it; the IP URL is the reliable path.
- iOS requests Local Network permission; the app explains why.
- If no Wi-Fi is available, the app explains that the phone's hotspot can be used and shows the same URL.

**Security**
- **v1: open LAN access** (decision 2026-09-03). Any client on the local network that can reach the URL can view the preview and control the camera. The phone shows how many clients are connected so an unexpected viewer is visible.
- **Later version (P1): pairing check.** On first connection from a new browser, both the phone and the browser display the same short code (a number or an emoji sequence). The user confirms on the phone that the codes match before the browser is granted control. Confirmed browsers are remembered until the app is reinstalled or the user revokes them from the phone. This proves the person at the browser can also see the phone, without typing secrets.
- Plain HTTP in both versions (self-signed TLS on LAN causes browser warnings and helps nobody). The interface must never be reachable off-LAN: bind to the LAN interface only, never to a public address.

**Preview**
- Downscaled preview, default 960 × 540 at up to 15 fps, JPEG frames over WebSocket. Quality and frame rate degrade automatically under bandwidth pressure. Target glass-to-glass latency < 500 ms on a healthy Wi-Fi network.
- Preview is separate from the recording pipeline: recording is always full resolution and frame rate regardless of preview quality or whether a browser is connected.
- Preview shows framing overlays (rule-of-thirds, eye-line guide) toggleable from the web UI.
- P2: WebRTC transport for lower latency and better compression.

**Controls (every setting listed in 6.1–6.7 plus)**
- Start / stop recording with a large, unambiguous control; recording state is impossible to misread (red border, elapsed timer).
- Shutter (1/50, 1/60, override), grid frequency, ISO (auto / manual value), white balance scenario and preset, lens, focus (tap on preview, lock), audio input and level, codec readout, orientation.
- Status: battery %, charging state, thermal state (nominal / fair / serious / critical), free storage as minutes remaining at the current bitrate, connection quality.
- Warnings (too bright, too dark, too close, thermal, low storage, low battery) are mirrored from the phone.

**State**
- Single source of truth on the phone. Every change from any client is broadcast to all connected clients within 200 ms.
- Multiple browsers may connect; last write wins.
- If the browser disconnects during a recording, the recording continues. On reconnect the browser shows the current recording state and elapsed time.

**Acceptance criteria**
- Given the phone shows a QR code, when a laptop on the same Wi-Fi scans it, then the web UI loads with a live preview within 5 seconds.
- Given a second browser on the same network opens the URL, then it sees the same preview and state, and the phone's connected-client count reads 2.
- Given a request from outside the LAN interface, then the server does not answer (it is not bound there).
- Given the user changes WB on the phone, then the web UI reflects it within 200 ms, and vice versa.
- Given recording is started from the browser, when Wi-Fi is turned off on the laptop, then the phone keeps recording and the file is complete on stop.
- Given a 4K/30 recording is running, then preview frames continue and recorded frame rate stays at 30 (preview must not steal encoder time).
- Given the browser is on a phone-sized screen, then the controls are usable (responsive layout).

### 6.9 On-device UI (P0)

The phone UI is intentionally minimal: preview, record button, the QR/URL panel, and a settings sheet exposing the same controls as the web UI. Screen stays awake while the app is foregrounded. The app must stay in the foreground to record (both OSes suspend the camera in the background); the UI states this.

### 6.10 Device capability handling (P0)

- On first launch and on each lens switch, probe **per lens**: 4K/30 support, manual shutter and ISO (Android: `MANUAL_SENSOR` capability; iOS: custom exposure mode support), manual WB gains (Android: `MANUAL_POST_PROCESSING`; iOS: locked white balance with device gains), and hardware HEVC.
- Show a one-screen capability report ("Main camera: 4K30 ✓, manual shutter ✓, manual WB ≈ approximated, HEVC ✓. Ultrawide: manual shutter ✗").
- On Android, gate each control on the capability flag of the active lens, not on the hardware level (`LIMITED` / `FULL` / `LEVEL_3`) and not on the OS version. A lens without the flag gets the behaviour decided in Open Question 1. Controls that are unavailable are labelled "Not supported on this lens" rather than pretending.
- Minimum OS (decision 2026-09-03): Android 14 (API 34), iOS 16. The OS floor does not guarantee manual controls; those depend on the per-lens flags above, which the phone maker declares and which do not change with OS updates.

### 6.11 Nice-to-have (P1)

- Pairing check for the web interface: matching number or emoji code shown on phone and browser, confirmed on the phone (see 6.8 Security).
- Download the last recording (or any recording from this session) from the web UI.
- "Auto once" white balance snap to nearest preset.
- Face-size "too close" detector.
- Stabilisation toggle.
- Manual audio gain; external mic level calibration.
- Flicker detection to confirm grid frequency.
- Bitrate presets (Efficient / Standard / High).
- Countdown before record (3-2-1) shown on the phone and web so the talent knows when to start.
- Marker: press a key in the browser to timestamp a good take in a sidecar file.

### 6.12 Future considerations (P2)

Design so these are not blocked:
- WebRTC preview transport.
- Teleprompter rendered in the web UI or on the phone screen (a second phone or tablet as prompter).
- Multi-phone sync (timecode over LAN).
- 10-bit HDR / Log profiles.
- Desktop companion app that wraps the web UI and auto-discovers phones.
- Cloud upload of finished takes.

## 7. Success Metrics

**Leading (first 30 days after launch)**
| Metric | Success | Stretch | How measured |
|---|---|---|---|
| Time from first launch to first completed recording | < 2 min median | < 90 s | Local analytics event timestamps (opt-in) |
| Web interface connection success rate | ≥ 90 % of attempts connect within 30 s | ≥ 95 % | Server log: QR shown → first WebSocket frame |
| Sessions that use the web interface | ≥ 60 % | ≥ 75 % | Sessions with ≥ 1 web client |
| Recordings that finish without error (thermal stop, storage full, crash) | ≥ 97 % | ≥ 99 % | Recording start / stop / error events |
| Recordings using HEVC on HEVC-capable devices | 100 % | — | File metadata |
| Flicker complaints in support | 0 | — | Support inbox |

**Lagging (90 days)**
| Metric | Success | How measured |
|---|---|---|
| 30-day retention of users who completed a recording | ≥ 40 % | Opt-in analytics |
| Average recordings per active user per week | ≥ 3 | Opt-in analytics |
| Store rating | ≥ 4.5 | App Store / Play Console |
| Share of users overriding defaults (shutter, WB) | ≤ 25 % | If higher, the defaults are wrong |

Analytics are opt-in and local-first; no metric requires a backend in v1.

## 8. Open Questions

**Decision log (2026-09-03, Davide)**
| Question | Decision |
|---|---|
| Audio in v1? | Yes, as specified in 6.6. |
| Platform order | Android first. iOS follows once the Android capture engine is proven. |
| Minimum OS | Android 14 (API 34), iOS 16. |
| Web interface security | v1 ships with open LAN access. A later version adds a pairing check: a number or emoji code shown on both phone and browser, confirmed on the phone. Specified in 6.8 and listed as P1. |

**Blocking (answer before build starts)**
1. **Behaviour on lenses without manual-control capabilities.** Manual shutter and ISO require the Camera2 `MANUAL_SENSOR` capability; Kelvin white balance requires `MANUAL_POST_PROCESSING`. Both are declared per lens by the phone maker's camera driver and do not change with the Android version, so the Android 14 floor does not settle this. A phone can have them on the main camera and not on the ultrawide or telephoto. Decide, for each flag, whether to (a) refuse to record on that lens and steer the user to one that has it, or (b) ship a degraded mode using platform auto-exposure or auto white balance with the affected controls disabled and labelled. Recommendation: (a) for `MANUAL_SENSOR`, because flicker-free shutter is the product's core promise and a take with rolling bands is worse than no take; (b) for `MANUAL_POST_PROCESSING`, using the platform AWB fallback already in 6.4. — *Engineering / Product*

**Non-blocking (resolve during implementation)**
2. **Kelvin → gains calibration on Android.** Per-device curve, generic approximation, or platform AWB modes only? Depends on how far off a generic curve is on the top 10 target devices. — *Engineering*
3. **Custom AE loop stability.** How aggressive can ISO adjustment be before it is visible on camera? Needs testing on real devices. — *Engineering*
4. **Thermal behaviour at 4K30 HEVC.** How long can target devices sustain recording plus preview encoding before throttling? Determines whether the preview needs to drop to 5 fps when the phone is hot. — *Engineering*
5. **Preview transport.** JPEG-over-WebSocket is drafted for simplicity. Is < 500 ms latency achievable on typical home Wi-Fi, or is WebRTC needed in v1? — *Engineering*
6. **Mixed-grid country list.** Confirm the list of countries needing the prominent toggle. — *Product*
7. **Tech stack.** Native Android (Kotlin, Camera2) is the natural choice for a manual-control camera app shipping Android first. Decide now whether the web UI is built so it can be reused by the later iOS app unchanged (recommended: a static bundle served by both apps). — *Engineering*
8. **Pairing code format.** Number (4–6 digits) or emoji sequence for the P1 pairing check? Emoji is friendlier and harder to shoulder-surf across languages; digits are easier to read aloud to an assistant. — *Product / Design*
9. **App name and store listing.** "Scenaristo Camera" is used as a working title. — *Product / Marketing*

## 9. Timeline Considerations

No hard external deadline is known. Suggested phasing:

| Phase | Scope | Exit criterion |
|---|---|---|
| **0 — Android spike (1–2 wks)** | Prove manual shutter + custom ISO loop + locked WB + hardware HEVC on two Android reference devices (one Pixel, one Samsung). Measure thermal headroom. | Flicker-free 10-minute 4K30 HEVC clip on both devices, no throttling. |
| **1 — Android capture engine + phone UI** | 6.1–6.7, 6.9, 6.10 on Android 14+. Recording works entirely on the phone. | Internal users record real content with defaults only. |
| **2 — Web control** | 6.8: server, discovery, preview, full control and state sync. Open LAN access. Web UI built as a static bundle reusable by iOS. | A solo creator completes a take from a laptop without touching the phone. |
| **3 — Android polish and P1** | Pairing check, file download, countdown, auto-once WB, stabilisation toggle, Play Store submission. | Public Android beta. |
| **4 — iOS** | Port the capture engine to AVFoundation on iOS 16+, reuse the web UI and server design. | iOS reaches parity with the Android beta. |

**Dependencies and risks**
- Android manual-control support varies by OEM and by lens, independent of OS version. The reference device list should be chosen before Phase 0 and include a Pixel (LEVEL_3, all flags), a Samsung (main camera typically has `MANUAL_SENSOR`, secondary lenses often do not), and one device from an OEM known to restrict Camera2 manual controls despite offering them in its own camera app.
- Thermal throttling at 4K30 HEVC with simultaneous preview encoding is the single biggest technical risk; Phase 0 exists to retire it.
- Shipping v1 with open LAN access is acceptable for home and small-studio use; the Play Store listing should say so plainly so that office users know to wait for the pairing check.
- Apple Local Network permission and App Store review of a local HTTP server (Phase 4): no known blocker, but the review notes must explain the feature.

## 10. Prior Art

- **Blackmagic Camera** (iOS, Android): free, excellent manual controls, no remote web control.
- **Filmic Pro** (iOS, Android): manual controls, paid; remote control via a separate phone app (Filmic Remote), not a browser.
- **Open Camera** (Android): manual controls, no remote preview.
- **DoubleTake / Moment Pro Camera**: multi-cam and pro controls respectively; neither targets the talking-head workflow or a browser remote.

The gap this product fills is the combination: opinionated talking-head defaults plus zero-install browser remote with preview.
