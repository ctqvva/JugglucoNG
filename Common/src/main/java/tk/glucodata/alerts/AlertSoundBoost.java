package tk.glucodata.alerts;

import android.media.audiofx.AudioEffect;
import android.media.audiofx.LoudnessEnhancer;

/** Optional peak-processing boost on one player's session; never the global mix. */
public final class AlertSoundBoost implements AutoCloseable {
    interface Effect {
        void targetGain(int millibels);
        void enable();
        void release();
    }
    interface Factory { Effect create(int sessionId); }

    private final Effect effect;
    private boolean released;
    private AlertSoundBoost(Effect effect) { this.effect = effect; }

    public static AlertSoundBoost attach(int sessionId, int percent) {
        return attach(sessionId, percent, id -> {
            LoudnessEnhancer nativeEffect = new LoudnessEnhancer(id);
            return new Effect() {
                @Override public void targetGain(int millibels) { nativeEffect.setTargetGain(millibels); }
                @Override public void enable() {
                    int status = nativeEffect.setEnabled(true);
                    if (status != AudioEffect.SUCCESS) throw new IllegalStateException("Boost enable failed: " + status);
                }
                @Override public void release() { nativeEffect.release(); }
            };
        });
    }

    static AlertSoundBoost attach(int sessionId, int percent, Factory factory) {
        int target = AlertSoundVolume.boostMillibels(percent);
        // Session zero is the shared output mix. Never create an effect there.
        if (target == 0 || sessionId <= 0) return null;
        Effect effect = null;
        try {
            effect = factory.create(sessionId);
            effect.targetGain(target);
            effect.enable();
            return new AlertSoundBoost(effect);
        } catch (RuntimeException e) {
            if (effect != null) {
                try { effect.release(); } catch (RuntimeException ignored) {}
            }
            android.util.Log.w("AlertSoundBoost", "Boost unavailable; retain normal playback", e);
            return null;
        }
    }

    @Override public synchronized void close() {
        if (released) return;
        released = true;
        try { effect.release(); }
        catch (RuntimeException e) { android.util.Log.w("AlertSoundBoost", "Cannot release boost", e); }
    }
}
