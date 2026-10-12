#!/usr/bin/env python3
"""Disassemble one ARM64 function from an ET_DYN .so with adrp/ldr string refs.

Part of the AiDEX G9 (CGM3) static-RE toolkit (see docs/aidex-g9-cgm3-protocol.md).
Needs: pip install capstone. Example:
  tools/blecomm_dis.py /tmp/aidex/libblecomm-lib.so 0x737fc 0x47c
Symbol addresses come from .dynsym (this lib keeps C++ symbols).

Usage: bdis.py <so> <vaddr-hex> <size-hex>"""
import struct, sys
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

def load(path):
    d = open(path, 'rb').read()
    assert d[:4] == b'\x7fELF'
    shoff = struct.unpack('<Q', d[0x28:0x30])[0]
    esz = struct.unpack('<H', d[0x3A:0x3C])[0]
    n = struct.unpack('<H', d[0x3C:0x3E])[0]
    si = struct.unpack('<H', d[0x3E:0x40])[0]
    secs = []
    for i in range(n):
        e = d[shoff+i*esz: shoff+(i+1)*esz]
        vals = struct.unpack('<IIQQQQIIQQ', e)
        secs.append(vals)
    s = secs[si]
    soff = s[4]
    named = {}
    for vals in secs:
        nm = d[soff+vals[0]:].split(b'\x00')[0].decode()
        named[nm] = vals
    return d, named

def vaddr_to_off(d, named, va):
    for nm, s in named.items():
        _, typ, flags, addr, off, size, *_ = s
        if typ == 1 and addr <= va < addr + size:  # PROGBITS
            return off + (va - addr)
    # bss/nobits or outside
    return None

def read_cstr(d, named, va, limit=120):
    off = vaddr_to_off(d, named, va)
    if off is None:
        return None
    raw = d[off:off+limit].split(b'\x00')[0]
    if not raw or len(raw) < 2:
        return None
    try:
        t = raw.decode('utf-8', errors='strict')
    except Exception:
        return None
    if all(32 <= ord(c) < 127 or c in '\t' for c in t):
        return t
    return None

def disasm(path, va, size):
    d, named = load(path)
    off = vaddr_to_off(d, named, va)
    code = d[off:off+size]
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    pages = {}  # reg -> page base from adrp
    for ins in md.disasm(code, va):
        extra = ''
        if ins.mnemonic == 'adrp' and len(ins.operands) == 2:
            pages[ins.operands[0].reg] = ins.operands[1].imm
        elif ins.mnemonic in ('ldr', 'adr') and ins.op_str:
            # resolve [reg, #imm] loads -> cstring
            ops = ins.operands
            if len(ops) == 2 and ops[1].type == 3:  # mem
                base = ops[1].mem.base
                if base in pages:
                    tgt = pages[base] + ops[1].mem.disp
                    s = read_cstr(d, named, tgt)
                    if s:
                        extra = f'  ; "{s[:100]}"'
                    else:
                        extra = f'  ; -> {tgt:#x}'
        print(f'{ins.address:#x}: {ins.mnemonic:10s} {ins.op_str}{extra}')

if __name__ == '__main__':
    disasm(sys.argv[1], int(sys.argv[2], 16), int(sys.argv[3], 16))
