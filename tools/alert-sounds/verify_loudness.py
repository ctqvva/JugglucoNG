#!/usr/bin/env python3
"""Check the decoded AAC we actually ship, not just the pre-encode master."""
import struct
import subprocess
import tempfile
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[2]


def decode(path, temp):
    wav = temp / (path.stem + '.wav')
    subprocess.run(['afconvert', str(path), str(wav), '-f', 'WAVE',
                    '-d', 'LEF32@48000', '-c', '1', '--mix'],
                   check=True, capture_output=True)
    data = wav.read_bytes()
    assert data[:4] == b'RIFF' and data[8:12] == b'WAVE'
    off = 12
    while off + 8 <= len(data):
        chunk, size = data[off:off + 4], struct.unpack('<I', data[off + 4:off + 8])[0]
        body = data[off + 8:off + 8 + size]
        if chunk == b'fmt ':
            assert struct.unpack('<H', body[2:4])[0] == 1
            assert struct.unpack('<I', body[4:8])[0] == 48000
            assert struct.unpack('<H', body[14:16])[0] == 32
        if chunk == b'data':
            return np.frombuffer(body, dtype='<f4')
        off += 8 + size + (size & 1)
    raise AssertionError(f'{path}: missing PCM')


def main():
    raw = ROOT / 'Common/src/main/res/raw'
    assets = sorted([*raw.glob('alert_ember_*.m4a'), *raw.glob('alert_xdrip_*.m4a')])
    assert len(assets) == 18
    with tempfile.TemporaryDirectory() as scratch:
        for path in assets:
            pcm = decode(path, Path(scratch))
            assert np.isfinite(pcm).all(), path.name
            peak = float(np.max(np.abs(pcm)))
            # Lossy encoding can overshoot the PCM master. Require remaining
            # decoded sample headroom, rather than assuming -1.5 dB survives.
            assert peak < .98, f'{path.name}: decoded peak {peak} risks clipping'
            assert abs(float(pcm.mean())) < .005, path.name
            active = pcm[np.abs(pcm) > .01 * peak]
            rms = float(np.sqrt(np.mean(active ** 2)))
            assert rms > .08, f'{path.name}: unexpectedly quiet active samples'
            # Electrical proxy for the upper partials a small speaker can
            # reproduce; this is not a phone-specific SPL/loudness measurement.
            freq = np.fft.rfftfreq(len(pcm), 1/48000)
            band = np.fft.irfft(np.fft.rfft(pcm) * ((freq >= 500) & (freq <= 4000)), len(pcm))
            band_db = 20*np.log10(np.sqrt(np.mean(band**2)))
            default_band_db = band_db + 20*np.log10(.8)
            if path.stem == 'alert_ember_low':
                assert default_band_db > -23, 'Ember low lost its louder upper partials at 80%'
            if path.stem == 'alert_ember_urgent_low':
                assert default_band_db > -18, 'Ember urgent low lost its louder upper partials at 80%'
            print(f'{path.stem}: peak {20*np.log10(peak):.2f} dBFS, active RMS {20*np.log10(rms):.2f} dBFS, 500–4000 Hz RMS {band_db:.2f} dBFS')


if __name__ == '__main__':
    main()
