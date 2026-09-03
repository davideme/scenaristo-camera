# Scenaristo Camera

Talking-head recording app: opinionated capture defaults (4K30, flicker-free shutter, locked white balance, lowest-noise ISO, hardware HEVC) plus a zero-install browser remote with live preview on the local network. Android first, iOS second. No backend.

## Where things are

- `docs/PRD-talking-head-camera.md`: the product requirements. Section numbers (6.1, 6.8, 8-Q1, ...) are referenced from ADRs, commits, and PRs.
- `docs/adr/`: Architecture Decision Records. `README.md` there is the index and lists open challenges to the PRD. `0000-template.md` is the template.
- `docs/protocol/`: the phone-to-browser protocol specification and fixtures (to be created; see ADR-0007). The message types themselves are the `@Serializable` classes in `:domain`; TypeScript and JSON Schema are generated from them, never hand-written.
- Multiplatform strategy: ADR-0013 says what is shared (web bundle, protocol, fixtures) and what is native (capture, phone UI).
- Planned code layout (ADR-0002, ADR-0009, ADR-0010): `android/` with Gradle modules `:domain`, `:capture` (CameraX), `:server`, `:app`; `web/` for the static browser UI; `ios/` later.

## Architecture Decision Records are required

Read `docs/adr/README.md` before changing anything architectural, and follow the constraints in Accepted and Proposed ADRs unless you are writing a new ADR that supersedes them.

**Write an ADR (before or with the change) when a feature or change does any of the following:**

- Adds, removes, or replaces a dependency, framework, build tool, or platform API family (camera, codec, muxer, server, UI toolkit).
- Changes how frames, audio, or files flow: camera streams, encoder configuration, container format, storage location, preview transport.
- Changes the phone-to-browser protocol in a way that is not purely additive, or adds a new channel or endpoint.
- Changes process lifecycle, permissions, background behaviour, network binding, or anything security-relevant (what a client on the LAN can do).
- Changes a default the PRD specifies (shutter, ISO behaviour, white balance, resolution, frame rate, bitrate, codec), or the minimum OS.
- Changes capability gating: what a device or lens is allowed to do.
- Introduces shared logic that both platforms must implement identically.
- Contradicts an existing ADR or a technical statement in the PRD. Do not diverge silently: write a superseding ADR and list the PRD text to amend.

**Do not write an ADR for:** bug fixes, refactors inside one module that keep interfaces, UI copy and layout, test additions, additive protocol fields, documentation.

**How to write one:** `docs/adr/README.md`. Two rules worth repeating here because they are the ones that get missed: an ADR ships in the same change as the code it justifies, and only Davide sets a status to `Accepted` — open yours as `Proposed`.

When a PRD statement and an ADR disagree, the ADR's Status decides: Accepted ADRs win and the PRD is pending amendment; Proposed ADRs are a challenge awaiting Davide's decision, so do not build against them as if settled unless the task says so.

## Tooling and skills

- Setup, build, test and PR conventions: `CONTRIBUTING.md`. Read it before running a build command. Android tooling here is Google's `android` CLI; there is **no `sdkmanager`, `adb`, `avdmanager` or system `gradle`** on the maintainer's machine, and CI runners are the opposite. Do not copy setup instructions between the two (ADR-0014).
- Build from `android/`: `./gradlew build`. `:domain` tests run as `./gradlew :domain:jvmTest`.
- The `commonTest` / `withHostTest {}` build warning is expected and deliberate (ADR-0015). Do not "fix" it.
- Repo invariants are enforced by `tools/check-adr-invariants.sh`, `tools/check-domain-platform-free.sh` and `tools/check-adr-index.sh`, all of which run in CI. If one fails, the message names the ADR.
- Google's Android skills (`camerax`, `testing-setup`, `android-profiler`, `android-cli`) are referenced as the pinned `android-skills` plugin, not vendored. **Consult the `camerax` skill before writing anything that touches CameraX, `Camera2Interop`, `Recorder` or `ImageAnalysis`.** If the plugin was never installed (`claude plugin install android-skills`) those skills are simply absent — there is no fallback.
- What to work on: `docs/ROADMAP.md`.

## Working conventions

- Product decisions (scope, priority, defaults as experienced by users) are Davide's; propose, do not decide. Technical decisions go through ADRs.
- The PRD's acceptance criteria are the definition of done for capture behaviour; cite them in tests.
- The Phase 0 reference matrix is one phone and one browser: a Pixel 10 and a MacBook (ADR-0017, PRD section 9). There is no Samsung, no iPhone and no iPad. Do not write a checklist, test or doc line that assumes a second device, and do not describe a Pixel 10 result as Android-wide or a macOS Safari result as iOS coverage. Widening the matrix is a later phase and a superseding ADR.
- `:domain` is a single-target Kotlin Multiplatform module; all of its code lives in `commonMain` so the compiler keeps it platform-free (ADR-0010).
- CameraX is pinned at 1.6.2 (ADR-0002). Do not bump it; the 1.7 upgrade is a scheduled review with its own checklist in that ADR. All `Camera2Interop` usage stays inside the `ManualControls` class in `:capture`.
- Do not commit or push unless asked. Branches are created by the workflow; do not create new ones.
