import struct, sys, datetime, collections

DIR = {0: 'H2C', 1: 'C2H'}

OPCODES = {
    0x01: 'ErrorRsp', 0x02: 'MtuReq', 0x03: 'MtuRsp',
    0x04: 'FindInfoReq', 0x05: 'FindInfoRsp',
    0x08: 'ReadByTypeReq', 0x09: 'ReadByTypeRsp',
    0x0a: 'ReadReq', 0x0b: 'ReadRsp',
    0x0c: 'ReadBlobReq', 0x0d: 'ReadBlobRsp',
    0x10: 'ReadByGroupReq', 0x11: 'ReadByGroupRsp',
    0x12: 'WriteReq', 0x13: 'WriteRsp',
    0x16: 'PrepareWrite', 0x17: 'PrepareWriteRsp', 0x18: 'ExecWrite', 0x19: 'ExecWriteRsp',
    0x1b: 'Notify', 0x1d: 'Indicate', 0x1e: 'Confirm',
    0x52: 'WriteCmd', 0xd2: 'SignedWriteCmd',
}

BASE_UUID_SUFFIX = '00001000800000805f9b34fb'

def read_pcap(path):
    with open(path, 'rb') as f:
        gh = f.read(24)
        endian = '<' if gh[:4] in (b'\xd4\xc3\xb2\xa1', b'\x4d\x3c\xb2\xa1') else '>'
        recs = []
        while True:
            hdr = f.read(16)
            if len(hdr) < 16:
                break
            ts_sec, ts_usec, incl, orig = struct.unpack(endian + 'IIII', hdr)
            recs.append((ts_sec, ts_usec, f.read(incl)))
        return recs

def uuid_str(b):
    if len(b) == 2:
        return ('0000%04x-0000-1000-8000-00805f9b34fb' % struct.unpack('<H', b)[0])
    if len(b) == 16:
        return '-'.join('%02x' % x for x in b)
    if len(b) == 4:
        v = struct.unpack('<I', b)[0]
        return ('%08x-0000-1000-8000-00805f9b34fb' % v)
    return b.hex()

def main(path, t_from, t_to):
    recs = read_pcap(path)
    # reassembly: (handle, dir) -> bytearray
    pending = {}
    att = []          # (ts, dir, opcode, handle, value, frame)
    uuid_by_handle = {}
    uuid_by_value_handle = {}
    conn_events = []
    for idx, (ts_sec, ts_usec, data) in enumerate(recs, start=1):
        ts = ts_sec + ts_usec / 1e6
        if len(data) < 5:
            continue
        d = data[4:]
        h4 = d[0]
        if h4 == 0x02 and len(d) >= 5:
            handle_field = struct.unpack('<H', d[1:3])[0]
            acl_len = struct.unpack('<H', d[3:5])[0]
            payload = d[5:5 + acl_len]
            h = handle_field & 0x0fff
            pb = (handle_field >> 12) & 0x3
            key = (h, data[3])
            if pb in (0x0, 0x2):  # first fragment (non-flushable or flushable)
                pending[key] = bytearray(payload)
            else:
                if key in pending:
                    pending[key].extend(payload)
                else:
                    continue
            buf = pending.get(key)
            if buf is None or len(buf) < 4:
                continue
            l2len = struct.unpack('<H', buf[0:2])[0]
            cid = struct.unpack('<H', buf[2:4])[0]
            if len(buf) - 4 < l2len:
                continue
            pdu = bytes(buf[4:4 + l2len])
            if cid != 0x0004 or not pdu:
                continue
            pending.pop(key, None)
            op = pdu[0] & 0x3f
            if op in (0x12, 0x52, 0x1b, 0x1d):
                if len(pdu) >= 3:
                    hh = struct.unpack('<H', pdu[1:3])[0]
                    att.append((ts, DIR[data[3]], OPCODES.get(op, hex(op)), hh, pdu[3:], idx))
            elif op == 0x0b:
                att.append((ts, DIR[data[3]], OPCODES.get(op, hex(op)), None, pdu[1:], idx))
            elif op == 0x0d:
                att.append((ts, DIR[data[3]], OPCODES.get(op, hex(op)), None, pdu[1:], idx))
            elif op == 0x11:  # read by group type response
                ln = pdu[1]
                if ln < 6:
                    # Unparseable entry length; bail out rather than spin on p += 0.
                    continue
                p = 2
                while p + ln <= len(pdu):
                    grp = pdu[p:p + ln]
                    s, e = struct.unpack('<HH', grp[0:4])
                    u = uuid_str(grp[4:ln])
                    for hh in range(s, e + 1):
                        uuid_by_handle[hh] = u
                    p += ln
            elif op == 0x09:  # read by type response (characteristic declarations)
                ln = pdu[1]
                if ln < 7:
                    # Unparseable entry length; bail out rather than spin on p += 0.
                    continue
                p = 2
                while p + ln <= len(pdu):
                    it = pdu[p:p + ln]
                    # Entry: declaration handle(2) + properties(1) + value handle(2) + UUID.
                    vh = struct.unpack('<H', it[3:5])[0]
                    u = uuid_str(it[5:ln])
                    uuid_by_value_handle[vh] = u
                    p += ln
            elif op == 0x05:  # find information response
                if len(pdu) >= 2:
                    fmt = pdu[1]
                    p = 2
                    while p + 2 + (2 if fmt == 1 else 16) <= len(pdu):
                        hh = struct.unpack('<H', pdu[p:p + 2])[0]
                        u = uuid_str(pdu[p + 2:p + 2 + (2 if fmt == 1 else 16)])
                        uuid_by_handle[hh] = 'desc:' + u
                        p += 2 + (2 if fmt == 1 else 16)
    # merge maps: keys of uuid_by_value_handle are already the value handles; that
    # authoritative mapping replaces the service-range fallback built above.
    for hh, u in uuid_by_value_handle.items():
        uuid_by_handle[hh] = u
    out = []
    out.append('== UUID map (service ranges) ==')
    prev = None
    start = None
    for hh in sorted(uuid_by_handle):
        u = uuid_by_handle[hh]
        if u != prev:
            if prev is not None:
                out.append('  0x%04x-0x%04x %s' % (start, last, prev))
            prev, start = u, hh
        last = hh
    if prev is not None:
        out.append('  0x%04x-0x%04x %s' % (start, last, prev))
    out.append('')
    out.append('== ATT PDUs %s .. %s ==' % (t_from, t_to))
    for ts, dr, op, hh, val, idx in att:
        if t_from <= ts <= t_to:
            dt = datetime.datetime.fromtimestamp(ts).strftime('%H:%M:%S.%f')[:-3]
            hs = ('0x%04x' % hh) if hh is not None else '-----'
            out.append('%s %s %-12s h=%-7s %-10s %s' % (
                dt, dr, op, hs, uuid_by_handle.get(hh, '') if hh is not None else '', val.hex(' ')))
    print('\n'.join(out))

if __name__ == '__main__':
    path = sys.argv[1]
    t_from = float(sys.argv[2]) if len(sys.argv) > 2 else 0
    t_to = float(sys.argv[3]) if len(sys.argv) > 3 else 1e12
    main(path, t_from, t_to)
