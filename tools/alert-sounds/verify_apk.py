#!/usr/bin/env python3
"""Verify named sound resources and committed AAC bytes in debug or shrunk APKs."""
import argparse
import json
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
    manifest = json.loads((ROOT / 'tools/alert-sounds/asset-manifest.json').read_text())
    expected = {entry['file'].removesuffix('.m4a'): (ROOT / 'Common/src/main/res/raw' / entry['file']).read_bytes()
                for entry in manifest['assets'].values()}
    assert len(expected) == 63, f'Expected 63 source sounds, found {len(expected)}'
    assert len(manifest['legacy']) == 9
    for apk in args.apks:
        table = subprocess.check_output([args.aapt2, 'dump', 'resources', str(apk)], text=True)
        # Release optimization shortens ZIP paths (e.g. res/OS.m4a), but the resource
        # table must keep raw/alert_contour_high for persisted URI resolution.
        files = dict(re.findall(r'resource \S+ raw/(alert_\w+)\n\s+\(\) \(file\) (\S+)', table))
        with zipfile.ZipFile(apk) as archive:
            for name, aac in expected.items():
                assert name in files, f'{apk}: missing named resource {name}'
                assert archive.read(files[name]) == aac, f'{apk}: changed audio for {name}'
            for name, entry in manifest['legacy'].items():
                source = ROOT / 'Common/src/main/res/raw' / entry['file']
                match = re.search(r'resource \S+ raw/' + re.escape(name) + r'\n\s+\(\) \(file\) (\S+)', table)
                assert match, f'{apk}: missing legacy resource {name}'
                assert archive.read(match[1]) == source.read_bytes(), name
        print(f'{apk.name}: legacy originals retained; all 63 named resources resolve to byte-identical AAC')


if __name__ == '__main__':
    main()
