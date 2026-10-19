#!/bin/bash
adb install -r "$APK" > inst.log 2>&1
echo "::warning title=install::$(tr '\n' ' ' < inst.log | cut -c1-300)"
adb logcat -c
adb shell monkey -p com.gismonitor -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 25
adb logcat -d -b crash > crash.log 2>&1
adb logcat -d AndroidRuntime:E DEBUG:E libc:F "*:S" > rt.log 2>&1
echo "::error title=crash::$(grep -v '^\s*$' crash.log rt.log | head -40 | tr '\n' '|' | cut -c1-3500)"
echo "::warning title=abis::$(unzip -l "$APK" | grep -o 'lib/[a-z0-9_-]*/' | sort -u | tr '\n' ' ')"
