#!/usr/bin/env bash
#
# Architecture debt report (plan §2.3, docs/architecture/direction.md).
#
# Report-only: nothing here fails a build. These are counts that are useful to watch trend on but
# whose "correct" value isn't unambiguous enough for a hard gate -- @Volatile fields, runCatching,
# raw Thread/Handler use, object singletons, per-package line counts, oversized files. A shrinking
# number is good news; a growing one is a prompt to look, not a red CI.
#
# The five checks that ARE unambiguous (Composable -> Natives/Applic/SharedPreferences,
# Class.forName from src/main, fallbackToDestructiveMigration) are a hard gate instead, as plain
# JUnit tests that scan sources directly: Common/src/test/java/tk/glucodata/arch/
# ArchitectureGateTests.kt. This script does not duplicate them and CI does not depend on this
# script's exit code -- ArchMetricsReportScriptTest only checks that it runs and prints numbers.
#
# Usage:
#   scripts/arch-metrics.sh              # report to stdout
#   scripts/arch-metrics.sh --root DIR   # measure another tree

set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

while [ $# -gt 0 ]; do
    case "$1" in
        --root) ROOT="$2"; shift 2 ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

SRC="$ROOT/Common/src/main/java $ROOT/Common/src/mobile/java $ROOT/Common/src/wear/java"

count_lines() { # read stdin, print the number of lines
    grep -c '' || true
}

metric_volatile_fields()   { grep -rhE '@Volatile|(^|[[:space:]])volatile[[:space:]]' $SRC 2>/dev/null | count_lines; }
metric_runcatching()       { grep -rh 'runCatching' $SRC 2>/dev/null | count_lines; }
metric_raw_threads()       { grep -rh 'Thread('  $SRC 2>/dev/null | count_lines; }
metric_raw_handlers()      { grep -rh 'Handler(' $SRC 2>/dev/null | count_lines; }
metric_object_singletons() {
    grep -rhE '^[[:space:]]*(internal |private |public )*object [A-Za-z]' $SRC 2>/dev/null | count_lines
}

files_over_2000_lines() {
    for dir in $SRC; do
        [ -d "$dir" ] || continue
        find "$dir" -type f \( -name '*.kt' -o -name '*.java' \) -print0
    done | xargs -0 wc -l 2>/dev/null | awk '$1 > 2000 && $2 != "total" {print $1, $2}' | sort -rn
}

# Lines of code per top-level package under tk.glucodata (drivers/, ui/, alerts/, the files
# directly in the root package, ...), summed across all three flavours. Where growth concentrates
# matters more than a single tree-wide total -- the previous version of this gate hard-failed on
# a single root_package_loc counter, so any line added anywhere in the root package turned CI red.
lines_by_top_level_package() {
    for dir in $SRC; do
        [ -d "$dir/tk/glucodata" ] || continue
        find "$dir/tk/glucodata" -maxdepth 1 -mindepth 1 -type d
        # files directly in the root package are grouped under the literal name "(root)"
        find "$dir/tk/glucodata" -maxdepth 1 -type f \( -name '*.kt' -o -name '*.java' \) -print |
            awk '{print "'"$dir"'/tk/glucodata/(root)"}'
    done | sort -u | while IFS= read -r pkg_path; do
        name="tk.glucodata.$(basename "$pkg_path")"
        if [ "$(basename "$pkg_path")" = "(root)" ]; then
            loc="$(find "$(dirname "$pkg_path")" -maxdepth 1 -type f \( -name '*.kt' -o -name '*.java' \) -exec cat {} + 2>/dev/null | wc -l)"
        else
            loc="$(find "$pkg_path" -type f \( -name '*.kt' -o -name '*.java' \) -exec cat {} + 2>/dev/null | wc -l)"
        fi
        printf '%8s  %s\n' "$loc" "$name"
    done | awk '{loc[$2]+=$1} END {for (p in loc) printf "%8d  %s\n", loc[p], p}' | sort -rn
}

echo "=== report-only architecture metrics (never gates CI) ==="
echo "@Volatile / volatile fields:     $(metric_volatile_fields)"
echo "runCatching occurrences:         $(metric_runcatching)"
echo "raw Thread( calls:               $(metric_raw_threads)"
echo "raw Handler( calls:              $(metric_raw_handlers)"
echo "object singletons:               $(metric_object_singletons)"
echo
echo "--- lines of code by top-level package (tk.glucodata.*) ---"
lines_by_top_level_package
echo
echo "--- files over 2000 lines ---"
over="$(files_over_2000_lines)"
if [ -n "$over" ]; then echo "$over"; else echo "none"; fi
