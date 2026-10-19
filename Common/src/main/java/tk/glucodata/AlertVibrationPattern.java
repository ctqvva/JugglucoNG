package tk.glucodata;

import static tk.glucodata.alerts.AlertConfigKt.MAX_SOUND_DELAY_SECONDS;
import static tk.glucodata.alerts.AlertConfigKt.sanitizeAlertDurationSeconds;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

final class AlertVibrationPattern {
    // Sized for the densest base pattern (9 segments per 1.2s loop) looped
    // across the maximum sound delay plus the maximum alarm duration (360s).
    private static final int MAX_SEGMENTS = 4096;

    final long[] timings;
    final int[] amplitudes;

    private AlertVibrationPattern(long[] timings, int[] amplitudes) {
        this.timings = timings;
        this.amplitudes = amplitudes;
    }

    static AlertVibrationPattern buildFinite(long[] baseTimings, int[] baseAmplitudes, int durationSeconds) {
        return buildFinite(baseTimings, baseAmplitudes, durationSeconds, 0);
    }

    // leadInSeconds extends the pattern across the silent sound-delay phase
    // ("vibrate first"), so it is bounded by the sound-delay cap rather than
    // folded into the alarm-duration sanitizer, which would reject the sum.
    static AlertVibrationPattern buildFinite(long[] baseTimings, int[] baseAmplitudes, int durationSeconds,
            int leadInSeconds) {
        // Never hand Android an infinite alert vibration; scheduled stop is only an early cleanup path.
        final int boundedLeadIn = Math.max(0, Math.min(leadInSeconds, MAX_SOUND_DELAY_SECONDS));
        final long maxDurationMs = TimeUnit.SECONDS
                .toMillis(sanitizeAlertDurationSeconds(durationSeconds) + (long) boundedLeadIn);
        final ArrayList<Long> timingList = new ArrayList<>();
        final ArrayList<Integer> amplitudeList = new ArrayList<>();
        long elapsedMs = 0L;
        boolean emittedAny = false;

        while (elapsedMs < maxDurationMs && timingList.size() < MAX_SEGMENTS) {
            long loopDurationMs = 0L;
            for (int i = 0; i < baseTimings.length && elapsedMs < maxDurationMs; i++) {
                final long baseDuration = Math.max(0L, baseTimings[i]);
                loopDurationMs += baseDuration;
                if (emittedAny && baseDuration == 0L) {
                    continue;
                }
                final long clippedDuration = Math.min(baseDuration, maxDurationMs - elapsedMs);
                timingList.add(clippedDuration);
                amplitudeList.add(baseAmplitudes.length == 0
                        ? 0
                        : baseAmplitudes[Math.min(i, baseAmplitudes.length - 1)]);
                elapsedMs += clippedDuration;
                emittedAny = true;
            }
            if (loopDurationMs <= 0L) {
                break;
            }
        }

        if (elapsedMs <= 0L) {
            timingList.clear();
            amplitudeList.clear();
            timingList.add(maxDurationMs);
            amplitudeList.add(0);
        } else if (timingList.isEmpty()) {
            timingList.add(maxDurationMs);
            amplitudeList.add(0);
        }

        final long[] timings = new long[timingList.size()];
        final int[] amplitudes = new int[amplitudeList.size()];
        for (int i = 0; i < timingList.size(); i++) {
            timings[i] = timingList.get(i);
            amplitudes[i] = amplitudeList.get(i);
        }
        return new AlertVibrationPattern(timings, amplitudes);
    }

    long totalDurationMs() {
        long total = 0L;
        for (long timing : timings) {
            total += timing;
        }
        return total;
    }
}
