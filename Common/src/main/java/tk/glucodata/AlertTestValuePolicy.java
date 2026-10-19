package tk.glucodata;

import tk.glucodata.alerts.AlertConfig;
import tk.glucodata.alerts.AlertType;

final class AlertTestValuePolicy {
    private static final float MMOL_MARGIN = 0.5f;
    private static final float MGDL_MARGIN = 10.0f;
    private static final float MMOL_NEUTRAL = 5.5f;
    private static final float MGDL_NEUTRAL = 100.0f;

    private AlertTestValuePolicy() {
    }

    static float resolve(AlertType type, AlertConfig config, boolean isMmol, float currentDisplayValue) {
        final float margin = isMmol ? MMOL_MARGIN : MGDL_MARGIN;
        final Float configuredThreshold = config != null ? config.getThreshold() : null;
        final float threshold = configuredThreshold != null ? configuredThreshold : Float.NaN;

        if (isLowFamily(type)) {
            final float fallback = isMmol ? 3.5f : 63.0f;
            return Float.isFinite(threshold) && threshold > 0.1f
                    ? Math.max(0.2f, threshold - margin)
                    : fallback;
        }
        if (isHighFamily(type)) {
            final float fallback = isMmol ? 10.0f : 180.0f;
            return Float.isFinite(threshold) && threshold > 0.1f
                    ? threshold + margin
                    : fallback;
        }
        if (Float.isFinite(currentDisplayValue) && currentDisplayValue > 0.1f) {
            return currentDisplayValue;
        }
        return isMmol ? MMOL_NEUTRAL : MGDL_NEUTRAL;
    }

    private static boolean isLowFamily(AlertType type) {
        return type == AlertType.LOW || type == AlertType.VERY_LOW || type == AlertType.PRE_LOW;
    }

    private static boolean isHighFamily(AlertType type) {
        return type == AlertType.HIGH || type == AlertType.VERY_HIGH || type == AlertType.PRE_HIGH
                || type == AlertType.PERSISTENT_HIGH;
    }
}
