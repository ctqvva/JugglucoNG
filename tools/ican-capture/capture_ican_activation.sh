#!/usr/bin/env bash
# Capture one iCan activation by the vendor app: logcat + HCI snoop, then summarise.
#
# Run this BEFORE opening the vendor app on the phone that will activate the sensor.
# You need: a fresh sensor, the vendor app installed, adb. Root makes the snoop pull easy;
# without it, take a bug report afterwards instead (the HCI log is inside it).
#
#   ./capture_ican_activation.sh ru.luch.testcgm ./ican-activation
#
# Then activate the sensor exactly as a normal user would, wait ~15 minutes, press Enter.
set -u

PKG="${1:-com.sinocare.ican.health.ru}"
OUT="${2:-$PWD/ican-activation}"
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"

echo "== package: $PKG"
adb shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName || echo "!! $PKG is not installed"

echo
echo "== HCI snoop log"
state="$(adb shell su -c "settings get secure bluetooth_hci_log" 2>/dev/null | tr -d '\r')"
echo "   bluetooth_hci_log = ${state:-unknown}"
if [ "${state:-0}" != "1" ]; then
    echo "   Enabling it through the shell (falls back to Developer options if this fails):"
    adb shell su -c "settings put secure bluetooth_hci_log 1" 2>/dev/null
    echo "   If it stays 0: Developer options -> 'Enable Bluetooth HCI snoop log', then re-run."
fi
adb shell su -c "ls -t /data/misc/bluetooth/logs 2>/dev/null | head -3" | sed 's/^/   existing: /'

echo
echo "== starting logcat (before the app is opened)"
adb logcat -c
adb logcat -v threadtime > "$OUT/logcat.txt" &
LOGCAT_PID=$!

echo
echo "Now: open $PKG, add the fresh sensor, activate it normally, and let it sit ~15 minutes."
echo "Do not connect JugglucoNG to the sensor during the activation — it would share the link."
echo "Press Enter when you are done."
read -r _

kill "$LOGCAT_PID" 2>/dev/null
wait "$LOGCAT_PID" 2>/dev/null

echo
echo "== pulling the HCI log"
HCI_DIR=/data/misc/bluetooth/logs
SNOOP_NAME="$(adb shell su -c "ls -t $HCI_DIR 2>/dev/null | grep -E '\.(curf|cfa)$|btsnoop' | head -1" | tr -d '\r')"
SNOOP=""
if [ -n "$SNOOP_NAME" ]; then
    # MIUI/MediaTek writes BT_HCI_*.cfa.curf; stock Android writes btsnoop_hci.log.
    if adb shell su -c "cat $HCI_DIR/$SNOOP_NAME" > "$OUT/$SNOOP_NAME" 2>/dev/null && [ -s "$OUT/$SNOOP_NAME" ]; then
        SNOOP="$OUT/$SNOOP_NAME"
        echo "   pulled $SNOOP_NAME ($(wc -c < "$SNOOP" | tr -d ' ') bytes)"
    fi
fi
if [ -z "$SNOOP" ]; then
    echo "   could not pull it. Take a bug report (Developer options -> Bug report) and extract the"
    echo "   HCI log from the zip; logcat was captured anyway."
fi

echo
echo "== summary (redacted; --no-redact for a private copy)"
if [ -n "$SNOOP" ]; then
    python3 "$HERE/ican_capture_summary.py" --logcat "$OUT/logcat.txt" --snoop "$SNOOP" | tee "$OUT/summary.txt"
else
    python3 "$HERE/ican_capture_summary.py" --logcat "$OUT/logcat.txt" | tee "$OUT/summary.txt"
fi

echo
echo "Captured into $OUT:"
ls -l "$OUT" 2>/dev/null
echo
echo "Attach summary.txt (and the HCI log if you are comfortable sharing it) to issue #679."
echo "Nothing is uploaded automatically."
