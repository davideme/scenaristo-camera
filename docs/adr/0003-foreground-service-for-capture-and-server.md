# ADR-0003: Run capture and the web server in a foreground service

**Status:** Accepted (2026-09-03, Davide; PRD 6.9 amended)
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 6.8, 6.9, 9 (risks: thermal)
**Related ADRs:** ADR-0002, ADR-0006

## Context
PRD 6.9 states: "The app must stay in the foreground to record (both OSes suspend the camera in the background); the UI states this." That is true on iOS. On Android it is not: since Android 11 an app may keep using the camera and microphone from a foreground service that was started while the app was visible, and Android 14 requires the service to declare `foregroundServiceType="camera|microphone"` with the matching `FOREGROUND_SERVICE_CAMERA` / `FOREGROUND_SERVICE_MICROPHONE` permissions. The web server has the same problem in a stronger form: if the activity is the only thing keeping the process alive, a screen timeout or an incoming call kills the remote control mid-take, which is the scenario PRD 6.8 explicitly protects against on the browser side.

The screen is also a heat source. The PRD names thermal throttling at 4K30 as the biggest risk and requires the screen to stay awake while foregrounded.

## Decision
We will run the camera session, encoder, muxer, audio capture, and the HTTP/WebSocket server inside a single foreground service with types `camera|microphone`, started from the activity while it is visible. The service owns a `LifecycleOwner` to which the CameraX use cases are bound (ADR-0002); the activity only attaches and detaches its `PreviewView` surface. The activity is a thin client of the service; it can be destroyed and recreated without affecting a recording or connected browsers. The service holds a `PARTIAL_WAKE_LOCK` and a `WIFI_MODE_FULL_HIGH_PERF` Wi-Fi lock while a browser is connected or a recording is running. The phone screen stays awake only while the activity is visible; the user may lock the phone once the browser is connected, and recording continues.

PRD 6.9 is amended to: "On Android, recording and remote control continue with the screen locked once started from the app. On iOS the app must stay in the foreground."

## Options Considered

### Option A: Activity-only, screen always on (PRD as written)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | High: any interruption (call, notification, timeout) kills the take and the server |
| Effort | Low |
| Reversibility | High |

**Pros:** Simplest lifecycle.
**Cons:** Fragile against exactly the interruptions a ten-minute take invites. Screen contributes to thermal load. Doze can throttle networking.

### Option B: Foreground service owns capture and server (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Medium: service binding, notification, permissions |
| Risk | Low; this is the platform-sanctioned pattern |
| Effort | Medium |
| Reversibility | Medium |

**Pros:** Survives activity destruction, screen lock, and calls (audio focus permitting). Screen-off recording removes a heat source. Persistent notification doubles as an unmistakable "recording" indicator.
**Cons:** Android 14 requires the service to be started from the foreground and enforces type permissions; a Play Store declaration is required for the camera foreground-service type.

### Option C: Service for the server only, activity for capture
**Pros:** Smaller service.
**Cons:** The two halves have different lifetimes, which is the original problem in a new shape.

## Trade-off Analysis
Option B costs a notification and a permission declaration and removes the largest class of mid-take failures. The thermal benefit of allowing screen-off recording is a bonus that directly targets the biggest named risk.

## Consequences
- Easier: recordings and browser sessions survive interruptions; configuration changes and process recreation of the activity are harmless.
- Harder: Play Console foreground-service type declaration; the "connected clients" count and warnings must be visible in the notification, not only on screen.
- Revisit when: an OEM is found to stop camera foreground services on screen-off (log it per device in the capability report, ADR-0011).

## Action Items
1. [ ] Amend PRD 6.9 as stated above.
2. [ ] Phase 0: verify a 10-minute screen-off 4K30 recording completes on both reference devices and compare peak temperature with screen-on.
3. [ ] Design the notification content: recording state, elapsed time, connected clients, stop action.
