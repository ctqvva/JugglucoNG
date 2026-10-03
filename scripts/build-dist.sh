#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

target="${1:-phone}"

case "$target" in
  phone)
    tasks=(
      :Common:assembleMobileRelease
    )
    ;;

  phone-all)
    tasks=(
      :Common:assembleMobileRelease
      :Common:assembleMobileReleasedub
    )
    ;;

  watch|wear)
    tasks=(
      :Common:assembleWearRelease
    )
    ;;

  watch-all|wear-all)
    tasks=(
      :Common:assembleWearRelease
      :Common:assembleWearReleasedub
    )
    ;;

  all)
    tasks=(
      :Common:assembleMobileRelease
      :Common:assembleMobileReleasedub
      :Common:assembleWearRelease
      :Common:assembleWearReleasedub
    )
    ;;

  *)
    echo "Usage: $0 {phone|phone-all|watch|watch-all|wear|wear-all|all}"
    exit 2
    ;;
esac

echo "Building signed distribution APK(s): $target"
./gradlew "${tasks[@]}" --stacktrace

echo
echo "Built APKs:"
find Common/build/outputs/apk \
  -type f \
  -name 'JugglucoNG-*.apk' \
  -print | sort
