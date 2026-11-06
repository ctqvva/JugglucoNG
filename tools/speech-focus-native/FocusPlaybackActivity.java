package tk.glucodata;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real MediaPlayer and TTS service, using the production focus coordinator and Android adapter. */
public final class FocusPlaybackActivity extends Activity {
    private final List<String> events = new ArrayList<>();
    private final AtomicBoolean protectedSpeech = new AtomicBoolean();
    private final AtomicBoolean prematureGain = new AtomicBoolean();
    private final SpeechFocus focus = new SpeechFocus(new SpeechAudioFocus());
    private TextToSpeech tts;
    private TextView status;
    private Button run;
    private volatile CountDownLatch started, done, mediaResumed;
    private volatile String engineError;
    private volatile String currentId;
    private volatile boolean cancelled;
    private AudioManager audio;
    private MediaPlayer music, alarm;
    private AudioFocusRequest musicRequest, alarmRequest;
    private SpeechFocus.Hold closing;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Applic.app = getApplicationContext();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 48, 24, 24);
        status = new TextView(this);
        status.setText("Initializing the phone's TTS engine. System volumes will not be changed.");
        run = new Button(this);
        run.setText("Run playback tests");
        run.setEnabled(false);
        layout.addView(status);
        layout.addView(run);
        setContentView(layout);
        tts = new TextToSpeech(this, result -> runOnUiThread(() -> {
            if (result == TextToSpeech.SUCCESS) {
                status.setText("Ready: real media + TTS. Two short speech/focus transitions.");
                run.setEnabled(true);
            } else status.setText("UNAVAILABLE: phone TTS initialization failed: " + result);
        }));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {
                event("native TTS onStart " + id);
                if (id.equals(currentId)) started.countDown();
            }
            @Override public void onDone(String id) {
                event("native TTS onDone " + id);
                if (id.equals(currentId)) protectedSpeech.set(false);
                focus.release(id);
                if (id.equals(currentId)) done.countDown();
            }
            @Override public void onError(String id) {
                engineError = "Native TTS error: " + id;
                protectedSpeech.set(false);
                focus.release(id);
                if (started != null) started.countDown();
                if (done != null) done.countDown();
            }
        });
        run.setOnClickListener(v -> {
            run.setEnabled(false);
            new Thread(() -> {
                try {
                    scenario(false);
                    scenario(true);
                    show("PASS: continuing TTS and the 300 ms closing gap kept independent focus. No premature media gain. See audio-focus-result.txt.");
                } catch (Throwable e) {
                    event("FAIL " + e);
                    show("FAIL: " + e);
                } finally {
                    cleanup();
                    writeReport();
                }
            }, "speech-focus-playback-test").start();
        });
    }

    private void scenario(boolean closingOnly) throws Exception {
        check(!cancelled, "Activity closed");
        show(closingOnly ? "Testing the 300 ms closing announcement gap..." : "Testing speech continuing after alarm stop...");
        prematureGain.set(false);
        protectedSpeech.set(false);
        mediaResumed = new CountDownLatch(1);
        music = player("background", AudioAttributes.USAGE_MEDIA, .03f);
        alarm = player("alarm", AudioAttributes.USAGE_ALARM, .02f);
        // A transient background client lets any pre-test media regain its focus afterward.
        musicRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC))
                .setOnAudioFocusChangeListener(change -> {
                    event("background focus " + change + ", protected=" + protectedSpeech.get());
                    if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        if (protectedSpeech.get()) prematureGain.set(true);
                        if (music != null) music.start();
                        mediaResumed.countDown();
                    } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS) {
                        if (music != null && music.isPlaying()) music.pause();
                    }
                }).build();
        check(audio.requestAudioFocus(musicRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED, "Background focus denied");
        music.start();
        check(music.isPlaying(), "Real background MediaPlayer did not start");
        alarmRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes(AudioAttributes.USAGE_ALARM, AudioAttributes.CONTENT_TYPE_SONIFICATION))
                .setOnAudioFocusChangeListener(change -> event("alarm focus " + change)).build();
        check(audio.requestAudioFocus(alarmRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED, "Alarm focus denied");
        alarm.start();
        protectedSpeech.set(true);
        String id = closingOnly ? "closing#2" : "continuing#1";
        if (!closingOnly) {
            queue(id, false);
            check(started.await(15, TimeUnit.SECONDS), "TTS never started");
            check(engineError == null && tts.isSpeaking(), "TTS was not still speaking at alarm stop: " + engineError);
            event("alarm stops while native TTS isSpeaking=true");
        }
        alarm.stop();
        closing = focus.handoff(closingOnly, () -> {
            event("abandon alarm focus after speech handoff");
            audio.abandonAudioFocusRequest(alarmRequest);
        });
        if (closingOnly) {
            Thread.sleep(300);
            check(!prematureGain.get(), "Media regained full focus during closing delay");
            event("300 ms closing gap completed without media gain");
            queue(id, true);
            closing.close();
            closing = null;
            check(started.await(15, TimeUnit.SECONDS), "Closing TTS never started");
        }
        check(done.await(30, TimeUnit.SECONDS), "Native TTS completion timed out");
        check(engineError == null, engineError);
        check(!prematureGain.get(), "Media regained full focus before speech finished");
        check(mediaResumed.await(5, TimeUnit.SECONDS), "Media focus was not restored after speech completion");
        event("PASS " + id + ": media focus restored only after native TTS completion");
        cleanupPlayers();
    }

    private void queue(String id, boolean requestNow) {
        currentId = id;
        started = new CountDownLatch(1);
        done = new CountDownLatch(1);
        engineError = null;
        focus.claim(id, requestNow);
        tts.setAudioAttributes(attributes(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH));
        tts.setSpeechRate(.9f);
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, .2f);
        String words = "This is a real speech playback test. The announcement continues while the alarm stops. Background media must wait until these words have finished.";
        check(tts.speak(words, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.SUCCESS, "TTS queue refused");
    }

    private MediaPlayer player(String name, int usage, float volume) throws Exception {
        MediaPlayer value = new MediaPlayer();
        value.setAudioAttributes(attributes(usage, AudioAttributes.CONTENT_TYPE_MUSIC));
        try (android.content.res.AssetFileDescriptor file = getResources().openRawResourceFd(
                getResources().getIdentifier(name, "raw", getPackageName()))) {
            value.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
        }
        value.setLooping(true);
        value.prepare();
        value.setVolume(volume, volume);
        return value;
    }

    private static AudioAttributes attributes(int usage, int content) {
        return new AudioAttributes.Builder().setUsage(usage).setContentType(content).build();
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private synchronized void event(String message) {
        events.add(android.os.SystemClock.elapsedRealtime() + " " + message);
        android.util.Log.i("SpeechFocusNativeTest", message);
    }
    private void show(String message) {
        event(message);
        runOnUiThread(() -> status.setText(message));
    }
    private void cleanupPlayers() {
        if (music != null) { music.release(); music = null; }
        if (alarm != null) { alarm.release(); alarm = null; }
        if (musicRequest != null) { audio.abandonAudioFocusRequest(musicRequest); musicRequest = null; }
        if (alarmRequest != null) { audio.abandonAudioFocusRequest(alarmRequest); alarmRequest = null; }
    }
    private void cleanup() {
        protectedSpeech.set(false);
        if (closing != null) { closing.close(); closing = null; }
        focus.release(currentId);
        cleanupPlayers();
    }
    private synchronized void writeReport() {
        try (java.io.FileOutputStream out = openFileOutput("audio-focus-result.txt", MODE_PRIVATE)) {
            out.write(String.join("\n", events).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { android.util.Log.e("SpeechFocusNativeTest", "Report failed", e); }
    }
    @Override public void onDestroy() {
        cancelled = true;
        cleanup();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
