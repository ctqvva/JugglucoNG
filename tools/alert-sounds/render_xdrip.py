#!/usr/bin/env python3
"""Original xDrip-style electronic alarms; no third-party samples or melodies.

Additive, band-limited sirens and beeps, one linear gain, -1 dBFS PCM peak
ceiling. The name describes a functional style, not official xDrip recordings.
"""
import argparse
import json
from pathlib import Path
import numpy as np
from render_juggluco import encode, save_or_check
from mastering import RATE, metrics

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
CUES = ('low', 'high', 'urgent_low', 'urgent_high', 'falling', 'rising',
        'signal', 'reminder', 'notice')


def render(cue, peak_normalize=True):
    seconds = 3 if cue == 'notice' else 4 if cue == 'reminder' else 6
    t = np.arange(seconds * RATE) / RATE
    if cue in ('low', 'urgent_low'):
        # Persistent, descending "wee-oo"; urgent low has a faster sweep.
        cycle = 0.52 if cue == 'urgent_low' else 0.9
        phase_in_cycle = (t % cycle) / cycle
        freq = 1650 - 850 * (0.5 - 0.5 * np.cos(2 * np.pi * phase_in_cycle))
        envelope = np.ones_like(t)
    else:
        period, on, pitches = {
            'high': (0.8, 0.28, (880, 1320)),
            'urgent_high': (0.34, 0.23, (1100, 1650)),
            'falling': (1.1, 0.65, (1500,)),
            'rising': (1.1, 0.65, (750,)),
            'signal': (1.6, 0.13, (980, 980, 660)),
            'reminder': (0.9, 0.22, (784, 1047, 1319)),
            'notice': (0.7, 0.16, (1047, 1568)),
        }[cue]
        local = t % period
        freq = np.take(pitches, (t / period).astype(int) % len(pitches)).astype(float)
        if cue == 'falling':
            freq *= 1 - 0.5 * np.minimum(local / on, 1)
        elif cue == 'rising':
            freq *= 1 + np.minimum(local / on, 1)
        # 5 ms raised-cosine edges avoid clicks at every beep boundary.
        envelope = np.sin(np.minimum(local / .005, 1) * np.pi / 2) ** 2
        envelope *= np.sin(np.clip((on - local) / .005, 0, 1) * np.pi / 2) ** 2
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    # No square wave, waveshaping, compression, or limiter.
    out = (np.sin(phase) + .25 * np.sin(2 * phase) + .12 * np.sin(3 * phase)) * envelope
    out -= out.mean()
    fade = 240
    out[:fade] *= np.sin(np.linspace(0, np.pi / 2, fade)) ** 2
    out[-fade:] *= np.cos(np.linspace(0, np.pi / 2, fade)) ** 2
    active = out[np.abs(out) > .01 * np.max(np.abs(out))]
    target = -9.5 if cue.startswith('urgent') else -11.5
    peak_db = -1.0 if peak_normalize else -1.5
    peak_gain = 10 ** (peak_db / 20) / np.max(np.abs(out))
    out *= peak_gain if peak_normalize else min(
        10 ** (target / 20) / np.sqrt(np.mean(active ** 2)), peak_gain)
    out[0] = out[-1] = 0
    assert np.max(np.abs(out)) <= 10 ** (peak_db / 20) + 1e-12
    return np.rint(out * 32767).astype('<i2')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--wav-dir', default=str(ROOT / 'Common/src/main/res/raw'))
    args = parser.parse_args()
    stats = {}
    for cue in CUES:
        pcm = render(cue)
        data = encode(pcm)
        name = f'alert_xdrip_{cue}.wav'
        save_or_check(Path(args.wav_dir) / name, data, args.check)
        stats[name] = metrics(pcm, data)
    assert len({entry['sha256'] for entry in stats.values()}) == 9
    save_or_check(HERE / 'xdrip-measurements.json',
                  (json.dumps(stats, indent=2) + '\n').encode(), args.check)
    print(('Verified' if args.check else 'Rendered') + ' 9 original xDrip-style cues')


if __name__ == '__main__':
    main()
