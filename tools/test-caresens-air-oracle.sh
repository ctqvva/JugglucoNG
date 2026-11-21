#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 || ! -f "$1" || ! -d "$2" ]]; then
    echo "Usage: $0 /path/to/private-engine.jar /path/to/oracle/output" >&2
    exit 2
fi

care_sens_engine="$(realpath "$1")"
care_sens_oracle="$(realpath "$2")"
care_sens_scratch="$(mktemp -d)"
trap 'rm -r "$care_sens_scratch"' EXIT
cd "$(dirname "$0")/.."

"${CXX:-c++}" -std=c++17 -I . -x c++ -o "$care_sens_scratch/factory" - <<'CPP'
#include "Common/src/main/cpp/air/air.hpp"
int main() {
    static_assert(sizeof(DeviceInfo3Obj) == 446);
    DeviceInfo3Obj config{};
    return fwrite(&config, sizeof(config), 1, stdout) != 1;
}
CPP
"$care_sens_scratch/factory" > "$care_sens_scratch/factory.bin"
care_sens_sources=Common/src/main/java/tk/glucodata/drivers/caresens
kotlinc "$care_sens_sources"/*.kt tools/caresens-air-oracle.kt -include-runtime -d "$care_sens_scratch/oracle.jar"
care_sens_main=tk.glucodata.drivers.caresens.Caresens_air_oracleKt
java -cp "$care_sens_scratch/oracle.jar:$care_sens_engine" "$care_sens_main" "$care_sens_oracle" "$care_sens_scratch/factory.bin"
