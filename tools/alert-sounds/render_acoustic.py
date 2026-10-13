#!/usr/bin/env python3
"""Render additive Timber and Ember collections from verified CC0 recordings.
Requires Python, NumPy and macOS afconvert. Existing synthesized files are never written.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
STUDY = HERE / 'acoustic-study'
sys.path.insert(0, str(STUDY))
from render_study import RATE, read_sample, encode, SCORES as SOFT
from render_body_crisp import SCORES as BODY_CRISP
from mastering import REFERENCES, master, metrics as measure

CUES = ('low', 'high', 'urgent_low', 'urgent_high', 'falling', 'rising', 'signal', 'reminder', 'notice')
# Develop the auditioned Timber and Ember phrases into full-length arrangements. Entries: onset, recording, level, decay seconds.
SCORES = {
    'timber': {
        **SOFT,
        'urgent_low': [(b+t, name, gain, .32) for b in (0, .90)
                       for t, name, gain in ((0,'marimba_b2_med',1),(.20,'marimba_c2_med',.9),(.40,'marimba_c2_med',1))],
        'urgent_high': [(b+t, name, gain, .42) for b in (0, .94)
                        for t, name, gain in ((0,'vibes_f4',.9),(.25,'vibes_a4',1))],
        'falling': [(0,'piano_g3',.8,.56),(.24,'piano_c3',1,.80)],
        'rising': [(0,'vibes_f2',.75,.50),(.18,'vibes_a2',.9,.55),(.40,'vibes_f4',.65,.70)],
        'notice': [(0,'marimba_c4',.7,.68),(.07,'vibes_f4',.35,.85)],
    },
    'ember': {
        **BODY_CRISP,
        'urgent_low': [(b+t,name,gain,d) for b in (0,.83)
                       for t,name,gain,d in ((0,'marimba_b2_med',1,.29),(.02,'wood_crisp',.19,.09),
                                             (.19,'marimba_c2_med',.95,.29),(.38,'marimba_c2_med',1,.32),(.40,'wood_crisp',.2,.09))],
        'urgent_high': [(b+t,name,gain,d) for b in (0,.88)
                        for t,name,gain,d in ((0,'vibes_f2',.9,.35),(.008,'vibes_attack',.32,.12),
                                              (.24,'vibes_a2',1,.38),(.248,'vibes_attack',.28,.12))],
        'falling': [(0,'marimba_b2_med',1,.46),(.016,'wood_crisp',.13,.08),(.25,'marimba_c2_med',1,.65)],
        'rising': [(0,'vibes_f2',1,.48),(.22,'vibes_a2',1,.55),(.43,'vibes_attack',.24,.20)],
        'signal': [(0,'wood_crisp',.7,.13),(.20,'wood_2',.65,.13),
                   (.71,'wood_crisp',.9,.16),(.735,'marimba_c2_med',.4,.38)],
        'reminder': [(0,'piano_c3',.9,.94),(.025,'wood_1',.08,.08),(.09,'piano_g3',.7,.98),
                     (.54,'piano_g3',.85,1.08),(.55,'wood_2',.07,.08)],
        'notice': [(0,'marimba_b2_med',.8,.52),(.018,'wood_crisp',.08,.07),(.11,'vibes_a2',.65,.74)],
    },
}


def render(style, cue, samples, peak_normalize=True):
    score = SCORES[style][cue]
    length = REFERENCES[cue]['frames'] / RATE
    phrase = max(t+d for t,_,_,d in score)
    # Returns of the same recognizable motif, with breathing room, changing
    # strike strength, and a longer final acoustic release. Never stretch PCM.
    count = max(2, round(length / (phrase + .60)))
    final_tail = min(1.05, length*.18)
    last_start = max(0, length - phrase - final_tail)
    starts = np.linspace(0, last_start, count)
    out = np.zeros(REFERENCES[cue]['frames'])
    for index, start in enumerate(starts):
        for onset, name, strength, duration in score:
            final = index == len(starts)-1
            duration += final_tail if final else .12
            at = round((start+onset)*RATE)
            x = samples[name][:min(round(duration*RATE),len(out)-at)].copy()
            release = min(round((.65 if final else .30)*RATE),len(x))
            x[-release:] *= np.cos(np.linspace(0,np.pi/2,release))**2
            # Subtle alternation keeps the returning phrase from sounding stamped.
            accent = (1.0, .87, .95)[index % 3]
            out[at:at+len(x)] += strength*accent*x
    # Continuous, gentle ending even when a short source has already decayed.
    release = min(round(.60*RATE),len(out))
    out[-release:] *= np.cos(np.linspace(0,np.pi/2,release))**2
    # Only the final constant gain changes; recordings, score and envelopes stay
    # intact. The reference mode reproduces the pre-normalization PCM for checks.
    boost = (4.5 if cue in ('low', 'urgent_low') else 3.0) if style == 'ember' else 0.0
    if peak_normalize:
        return master(out, cue, peak_db=-.75 if style == 'ember' else -1.0,
                      peak_normalize=True)
    return master(out, cue, boost_db=boost, peak_db=-1.5 if style == 'ember' else -3.0)


def save_or_check(path, data, check):
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
    dest_dir = Path(args.wav_dir) if args.wav_dir else ROOT/'Common/src/main/res/raw'
    manifest = json.loads((STUDY/'sources.json').read_text())
    for name, info in manifest.items():
        source = STUDY/'sources'/name if name.endswith('.wav') else STUDY/name
        assert hashlib.sha256(source.read_bytes()).hexdigest() == info['sha256'], name
    with tempfile.TemporaryDirectory() as temp:
        samples = {p.stem:read_sample(p,Path(temp)) for p in (STUDY/'sources').glob('*.wav')}
    metrics = {}
    for style in SCORES:
        reel = []
        assert set(SCORES[style]) == set(CUES)
        for cue in CUES:
            pcm = render(style,cue,samples)
            data = encode(pcm)
            name = f'alert_{style}_{cue}.wav'
            save_or_check(dest_dir/name,data,args.check)
            metrics[name] = measure(pcm, data)
            reel.extend((pcm,np.zeros(RATE,dtype='<i2')))
        save_or_check(HERE/f'{style}-preview.wav',encode(np.concatenate(reel)),args.check)
    assert len({value['sha256'] for value in metrics.values()}) == 18
    save_or_check(HERE/'acoustic-measurements.json',(json.dumps(metrics,indent=2)+'\n').encode(),args.check)
    print(('Verified' if args.check else 'Rendered')+' 18 acoustic cues and 2 reels; original-length phrases with natural releases')


if __name__ == '__main__':
    main()
