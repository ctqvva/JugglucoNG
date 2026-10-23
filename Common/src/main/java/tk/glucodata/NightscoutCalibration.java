package tk.glucodata;

import androidx.annotation.Keep;

public final class NightscoutCalibration {
    private static final float MGDL_PER_MMOLL = 18.0182f;

    private NightscoutCalibration() {}

    private static boolean isRawPrimary(int viewMode) {
        return viewMode == 1 || viewMode == 3;
    }

    private static float rawCurrentToMgdl(int rawCurrent) {
        if (rawCurrent <= 0) {
            return 0f;
        }
        return (rawCurrent * MGDL_PER_MMOLL) / 10.0f;
    }

    private static String resolveSensorId(String sensorId) {
        if (sensorId != null && !sensorId.trim().isEmpty()) {
            return sensorId;
        }
        final String current = Natives.lastsensorname();
        return current != null ? current : "";
    }

    private static float sanitizeMgdl(float value) {
        if (!Float.isFinite(value) || value <= 0f) {
            return 0f;
        }
        return value;
    }

    private static boolean isMmolApp() {
        return Applic.unit == 1;
    }

    private static float mgdlToDisplay(float valueMgdl) {
        if (!Float.isFinite(valueMgdl) || valueMgdl <= 0f) {
            return 0f;
        }
        return isMmolApp() ? (valueMgdl / MGDL_PER_MMOLL) : valueMgdl;
    }

    private static float displayToMgdl(float displayValue) {
        if (!Float.isFinite(displayValue) || displayValue <= 0f) {
            return 0f;
        }
        return isMmolApp() ? (displayValue * MGDL_PER_MMOLL) : displayValue;
    }

    @Keep
    public static int resolveExportedValueMgdl(
            String sensorId,
            int viewMode,
            int autoMgdl,
            int rawCurrent,
            long timestampMillis
    ) {
        try {
            final float autoValue = sanitizeMgdl(autoMgdl);
            final float rawValue = sanitizeMgdl(rawCurrentToMgdl(rawCurrent));

            float primaryValue = autoValue;
            if (isRawPrimary(viewMode) && rawValue > 0f) {
                primaryValue = rawValue;
            }
            if (primaryValue <= 0f) {
                primaryValue = autoValue > 0f ? autoValue : rawValue;
            }
            if (primaryValue <= 0f) {
                return 0;
            }

            final float calibrated = getCalibratedValueForViewMode(
                    sensorId,
                    viewMode,
                    autoValue,
                    rawValue,
                    timestampMillis
            );
            final float exported = calibrated > 0f ? calibrated : primaryValue;
            if (!Float.isFinite(exported) || exported <= 0f) {
                return 0;
            }
            return Math.round(exported);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Keep
    public static boolean hasCalibrationForViewMode(String sensorId, int viewMode) {
        try {
            final boolean rawPrimary = isRawPrimary(viewMode);
            return CalibrationAccess.hasActiveCalibration(rawPrimary, resolveSensorId(sensorId));
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Keep
    public static float getCalibratedValueForViewMode(
            String sensorId,
            int viewMode,
            float autoMgdl,
            float rawMgdl,
            long timestampMillis
    ) {
        try {
            final boolean rawPrimary = isRawPrimary(viewMode);
            final String resolvedSensorId = resolveSensorId(sensorId);
            if (!CalibrationAccess.hasActiveCalibration(rawPrimary, resolvedSensorId)) {
                return 0f;
            }

            final float baseValue = mgdlToDisplay(rawPrimary ? rawMgdl : autoMgdl);
            if (!Float.isFinite(baseValue) || baseValue <= 0f) {
                return 0f;
            }

            final float calibratedDisplay = CalibrationAccess.getCalibratedValue(
                    baseValue,
                    timestampMillis,
                    rawPrimary,
                    false,
                    resolvedSensorId
            );
            if (!Float.isFinite(calibratedDisplay) || calibratedDisplay <= 0f) {
                return 0f;
            }
            return displayToMgdl(calibratedDisplay);
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    @Keep
    public static int getNightscoutCalibrationOverride(
            String sensorId,
            int viewMode,
            int autoMgdl,
            int rawCurrent,
            long timestampMillis
    ) {
        return resolveExportedValueMgdl(sensorId, viewMode, autoMgdl, rawCurrent, timestampMillis);
    }

    /**
     * Averaging window the native Nightscout uploader has to apply to the values it
     * builds from stored polls, so its entries match the smoothed values every other
     * exchange output already sends. 0 keeps the uploader on the stored values.
     */
    @Keep
    public static int getExchangeSmoothingSeconds() {
        try {
            if (Applic.app == null) {
                return 0;
            }
            final int minutes = DataSmoothing.exchangeSmoothingMinutes(Applic.app);
            return minutes > 0 ? minutes * 60 : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Keep
    public static int getExchangeOutputIntervalSeconds() {
        try {
            if (Applic.app == null || !DataSmoothing.shouldCollapseExchangeOutputs(Applic.app)) {
                return 0;
            }
            final int minutes = DataSmoothing.collapseIntervalMinutes(DataSmoothing.getMinutes(Applic.app));
            return minutes > 0 ? minutes * 60 : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
