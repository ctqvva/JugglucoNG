#!/usr/bin/env python3
"""Check the decoded AAC we actually ship, not just the pre-encode master."""
import struct
import subprocess
import tempfile
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[2]


def decode(path, temp, rate=48000):
    wav = temp / (path.stem + f'-{rate}.wav')
    subprocess.run(['afconvert', str(path), str(wav), '-f', 'WAVE',
                    '-d', f'LEF32@{rate}', '-c', '1', '--mix',
                    '-r', '127', '--src-complexity', 'bats'],
                   check=True, capture_output=True)
    data = wav.read_bytes()
    assert data[:4] == b'RIFF' and data[8:12] == b'WAVE'
    off = 12
    while off + 8 <= len(data):
        chunk, size = data[off:off + 4], struct.unpack('<I', data[off + 4:off + 8])[0]
        body = data[off + 8:off + 8 + size]
        if chunk == b'fmt ':
            assert struct.unpack('<H', body[2:4])[0] == 1
            assert struct.unpack('<I', body[4:8])[0] == rate
            assert struct.unpack('<H', body[14:16])[0] == 32
        if chunk == b'data':
            return np.frombuffer(body, dtype='<f4')
        off += 8 + size + (size & 1)
    raise AssertionError(f'{path}: missing PCM')


def main():
    raw = ROOT / 'Common/src/main/res/raw'
    assets = sorted(raw.glob('alert_*.m4a'))
    assert len(assets) == 63
    with tempfile.TemporaryDirectory() as scratch:
        for path in assets:
            pcm = decode(path, Path(scratch))
            assert np.isfinite(pcm).all(), path.name
            peak = float(np.max(np.abs(pcm)))
            # Lossy encoding can overshoot the PCM master. Require remaining
            # decoded sample headroom, rather than assuming -1.5 dB survives.
            assert peak < .98, f'{path.name}: decoded peak {peak} risks clipping'
            # Independent 4x reconstruction checks inter-sample overshoot too.
            reconstructed = decode(path, Path(scratch), rate=192000)
            reconstructed_peak = float(np.max(np.abs(reconstructed)))
            assert reconstructed_peak < .98, f'{path.name}: 4x peak {reconstructed_peak} risks clipping'
            assert abs(float(pcm.mean())) < .005, path.name
            active = pcm[np.abs(pcm) > .01 * peak]
            rms = float(np.sqrt(np.mean(active ** 2)))
            assert rms > .08, f'{path.name}: unexpectedly quiet active samples'
            print(f'{path.stem}: peak {20*np.log10(peak):.2f} dBFS, 4x peak {20*np.log10(reconstructed_peak):.2f} dBFS, active RMS {20*np.log10(rms):.2f} dBFS')


if __name__ == '__main__':
    main()
