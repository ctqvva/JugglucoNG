#!/usr/bin/env python3
"""Run the LibreView history walk for a Libre 3 read by the Kotlin driver (host C++20 compiler).

Also checks that every LibreView decision between the Libre 2 and Libre 3 uploads asks the
LibreView predicates, so a managed Libre 3 shell (Libre 2 by elimination natively) cannot slip
back onto the Libre 2 upload, and that the upload pass writes no more history than it sized its
buffer for and keeps an older running Libre 3 eligible. Last, the record's short name for such a
shell is its serial.
"""

import os
from pathlib import Path
import re
import subprocess
import tempfile


def function(source, marker):
    begin = source.index(marker)
    end = source.index("{", begin) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[begin:end]


def main():
    root = Path(__file__).resolve().parents[1]
    cpp = root / "Common/src/main/cpp"
    for name in ("libreview.cpp", "newlibre3.cpp"):
        source = (cpp / "net/libreview" / name).read_text()
        stray = [m.group(0) for m in re.finditer(r"->isLibre[23]\(\)", source)]
        if stray:
            raise SystemExit(f"{name}: use libreviewLibre2()/libreviewLibre3(), found {stray}")
    upload = (cpp / "net/libreview/newlibre3.cpp").read_text()
    writer = function(upload, "static int managedhistory(")
    # The buffer is sized from the end slot counted first; backfill can store more minutes
    # before the writer runs, so the writer must not look the end up again.
    if "managedendslot(" in writer or "endSlot(" in writer:
        raise SystemExit("managedhistory must write up to the end slot the buffer was sized for")
    if "managedhistory(sens,managedends[i-firstsensor]" not in upload:
        raise SystemExit("sendlibre3viewdata must pass the counted end slot to managedhistory")
    # Two Libre 3 sensors can run at once: success must not move the start past the older one,
    # and every sensor that wrote history gets its own cursor.
    if "settings->data()->startlibre3view=nextsensor;" in upload:
        raise SystemExit("a successful upload must keep the earliest unfinished Libre 3 eligible")
    if "for(const auto &[index,next]:sentuntil)" not in upload:
        raise SystemExit("each sensor that wrote history must get its cursor")
    header = (cpp / "SensorGlucoseData.hpp").read_text()
    if "std::array<char, 12> paddedshortname = padshortname(sensordir);" not in header:
        raise SystemExit("SensorGlucoseData must keep the padded short name of its directory")
    names = NAMES_TEST.replace("@METHODS@", "\n".join(
        function(header, marker) for marker in (
            "  [[nodiscard]] std::string_view sensid() const",
            "  static std::array<char, 12> padshortname(",
            "  [[nodiscard]] const sensorname_t *shortsensorname() const",
            "  [[nodiscard]] std::string_view showsensorname() const",
        ))).replace("@FIXEDVIEW@", function(
            (cpp / "net/watchserver/common.hpp").read_text(), "inline std::string_view fixedsensorview("))
    with tempfile.TemporaryDirectory(prefix="juggluco-libreview-libre3-") as temporary:
        executable = Path(temporary) / "managed-libre3-history-tests"
        subprocess.run([
            os.environ.get("CXX", "c++"), "-std=c++20", "-O1", "-g", "-Wall", "-Wextra",
            f"-I{cpp}",
            str(root / "Common/src/test/cpp/ManagedLibre3HistoryTests.cpp"),
            "-o", str(executable),
        ], check=True)
        if subprocess.run([str(executable)], timeout=60).returncode:
            return 1
        names_source = Path(temporary) / "names.cpp"
        names_source.write_text(names)
        names_executable = Path(temporary) / "managed-libre3-names"
        subprocess.run([
            os.environ.get("CXX", "c++"), "-std=c++20", "-O1", "-g", "-Wall", "-Wextra",
            str(names_source), "-o", str(names_executable),
        ], check=True)
        return subprocess.run([str(names_executable)], timeout=60).returncode


# The production name methods over a stub record. The web server's ids, Clone's serial and the
# Java name getters all read shortsensorname(); a nine-character serial must not come out as the
# path's last 11 characters ("s/0D1234ABC").
NAMES_TEST = r"""
#include <algorithm>
#include <array>
#include <cassert>
#include <cstdio>
#include <string>
#include <string_view>
struct Info { int family = 0; };
struct SensorGlucoseData {
  std::string sensordir;
  Info info;
  typedef std::array<char, 11> sensorname_t;
  std::array<char, 12> paddedshortname;
  SensorGlucoseData(std::string dir, int family)
      : sensordir(std::move(dir)), info{family}, paddedshortname(padshortname(sensordir)) {}
  const Info *getinfo() const { return &info; }
  bool isSibionics() const { return false; }
  bool isLibre3() const { return info.family == 3; }
  bool isAiDex() const { return false; }
  bool isAir() const { return false; }
  bool isManagedLibre3() const { return info.family == 9; }
@METHODS@
};
using sensorname_t = SensorGlucoseData::sensorname_t;
@FIXEDVIEW@
int main() {
  const SensorGlucoseData managed("/data/files/sensors/0D1234ABC", 9);
  assert(fixedsensorview(managed.shortsensorname()) == "0D1234ABC");
  assert(std::string(managed.shortsensorname()->data()) == "0D1234ABC");
  assert(managed.showsensorname() == "0D1234ABC");
  // Anything else keeps its old name, so already-sent ids do not change.
  const SensorGlucoseData other("/data/files/sensors/0D1234ABC", 0);
  assert(std::string(other.shortsensorname()->data(), 11) == "s/0D1234ABC");
  const SensorGlucoseData legacy("/data/files/sensors/E07A-XX0D1234ABC", 3);
  assert(std::string(legacy.shortsensorname()->data(), 11) == "XX0D1234ABC");
  assert(legacy.showsensorname() == "0D1234ABC");
  std::puts("managed Libre 3 names: ok");
  return 0;
}
"""


if __name__ == "__main__":
    raise SystemExit(main())
