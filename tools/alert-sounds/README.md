# JugglucoNG sound collections

Seven collections, nine cues each: **Contour**, **Porcelain**, **Halo**, **Timber**,
**Ember**, **Juggluco**, and **xDrip-style**. The original 27 synthesized sounds keep their names and URIs.
Timber and Ember add 18 recorded-acoustic cues; Juggluco remasters the nine original
MP3/OGG recordings with the same melodies and durations: one linear gain to the
shared mastering targets, DC removal, fixed hot peaks and click-free endpoints.
All 63 cues ship as compact
AAC-LC 160 kbps mono 48 kHz M4A assets (about 5 MB total instead of ~26 MB of WAV)
under the same `alert_<style>_<cue>` resource names, so saved selections keep working.
Select a collection in an alert's sound picker; the alert determines the cue. xDrip-style is listed first, directly above Juggluco;
tapping a row confirms it immediately (Cancel dismisses without changing anything). Global **Apply to all** selects the matching cue
for each standard and custom glucose alert. **Ember is the app default** for unset sounds, including Wear playback. Explicit collection,
external, system-default and saved native sound choices are retained. Choosing App Default
in the phone picker previews Ember and clears the previous per-alert choice. Collection names are proper names shared across locales; the surrounding UI
reuses existing translations. The picker is in the phone Compose settings; Wear packages
the assets but its legacy settings do not gain a collection picker.

## Listen

Every reel plays: low, high, urgent low, urgent high, falling, rising, signal, reminder, notice,
with a one-second separator in addition to each cue's release.

- [Contour](contour-preview.wav): warm register, rounded synthesis and dry reflections.
- [Porcelain](porcelain-preview.wav): brighter synthesis, staggered voicings and a reflective tail.
- [Halo](halo-preview.wav): FM, pulse modulation, compact phrases and rhythmic echoes.
- [Timber](timber-preview.wav): soft recorded marimba, vibraphone, woodblock and piano.
- [Ember](ember-preview.wav): lower recorded bodies with separate crisp strikes; app default.
- [Juggluco](juggluco-preview.wav): the familiar siren, classic, ghost, nudge, elves, verylow, veryhigh, lowsoon and highsoon melodies, remastered.

[Original alert-type comparison](alert-identities-preview.wav) demonstrates all nine Halo cues.
The [acoustic study](acoustic-study/README.md) and [low/high A/B](acoustic-study/body-crisp/README.md)
are retained byte-for-byte as short studies. Their phrases now develop into longer
Timber and Ember arrangements; both remain selectable alongside all three previous collections.

## Cue vocabulary

| Cue | Used for | Original synthesized collections | Timber / Ember |
| --- | --- | --- | --- |
| Low | Low glucose, custom low | Hollow falling wobble | Descending marimba strikes |
| High | High/persistent high, custom high | Bright chiming cascade | Vibraphone response |
| Urgent low | Very low | Rough pulses | Two groups of three firmer wooden strikes |
| Urgent high | Very high | Rapid metallic alternation | Two pairs of shorter metal strikes |
| Falling | Forecast low, falling fast | Liquid downward gesture | Short descending piano / marimba figure |
| Rising | Forecast high, rising fast | Airy upward whistle | Compact ascending vibraphone figure |
| Signal | Signal loss, missed reading | Dry ticks and broken radio response | Broken wooden knock pattern |
| Reminder | Amount, sensor expiry | Wooden plucked phrase | Soft piano interval and response |
| Notice | Value available | Airy sparkle | Small marimba/vibraphone flourish |

The acoustic collections retain recorded strikes and resonances; there is no pitch shifting,
added oscillator, synthesized modulation or artificial reverberation. Urgent cues use a tighter
cadence and a higher active RMS target. All cues render as mono 48 kHz, 16-bit PCM
with explicit releases, headroom and zero endpoints before the AAC encode. Source recordings, scripts and
preview reels are outside Android resources and do not add to the APK.

## Duration and mastering

Timber, Ember and the Juggluco remasters match the decoded 48 kHz frame count of each
original cue. [original-reference.json](original-reference.json) records source hashes,
durations and measured electrical levels (mono afconvert decode). Mapping: low=siren,
high=classic, notice=ghost, reminder=nudge, signal=elves, urgent low=verylow,
urgent high=veryhigh, falling=lowsoon, rising=highsoon. Durations range from 4.125 to 15.768 s.
Acoustic motifs return with breathing space and varied accents, then a longer final decay;
the source recordings are never time-stretched. The three earlier synthesized sets keep
their original compositions and durations; only final mastering gain changes.

Timber now uses a single peak-normalizing gain with a -1 dBFS PCM ceiling.
The older -17.5/-15.25 dBFS active-RMS masters are pinned as waveform references.
A single linear gain is
applied; if the peak ceiling is reached first, the lower RMS is retained. There is no
waveshaping, compression or saturation. This preserves recorded transients instead of
forcing them into a loudness target. Active RMS excludes samples below 1% of peak and
is not a claim of matched perceived loudness. The longer arrangements and releases remain.

The Juggluco remasters decode those same MP3/OGG sources to the exact reference
frame counts, then apply one linear peak-normalizing gain (-1 dBFS PCM ceiling),
DC removal, the existing 5 ms fades and zero endpoints.
Interior samples match the originals up to that single gain (≤0.001 full scale):
no compression, saturation, pitch or time changes. This fixes the hot masters
(nudge peaked at -1.9 dBFS), the click-prone onsets (elves, ghost), the DC offset
(highsoon) and the level spread, while keeping every cue exactly recognizable. The legacy
MP3/OGG files stay packaged so previously saved Juggluco choices keep playing;
those saved URIs still read as Juggluco and remap to the matching remaster on
**Apply to all**. The existing user-configured alarm duration still controls playback and may stop a cue
before its recorded ending; this change does not extend or override alarm timers.

## Container and sizes

Eight of the nine legacy originals are mono 48 kHz (highsoon is dual-mono stereo),
so mono is the faithful channel layout, not a downgrade — and the originals
themselves were lossy (64 kbps MP3, ~40–60 kbps Vorbis, 256 kbps MP3). The canonical
PCM WAVs never ship: `encode_assets.py` renders all three collection groups to a
scratch directory and encodes the 63 cues to AAC-LC 160 kbps mono 48 kHz `.m4a`
with macOS afconvert (byte-deterministic apart from MP4 wall-clock stamps, which
the script zeroes, verified across wall-clock seconds). 160 kbps mono is roughly
320 kbps stereo-equivalent — comfortably above the originals' compression.
Total: **about 5 MB instead of ~26 MB of WAV**, at the same durations and resource names.
`asset-manifest.json` pins every committed file hash, duration and the encoder
settings; the JVM suite checks the manifest against `res/raw`.

## Sources and licensing

The unchanged synthesized collections were composed with modal/additive/FM synthesis at
96 kHz before filtered conversion to 48 kHz, without recordings or third-party loops.
Their loose connection to Juggluco is its short tonal vocabulary, not copied melodies or audio.
Those files and the renderers use the repository's GPL-3.0-or-later license.

**The 18 `alert_timber_*.wav` and `alert_ember_*.wav` files and their preview reels are CC0.**
They arrange recordings from Versilian Studios' [VCSL](https://github.com/sgossner/VCSL), pinned
at `c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e`. Original source bytes, source URLs, SHA-256 hashes,
and the upstream CC0 license are preserved in [acoustic-study](acoustic-study/README.md).
No recordings are fetched during an Android build. The original stereo samples are downmixed
and resampled by macOS afconvert, then trimmed, gently filtered, arranged and level-balanced.

The nine `alert_juggluco_*.m4a` cues encode the repository's own remastered
originals, so they use the repository's GPL-3.0-or-later license like the synthesized
collections.

## Reproduce and verify

Python 3 and NumPy (authored with 2.3.5); acoustic, Juggluco and encode steps also require macOS afconvert.
The canonical check renders everything and verifies the committed AAC byte for byte:

```sh
python3 tools/alert-sounds/encode_assets.py --check
```

This runs each collection renderer (PCM stage, previews, measurements) and then
the AAC encode plus the manifest. Per-group debugging stays available:

```sh
python3 tools/alert-sounds/render.py --check --wav-dir /tmp/c
python3 tools/alert-sounds/render_acoustic.py --check --wav-dir /tmp/a
python3 tools/alert-sounds/render_juggluco.py --check --wav-dir /tmp/j
```

Each renderer writes only its own resources. Checks cover exact PCM/reel reproduction, source hashes, peaks, DC offset,
zero endpoints, encoded durations and measurements. The short studies remain separate, unchanged artifacts. Exact bytes can depend on NumPy/platform and sample-rate-converter
versions. Measurements are in `measurements.json` (original 27), `acoustic-measurements.json`
(acoustic 18) and `juggluco-measurements.json` (remastered 9); `asset-manifest.json`
pins the shipped files. Android builds use the committed M4A and do not require Python or afconvert.

Selections use `android.resource://<applicationId>/raw/<name>` so numeric resource-ID changes
cannot retarget saved choices. `alert_sounds_keep.xml` preserves all six collections and legacy raw sounds during
release shrinking. JVM tests cover selection, global remapping, external URIs, locale-independent
names, all 63 manifest assets plus nine legacy files, and duration references. Verify actual APK resource tables too:

```sh
python3 tools/alert-sounds/verify_apk.py --aapt2 "$ANDROID_HOME/build-tools/37.0.0/aapt2" path/to/app.apk
```

This verifies all 63 named AAC resources, nine legacy originals, and the exact audio bytes they resolve to, even when
release optimization shortens physical ZIP paths.

## Listening acceptance

Digital checks do not establish everyday preference, physical-speaker audibility, or clinical
alarm recognition. Audition on the target phone/watch with background noise and Bluetooth,
repeated alarms, preview switching/cancellation and saved selections after restart/update.
Physical-device listening acceptance of the new collections is still outstanding. Unset app-default sounds now resolve to Ember; explicit choices are not migrated.

## Louder alerts and volume adjustment (issue #624)

The existing Soft/Steady/Strong/Escalating overall alert intensity retains its
sound-level ratios (40%, 70%, 100%, 100%), vibration behavior, and 1.2-second sound
fade from 12%. A 30–100% sound-volume slider sits in Advanced directly below those
options, with **default/reset 80%**. Through 80%, it is strictly player-local
attenuation; no playback effect is created. **Above 80%**, an isolated
LoudnessEnhancer on that player's nonzero audio session progressively adds up to
6 dB at 100%, in addition to the player gain change. The nominal change from 80%
to 100% is therefore 7.94 dB before any peak compression. The effect may compress
peaks at these manually boosted levels, as authorized; it never affects the global
output mix or another player's session. Stop, error, preview replacement and picker
dismissal release it. Unsupported effects and the Ringtone-only fallback retain
normal playback without the extra boost. Phone stream volume is never changed.
Missing preferences use 80%; saved choices, global Apply, custom alerts and retries
retain their volume. Picker previews use the same gain, booster and resolved audio
usage as real alerts. Existing saved values above 80% now select the boosted region.

The rejected Ember EQ/strike/decay changes stay removed. **All 63 source masters
now change only by one constant gain**, rendered from their original synthesis or
recordings rather than transcoding the previous AAC. Ember uses a -0.75 dBFS PCM
ceiling; the other collections use -1 dBFS. Relative to the preceding version,
source gains range from +0.75–1.54 dB for Ember, +2–5.23 dB for Timber,
+5.48–10.51 dB for Contour, +2.93–8.18 dB for Porcelain, +2.96–8.13 dB for Halo,
+2–6.46 dB for Juggluco remasters, and +5.01–7.12 dB for xDrip-style. Ember has
less unused headroom; no EQ, new envelopes or baked-in compressor is used to force
it higher. Legacy originals remain byte-identical.

`linear-reference.json` pins the 63 preceding PCM WAV hashes. The reference-mode
render must reproduce each hash, then `verify_waveforms.py` checks that the new
PCM is proportional to it within independent 16-bit rounding error, with identical
frames/endpoints. This rejects accidental changes to notes, timbre, timing,
envelopes or nonlinear processing in the masters. `verify_loudness.py` checks all
63 shipped AACs after decoding, including an independent 192 kHz (4x) reconstruction
for inter-sample overshoot. These digital checks do not establish physical-speaker
loudness or subjective listening quality, especially at boosted settings.

**xDrip-style**, immediately above Juggluco in the picker, is a new original
synthesis: sustained descending low sirens, a faster urgent-low siren, alternating
high beeps, directional sweeps, and distinct signal/reminder/notice patterns.
`render_xdrip.py` generates every sample from oscillators with smooth edges; it
copies no xDrip recordings, melodies, or code. These assets and their generator
use this repository’s GPL-3.0-or-later license. The name indicates an electronic
alarm style, not official xDrip audio or identical phone loudness.

For regeneration and waveform/decoded-headroom verification:

```sh
python3 tools/alert-sounds/encode_assets.py
python3 tools/alert-sounds/encode_assets.py --check
python3 tools/alert-sounds/verify_waveforms.py
python3 tools/alert-sounds/verify_loudness.py
```

Electrical gain and decoded-sample checks do not establish speaker loudness or
wake-up reliability on the phones reported in #624; those need device listening.
