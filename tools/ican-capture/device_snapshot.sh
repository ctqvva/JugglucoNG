#!/system/bin/sh
# Device-side helper for collect_snapshot.sh. Install via:
#   adb -s <serial> push tools/ican-capture/device_snapshot.sh /data/local/tmp/ican-snapshot.sh
#   adb -s <serial> shell su -c "chmod 755 /data/local/tmp/ican-snapshot.sh"
# usage: ican-snapshot.sh <label> [mem]
LABEL="${1:?label}"
MEM="${2:-}"
ROOT="/data/local/tmp/ican-snap/$LABEL"
mkdir -p "$ROOT/db" "$ROOT/cache"
# HCI snoop files (already rotating by the OS)
for f in /data/log/bt/btsnoop_hci.log /sdcard/btsnoop_hci.log; do
    [ -f "$f" ] && cp "$f" "$ROOT/$(basename "$f").curf" 2>/dev/null
done
# SDK databases
for db in B949532EDCA0B1A8 sinocare_log; do
    src="/data/data/com.sinocare.ican.health.ru/databases/$db"
    [ -f "$src" ] && cp "$src" "$ROOT/db/$db" 2>/dev/null
done
# OkHttp cache (if present)
src="/data/data/com.sinocare.ican.health.ru/cache"
[ -d "$src" ] && cp -r "$src" "$ROOT/cache/" 2>/dev/null
# Recent logcat
logcat -d -v time -s ICANX > "$ROOT/logcat-icanx.txt" 2>/dev/null
# Optional memory dump of the RU process
if [ -n "$MEM" ]; then
    pid=$(pidof com.sinocare.ican.health.ru 2>/dev/null | tr ' ' '\n' | head -1)
    [ -n "$pid" ] && (mkdir -p /data/local/tmp/ru-dump/$pid && cp /proc/$pid/mem /data/local/tmp/ru-dump/$pid/mem.bin 2>/dev/null)
fi
tar -cf - -C "$ROOT" . 2>/dev/null
