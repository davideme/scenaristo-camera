#!/usr/bin/env bash
# Which parts of the tree a change touches, so a pull request only runs the CI
# jobs whose inputs actually moved (ADR-0016). Prints `name=true|false` lines in
# GitHub Actions output format; the workflow gates its jobs on them.
#
#   ./tools/changed-scopes.sh                 # working tree + commits vs origin/main
#   ./tools/changed-scopes.sh <base-ref>      # a pull request's diff against its base
#
# Everything is true when the diff cannot be determined, and on pushes to main:
# the safe answer is to run the whole gate. Never invert that default to make a
# run cheaper — a skipped job reads as a passing job.
set -uo pipefail
cd "$(dirname "$0")/.."

base="${1:-}"
files=""

if [ -n "$base" ]; then
  # Three dots: what this branch changed since it diverged, not what main did
  # meanwhile. On a pull_request event the checkout is the merge commit, so
  # `<base>...HEAD` is exactly the PR's own diff.
  # An empty result is indistinguishable from a fetch too shallow to find the
  # merge base, so both fall through to "run everything".
  files=$(git diff --name-only "$base...HEAD" 2>/dev/null) || files=""
fi

emit() { # name, regex
  if [ -z "$files" ]; then
    echo "$1=true"
  elif echo "$files" | grep -qE "$2"; then
    echo "$1=true"
  else
    echo "$1=false"
  fi
}

# A scope is its own directory and nothing else. Notably, editing the workflow
# file does NOT pull every job back in: a docs pull request that also tweaks
# `ci.yml` should not spend five minutes assembling an APK. The cost is that a
# change to the `android` job's own definition is not exercised until something
# under `android/` changes -- or until the merge, since every push to main runs
# the complete gate unfiltered. That is where a broken job definition surfaces.
#
# `web/` IS part of the android scope, as of ADR-0009 action 3: `pnpm run build`
# is an input to `assembleDebug`, so the bundle ships inside the APK and a
# change under `web/` can now break the Android build. It also covers the two
# ways `web/src/protocol.ts` can go stale against `:domain` (#84) -- the test
# that catches that runs in the `android` job.
#
# The `web` job still runs on its own for a web-only change: it is the faster of
# the two and it is where a bundle regression is legible, rather than buried in
# a Gradle Exec task's output.
emit android '^android/|^web/'
emit web '^web/'
