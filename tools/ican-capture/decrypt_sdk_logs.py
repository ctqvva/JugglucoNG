#!/usr/bin/env python3
"""Decrypt iCan CE SDK log files (BleFileManager format).

Log format: line 1 = hex RSA block (key envelope, skipped), lines 2..n = hex AES-128-CBC/PKCS5
ciphertext. Key = the SDK SharedPreferences value "test" (16 ASCII chars); IV = the SDK's
AndroidID string (16 ASCII chars).

usage:
  decrypt_sdk_logs.py <logdir> <key> <android_id|iv_hex> [--out out.txt]
"""
import argparse
import glob
import os
import sys

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


def key_bytes(s: str) -> bytes:
    return (s + "\x00" * 16)[:16].encode("latin-1")


def iv_bytes(s: str, hex_hint: bool) -> bytes:
    if hex_hint and len(s) == 32:
        return bytes.fromhex(s)
    return key_bytes(s)


def decrypt_line(line: str, key: bytes, iv: bytes) -> str:
    ct = bytes.fromhex(line)
    if not ct or len(ct) % 16:
        raise ValueError("bad block length")
    c = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
    pt = c.update(ct) + c.finalize()
    p = pt[-1]
    if 1 <= p <= 16 and pt[-p:] == bytes([p]) * p:
        pt = pt[:-p]
    return pt.decode("utf-8", "replace")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("logdir")
    ap.add_argument("key")
    ap.add_argument("iv")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    key = key_bytes(args.key)
    iv = iv_bytes(args.iv, hex_hint=True)
    files = sorted(glob.glob(os.path.join(args.logdir, "*.txt")))
    if not files:
        print("no *.txt in " + args.logdir, file=sys.stderr)
        return 1

    out = open(args.out, "w") if args.out else sys.stdout
    try:
        for path in files:
            lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
            out.write("=== %s (%d lines)\n" % (os.path.basename(path), len(lines)))
            ok = bad = 0
            for i, line in enumerate(lines):
                line = line.strip()
                if not line:
                    continue
                try:
                    out.write(decrypt_line(line, key, iv) + "\n")
                    ok += 1
                except Exception:
                    bad += 1
            out.write("--- decrypted=%d failed=%d\n" % (ok, bad))
    finally:
        if args.out:
            out.close()
    if args.out:
        print("wrote " + args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
