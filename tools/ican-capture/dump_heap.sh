#!/system/bin/sh
# Heap-only snapshot of an app process (root, no ptrace; safe against DenyList'd apps).
# usage: dump_heap.sh <pid> <outdir> [cap_mb_per_region]
set -u
PID="$1"
OUT="$2"
CAP_MB="${3:-64}"
CAP=$((CAP_MB * 1024 * 1024))
rm -rf "$OUT"
mkdir -p "$OUT"
awk '$2 ~ /^rw/ {print $1"|"$6}' /proc/$PID/maps | while IFS='|' read -r range name; do
  case "$name" in
    *dalvik-main*|*dalvik-alloc*|*dalvik-non*|*dalvik-large*|*dalvik-free*|*libc_malloc*|"") ;;
    *) continue ;;
  esac
  start=${range%-*}
  end=${range#*-}
  s=$((0x$start))
  e=$((0x$end))
  len=$((e - s))
  if [ "$len" -le 262144 ]; then continue; fi
  if [ "$len" -gt "$CAP" ]; then len="$CAP"; fi
  skip=$((s / 4096))
  blocks=$((len / 4096))
  if [ "$blocks" -le 0 ]; then continue; fi
  dd if=/proc/$PID/mem bs=4096 skip=$skip count=$blocks of="$OUT/r_$start.bin" 2>/dev/null
done
echo "files=$(ls "$OUT" | wc -l) size=$(du -sh "$OUT" | cut -f1)"
