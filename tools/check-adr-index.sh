#!/usr/bin/env bash
# Keeps docs/adr/README.md and docs/adr/*.md in agreement.
# Run before opening a PR:  ./tools/check-adr-index.sh
set -uo pipefail
cd "$(dirname "$0")/.."

index=docs/adr/README.md
fail=0
note() { echo "check-adr-index: $*" >&2; fail=1; }

# 1. Every ADR file except the template has a row in the index.
for f in docs/adr/[0-9][0-9][0-9][0-9]-*.md; do
  n=$(basename "$f" | cut -c1-4)
  [ "$n" = "0000" ] && continue
  grep -qE "^\| \[?$n\]?[])(]?" "$index" || note "ADR $n ($f) has no row in $index"
done

# 2. Every ADR file has a well-formed Status line.
#    ADR-0004 is Withdrawn and has no file on purpose; the number is not reused.
for f in docs/adr/[0-9][0-9][0-9][0-9]-*.md; do
  [ "$(basename "$f" | cut -c1-4)" = "0000" ] && continue
  grep -qE '^\*\*Status:\*\* *(Proposed|Accepted|Deprecated|Withdrawn|Superseded by ADR-[0-9]{4})' "$f" \
    || note "$f has no valid **Status:** line"
done

# 3. Every file the index links to exists.
for rel in $(grep -oE '\]\([0-9]{4}-[a-z0-9-]+\.md\)' "$index" | tr -d '])(' | sort -u); do
  [ -f "docs/adr/$rel" ] || note "index links a missing file: docs/adr/$rel"
done

# 4. No duplicate ADR numbers.
dups=$(ls docs/adr/[0-9][0-9][0-9][0-9]-*.md 2>/dev/null | xargs -n1 basename | cut -c1-4 | sort | uniq -d)
[ -z "$dups" ] || note "duplicate ADR numbers: $dups"

[ $fail -eq 0 ] && echo "check-adr-index: OK"
exit $fail
