#!/usr/bin/env python3
"""Summarise one iCan activation capture (vendor app) for issue #679 / PR #680.

Two inputs, either alone is useful:

  --logcat FILE   `adb logcat -v threadtime` captured during the activation. The vendor SDK
                  logs the table and, on the initParameter path, the hex of every written chunk.
  --snoop FILE    btsnoop_hci.log (rooted: /data/misc/bluetooth/logs/btsnoop_hci.log, or the one
                  inside a bug report). Parsed with tshark: every ATT write/notification with its
                  timestamp, grouped into F3/F4/F6/F2/F7 transactions.

What it prints:

  * which provisioning path ran (`writeCorrectCode` = server table, `writeInitParameter` =
    SN-derived) — the two use the same native chunker with a different 4th flag;
  * the recovered `CodeValueBean` JSON (`full code:` / `parameterCode:` lines) — the 25 floats;
  * the F6 state before and after (0xFFFF = unprovisioned);
  * the F2 chunk run: count, per-chunk length, hex — the framing ground truth PR #680 needs;
  * 2AAA / 2AA9 reads, as the acceptance baseline.

The output is redacted by default: 16-hex serials, 12-digit user ids and MAC addresses are masked.
Pass --no-redact only for a private copy.

Usage:
  python3 ican_capture_summary.py --logcat ican-activation.logcat.txt --snoop btsnoop_hci.log
"""

import argparse
import os
import re
import subprocess
import sys

TSHARK_CANDIDATES = [
    "tshark",
    "/Applications/Wireshark.app/Contents/MacOS/tshark",
    "/usr/bin/tshark",
]

MAC_RE = re.compile(r"\b([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b")
SERIAL_RE = re.compile(r"\b[0-9A-Fa-f]{16,32}\b")
USERID_RE = re.compile(r"\b\d{12}\b")


def redact(text, enabled=True):
    if not enabled:
        return text
    text = MAC_RE.sub("xx:xx:xx:xx:xx:xx", text)
    text = USERID_RE.sub("<user-id>", text)
    text = SERIAL_RE.sub("<serial>", text)
    return text


def find_tshark():
    for candidate in TSHARK_CANDIDATES:
        if os.path.sep in candidate:
            if os.path.exists(candidate):
                return candidate
        else:
            from shutil import which

            found = which(candidate)
            if found:
                return found
    return None


# --------------------------------------------------------------------------------------------
# logcat
# --------------------------------------------------------------------------------------------

LOGCAT_PATTERNS = {
    "path_correct_code": re.compile(r"writeCorrectCode: write correct code again|writeCorrectCode: index", re.I),
    "path_init_parameter": re.compile(r"writeInitParameter: index|writeInitParameter failed", re.I),
    "full_code": re.compile(r"full code:\s*(\{.*\})", re.I),
    "parameter_code": re.compile(r"parameterCode:\s*(\{.*\})", re.I),
    "chunk_hex": re.compile(r"writeInitParameter: index:\s*(\d+).*?code:\s*([0-9a-fA-F]+)", re.I),
    "correct_code_index": re.compile(r"writeCorrectCode: index:\s*(\d+),\s*full code:\s*(\{.*\})", re.I),
    "random_command": re.compile(r"RANDOM_COMMAND_CODE:\s*([0-9a-fA-F]+)", re.I),
    "encrypt_random_response": re.compile(r"encryptRandomResponse:\s*([0-9a-fA-F]+)", re.I),
    "f6": re.compile(r"(?:1c\s*f6\s*01|Sensor info:)\s*([0-9a-fA-F]{2})\s*([0-9a-fA-F]{2})", re.I),
    "f6_decimal": re.compile(r"read code response:\s*(\d+)", re.I),
}


def summarise_logcat(path, do_redact):
    try:
        lines = open(path, "r", errors="replace").read().splitlines()
    except OSError as exc:
        print("logcat: cannot read %s (%s)" % (path, exc))
        return

    print("== logcat: %s (%d lines)" % (path, len(lines)))

    paths = {"correctCode (server table)": 0, "initParameter (SN-derived)": 0}
    tables, parameter_codes, chunks, responses, f6_values = [], [], [], [], []
    for line in lines:
        if LOGCAT_PATTERNS["path_correct_code"].search(line):
            paths["correctCode (server table)"] += 1
        if LOGCAT_PATTERNS["path_init_parameter"].search(line):
            paths["initParameter (SN-derived)"] += 1
        m = LOGCAT_PATTERNS["full_code"].search(line)
        if m:
            tables.append(m.group(1))
        m = LOGCAT_PATTERNS["parameter_code"].search(line)
        if m:
            parameter_codes.append(m.group(1))
        m = LOGCAT_PATTERNS["chunk_hex"].search(line)
        if m:
            chunks.append((int(m.group(1)), m.group(2)))
        m = LOGCAT_PATTERNS["random_command"].search(line)
        if m:
            responses.append(("RANDOM_COMMAND_CODE", m.group(1)))
        m = LOGCAT_PATTERNS["encrypt_random_response"].search(line)
        if m:
            responses.append(("encryptRandomResponse", m.group(1)))
        m = LOGCAT_PATTERNS["f6"].search(line)
        if m:
            lo, hi = int(m.group(1), 16), int(m.group(2), 16)
            f6_values.append(lo | (hi << 8))
        m = LOGCAT_PATTERNS["f6_decimal"].search(line)
        if m:
            f6_values.append(int(m.group(1)))

    for name, count in paths.items():
        print("  path %-28s hits=%d" % (name, count))
    if f6_values:
        print("  F6 values seen: %s  (0xFFFF = no correctCode, anything else = provisioned)"
              % ", ".join("0x%04X" % v for v in f6_values))
    for label, value in responses[:4]:
        print("  %s: %s" % (label, redact(value, do_redact)))
    if tables:
        print("  table(s) from `full code:` (%d):" % len(tables))
        for table in tables[:2]:
            print("    %s" % redact(table, do_redact))
    if parameter_codes:
        print("  `parameterCode:` (%d):" % len(parameter_codes))
        for code in parameter_codes[:2]:
            print("    %s" % redact(code, do_redact))
    if chunks:
        print("  writeInitParameter chunks (%d):" % len(chunks))
        for index, hex_value in chunks[:8]:
            print("    index=%d len=%d %s" % (index, len(hex_value) // 2, redact(hex_value, do_redact)))
        if len(chunks) > 8:
            print("    ... %d more" % (len(chunks) - 8))
    if not any([tables, parameter_codes, chunks, f6_values, any(paths.values())]):
        print("  no provisioning lines found — was logging enabled and the capture started before "
              "the app?")


# --------------------------------------------------------------------------------------------
# btsnoop
# --------------------------------------------------------------------------------------------

def tshark_fields(tshark, path, display_filter, fields):
    cmd = [tshark, "-r", path, "-Y", display_filter, "-T", "fields"]
    for field in fields:
        cmd += ["-e", field]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print("  tshark failed: %s" % result.stderr.strip()[:400])
        return []
    rows = []
    for line in result.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) >= len(fields):
            rows.append(parts)
    return rows


def hex_bytes(value):
    value = value.replace(":", "")
    try:
        return bytes.fromhex(value)
    except ValueError:
        return b""


def summarise_snoop(path, do_redact):
    tshark = find_tshark()
    if not tshark:
        print("== snoop: tshark not found, skipping %s" % path)
        return
    if not os.path.exists(path):
        print("== snoop: %s does not exist" % path)
        return

    print("== snoop: %s (tshark %s)" % (path, tshark))
    writes = tshark_fields(tshark, path, "btatt.opcode==0x12 || btatt.opcode==0x52",
                           ["frame.time_relative", "btatt.handle", "btatt.value"])
    notifications = tshark_fields(tshark, path, "btatt.opcode==0x1b || btatt.opcode==0x1d",
                                  ["frame.time_relative", "btatt.handle", "btatt.value"])
    reads = tshark_fields(tshark, path, "btatt.opcode==0x0a", ["frame.time_relative", "btatt.handle", "btatt.value"])

    # The vendor op handle is the one that receives `f3` and later `f4 10`.
    op_handle = None
    for _, handle, value in writes:
        data = hex_bytes(value)
        if data[:1] == b"\xf3":
            op_handle = handle
            break
    if op_handle is None:
        print("  no iCan vendor opcode handle found (no `f3` write) — not an iCan session, or the "
              "capture missed the auth")
        return
    print("  vendor op handle: %s" % op_handle)

    events = []
    for ts, handle, value in writes:
        if handle != op_handle:
            continue
        data = hex_bytes(value)
        if not data:
            continue
        opcode = data[0]
        if opcode == 0xF3:
            events.append((float(ts), "F3 auth prime", data))
        elif opcode == 0xF4:
            events.append((float(ts), "F4 auth token", data))
        elif opcode == 0xF6:
            events.append((float(ts), "F6 read code state", data))
        elif opcode == 0xF2:
            events.append((float(ts), "F2 correctCode chunk", data))
        elif opcode == 0xF7:
            events.append((float(ts), "F7 initParameter commit", data))

    counts = {}
    for _, label, _ in events:
        counts[label] = counts.get(label, 0) + 1
    for label in ("F3 auth prime", "F4 auth token", "F6 read code state",
                  "F2 correctCode chunk", "F7 initParameter commit"):
        print("  %-26s %d" % (label, counts.get(label, 0)))

    # Group consecutive F2 writes; a later F7 is the commit of the initParameter path.
    runs, run = [], []
    for ts, label, data in events:
        if label == "F2 correctCode chunk":
            run.append((ts, data))
        else:
            if run:
                runs.append(run)
                run = []
            if label == "F7 initParameter commit" and runs:
                # keep the commit attached in the printout by timestamp only
                pass
    if run:
        runs.append(run)
    if runs:
        print("  F2 chunk runs: %d" % len(runs))
        for number, chunk_run in enumerate(runs, 1):
            lengths = [len(d) for _, d in chunk_run]
            print("    run %d: %d chunks, lengths %s" % (number, len(chunk_run), lengths))
            for ts, data in chunk_run[:4]:
                print("      t=%.3f %s" % (ts, redact(data.hex(), do_redact)))
            if len(chunk_run) > 4:
                print("      ... %d more" % (len(chunk_run) - 4))

    f6_answers = []
    for ts, handle, value in notifications:
        data = hex_bytes(value)
        if data[:3] == b"\x1c\xf6\x01" and len(data) >= 5:
            f6_answers.append((float(ts), data[3] | (data[4] << 8)))
    if f6_answers:
        print("  F6 answers: %s" % ", ".join("t=%.3f 0x%04X" % pair for pair in f6_answers[:8]))

    if reads:
        print("  ATT reads: %d (2AAA/2AA9 need the UUID columns; check by hand in Wireshark)" % len(reads))
    if not events:
        print("  vendor op handle %s saw no F3/F4/F6/F2/F7 writes" % op_handle)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--logcat", help="logcat capture taken during the activation")
    parser.add_argument("--snoop", help="btsnoop_hci.log from the same run")
    parser.add_argument("--no-redact", action="store_true", help="do not mask serials/ids/MACs")
    args = parser.parse_args()

    if not args.logcat and not args.snoop:
        parser.error("give --logcat, --snoop, or both")

    do_redact = not args.no_redact
    if args.logcat:
        summarise_logcat(args.logcat, do_redact)
    if args.snoop:
        summarise_snoop(args.snoop, do_redact)


if __name__ == "__main__":
    main()
