#!/usr/bin/env python3
"""Execute the official GS3 ARM64 connect_decode() in Unicorn.

This is a deliberately small ELF loader for this one Android shared object.  It maps both
loadable segments, applies the RELA relocations (including local C++ symbols), and supplies a
guest heap/TLS plus Python implementations for the libc calls reached by the decoder.  The ELF
is stripped, but the exported C++ symbol is still present in .dynsym at 0x15bd4.
"""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import math
import os
import re
import struct
import sys
from pathlib import Path
from typing import Callable

from elftools.elf.elffile import ELFFile
from unicorn import (
    Uc,
    UcError,
    UC_ARCH_ARM64,
    UC_HOOK_CODE,
    UC_HOOK_MEM_INVALID,
    UC_MODE_ARM,
    UC_PROT_ALL,
)
from unicorn.arm64_const import (
    UC_ARM64_REG_D0,
    UC_ARM64_REG_LR,
    UC_ARM64_REG_PC,
    UC_ARM64_REG_S0,
    UC_ARM64_REG_SP,
    UC_ARM64_REG_TPIDR_EL0,
    UC_ARM64_REG_X0,
    UC_ARM64_REG_X1,
    UC_ARM64_REG_X2,
    UC_ARM64_REG_X3,
)


PAGE = 0x1000
BASE = 0x100000000
STACK = 0x200000000
HEAP = 0x300000000
TLS = 0x400000000
STUBS = 0x500000000
STOP = 0x600000000
STACK_SIZE = 0x200000
HEAP_SIZE = 0x4000000
TLS_SIZE = 0x1000
STUB_SIZE = 0x1000
STOP_SIZE = 0x1000
CONNECT_DECODE = 0x15BD4

R_AARCH64_ABS64 = 257
R_AARCH64_GLOB_DAT = 1025
R_AARCH64_JUMP_SLOT = 1026
R_AARCH64_RELATIVE = 1027


def align_down(value: int) -> int:
    return value & -PAGE


def align_up(value: int) -> int:
    return (value + PAGE - 1) & -PAGE


def u64(value: int) -> bytes:
    return struct.pack("<Q", value & 0xFFFFFFFFFFFFFFFF)


def i32(value: int) -> bytes:
    return struct.pack("<i", value)


class ProbeError(RuntimeError):
    pass


class OfficialDecoder:
    def __init__(self, path: Path, trace: bool = False):
        self.path = path
        self.trace = trace
        self.uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        self._elf_data = path.read_bytes()
        expected_sha = "9b176c18d4346c04cc1e55fec29c5e892b72ec136f5a8bbf4bfe1c43a84109fe"
        if hashlib.sha256(self._elf_data).hexdigest() != expected_sha:
            raise ProbeError("this harness requires the documented official library SHA-256")
        self._external: dict[str, int] = {}
        self._stub_names: dict[int, str] = {}
        self._allocations: dict[int, int] = {}
        self._heap_next = HEAP + 0x10000
        self._once_seen: set[int] = set()
        self._last_pc = 0
        self._trace_calls: list[str] = []
        self._load_elf()
        self._install_stubs()
        self.uc.hook_add(UC_HOOK_CODE, self._code_hook)
        self.uc.hook_add(UC_HOOK_MEM_INVALID, self._invalid_memory_hook)

    def _load_elf(self) -> None:
        with self.path.open("rb") as raw:
            elf = ELFFile(raw)
            self._dynsym = elf.get_section_by_name(".dynsym")
            if self._dynsym is None:
                raise ProbeError("official library has no .dynsym")
            for segment in elf.iter_segments():
                if segment["p_type"] != "PT_LOAD":
                    continue
                start = align_down(BASE + segment["p_vaddr"])
                end = align_up(BASE + segment["p_vaddr"] + segment["p_memsz"])
                self.uc.mem_map(start, end - start, UC_PROT_ALL)
                file_bytes = segment.data()
                self.uc.mem_write(BASE + segment["p_vaddr"], file_bytes)

            # A zeroed thread pointer is not enough: every native function checks a stack canary at
            # TPIDR_EL0+0x28.  The decoder does not use Android TLS semantics beyond that check.
            self.uc.mem_map(STACK, STACK_SIZE, UC_PROT_ALL)
            self.uc.mem_map(HEAP, HEAP_SIZE, UC_PROT_ALL)
            self.uc.mem_map(TLS, TLS_SIZE, UC_PROT_ALL)
            self.uc.mem_map(STOP, STOP_SIZE, UC_PROT_ALL)
            self.uc.mem_write(STOP, struct.pack("<I", 0xD65F03C0))  # ret, only for safety
            self.uc.reg_write(UC_ARM64_REG_SP, STACK + STACK_SIZE - 0x100)
            self.uc.reg_write(UC_ARM64_REG_TPIDR_EL0, TLS)
            self.uc.mem_write(TLS + 0x28, u64(0xA55A5AA5A55A5AA5))

            self._apply_relocations(elf)

    def _apply_relocations(self, elf: ELFFile) -> None:
        reloc_counts: dict[int, int] = {}
        unsupported: list[tuple[int, int, str]] = []
        for section in elf.iter_sections():
            if section["sh_type"] != "SHT_RELA":
                continue
            for reloc in section.iter_relocations():
                typ = reloc["r_info_type"]
                reloc_counts[typ] = reloc_counts.get(typ, 0) + 1
                sym_index = reloc["r_info_sym"]
                symbol = self._dynsym.get_symbol(sym_index) if sym_index else None
                name = symbol.name if symbol is not None else ""
                addend = reloc["r_addend"]
                if typ == R_AARCH64_RELATIVE:
                    value = BASE + addend
                elif typ in (R_AARCH64_ABS64, R_AARCH64_GLOB_DAT, R_AARCH64_JUMP_SLOT):
                    if symbol is not None and symbol["st_shndx"] != "SHN_UNDEF" and symbol["st_value"]:
                        value = BASE + symbol["st_value"] + addend
                    else:
                        value = self._external_address(name)
                else:
                    unsupported.append((typ, reloc["r_offset"], name))
                    continue
                self.uc.mem_write(BASE + reloc["r_offset"], u64(value))
        if unsupported:
            details = ", ".join(f"{typ}@0x{off:x}:{name}" for typ, off, name in unsupported[:8])
            raise ProbeError(f"unsupported relocations ({details})")
        if self.trace:
            print(f"relocations: {reloc_counts}", file=sys.stderr)

    def _external_address(self, name: str) -> int:
        if name not in self._external:
            index = len(self._external)
            address = STUBS + index * 0x20
            self._external[name] = address
            self._stub_names[address] = name
        return self._external[name]

    def _install_stubs(self) -> None:
        self.uc.mem_map(STUBS, STUB_SIZE, UC_PROT_ALL)
        self.uc.mem_write(STUBS, struct.pack("<I", 0xD65F03C0) * (STUB_SIZE // 4))

    def _read(self, address: int, size: int) -> bytes:
        return bytes(self.uc.mem_read(address, size))

    def _write(self, address: int, data: bytes) -> None:
        self.uc.mem_write(address, data)

    def _read_cstr(self, address: int, limit: int = 1 << 20) -> bytes:
        if not address:
            return b""
        result = bytearray()
        while len(result) < limit:
            chunk = self._read(address + len(result), min(256, limit - len(result)))
            zero = chunk.find(b"\0")
            if zero >= 0:
                result.extend(chunk[:zero])
                return bytes(result)
            result.extend(chunk)
        raise ProbeError(f"unterminated guest string at 0x{address:x}")

    def _guest_alloc(self, size: int, alignment: int = 16) -> int:
        if size < 1:
            size = 1
        address = (self._heap_next + alignment - 1) & -alignment
        end = address + size
        if end > HEAP + HEAP_SIZE:
            raise ProbeError(f"guest heap exhausted allocating {size} bytes")
        self._heap_next = end
        self._allocations[address] = size
        self._write(address, b"\0" * min(size, 64))
        return address

    @staticmethod
    def _signed(value: int, bits: int = 32) -> int:
        sign = 1 << (bits - 1)
        return (value & (sign - 1)) - (value & sign)

    def _set_return(self, value: int = 0) -> None:
        self.uc.reg_write(UC_ARM64_REG_X0, value & 0xFFFFFFFFFFFFFFFF)
        self.uc.reg_write(UC_ARM64_REG_PC, self.uc.reg_read(UC_ARM64_REG_LR))

    def _code_hook(self, uc: Uc, address: int, size: int, _user_data: object) -> None:
        self._last_pc = address
        if address == STOP:
            uc.emu_stop()
            return
        name = self._stub_names.get(address)
        if name is not None:
            self._trace_calls.append(name)
            self._dispatch_external(name)
            return
        if self.trace and address in (BASE + CONNECT_DECODE,):
            print(f"entered connect_decode at 0x{address - BASE:x}", file=sys.stderr)

    def _invalid_memory_hook(self, _uc: Uc, access: int, address: int, size: int, value: int, _data: object) -> bool:
        raise ProbeError(
            f"invalid memory access access={access} addr=0x{address:x} size={size} value=0x{value:x} "
            f"from pc=0x{self._last_pc:x}"
        )

    def _dispatch_external(self, name: str) -> None:
        x = lambda index: self.uc.reg_read((UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2, UC_ARM64_REG_X3)[index])

        if name in {"malloc", "_Znwm", "_Znam", "_ZnwmSt11align_val_t", "_ZnamSt11align_val_t"}:
            self._set_return(self._guest_alloc(x(0), 16))
            return
        if name in {"calloc"}:
            count, size = x(0), x(1)
            address = self._guest_alloc(count * size, 16)
            self._write(address, b"\0" * min(count * size, 64))
            self._set_return(address)
            return
        if name in {"realloc"}:
            old, size = x(0), x(1)
            address = self._guest_alloc(size, 16)
            if old and size:
                copy = min(size, self._allocations.get(old, size))
                self._write(address, self._read(old, copy))
            self._set_return(address)
            return
        if name in {
            "free",
            "_ZdlPv",
            "_ZdaPv",
            "_ZdlPvm",
            "_ZdaPvm",
            "_ZdlPvSt11align_val_t",
            "_ZdaPvSt11align_val_t",
            "_ZdlPvmSt11align_val_t",
            "_ZdaPvmSt11align_val_t",
        }:
            self._set_return(0)
            return
        if name == "posix_memalign":
            output, alignment, size = x(0), x(1), x(2)
            self._write(output, u64(self._guest_alloc(size, max(16, alignment))))
            self._set_return(0)
            return
        if name in {"strlen", "__strlen_chk"}:
            self._set_return(len(self._read_cstr(x(0))))
            return
        if name in {"memcpy", "memmove", "__memmove_chk"}:
            destination, source, count = x(0), x(1), x(2)
            data = self._read(source, count)
            self._write(destination, data)
            self._set_return(destination)
            return
        if name in {"memset", "wmemset"}:
            destination, value, count = x(0), x(1), x(2)
            if name == "wmemset":
                data = struct.pack("<I", value & 0xFFFFFFFF) * count
            else:
                data = bytes([value & 0xFF]) * count
            self._write(destination, data)
            self._set_return(destination)
            return
        if name in {"strcpy", "__strcpy_chk"}:
            destination, source = x(0), x(1)
            data = self._read_cstr(source) + b"\0"
            self._write(destination, data)
            self._set_return(destination)
            return
        if name == "memcmp":
            left, right, count = self._read(x(0), x(2)), self._read(x(1), x(2)), x(2)
            result = 0
            for a, b in zip(left, right):
                if a != b:
                    result = a - b
                    break
            self._set_return(result)
            return
        if name == "strcmp":
            left, right = self._read_cstr(x(0)), self._read_cstr(x(1))
            self._set_return((left > right) - (left < right))
            return
        if name == "strstr":
            haystack, needle = self._read_cstr(x(0)), self._read_cstr(x(1))
            offset = haystack.find(needle)
            self._set_return(x(0) + offset if offset >= 0 else 0)
            return
        if name == "strspn":
            subject, accepted = self._read_cstr(x(0)), set(self._read_cstr(x(1)))
            count = 0
            while count < len(subject) and subject[count] in accepted:
                count += 1
            self._set_return(count)
            return
        if name == "memchr":
            address, value, count = x(0), x(1) & 0xFF, x(2)
            data = self._read(address, count)
            offset = data.find(bytes([value]))
            self._set_return(address + offset if offset >= 0 else 0)
            return
        if name in {"toupper", "isxdigit", "islower"}:
            value = x(0) & 0xFF
            if name == "toupper":
                result = ord(chr(value).upper()) if value < 128 else value
            elif name == "islower":
                result = 1 if 97 <= value <= 122 else 0
            else:
                result = 1 if (48 <= value <= 57 or 65 <= value <= 70 or 97 <= value <= 102) else 0
            self._set_return(result)
            return
        if name in {"strtoul", "strtoull", "strtol", "strtoll"}:
            self._dispatch_integer_parser(name, x(0), x(1), x(2))
            return
        if name in {"strtof", "strtod", "strtold", "wcstof", "wcstod", "wcstold"}:
            value = 0.0
            try:
                text = self._read_cstr(x(0)).decode("ascii", errors="ignore")
                match = re.match(r"[\s]*[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?", text)
                value = float(match.group(0)) if match else 0.0
            except (ValueError, UnicodeDecodeError):
                value = 0.0
            self.uc.reg_write(UC_ARM64_REG_D0, struct.unpack("<Q", struct.pack("<d", value))[0])
            self.uc.reg_write(UC_ARM64_REG_S0, struct.unpack("<I", struct.pack("<f", value))[0])
            self._set_return(0)
            return
        if name in {"wcslen", "wmemcmp", "wmemcpy", "wmemmove", "wmemchr"}:
            self._dispatch_wide(name, x)
            return
        if name == "__errno":
            self._set_return(TLS + 0x100)
            return
        if name == "pthread_once":
            once, routine = x(0), x(1)
            if once not in self._once_seen and once and routine:
                self._once_seen.add(once)
                self._write(once, i32(1))
                self.uc.reg_write(UC_ARM64_REG_LR, self.uc.reg_read(UC_ARM64_REG_PC) + 4)
                self.uc.reg_write(UC_ARM64_REG_PC, routine)
            else:
                self._set_return(0)
            return
        if name == "pthread_key_create":
            if x(0):
                self._write(x(0), struct.pack("<I", 1))
            self._set_return(0)
            return
        if name in {"pthread_setspecific", "pthread_mutex_lock", "pthread_mutex_unlock", "pthread_getspecific"}:
            self._set_return(0)
            return
        if name in {
            "__cxa_atexit",
            "__cxa_finalize",
            "android_set_abort_message",
            "syslog",
            "openlog",
            "closelog",
            "fputc",
            "vfprintf",
            "snprintf",
            "__vsnprintf_chk",
            "swprintf",
            "vasprintf",
            "dl_iterate_phdr",
            "realloc",
        }:
            self._set_return(0)
            return
        if name in {"abort", "__stack_chk_fail", "_ZSt9terminatev", "__cxa_throw"}:
            raise ProbeError(f"guest requested fatal external {name}")
        # A nonfatal zero-return default is useful for diagnostics, but every such call is
        # recorded and reported in the result so a future input cannot silently hide a missing
        # dependency implementation.
        if self.trace:
            print(f"unimplemented external {name}", file=sys.stderr)
        self._set_return(0)

    def _dispatch_integer_parser(self, name: str, address: int, endptr: int, base: int) -> None:
        text = self._read_cstr(address).decode("ascii", errors="ignore")
        match = re.match(r"[\s]*([+-]?(?:0[xX][0-9a-fA-F]+|[0-9]+))", text)
        if match:
            token = match.group(1)
            try:
                value = int(token, base or 0)
            except ValueError:
                value = 0
            consumed = len(match.group(0))
        else:
            value, consumed = 0, 0
        if endptr:
            self._write(endptr, u64(address + consumed))
        self._set_return(value)

    def _dispatch_wide(self, name: str, x: Callable[[int], int]) -> None:
        address = x(0)
        if name == "wcslen":
            count = 0
            while struct.unpack("<I", self._read(address + count * 4, 4))[0]:
                count += 1
            self._set_return(count)
            return
        if name in {"wmemcpy", "wmemmove"}:
            destination, source, count = x(0), x(1), x(2)
            self._write(destination, self._read(source, count * 4))
            self._set_return(destination)
            return
        if name == "wmemcmp":
            left, right, count = x(0), x(1), x(2)
            result = 0
            for index in range(count):
                a = struct.unpack("<I", self._read(left + index * 4, 4))[0]
                b = struct.unpack("<I", self._read(right + index * 4, 4))[0]
                if a != b:
                    result = (a > b) - (a < b)
                    break
            self._set_return(result)
            return
        if name == "wmemchr":
            value, count = x(1), x(2)
            for index in range(count):
                if struct.unpack("<I", self._read(address + index * 4, 4))[0] == value:
                    self._set_return(address + index * 4)
                    return
            self._set_return(0)

    def decode(self, value: str) -> dict[str, object]:
        self._heap_next = HEAP + 0x10000
        self._allocations.clear()
        self._trace_calls.clear()
        self.uc.reg_write(UC_ARM64_REG_SP, STACK + STACK_SIZE - 0x100)
        input_address = self._guest_alloc(len(value.encode("ascii")) + 1)
        batch_address = self._guest_alloc(64)
        sensitivity_address = self._guest_alloc(4)
        serial_address = self._guest_alloc(64)
        self._write(input_address, value.encode("ascii") + b"\0")
        self._write(batch_address, b"?" * 64)
        self._write(sensitivity_address, i32(-999999))
        self._write(serial_address, b"?" * 64)

        self.uc.reg_write(UC_ARM64_REG_X0, input_address)
        self.uc.reg_write(UC_ARM64_REG_X1, batch_address)
        self.uc.reg_write(UC_ARM64_REG_X2, sensitivity_address)
        self.uc.reg_write(UC_ARM64_REG_X3, serial_address)
        self.uc.reg_write(UC_ARM64_REG_LR, STOP)
        self.uc.reg_write(UC_ARM64_REG_PC, BASE + CONNECT_DECODE)
        try:
            self.uc.emu_start(BASE + CONNECT_DECODE, STOP, count=5_000_000)
        except UcError as exc:
            raise ProbeError(f"Unicorn stopped at 0x{self._last_pc:x}: {exc}") from exc

        result = self._signed(self.uc.reg_read(UC_ARM64_REG_X0) & 0xFFFFFFFF)
        batch_raw = self._read(batch_address, 8)
        serial_raw = self._read(serial_address, 8)
        batch = batch_raw.split(b"\0", 1)[0].decode("ascii", errors="replace")
        serial = serial_raw.split(b"\0", 1)[0].decode("ascii", errors="replace")
        sensitivity = struct.unpack("<i", self._read(sensitivity_address, 4))[0]
        return {
            "input": value,
            "return": result,
            "batch": batch,
            "batch_raw": batch_raw.hex(),
            "sensitivity": sensitivity,
            "serial": serial,
            "serial_raw": serial_raw.hex(),
            "external_calls": sorted(set(self._trace_calls)),
        }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("values", nargs="*", help="QR/connection strings to pass to connect_decode")
    parser.add_argument(
        "--library",
        type=Path,
        required=True,
    )
    parser.add_argument("--trace", action="store_true")
    args = parser.parse_args()
    values = args.values or [
        "EU2VCZUQPSHD5Q",
        "145TUMXYK4S46V",
        "145TUMXYK4S46P",
        "XPT1EEX2NRU16U",
        "J45TUMXYK4S46V",
        "P2250683013AQT98",
        "12345678901234",
        "00000000000000",
    ]
    decoder = OfficialDecoder(args.library, trace=args.trace)
    print(f"library={args.library}")
    print(f"sha256={hashlib.sha256(args.library.read_bytes()).hexdigest()}")
    for value in values:
        try:
            print(decoder.decode(value))
        except Exception as exc:  # keep every control visible in one run
            print({"input": value, "error": f"{type(exc).__name__}: {exc}"})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
