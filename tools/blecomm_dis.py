#!/usr/bin/env python3
"""Disassemble one ARM64 function from an ET_DYN .so with adrp/ldr string refs.

Part of the AiDEX G9 (CGM3) static-RE toolkit (see docs/aidex-g9-cgm3-protocol.md).
Needs: pip install capstone. Example:
  tools/blecomm_dis.py /tmp/aidex/libblecomm-lib.so 0x737fc 0x47c
Symbol addresses come from .dynsym (this lib keeps C++ symbols).

Usage: blecomm_dis.py <so> <vaddr-hex> <size-hex>"""
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

def reg_num(md, rid):
    """Canonical register number (x8 and w8 both track slot 8)."""
    name = md.reg_name(rid)
    digits = ''.join(c for c in name if c.isdigit())
    return int(digits) if digits else None


def disasm(path, va, size):
    d, named = load(path)
    off = vaddr_to_off(d, named, va)
    code = d[off:off+size]
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    pages = {}  # reg slot -> tracked address
    for ins in md.disasm(code, va):
        extra = ''
        ops = ins.operands
        # 1. Resolve loads against the pre-instruction register state.
        if ins.mnemonic == 'ldr' and len(ops) == 2 and ops[1].type == 3:  # mem
            base = reg_num(md, ops[1].mem.base)
            if base is not None and base in pages and ops[1].mem.index == 0:
                tgt = pages[base] + ops[1].mem.disp
                s = read_cstr(d, named, tgt)
                if s:
                    extra = f'  ; "{s[:100]}"'
                else:
                    extra = f'  ; -> {tgt:#x}'
        # Snapshot sources before invalidating anything this insn writes.
        src_vals = {}
        for op in ops:
            if op.type == 1:  # reg
                n = reg_num(md, op.reg)
                if n is not None and n in pages:
                    src_vals[n] = pages[n]
        # 2. Invalidate every register this instruction writes (kills stale
        # entries: without this, `adrp x0..; add x0,x0,#off; ldr x1,[x0]`
        # would resolve against the old page base).
        # 2. Invalidate every register this instruction writes (destination
        # operands with write access). Without this, `adrp x0..;
        # add x0,x0,#off; ldr x1,[x0]` resolves against the old page base
        # instead of page+off. (Note: capstone regs_write is unreliable
        # here, so destinations are derived from operand access flags.)
        for op in ops:
            if op.type == 1 and (op.access & 2):
                n = reg_num(md, op.reg)
                if n is not None:
                    pages.pop(n, None)
        # Indexed writeback (`[...]!`) also rewrites the base register.
        if ins.op_str.strip().endswith('!'):
            for op in ops:
                if op.type == 3:
                    n = reg_num(md, op.mem.base)
                    if n is not None:
                        pages.pop(n, None)
        # 3. Track supported address constructions.
        if ins.mnemonic == 'adrp' and len(ops) == 2:
            n = reg_num(md, ops[0].reg)
            if n is not None:
                pages[n] = ops[1].imm
        elif ins.mnemonic == 'add' and len(ops) == 3:
            dst = reg_num(md, ops[0].reg)
            src = reg_num(md, ops[1].reg) if ops[1].type == 1 else None
            if dst is not None and src is not None and src in src_vals and ops[2].type == 2:
                pages[dst] = src_vals[src] + ops[2].imm
        elif ins.mnemonic == 'mov' and len(ops) == 2 and ops[1].type == 1:
            dst = reg_num(md, ops[0].reg)
            src = reg_num(md, ops[1].reg)
            if dst is not None and src is not None and src in src_vals:
                pages[dst] = src_vals[src]
        print(f'{ins.address:#x}: {ins.mnemonic:10s} {ins.op_str}{extra}')

if __name__ == '__main__':
    disasm(sys.argv[1], int(sys.argv[2], 16), int(sys.argv[3], 16))
