#!/usr/bin/env python3
"""Build and run the Clarity material serialization tests (no provider library)."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / 'Common/src/main/cpp'
CLARITY = CPP / 'net/clarity'
SOURCES = ['material', 'block_decoder', 'bytes', 'logging']


def main():
    with tempfile.TemporaryDirectory(prefix='clarity-material-') as temporary:
        binary = Path(temporary) / 'test'
        subprocess.run([os.environ.get('CXX', 'c++'), '-std=c++20', '-g', '-O1',
                        '-I' + str(CPP), '-I' + str(CPP / 'share'),
                        *[str(CLARITY / f'{name}.cpp') for name in SOURCES],
                        str(ROOT / 'Common/src/test/cpp/ClarityMaterialTests.cpp'),
                        '-lz', '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True)


if __name__ == '__main__':
    main()
