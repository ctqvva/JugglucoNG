#!/usr/bin/env bash
# Run Common unit tests and refuse to report on stale results.
#
# Exists because a compile failure in test sources let `gradle -q` exit
# non-zero while the previous run's XML stayed on disk, so reading the reports
# afterwards produced measurements from an older build. Three sweep results
# were recorded that way before the identical numbers gave it away.
#
# Usage: tools/run-unit-tests.sh [--tests 'pattern' ...]
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS="$REPO/Common/build/test-results/testMobileDebugUnitTest"

# 1. No stale reports may survive into this run.
rm -rf "$RESULTS"

# 2. Run, keeping the exit status.
( cd "$REPO" && ./gradlew :Common:testMobileDebugUnitTest --no-daemon "$@" ) >/tmp/juggluco-test-run.log 2>&1
STATUS=$?

# 3. A compile failure must be loud, and must never fall through to reports.
if grep -qE '^e: |Compilation error|COMPILATION ERROR' /tmp/juggluco-test-run.log; then
    echo "COMPILE FAILED — no results produced:"
    grep -E '^e: ' /tmp/juggluco-test-run.log | head -15
    exit 2
fi

if [ ! -d "$RESULTS" ] || [ -z "$(ls -A "$RESULTS" 2>/dev/null)" ]; then
    echo "NO RESULTS WRITTEN (gradle exit $STATUS) — refusing to report."
    tail -20 /tmp/juggluco-test-run.log
    exit 3
fi

FAILED=$(grep -l 'failures="[1-9]\|errors="[1-9]' "$RESULTS"/*.xml 2>/dev/null || true)
if [ -n "$FAILED" ]; then
    echo "FAILING CLASSES:"
    for f in $FAILED; do basename "$f" .xml | sed 's/TEST-//'; done
else
    echo "ALL GREEN (gradle exit $STATUS)"
fi
exit $STATUS
