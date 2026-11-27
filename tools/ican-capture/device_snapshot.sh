#!/system/bin/sh
# Device-side helper for collect_snapshot.sh. Install via:
#   adb -s <serial> push tools/ican-capture/device_snapshot.sh /data/local/tmp/ican-snapshot.sh
#   adb -s <serial> shell su -c "chmod 755 /data/local/tmp/ican-snapshot.sh"
# usage: ican-snapshot.sh <label> [mem]
# Also needs dump_heap.sh on the device for the optional memory dump:
#   adb -s <serial> push tools/ican-capture/dump_heap.sh /data/local/tmp/ican-dumpheap.sh
LABEL="${1:?label}"
MEM="${2:-}"
ROOT="/data/local/tmp/ican-snap/$LABEL"
mkdir -p "$ROOT/db" "$ROOT/cache"
# HCI snoop files (already rotating by the OS; stock and MIUI/MediaTek paths)
for f in /data/log/bt/btsnoop_hci.log /sdcard/btsnoop_hci.log /data/misc/bluetooth/logs/BT_HCI_*.cfa.curf; do
    [ -f "$f" ] || continue
    name=$(basename "$f")
    case "$name" in
        *.curf) dest="$name" ;;
        *) dest="$name.curf" ;;
    esac
    cp "$f" "$ROOT/$dest" 2>/dev/null
done
# SDK databases, including WAL/SHM sidecars (live DB may still be in WAL)
for db in B949532EDCA0B1A8 sinocare_log; do
    src="/data/data/com.sinocare.ican.health.ru/databases/$db"
    [ -f "$src" ] && cp "$src" "$ROOT/db/$db" 2>/dev/null
    for suffix in -wal -shm; do
        [ -f "$src$suffix" ] && cp "$src$suffix" "$ROOT/db/$db$suffix" 2>/dev/null
    done
done
# OkHttp cache (if present)
src="/data/data/com.sinocare.ican.health.ru/cache"
[ -d "$src" ] && cp -r "$src" "$ROOT/cache/" 2>/dev/null
# Recent logcat
logcat -d -v time -s ICANX > "$ROOT/logcat-icanx.txt" 2>/dev/null
# Optional memory dump of the RU process (mapped regions only; /proc/<pid>/mem
# is not a regular file and cannot be copied wholesale)
if [ -n "$MEM" ]; then
    pid=$(pidof com.sinocare.ican.health.ru 2>/dev/null | tr ' ' '\n' | head -1)
    if [ -n "$pid" ]; then
        out="/data/local/tmp/ru-dump/$pid"
        helper=""
        for f in /data/local/tmp/ican-dumpheap.sh /data/local/tmp/dump_heap.sh; do
            [ -f "$f" ] && helper="$f" && break
        done
        if [ -n "$helper" ]; then
            sh "$helper" "$pid" "$out" 2>/dev/null
        else
            echo "dump_heap.sh not found on device; memory dump skipped" >&2
        fi
    fi
fi
tar -cf - -C "$ROOT" . 2>/dev/null
