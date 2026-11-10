#!/usr/bin/env bash
#
# Generates update-manifest.json for a GitHub release.
#
# The in-app updater works without this file — it falls back to comparing release tag names and
# matching asset filenames. What the manifest adds is the two things a tag cannot carry:
#   * the real versionCode, which is what Android actually compares when replacing a package;
#   * a SHA-256 per artifact, so a truncated or corrupted download is caught before install.
#
# Attach the output to the release as an asset literally named "update-manifest.json".
# Note that "file" refers to another asset of the same release: the updater takes download URLs
# from GitHub's asset listing, never from this file, so a tampered manifest cannot redirect a
# download somewhere else.
#
# Usage:
#   scripts/make-update-manifest.sh <apk-dir> [> update-manifest.json]
#
# Example, after ./gradlew assembleMobileRelease assembleMobileReleasedub:
#   scripts/make-update-manifest.sh Common/build/outputs/apk > update-manifest.json
#   gh release upload <tag> update-manifest.json
#
set -euo pipefail

APK_DIR="${1:-Common/build/outputs/apk}"
GRADLE_FILE="$(dirname "$0")/../Common/build.gradle"

version_code="$(sed -n 's/.*appVersionCode *= *\([0-9][0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1)"
version_name="$(sed -n "s/.*appVersionName *= *'\([^']*\)'.*/\1/p" "$GRADLE_FILE" | head -1)"
min_sdk="$(sed -n 's/.*minSdk \([0-9][0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1)"

[ -n "$version_code" ] || { echo "could not read appVersionCode" >&2; exit 1; }
[ -n "$version_name" ] || { echo "could not read appVersionName" >&2; exit 1; }

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# applicationId per phone build type. Wear APKs are deliberately absent: the updater is a
# phone-only feature and a wear artifact must never be offered to a phone install.
application_id_for() {
  case "$1" in
    *-wear*)    echo "" ;;
    *-dub2.apk) echo "tk.glucodata.ng.dub2" ;;
    *-dub.apk)  echo "tk.glucodata.ng.dub" ;;
    *-debug*)   echo "" ;;
    JugglucoNG-*.apk) echo "tk.glucodata.ng" ;;
    *) echo "" ;;
  esac
}

entries=""
while IFS= read -r apk; do
  name="$(basename "$apk")"
  app_id="$(application_id_for "$name")"
  [ -n "$app_id" ] || continue
  size="$(wc -c < "$apk" | tr -d ' ')"
  digest="$(sha256 "$apk")"
  [ -z "$entries" ] || entries="$entries,"
  entries="$entries
    {
      \"applicationId\": \"$app_id\",
      \"file\": \"$name\",
      \"size\": $size,
      \"sha256\": \"$digest\"
    }"
done < <(find "$APK_DIR" -name 'JugglucoNG-*.apk' | sort)

[ -n "$entries" ] || { echo "no release APKs found under $APK_DIR" >&2; exit 1; }

cat <<JSON
{
  "schema": 1,
  "versionName": "$version_name",
  "versionCode": $version_code,
  "minSdk": ${min_sdk:-26},
  "artifacts": [$entries
  ]
}
JSON
