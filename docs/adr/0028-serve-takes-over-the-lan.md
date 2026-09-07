# ADR-0028: Serve finished takes over a second HTTP route, refused while recording

**Status:** Proposed
**Date:** 2026-09-07
**Deciders:** Davide Mendolia
**PRD sections:** 6.11, 6.8 (security), 6.7
**Related ADRs:** ADR-0006, ADR-0007, ADR-0008, ADR-0009, ADR-0013, ADR-0019, ADR-0020, ADR-0025, ADR-0026

## Context

PRD 6.11 promises: *"Download the last recording (or any recording from this session) from the web UI."* Nothing implements it. Today a take is reachable only with `adb pull`, because ADR-0020's default destination — the app's own external files directory — is invisible to the gallery and to MTP. That is the point of that default (a quiet neighbour that does not fill someone's photo roll), and it is also what makes this feature load-bearing rather than convenient: for the default destination there is no other way off the phone.

The forces:

- **The remote already exists.** ADR-0006 runs a Ktor CIO server and ADR-0008 established the pattern for bulk media — a second HTTP route beside `/ws`, which ADR-0007 keeps to JSON text frames. Nothing new has to be invented for the transport.
- **A take is large.** PRD 6.7 measured 33–36 Mbit/s at 4K30 on the reference device: roughly 250 MB per minute, so a ten-minute take is ~2.5 GB. That number decides almost everything below.
- **The network is not stable.** ADR-0026 closes the port whenever the phone leaves a local network. For a WebSocket that costs a reconnect the browser would have done anyway; for a 2.5 GB transfer it costs the transfer.
- **There is no authentication.** PRD 6.8 decided open LAN access for v1: *"Any client on the local network that can reach the URL can view the preview and control the camera."* The pairing check is P1 and unbuilt.
- **Recording is the thing that must not break.** PRD 6.1's frame rate is the product's central promise, and `spec-chapter-markers.md` §3 already named serving 4K back during a take as competing for the thermal headroom that is the biggest technical risk.

## Decision

We will serve each finished take from **one new HTTP route on the existing server**, `GET /takes/<name>.mp4`, and **refuse every download while a take is recording**.

The take list travels on the existing state document as `State.takes` (additive, ADR-0007), rather than behind a JSON endpoint of its own. That is not for convenience: the browser must disable the download links while recording, and a list fetched at one moment against a `recording` flag from another would flicker and occasionally offer a link the server refuses. **In one document the two cannot disagree.** The list is derived from the takes directory on each rescan, capped at the newest ten, so it is never stale and needs nothing from the recorder.

Specifically:

- **The route refuses in this order:** a name that is not PRD 6.7's shape is 404; a take in progress is **409, before anything touches the disk**; no such take is 404.
- **The 409 is load-bearing, not a courtesy.** A file's existence cannot stand in for "this take is finished": the recorder writes progressively, which is precisely what makes #17's force-killed take playable. Without the refusal the route would stream a file still being written and hand the user a take shorter than the one they watched being made. It carries the IO and thermal argument as well.
- **`Range` is supported** — a single range, including suffix ranges — with 206, `Content-Range`, and 416 for a start at or past the end. A genuine multi-range request is answered whole rather than merged into the span covering it, which RFC 9110 permits and which avoids reporting a wrong answer as a right one. Parsed with `parseRangesSpecifier` from `ktor-http`, already on the classpath.
- **A validator is sent** — a strong `ETag` of name and length, plus `Last-Modified` semantics via the list's `recordedAtMs`. Without one, no browser download manager offers resume, and the range support would be reachable only from `curl`. `Cache-Control` is `private`, deliberately **not** the `no-store` the preview route uses, which would forbid the resume this exists for.
- **`:server` never sees a path.** It holds a `Takes` interface taking a name and an offset. Resolving the name is `:app`'s job, which is what makes the name a lookup key rather than a path fragment. Phase 4's iOS server implements the same interface (ADR-0013).
- **A download keeps the service alive** — a third term in ADR-0019's shutdown predicate — and holds the wake and Wi-Fi locks. It does **not** bind the camera (ADR-0025).

### PRD text to amend

PRD 6.8, Security, first bullet. Today:

> **v1: open LAN access** (decision 2026-09-03). Any client on the local network that can reach the URL can view the preview and control the camera.

Amended to add: *"…and download any take the app has recorded."*

PRD 6.8's acceptance criteria gain: *given a request to a take's URL whose remote address is not private or whose `Host` is not an IP literal, then the server answers 403*; and *given a take is recording, when any take is requested, then the server answers 409.*

### The exposure this creates, stated plainly

This is the decision being asked for, and it should not be buried. Before this change, an unauthenticated stranger on the LAN could watch the room and start or stop a recording. After it, they can **walk off with the finished footage** — every take the app has recorded, at full quality, over a link with no authentication of any kind.

That is materially more than what PRD 6.8 signed up for, and the honest reasons to accept it anyway are: the exposure is bounded by the same LAN the preview already trusts; the preview arguably leaks more of the *present* than the archive leaks of the past, since a stranger who can watch the room live can also record it; and the feature is unusable without it, because the default storage destination has no other route off the phone. What makes it *visible* is PRD 6.8's existing device: the phone shows how many browsers are attached, and a download can only come from a client that is already counted. No new phone-side indicator is added (Davide, 2026-09-07).

## Options Considered

### Option A: A second HTTP route, refused while recording (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Low-Medium; one route, one pure range decision, one counter |
| Risk | Medium — it is the first route that serves recorded content to an unauthenticated peer |
| Effort | Low |
| Reversibility | High; deleting the route removes the capability entirely |

**Pros:** Follows ADR-0008's precedent exactly. Keeps `/ws` to JSON. Resumable, so ADR-0026's dropped network costs the remainder rather than the file. The refusal while recording removes the contention risk rather than measuring it.
**Cons:** Unauthenticated. A user who wants a take *during* a long recording cannot have it.

### Option B: Gate downloads behind the P1 pairing check

| Dimension | Assessment |
|---|---|
| Complexity | High; the pairing check does not exist |
| Risk | Low once built |
| Effort | High |
| Reversibility | Medium |

**Pros:** The only option that actually answers the exposure above, and PRD 6.8 already reserves the shape for it — ADR-0006 put a `clientId` cookie and a per-client `role` in the protocol for exactly this.
**Cons:** Pairing is a P1 the PRD schedules for a later version, and building it here would mean this feature waits on a login flow, a phone-side confirmation UI, and a persistence story for remembered browsers. It would also gate the preview and the record button, which are far more used — so it is a change to the product's security posture as a whole, not a rider on a download route. **Rejected for now, not on the merits**: if Davide would rather this feature wait for pairing, that is the decision this ADR exists to surface.

### Option C: Serve takes during recording as well

**Pros:** No refusal to explain; the user gets a take the moment it exists.
**Cons:** A 2.5 GB sequential read and a Wi-Fi transfer alongside a 36 Mbit/s UHD write, on a device already measured reaching `MODERATE` thermal after eight minutes. It also has no way to serve a *complete* file for the take in progress, since the file is still being written. The cost is paid in the one place the product cannot afford it.

### Option D: Push takes somewhere (cloud, desktop companion)

PRD section 3 makes cloud upload and a backend explicit non-goals for v1, and section 6.12 lists both as P2 futures. Not available.

## Trade-off Analysis

Option A wins against Option B on scheduling rather than on security, and this ADR does not pretend otherwise. Pairing is the right answer to the exposure and it is a whole feature; blocking a Phase 3 P1 on an unstarted Phase 3 P1 trades one delay for another and leaves the default storage destination unreachable in the meantime. The mitigations that *are* available — LAN-only enforcement (ADR-0006), the port closed off-network (ADR-0026), the visible client count, and read-only access with no delete — are the ones already paid for.

Against Option C, the refusal while recording is the cheapest possible way to be certain: it removes the contention rather than measuring it, and it is one status code. The cost is a real workflow the product does not support — pulling a take mid-shoot — and Action Item 3 is what would let a later ADR relax it on evidence.

## Consequences

- **Easier:** A take reaches a laptop without a cable, which for ADR-0020's default destination was previously impossible. A dropped Wi-Fi link costs the remainder of a transfer, not the transfer. ADR-0020's revisit trigger — *"a download feature needs to read files back and the URI becomes load-bearing"* — is discharged: the URI did not become load-bearing at all, because the list is derived from the folder and the gallery destination is out of scope.
- **Harder:** The security story now has to talk about footage, not just control. `:server` has a second capture-side contract for Phase 4 to implement. The service and the locks have a new reason to stay up, which is a new way to keep a phone awake if the counter is ever unbalanced (it is clamped at zero for that reason).
- **Not addressed:** Gallery takes (`saveToGallery`) are not served; they are already in Photos and over USB. A take truncated by a crash is listed and downloadable, but a take whose *process died* is only found by the next scan. `HEAD` is not supported — `AutoHeadResponse` is a separate artifact — so `curl -I` answers 404. Two concurrent downloads are not serialised.
- **Revisit when:** the pairing check of PRD 6.8 is built, at which point this route should be behind it and this ADR superseded; or when Action Item 3 measures what a concurrent download actually costs a recording, which is what would justify relaxing the 409.

## Action Items

1. [ ] Amend PRD 6.8's security bullet and acceptance criteria as above.
2. [ ] Add the pairing check's `role` to this route first when it is built: a `viewer` should arguably not be able to download, even when a `controller` can.
3. [ ] Measure a full-speed download concurrent with a 4K30 take on the reference device, by temporarily lifting the 409, and record whether frames drop. Until that number exists, the 409 is a precaution and this ADR should not be read as claiming it is a measured necessity.
4. [ ] Confirm on a second OEM device when #29 widens the matrix; every number here is a Pixel 10's (ADR-0017).
