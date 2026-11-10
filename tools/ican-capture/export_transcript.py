#!/usr/bin/env python3
"""Export a redacted ATT transcript of the iCan sensor link from an Android HCI capture.

Only the sensor's ACL connection is included (the capture may also contain unrelated
BLE links, e.g. an insulin pump). Serial-like ASCII runs are masked; MACs and account
ids never appear in the ATT layer.

Usage:
  python3 export_transcript.py capture.pcap --out transcript.md [--from T0 --to T1] [--link 0x0201]
"""
import argparse
import datetime
import re
import struct
import sys

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
UUID16 = {
    0x0021: "RACP", 0x0022: "RACP CCCD", 0x0024: "SSCP", 0x0025: "SSCP CCCD",
    0x0016: "CGM Measurement", 0x0017: "Measurement CCCD", 0x001D: "Session Start Time",
    0x002C: "Serial Number",
}
HANDLE_OPS = {0x01, 0x0A, 0x0C, 0x12, 0x52, 0x1B, 0x1D}
KEEP_OPS = {0x0A, 0x0B, 0x0C, 0x0D, 0x12, 0x13, 0x1B, 0x1D, 0x1E, 0x52}


def read_pcap(path):
    with open(path, "rb") as f:
        gh = f.read(24)
        endian = "<" if gh[:4] in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1") else ">"
        while True:
            hdr = f.read(16)
            if len(hdr) < 16:
                break
            ts_sec, ts_usec, incl, _ = struct.unpack(endian + "IIII", hdr)
            yield ts_sec + ts_usec / 1e6, f.read(incl)


def att_pdus(path):
    """Yield (ts, acl_handle, direction, pdu) with L2CAP/ATT reassembly."""
    pending = {}
    for ts, data in read_pcap(path):
        if len(data) < 6:
            continue
        d = data[4:]                      # 4-byte MIUI "linux" header
        if d[0] != 0x02:                  # ACL only
            continue
        handle_field = struct.unpack("<H", d[1:3])[0]
        acl_len = struct.unpack("<H", d[3:5])[0]
        payload = d[5:5 + acl_len]
        acl = handle_field & 0x0FFF
        pb = (handle_field >> 12) & 0x3
        key = (acl, data[3])
        if pb in (0x0, 0x2):
            pending[key] = bytearray(payload)
        elif key in pending:
            pending[key].extend(payload)
        else:
            continue
        buf = pending.get(key)
        if buf is None or len(buf) < 4:
            continue
        l2len = struct.unpack("<H", buf[0:2])[0]
        cid = struct.unpack("<H", buf[2:4])[0]
        if cid != 0x0004 or len(buf) - 4 < l2len:
            continue
        pdu = bytes(buf[4:4 + l2len])
        pending.pop(key, None)
        yield ts, acl, data[3], pdu


def redact(hexstr: str) -> str:
    """Render bytes as hex, hiding serial-like runs with <SN>.

    Serials are not always hex — some vendors use letters and dashes inside the
    serial number, so a hex-only mask would leak long tails of those. Within every
    printable run, any substring of >=12 characters from [0-9A-Za-z-] is treated as
    serial-like and masked, even when the surrounding bytes break a fullmatch.
    Short ASCII fragments (device names, firmware strings like "V1.5.SH2446.1")
    still stay readable.
    """
    data = bytes.fromhex(hexstr.replace(" ", ""))
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
                if m.start():
                    tokens.extend("%02x" % b for b in run[:m.start()])
                tokens.append("<SN>")
                if m.end() < len(run):
                    tokens.extend("%02x" % b for b in run[m.end():])
            else:
                tokens.extend("%02x" % b for b in run)
            i = j
        else:
            tokens.append("%02x" % data[i])
            i += 1
    return " ".join(tokens)


def annotate(op, handle, val):
    if op in (0x12, 0x52) and handle == 0x0024 and val:
        if val[0] == 0xF3:
            return "auth: request challenge"
        if val[0] == 0xF4:
            return "auth: token (%d bytes)" % (len(val) - 2)
        if val[0] == 0xF6:
            return "sensor info request (F6)"
        if val[0] == 0xF2:
            return "F2 chunk (%d bytes)" % (len(val) - 2)
        if val[0] == 0x1A:
            return "start session (0x1A)"
    if op == 0x1D and handle == 0x0024 and val[:3] == b"\x06\xf3\x01":
        return "auth: challenge %s" % val[3:7].hex()
    if op == 0x1D and handle == 0x0024 and val[:3] == b"\x1c\xf6\x01":
        code = val[3] | (val[4] << 8) if len(val) >= 5 else -1
        return "sensor info: codeState=0x%04X%s" % (code, " (UNPROVISIONED)" if code == 0xFFFF else "")
    if op == 0x1D and handle == 0x0024 and val[:3] == b"\x1c\xf4\x01":
        return "auth: token accepted"
    if op == 0x1D and handle == 0x0024 and val[:3] == b"\x1c\xf2\x01":
        return "F2 chunk ack"
    if op == 0x1D and handle == 0x0024 and val[:2] == b"\x1c\x1a":
        return "session started"
    if op in (0x12, 0x52) and handle == 0x001D and len(val) == 9:
        y = val[0] | (val[1] << 8)
        return "session start time %04d-%02d-%02d %02d:%02d:%02d tz=%d dst=%d" % (
            y, val[2], val[3], val[4], val[5], val[6], val[7], val[8])
    if op in (0x12, 0x52) and handle == 0x0021 and val[:1] == b"\xf1":
        return "RACP report records"
    if op == 0x1B and handle == 0x0016:
        if val[:2] == b"SN":
            return "SN batch subtype=%d (%d bytes)" % (val[5], len(val))
        if len(val) == 19:
            return "live frame (19 bytes)"
    return ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pcap")
    ap.add_argument("--out", required=True)
    ap.add_argument("--link", default="0x0201")
    ap.add_argument("--after", type=float, default=None, help="absolute epoch start")
    ap.add_argument("--before", type=float, default=None, help="absolute epoch end")
    ap.add_argument("--title", default="iCan CGM — ATT transcript")
    args = ap.parse_args()
    link = int(args.link, 16)

    rows = []
    t0 = None
    for ts, acl, direction, pdu in att_pdus(args.pcap):
        if acl != link or len(pdu) < 1:
            continue
        if args.after is not None and ts < args.after:
            continue
        if args.before is not None and ts > args.before:
            continue
        if t0 is None:
            t0 = ts
        op = pdu[0] & 0x3F
        if op not in KEEP_OPS:
            continue
        has_handle = op in HANDLE_OPS and len(pdu) >= 3
        handle = struct.unpack("<H", pdu[1:3])[0] if has_handle else None
        val = pdu[3:] if has_handle else pdu[1:]
        note = annotate(op, handle, val)
        masked = ""
        if val:
            masked = redact(val.hex(" "))
        handle_text = ("0x%04X %s" % (handle, UUID16.get(handle, ""))) if handle is not None else "-"
        rows.append((ts - t0, "H→S" if direction == 0 else "S→H", OPCODES.get(op, "0x%02X" % op),
                     handle_text, masked, note))

    with open(args.out, "w") as f:
        f.write("# %s\n\n" % args.title)
        f.write("Sensor link only (ACL handle `%s`); unrelated BLE links are excluded.\n" % args.link)
        f.write("Serial-like ASCII runs are masked as `<SN>`; no MACs or account ids are present.\n")
        f.write("Time is relative to the first ATT PDU in the exported window.\n\n")
        f.write("| t+ (s) | dir | ATT | handle | payload | note |\n|---|---|---|---|---|---|\n")
        for dt, d, op, h, payload, note in rows:
            f.write("| %.3f | %s | %s | `%s` | %s | %s |\n" % (
                dt, d, op, h, "`%s`" % payload if payload else "", note))
        f.write("\n# rows: %d\n" % len(rows))
    print("wrote %s (%d rows)" % (args.out, len(rows)))


if __name__ == "__main__":
    main()
