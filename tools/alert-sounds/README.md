# JugglucoNG sound collections

27 original synthesized cues in three optional collections. Select **Contour**, **Porcelain**,
or **Halo** in an alert's sound picker. The alert determines the cue. The global **Apply to all**
action selects that collection's corresponding cue for each standard and custom glucose alert.
Existing app defaults and saved external sounds remain unchanged. Collection names are proper
names and remain the same in every locale; the surrounding picker uses existing translations.
Selection is exposed in the phone Compose settings. The assets are also packaged for Wear;
this change does not add a collection picker to the legacy Wear settings.

## Listen

Each reel plays: low, high, urgent low, urgent high, falling, rising, signal, reminder, notice.
There is a one-second gap between cues, in addition to each cue's release.

- [Contour](contour-preview.wav): rounded harmonic tones with gentle saturation.
- [Porcelain](porcelain-preview.wav): lightly inharmonic glass with a restrained, short shimmer.
- [Halo](halo-preview.wav): a brighter harmonic pulse with a quicker attack.

These are original additive-synthesis compositions, with no recordings, sampled instruments,
third-party loops, or generated speech. The loose connection to Juggluco is its short tonal
alert vocabulary, not reuse of its melodies or recordings. Renderer and audio are distributed
under the repository's GPL-3.0-or-later license.

## Cue vocabulary

| Cue | Used for | Audible structure |
| --- | --- | --- |
| Low | Low glucose, custom low | Three descending notes |
| High | High/persistent high, custom high | Two ascending notes |
| Urgent low | Very low | Two groups of three short descending notes |
| Urgent high | Very high | Two groups of two short ascending notes |
| Falling | Forecast low, falling fast | Short descending pair |
| Rising | Forecast high, rising fast | Short ascending pair |
| Signal | Signal loss, missed reading | Repeated pitch, pause, lower response |
| Reminder | Amount, sensor expiry | Gentle ascending major third |
| Notice | Value available | Single short tone |

Urgency is conveyed by rhythm and a 2.5 dB increase in the synthesis RMS target, rather than
an abrasive siren. Cues are 1.18–2.38 seconds long. They have explicit attacks/releases, short
mono reflections, and a quiet tail for repeated playback. Mono avoids stereo cancellation
and is appropriate to a phone speaker. Production files are lossless 48 kHz, 16-bit PCM WAV.
All 27 resources total about 4.7 MB before APK compression. Preview reels are outside Android
resources and are not packaged into the app.

## Reproduce and verify

Use Python 3 with NumPy (authored with NumPy 2.3.5):

```sh
python3 tools/alert-sounds/render.py
python3 tools/alert-sounds/render.py --check
```

`--check` compares the committed PCM and reels with the deterministic score, checks peaks,
DC offset and zero endpoints, and verifies the measured durations/RMS/peak levels and hashes
in `measurements.json`. Exact PCM reproduction can depend on the NumPy/platform math runtime.
Keep generated files together when deliberately revising a composition.

Android selections use `android.resource://<applicationId>/raw/<name>` so an app update cannot
retarget a saved selection through a reassigned numeric resource ID. `alert_sounds_keep.xml`
preserves those dynamically resolved resources during release shrinking. JVM tests cover
cue assignment, global family application, external-URI preservation, locale-independent
resource names, and the presence of all referenced WAV files. Inspect the release APK as well:
all 27 named raw resources and their original PCM bytes must survive shrinking. Release
optimization can shorten the physical ZIP paths; verify through the resource table:

```sh
python3 tools/alert-sounds/verify_apk.py --aapt2 "$ANDROID_HOME/build-tools/37.0.0/aapt2" path/to/app.apk
```

## Listening acceptance

Automated checks do not establish preference, real-world audibility, or clinical alarm
recognition. Before choosing these as defaults, audition on the target phone/watch speaker
at everyday volume, with background noise and Bluetooth routing, including repeated alarms,
preview switching, cancellation, and a saved selection after app restart/update. Compare
low versus high and regular versus urgent cues without looking at the screen. These checks
have not been completed by a human listener as part of the generation process.
