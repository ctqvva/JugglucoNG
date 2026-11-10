#!/usr/bin/env bash
set -euo pipefail
cd "${1:-$(dirname "$0")/../..}"
ndk=$(sed -n "s/^ *ndkver *= *'\(.*\)'.*/\1/p" Common/build.gradle)
cmake=$(sed -n "s/^ *CMAKEVERSION *= *'\(.*\)'.*/\1/p" Common/build.gradle)
sdk=$(sed -n 's/^ *TARGETSDK *= *\([0-9]*\).*/\1/p' Common/build.gradle)
test -n "$ndk" && test -n "$cmake" && test -n "$sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses <<< "$(printf 'y\n%.0s' {1..100})" > /dev/null
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;$ndk" "cmake;$cmake" "platforms;android-$sdk" 'build-tools;36.0.0'
