# Contributing to Scenaristo Camera

An opinionated talking-head camera: correct capture defaults on the phone, plus a browser remote
on the local network. Android first, iOS second, no backend.

## 1. Start here

- The product spec is [docs/PRD-talking-head-camera.md](docs/PRD-talking-head-camera.md). Section
  numbers (6.1, 6.8, 8-Q1) are the shared vocabulary — they appear in issues, commits, pull
  requests, ADRs and test names.
- Every architectural choice already made is in [docs/adr/](docs/adr/README.md). Read the index
  before changing anything structural.
- What to build next is [docs/ROADMAP.md](docs/ROADMAP.md).
- **Product decisions** — scope, priority, user-visible defaults — are Davide's. Propose, do not
  decide.
- **Technical decisions** go through an ADR. [CLAUDE.md](CLAUDE.md) lists exactly when one is
  required.

## 2. What to work on

[docs/ROADMAP.md](docs/ROADMAP.md) maps the PRD's phases onto the issue tracker. Phases are
GitHub milestones; the `P0`/`P1` labels are PRD priority ("must ship in v1"), not phase.

Start with Phase 0 if you are picking something up cold. It exists to retire the project's two
largest risks — whether the CameraX manual-control keys are honoured on real devices, and whether
there is thermal headroom for 4K30 with simultaneous preview encoding. Everything in Phases 1–2
is built on assumptions Phase 0 either confirms or destroys.

The open `user-story` issues are PRD section 5 stories with their acceptance criteria copied in.
They are usually too big for one pull request — open a task issue for the piece you will actually
close, and reference the parent.

## 3. Environment setup

### 3.1 Everyone

JDK 21 (Temurin), Node 22+, pnpm 10+, `git`, `gh`.

```bash
java -version && node -v && pnpm --version && gh auth status
```

`web/` uses **pnpm**, not npm (ADR-0014). `package.json` pins the version via `packageManager`,
so Corepack will use the right one; `corepack enable` if you do not have pnpm already.

### 3.2 Android tooling, macOS — the `android` CLI

This project's macOS setup uses Google's unified `android` CLI. **There is no `sdkmanager`,
`avdmanager`, standalone `gradle`, or Android Studio requirement**, and no `adb` on `PATH`.
Instructions elsewhere that begin "run `sdkmanager`" do not apply, and the package ids are
path-style (`platforms/android-37.0`, not `platforms;android-37`).

`adb` is unlisted rather than absent: `android sdk install platform-tools` below is what installs
it. Reach for it only where the `android` CLI does not reach — `logcat`, `am`, `getprop`,
force-stopping a recording — and keep it in a variable rather than on `PATH`, so nothing written
here grows a dependency CI does not have:

```bash
ADB="$(android info sdk)/platform-tools/adb"
"$ADB" devices -l
```

```bash
android --version        # expect 1.0.16251017 or newer
android info             # prints the SDK root it will use

android sdk install platform-tools
android sdk install platforms/android-37.0
android sdk install build-tools/37.0.0
```

`android/local.properties` carries `sdk.dir` and is git-ignored. If Gradle cannot find the SDK,
point it at the path `android info` prints. See ADR-0014 for why `sdk.dir` rather than
`ANDROID_HOME`.

Useful beyond setup: `android screen` (screenshots), `android docs search "<topic>"` (offline
official docs), `android emulator create|start`, and to install and launch on a connected device:

```bash
cd android && ./gradlew :app:assembleDebug
android run --apks app/build/outputs/apk/debug/app-debug.apk \
            --activity com.scenaristo.camera.MainActivity
```

`android run` does **not** locate the APK by itself — without `--apks` it fails with
"No apks specified". Build first, then pass the path.

### 3.3 Android tooling elsewhere, and why CI differs

GitHub Actions `ubuntu-latest` runners ship the **classic** SDK (`ANDROID_HOME` preset,
`sdkmanager` and `adb` on `PATH`) and no `android` CLI. macOS here is the reverse. Both are
correct; neither set of setup instructions can be copied into the other.

Two rules keep that from rotting:

- **Everything CI runs is a Gradle task, an npm script, or a script in `tools/`.** No workflow
  step calls `android`, `sdkmanager` or `adb`.
- **Everything you run locally is the same task or script.** The `android` CLI is used only for
  what Gradle does not do: installing the SDK, creating emulators, driving a device.

Consequence, stated plainly: **a green CI run does not prove the app works on a phone.** It proves
the code compiles, host tests and fixtures pass, and the web bundle builds. See ADR-0016.

### 3.4 Web

```bash
cd web && pnpm install && pnpm run dev
```

`pnpm run build` produces `web/dist/` — one HTML file, one JS file, one CSS file, no external
requests (ADR-0009). The page is served over plain HTTP from a LAN IP, so there is no secure
context and no CDN to reach.

**"No secure context" is a bigger constraint than it sounds, and it fails silently.** Anything the
platform gates behind a secure context is simply absent: `crypto.randomUUID`, `crypto.subtle`,
service workers, `navigator.clipboard.write`, geolocation, WebCodecs (ADR-0008 records that one).
The failure mode is a `TypeError` in a click handler and a button that does nothing — no build
error, no type error, and `pnpm run dev` over `localhost` **is** a secure context, so it works
perfectly until it reaches the phone. `crypto.getRandomValues` is not restricted and is the usual
substitute for `randomUUID`. Test on the phone before believing a browser API exists.

Do not hand-edit `web/src/protocol.ts` once it exists: it is generated from the `@Serializable`
classes in `:domain`.

### 3.5 A physical device is required for the parts that matter

The emulator has no real camera, no `MANUAL_SENSOR`, no hardware HEVC encoder and no thermal
behaviour. It is useful for `:domain`, `:server` and non-camera UI, and for nothing else this app
is about.

The Pixel 10 is usually paired over Wi-Fi and appears as `<ip>:5555`. Confirm which phone answered
before writing a result down — `"$ADB" -s <serial> shell getprop ro.product.model` — because every
device result in this repository is a claim about one specific handset.

Reference devices are one Pixel 10 and a MacBook running Safari and Chrome (ADR-0017, PRD
section 9). There is no second phone and no iOS device, so a Phase 0 result is evidence about a
Pixel 10 and about WebKit on macOS — say that, rather than implying fleet-wide or iOS coverage. The
second OEM device and the iOS Safari check arrive before public beta; see ADR-0017.

Once a second phone is in the matrix, anything that works on only one of them is a bug or an ADR,
not a feature.

## 4. Build, test, run

Run from `android/` unless stated otherwise.

On a pull request, the `android` and `web` jobs run **only when their own directory changed**
(ADR-0016); `../tools/changed-scopes.sh origin/main` prints what CI will do for your branch. A push
to `main` always runs the whole gate. A skipped job means CI saw nothing in that directory, not that
it verified anything — the commands below are still yours to run.

| Goal | Command |
|---|---|
| Everything CI runs | `./gradlew build` then `cd ../web && pnpm install --frozen-lockfile && pnpm run check && pnpm run build` |
| Domain tests and protocol fixtures, no device | `./gradlew :domain:jvmTest` |
| All host tests | `./gradlew test :domain:jvmTest` |
| Android lint | `./gradlew lint` |
| Debug APK | `./gradlew :app:assembleDebug` |
| Install and launch on a device | `./gradlew :app:assembleDebug` then `android run --apks app/build/outputs/apk/debug/app-debug.apk --activity com.scenaristo.camera.MainActivity` |
| Repo invariants | `../tools/check-adr-invariants.sh` |
| `:domain` is platform-free | `../tools/check-domain-platform-free.sh` |
| ADR index is consistent | `../tools/check-adr-index.sh` |
| What CI will actually run for your branch | `../tools/changed-scopes.sh origin/main` |

The build prints `WARNING: The 'commonTest' source directory exists, but android host tests are
not enabled`. That is expected: `:domain` tests run on its `jvm` target, deliberately, so they
need no Android machinery (ADR-0015). Do not "fix" it.

## 5. Architecture decisions

- **When** an ADR is required: [CLAUDE.md](CLAUDE.md).
- **How** to write one: [docs/adr/README.md](docs/adr/README.md).

Two rules that pull requests get rejected on: an ADR ships **in the same pull request** as the
change it justifies, and only Davide sets a status to `Accepted` — open yours as `Proposed`.

## 6. Branches, commits, pull requests

- **Branches** are `claude/<slug>-<6hex>`. The workflow creates the session's first one; **stack
  the rest** rather than piling several changes onto one branch, which section 7 rejects. A change
  that depends on an unmerged one branches from it and opens its pull request against it as base;
  an independent change branches from `main`. When a lower pull request merges, rebase what sat on
  top of it.
- **A stack merges bottom-up, and GitHub will not stop you getting this wrong.** Merge the lowest
  pull request first, then the one above it, and so on. If you want to merge them out of order,
  **retarget the upper pull request to `main` first** — its "Edit" button changes the base.

  The failure this prevents has already happened once, in #56. It was open against #49's branch;
  #49 was squashed onto `main` first, which left #56 pointing at a branch `main` would never take
  again. Merging it then wrote its commits to a dead branch. GitHub marked it **merged**, the
  checks were green, the issue auto-closed — and not one line of it was in the product. It was
  found days later only because a feature that depended on it did nothing.

  **And do not delete a branch while anything is still stacked on it.** GitHub *closes* a pull
  request whose base branch disappears, and a closed pull request cannot be reopened or retargeted
  once its base is gone — the work is not lost, but the review, the description and the discussion
  are stranded and the only way forward is a fresh pull request. `gh pr merge --delete-branch` on
  the lowest of a stack does exactly this to the one above it. Retarget the upper pull request to
  `main` **first**, then merge and delete.

  So after merging any stacked pull request, **check that the code is actually on `main`** rather
  than trusting the merged badge:

  ```bash
  git fetch origin main && git log origin/main --oneline -5
  git ls-tree -r origin/main --name-only | grep <a file the PR added>
  ```
- **Pull request titles are Conventional Commits.** `main` uses squash merges, so the PR title
  becomes the commit subject on `main` — the title is the thing that has to conform, and commits
  on your branch are working notes that the squash discards.

  ```
  feat(capture): lock shutter to the mains-frequency ladder
  fix(server): reject Host headers that are not IP literals
  docs(adr): record the build toolchain
  ```

  Types: `feat`, `fix`, `docs`, `build`, `ci`, `refactor`, `test`, `perf`, `chore`.
  Scopes: `domain`, `capture`, `server`, `app`, `web`, `adr`, `prd`, `ci`.
  `BREAKING CHANGE:` is reserved for a non-additive protocol change (ADR-0007).

  Note that commits made before this convention was adopted are plain imperative subjects with no
  type prefix. That is history, not a pattern to copy.

- **Bodies** wrap at 72 columns and explain *why*. Cite `ADR-NNNN` and PRD sections where either
  applies.
- One reviewable change per pull request. Formatting-only churn in files you did not otherwise
  touch belongs in its own.
- **Merging is a human decision.** Agents never merge, and never ask CI to merge for them: no `gh pr merge`, no auto-merge. An agent's job ends at a green pull request with a description someone can act on.
- Agents: do not commit or push unless asked.

## 7. The review bar

In order:

1. **Does it contradict an Accepted ADR?** If so it needs a superseding ADR in the same pull
   request, or it is rejected. If it contradicts a `Proposed` ADR, say so — that is Davide's
   decision, not the pull request's.
2. **Does it need an ADR it does not have?** Check the trigger list in `CLAUDE.md`.
3. **Does it change a user-visible default the PRD specifies?** Then it is a product decision.
   Propose it; do not merge on your own judgement.
4. **Do the tests cite PRD acceptance criteria?** See section 8.
5. **Was it verified on hardware, if it touches capture?** Name the device, Android version, lens
   and what you observed. "CI is green" is not verification for PRD 6.1–6.7.
6. **Is CI green?**
7. **Is the scope one change?**
8. **Does it leave the PRD or an ADR saying something false?** Fix it in the same pull request or
   say why not.

## 8. Tests must cite PRD acceptance criteria

The PRD's acceptance criteria are the definition of done for capture behaviour. A test that
verifies one names it, so a failing test says which promise broke.

```kotlin
// PRD 6.2: "Given the device region is Germany, when the app launches, then
//           shutter defaults to 1/50 s and the UI reads '50 Hz'."
@Test
fun `PRD 6_2 - Germany defaults to 1 over 50 s and reads 50 Hz`() { … }
```

- Name the test after the **PRD** section, not the ADR. Put the ADR in a comment when one amended
  the criterion — 6.3, 6.6, 6.7 and 6.9 all have amendments.
- Where a criterion was reduced for the MVP (level meter 5 Hz rather than 10 Hz, crash resilience
  only up to a measured take length), assert the **MVP** value and reference the ADR that lowered
  it. The day it is raised, the test is what changes.
- Criteria that only hold on a device go in `androidTest` with the same naming, and the pull
  request reports the device.
- Cross-platform criteria are driven from the golden fixtures in
  [docs/protocol/fixtures/](docs/protocol/fixtures/) so the Phase 4 iOS port inherits them.
- A criterion with no test is a gap. Note it in the pull request rather than leaving it silent.

## 9. Reporting device compatibility

Open a **Device compatibility report** issue. You do not need to write code for this to be
valuable: which codec your device picks for UHD, whether the manual keys are honoured, and your
per-lens `MANUAL_SENSOR` truth table are direct inputs to ADR-0002, ADR-0011 and PRD 8-Q4, and
the maintainer has two phones.

## 10. Working with an AI agent

[CLAUDE.md](CLAUDE.md) is the agent's map of the repository and carries the rules that matter most
mid-task. `.claude/settings.json` is checked in and makes two of them enforced rather than hoped
for: commits, pushes and anything outward-facing prompt before they happen.

Google's official Android skills (CameraX, testing, profiling, the `android` CLI) are referenced
as a pinned plugin rather than vendored. Install once:

```bash
claude plugin install android-skills
```

Without it those skills simply do not exist for the session — there is no graceful degradation —
so do this before asking an agent to touch CameraX.
