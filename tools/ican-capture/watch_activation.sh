#!/bin/sh
# Snapshot the vendor app's heap every N seconds during a sensor activation.
# Snapshots stay on the phone (tarred) and are pulled once at the end.
#
# usage: watch_activation.sh <package> <outdir> [interval_s] [max_snapshots]
set -u
PKG="${1:?package}"
OUT="${2:?outdir}"
IV="${3:-10}"
MAX="${4:-8}"
PHONE_DIR=/data/local/tmp/actsnaps

mkdir -p "$OUT"
adb shell su -c "rm -rf $PHONE_DIR; mkdir -p $PHONE_DIR" >/dev/null 2>&1
echo "watching $PKG every ${IV}s, max $MAX snapshots -> $OUT"

i=0
while [ "$i" -lt "$MAX" ]; do
  PID=$(adb shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')
  if [ -n "$PID" ]; then
    i=$((i + 1))
    TS=$(date +%H%M%S)
    adb shell su -c "sh /data/local/tmp/dump_heap.sh $PID $PHONE_DIR/$TS 64" 2>/dev/null | tail -1 | sed "s/^/  $TS /"
    adb shell su -c "tar -cf $PHONE_DIR/snap-$TS.tar -C $PHONE_DIR/$TS . && rm -rf $PHONE_DIR/$TS" >/dev/null 2>&1
  fi
  sleep "$IV"
done

echo "snapshots on phone:"
adb shell su -c "ls -lh $PHONE_DIR/*.tar" 2>/dev/null
for f in $(adb shell su -c "ls $PHONE_DIR/*.tar" 2>/dev/null | tr -d '\r'); do
  adb pull "$f" "$OUT/" >/dev/null 2>&1 && echo "pulled $(basename "$f")"
done
