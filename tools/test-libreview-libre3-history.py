#!/usr/bin/env python3
"""Run the LibreView history walk for a Libre 3 read by the Kotlin driver (host C++20 compiler).

Also checks that every LibreView decision between the Libre 2 and Libre 3 uploads asks the
LibreView predicates, so a managed Libre 3 shell (Libre 2 by elimination natively) cannot slip
back onto the Libre 2 upload, and that the upload pass writes no more history than it sized its
buffer for and keeps an older running Libre 3 eligible.
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
    with tempfile.TemporaryDirectory(prefix="juggluco-libreview-libre3-") as temporary:
        executable = Path(temporary) / "managed-libre3-history-tests"
        subprocess.run([
            os.environ.get("CXX", "c++"), "-std=c++20", "-O1", "-g", "-Wall", "-Wextra",
            f"-I{cpp}",
            str(root / "Common/src/test/cpp/ManagedLibre3HistoryTests.cpp"),
            "-o", str(executable),
        ], check=True)
        return subprocess.run([str(executable)], timeout=60).returncode


if __name__ == "__main__":
    raise SystemExit(main())
