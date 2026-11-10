#!/usr/bin/env python3
"""Remaster the original Juggluco alert recordings to modern-quality WAVs.

Decodes the committed low-bitrate MP3/OGG originals with macOS afconvert to the
exact original-reference.json frame counts, then applies the same single-gain,
headroom-limited mastering as the Timber/Ember collections: DC removal, one
linear peak-normalizing gain (-1 dBFS PCM peak ceiling),
5 ms raised-cosine fades and zero endpoints. No compression, saturation, pitch
or time changes: the cues stay recognizably the originals, minus the harshness,
DC offsets, hot peaks and click-prone endpoints.

Run with --check to verify the committed WAVs against this pipeline.
"""
import argparse
import hashlib
import json
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
from mastering import REFERENCES, master, metrics as measure

CUES = ('low', 'high', 'urgent_low', 'urgent_high', 'falling', 'rising', 'signal', 'reminder', 'notice')
RATE = 48000


def decode_original(source: Path, temp: Path) -> np.ndarray:
    """Decode an MP3/OGG original to mono 48 kHz float PCM, exact reference frames."""
    converted = temp / (source.stem + '.wav')
    subprocess.run(['afconvert', str(source), str(converted), '-f', 'WAVE',
                    '-d', 'LEI16@48000', '-c', '1', '--mix',
                    '-r', '127', '--src-complexity', 'bats'], check=True)
    data = converted.read_bytes()
    assert data[0:4] == b'RIFF' and data[8:12] == b'WAVE', converted
    bits, pcm = 0, None
    off = 12
    while off + 8 <= len(data):
        chunk, size = data[off:off + 4], struct.unpack('<I', data[off + 4:off + 8])[0]
        body = data[off + 8:off + 8 + size]
        if chunk == b'fmt ':
            tag = struct.unpack('<H', body[0:2])[0]
            assert tag in (0x1, 0xFFFE), f'{source.name}: format {hex(tag)}'
            assert struct.unpack('<H', body[2:4])[0] == 1, f'{source.name}: not mono'
            assert struct.unpack('<I', body[4:8])[0] == RATE, f'{source.name}: not 48 kHz'
            bits = struct.unpack('<H', body[14:16])[0]
            assert bits == 16, f'{source.name}: not 16-bit'
        elif chunk == b'data':
            pcm = np.frombuffer(body, dtype='<i2').astype(float) / 32768
            break
        off += 8 + size + (size & 1)
    assert pcm is not None, f'{source.name}: no data chunk'
    return pcm


def encode(samples: np.ndarray) -> bytes:
    import io
    import wave
    stream = io.BytesIO()
    with wave.open(stream, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(samples.tobytes())
    return stream.getvalue()


def save_or_check(path: Path, data: bytes, check: bool):
    if check:
        assert path.read_bytes() == data, f'{path}: output differs'
    else:
        path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--wav-dir', default=None,
                        help='Write WAVs here instead of Common/src/main/res/raw')
    args = parser.parse_args()
    dest_dir = Path(args.wav_dir) if args.wav_dir else ROOT / 'Common/src/main/res/raw'
    references = json.loads((HERE / 'original-reference.json').read_text())
    assert set(references) == set(CUES)
    metrics = {}
    with tempfile.TemporaryDirectory() as temp_dir:
        temp = Path(temp_dir)
        reel = []
        for cue in CUES:
            source = ROOT / 'Common/src/main/res/raw' / references[cue]['source']
            assert hashlib.sha256(source.read_bytes()).hexdigest() == references[cue]['sha256'], source.name
            pcm = decode_original(source, temp)
            assert len(pcm) == references[cue]['frames'], f'{cue}: {len(pcm)} != {references[cue]["frames"]}'
            remastered = master(pcm, cue, peak_db=-1.0, peak_normalize=True)
            data = encode(remastered)
            name = f'alert_juggluco_{cue}.wav'
            save_or_check(dest_dir / name, data, args.check)
            metrics[name] = measure(remastered, data)
            reel.extend((remastered, np.zeros(RATE, dtype='<i2')))
        save_or_check(HERE / 'juggluco-preview.wav', encode(np.concatenate(reel)), args.check)
    assert len({value['sha256'] for value in metrics.values()}) == 9
    save_or_check(HERE / 'juggluco-measurements.json', (json.dumps(metrics, indent=2) + '\n').encode(), args.check)
    print(('Verified' if args.check else 'Rendered') + ' 9 remastered Juggluco cues and preview reel')


if __name__ == '__main__':
    main()
