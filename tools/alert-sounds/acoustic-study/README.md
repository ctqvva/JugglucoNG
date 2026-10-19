# Recorded-acoustic listening study

The original acoustic audition is retained for comparison. Its four cues form the basis
of Timber; the low/high B cues form the basis of Ember. Production arrangements are longer; the short study files here remain unchanged.
The study copies and source recordings in this directory are not themselves packaged in the
APK. All three original synthesized collections remain available alongside both acoustic sets.

[Listen to the audition](acoustic-audition.wav), in this order:

1. [Low](low.wav): two soft marimba strikes with a descending response.
2. [High](high.wav): two soft-mallet vibraphone strikes with natural metal resonance.
3. [Signal loss](signal.wav): a broken pattern of quiet wooden knocks.
4. [Reminder](reminder.wav): a softly played piano interval and a short response.

All sounds use real instrument recordings. There are no synthesized oscillators, pitch
sweeps, pitch shifting, synthetic reverberation, or electronic modulation. Edits are
high-quality conversion to mono 48 kHz, onset trimming, gentle low-pass filtering,
level balancing, arrangement, and smooth shortening of the recorded decays. The piano
was recorded normally at soft velocity; it is not a recording of a felt-modified piano.

## Source and rights

Samples: Versilian Studios' [Versilian Community Sample Library](https://github.com/sgossner/VCSL),
revision `c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e`, published under **CC0 1.0**.
See the upstream [README](https://github.com/sgossner/VCSL/blob/c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e/README.md),
the preserved [license](CC0.txt), and [source manifest](sources.json) for exact URLs and hashes.
Original source recordings are preserved unchanged in `sources/`; their Git blob hashes
were checked against the upstream tree when downloaded. The arrangements and audition
WAVs in this folder are also dedicated under CC0. The renderer follows the repository's
GPL-3.0-or-later license. This source exception applies only to this study folder.

## Reproduce

Requires macOS `afconvert`, Python 3 and NumPy (authored with NumPy 2.3.5):

```sh
python3 tools/alert-sounds/acoustic-study/render_study.py
python3 tools/alert-sounds/acoustic-study/render_study.py --check
```

The check validates source hashes, reproduces all four cues and the reel, checks digital
peaks, DC offset and zero endpoints, and compares `measurements.json`. Exact bytes can
depend on the macOS sample-rate converter and NumPy versions. No physical-device
recognition or listening acceptance is claimed. These original studies contain no urgent-alarm designs; the complete collections
are documented in the parent listening guide.

Production update: these short audition WAVs remain unchanged. The selectable Timber and Ember
collections now use longer arrangements with restrained linear mastering; they are no longer byte-identical
to these study copies. Ember is the default for unset sounds; explicit choices remain saved.
