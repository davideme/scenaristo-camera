#!/usr/bin/env bash
# Repository invariants that ADR prose alone cannot enforce (ADR-0016).
# Each failure names the ADR and says what to do instead.
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0

# --- ADR-0002: CameraX is pinned at 1.6.2 --------------------------------
if ! grep -qE '^camerax = "1\.6\.2"' android/gradle/libs.versions.toml; then
  cat >&2 <<'MSG'
check-adr-invariants: CameraX is pinned at 1.6.2 by ADR-0002.
  The 1.7 upgrade is a scheduled review with a checklist: enforce HEVC via
  setVideoMimeType, migrate ManualControls off the deprecated Camera2Interop
  API, re-check crash-resilient output, and decide spec-chapter-markers CM-1.
  Bumping it needs a superseding ADR, not a version change.
MSG
  fail=1
fi

# --- ADR-0002: Camera2Interop lives only in ManualControls ---------------
hits=$(grep -rln 'Camera2Interop\|Camera2CameraControl\|CaptureRequestOptions' \
         --include='*.kt' android/ 2>/dev/null | grep -v 'ManualControls\.kt$' || true)
if [ -n "$hits" ]; then
  echo "check-adr-invariants: Camera2 interop found outside ManualControls (ADR-0002):" >&2
  echo "$hits" >&2
  echo "  All interop stays in one class because CameraX 1.7 replaces that API." >&2
  fail=1
fi

[ $fail -eq 0 ] && echo "check-adr-invariants: OK"
exit $fail
