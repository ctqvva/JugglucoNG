#!/usr/bin/env python3
"""Render original JugglucoNG earcons with Python 3 and NumPy, without samples.
--check verifies committed PCM against the deterministic scores.
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
SYNTH_RATE = RATE * 2
CUES = ('low', 'high', 'urgent_low', 'urgent_high', 'falling', 'rising', 'signal', 'reminder', 'notice')
# Each family has its own compositions, not just a different patch on one melody.
# Event: onset seconds, MIDI pitch, articulation seconds, velocity.
SCORES = {
    'contour': {
        'low': [(0, 79, .24, 1), (.24, 74, .20, .85), (.60, 67, .40, 1), (.77, 67, .16, .40)],
        'high': [(0, 67, .22, 1), (.30, 74, .20, .8), (.48, 79, .40, 1)],
        'urgent_low': [(b+t, p, .18, v) for b in (0, .82) for t,p,v in ((0,81,1),(.18,74,.9),(.37,67,1))],
        'urgent_high': [(b+t, p, .19, v) for b in (0,.86) for t,p,v in ((0,69,.9),(.22,81,1))],
        'falling': [(0,79,.20,.8),(.19,76,.17,.65),(.44,71,.33,1)],
        'rising': [(0,71,.19,.8),(.23,76,.17,.65),(.42,79,.34,1)],
        'signal': [(0,74,.12,.9),(.15,74,.12,.65),(.61,67,.32,1)],
        'reminder': [(0,67,.20,1),(.30,74,.16,.65),(.46,71,.26,.85),(.74,79,.30,.8)],
        'notice': [(0,74,.24,.85),(.075,79,.30,.7)],
    },
    'porcelain': {
        'low': [(0,83,.31,1),(.07,78,.24,.38),(.43,78,.28,.8),(.91,71,.38,1)],
        'high': [(0,71,.30,.85),(.38,78,.29,.85),(.79,83,.40,1),(.85,90,.23,.27)],
        'urgent_low': [(b+t,p,.22,v) for b in (0,.92) for t,p,v in ((0,83,1),(.20,78,.85),(.42,71,1))],
        'urgent_high': [(b+t,p,.26,v) for b in (0,.98) for t,p,v in ((0,76,.85),(.31,88,1))],
        'falling': [(0,86,.27,.9),(.30,81,.32,1),(.36,74,.20,.28)],
        'rising': [(0,74,.26,.8),(.34,81,.32,1),(.41,86,.22,.35)],
        'signal': [(0,81,.14,.85),(.20,81,.14,.85),(.89,70,.35,1)],
        'reminder': [(0,74,.29,.8),(.11,81,.30,.55),(.52,78,.35,.95),(.64,86,.28,.5)],
        'notice': [(0,78,.29,.9),(.09,85,.33,.6),(.17,90,.22,.25)],
    },
    'halo': {
        'low': [(0,81,.21,.9),(.28,76,.19,.7),(.64,69,.34,1)],
        'high': [(0,69,.18,.85),(.33,76,.17,.7),(.53,81,.32,1)],
        'urgent_low': [(b+t,p,.14,v) for b in (0,.76) for t,p,v in ((0,81,1),(.17,76,.8),(.34,69,1))],
        'urgent_high': [(b+t,p,.19,v) for b in (0,.84) for t,p,v in ((0,72,.85),(.25,84,1))],
        'falling': [(0,81,.24,.9),(.34,72,.29,1)],
        'rising': [(0,72,.24,.9),(.35,81,.29,1)],
        'signal': [(0,78,.095,.9),(.16,78,.095,.8),(.64,66,.26,1)],
        'reminder': [(0,69,.16,.9),(.28,76,.12,.6),(.46,73,.18,.8),(.78,81,.25,1)],
        'notice': [(0,76,.16,.85),(.15,83,.27,1)],
    },
}

# Category identity comes before collection style: sustained LOW, chiming HIGH,
# continuous trend gestures, broken SIGNAL, and a plucked REMINDER phrase.
for style, score in SCORES.items():
    score['low'] = [(0, 76, .42, 1), (.66, 69, .48, 1)]
    if style == 'porcelain':
        score['low'] = [(0, 78, .39, .9), (.61, 71, .51, 1)]
    elif style == 'halo':
        score['low'] = [(0, 76, .35, 1), (.56, 67, .47, 1)]
    score['falling'] = [(0, 81, .32, 1), (.46, 72, .17, .7)]
    score['rising'] = [(0, 69, .48, 1), (.61, 81, .13, .65)]


def voice(style, midi, duration, cue, seed):
    """Each alert owns an instrument. Collections color it without erasing its identity."""
    t = np.arange(round(SYNTH_RATE * (duration + .68))) / SYNTH_RATE
    color = {'contour': .85, 'porcelain': 1.08, 'halo': 1.0}[style]
    f = 440 * 2 ** ((midi - 69) / 12) * color
    rng = np.random.default_rng(seed)
    noise = rng.normal(size=len(t))
    attack = 1-np.exp(-t/.004)
    release = np.exp(-np.maximum(0,t-duration)/.045)

    if cue == 'low':
        # Hollow, falling "woaw": moving formants and a gentle pitch droop.
        # Deliberately unrelated to HIGH's struck, bright, inharmonic sound.
        freq = f*.59*(1+.24*np.exp(-t/.07))
        phase = 2*np.pi*np.cumsum(freq)/SYNTH_RATE
        formant = 1.6+3.1*np.exp(-t/.075)
        tone = np.zeros_like(t)
        for h in (1,2,3,4,5,6,7):
            weight = .16/h + .60*np.exp(-((h-formant)/1.15)**2)
            tone += weight*np.sin(h*phase)
        tone *= attack*release*(.76+.24*np.cos(2*np.pi*6*t))
    elif cue == 'high':
        # Bright chiming burst, with an attack that breaks into a little shimmer.
        tone = np.zeros_like(t)
        for ratio,level,decay in ((1,1,.30),(2.756,.43,.12),(4.07,.20,.06),(5.43,.08,.025)):
            tone += level*np.sin(2*np.pi*f*ratio*t)*np.exp(-t/(decay+duration*.4))
        tone += .15*np.sin(2*np.pi*f*1.006*t)*np.exp(-t/.25)
        tone *= 1-np.exp(-t/.0018)
    elif cue == 'urgent_low':
        # Chestier, rough-edged alarm pulses: a new warning texture, not faster LOW.
        phase = 2*np.pi*f*.58*t
        tone = sum(np.sin(h*phase)/h for h in (1,2,3,5,7))
        tone += .22*np.sin(phase+1.3*np.sin(phase*.503))
        tone = np.tanh(tone*1.3)/1.3
        tone *= attack*release*(.85+.15*np.cos(2*np.pi*19*t))
    elif cue == 'urgent_high':
        # A metallic, rapidly alternating alarm bell, different from both HIGH and
        # URGENT LOW even when the listener hears only the start of the sound.
        phase = 2*np.pi*f*t
        switch = .5+.5*np.tanh(3*np.sin(2*np.pi*13*t))
        tone = switch*np.sin(phase)+(1-switch)*.85*np.sin(phase*1.498)
        tone += .22*np.sin(phase*2.76)*np.exp(-t/.1)
        tone *= attack*release
    elif cue == 'falling':
        # Liquid spring/zipper descending through more than an octave.
        freq = f*(.43+1.0*np.exp(-t/.095))
        phase = 2*np.pi*np.cumsum(freq)/SYNTH_RATE
        tone = np.sin(phase+2.8*np.exp(-t/.075)*np.sin(phase*1.5))
        tone += .18*np.sin(phase*2)
        tone *= attack*np.exp(-t/.14)*release
    elif cue == 'rising':
        # Airy accelerating whistle, a distinct texture from the falling spring.
        freq = f*(.55+.8*np.minimum(t/max(duration,.1),1)**.65)
        phase = 2*np.pi*np.cumsum(freq)/SYNTH_RATE
        air = np.convolve(noise,np.ones(9)/9,mode='same')
        tone = np.sin(phase)+.19*np.sin(phase*2)+.10*air
        tone *= attack*release*(.72+.28*np.cos(2*np.pi*(8*t+12*t*t)))
    elif cue == 'signal':
        # Two dry ticks, a gap, then a broken radio-like response. No pitched melody.
        if duration < .2:
            tone = (np.sin(2*np.pi*1320*t)+.6*np.sin(2*np.pi*2137*t))
            tone *= (1-np.exp(-t/.001))*np.exp(-t/.018)
        else:
            filtered = np.convolve(noise,np.ones(23)/23,mode='same')
            phase = 2*np.pi*310*color*t
            tone = .65*np.sin(phase+.7*np.sin(phase*1.41))+.6*filtered
            stutter = (.5+.5*np.cos(2*np.pi*14*t))**3
            tone *= attack*release*stutter
    elif cue == 'reminder':
        # Warm plucked phrase with a wooden knock and resonant lower body.
        f *= .78
        tone = np.zeros_like(t)
        for ratio,level,decay in ((1,1,.24),(2,.27,.10),(3.97,.24,.045),(6.15,.055,.025)):
            tone += level*np.sin(2*np.pi*f*ratio*t)*np.exp(-t/(decay+duration*.25))
        tone += .12*np.convolve(noise,np.ones(13)/13,mode='same')*np.exp(-t/.008)
        tone *= 1-np.exp(-t/.002)
    else:
        # A brief airy sparkle, rather than another bell phrase.
        phase = 2*np.pi*f*1.6*t
        tone = np.sin(phase+.5*np.sin(phase*2.01))*np.exp(-t/.105)
        tone += .17*np.convolve(noise,np.ones(7)/7,mode='same')*np.exp(-t/.07)
        tone *= (1-np.exp(-t/.008))*(.7+.3*np.cos(2*np.pi*24*t))

    # Collection-level material treatment is secondary to the alert's instrument.
    if style == 'contour':
        tone = np.tanh(tone*1.15)/1.15
    elif style == 'halo' and cue in ('low','reminder','notice'):
        tone *= .85+.15*np.cos(2*np.pi*23*t)
    tone *= np.minimum(1,np.maximum(0,(duration+.68-t)/.16))**2
    return tone


def render(style, cue, peak_normalize=True):
    score = SCORES[style][cue]
    out = np.zeros(round(SYNTH_RATE*(max(t+d for t,_,d,_ in score)+1.02)))
    reflections = {
        'contour': ((0,1),(.063,.065),(.107,.025)),
        'porcelain': ((0,1),(.053,.13),(.097,.085),(.151,.045)),
        'halo': ((0,1),(.105,.15),(.21,.045)),
    }
    for i, (onset,midi,duration,strength) in enumerate(score):
        tone = voice(style,midi,duration,cue,1000*CUES.index(cue)+i)
        for delay,gain in reflections[style]:
            at = round((onset+delay)*SYNTH_RATE)
            n = min(len(tone),len(out)-at)
            out[at:at+n] += strength*gain*tone[:n]
    # 2x oversampling with a windowed-sinc low-pass before decimation, including FM
    # and saturation harmonics. FIR group delay is removed by 'same' convolution.
    x = np.arange(-96,97)
    kernel = .44*np.sinc(.44*x)*np.kaiser(len(x),9)
    kernel /= kernel.sum()
    out = np.convolve(out,kernel,mode='same')[::2]
    out -= np.mean(out)
    fade = round(.004*RATE)
    out[:fade] *= np.linspace(0,1,fade)**2
    out[-round(.18*RATE):] *= np.linspace(1,0,round(.18*RATE))**2
    target_rms = 10**((-16 if cue.startswith('urgent') else -18.5)/20)
    active = out[np.abs(out)>.012*np.max(np.abs(out))]
    peak_gain = 10**((-1 if peak_normalize else -3)/20)/np.max(np.abs(out))
    out *= peak_gain if peak_normalize else min(target_rms/np.sqrt(np.mean(active**2)), peak_gain)
    return np.rint(out*32767).astype('<i2')


def wav(samples):
    stream = io.BytesIO()
    with wave.open(stream,'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(samples.tobytes())
    return stream.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true')
    parser.add_argument('--wav-dir', default=None,
                        help='Write WAVs here instead of Common/src/main/res/raw')
    args = parser.parse_args()
    dest_dir = Path(args.wav_dir) if args.wav_dir else ROOT/'Common/src/main/res/raw'
    stats = {}
    for style in SCORES:
        reel = []
        for cue in CUES:
            name = f'alert_{style}_{cue}.wav'
            samples = render(style,cue)
            data = wav(samples)
            dest = dest_dir/name
            if args.check:
                assert dest.read_bytes()==data, f'{name}: differs from score'
            else:
                dest.write_bytes(data)
            normalized = samples.astype(float)/32768
            assert np.max(np.abs(normalized))<.892, name
            assert samples[0]==samples[-1]==0, name
            assert abs(np.mean(normalized))<.001, name
            stats[name] = dict(seconds=round(len(samples)/RATE,3),
                               peak_dbfs=round(20*np.log10(np.max(np.abs(normalized))),2),
                               rms_dbfs=round(20*np.log10(np.sqrt(np.mean(normalized**2))),2),
                               sha256=hashlib.sha256(data).hexdigest())
            reel.extend((samples,np.zeros(RATE,dtype='<i2')))
        dest = ROOT/'tools/alert-sounds'/f'{style}-preview.wav'
        data = wav(np.concatenate(reel))
        if args.check:
            assert dest.read_bytes()==data, str(dest)
        else:
            dest.write_bytes(data)
    # One family, consecutive alert identities: useful for judging category
    # recognition without confusing it with differences between collections.
    identity_reel = []
    for cue in CUES:
        identity_reel.extend((render('halo',cue),np.zeros(RATE,dtype='<i2')))
    identity_path = ROOT/'tools/alert-sounds/alert-identities-preview.wav'
    identity_data = wav(np.concatenate(identity_reel))
    if args.check:
        assert identity_path.read_bytes()==identity_data
    else:
        identity_path.write_bytes(identity_data)
    assert len({s['sha256'] for s in stats.values()})==27
    report = json.dumps(stats,indent=2)+'\n'
    dest = ROOT/'tools/alert-sounds/measurements.json'
    if args.check:
        assert dest.read_text()==report
    else:
        dest.write_text(report)
    print(f'{"Verified" if args.check else "Rendered"} {len(stats)} mono 48 kHz PCM sounds and 4 preview reels')


if __name__=='__main__':
    main()
