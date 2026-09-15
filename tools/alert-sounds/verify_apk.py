#!/usr/bin/env python3
"""Verify named sound resources and original PCM bytes in debug or shrunk APKs."""
import argparse
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--aapt2', required=True, help='Path to Android SDK build-tools aapt2')
    parser.add_argument('apks', nargs='+', type=Path)
    args = parser.parse_args()
    expected = {p.stem: p.read_bytes() for p in (ROOT / 'Common/src/main/res/raw').glob('alert_*.wav')}
    assert len(expected) == 27, f'Expected 27 source sounds, found {len(expected)}'
    for apk in args.apks:
        table = subprocess.check_output([args.aapt2, 'dump', 'resources', str(apk)], text=True)
        # Release optimization shortens ZIP paths (e.g. res/OS.wav), but the resource
        # table must keep raw/alert_contour_high for persisted URI resolution.
        files = dict(re.findall(r'resource \S+ raw/(alert_\w+)\n\s+\(\) \(file\) (\S+)', table))
        with zipfile.ZipFile(apk) as archive:
            for name, pcm in expected.items():
                assert name in files, f'{apk}: missing named resource {name}'
                assert archive.read(files[name]) == pcm, f'{apk}: changed PCM for {name}'
        print(f'{apk.name}: all 27 named resources resolve to byte-identical WAVs')


if __name__ == '__main__':
    main()
