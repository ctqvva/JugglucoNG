#!/usr/bin/env python3
"""Compare lower acoustic body and distinct recorded attacks with the soft audition.
Requires the same NumPy/macOS afconvert setup as render_study.py. Does not touch app assets.
"""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import wave
import numpy as np
from render_study import ROOT, RATE, read_sample, encode

# Use original lower-register recordings for body; short higher recorded strikes
# add definition independently. No pitch shifting, bass oscillators, or distortion.
SCORES = {
    'low': [(0,'marimba_b2_med',1,.78),(.025,'wood_crisp',.13,.10),
            (.43,'marimba_c2_med',1,.95),(.455,'wood_crisp',.12,.10)],
    'high': [(0,'vibes_f2',.9,1.05),(.008,'vibes_attack',.24,.19),
             (.43,'vibes_a2',1,1.18),(.438,'vibes_attack',.20,.16)],
}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true')
    args=parser.parse_args()
    dest=ROOT/'body-crisp'
    dest.mkdir(exist_ok=True)
    names={name for score in SCORES.values() for _,name,_,_ in score}
    manifest=json.loads((ROOT/'sources.json').read_text())
    with tempfile.TemporaryDirectory() as temp:
        samples={}
        for name in names:
            path=ROOT/'sources'/f'{name}.wav'
            assert hashlib.sha256(path.read_bytes()).hexdigest()==manifest[path.name]['sha256']
            samples[name]=read_sample(path,Path(temp))
    reel=[]
    metrics={}
    for cue,score in SCORES.items():
        out=np.zeros(round((max(t+d for t,_,_,d in score)+.2)*RATE))
        for onset,name,strength,duration in score:
            x=samples[name][:round(duration*RATE)].copy()
            release=round(min(.28,duration*.5)*RATE)
            x[-release:]*=np.cos(np.linspace(0,np.pi/2,release))**2
            at=round(onset*RATE)
            out[at:at+len(x)]+=strength*x
        active=out[np.abs(out)>.01*np.max(np.abs(out))]
        out*=min(10**(-20/20)/np.sqrt(np.mean(active**2)),10**(-3/20)/np.max(np.abs(out)))
        out[:96]*=np.linspace(0,1,96)**2
        out[-96:]*=np.linspace(1,0,96)**2
        pcm=np.rint(out*32767).astype('<i2')
        assert pcm[0]==pcm[-1]==0 and np.max(np.abs(out))<.709
        assert abs(np.mean(out))<.001
        data=encode(pcm)
        path=dest/f'{cue}.wav'
        if args.check: assert path.read_bytes()==data,path
        else: path.write_bytes(data)
        # A then B for LOW, followed by A then B for HIGH. One-second separators.
        with wave.open(str(ROOT/f'{cue}.wav'),'rb') as w:
            previous=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2')
        reel.extend((previous,np.zeros(RATE,dtype='<i2'),pcm,np.zeros(RATE,dtype='<i2')))
        active=out[np.abs(out)>.01*np.max(np.abs(out))]
        metrics[cue]={'active_rms_dbfs':round(20*np.log10(np.sqrt(np.mean(active**2))),2),
                      'peak_dbfs':round(20*np.log10(np.max(np.abs(out))),2),
                      'sha256':hashlib.sha256(data).hexdigest()}
    path=dest/'low-high-ab.wav'
    data=encode(np.concatenate(reel))
    if args.check: assert path.read_bytes()==data,path
    else: path.write_bytes(data)
    path=dest/'measurements.json'
    data=json.dumps(metrics,indent=2)+'\n'
    if args.check: assert path.read_text()==data
    else: path.write_text(data)
    print(('Verified' if args.check else 'Rendered')+' lower-body/crisp-attack LOW and HIGH A/B')
    print(metrics)


if __name__=='__main__':
    main()
