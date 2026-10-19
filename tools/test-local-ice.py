#!/usr/bin/env python3
"""Run native LAN signaling fault-injection tests (C/C++20 compiler and zlib required)."""

import os
from pathlib import Path
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parents[1]
    cpp = root / "Common/src/main/cpp"
    if not (cpp / "libjuice/include/juice/juice.h").is_file():
        raise SystemExit("Initialize libjuice first: git submodule update --init")
    with tempfile.TemporaryDirectory(prefix="juggluco-local-ice-") as temporary:
        build = Path(temporary)
        objects = []
        for name in ("ascon_permutations", "ascon_buffering", "ascon_aead_common", "ascon_aead128a"):
            target = build / f"{name}.o"
            subprocess.run([
                os.environ.get("CC", "cc"), "-std=gnu99", "-O1", "-g", "-DNOLOG",
                f"-I{cpp / 'LibAscon/inc'}", f"-I{cpp / 'share'}", "-c",
                str(cpp / f"LibAscon/src/{name}.c"), "-o", str(target),
            ], check=True)
            objects.append(str(target))
        executable = build / "local-ice-tests"
        subprocess.run([
            os.environ.get("CXX", "c++"), "-std=c++20", "-O1", "-g", "-pthread",
            "-DNOLOG", f"-I{cpp}", f"-I{cpp / 'share'}",
            f"-I{cpp / 'LibAscon/inc'}",
            str(root / "Common/src/test/cpp/LocalICESignalTests.cpp"),
            *objects, "-lz", "-o", str(executable),
        ], check=True)
        return subprocess.run([str(executable)], timeout=60).returncode


if __name__ == "__main__":
    raise SystemExit(main())
