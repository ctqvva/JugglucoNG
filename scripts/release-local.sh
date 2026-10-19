#!/usr/bin/env bash
# Exceptional first release of new JNI bytes; normal releases use Actions.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/dist/publish-release.py --bootstrap "$@"
