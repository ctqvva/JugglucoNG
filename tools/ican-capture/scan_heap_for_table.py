#!/usr/bin/env python3
"""Scan heap snapshots for the iCan calibration table and provisioning markers.

usage: scan_heap_for_table.py <snapshot_dir> [extra_hex_blob ...]

Looks for:
  * ASCII markers: correctCode / parameterCode / full code / x1..x25 / cgmVal
  * float32 sequences starting with the values shared by the known table
    (x2=32.0, x3=25.0) and prints the surrounding 25 floats
  * optional extra byte blobs (e.g. captured F2 ciphertexts, given as hex)
"""
import glob
import os
import struct
import tarfile
import sys

MARKERS = [b"correctCode", b"parameterCode", b"full code", b"writeCorrectCode",
           b"writeInitParameter", b'"x1"', b'"x25"', b"cgmVal", b"parameter"]

PAIR = struct.pack("<2f", 32.0, 25.0)          # x2, x3 of the known RU table
HEAD = struct.pack("<4f", 0.015, 32.0, 25.0, 1.0)  # x1..x4
TABLE25 = struct.pack("<25f",
    0.015, 32.0, 25.0, 1.0, 0.8, 0.3, 40.0, 0.5, 0.5, 1.2, 13.0, 1.4, 0.4,
    3.5, 15.0, 15.0, 0.1, 0.6, 0.2, 0.6, 0.1, 16.0, 1.0, 2.1, 2.2)


def floats_at(d, off, n=25):
    if off + 4 * n > len(d):
        return None
    return struct.unpack("<%df" % n, d[off:off + 4 * n])


def gather_blobs(snap):
    """Collect scan candidates as (name, bytes) pairs.

    The heap watcher snapshots arrive as plain .bin dumps and as .tar archives of
    them; both feeds go through the same scan.
    """
    for p in sorted(glob.glob(os.path.join(snap, "**", "*.bin"), recursive=True)):
        yield p, open(p, "rb").read()
    for p in sorted(glob.glob(os.path.join(snap, "**", "*.tar"), recursive=True)):
        try:
            with tarfile.open(p, "r:*") as tar:
                for member in tar.getmembers():
                    if member.isfile():
                        yield "%s!%s" % (p, member.name), tar.extractfile(member).read()
        except tarfile.TarError as exc:
            print("skip unreadable archive %s: %s" % (p, exc))
    for p in sorted(glob.glob(os.path.join(snap, "**", "*.tgz"), recursive=True)):
        try:
            with tarfile.open(p, "r:gz") as tar:
                for member in tar.getmembers():
                    if member.isfile():
                        yield "%s!%s" % (p, member.name), tar.extractfile(member).read()
        except tarfile.TarError as exc:
            print("skip unreadable archive %s: %s" % (p, exc))


def main():
    snap = sys.argv[1]
    blobs = [bytes.fromhex(h) for h in sys.argv[2:]]
    files = gather_blobs(snap)
    count = 0
    for p, d in files:
        count += 1
        for m in MARKERS:
            i = d.find(m)
            if i >= 0:
                ctx = d[max(0, i - 40):i + 120]
                ctx = "".join(chr(c) if 32 <= c < 127 else "." for c in ctx)
                print("MARKER %-18s %s +0x%x  %s" % (m.decode(), p, i, ctx))
        for name, sig in (("head", HEAD), ("pair", PAIR), ("table25", TABLE25)):
            i = d.find(sig)
            if i >= 0:
                vals = floats_at(d, i - 4, 25)
                print("FLOATS %-8s %s +0x%x  %s" % (
                    name, p, i, " ".join("%.4g" % v for v in (vals or [])) if vals else ""))
        for b in blobs:
            i = d.find(b)
            if i >= 0:
                print("BLOB %s +0x%x  %s" % (p, i, b.hex()))
    print("files:", count)


if __name__ == "__main__":
    main()
