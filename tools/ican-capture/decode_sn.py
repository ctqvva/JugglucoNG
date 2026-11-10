import struct, sys, datetime

NEW_GLUCOSE_KEY = "y67BbdK!3qN?75t8"
NEW_ORIGINAL_KEY = "X3c26#mb9~Sudyk9"
OLD_GLUCOSE_KEY = "4&13E6G24w6ZL3rF"
OLD_ORIGINAL_KEY = "z3q9V419#7574XZI"


def aes_cbc_decrypt(key, iv, ct):
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    dec = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
    return dec.update(ct) + dec.finalize()


def unpad(b):
    if not b:
        return None
    p = b[-1]
    if p < 1 or p > 16 or b[-p:] != bytes([p]) * p:
        return None
    return b[:-p]


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
            recs.append((ts_sec + ts_usec / 1e6, f.read(incl)))
        return recs


def att_pdus(path):
    pending = {}
    for ts, data in read_pcap(path):
        if len(data) < 6:
            continue
        d = data[4:]
        if d[0] != 0x02:
            continue
        handle_field = struct.unpack('<H', d[1:3])[0]
        acl_len = struct.unpack('<H', d[3:5])[0]
        payload = d[5:5 + acl_len]
        h = handle_field & 0x0fff
        pb = (handle_field >> 12) & 0x3
        key = (h, data[3])
        if pb in (0x0, 0x2):
            pending[key] = bytearray(payload)
        elif key in pending:
            pending[key].extend(payload)
        else:
            continue
        buf = pending.get(key)
        if buf is None or len(buf) < 4:
            continue
        l2len = struct.unpack('<H', buf[0:2])[0]
        cid = struct.unpack('<H', buf[2:4])[0]
        if cid != 0x0004 or len(buf) - 4 < l2len:
            continue
        pdu = bytes(buf[4:4 + l2len])
        pending.pop(key, None)
        yield ts, data[3], pdu


def build_iv(challenge):
    iv = bytearray(16)
    iv[1] = challenge[0]
    iv[6] = challenge[1]
    iv[10] = challenge[2]
    iv[15] = challenge[3]
    return bytes(iv)


def decode_records(plain, subtype, direct):
    if len(plain) < 6 or plain[0] != 0x55 or plain[-1] != 0xAA:
        return None
    base = struct.unpack('<H', plain[1:3])[0]
    recs = []
    off = 3
    seq = base
    if subtype == 0x02:
        while off + 2 <= len(plain) - 1:
            lo, hi = plain[off], plain[off + 1]
            if direct:
                mant, nib = lo | (hi << 8), 0
            else:
                nib, mant = (hi >> 4) & 0x0F, lo | ((hi & 0x0F) << 8)
            recs.append((seq, mant / 100.0, nib))
            seq += 3
            off += 2
    else:
        while off + 4 <= len(plain) - 1:
            lo, hi = plain[off], plain[off + 1]
            mant = lo | (hi << 8)
            temp = struct.unpack('<H', plain[off + 2:off + 4])[0] / 10.0
            recs.append((seq, mant, temp))
            seq += 3
            off += 4
    return base, recs


def try_decode(enc, subtype, challenge, families):
    for fam in families:
        if subtype == 0x02:
            key = NEW_GLUCOSE_KEY if fam == 'new' else OLD_GLUCOSE_KEY
        else:
            key = NEW_ORIGINAL_KEY if fam == 'new' else OLD_ORIGINAL_KEY
        plain = unpad(aes_cbc_decrypt(key.encode(), build_iv(challenge), enc))
        if plain is None:
            continue
        for direct in (True, False):
            res = decode_records(plain, subtype, direct)
            if res:
                return fam, direct, res
    return None


def main(path):
    challenges = []
    frames = []
    for ts, direction, pdu in att_pdus(path):
        if len(pdu) < 3:
            continue
        op = pdu[0] & 0x3f
        if op == 0x1d and struct.unpack('<H', pdu[1:3])[0] == 0x0024:
            val = pdu[3:]
            if val[:3] == b'\x06\xf3\x01' and len(val) >= 7:
                challenges.append((ts, val[3:7]))
            continue
        if op != 0x1b:
            continue
        if struct.unpack('<H', pdu[1:3])[0] != 0x0016:
            continue
        val = pdu[3:]
        subtype = None
        enc = None
        if val[:2] == b'SN' and len(val) >= 8 and val[3] == 0x2A and val[4] == 0x52:
            subtype = val[5]
            enc = val[6:-1]
        elif len(val) == 19 and val[2] == 0x02:
            subtype = 0x02
            enc = val[3:19]
        if subtype is None or not enc:
            continue
        frames.append((ts, subtype, enc))

    out = []
    unmatched = 0
    for ts, subtype, enc in frames:
        prior = [c for c in challenges if c[0] <= ts + 0.5]
        if not prior:
            unmatched += 1
            continue
        res = None
        used = None
        for cts, ch in reversed(prior[-4:]):
            res = try_decode(enc, subtype, ch, ('new', 'old'))
            if res:
                used = cts
                break
        if res is None:
            for cts, ch in reversed(prior):
                res = try_decode(enc, subtype, ch, ('new', 'old'))
                if res:
                    used = cts
                    break
        dt = datetime.datetime.fromtimestamp(ts).strftime('%H:%M:%S')
        if res is None:
            unmatched += 1
            out.append('%s st=%d UNDECODED' % (dt, subtype))
            continue
        fam, direct, (base, recs) = res
        if subtype == 0x02:
            body = ' '.join('%.2f@%d' % (v, s) for s, v, nib in recs)
        else:
            body = ' '.join('%.0f/%.1fC@%d' % (v, t, s) for s, v, t in recs)
        out.append('%s st=%d %s base=%d %s' % (dt, subtype, fam, base, body))
    print('\n'.join(out))
    print('# frames=%d undecoded=%d challenges=%d' % (len(frames), unmatched, len(challenges)))


if __name__ == '__main__':
    main(sys.argv[1])
