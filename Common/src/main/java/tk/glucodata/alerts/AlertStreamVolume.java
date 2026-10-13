package tk.glucodata.alerts;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import java.util.HashMap;
import java.util.Map;

/** Own a stream setting only while an alert or preview is playing. */
public final class AlertStreamVolume {
    private static Controller controller;

    private AlertStreamVolume() {}

    private static synchronized Controller get(Context context) {
        if (controller == null) {
            controller = new Controller(new AndroidVolumeAccess(context));
            controller.recover();
        }
        return controller;
    }

    public static void recoverInterrupted(Context context) {
        try {
            get(context);
        } catch (RuntimeException e) {
            android.util.Log.w("AlertStreamVolume", "Cannot recover alert volume", e);
        }
    }

    public static Lease acquire(Context context, AudioAttributes attributes, int percent) {
        try {
            return get(context).acquire(attributes.getVolumeControlStream(), percent);
        } catch (RuntimeException e) {
            android.util.Log.w("AlertStreamVolume", "Cannot set alert stream volume", e);
            return null;
        }
    }

    interface VolumeAccess {
        boolean allowed(int stream);
        int read(int stream);
        int minimum(int stream);
        int maximum(int stream);
        void write(int stream, int index);
        void save(int stream, int original, int written);
        void clear(int stream);
        Map<Integer, int[]> interrupted();
    }

    private static final class State {
        final int stream, original;
        int written;
        boolean relinquished;
        final Map<Lease, Integer> targets = new HashMap<>();
        State(int stream, int original) {
            this.stream = stream;
            this.original = this.written = original;
        }
    }

    public static final class Lease implements AutoCloseable {
        private final Controller owner;
        private final State state;
        private boolean closed;
        private Lease(Controller owner, State state) { this.owner = owner; this.state = state; }
        @Override public void close() { owner.release(this); }
    }

    static final class Controller {
        private final VolumeAccess access;
        private final Map<Integer, State> streams = new HashMap<>();
        Controller(VolumeAccess access) { this.access = access; }

        synchronized void recover() {
            for (Map.Entry<Integer, int[]> entry : access.interrupted().entrySet()) {
                int stream = entry.getKey();
                int[] saved = entry.getValue();
                try {
                    if (access.allowed(stream) && access.read(stream) == saved[1]) {
                        access.write(stream, saved[0]);
                    }
                    access.clear(stream);
                } catch (RuntimeException e) {
                    android.util.Log.w("AlertStreamVolume", "Cannot restore interrupted volume", e);
                }
            }
        }

        synchronized Lease acquire(int stream, int percent) {
            if (stream < 0 || !access.allowed(stream)) return null;
            State state = streams.get(stream);
            if (state == null) {
                state = new State(stream, access.read(stream));
                streams.put(stream, state);
            }
            Lease lease = new Lease(this, state);
            state.targets.put(lease, AlertSoundVolume.streamIndex(percent,
                    access.minimum(stream), access.maximum(stream)));
            update(state, false);
            return lease;
        }

        private void update(State state, boolean finished) {
            try {
                // Do not undo a hardware-volume change, mute or DND transition.
                if (access.read(state.stream) != state.written || !access.allowed(state.stream)) {
                    state.relinquished = true;
                }
                if (!state.relinquished) {
                    int target = state.original;
                    if (!finished) {
                        target = 0;
                        for (int value : state.targets.values()) target = Math.max(target, value);
                    }
                    if (target != state.written) {
                        // Journal before the Android write, including the crash window.
                        access.save(state.stream, state.original, target);
                        access.write(state.stream, target);
                        state.written = access.read(state.stream);
                        access.save(state.stream, state.original, state.written);
                    }
                }
                if (finished || state.relinquished) access.clear(state.stream);
            } catch (RuntimeException e) {
                state.relinquished = true;
                // Leave the journal for restart recovery if a write partially succeeded.
                android.util.Log.w("AlertStreamVolume", "Cannot update alert volume", e);
            }
        }

        synchronized void release(Lease lease) {
            if (lease.closed) return;
            lease.closed = true;
            State state = lease.state;
            state.targets.remove(lease);
            boolean finished = state.targets.isEmpty();
            update(state, finished);
            if (finished) streams.remove(state.stream);
        }
    }

    private static final class AndroidVolumeAccess implements VolumeAccess {
        private final AudioManager audio;
        private final NotificationManager notifications;
        private final SharedPreferences journal;
        AndroidVolumeAccess(Context context) {
            audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            journal = context.getSharedPreferences("alert_stream_volume", Context.MODE_PRIVATE);
        }
        @Override public boolean allowed(int stream) {
            if (audio == null || audio.isVolumeFixed()) return false;
            if (notifications != null && notifications.getCurrentInterruptionFilter()
                    != NotificationManager.INTERRUPTION_FILTER_ALL) return false;
            return stream == AudioManager.STREAM_ALARM || stream == AudioManager.STREAM_MUSIC
                    || audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
        }
        @Override public int read(int stream) { return audio.getStreamVolume(stream); }
        @Override public int minimum(int stream) {
            return Build.VERSION.SDK_INT >= 28 ? audio.getStreamMinVolume(stream) : 0;
        }
        @Override public int maximum(int stream) { return audio.getStreamMaxVolume(stream); }
        @Override public void write(int stream, int index) { audio.setStreamVolume(stream, index, 0); }
        @Override public void save(int stream, int original, int written) {
            if (!journal.edit().putInt("original_" + stream, original)
                    .putInt("written_" + stream, written).commit()) {
                throw new IllegalStateException("Cannot journal alert volume");
            }
        }
        @Override public void clear(int stream) {
            journal.edit().remove("original_" + stream).remove("written_" + stream).commit();
        }
        @Override public Map<Integer, int[]> interrupted() {
            Map<Integer, int[]> result = new HashMap<>();
            for (String key : journal.getAll().keySet()) {
                if (!key.startsWith("original_")) continue;
                int stream = Integer.parseInt(key.substring(9));
                if (journal.contains("written_" + stream)) {
                    result.put(stream, new int[] { journal.getInt(key, 0),
                            journal.getInt("written_" + stream, 0) });
                }
            }
            return result;
        }
    }
}
