#!/usr/bin/env bash
# Canonical, production-signed distribution build. Ordinary CI uses Gradle directly.
set -euo pipefail
cd "$(dirname "$0")/.."
target="${1:-phone}"
shift "$(( $# > 0 ? 1 : 0 ))"
case "$target" in watch) target=wear ;; watch-all) target=wear-all ;; esac
case "$target" in phone|phone-all|wear|wear-all|all) ;; *) echo "Usage: $0 {phone|phone-all|wear|wear-all|all} [Gradle options]" >&2; exit 2 ;; esac
# ABI selection has one source, so verification cannot disagree with Gradle.
for arg in "$@"; do
  case "$arg" in -PjugglucoAbi=*|-Pthe*|-PrequireProductionSigning*|-PdistributionTurnDirectory*) echo 'Use ORG_GRADLE_PROJECT_* or Gradle properties for ABI/signing configuration' >&2; exit 2 ;; esac
done
# Prevent a failed build from leaving a previous successful distribution in this target.
rm -rf "build/dist/$target"
# Do not silently ship a direct-only Clone build. The generated header is private
# source material, while its relay credentials are intentionally bundled in APKs.
umask 077
mkdir -p build/private
turn_dir=$(mktemp -d "$PWD/build/private/clone-turn.XXXXXX")
trap 'rm -rf "$turn_dir"' EXIT
python3 scripts/dist/turn.py generate --directory "$turn_dir"
export ORG_GRADLE_PROJECT_distributionTurnDirectory="$turn_dir"
python3 scripts/dist/dist.py check-inputs --target "$target"
task_list=$(python3 scripts/dist/dist.py tasks --target "$target")
tasks=()
while IFS= read -r task; do tasks+=("$task"); done <<< "$task_list"
echo "Building production-signed distribution APK(s): $target"
./gradlew "${tasks[@]}" "$@" --stacktrace -PrequireProductionSigning=true
python3 scripts/dist/turn.py verify --target "$target"
printf '\nBuilt APKs:\n'
python3 scripts/dist/dist.py stage --target "$target"
