#!/usr/bin/env python3
"""Run the native lifetime rules for every sensor family.

Compiles the production sensoren.hpp and SensorGlucoseData.hpp for the host (C++23, clang) and drives
real records in a temporary directory, so nothing about how native classifies or stores a sensor is
re-implemented here. Needs the LibAscon submodule for a header.
"""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def main():
    root = Path(__file__).resolve().parents[1]
    cpp = root / "Common/src/main/cpp"
    compiler = os.environ.get("CXX") or shutil.which("clang++") or "c++"
    darwin = sys.platform == "darwin"
    with tempfile.TemporaryDirectory(prefix="juggluco-sensor-lifetime-") as temporary:
        executable = Path(temporary) / "lifetime"
        subprocess.run([
            compiler, "-std=gnu++2b", "-O1", "-g", "-w", "-ffunction-sections", "-fdata-sections",
            "-DNOLOG", "-DNOJAVA", "-DNOTAPP", "-DLIBRE3=1", "-DDEXCOM=1", "-DSIBIONICS=1", "-DHAVE_NOPRCTL=1",
            *(["-D__in6_u=__u6_addr"] if darwin else []),
            *(f"-I{cpp / path}" for path in (".", "share", "cmdline", "LibAscon/inc", "include")),
            str(root / "Common/src/test/cpp/SensorLifetimeTests.cpp"),
            "-Wl,-dead_strip" if darwin else "-Wl,--gc-sections",
            "-o", str(executable),
        ], check=True)
        return subprocess.run([str(executable), str(Path(temporary) / "records")], timeout=120).returncode


if __name__ == "__main__":
    raise SystemExit(main())
