# ADR-0016: Gate pull requests on compilation, host tests and repo invariants; verify devices by hand

**Status:** Proposed
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 6.1-6.7, 6.10, 9
**Related ADRs:** ADR-0002, ADR-0011, ADR-0014, ADR-0015

## Context

The PRD's acceptance criteria are the definition of done for capture behaviour, and `CLAUDE.md`
requires tests to cite them. But the criteria that matter most cannot run on a build server. PRD
6.1 asks for a clip whose metadata shows 3840x2160 at 30.00 fps constant with a matching shutter;
6.2 asks for no rolling bands against a real 60 Hz LED panel; PRD section 9 names thermal
throttling at 4K30 with simultaneous preview encoding as "the single biggest technical risk". The
reference devices are a physical Pixel and a physical Samsung.

An emulator cannot stand in. It has no real camera, no `MANUAL_SENSOR`, no hardware HEVC encoder,
and no thermal behaviour — that is, it lacks precisely the four things this app is about.

There is a second, quieter problem. Local Android tooling here is the `android` CLI with no
`sdkmanager`, `adb` or Android Studio, while GitHub Actions runners have the classic tools and no
`android` CLI (ADR-0014). If CI and contributors run different commands, CI drifts into testing a
configuration nobody develops against.

Two ADR invariants are also pure prose today and are exactly the kind that erode: ADR-0002 pins
CameraX at 1.6.2 ("Do not bump it") and confines all `Camera2Interop` to the `ManualControls`
class.

## Decision

We will run GitHub Actions on `ubuntu-latest` for every pull request and push to `main`, with
four jobs:

- **`guards`** — repository invariants that prose cannot enforce: the CameraX 1.6.2 pin
  (ADR-0002), `Camera2Interop` appearing only in `ManualControls` (ADR-0002), `:domain`
  `commonMain` free of `java.*`/`javax.*` (ADR-0015), and the ADR index agreeing with
  `docs/adr/*.md`. Each failure message names the ADR and says what to do instead, because a
  guard that only says "failed" gets deleted.
- **`pr-title`** — the PR title is a Conventional Commit, since a squash merge takes the commit
  subject from it.
- **`android`** — `ktlintCheck`, `test`, `lint`, `assembleDebug`, on Temurin 21 with
  `gradle/actions/wrapper-validation`.
- **`web`** — `pnpm install --frozen-lockfile`, typecheck, `pnpm run build` (ADR-0014).

**Every CI step is a Gradle task or an npm script that a contributor runs identically.** No
workflow step invokes `android`, `sdkmanager` or `adb`. If a check cannot be expressed as
`./gradlew <task>`, `pnpm run <script>` or a script in `tools/`, it does not go in CI. This is the
rule that keeps the local/CI divergence from spreading past the setup step.

**A green CI run does not mean the app works.** It means the code compiles, host tests and
fixtures pass, and the web bundle builds. Capture behaviour is verified on a physical reference
device by the author, who records the device, OS version, lens and observation in the pull request
template's hardware table. Reviewers treat a missing table on a capture change the way they would
treat a missing test.

**Instrumented tests are not in the pull-request gate.** They will exist for `:domain`, `:server`
and non-camera UI, and may run nightly or on demand, but they add emulator boot time to every PR
in exchange for coverage that excludes everything device-specific. macOS runners are not used
until Phase 4 brings iOS.

## Options Considered

### Option A: Host-only gate, invariant guards, manual device verification (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | Low, provided the gate's limits are stated rather than assumed |
| Effort | Low |
| Reversibility | High |

**Pros:** Fast; every check is reproducible locally; the guards convert two eroding ADR rules into
build failures; the PR template makes device verification visible instead of implicit.
**Cons:** Relies on author honesty for the hardware table. Mitigated by it being a review item and
by a solo/small contributor base.

### Option B: Emulator-based instrumented tests in the PR gate
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Risk | Medium — a green emulator run reads as device coverage when it is not |
| Effort | Medium |
| Reversibility | High |

**Pros:** Catches wiring and lifecycle regressions automatically.
**Cons:** Around eight minutes per PR, and its worst property is not the cost but the false
confidence: an emulator passing `record.start` proves nothing about a locked shutter on a Samsung.
Worth adding nightly once there is UI to regress; wrong as the gate.

### Option C: Self-hosted runner with the reference phones attached
| Dimension | Assessment |
|---|---|
| Complexity | High |
| Risk | Medium — a flaky USB device becomes a broken merge queue |
| Effort | High |
| Reversibility | Medium |

**Pros:** The only option that makes CI mean what people assume it means; would automate ADR-0002
action items 2-4.
**Cons:** A phone on a desk maintained as build infrastructure, needing charge management, thermal
cooldown between runs (the very variable being measured) and physical presence. Disproportionate
before the capture engine exists.

### Option D: No CI; rely on review
**Pros:** Nothing to maintain.
**Cons:** The invariants that most need protecting are the ones a reviewer skims past — a version
number in a catalog, an import in a new file.

## Trade-off Analysis

CI's honest job here is to make everything that *can* be checked mechanically impossible to get
wrong, and to be explicit that this excludes the acceptance criteria the product is judged on.
Option B and Option C both attempt to extend the gate toward device behaviour; B does so
misleadingly and C at a cost that only makes sense once there is a product to regress. Option A
draws the line where the evidence actually changes and writes the limitation into the PR template
so nobody mistakes a green tick for a working camera.

## Consequences

- Easier: ADR-0002's pin and interop confinement now fail loudly; contributors can reproduce every
  CI failure locally; PR review has a fixed checklist.
- Harder: device verification depends on the author reporting it; capture regressions can reach
  `main` if nobody records a device. The PR template and the review bar are the mitigation, not CI.
- Revisit when: a hosted device farm becomes available; or Phase 1 produces a capture regression
  that an emulator test would have caught, which would justify Option B nightly; or the first time
  a merged PR turns out to have skipped the hardware table.

## Action Items

1. [x] Add `.github/workflows/ci.yml` with the four jobs.
2. [x] Add the hardware verification table to the pull request template.
3. [ ] Davide: add the CI checks as required status checks on the `main` ruleset once they have run
   once and their names exist.
4. [ ] Revisit nightly instrumented tests when Phase 1 has UI worth regressing.
