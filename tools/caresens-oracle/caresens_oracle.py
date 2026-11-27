"""Differential oracle for the CareSens Air calibration.

Runs the vendor's air1_opcal4_algorithm from a locally supplied
libCALCULATION.so (arm64 Android build) in this process. The library is never
part of the repository: set CARESENS_REFERENCE_LIB to its path.

Struct layouts come from Common/src/main/cpp/air/air.hpp, the same header the
app uses to call the library.
"""
import ctypes
import hashlib
import os
import re
import subprocess
import tempfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
HEADER = os.path.join(REPO, 'Common', 'src', 'main', 'cpp', 'air', 'air.hpp')
BASE_TIME = 1709726400
# sha256 of the arm64 library the fixtures were produced with.
KNOWN_REFERENCE = {'0bc4e28b98b8ba4e9ea47db0f4cf413febf596eb94bfe87162432031cd02d4cf': 'arm64-v8a'}

_TYPES = {'uint8_t': 'u1', 'int8_t': 'i1', 'uint16_t': '<u2', 'int16_t': '<i2', 'uint32_t': '<u4',
          'float': '<f4', 'double': '<f8', 'char': 'u1'}


def _struct(text, name, packed):
    body = re.search(r'struct ' + name + r'\s*\{(.*?)\}', text, re.S).group(1)
    fields = []
    for decl in re.findall(r'([^;]+);', re.sub(r'//.*', '', body)):
        m = re.match(r'\s*(\w+)\s+(\w+)\s*((?:\[\d+\])*)', decl)
        dims = tuple(int(x) for x in re.findall(r'\[(\d+)\]', m[3]))
        kind = _TYPES.get(m[1]) or _struct(text, m[1], packed)
        fields.append((m[2], kind, dims) if dims else (m[2], kind))
    return np.dtype(fields, align=not packed)


_H = open(HEADER).read()
DEVICE = _struct(_H, 'air1_opcal4_device_info_t', True)
INPUT = _struct(_H, 'air1_opcal4_cgm_input_t', True)
CAL = _struct(_H, 'air1_opcal4_cal_list_t', True)
OUTPUT = _struct(_H, 'air1_opcal4_output_t', True)
DEBUG = _struct(_H, 'air1_opcal4_debug_t', True)
STATE = _struct(_H, 'air1_opcal4_arguments_t', False)  # natural alignment, as the library uses it
assert (DEVICE.itemsize, INPUT.itemsize, OUTPUT.itemsize, DEBUG.itemsize, STATE.itemsize) == (446, 74, 155, 1579, 117312)


def default_device():
    """DeviceInfo2Obj defaults from air.hpp, as the app sends them."""
    body = re.search(r'struct\s+DeviceInfo2Obj\s*\{(.*?)\}\s*__attribute', _H, re.S).group(1)
    d = np.zeros(1, DEVICE)
    for decl in re.findall(r'([^;]+);', body):
        m = re.match(r'\s*\w+\s+(\w+)\s*((?:\[\d+\])*)\s*(?:=\s*(.*))?$', decl.strip(), re.S)
        if not m or m[3] is None:
            continue
        nums = [float(x.rstrip('f')) for x in re.findall(r'-?\d+(?:\.\d+)?(?:[eE]-?\d+)?f?', m[3])]
        d[m[1]][0] = np.array(nums).reshape(d[m[1]][0].shape) if len(nums) > 1 else nums[0]
    return d


class Reference:
    def __init__(self, path=None):
        path = path or os.environ.get('CARESENS_REFERENCE_LIB')
        if not path or not os.path.isfile(path):
            raise SystemExit('Set CARESENS_REFERENCE_LIB to a local arm64 libCALCULATION.so')
        self.sha256 = hashlib.sha256(open(path, 'rb').read()).hexdigest()
        lib = self._loader()
        lib.oracle_open.restype = ctypes.c_void_p
        lib.oracle_open.argtypes = [ctypes.c_char_p]
        lib.oracle_error.restype = ctypes.c_char_p
        lib.oracle_error.argtypes = [ctypes.c_void_p]
        lib.oracle_run.argtypes = [ctypes.c_void_p] * 9 + [ctypes.c_int]
        lib.oracle_run.restype = None
        self.handle = lib.oracle_open(path.encode())
        err = lib.oracle_error(self.handle)
        if err:
            raise SystemExit('reference library: ' + err.decode())
        self.lib = lib

    @staticmethod
    def _loader():
        cache = os.path.join(os.environ.get('TMPDIR', '/tmp'), 'caresens-oracle')
        os.makedirs(cache, exist_ok=True)
        src = os.path.join(HERE, 'oracle_host.c')
        out = os.path.join(cache, 'liboracle-%s.so' % hashlib.sha1(open(src, 'rb').read()).hexdigest()[:12])
        if not os.path.exists(out):
            with tempfile.TemporaryDirectory(dir=cache) as work:
                temporary_lib = os.path.join(work, 'liboracle.so')
                subprocess.run(['cc', '-O1', '-shared', '-fPIC', '-o', temporary_lib, src, '-lm'], check=True)
                os.replace(temporary_lib, out)
        return ctypes.CDLL(out)

    def run(self, device, records, state=None, snapshots=False):
        """records: INPUT array. Returns (count, outputs[n,4], debug[n,4], state, snapshots)."""
        n = len(records)
        p = lambda a: a.ctypes.data_as(ctypes.c_void_p)
        state = np.zeros(1, STATE) if state is None else state
        cal = np.zeros(1, CAL)
        out = np.zeros((n, 4), OUTPUT)
        dbg = np.zeros((n, 4), DEBUG)
        res = np.zeros(n, 'u1')
        snap = np.zeros(n, STATE) if snapshots else None
        self.lib.oracle_run(self.handle, p(device), p(records), p(cal), p(state), p(out), p(dbg), p(res),
                            p(snap) if snapshots else None, n)
        return res, out, dbg, state, snap


def records(sequence, time, adc, temperature):
    r = np.zeros(len(sequence), INPUT)
    r['seq_number'] = sequence
    r['measurement_time_standard'] = time
    r['workout'] = adc
    r['temperature'] = temperature
    return r
