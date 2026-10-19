package tk.glucodata;

import static java.lang.String.format;

final class ExchangeGlucosePayload {
    private static final double MGDL_PER_MMOLL = 18.0182;

    final String sensorId;
    final String primaryText;
    final double primaryDisplayValue;
    final int primaryMgdl;
    final float autoValue;
    final int autoMgdl;
    final float rawValue;
    final float rate;
    final int trendIndex;
    final String trendName;
    final String trendArrow;
    final float trendRate;
    final long timeMillis;
    final int sensorGen;

    private ExchangeGlucosePayload(
            String sensorId,
            String primaryText,
            double primaryDisplayValue,
            int primaryMgdl,
            float autoValue,
            int autoMgdl,
            float rawValue,
            float rate,
            ExchangeTrend trend,
            long timeMillis,
            int sensorGen) {
        this.sensorId = sensorId;
        this.primaryText = primaryText;
        this.primaryDisplayValue = primaryDisplayValue;
        this.primaryMgdl = primaryMgdl;
        this.autoValue = autoValue;
        this.autoMgdl = autoMgdl;
        this.rawValue = rawValue;
        this.rate = rate;
        this.trendIndex = trend.index;
        this.trendName = trend.name;
        this.trendArrow = trend.arrow;
        this.trendRate = trend.rateMgdlPerMinute;
        this.timeMillis = timeMillis;
        this.sensorGen = sensorGen;
    }

    String getSensorId() {
        return sensorId;
    }

    String getPrimaryText() {
        return primaryText;
    }

    double getPrimaryDisplayValue() {
        return primaryDisplayValue;
    }

    int getPrimaryMgdl() {
        return primaryMgdl;
    }

    float getAutoValue() {
        return autoValue;
    }

    int getAutoMgdl() {
        return autoMgdl;
    }

    float getRawValue() {
        return rawValue;
    }

    float getRate() {
        return rate;
    }

    int getTrendIndex() {
        return trendIndex;
    }

    String getTrendName() {
        return trendName;
    }

    String getTrendArrow() {
        return trendArrow;
    }

    float getTrendRate() {
        return trendRate;
    }

    long getTimeMillis() {
        return timeMillis;
    }

    int getSensorGen() {
        return sensorGen;
    }

    static ExchangeGlucosePayload resolve(
            String preferredSensorId,
            double fallbackDisplayValue,
            float fallbackRate,
            long fallbackTimeMillis,
            int fallbackSensorGen,
            String fallbackPrimaryText) {
        CurrentDisplaySource.Snapshot current = null;
        try {
            current = CurrentDisplaySource.resolveCurrentForExchange(Notify.glucosetimeout, preferredSensorId);
        } catch (Throwable th) {
            if (Log.doLog) {
                Log.i("ExchangeGlucosePayload", "resolveCurrentForExchange failed " + th);
            }
        }

        if (current != null) {
            final double primaryDisplayValue = isFinitePositive(current.getPrimaryValue())
                    ? current.getPrimaryValue()
                    : (isFinitePositive(current.getSharedDisplayValue())
                            ? current.getSharedDisplayValue()
                            : fallbackDisplayValue);
            final String rawSensorId = notBlank(current.getSensorId()) ? current.getSensorId() : preferredSensorId;
            final String sensorId = SensorIdentity.resolveAppSensorId(rawSensorId) != null
                    ? SensorIdentity.resolveAppSensorId(rawSensorId)
                    : rawSensorId;
            final String primaryText = formatPrimary(primaryDisplayValue, current.getPrimaryStr());
            final float nativeRate = Float.isFinite(current.getRate()) ? current.getRate() : fallbackRate;
            final long timeMillis = current.getTimeMillis() > 0L ? current.getTimeMillis() : fallbackTimeMillis;
            final int sensorGen = current.getSensorGen() != 0 ? current.getSensorGen() : fallbackSensorGen;
            final int primaryMgdl = toMgdl(primaryDisplayValue);
            final float autoValue = current.getAutoValue();
            final int autoMgdl = toMgdl(autoValue);
            final float rawValue = current.getRawValue();
            // Opt-in: send the app's computed trend instead of the sensor's
            // lagging estimate; index/name follow the sent rate so the
            // outgoing extras stay consistent with each other.
            final boolean computedTrend = BroadcastTrendRate.enabled();
            final float rate = computedTrend ? BroadcastTrendRate.computed(sensorId, nativeRate) : nativeRate;
            final ExchangeTrend trend = computedTrend
                    ? ExchangeTrend.fromRate(rate)
                    : ExchangeTrend.resolve(sensorId, timeMillis, rate);
            return new ExchangeGlucosePayload(
                    sensorId,
                    primaryText,
                    primaryDisplayValue,
                    primaryMgdl,
                    autoValue,
                    autoMgdl,
                    rawValue,
                    rate,
                    trend,
                    timeMillis,
                    sensorGen);
        }

        final String fallbackSensorId = SensorIdentity.resolveAppSensorId(preferredSensorId) != null
                ? SensorIdentity.resolveAppSensorId(preferredSensorId)
                : preferredSensorId;

        final boolean computedTrend = BroadcastTrendRate.enabled();
        final float outgoingRate = computedTrend
                ? BroadcastTrendRate.computed(fallbackSensorId, fallbackRate)
                : fallbackRate;
        return new ExchangeGlucosePayload(
                fallbackSensorId,
                formatPrimary(fallbackDisplayValue, fallbackPrimaryText),
                fallbackDisplayValue,
                toMgdl(fallbackDisplayValue),
                Float.NaN,
                0,
                Float.NaN,
                outgoingRate,
                computedTrend
                        ? ExchangeTrend.fromRate(outgoingRate)
                        : ExchangeTrend.resolve(fallbackSensorId, fallbackTimeMillis, fallbackRate),
                fallbackTimeMillis,
                fallbackSensorGen);
    }

    private static boolean isFinitePositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isEmpty();
    }

    private static int toMgdl(double displayValue) {
        if (!isFinitePositive(displayValue)) {
            return 0;
        }
        return (int) Math.round(Applic.unit == 1 ? displayValue * MGDL_PER_MMOLL : displayValue);
    }

    private static String formatPrimary(double displayValue, String fallbackPrimaryText) {
        if (notBlank(fallbackPrimaryText)) {
            return fallbackPrimaryText;
        }
        final String displayFormat = Notify.pureglucoseformat != null
                ? Notify.pureglucoseformat
                : (Applic.unit == 1 ? "%.1f" : "%.0f");
        return format(Applic.usedlocale, displayFormat, displayValue);
    }
}
