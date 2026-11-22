#!/usr/bin/env python3
"""Run the LibreView history walk for a Libre 3 read by the Kotlin driver (host C++20 compiler).

Also checks that every LibreView decision between the Libre 2 and Libre 3 uploads asks the
LibreView predicates, so a managed Libre 3 shell (Libre 2 by elimination natively) cannot slip
back onto the Libre 2 upload.
"""

import os
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parents[1]
    cpp = root / "Common/src/main/cpp"
    for name in ("libreview.cpp", "newlibre3.cpp"):
        source = (cpp / "net/libreview" / name).read_text()
        stray = [m.group(0) for m in re.finditer(r"->isLibre[23]\(\)", source)]
        if stray:
            raise SystemExit(f"{name}: use libreviewLibre2()/libreviewLibre3(), found {stray}")
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
