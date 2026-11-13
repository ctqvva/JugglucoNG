#!/usr/bin/env python3
"""Run the Nightscout upload pass against a simulated server (a host C++20 compiler is required).

Covers what only shows with a long history behind a live reading: that the reading is not made
to wait for it, that the history still finishes and nothing is stranded or sent twice, and what
a refusal or a restart does to the stored cursors.
"""

import os
from pathlib import Path
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parents[1]
    cpp = root / "Common/src/main/cpp"
    with tempfile.TemporaryDirectory(prefix="juggluco-nightscout-backlog-") as temporary:
        executable = Path(temporary) / "nightscout-backlog-tests"
        subprocess.run([
            os.environ.get("CXX", "c++"), "-std=c++20", "-O1", "-g", "-Wall", "-Wextra",
            f"-I{cpp}",
            str(root / "Common/src/test/cpp/NightscoutBacklogTests.cpp"),
            "-o", str(executable),
        ], check=True)
        return subprocess.run([str(executable)], timeout=120).returncode


if __name__ == "__main__":
    raise SystemExit(main())
