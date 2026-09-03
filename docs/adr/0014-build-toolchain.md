# ADR-0014: Pin the build toolchain and provision the Android SDK with the `android` CLI

**Status:** Proposed
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 6.1, 6.10, 8-Q7, 9 (Phase 0)
**Related ADRs:** ADR-0002, ADR-0009, ADR-0012, ADR-0015, ADR-0016

## Context

The repository was documentation-only: thirteen Accepted ADRs fixed the architecture, sixteen
issues carried the user stories, and no build existed. Every issue was therefore unpickupable,
because a contributor's first act would have been to invent a build, and each would have invented
a different one. `CLAUDE.md` requires an ADR for anything that "adds, removes, or replaces a
dependency, framework, build tool, or platform API family", which standing up a build does
several times over.

Two environment facts shape the decision more than the version numbers do.

**There is no classic Android SDK tooling on the reference machine.** No `sdkmanager`, no
`avdmanager`, no `adb`, no system `gradle`, no Android Studio. The only Android tooling installed
is Google's unified `android` CLI (1.0.16251017), and its SDK root was empty. Package ids are
path-style (`platforms/android-37.0`), not the classic `platforms;android-37`. Any setup
instruction beginning "run `sdkmanager`" is wrong here. GitHub Actions runners are the reverse:
they ship the classic tools and no `android` CLI. The two environments cannot share setup steps,
only Gradle tasks.

**AGP 9 is a different build tool from AGP 8**, not a version bump. Kotlin compilation is built
in (`kotlin-android` must not be applied and fails the build if it is); the legacy variant API is
gone; `targetSdk` now defaults to `compileSdk` rather than `minSdk`; `getDefaultProguardFile()`
accepts only the optimising file; `android.uniquePackageNames` is on; and the minimum Gradle
version climbs *within* AGP 9 (9.0 requires Gradle 9.1, 9.3 requires 9.5), so a minor AGP bump is
not wrapper-neutral.

ADR-0012 fixes `minSdk` at 34 and is silent on the other two SDK levels.

## Decision

We will pin the toolchain in `android/gradle/libs.versions.toml` and the Gradle wrapper:

| Component | Version | Why this one |
|---|---|---|
| Gradle | 9.7.1 | AGP 9.3+ requires ≥ 9.5.0; 9.7.1 was current and is verified working with AGP 9.4.0 |
| AGP | 9.4.0 | latest stable |
| Kotlin | 2.4.10 | latest stable; Kotlin 2.4 requires AGP ≥ 9.1.0, so these two are coupled and move together |
| Java toolchain | 17 | matches the bytecode target, so there is no target-mismatch class of failure |
| `compileSdk` | 37 | newest platform, so new APIs and deprecations are visible |
| `targetSdk` | **36, stated explicitly** | see below |
| `minSdk` | 34 | ADR-0012 |

**`targetSdk` is written out rather than inherited.** AGP 9 defaults it to `compileSdk`, so
leaving it unset would silently adopt every API 37 runtime behaviour change the moment
`compileSdk` moved — on an app whose entire risk surface is the camera, a `camera|microphone`
foreground service (ADR-0003), and a bound network socket (ADR-0006). Behaviour changes are opted
into deliberately, in their own commit, with a device check.

**Java 17 is the bytecode ceiling, not a conservatism.** Android 14 (API 34) accepts Java 17;
no Android release accepts Java 21. Toolchain, `sourceCompatibility`, `targetCompatibility` and
Kotlin's `jvmTarget` therefore all read 17. The Gradle *daemon* runs on the installed Temurin
21.0.1, which is unrelated and fine.

**The Android SDK is provisioned with the `android` CLI** and located through
`android/local.properties` (`sdk.dir`), which is git-ignored. Not `ANDROID_HOME`: `sdk.dir` is
per-checkout, survives a shell with no environment set, is what the `android` CLI itself writes,
and is what a CI runner overrides. Required packages are `platform-tools`,
`platforms/android-37.0`, `build-tools/37.0.0`.

**The build is derived from the `android` CLI's own template, not written from scratch.** The
reference project was generated with:

```
android create empty-activity --name "Scenaristo Ref" --minSdk 34 --output <scratch>
```

`empty-activity` is the only template the CLI offers (tagged `compose,activity,agp-9`) and it
produces a single-module `:app` project, so it cannot be used directly for the four-module layout
ADR-0002 requires. It is used as the authority for AGP 9 *idioms* and for boilerplate that is
tedious and error-prone to hand-write. Taken from it verbatim or with the package renamed:

- the `pluginManagement` / `dependencyResolutionManagement` blocks including the
  `content { includeGroupByRegex(...) }` filters, and the foojay resolver plugin;
- `gradle.properties`;
- `gradlew`, `gradlew.bat`, `gradle-wrapper.jar`;
- the `:app` module's launcher icons, `themes.xml`, `theme/{Color,Type,Theme}.kt`,
  `backup_rules.xml` and `data_extraction_rules.xml`, and the manifest's `icon`, `roundIcon`,
  `dataExtractionRules`, `fullBackupContent` and `windowSoftInputMode` attributes.

Deliberately **not** taken from it: Navigation 3 (`androidx.navigation3`), the demo
`DataRepository`/ViewModel scaffolding, and dynamic colour. The phone UI is one screen plus a
settings sheet (PRD 6.9), so a navigation library would be an unjustified dependency; and dynamic
colour would let the wallpaper retint a UI that frames a live camera preview and reports white
balance (PRD 6.4).

Where the template's pins are older than ours — it ships AGP 9.0.1, Kotlin 2.3.20, Compose BOM
2026.03.01 and `compileSdk` 36 — the newer versions in the table above win, and were verified by
a green `./gradlew build`. The template is an authority on shape, not on currency.

**The wrapper properties file is the one exception, written by hand.** With no system Gradle there
is nothing to run `gradle wrapper` with, so `gradle-wrapper.properties` is written for 9.7.1 with
the SHA-256 from services.gradle.org. The template's own must not be copied: it points at Gradle
9.1.0, which AGP 9.4 rejects during *configuration*, so a subsequent `./gradlew wrapper` upgrade
could never run. The jar is committed and validated in CI by `gradle/actions/wrapper-validation`.

**Configuration cache is on.** This constrains all future build logic in the repository: no
`Project` access at execution time.

**`web/` uses pnpm 10, not npm.** The version is pinned in `package.json` via `packageManager`, so
Corepack resolves it and CI, local machines and any future contributor share one resolver.
`pnpm install --frozen-lockfile` is the `npm ci` equivalent: it fails rather than silently
rewriting the lockfile when `package.json` has drifted. `package-lock.json` and `yarn.lock` are
git-ignored, because two lockfiles for one tree means two resolvers disagreeing about it.

*This amends a technical statement in Accepted ADR-0009*, whose Decision says "a Gradle `Exec`
task in `:app` runs `npm run build`". The text to amend is that phrase and the surrounding
`npm run build` references: read them as `pnpm run build`. ADR-0009's actual decision — one static
bundle, Vite + TypeScript + Preact, protocol types generated from `:domain`, no host coupling — is
untouched; only the command changes. The Phase 2 Gradle-to-npm bridge described below is
correspondingly a Gradle-to-pnpm bridge.

**Test frameworks are split by necessity, not preference:** `kotlin.test` in `:domain`'s
`commonTest` (the only multiplatform option), JUnit 4 in the Android modules (what AGP and
Compose testing assume).

**The Gradle-to-pnpm bridge is deferred to Phase 2.** ADR-0009 Action Item 3 prescribes an `Exec`
task with `preBuild.dependsOn` and `web/dist` as a resources source directory. AGP 9 defaults
`android.sourceset.disallowProvider` to true, so that now requires the `androidComponents`
Sources API. Wiring it during the bootstrap would put pnpm on the critical path of every Android
build and is a configuration-cache hazard, for no benefit until the web UI exists. `web/` builds
independently until then. This defers an ADR-0009 action item; it does not change its decision.

## Options Considered

### Option A: `android` CLI provisioning, committed wrapper, version catalog (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | Low — verified end to end on the reference machine |
| Effort | Low |
| Reversibility | High; the catalog is one file |

**Pros:** Works on the machine the project is actually developed on; `sdk.dir` keeps the SDK path
out of the environment; the catalog gives ADR-0002's CameraX pin a single enforceable home.
**Cons:** Local setup instructions differ from CI, which must be documented rather than papered
over. The `android` CLI is new and its behaviour may shift.

### Option B: Classic `cmdline-tools` and `sdkmanager`
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | Medium |
| Effort | Medium — an extra install of tooling that is already superseded locally |
| Reversibility | High |

**Pros:** Matches most documentation on the internet and what CI runners already have, so one set
of instructions would cover both.
**Cons:** Requires installing tooling the maintainer's machine deliberately does not have, in
parallel with the `android` CLI that manages the same SDK root. Two managers over one directory
is a worse failure mode than two sets of instructions.

### Option C: Containerised builds
| Dimension | Assessment |
|---|---|
| Complexity | High |
| Risk | Low for reproducibility, high for the actual work |
| Effort | High |
| Reversibility | Medium |

**Pros:** Identical toolchain everywhere; the local/CI divergence disappears.
**Cons:** The work that matters is a physical phone attached over USB, which is exactly what a
container makes awkward. Reproducibility of the *build* was never the problem; reproducibility of
*device behaviour* is, and no container helps with it.

## Trade-off Analysis

The binding constraint is that this project's real verification is a Pixel and a Samsung on a
desk, so the toolchain's job is to get out of the way and be identical enough that a build failure
is never the interesting problem. Option A achieves that with the tooling already present, and
pays for it with a documentation obligation — one section of `CONTRIBUTING.md` — which is cheap
and honest. Option B pays with a second SDK manager over the same directory, and Option C pays
with friction on precisely the device workflow the project exists to exercise.

## Consequences

- Easier: `./gradlew build` works from a clean clone; the CameraX pin has one enforceable home;
  contributors and CI run identical Gradle tasks.
- Harder: `CONTRIBUTING.md` must keep two setup paths correct, and the local one will look wrong
  to anyone who has used Android tooling before. AGP 9's minimum Gradle moves within the 9.x line,
  so an AGP bump must check the wrapper too.
- Revisit when: AGP 10 ships; or the `android` CLI leaves preview or changes its package-id
  scheme; or a hosted device farm makes containerised builds worth reconsidering.

## Action Items

1. [x] Pin `androidx.camera:*` at 1.6.2 in the catalog with a comment pointing at ADR-0002's revisit (discharges ADR-0002 action item 1).
2. [x] Commit the wrapper and validate it in CI.
3. [x] Add a CI guard that fails if the CameraX pin changes or `Camera2Interop` appears outside `ManualControls`.
4. [ ] Phase 2: wire the web bundle into `:app` via the `androidComponents` Sources API (ADR-0009 action item 3).
5. [ ] Re-check the Gradle floor whenever AGP is bumped, including minor versions.
