## What and why

<!-- One paragraph: what changes, and which problem it solves. -->

Closes #

## PRD

<!-- Sections this implements or affects, e.g. 6.3, 6.8. Write "none" for build,
     docs or infrastructure changes. -->
PRD sections:

**Acceptance criteria covered** — quote each, and name the test that proves it:

| PRD criterion | Test |
|---|---|
|  |  |

Criteria in scope but **not** covered by a test, and why:

## Architecture decisions

- [ ] Needs **no** ADR (checked against the trigger list in `CLAUDE.md`)
- [ ] Governed by an existing ADR: ADR-____
- [ ] **Adds or changes** an ADR: ADR-____ (opened as `Proposed`; only Davide sets `Accepted`)
  - [ ] Row added to `docs/adr/README.md` in this PR
  - [ ] "Challenges" row added if it amends the PRD

Does anything here contradict an Accepted ADR or a technical statement in the PRD?
<!-- If yes, name it. Do not diverge silently: write a superseding ADR. -->

## Verification

- [ ] `cd android && ./gradlew build` passes
- [ ] `cd web && npm run check && npm run build` passes
- [ ] `./tools/check-adr-index.sh`, `./tools/check-adr-invariants.sh` and
      `./tools/check-domain-platform-free.sh` pass

**Hardware** — required if this touches capture (PRD 6.1–6.7, 6.9, 6.10). CI cannot
verify any of it: the runner has no camera. See ADR-0016.

| Device | Android | Lens | What was observed |
|---|---|---|---|
|  |  |  |  |

## Product decisions

- [ ] Changes **no** user-visible default the PRD specifies
- [ ] It does — proposing, not deciding. Davide to confirm: ______

## Phase

Roadmap phase (`docs/ROADMAP.md`): ____
