package tk.glucodata;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Shares predictions between the two layouts of one notification, never between updates. */
final class NotificationPredictionBatch {
    private record Parameters(boolean isMmol, int viewMode, boolean hasCalibration,
                              String sensorId, float targetLow, float targetHigh) {}

    // History is owned by this notification render and is not mutated between layouts.
    // Identity keeps distinct peer histories separate without hashing every reading.
    private final Map<List<GlucosePoint>, Map<Parameters, List<NotificationPredictionSeries>>> predictions =
            new IdentityHashMap<>();

    List<NotificationPredictionSeries> resolve(
            List<GlucosePoint> history, boolean isMmol, int viewMode, boolean hasCalibration,
            String sensorId, float targetLow, float targetHigh,
            Supplier<List<NotificationPredictionSeries>> build) {
        final Parameters parameters = new Parameters(isMmol, viewMode, hasCalibration,
                sensorId, targetLow, targetHigh);
        return predictions.computeIfAbsent(history, ignored -> new HashMap<>())
                .computeIfAbsent(parameters, ignored -> build.get());
    }
}
