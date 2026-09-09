# ADR-0032: Minimum Android is 16 (API 36); iOS 16 unchanged

**Status:** Proposed
**Date:** 2026-09-09
**Deciders:** Davide Mendolia
**PRD sections:** 6.10, 8 decision log, 9
**Related ADRs:** Supersedes [ADR-0012](0012-minimum-os-versions.md). [ADR-0011](0011-per-lens-capability-gating.md), [ADR-0014](0014-build-toolchain.md), [ADR-0017](0017-phase-0-verification-matrix.md), [ADR-0033](0033-native-shutter-priority-on-android-16.md)

## Context

ADR-0012 set the Android floor at 14 (API 34) and recorded honestly that no capture API in the
design needs it: the floor buys a small test matrix and recent HALs, and costs installed base. It
also wrote down the ordering that governs this decision:

> Raising a floor after launch is painful; lowering it is cheap. Starting high during Phases 0–3 and
> lowering to API 31 for the public beta if Play Console data shows a large excluded share is the
> reversible ordering.

Nothing has shipped. We are inside the window that reasoning describes, and three things now argue
the floor should be higher rather than lower for v1.

**Two capture APIs the product wants exist only at API 36.**
`CONTROL_AE_PRIORITY_MODE` (ADR-0033) is the shutter-priority mode PRD 6.3 says does not exist, and
`COLOR_CORRECTION_MODE_CCT` with `COLOR_CORRECTION_COLOR_TEMPERATURE` (ADR-0034) is the direct Kelvin
API PRD 6.4 says does not exist. At an API 34 floor, each of them can only be reached behind a
version check, which means a second behaviour on the same code path — the cost that dominates both
of those ADRs. At an API 36 floor the version fork disappears entirely and only the *capability*
fork remains, which is ADR-0011's existing per-lens pattern and is already built.

**The reference matrix and the floor finally agree.** ADR-0017 accepted a one-device matrix and
listed the gaps it leaves; one of them is verbatim:

> ADR-0012's Android 14 (API 34) floor has no device that runs it.

At an API 34 floor, every measurement Phase 0 takes is taken two OS versions above the floor, and
the floor's behaviour is inferred rather than observed. At an API 36 floor the reference Pixel 10
*is* the floor, and that gap closes without buying a phone.

**Play's targeting rule is not the reason, and should not be read as one.** From 31 August 2026 new
apps and updates must *target* API 36 to be submitted to Google Play. That is `targetSdk`, which
ADR-0014 already sets to 36 on `compileSdk 37`, and Play does not constrain `minSdk`: an app
targeting Android 16 installs and runs on Android 14 perfectly well. The requirement is satisfied
either way. It is recorded here only so nobody later reconstructs it as the cause of this decision.

The cost is real and is the whole of the case against. Android 16 shipped in 2025; the share of
active devices it reaches is smaller than API 34's by some margin, and this ADR does not guess at
the number. ADR-0012's action item 2 — pull the Play Console device catalogue before public beta —
survives this decision unchanged and becomes more load-bearing, not less: it is now the measurement
that decides whether the floor comes back down before the beta opens, and it now has to report on
API 34 and 35 as well.

## Decision

We will set the Android minimum to **API 36 (Android 16)**. `minSdk` moves from 34 to 36 in
`android/gradle/libs.versions.toml`; `targetSdk` stays 36 and `compileSdk` stays 37 (ADR-0014,
unchanged). The iOS floor stays at **iOS 16**, for the reason ADR-0012 gave and that nothing here
touches: it excludes only devices without the required hardware encoders.

**This supersedes ADR-0012.** Its Android decision is replaced; its iOS decision, its rationale for
why a high floor is the reversible direction, and its Play Console measurement are carried forward.

**PRD amendments.** Four passages state the old floor and become:

- Summary table, "Platforms": *Android first (min Android 16), then iOS (min iOS 16)*.
- 6.10: *Minimum OS (decision 2026-09-09, ADR-0032): Android 16 (API 36), iOS 16.* The sentence that
  follows — that the OS floor does not guarantee manual controls, which depend on per-lens flags — is
  unchanged and matters more than before; see below.
- 8 decision log, "Minimum OS": Android 16 (API 36), iOS 16, with this ADR as the rationale and
  ADR-0012 as the superseded entry.
- 9 Phase 1: *6.1–6.7, 6.9, 6.10 on Android 16+*.

**The floor guarantees the keys exist, not that any camera declares them.** This is the sentence
that keeps ADR-0033 and ADR-0034 honest, and it is the same distinction PRD 6.10 already draws for
`MANUAL_SENSOR`. A phone updated to Android 16 can still report neither `CCT` in
`COLOR_CORRECTION_AVAILABLE_MODES` nor anything useful in `CONTROL_AE_AVAILABLE_PRIORITY_MODES`,
because those are driver declarations and drivers do not improve with an OS update. Every capability
gate ADR-0011 defines stays exactly as it is, and neither of the two ADRs above may be simplified
into "API 36, therefore available".

## Options Considered

### Option A: API 36 / iOS 16 (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Lowest: no version forks anywhere in the capture path |
| Risk | Installed base, unmeasured, and the measurement is scheduled |
| Effort | One line, plus the PRD passages |
| Reversibility | High while nothing has shipped; low after the first public release |

**Pros:** ADR-0033's and ADR-0034's version forks disappear before they are written into code; the
reference device becomes the floor, closing an ADR-0017 gap; one foreground-service path and one
codec story, as ADR-0012 wanted, only more so.
**Cons:** The largest excluded installed base of any option, chosen before the number is known.

### Option B: Keep API 34 and version-fork the two features

| Dimension | Assessment |
|---|---|
| Complexity | High: two exposure behaviours or two white-balance rungs, gated on version |
| Risk | High: the fallback path is the one no device in the matrix runs |
| Effort | Weeks, spread across two features |
| Reversibility | Medium |

**Pros:** Keeps every Android 14 and 15 device.
**Cons:** This is the cost ADR-0033 and ADR-0034 each identify as decisive against adopting their
key. Paying it twice, for a floor whose excluded share nobody has measured either, is the worst of
both.

### Option C: Keep API 34 and skip both features

**Pros:** Cheapest today; the app already works this way.
**Cons:** Leaves PRD 6.4's Kelvin promise resting on an uncalibrated curve that has never been built
(#24), on a device that offers the exact key. Declines a platform capability to protect a floor
nobody chose for a stated reason.

### Option D: API 35 (Android 15)

**Pros:** A middle floor; picks up `FLASH_STRENGTH_LEVEL` and little else this product wants.
**Cons:** Neither API 36 key is available, so it buys nothing for ADR-0033 or ADR-0034 while still
excluding Android 14. It is the floor that costs something and returns nothing.

## Trade-off Analysis

The forces are the ones ADR-0012 named, pointing the other way once two API 36 keys became relevant.
Its own argument decides it: high first and lower later is the cheap direction, and lower first and
raise later is the expensive one. We are pre-release, which is the only time this move is free.

Against Option B, the argument is that a version fork is precisely what ADR-0033 and ADR-0034 each
refuse to pay for on its own, and paying it twice does not become cheaper. Against Option C, that
the reference device offers a correct Kelvin API while the PRD promises ±300 K from a curve nobody
has measured.

The honest weakness is that this is a decision about installed base taken without the installed-base
number, which is exactly what ADR-0012 declined to do in the other direction ("to be measured, not
assumed"). It is defensible only because it is reversible until the first public release and because
the measurement is already scheduled ahead of that release. If the Play Console data comes back
badly, the floor comes down and the version forks come back with it — and that is a worse product
than this one, arrived at with evidence instead of before it.

## Consequences

- Easier: no `Build.VERSION` gates in the capture path; ADR-0033 and ADR-0034 reduce to per-camera
  capability checks, which ADR-0011 already does.
- Easier: the reference Pixel 10 tests the floor rather than something two versions above it
  (ADR-0017).
- Harder: the excluded installed base is the largest of any option, and unknown until the Play
  Console pull.
- Harder: the store listing and the PRD must say Android 16, and lowering the floor after release is
  the painful direction ADR-0012 warned about.
- Neutral: nothing in the current code is version-gated except `Theme.kt`'s dynamic-colour check
  (API 31), which stays correct and simply never takes its false branch.
- Revisit when: the Play Console device-catalogue pull scheduled before public beta (inherited from
  ADR-0012 action 2) shows a material share of API 34–35 phones with a `MANUAL_SENSOR` main camera.
  The response is to lower the floor **and** accept the version forks ADR-0033 and ADR-0034 avoid,
  which is a decision with its own ADR, not a build change.

## Action Items
1. [ ] Set `minSdk = 36` in `android/gradle/libs.versions.toml` and update ADR-0014's SDK table row,
       which cites ADR-0012 for that number.
2. [ ] Amend the four PRD passages listed under Decision, citing this ADR.
3. [ ] Set ADR-0012's status to `Superseded by ADR-0032` and carry its action item 2 forward here.
4. [ ] Before Phase 3: pull the Play Console device catalogue for API 34–35 phones with a
       `MANUAL_SENSOR` main camera and record the excluded share in this ADR.
5. [ ] Update ADR-0017's gap list, whose third item is the API 34 floor that no device runs.
