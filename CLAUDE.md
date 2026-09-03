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

**How:**

1. Copy `docs/adr/0000-template.md` to `docs/adr/NNNN-short-title.md` with the next number.
2. Status starts as `Proposed`. Only Davide sets `Accepted`. Never edit an Accepted ADR's decision; write a new one with `Supersedes ADR-NNNN` and set the old one to `Superseded by ADR-MMMM`.
3. Fill every section. Name at least two real options with a dimension table each. State a concrete "revisit when" trigger in Consequences. If the ADR changes PRD text, list the amendment under Decision.
4. Add the row to the index in `docs/adr/README.md` in the same change, and to the "Challenges" table if it amends the PRD.
5. Reference the ADR number in the commit message and PR description (`ADR-0007`).

When a PRD statement and an ADR disagree, the ADR's Status decides: Accepted ADRs win and the PRD is pending amendment; Proposed ADRs are a challenge awaiting Davide's decision, so do not build against them as if settled unless the task says so.

## Working conventions

- Product decisions (scope, priority, defaults as experienced by users) are Davide's; propose, do not decide. Technical decisions go through ADRs.
- The PRD's acceptance criteria are the definition of done for capture behaviour; cite them in tests.
- Reference devices for Phase 0 are one Pixel and one Samsung (PRD section 9). Anything that only works on one of them is a bug or an ADR, not a feature.
- `:domain` is a single-target Kotlin Multiplatform module; all of its code lives in `commonMain` so the compiler keeps it platform-free (ADR-0010).
- CameraX is pinned at 1.6.2 (ADR-0002). Do not bump it; the 1.7 upgrade is a scheduled review with its own checklist in that ADR. All `Camera2Interop` usage stays inside the `ManualControls` class in `:capture`.
- Do not commit or push unless asked. Branches are created by the workflow; do not create new ones.
