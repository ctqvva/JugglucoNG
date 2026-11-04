package tk.glucodata;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

/**
 * Best-effort ducking for glucose announcements. A refused focus request does not suppress
 * speech: the spoken value is still attempted at the selected, unchanged stream volume,
 * without any guarantee that other media will duck (including background restrictions on
 * Android 15+). Only a granted request is later abandoned.
 */
final class SpeechAudioFocus implements SpeechFocus.Audio {
    // A distinct listener also gives speech a distinct Android focus client from Notify's
    // alarm request. Abandoning speech must not abandon an alarm that started during it.
    private final AudioFocusRequest request = new AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setOnAudioFocusChangeListener(change -> {})
            .build();
    private AudioManager manager;
    private boolean held;

    @Override
    public void request() {
        if (held) return;
        manager = (AudioManager) Applic.app.getSystemService(Context.AUDIO_SERVICE);
        if (manager != null) {
            try {
                int result = manager.requestAudioFocus(request);
                held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                if (!held) android.util.Log.w("SpeechAudioFocus",
                        "Focus denied (" + result + "); speech continues without guaranteed ducking");
            } catch (RuntimeException e) {
                android.util.Log.w("SpeechAudioFocus", "Focus unavailable; speech continues without guaranteed ducking", e);
            }
        }
    }

    @Override
    public void abandon() {
        if (held) {
            held = false;
            manager.abandonAudioFocusRequest(request);
        }
    }
}
