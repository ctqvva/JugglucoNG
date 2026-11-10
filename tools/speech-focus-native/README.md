# Native speech-focus playback check

This standalone APK compiles the production `SpeechFocus.java` and
`SpeechAudioFocus.java` directly, with a context-only `Applic` host. It does not
load the CGM application's JNI, sensor state, preferences or services. The real
Talker queue, preview ownership and Notify handoff call are covered separately by
`TalkerPlaybackTests`; this APK checks the coordinator against Android's real
AudioManager, MediaPlayer and installed TextToSpeech service.

Build with Java 21 and Android SDK platform 36/build-tools 37.0.0:

```sh
JAVA_HOME=/path/to/jdk-21 ANDROID_HOME=/path/to/sdk \
  python3 tools/speech-focus-native/build.py --out /private/tmp/speech-focus-native
```

Use the Device-panel tools to select/open the requested phone, then the returned
`agent-device` command to install `speech-focus-test.apk`, open
`org.jugglucong.speechfocustest` and press **Run playback tests**. Device actions
must follow the current agent/device instructions. Phone installation approval
and an initialized TTS engine are required.

Two scenarios use actual playback: TTS continues after alarm sound stops, and a
closing announcement starts after a 300 ms gap. The test fails if its background
media client regains full focus during either protected interval, or does not
regain focus after speech completes. Source players have quiet per-player gain;
no system stream volume is changed. Test focus requests are temporary and are
abandoned during cleanup. The app displays PASS/FAIL and writes
`files/audio-focus-result.txt`; it can be read with the project's allowed test/log
tools or `adb -s <selected-phone> shell run-as org.jugglucong.speechfocustest cat
files/audio-focus-result.txt`.

This does not establish behavior of every third-party media app, earbud routing,
or Android 15+ background focus restrictions when run on an older phone. It does
not replace the full JVM suite or the phone/Wear compilation checks.

Recorded device result: [Mi 9T, Android 10, 2026-11-01](results/mi9t-2026-11-01.txt). Both real-engine scenarios passed; the trace includes the tested production-source hashes.
