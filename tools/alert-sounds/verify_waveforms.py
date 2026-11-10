#!/usr/bin/env python3
"""Verify all source waveforms against pinned pre-boost PCM, allowing only gain."""
import hashlib
import json
from pathlib import Path
import tempfile
import numpy as np
import render as synthetic
import render_acoustic as acoustic
import render_juggluco as juggluco
import render_xdrip as xdrip
from mastering import master

HERE = Path(__file__).resolve().parent


def verify(name, before, after, encode, references):
    assert hashlib.sha256(encode(before)).hexdigest() == references[name]['sha256'], \
        f'{name}: reference waveform changed (notes, timbre or envelopes)'
    x, y = before.astype(float), after.astype(float)
    assert len(x) == len(y), name
    gain = np.dot(x, y) / np.dot(x, x)
    # Independently quantized 16-bit masters can differ by half an LSB on each
    # side of the constant gain. This rejects EQ, transient shaping and limiting.
    error = np.max(np.abs(y - gain*x))
    assert error <= (gain + 1) * .51, f'{name}: nonlinear/tonal change {error} LSB'
    assert gain >= 1, f'{name}: unintentionally quieter source'
    assert y[0] == y[-1] == 0, name
    print(f'{name}: pure gain +{20*np.log10(gain):.2f} dB, error {error:.2f} LSB')


def main():
    references = json.loads((HERE/'linear-reference.json').read_text())
    for style in synthetic.SCORES:
        for cue in synthetic.CUES:
            verify(f'alert_{style}_{cue}.wav', synthetic.render(style, cue, peak_normalize=False),
                   synthetic.render(style, cue), synthetic.wav, references)
    with tempfile.TemporaryDirectory() as scratch:
        samples = {p.stem:acoustic.read_sample(p, Path(scratch))
                   for p in (acoustic.STUDY/'sources').glob('*.wav')}
        for style in acoustic.SCORES:
            for cue in acoustic.CUES:
                verify(f'alert_{style}_{cue}.wav', acoustic.render(style, cue, samples, peak_normalize=False),
                       acoustic.render(style, cue, samples), acoustic.encode, references)
        for cue, source in juggluco.REFERENCES.items():
            path = juggluco.ROOT/'Common/src/main/res/raw'/source['source']
            assert hashlib.sha256(path.read_bytes()).hexdigest() == source['sha256']
            original = juggluco.decode_original(path, Path(scratch))
            verify(f'alert_juggluco_{cue}.wav', master(original, cue),
                   master(original, cue, peak_db=-1, peak_normalize=True), juggluco.encode, references)
    for cue in xdrip.CUES:
        verify(f'alert_xdrip_{cue}.wav', xdrip.render(cue, peak_normalize=False),
               xdrip.render(cue), xdrip.encode, references)
    assert len(references) == 63
    print('Verified all 63 compositions: duration, notes, timbre and envelopes unchanged; constant gain only')


if __name__ == '__main__':
    main()
