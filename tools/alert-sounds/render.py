#!/usr/bin/env python3
"""Render original JugglucoNG earcons. Requires Python 3 and NumPy; no samples.
Run from any directory. --check verifies committed PCM against the deterministic score.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import wave
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
RATE = 48000
# Time, MIDI pitch, duration, strength. Pitch direction and rhythm survive voice changes.
SCORES = {
    'low': [(0, 79, .32, 1), (.38, 74, .38, 1), (.84, 69, .48, 1)],
    'high': [(0, 69, .38, 1), (.48, 76, .48, 1)],
    'urgent_low': [(t + b, p, .18, 1) for b in (0, 1.02) for t, p in ((0, 81), (.23, 76), (.46, 69))],
    'urgent_high': [(t + b, p, .22, 1) for b in (0, 1.02) for t, p in ((0, 72), (.29, 81))],
    'falling': [(0, 79, .24, .85), (.32, 74, .42, 1)],
    'rising': [(0, 74, .24, .85), (.32, 79, .42, 1)],
    'signal': [(0, 76, .18, 1), (.28, 76, .18, 1), (.94, 71, .38, .9)],
    'reminder': [(0, 72, .30, .9), (.40, 76, .48, 1)],
    'notice': [(0, 79, .46, 1)],
}
VOICES = {
    # Partial ratio, level, decay relative to note length.
    'contour': [(1, 1, 1.8), (2, .26, 1.1), (3, .09, .65), (4, .025, .4)],
    'porcelain': [(1, 1, 1.9), (2.003, .22, 1), (2.756, .085, .6), (4.01, .025, .32)],
    'halo': [(1, 1, 2), (2, .34, 1.5), (3, .14, .9), (5, .035, .4)],
}


def render(style, cue):
    score = SCORES[cue]
    length = max(t + d for t, _, d, _ in score) + .72
    out = np.zeros(int(RATE * length))
    for onset, midi, duration, strength in score:
        t = np.arange(int(RATE * (duration + .46))) / RATE
        f = 440 * 2 ** ((midi - 69) / 12)
        note = np.zeros_like(t)
        attack = .012 if style == 'halo' else .020
        for ratio, level, decay in VOICES[style]:
            envelope = (1 - np.exp(-t / attack)) ** 2 * np.exp(-t / (duration * decay))
            # Controlled, settling pitch transient adds material without a cartoon sweep.
            phase = 2 * np.pi * f * ratio * (t + .00012 * (1 - np.exp(-t / .035)))
            note += level * envelope * np.sin(phase)
        if style == 'contour':
            note = np.tanh(note * 1.15) / 1.15
        # Explicit release and short mono early reflections; no long atmospheric wash.
        note *= np.clip((duration + .46 - t) / .16, 0, 1) ** 2
        start = round(onset * RATE)
        for delay, gain in ((0, 1), (.043, .075), (.079, .035)):
            at = start + round(delay * RATE)
            n = min(len(note), len(out) - at)
            out[at:at+n] += strength * gain * note[:n]
    out -= np.mean(out)
    # Click-free boundaries and a small breath before playback loops again.
    fade = int(.012 * RATE)
    out[:fade] *= np.linspace(0, 1, fade) ** 2
    out[-int(.15 * RATE):] *= np.linspace(1, 0, int(.15 * RATE)) ** 2
    urgent = cue.startswith('urgent')
    target_rms = 10 ** ((-17.5 if urgent else -20) / 20)
    active = out[np.abs(out) > .012 * np.max(np.abs(out))]
    out *= min(target_rms / np.sqrt(np.mean(active ** 2)), 10 ** (-3 / 20) / np.max(np.abs(out)))
    return np.rint(np.clip(out, -1, 1) * 32767).astype('<i2')


def wav(samples):
    stream = io.BytesIO()
    with wave.open(stream, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(samples.tobytes())
    return stream.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    stats = {}
    for style in VOICES:
        reel = []
        for cue in SCORES:
            name = f'alert_{style}_{cue}.wav'
            samples = render(style, cue)
            data = wav(samples)
            dest = ROOT / 'Common/src/main/res/raw' / name
            if args.check:
                assert dest.read_bytes() == data, f'{name}: differs from score'
            else:
                dest.write_bytes(data)
            normalized = samples.astype(float) / 32768
            assert np.max(np.abs(normalized)) < .709, name
            assert samples[0] == samples[-1] == 0, name
            assert abs(np.mean(normalized)) < .001, name
            stats[name] = dict(seconds=round(len(samples)/RATE, 3),
                               peak_dbfs=round(20*np.log10(np.max(np.abs(normalized))), 2),
                               rms_dbfs=round(20*np.log10(np.sqrt(np.mean(normalized**2))), 2),
                               sha256=hashlib.sha256(data).hexdigest())
            reel.extend((samples, np.zeros(RATE, dtype='<i2')))
        dest = ROOT / 'tools/alert-sounds' / f'{style}-preview.wav'
        data = wav(np.concatenate(reel))
        if args.check:
            assert dest.read_bytes() == data, str(dest)
        else:
            dest.write_bytes(data)
    report = json.dumps(stats, indent=2) + '\n'
    dest = ROOT / 'tools/alert-sounds/measurements.json'
    if args.check:
        assert dest.read_text() == report
    else:
        dest.write_text(report)
    print(f'{"Verified" if args.check else "Rendered"} {len(stats)} mono 48 kHz PCM sounds and 3 preview reels')


if __name__ == '__main__':
    main()
