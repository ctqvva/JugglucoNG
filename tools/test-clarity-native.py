#!/usr/bin/env python3
"""Build and run the Dexcom Clarity outbox/storage host tests (no provider library)."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / 'Common/src/main/cpp'
CLARITY = CPP / 'net/clarity'
# The library minus its ARM provider engine, JNI glue and HTTPS transport.
SOURCES = ['bytes', 'cipher', 'logging', 'provider', 'protocol', 'records', 'mapped', 'account_store',
           'outbox', 'client', 'auth', 'redirect', 'http_error', 'certificate', 'certificate_crypto',
           'engine_unavailable']


def main():
    with tempfile.TemporaryDirectory(prefix='clarity-native-') as temporary:
        binary = Path(temporary) / 'test'
        subprocess.run([os.environ.get('CXX', 'c++'), '-std=c++20', '-pthread', '-g', '-O1',
                        '-I' + str(CPP), '-I' + str(CPP / 'share'),
                        *[str(CLARITY / f'{name}.cpp') for name in SOURCES],
                        str(ROOT / 'Common/src/test/cpp/ClarityOutboxTests.cpp'),
                        '-Wno-deprecated-declarations', '-lz', '-ldl', '-o', str(binary)], check=True)
        # The library logs every batch and event to stderr; keep only the test's own output.
        result = subprocess.run([str(binary)], stderr=subprocess.PIPE, text=True, timeout=120)
        if result.returncode:
            print(result.stderr[-4000:])
            raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
