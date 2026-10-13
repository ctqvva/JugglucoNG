"""Duration references and linear, headroom-limited acoustic gain (48 kHz)."""
import json
from pathlib import Path
import numpy as np
RATE = 48000
REFERENCES = json.loads((Path(__file__).parent/'original-reference.json').read_text())

def master(out, cue, boost_db=0.0, peak_db=-3.0, peak_normalize=False):
    assert len(out) == REFERENCES[cue]['frames']
    out = out - np.mean(out)
    # ONE gain: no waveshaping, saturation, compression or envelope changes.
    # The RMS mode retains the pinned reference masters; peak mode uses their
    # remaining headroom without claiming equal perceptual loudness.
    target = (-15.25 if cue.startswith('urgent') else -17.5) + boost_db
    active = out[np.abs(out) > .01*np.max(np.abs(out))]
    peak_gain = 10**(peak_db/20)/np.max(np.abs(out))
    gain = peak_gain if peak_normalize else min(
        10**(target/20)/np.sqrt(np.mean(active**2)), peak_gain)
    out *= gain
    out[:240] *= np.sin(np.linspace(0,np.pi/2,240))**2
    out[-240:] *= np.cos(np.linspace(0,np.pi/2,240))**2
    out[0] = out[-1] = 0
    assert np.max(np.abs(out)) <= 10**(peak_db/20)+1e-12 and abs(np.mean(out)) < .002
    return np.rint(out*32767).astype('<i2')

def metrics(pcm, data):
    import hashlib
    x = pcm.astype(float)/32768
    active = x[np.abs(x) > .01*np.max(np.abs(x))]
    return dict(frames=len(x), seconds=len(x)/RATE,
        peak_dbfs=round(float(20*np.log10(np.max(np.abs(x)))),2),
        rms_dbfs=round(float(20*np.log10(np.sqrt(np.mean(x*x)))),2),
        active_rms_dbfs=round(float(20*np.log10(np.sqrt(np.mean(active*active)))),2),
        sha256=hashlib.sha256(data).hexdigest())
