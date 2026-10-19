#!/usr/bin/env bash
# Restore only checksummed private inputs from an already-published release APK.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/dist/dist.py restore "$@"
