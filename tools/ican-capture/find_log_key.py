#!/usr/bin/env python3
"""Find the iCan CE SDK log AES key in a process heap dump and recover the IV.

The key is the 16-character value of the SDK SharedPreferences key "test" (uppercase hex).
The IV is the SDK's AndroidID string (16 characters). Only the first CBC block depends on the IV,
so a candidate key is accepted when the *second* block of a log line decrypts to printable text.

usage:
  find_log_key.py <memdump_dir> <logdir> [--prefix "2026-10-20 12:34"] [--android-id ID]
"""
import argparse
import glob
import os
import re
import sys

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

CAND = re.compile(rb"[0-9A-F]{16}")


def printable(b: bytes) -> bool:
    return all(32 <= c < 127 or c in (9, 10, 13) for c in b)


def first_body_line(logdir: str):
    for p in sorted(glob.glob(os.path.join(logdir, "*.txt"))):
        lines = open(p, encoding="utf-8", errors="replace").read().splitlines()
        for line in lines[1:]:  # line 0 is the RSA key envelope, not AES
            line = line.strip()
            if not line:
                continue
            try:
                ct = bytes.fromhex(line)
            except ValueError:
                continue
            if len(ct) >= 32 and len(ct) % 16 == 0:
                return ct
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("memdump")
    ap.add_argument("logdir")
    ap.add_argument("--prefix", default=None, help="known first 16 plaintext chars, e.g. '2026-10-20 12:34'")
    ap.add_argument("--android-id", default=None, help="SDK AndroidID (16 chars) if known")
    args = ap.parse_args()

    ct = first_body_line(args.logdir)
    if ct is None:
        print("no AES body line found in " + args.logdir, file=sys.stderr)
        return 1

    cands = set()
    for p in glob.glob(os.path.join(args.memdump, "**", "*.bin"), recursive=True):
        d = open(p, "rb").read()
        for m in CAND.finditer(d):
            cands.add(m.group(0))
    print("candidates: %d" % len(cands), flush=True)

    found = []
    for k in cands:
        key = k.decode("latin-1").encode("latin-1")
        c = Cipher(algorithms.AES(key), modes.CBC(bytes(16))).decryptor()
        pt = c.update(ct) + c.finalize()
        if printable(pt[16:32]):
            found.append(key)
    print("keys: %d" % len(found))
    if not found:
        return 1

    for key in found:
        c = Cipher(algorithms.AES(key), modes.CBC(bytes(16))).decryptor()
        d0 = c.update(ct[:16])
        iv = None
        if args.android_id:
            iv = (args.android_id + "\x00" * 16)[:16].encode("latin-1")
        elif args.prefix:
            pref = (args.prefix + "\x00" * 16)[:16].encode("latin-1")
            iv = bytes(a ^ b for a, b in zip(d0, pref))
        print("key=%s iv=%s" % (key.decode(), iv.hex() if iv else "unknown"))
        if iv:
            c = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
            pt = c.update(ct) + c.finalize()
            p = pt[-1]
            if 1 <= p <= 16 and pt[-p:] == bytes([p]) * p:
                pt = pt[:-p]
            print("sample:", pt.decode("utf-8", "replace"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
