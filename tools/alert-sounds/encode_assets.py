#!/usr/bin/env python3
"""Render all alert collections to PCM and encode the committed AAC assets.

Canonical pipeline: render.py, render_acoustic.py and render_juggluco.py each
render deterministic mono 48 kHz PCM WAVs into a scratch directory, then every
WAV is encoded with macOS afconvert to AAC-LC 160 kbps mono 48 kHz M4A under
Common/src/main/res/raw. Android resolves bundled sounds by resource NAME, so
the alert_<style>_<cue> URIs need no code changes when the container changes.

The AAC encode is byte-deterministic on one machine (verified: two runs give
identical bytes), so --check verifies the committed .m4a files byte for byte,
alongside the WAV-stage renderers, the preview reels, the measurements and the
asset manifest. Exact encoded bytes can still depend on the OS/afconvert
version, like the existing sample-rate-converter caveat.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
RAW = ROOT / 'Common/src/main/res/raw'
MANIFEST = HERE / 'asset-manifest.json'
ENCODER = ('afconvert m4af aacl@48000 mono -b 160000 -r 127 -q 127, '
           'MP4 creation/modification timestamps zeroed')
STYLES = ('contour', 'porcelain', 'halo', 'timber', 'ember', 'juggluco', 'xdrip')
CUES = ('low', 'high', 'urgent_low', 'urgent_high', 'falling', 'rising',
        'signal', 'reminder', 'notice')
RENDERERS = ('render.py', 'render_acoustic.py', 'render_juggluco.py', 'render_xdrip.py')


CONTAINERS = {b'moov', b'trak', b'edts', b'mdia', b'minf', b'dinf',
              b'stbl', b'mvex', b'moof', b'traf', b'mfra'}
STAMPED = {b'mvhd', b'tkhd', b'mdhd'}


def scrub_m4a(path: Path):
    """Zero MP4 creation/modification timestamps so encodes are byte-stable.

    The audio (mdat) is untouched; only the informational wall-clock stamps in
    mvhd/tkhd/mdhd vary run to run (verified: exactly those bytes differ).
    """
    data = bytearray(path.read_bytes())

    def walk(off, end):
        while off + 8 <= end:
            size = int.from_bytes(data[off:off + 4], 'big')
            box = bytes(data[off + 4:off + 8])
            if size == 1:
                size = int.from_bytes(data[off + 8:off + 16], 'big')
                head = 16
            elif size == 0:
                size = end - off
                head = 8
            else:
                head = 8
            if box in STAMPED:
                version = data[off + head]
                if version == 0:
                    data[off + head + 4:off + head + 12] = b'\0' * 8
                else:
                    data[off + head + 4:off + head + 20] = b'\0' * 16
            elif box in CONTAINERS:
                walk(off + head, off + size)
            off += size

    walk(0, len(data))
    path.write_bytes(data)


def encode_wav(source: Path, dest: Path):
    subprocess.run(['afconvert', str(source), str(dest), '-f', 'm4af',
                    '-d', 'aacl@48000', '-c', '1', '--mix',
                    '-b', '160000', '-r', '127', '-q', '127'],
                   check=True, capture_output=True)
    scrub_m4a(dest)


def valid_frames(path: Path) -> int:
    info = subprocess.run(['afinfo', str(path)], check=True,
                          capture_output=True, text=True).stdout
    match = re.search(r'audio (\d+) valid frames', info)
    assert match, f'{path}: no valid-frame count in afinfo'
    return int(match.group(1))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--styles', nargs='+', choices=STYLES,
                        help='Regenerate selected collections while retaining other committed bytes')
    args = parser.parse_args()
    styles = args.styles or STYLES
    def renderer_for(style):
        return ('render' if style in ('contour', 'porcelain', 'halo')
                else 'render_acoustic' if style in ('timber', 'ember')
                else 'render_xdrip' if style == 'xdrip' else 'render_juggluco')
    renderers = [script for script in RENDERERS
                 if Path(script).stem in {renderer_for(style) for style in styles}]
    with tempfile.TemporaryDirectory() as temp_dir:
        temp = Path(temp_dir)
        # WAV stage: each renderer writes (identically, when deterministic) and
        # then verifies its WAVs, previews and measurements against the commit.
        for script in renderers:
            wav_dir = temp / Path(script).stem
            wav_dir.mkdir()
            subprocess.run([sys.executable, str(HERE / script),
                            '--wav-dir', str(wav_dir)], check=True)
            subprocess.run([sys.executable, str(HERE / script),
                            '--check', '--wav-dir', str(wav_dir)], check=True)
        # Encode stage: scratch WAVs become committed M4A assets.
        pcm_stats = {}
        for path in (HERE / 'measurements.json', HERE / 'acoustic-measurements.json',
                     HERE / 'juggluco-measurements.json', HERE / 'xdrip-measurements.json'):
            pcm_stats.update(json.loads(path.read_text()))
        assets = json.loads(MANIFEST.read_text())['assets'] if args.styles else {}
        for style in styles:
            for cue in CUES:
                wav_name = f'alert_{style}_{cue}.wav'
                renderer = renderer_for(style)
                source = temp / renderer / wav_name
                assert source.is_file(), f'missing rendered {wav_name}'
                dest = RAW / f'alert_{style}_{cue}.m4a'
                with tempfile.TemporaryDirectory() as enc:
                    staged = Path(enc) / dest.name
                    encode_wav(source, staged)
                    data = staged.read_bytes()
                    if args.check:
                        assert dest.is_file(), f'missing committed {dest.name}'
                        assert dest.read_bytes() == data, f'{dest.name}: differs'
                    else:
                        dest.write_bytes(data)
                stats = pcm_stats[wav_name]
                with wave.open(str(source), 'rb') as w:
                    frames = w.getnframes()
                    assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, 48000)
                assert valid_frames(dest) == frames, dest.name
                assets[dest.stem] = dict(file=dest.name,
                                         sha256=hashlib.sha256(data).hexdigest(),
                                         frames=frames,
                                         seconds=stats['seconds'])
        legacy = {Path(info['source']).stem: dict(file=info['source'],
                                                  sha256=info['sha256'])
                  for info in json.loads(
                      (HERE / 'original-reference.json').read_text()).values()}
        manifest = json.dumps(dict(encoder=ENCODER, assets=assets,
                                   legacy=legacy), indent=2) + '\n'
        if args.check:
            assert MANIFEST.read_text() == manifest, 'asset-manifest.json differs'
        else:
            MANIFEST.write_text(manifest)
    print(f'{"Verified" if args.check else "Encoded"} {len(styles)*len(CUES)} AAC assets, '
          f'{len(legacy)} legacy files referenced, manifest '
          f'{"verified" if args.check else "written"}')


if __name__ == '__main__':
    main()
