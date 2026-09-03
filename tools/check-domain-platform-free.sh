#!/usr/bin/env bash
# :domain must contain no platform types (ADR-0015).
#
# Division of labour, measured on 2026-09-03 rather than assumed:
#
#   android.* in commonMain -> REJECTED by the Kotlin compiler, because the
#       jvm() target does not have those symbols. The second target earns its
#       keep here, and this is the mistake that actually happens.
#
#   java.*    in commonMain -> ACCEPTED by the compiler, because androidTarget
#       and jvm are both JVM-family and share the JDK surface. Only a non-JVM
#       target (Kotlin/Native or JS) would close this, and that is the
#       toolchain cost ADR-0010 deferred to Phase 4.
#
# This script covers the second case, so the two together give the guarantee
# ADR-0010 claimed for the compiler alone.
set -uo pipefail
cd "$(dirname "$0")/.."

SRC=android/domain/src/commonMain
[ -d "$SRC" ] || { echo "check-domain-platform-free: $SRC missing"; exit 1; }

# Match `java.` / `javax.` as an import or a fully-qualified reference.
hits=$(grep -rnE '(^|[^A-Za-z0-9_.])javax?\.[a-z]' --include='*.kt' "$SRC" || true)

if [ -n "$hits" ]; then
  echo "check-domain-platform-free: JVM-only types found in :domain commonMain." >&2
  echo "  :domain is platform-free (ADR-0015); the iOS port in Phase 4 must compile" >&2
  echo "  the same sources. Use kotlin.* or kotlinx.* equivalents." >&2
  echo "$hits" >&2
  exit 1
fi

echo "check-domain-platform-free: OK"
