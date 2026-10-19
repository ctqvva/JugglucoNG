#!/usr/bin/env python3
"""Small listening study using pinned CC0 acoustic recordings. Python+NumPy+macOS afconvert.
Does not modify Android resources. Run with --check to verify the committed audition.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import wave
import numpy as np

ROOT = Path(__file__).resolve().parent
RATE = 48000
# Onset, original recording, strength, audible decay. No oscillators or pitch shifts.
SCORES = {
    'low': [(0,'marimba_c4',.9,1.3),(.43,'marimba_f3',1,1.6)],
    'high': [(0,'vibes_f4',.8,1.7),(.40,'vibes_a4',1,1.8)],
    'signal': [(0,'wood_1',1,.20),(.22,'wood_2',.8,.20),(.77,'wood_1',.9,.25)],
    'reminder': [(0,'piano_c3',.85,1.35),(.09,'piano_g3',.65,1.35),(.55,'piano_g3',.75,1.5)],
}


def encode(samples):
    stream=io.BytesIO()
    with wave.open(stream,'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(samples.tobytes())
    return stream.getvalue()


def read_sample(path, temp):
    converted=temp/path.name
    subprocess.run(['afconvert',str(path),str(converted),'-f','WAVE','-d','LEI24@48000',
                    '-c','1','--mix','-r','127','--src-complexity','bats'],check=True)
    with wave.open(str(converted),'rb') as w:
        assert (w.getnchannels(),w.getsampwidth(),w.getframerate())==(1,3,RATE)
        b=np.frombuffer(w.readframes(w.getnframes()),dtype=np.uint8).reshape(-1,3).astype(np.int32)
    integer=b[:,0]|(b[:,1]<<8)|(b[:,2]<<16)
    x=((integer^0x800000)-0x800000).astype(float)/8388608
    x-=np.mean(x)
    peak=np.max(np.abs(x))
    onset=np.flatnonzero(np.abs(x)>peak*.02)[0]
    x=x[max(0,onset-round(.008*RATE)):]
    # Gentle tonal darkening, retaining the recorded strike, resonances, and room.
    cutoff=3500 if path.stem.startswith('piano') else 9000
    n=np.arange(-96,97)
    kernel=(2*cutoff/RATE)*np.sinc(2*cutoff*n/RATE)*np.kaiser(len(n),8)
    kernel/=kernel.sum()
    x=np.convolve(x,kernel,mode='same')
    x*=.8/np.max(np.abs(x))
    x[:96]*=np.linspace(0,1,96)**2
    return x


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true')
    args=parser.parse_args()
    manifest=json.loads((ROOT/'sources.json').read_text())
    for name,meta in manifest.items():
        file=ROOT/'sources'/name if name.endswith('.wav') else ROOT/name
        assert hashlib.sha256(file.read_bytes()).hexdigest()==meta['sha256'],name
    with tempfile.TemporaryDirectory() as temp_dir:
        temp=Path(temp_dir)
        samples={p.stem:read_sample(p,temp) for p in (ROOT/'sources').glob('*.wav')}
    reel=[]
    measurements={}
    for cue,score in SCORES.items():
        out=np.zeros(round((max(t+d for t,_,_,d in score)+.25)*RATE))
        for onset,name,strength,duration in score:
            x=samples[name][:round(duration*RATE)].copy()
            # A smooth damping curve shortens the acoustic decay without a hard gate.
            release=min(round(.45*RATE),len(x))
            x[-release:]*=np.cos(np.linspace(0,np.pi/2,release))**2
            at=round(onset*RATE)
            out[at:at+len(x)]+=x*strength
        active=out[np.abs(out)>np.max(np.abs(out))*.01]
        gain=min(10**(-20/20)/np.sqrt(np.mean(active**2)),10**(-3/20)/np.max(np.abs(out)))
        out*=gain
        out[:96]*=np.linspace(0,1,96)**2
        out[-96:]*=np.linspace(1,0,96)**2
        pcm=np.rint(out*32767).astype('<i2')
        assert pcm[0]==pcm[-1]==0
        assert np.max(np.abs(out))<.709 and abs(np.mean(out))<.001
        data=encode(pcm)
        path=ROOT/f'{cue}.wav'
        if args.check: assert path.read_bytes()==data,path
        else: path.write_bytes(data)
        measurements[cue]={'seconds':round(len(out)/RATE,3),'peak_dbfs':round(20*np.log10(np.max(np.abs(out))),2),'sha256':hashlib.sha256(data).hexdigest()}
        reel.extend((pcm,np.zeros(RATE,dtype='<i2')))
    data=encode(np.concatenate(reel))
    path=ROOT/'acoustic-audition.wav'
    if args.check: assert path.read_bytes()==data,path
    else: path.write_bytes(data)
    report=json.dumps(measurements,indent=2)+'\n'
    path=ROOT/'measurements.json'
    if args.check: assert path.read_text()==report
    else: path.write_text(report)
    print(('Verified' if args.check else 'Rendered')+' four recorded-acoustic cues and audition reel')


if __name__=='__main__':
    main()
