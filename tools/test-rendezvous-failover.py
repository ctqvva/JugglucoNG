#!/usr/bin/env python3
"""Run the native rendezvous failover tests (C++20 compiler required)."""

import os
from pathlib import Path
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix="juggluco-rendezvous-") as temporary:
        directory = Path(temporary)
        ice = (root / "Common/src/main/cpp/net/ICE/ICE.cpp").read_text()
        start = ice.index("static RendezvousAttemptResult putDescription(")
        end = ice.index("// Exchanges descriptions", start)
        fixture = (root / "tools/fixtures/rendezvous-description.cpp").read_text()
        description_tests = directory / "rendezvous-description.cpp"
        description_tests.write_text(fixture.replace("/* PUT_DESCRIPTION */", ice[start:end]))
        result = 0
        for source in (root / "Common/src/test/cpp/RendezvousFailoverTests.cpp",
                       description_tests):
            executable = directory / source.stem
            subprocess.run([
                os.environ.get("CXX", "c++"), "-std=c++20", "-O1", "-g", "-pthread",
                "-Wall", "-Werror", f"-I{root / 'Common/src/main/cpp'}",
                str(source), "-o", str(executable),
            ], check=True)
            result |= subprocess.run([str(executable)], timeout=60).returncode
        return result


if __name__ == "__main__":
    raise SystemExit(main())
