#!/usr/bin/env python3
"""Run native sensor records under 4 KB and 16 KB pages.

A library can be aligned for 16 KB pages, so that Android loads it, and still size its files by the
page. This drives the production sensoren.hpp and SensorGlucoseData.hpp on real files: every family's
record, new and existing, kept across processes and moved between page sizes.

sysconf()'s page size is replaced; the kernel's is the host's. On a Mac with Apple silicon that is
16 KB as well, so the mappings there are the real ones. Needs the LibAscon headers.
"""

import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time

PAGES = (4096, 16384)
# Where the page size may be asked for: mprotect() must be given whole pages.
MAY_ASK = {"libre3/unprotect.hpp"}


def stray_page_sizes(cpp):
    found = []
    for path in sorted(cpp.rglob("*")):
        if path.suffix not in (".cpp", ".hpp", ".c", ".h") or "libjuice" in path.parts:
            continue
        source = re.sub(r"//[^\n]*|/\*.*?\*/", "", path.read_text(errors="replace"), flags=re.S)
        if re.search(r"_SC_PAGE_?SIZE|\bgetpagesize\s*\(|\bAT_PAGESZ\b|\bst_blksize\b", source):
            name = path.relative_to(cpp).as_posix()
            if name not in MAY_ASK:
                found.append(name)
    return found


def main():
    root = Path(__file__).resolve().parents[1]
    cpp = root / "Common/src/main/cpp"
    stray = stray_page_sizes(cpp)
    if stray:
        raise SystemExit(f"these depend on the device's page size: {', '.join(stray)}")
    compiler = os.environ.get("CXX") or shutil.which("clang++") or "c++"
    darwin = sys.platform == "darwin"
    with tempfile.TemporaryDirectory(prefix="juggluco-page-size-") as temporary:
        executable = Path(temporary) / "pages"
        subprocess.run([
            compiler, "-std=gnu++2b", "-O1", "-g", "-w", "-ffunction-sections", "-fdata-sections",
            "-DNOLOG", "-DNOJAVA", "-DNOTAPP", "-DLIBRE3=1", "-DDEXCOM=1", "-DSIBIONICS=1", "-DHAVE_NOPRCTL=1",
            *(["-D__in6_u=__u6_addr"] if darwin else []),
            *(f"-I{cpp / path}" for path in (".", "share", "cmdline", "LibAscon/inc", "include")),
            str(root / "Common/src/test/cpp/PageSizeTests.cpp"),
            "-Wl,-dead_strip" if darwin else "-Wl,--gc-sections",
            "-o", str(executable),
        ], check=True)
        start = int(time.time()) - 5 * 86400

        def run(directory, page, step, written=0):
            return subprocess.run(
                [str(executable), str(directory), step, str(start), str(written)],
                check=True, env={**os.environ, "TEST_PAGE_SIZE": str(page)}, timeout=120, capture_output=step == "layout", text=True).stdout

        def fresh(name):
            directory = Path(temporary) / name
            directory.mkdir()
            return directory

        layouts = {}
        for page in PAGES:
            # A new installation, then two restarts.
            same = fresh(f"same-{page}")
            run(same, page, "create")
            layouts[page] = sorted(run(same, page, "layout").splitlines())
            run(same, page, "reopen", 1)
            run(same, page, "reopen", 2)
            # A backup of it on a device with the other page size, and back.
            other = PAGES[1 - PAGES.index(page)]
            moved = fresh(f"moved-{page}")
            run(moved, page, "create")
            run(moved, other, "reopen", 1)
            run(moved, page, "reopen", 2)
            full = fresh(f"full-{page}")
            run(full, page, "create")
            # Both end on a page here, so nothing past them is mapped.
            for name, size in (("current.dat", 1024 * 20), ("trends.dat", 1024 * 32)):
                for path in full.rglob(name):
                    os.truncate(path, size)
            run(full, page, "full")
            # What the previous version left on a 16 KB device: scan files of no bytes.
            broken = fresh(f"broken-{page}")
            run(broken, page, "create")
            for name in ("current.dat", "trends.dat"):
                for path in broken.rglob(name):
                    path.write_bytes(b"")
            run(broken, page, "repair", 1)
        if layouts[4096] != layouts[16384]:
            different = sorted(set(layouts[4096]) ^ set(layouts[16384]))
            raise SystemExit("records are laid out by the page size:\n" + "\n".join(different))
        # Existing 4 KB installations keep the sizes they have: 409 scans.
        sizes = dict(line.rsplit(" ", 1) for line in layouts[4096])
        for record in ("E007-0M00LIBRE2X", "0D1234ABC"):
            got = int(sizes[f"{record}/current.dat"]), int(sizes[f"{record}/trends.dat"])
            if got != (409 * 20, 409 * 32):
                raise SystemExit(f"{record}: scan storage {got} differs from existing installations'")
    print("native page size: ok")


if __name__ == "__main__":
    main()
