#!/usr/bin/env python3
"""Per-function skim of libblecomm-lib.so: small immediates + string refs.

Part of the AiDEX G9 (CGM3) static-RE toolkit (see docs/aidex-g9-cgm3-protocol.md).
Needs: pip install capstone. This is how the Cgm3 opcode map was extracted:
each request builder calls the vtable send helper with w1 = raw wire opcode,
so per-function immediates give the opcode table without reading every function.
Usage: blecomm_skim.py <so> <symbol-prefix> [symbol-prefix ...]
Matches dynsym GLOBAL FUNC symbols starting with any prefix; prints per-function
immediate histogram (mov/movz/movk/add/sub/cmp/and, imm<0x1000) and referenced strings."""
import struct, sys, re
from collections import Counter
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
        secs.append(struct.unpack('<IIQQQQIIQQ', e))
    s = secs[si]
    soff = s[4]
    named = {}
    for vals in secs:
        nm = d[soff+vals[0]:].split(b'\x00')[0].decode()
        named[nm] = vals
    # dynsym
    ds, dt = named['.dynsym'], named['.dynstr']
    syms = []
    for i in range(ds[5] // ds[9]):
        e = d[ds[4]+i*ds[9]: ds[4]+(i+1)*ds[9]]
        st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack('<IBBHQQ', e)
        if (st_info & 0xF) == 2 and st_shndx != 0:
            nm = d[dt[4]+st_name:].split(b'\x00')[0].decode(errors='replace')
            syms.append((nm, st_value, st_size))
    return d, named, syms

def va2off(d, named, va):
    for nm, s in named.items():
        _, typ, _, addr, off, size = s[:6]
        if typ == 1 and addr <= va < addr + size:
            return off + (va - addr)
    return None

def cstr(d, named, va, limit=100):
    off = va2off(d, named, va)
    if off is None:
        return None
    raw = d[off:off+limit].split(b'\x00')[0]
    if len(raw) < 2:
        return None
    try:
        t = raw.decode()
    except Exception:
        return None
    if re.fullmatch(r'[\x20-\x7e\t]+', t):
        return t
    return None

def main():
    path, prefixes = sys.argv[1], sys.argv[2:]
    d, named, syms = load(path)
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    for nm, va, sz in sorted(syms, key=lambda s: s[1]):
        if not any(nm.startswith(p) for p in prefixes) or sz == 0 or sz > 0x3000:
            continue
        off = va2off(d, named, va)
        if off is None:
            continue
        code = d[off:off+sz]
        imms = Counter()
        strs = []
        pages = {}
        try:
            insns = list(md.disasm(code, va))
        except Exception as e:
            print(f'## {nm} disasm error {e}')
            continue
        def slot(rid):
            name = md.reg_name(rid)
            digits = ''.join(c for c in name if c.isdigit())
            return int(digits) if digits else None

        for ins in insns:
            if ins.mnemonic in ('mov', 'movz', 'movk', 'movn', 'add', 'adds',
                                'sub', 'subs', 'cmp', 'and', 'ands', 'orr', 'eor'):
                for op in ins.operands:
                    if op.type == 2 and 0 <= op.imm < 0x1000:
                        imms[op.imm] += 1
            ops = ins.operands
            # 1. Resolve loads against the pre-instruction register state.
            if ins.mnemonic == 'ldr' and len(ops) == 2 and ops[1].type == 3:
                base = slot(ops[1].mem.base)
                if base is not None and base in pages and ops[1].mem.index == 0:
                    s = cstr(d, named, pages[base] + ops[1].mem.disp)
                    if s and s not in strs:
                        strs.append(s)
            # Snapshot sources before invalidating anything this insn writes.
            src_vals = {}
            for op in ops:
                if op.type == 1:
                    n = slot(op.reg)
                    if n is not None and n in pages:
                        src_vals[n] = pages[n]
            # 2. Invalidate every register this instruction writes
            # (destination operands with write access). Without this,
            # `adrp x0..; add x0,x0,#off; ldr x1,[x0]` resolves against the
            # old page base instead of page+off. (Note: capstone regs_write
            # is unreliable here, so destinations are derived from operand
            # access flags.)
            for op in ops:
                if op.type == 1 and (op.access & 2):
                    n = slot(op.reg)
                    if n is not None:
                        pages.pop(n, None)
            # Indexed writeback (`[...]!`) also rewrites the base register.
            if ins.op_str.strip().endswith('!'):
                for op in ops:
                    if op.type == 3:
                        n = slot(op.mem.base)
                        if n is not None:
                            pages.pop(n, None)
            # 3. Track supported address constructions (incl. three-operand
            # `add Rd, Rn, #imm`, which the old two-operand check skipped).
            if ins.mnemonic == 'adrp' and len(ops) == 2:
                n = slot(ops[0].reg)
                if n is not None:
                    pages[n] = ops[1].imm
            elif ins.mnemonic == 'add' and len(ops) == 3:
                dst = slot(ops[0].reg)
                src = slot(ops[1].reg) if ops[1].type == 1 else None
                if dst is not None and src is not None and src in src_vals and ops[2].type == 2:
                    pages[dst] = src_vals[src] + ops[2].imm
            elif ins.mnemonic == 'mov' and len(ops) == 2 and ops[1].type == 1:
                dst = slot(ops[0].reg)
                src = slot(ops[1].reg)
                if dst is not None and src is not None and src in src_vals:
                    pages[dst] = src_vals[src]
        short = nm[:110]
        print(f'### {short} @ {va:#x} sz {sz:#x}')
        if imms:
            print('   imm: ' + ' '.join(f'{k:#x}x{v}' for k, v in sorted(imms.items())))
        for s in strs[:12]:
            print(f'   str: {s[:110]}')
main()
