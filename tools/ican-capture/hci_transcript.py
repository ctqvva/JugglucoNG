#!/usr/bin/env python3
"""Export a redacted ATT transcript from a btsnoop/pcap HCI capture.

Uses tshark for dissection (robust across MIUI/Android HCI formats), then
redacts auth material and serial-like ASCII runs. Never writes MACs or
account ids: only ATT payloads are exported.

Usage:
  python3 hci_transcript.py capture.curf --out transcript.md [--after EPOCH] [--before EPOCH]
"""
import argparse
import os
import re
import shutil
import subprocess
import sys

TSHARK = os.environ.get("TSHARK", "tshark")

OPCODES = {
    0x01: "Error Response", 0x02: "MTU Request", 0x03: "MTU Response",
    0x04: "Find Information Request", 0x05: "Find Information Response",
    0x08: "Read By Type Request", 0x09: "Read By Type Response",
    0x0A: "Read Request", 0x0B: "Read Response",
    0x0C: "Read Blob Request", 0x0D: "Read Blob Response",
    0x10: "Read By Group Type Request", 0x11: "Read By Group Type Response",
    0x12: "Write Request", 0x13: "Write Response",
    0x1B: "Notify", 0x1D: "Indicate", 0x1E: "Confirm",
    0x52: "Write Command",
}
HOST_TO_CTRL = {0x02, 0x03, 0x04, 0x08, 0x0A, 0x0C, 0x10, 0x12, 0x52}
UUID16 = {
    0x0021: "RACP", 0x0022: "RACP CCCD", 0x0024: "SSCP", 0x0025: "SSCP CCCD",
    0x0016: "CGM Measurement", 0x0017: "Measurement CCCD", 0x001D: "Session Start Time",
    0x002C: "Serial Number",
}


def find_tshark():
    if os.path.sep in TSHARK or shutil.which(TSHARK):
        return TSHARK
    for p in (
        "/Applications/Wireshark.app/Contents/MacOS/tshark",
        "/usr/local/bin/tshark",
        "/opt/homebrew/bin/tshark",
    ):
        if os.path.exists(p):
            return p
    return TSHARK


def extract(path):
    tshark = find_tshark()
    cmd = [
        tshark, "-r", path, "-Y", "btatt",
        "-T", "fields",
        "-e", "frame.time_epoch", "-e", "btatt.opcode",
        "-e", "btatt.handle", "-e", "btatt.value",
    ]
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        sys.stderr.write(out.stderr)
        return []
    rows = []
    for line in out.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) < 3 or not parts[0]:
            continue
        try:
            ts = float(parts[0])
        except ValueError:
            continue
        op = int(parts[1], 16) if parts[1] else None
        handle_raw = parts[2].split(",")[0] if parts[2] else ""
        handle = int(handle_raw, 16) if handle_raw else None
        value_hex = parts[3].replace(":", "").replace(",", "") if len(parts) > 3 else ""
        try:
            value = bytes.fromhex(value_hex) if value_hex else b""
        except ValueError:
            value = b""
        rows.append((ts, op, handle, value))
    return rows


def redact(data: bytes) -> str:
    tokens = []
    i = 0
    while i < len(data):
        if 0x20 <= data[i] < 0x7F:
            j = i
            while j < len(data) and 0x20 <= data[j] < 0x7F:
                j += 1
            run = data[i:j]
            m = re.search(rb"[0-9A-Za-z][0-9A-Za-z-]{10,}", run)
            if m:
                tokens.extend("%02x" % b for b in run[:m.start()])
                tokens.append("<SN>")
                tokens.extend("%02x" % b for b in run[m.end():])
            else:
                tokens.extend("%02x" % b for b in run)
            i = j
        else:
            tokens.append("%02x" % data[i])
            i += 1
    return " ".join(tokens)


def is_auth(op, handle, val) -> bool:
    if handle != 0x0024 or not val:
        return False
    if op in (0x12, 0x52) and val[0] in (0xF3, 0xF4):
        return True
    if op == 0x1D and val[:3] in (b"\x06\xf3\x01", b"\x1c\xf4\x01", b"\x1c\xf4\x02"):
        return True
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("capture")
    ap.add_argument("--out", required=True)
    ap.add_argument("--after", type=float, default=None)
    ap.add_argument("--before", type=float, default=None)
    ap.add_argument("--title", default="iCan CGM — ATT transcript")
    args = ap.parse_args()

    rows = extract(args.capture)
    t0 = None
    out_rows = []
    for ts, op, handle, val in rows:
        if args.after is not None and ts < args.after:
            continue
        if args.before is not None and ts > args.before:
            continue
        if t0 is None:
            t0 = ts
        if op is None:
            continue
        direction = "H→S" if op in HOST_TO_CTRL else "S→H"
        auth = is_auth(op, handle, val)
        payload = "<auth>" if auth else (redact(val) if val else "")
        h = ("0x%04X %s" % (handle, UUID16.get(handle, ""))) if handle is not None else "-"
        note = "auth (payload hidden)" if auth else ""
        out_rows.append((ts - t0, direction, OPCODES.get(op, "0x%02X" % op), h, payload, note))

    with open(args.out, "w") as f:
        f.write("# %s\n\n" % args.title)
        f.write("ATT layer only; MACs and account ids are not exported.\n")
        f.write("Auth payloads are redacted; serial-like runs are `<SN>`.\n\n")
        f.write("| t+ (s) | dir | ATT | handle | payload | note |\n|---|---|---|---|---|---|\n")
        for dt, d, op, h, payload, note in out_rows:
            f.write("| %.3f | %s | %s | `%s` | %s | %s |\n" % (
                dt, d, op, h, "`%s`" % payload if payload else "", note))
        f.write("\n# rows: %d\n" % len(out_rows))
    print("wrote %s (%d rows)" % (args.out, len(out_rows)))


if __name__ == "__main__":
    main()
