#!/usr/bin/env bash
# Pull an iCan capture snapshot (HCI, SDK DBs, OkHttp cache, logcat) and parse it.
# usage: ADB_SERIAL=<serial> DEST=<dir> collect_snapshot.sh <label> [mem]
set -u
SERIAL="${ADB_SERIAL:?set ADB_SERIAL}"
LABEL="${1:?label}"
MEM="${2:-}"
DEST="${DEST:-/tmp/ican-snaps/$LABEL}"
TSHARK="${TSHARK:-tshark}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

adb -s "$SERIAL" exec-out "su -c sh /data/local/tmp/ican-snapshot.sh $LABEL $MEM" || exit 1
mkdir -p "$DEST"
adb -s "$SERIAL" exec-out "su -c tar -cf - -C /data/local/tmp/ican-snap/$LABEL ." | tar -xf - -C "$DEST"

# HCI -> pcap -> redacted transcript
for f in "$DEST"/*.curf "$DEST"/*.cfa; do
  [ -f "$f" ] || continue
  "$TSHARK" -r "$f" -F pcap -w "$DEST/hci.pcap" 2>/dev/null
done
if [ -f "$DEST/hci.pcap" ]; then
  python3 "$ROOT/tools/ican-capture/hci_transcript.py" "$DEST/hci.pcap" --out "$DEST/transcript.md"
fi

# plaintext SDK log DB
if [ -f "$DEST/db/sinocare_log" ]; then
  sqlite3 "$DEST/db/sinocare_log" \
    "SELECT id, identifier, substr(properties,1,800) FROM LogsEventBean ORDER BY id DESC LIMIT 200;" \
    > "$DEST/sdk_logs.txt" 2>/dev/null
fi

# memory dumps usually live in /data/local/tmp/ru-dump/<pid>; scan on demand:
echo "snapshot: $DEST"
echo "memory (if any): adb -s $SERIAL exec-out \"su -c grep -a ... /data/local/tmp/ru-dump/<pid>/*.bin\""
