# ADR-0015: Build `:domain` with two JVM-family targets under the Android KMP library plugin

**Status:** Proposed
**Date:** 2026-09-03
**Deciders:** Davide Mendolia
**PRD sections:** 9 (Phase 4), 6.2, 6.3, 6.4, 6.10
**Related ADRs:** Supersedes ADR-0010. Related: ADR-0002, ADR-0005, ADR-0007, ADR-0011, ADR-0013, ADR-0014

## Context

ADR-0010 established that platform-independent logic lives in a `:domain` module, that the iOS
target is deferred to Phase 4, and that behavioural parity is protected by golden fixture tests.
All three still hold and are restated below.

Two of its mechanical claims do not survive contact with the toolchain. Both were checked on
2026-09-03 while standing the build up, not inferred.

**1. `androidTarget()` is not available.** ADR-0010 specifies "the `kotlin("multiplatform")`
plugin and a single `androidTarget()`". AGP 9 removed that combination:

> using the `org.jetbrains.kotlin.multiplatform` plugin together with the `com.android.library`
> or `com.android.application` plugin is no longer allowed when built-in Kotlin is enabled.
> — Android, *Migrate to built-in Kotlin*

The supported replacement is the `com.android.kotlin.multiplatform.library` plugin with the
target declared as `kotlin { android { … } }`. (`androidLibrary { }` is deprecated since AGP
9.1.0-alpha09.)

**2. A single target enforces nothing.** ADR-0010 rejected its own Option D — a plain
`kotlin("jvm")` module plus a hand-written import check — on the grounds that "the compiler
itself refuses `java.*`, reflection, and JVM-only standard-library members in `commonMain`, which
is the rule a hand-written import check could only approximate". That is not true of a
single-target module:

> If your project only has a single target (for example, JVM), you can access target-specific
> symbols with appropriate visibility from common code. However, as soon as you add a second
> target, target-specific symbols become unavailable in common code.
> — JetBrains, *The basics of Kotlin Multiplatform project structure*

So ADR-0010 as written buys the Gradle complexity of multiplatform and none of the enforcement
that justified it.

**3. What a second JVM-family target actually buys — measured, not assumed.** Adding `jvm()`
alongside the Android target was tried, and the result is partial:

| Written in `commonMain` | Result |
|---|---|
| `android.util.Log.d(...)` | **Rejected** — `Unresolved reference 'android'` |
| `java.io.File(...)` | **Accepted** — compiles |

The reason is that a second target only hides symbols specific to *one* target. `androidTarget`
and `jvm` are both JVM-family and share the JDK surface, so `java.*` stays visible to both. Only
a non-JVM target (Kotlin/Native or JS) would close that half, and Kotlin/Native is precisely the
Xcode-and-second-toolchain cost ADR-0010 deferred to Phase 4.

`android.*` is nonetheless the mistake that actually happens: the realistic failure is reaching
for `Log`, `Context`, or `SystemClock` while writing domain logic in an Android-shaped codebase,
not reaching for `java.io.File`.

## Decision

We will build `:domain` with the **`org.jetbrains.kotlin.multiplatform` plugin plus
`com.android.kotlin.multiplatform.library`**, declaring **two targets**: `android { }` (the AAR
that `:capture`, `:server` and `:app` consume) and `jvm()`. All code stays in `commonMain`. The
iOS target remains deferred to Phase 4, and adding it stays a build-file change.

Platform-freeness is enforced by two mechanisms that together cover what ADR-0010 claimed for the
compiler alone, with the split stated honestly:

- the `jvm()` target makes the compiler reject `android.*` in `commonMain`, and
- `tools/check-domain-platform-free.sh` rejects `java.*` and `javax.*`, and runs in CI and
  locally.

`commonTest` runs on the `jvm` target as `jvmTest`: fast, no Android host-test machinery, no
device. Android host tests are deliberately not enabled on the Android target, which is why the
build prints a `withHostTest {}` advisory — that advisory is expected and should not be
"fixed".

The golden fixtures under `docs/protocol/fixtures/` remain the cross-platform parity contract
(ADR-0013). They are exercised from `jvmTest` rather than `commonTest`, because reading a file
needs a platform API; the fixture files themselves stay platform-neutral so a Phase 4 iOS runner
reads the same bytes.

This supersedes ADR-0010 in full. Everything in ADR-0010's Decision that is not contradicted
above is carried forward unchanged: domain logic is platform-free, inputs and outputs are plain
data classes, platform layers adapt CameraX and AVFoundation values into them, `kotlinx.serialization`
is the only non-stdlib dependency, and KMP for iOS plus Compose Multiplatform stay deferred.

No PRD text changes.

## Options Considered

### Option A: Two JVM-family targets plus a lint for the remainder (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | Low |
| Effort | One plugin line, one target line, one shell script |
| Reversibility | High |

**Pros:** Compiler catches the failure that actually occurs (`android.*`); the lint closes the
rest at negligible cost; `jvmTest` is fast and device-free; no Kotlin/Native toolchain; adding
`iosArm64()` in Phase 4 upgrades the guarantee automatically and needs no rewrite.
**Cons:** Enforcement is split across two mechanisms rather than one, so the ADR has to explain
which covers what. Someone deleting the shell script silently loses half of it — hence it runs in
CI, not only locally.

### Option B: Single Android target only (ADR-0010 as literally written, ported to AGP 9)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Risk | Medium |
| Effort | Lowest |
| Reversibility | High |

**Pros:** Fewest moving parts; matches ADR-0010's letter.
**Cons:** Enforces nothing — both `android.*` and `java.*` compile in `commonMain`. Needs
`withHostTest { }` for tests to exist at all, which drags in Android host-test machinery for
logic that has no Android in it. Phase 4 becomes a real migration rather than the "one-line build
change" ADR-0010 promises, because the drift it was meant to prevent will already have happened.

### Option C: Add a Kotlin/Native iOS target now
| Dimension | Assessment |
|---|---|
| Complexity | High |
| Risk | Medium |
| Effort | High |
| Reversibility | Medium |

**Pros:** Full compiler enforcement, including `java.*`; Phase 4 domain work would be done.
**Cons:** Requires Xcode, which is not installed on the reference machine (Command Line Tools
only), plus Kotlin/Native compilation in CI and Swift interop constraints — the exact costs
ADR-0010 and ADR-0013 deferred, paid before the Android capture engine is proven. This is the
Phase 4 decision, not a bootstrap one.

### Option D: Add a JS or Wasm target purely to force enforcement
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Risk | Low |
| Effort | Medium |
| Reversibility | High |

**Pros:** Non-JVM, so it would reject `java.*` with no Xcode dependency.
**Cons:** A compile target that ships nothing, maintained solely as a lint, and paid on every CI
run. The TypeScript the web UI needs is generated from the `@Serializable` classes by `kxstsgen`
(ADR-0009), not by a JS target, so there is no second use to amortise it against. A four-line
shell script buys the same guarantee.

## Trade-off Analysis

The forces are unchanged from ADR-0010: prevent Android and iOS drifting on behaviour the PRD
requires to be identical, without paying for a second toolchain before the first product works.
What changed is the evidence about which mechanism delivers that. Option B was chosen by ADR-0010
on a false premise and delivers no enforcement. Option C delivers full enforcement at exactly the
cost that ADR was written to avoid. Option A delivers enforcement against the realistic mistake
through the compiler and against the rest through four lines of shell, keeping the Phase 4
decision open and cost-free. The parity guarantee that matters most — the fixtures — is unaffected
by any of this and carries over intact.

## Consequences

- Easier: `:domain` tests run in seconds with no emulator; `android.*` in domain code fails at
  compile time with a clear message; the Phase 4 iOS target upgrades enforcement for free.
- Harder: two enforcement mechanisms must both be kept alive, and the CI job is what stops the
  shell one rotting. The `withHostTest {}` build advisory is permanent noise that has to be
  explained to every newcomer rather than silenced, since silencing it repo-wide
  (`android.sync.suppressAgpWarnings=GENERIC`) would hide unrelated warnings too.
- Revisit when: the iOS target is added in Phase 4 — at that point `java.*` becomes a compile
  error and `tools/check-domain-platform-free.sh` should be deleted rather than left as dead
  ceremony. Also revisit if AGP restores a supported single-target Android KMP shape with
  enforcement.

## Action Items

1. [x] Create `:domain` with `android { }` + `jvm()` under the KMP library plugin, all code in `commonMain`.
2. [x] Add `tools/check-domain-platform-free.sh` and wire it into CI.
3. [x] Seed `docs/protocol/fixtures/` and run it from `jvmTest`.
4. [ ] Set ADR-0010 to `Superseded by ADR-0015` once this ADR is Accepted.
5. [ ] Phase 4: add the iOS target, then delete the shell guard.
