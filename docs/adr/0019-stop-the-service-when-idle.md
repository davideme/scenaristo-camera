# ADR-0019: Stop the capture service when the user leaves and nothing is using it

**Status:** Proposed
**Date:** 2026-09-04
**Deciders:** Davide Mendolia
**PRD sections:** 6.8, 6.9
**Related ADRs:** [ADR-0003](0003-foreground-service-for-capture-and-server.md), [ADR-0006](0006-local-web-server.md)

## Context

ADR-0003 put capture and the web server in a foreground service so a take survives the screen locking, an incoming call, or the activity being destroyed. It answered "how does a recording stay alive", and it never answered "when does any of this stop".

Nothing stops it. `rememberCaptureService` unbinds when the activity goes away and deliberately does not call `stopService`, with the comment that a recording must outlive the activity — true while recording, and the reason the service is still holding the camera, a wake lock, a Wi-Fi lock and an open HTTP server hours after the user last thought about the app. Phase 0 measured `PowerManager` reporting thermal `SERIOUS` while merely previewing, before any recording (#23), so idle is not free.

There is a trap in the obvious rule. "Stop when no remote is connected and nothing is recording" cannot be the whole condition, because **the first remote can never connect to a server that is not running**: the user sets the phone down, walks to the laptop, and types an address that stopped answering. The same argument rules out the screen turning off as a shutdown trigger — PRD 6.8's whole flow is that the phone sits there while the user is somewhere else, and ADR-0003 exists precisely so that a locked screen changes nothing.

So the signal cannot be "is anyone using it". It has to be "has the user left".

## Decision

We will stop the service when **all three** are true:

1. The activity has been **destroyed** — back, swipe away from recents, or the system reclaiming it — and not merely stopped. A screen turning off, a phone put face down, or the user switching apps for a moment does not qualify.
2. No recording is running.
3. No remote is connected.

Implemented as the unbind path: the last client unbinding is the activity being destroyed, so the service checks 2 and 3 there and stops itself after a short grace period. The grace period exists so that a rebind — a configuration change, or the activity being recreated — cancels the shutdown instead of racing it.

We will also give the user an explicit way to stop it, as a notification action, because UI-7's security copy already promises one: *"Turn the server off when you are done."* An automatic rule the user cannot see is not an answer to a security consequence they have just been told about.

Recording and remotes both **keep it alive by themselves**. Neither is a reason to keep it alive once both are gone and the user has left.

## Options Considered

### Option A: Stop on activity destroy when idle, with an explicit stop action (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Low: one lifecycle callback, one guard, one notification action |
| Risk | Low: the conditions are the ones already used for the wake locks |
| Effort | Hours |
| Reversibility | High; it is a guard in one method |

**Pros:** Leaving the app releases the camera, the locks and the port. The connect flow is untouched, because leaving the app is exactly what the user does *not* do while connecting a laptop. The user gets a visible off switch for the open-LAN consequence.
**Cons:** A user who leaves the app and comes back pays a camera rebind. Nothing stops the service while the app sits open and forgotten in the foreground.

### Option B: Stop when idle for N minutes, regardless of the activity

| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | **High** |
| Effort | Hours |
| Reversibility | High |

**Pros:** Also catches the app left open and forgotten.
**Cons:** Breaks the product's main flow. The gap between setting the phone down and typing the address into a laptop is exactly an idle period with no remote connected, and any timeout short enough to be useful is short enough to lose that race. A user who takes too long to walk to their desk finds a dead address and no explanation.

### Option C: Stop when the screen turns off and nothing is recording

**Pros:** Aggressive about battery.
**Cons:** Contradicts ADR-0003, whose entire premise is that the screen is not part of the session, and kills the server in the common case of a phone lying face down on a tripod waiting for a laptop.

### Option D: Leave it running (today's behaviour)

**Pros:** No work; no rebind cost; no risk of stopping something the user wanted.
**Cons:** The camera, two locks and an open port stay held indefinitely after the user has finished. On a device already reporting thermal `SERIOUS` while idle, that is a real cost paid for nothing.

## Trade-off Analysis

B and C are the two rules that sound right and break the product, both for the same reason: they treat "nobody is using it" as "nobody wants it", and the interval between those two states is precisely PRD 6.8's setup flow. A distinguishes them with a signal that is unambiguous — the user closed the app — at the cost of not catching an app left open in the foreground, which is a case the user can see and act on because the screen is in front of them.

The rebind cost of A is real but bounded, and it is paid only by someone returning to an app they had closed, who is already expecting to wait for a camera.

## Consequences

- Easier: leaving the app releases the camera, the wake lock, the Wi-Fi lock and the port. The idle thermal load of #23 stops accruing when nobody is there.
- Harder: two lifecycle states now matter where one did, and "stopped" becomes a state the phone UI may have to explain on return.
- The explicit stop action makes UI-7's security sentence actionable rather than advisory.
- Revisit when: a user reports losing a connection they were in the middle of making, or when PRD 6.11's pairing check lands and changes what an idle server costs.

## Action Items

1. [ ] Implement the unbind guard and the grace period.
2. [ ] Add the notification stop action, and make UI-7's sheet point at it.
3. [ ] Confirm on the reference device that a recording started from a remote survives the activity being swiped away (ADR-0003's promise, and the case this must not break).
4. [ ] Measure the rebind delay on returning to a stopped service, and decide whether the phone UI needs to say anything while it happens.
