# ADR-0025: Run the camera only while something is watching it

**Status:** Proposed
**Date:** 2026-09-07
**Deciders:** Davide Mendolia
**PRD sections:** 6.8, 6.9, 8-Q4, 9
**Related ADRs:** [ADR-0003](0003-foreground-service-for-capture-and-server.md), [ADR-0008](0008-preview-transport.md), [ADR-0018](0018-preview-tap-for-metering-and-preview-frames.md), [ADR-0019](0019-stop-the-service-when-idle.md)

## Context

ADR-0003 put capture and the server in a foreground service so a take survives the screen locking.
ADR-0019 asked when all of that stops, and answered for one case: the user leaves the app entirely.
Neither asked the narrower question of whether the camera should be *running* at any given moment,
and the code answers it in the most expensive way available — `CaptureService.onCreate` binds
CameraX and nothing on the production path ever unbinds it. There is no `unbind` or `unbindAll`
outside the diagnostic lens sweep.

Two things follow, and both cost power for nothing.

**The camera runs while the app is backgrounded.** The activity is deliberately decoupled from the
service — `rememberCaptureService` keys its effect on the context rather than on a lifecycle — so
backgrounding the app does nothing at all. ADR-0019 does not catch this, and says so on purpose:
its shutdown is the activity being *destroyed*, and its comment is explicit that "a screen turning
off or a phone put face down must not reach here." That leaves the exact case a talking-head rig
lives in: the phone is on a tripod, the display has timed out, and no browser has connected yet.
The service is correctly still alive. The camera, the ISP, the GL tap and the JPEG encoder are all
running for an audience of nobody.

**The preview is encoded for nobody.** `onTapFrame` compresses every tapped frame to JPEG
unconditionally, and the only consumer is `/preview.mjpg`. With no browser attached that is a full
`Bitmap.compress` per frame, paid at the tap's rate, thrown away.

PRD section 9 names thermal throttling at 4K30 as the single biggest technical risk, and #23
measured a 10:42 take reaching Android's `MODERATE` after eight minutes. That measurement is about
*recording*, and the honest reading of it is narrow: the same issue also recorded that a cool phone
reads `THERMAL_STATUS 0` while merely previewing, and corrected an earlier claim that idle preview
was alarming. So the case for this ADR is **battery and held hardware**, not heat. An idle bound
camera is a sensor and an ISP powered up, a GL thread drawing, an encoder compressing, and a camera
privacy indicator lit, on a device the user believes is doing nothing.

PRD 6.8 constrains the answer: "recording is always full resolution and frame rate regardless of
preview quality or whether a browser is connected", and the acceptance criterion that the phone
keeps recording when the laptop's Wi-Fi drops. Nothing here may touch a running take.

## Decision

We will run the camera only while something is watching it, at two levels.

**The preview encoder** runs only while at least one browser is pulling `/preview.mjpg`. The
metering tap keeps running regardless, because the phone's own exposure and warnings do not depend
on anyone watching. When the last viewer leaves, the last encoded frame is dropped, so the next
viewer waits a tenth of a second for a real frame rather than being shown a stale one as if it were
live.

**The camera itself** is bound while any of three things is true — a recording is running, the
phone's activity is visible, or at least one browser is attached, counting both a `/ws` client and
an open `/preview.mjpg` stream — and is unbound otherwise, after a grace period. It rebinds the
moment any of the three becomes true again. That predicate is deliberately ADR-0019's own idle test
(`recording || clients > 0`) with one term added, applied at a different level: ADR-0019 stops the
*service*, this releases the *camera*.

**The server is not touched.** The port stays open, the notification stays up, the state document
keeps ticking, and ADR-0019 keeps sole ownership of when the service stops. No protocol change:
`PROTOCOL_VERSION` stays 2, no new command, no new state field. A browser wakes the camera by
connecting, which is what a browser does anyway.

No PRD text is amended.

### Why this is not the rule ADR-0019 rejected

ADR-0019 considered and rejected "stop when the screen turns off" as its Option C, because it
"contradicts ADR-0003, whose entire premise is that the screen is not part of the session, and
kills the server in the common case of a phone lying face down on a tripod waiting for a laptop."
That objection is exactly right, and it is fatal to any rule that takes the server down with the
screen. It does not reach this one, because **only the camera sleeps**. The laptop that walks up
five minutes later connects to a server that is still answering; the connection itself is the wake
signal; the preview arrives. The trap ADR-0019 named — that the first remote can never connect to
a server that is not running — is avoided by never stopping the server.

ADR-0019's own Consequences name the gap this fills: "Nothing stops the service while the app sits
open and forgotten in the foreground." This does not close that gap either, but it removes the
larger neighbouring one, and it lands directly on ADR-0019's open action item 4, "measure the
rebind delay on returning to a stopped service".

## Options Considered

### Option A: Release the camera when nothing is watching; keep the server up (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Medium: one state machine, one visibility signal, careful teardown |
| Risk | Medium: rebinding from the background is a platform behaviour we depend on |
| Effort | Days |
| Reversibility | High; it is a gate around an existing bind call |

**Pros:** Catches the case ADR-0019 deliberately excludes, which is the case a tripod rig is
actually in. The connect flow is untouched because the server never goes down. The encoder half
helps even while the app is foregrounded.
**Cons:** A wake costs a camera rebind, so the first frame after a gap is not instant. Teardown and
rebind is a new lifecycle with real failure modes — a leaked GL texture, a stale surface request, a
lost white balance — that a bind-once service never had.

### Option B: Leave it running (today's behaviour)

| Dimension | Assessment |
|---|---|
| Complexity | None |
| Risk | None |
| Effort | None |
| Reversibility | — |

**Pros:** No rebind latency ever; no new failure modes; the preview is always instant.
**Cons:** The camera, the ISP, the GL thread and the encoder run indefinitely while the phone sits
backgrounded on a tripod with nobody connected. The privacy indicator stays lit on a device the
user thinks is idle.

### Option C: Drop the preview frame rate when nothing is watching, but stay bound

| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | Low |
| Effort | Hours |
| Reversibility | High |

**Pros:** Keeps the wake instant, since the camera never closes. Cheap to build. PRD 8-Q4 already
contemplates degrading the preview when hot, so the machinery is wanted anyway.
**Cons:** Saves the encoder and almost nothing else. The sensor and ISP stay powered, which is the
dominant cost, and the privacy indicator stays lit. It optimises the part that was already cheap.

### Option D: An explicit sleep/wake command in the protocol, with a button in the remote

| Dimension | Assessment |
|---|---|
| Complexity | Medium-high: protocol, fixtures, generated TypeScript, web UI |
| Risk | Low |
| Effort | Days |
| Reversibility | Medium; a protocol addition is hard to withdraw |

**Pros:** A browser tab left open all day cannot pin the camera on, which is the failure mode
Option A still has. The user is told what is happening rather than inferring it.
**Cons:** Buys control at the price of friction, and the friction lands on the common case: a
producer who opens the page expects to see the shot, not to ask for it. Every wake becomes a click.
Rejected for v1.

**Measured on the reference device, 2026-09-07.** The forgotten tab is not hypothetical: during the
first session of testing Option A, a single remote page left open on the MacBook held the camera
bound for the whole session, through every backgrounding and screen lock. The count was confirmed
accurate rather than leaked — three connect-and-drop cycles from a second client each returned to
the same total, so abrupt disconnects are reaped correctly. Davide was shown this and **reaffirmed
Option A's rule on 2026-09-07**: a connected browser keeps the camera, and the tab is the user's to
close. That measurement is the concrete form of this ADR's revisit trigger.

## Trade-off Analysis

The forces are PRD 6.8's setup flow, which must not be broken, and the cost of held hardware, which
is real but not urgent. Option B is the honest baseline and loses on the second. Option C is
tempting because it is safe, but it spends effort on the encoder while leaving the sensor and ISP —
the actual cost — powered, so it buys the smaller half of Option A for most of the risk-free-ness of
Option B.

The genuine contest is A against D, and it is a judgement about who decides. D is more correct in
the limit: the only signal that truly means "I want to see the picture" is someone saying so.
A infers it from connecting, which is right almost always and wrong for a tab left open overnight.
A is chosen because the wrong case is bounded and visible — a browser tab the user can close —
while D's cost is paid on every single use by every user. If a forgotten tab turns out to matter,
D is a strictly additive follow-up: a command and a button can be added to A without unpicking it.

The residual risk in A is not the policy but the mechanics. Binding once and never unbinding hides
a class of bugs — leaked textures, stale surfaces, settings that live only in a change-detector's
cache — that only a second bind exposes. That is where this change can go wrong, and it is where
the verification below is aimed.

## Consequences

- Easier: a phone left backgrounded on a tripod stops holding the camera, and the privacy indicator
  goes out when the app genuinely is not looking. The preview encoder costs nothing when unwatched,
  foregrounded or not.
- Harder: two bind states where there was one. Everything built once at bind time is now built
  repeatedly, so anything that leaked or went stale across the lens sweep's single rebind now does
  so per wake. Bind failure becomes a routine path needing retry rather than a one-off that could
  be left in a notification string.
- Harder: a wake costs a camera rebind. **Measured at 1.73 s** from opening `/preview.mjpg` to the
  first complete JPEG frame, on a Pixel 10 whose screen was off and keyguard up (2026-09-07). That
  is slower than the 0.4–1 s budgeted, and it includes TCP connect and the first encode as well as
  `CameraDevice.open`. It is short enough that the existing null-frame poll covers it without a
  browser seeing an error, and long enough to revisit if a producer reports the preview feeling
  slow to appear — at which point the additive `camera` state field this ADR omits becomes worth
  adding, so the remote can say "waking" rather than showing an empty frame.
- The background rebind depends on the process capability granted when a `camera`-type foreground
  service is started while visible, and held for as long as that service instance runs. A
  `START_STICKY` restart while backgrounded would lose it. Standby makes such a kill less likely
  rather than more, but it is now a reachable state.
- Revisit when: the wake latency is measured on the Pixel 10; or a forgotten browser tab becomes a
  complaint rather than an observation, which is the trigger for Option D. The observation itself
  has already happened (see Option D) and was judged acceptable.

## Action Items

1. [x] Gate the preview encoder on an attached viewer, and drop the stale frame when the last one
   leaves.
2. [x] **Verify on the Pixel 10 that the camera rebinds while the app is backgrounded and the
   screen is locked.** Done 2026-09-07 — see Verification below. This was the load-bearing one.
3. [x] Implement the bind/unbind state machine with a grace period, sharing a mutex with recording
   start so a take can never race a release.
4. [ ] Confirm white balance, shutter lock, grid and rotation survive a standby cycle. They are
   held in change-detector caches that a rebind does not reset, so the default is that they are
   silently lost. Partially exercised: a `shutterLock` of 100 and 3200 K were applied and observed
   taking effect (`shutterHz` 50 → 100, ISO recompensating 1578 → 3155), but the phone was not
   observed completing a sleep/wake cycle with them set.
5. [ ] Exercise `record → standby → wake → record` several times on the reference device; a
   `Recorder` rebound after finalising is the least certain part of the platform behaviour here.
6. [ ] Measure 30-minute backgrounded idle battery drain, before and after. The latency half is
   done and recorded in Consequences.
7. [ ] Confirm the phone still answers on the LAN after a long standby; if a dozing phone cannot
   accept the connection, a wake lock is needed in standby and ADR-0003's lock rule — which the
   code currently does not implement as written — needs revisiting with it.

## Verification

Pixel 10, Android 17 (`CP2A.260805.005`), back camera, 2026-09-07. Evidence about **one handset**
(ADR-0017); a pass here is not an Android-wide claim.

The problem, first, since it is what this ADR exists for: with the app backgrounded and the screen
off, no recording and no browser, `dumpsys media.camera` showed `Camera ID: 0` still open and held
by `com.scenaristo.camera`. That is the behaviour being removed.

With the change, one full cycle observed end to end while `mAwake=false` and
`isKeyguardShowing=true` throughout:

| Time | Event |
|---|---|
| 12:08:06 | `nothing watching; releasing the camera` — `Active Camera Clients` drops to none |
| 12:08:49 | `/preview.mjpg` opened from the MacBook → `camera wanted: a browser is pulling the preview` → `binding the camera` |
| 12:08:51 | first complete JPEG frame, 1.73 s after the request |
| 12:09:01 | stream closed → `nothing is watching; releasing in 15000ms` |

So the camera is released when nothing is watching, and a browser arriving at a server that never
stopped brings it back **without the phone being unlocked or woken** — which is the flow PRD 6.8
describes and the thing ADR-0019's Option C could not have delivered.

The preview streamed 143 frames in 12.3 s (11.6 fps against the 15 fps cap). That shortfall is a
separate, already-known defect in the stream's pacing and not a consequence of this change.

Two supporting observations: the process holds the foreground-service camera capability in exactly
that state (`curProcState=4`, `curCapability=-CMNFUATI`), which is what makes the background reopen
legal; and the WebSocket client count is reaped correctly on abrupt disconnect, so a dropped
browser cannot pin the camera forever.

Items 4, 5 and 7 remain open. The reference phone is shared with other work, and a second session
holding the app in the foreground makes `uiVisible` legitimately true — which is indistinguishable
from a stuck flag without coordinating the device, so those runs are better done on a quiet phone.
