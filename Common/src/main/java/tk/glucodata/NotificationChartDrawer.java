package tk.glucodata;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Shader;
import android.util.DisplayMetrics;
import android.graphics.Matrix;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

public class NotificationChartDrawer {
    private static final long DEFAULT_CHART_DURATION_MS = 3 * 60 * 60 * 1000L;
    private static final String AOD_OVERLAY_SERVICE_NAME =
            "tk.glucodata.accessibility.AODOverlayService";
    private static final float DASHBOARD_PRIMARY_IDENTITY_TINT = 0.22f;
    private static final float DASHBOARD_PRIMARY_LINE_ALPHA = 1.0f;
    private static final float DASHBOARD_PRIMARY_THRESHOLD_ALPHA = 0.96f;
    private static final float DASHBOARD_PRIMARY_STROKE_SCALE = 1.12f;
    private static final float DASHBOARD_SECONDARY_LINE_ALPHA = 0.82f;
    private static final float DASHBOARD_SECONDARY_STROKE_SCALE = 0.68f;
    private static final float DASHBOARD_PEER_NEUTRAL_BLEND = 0.46f;
    private static final float DASHBOARD_PEER_LINE_ALPHA = 0.88f;
    private static final float DASHBOARD_PEER_THRESHOLD_ALPHA = 0.84f;
    private static final float DASHBOARD_PEER_EXTREME_ALPHA = 0.86f;
    private static final float DASHBOARD_PEER_AUTO_PASS_ALPHA = 1.0f;
    private static final float DASHBOARD_PEER_RAW_PASS_ALPHA = 0.82f;

    public static class PeerSeries {
        public final String sensorId;
        public final int viewMode;
        public final int color;
        public final List<GlucosePoint> points;

        public PeerSeries(String sensorId, int viewMode, int color, List<GlucosePoint> points) {
            this.sensorId = sensorId;
            this.viewMode = viewMode;
            this.color = color;
            this.points = points != null ? points : Collections.emptyList();
        }
    }

    public static class ValueItem {
        public final String text;
        public final int color;
        /** Trend rate for the inline arrow; NaN when unknown. */
        public final float rate;

        public ValueItem(String text, int color) {
            this(text, color, Float.NaN);
        }

        public ValueItem(String text, int color, float rate) {
            this.text = text != null ? text : "";
            this.color = color;
            this.rate = rate;
        }
    }

    // Optional GDH-style traffic coloring of the chart line, resolved from
    // prefs at each drawChartInternal entry (single funnel for all charts).
    private static volatile boolean sTrafficLineColors = false;
    private static volatile boolean sTrafficLineDark = false;

    private static int resolveThresholdPointColor(
            float value,
            float targetLow,
            float targetHigh,
            float veryLowThreshold,
            float veryHighThreshold,
            int inRangeColor,
            boolean isMmol) {
        if (sTrafficLineColors) {
            return GlucoseRangeColors.trafficColorForValue(
                    value,
                    targetLow,
                    targetHigh,
                    veryLowThreshold,
                    veryHighThreshold,
                    sTrafficLineDark,
                    isMmol,
                    inRangeColor);
        }
        return GlucoseRangeColors.colorForValue(
                value,
                targetLow,
                targetHigh,
                veryLowThreshold,
                veryHighThreshold,
                inRangeColor,
                false,
                isMmol);
    }

    private static void drawThresholdColoredSeries(
            Canvas canvas,
            Paint linePaint,
            List<Long> timestamps,
            List<Float> values,
            long startTime,
            long duration,
            float chartLeft,
            float chartBottom,
            float chartWidth,
            float chartHeight,
            float minY,
            float yRange,
            float targetLow,
            float targetHigh,
            float veryLowThreshold,
            float veryHighThreshold,
            boolean isMmol,
            int inRangeColor) {
        drawSeries(
                canvas,
                linePaint,
                timestamps,
                values,
                startTime,
                duration,
                chartLeft,
                chartBottom,
                chartWidth,
                chartHeight,
                minY,
                yRange,
                targetLow,
                targetHigh,
                veryLowThreshold,
                veryHighThreshold,
                isMmol,
                inRangeColor,
                true,
                0,
                1f,
                1f);
    }

    private static boolean showsAuto(int viewMode) {
        return viewMode == 0 || viewMode == 2 || viewMode == 3;
    }

    private static boolean showsRaw(int viewMode) {
        return viewMode == 1 || viewMode == 2 || viewMode == 3;
    }

    private static int notificationPrimaryLineColor(boolean isDark) {
        return isDark ? 0xFFF2F0EA : 0xFF1D1B20;
    }

    private static int dashboardPrimaryLineColor(boolean isDark, String sensorId, boolean multiSensor) {
        int base = notificationPrimaryLineColor(isDark);
        if (!multiSensor || sensorId == null || sensorId.trim().isEmpty()) {
            return base;
        }
        return SensorVisuals.blendArgb(
                base,
                SensorVisuals.colorArgb(sensorId),
                DASHBOARD_PRIMARY_IDENTITY_TINT);
    }

    private static int dashboardPrimaryIdentityColor(String sensorId, boolean multiSensor) {
        if (!multiSensor || sensorId == null || sensorId.trim().isEmpty()) {
            return 0;
        }
        return SensorVisuals.colorArgb(sensorId);
    }

    public static int notificationChartPrimaryValueColor(
            Context context,
            float value,
            boolean isMmol,
            String sensorId,
            boolean multiSensor) {
        boolean isDark = useLightOnTransparentPalette(context);
        return notificationPrimaryLineColor(isDark);
    }

    public static int notificationChartSecondaryValueColor(Context context) {
        return defaultSecondaryValueTextColor(context);
    }

    public static int notificationChartTertiaryValueColor(Context context) {
        boolean isDark = useLightOnTransparentPalette(context);
        return isDark ? 0x73FFFFFF : 0x73000000;
    }

    private static int dashboardPeerLineBase(int sensorColor, boolean isDark) {
        int neutral = DashboardChartColors.onSurfaceVariant(isDark);
        return SensorVisuals.blendArgb(sensorColor, neutral, DASHBOARD_PEER_NEUTRAL_BLEND);
    }

    private static int resolvePrimaryPointColor(
            float value,
            float targetLow,
            float targetHigh,
            float veryLowThreshold,
            float veryHighThreshold,
            int inRangeColor,
            int thresholdTintColor,
            boolean isMmol,
            float lineAlpha,
            float thresholdAlpha) {
        int color = resolveThresholdPointColor(
                value,
                targetLow,
                targetHigh,
                veryLowThreshold,
                veryHighThreshold,
                inRangeColor,
                isMmol);
        boolean inRange = (color & 0x00FFFFFF) == (inRangeColor & 0x00FFFFFF);
        if (!inRange && thresholdTintColor != 0) {
            color = SensorVisuals.blendArgb(color, thresholdTintColor, DASHBOARD_PRIMARY_IDENTITY_TINT);
        }
        return withAlpha(color, inRange ? lineAlpha : thresholdAlpha);
    }

    private static int resolveSubtlePeerPointColor(
            float value,
            float targetLow,
            float targetHigh,
            float veryLowThreshold,
            float veryHighThreshold,
            int sensorColor,
            boolean isMmol,
            boolean isDark,
            float passAlpha) {
        // Match the dashboard peer-trace treatment: identity color blended
        // toward onSurfaceVariant, then rendered at dashboard-like opacity.
        int toned = dashboardPeerLineBase(sensorColor, isDark);
        int thresholdColor = resolveThresholdPointColor(
                value,
                targetLow,
                targetHigh,
                veryLowThreshold,
                veryHighThreshold,
                sensorColor,
                isMmol);
        if ((thresholdColor & 0x00FFFFFF) == (sensorColor & 0x00FFFFFF)) {
            return withAlpha(toned, DASHBOARD_PEER_LINE_ALPHA * passAlpha);
        }
        boolean extreme = value <= veryLowThreshold || value >= veryHighThreshold;
        float blendFraction = extreme ? 0.58f : 0.48f;
        float alpha = (extreme ? DASHBOARD_PEER_EXTREME_ALPHA : DASHBOARD_PEER_THRESHOLD_ALPHA) * passAlpha;
        return withAlpha(GlucoseRangeColors.blend(toned, thresholdColor, blendFraction), alpha);
    }

    private static float peerPassAlpha(boolean drawRaw, boolean drawAuto, boolean rawPass) {
        if (!drawRaw || !drawAuto) {
            return DASHBOARD_PEER_AUTO_PASS_ALPHA;
        }
        return rawPass ? DASHBOARD_PEER_RAW_PASS_ALPHA : DASHBOARD_PEER_AUTO_PASS_ALPHA;
    }

    private static float notificationLineAlpha(int color, int primaryColor, int secondaryColor) {
        if (color == primaryColor) {
            return DASHBOARD_PRIMARY_LINE_ALPHA;
        }
        if (color == secondaryColor) {
            return DASHBOARD_SECONDARY_LINE_ALPHA;
        }
        return 1f;
    }

    private static float notificationThresholdAlpha(int color, int primaryColor, int secondaryColor) {
        if (color == primaryColor) {
            return DASHBOARD_PRIMARY_THRESHOLD_ALPHA;
        }
        return notificationLineAlpha(color, primaryColor, secondaryColor);
    }

    private static float notificationStrokeScale(int color, int primaryColor, int secondaryColor) {
        if (color == primaryColor) {
            return DASHBOARD_PRIMARY_STROKE_SCALE;
        }
        if (color == secondaryColor) {
            return DASHBOARD_SECONDARY_STROKE_SCALE;
        }
        return 1f;
    }

    private static void drawSubtlePeerSeries(
            Canvas canvas,
            Paint linePaint,
            List<Long> timestamps,
            List<Float> values,
            long startTime,
            long duration,
            float chartLeft,
            float chartBottom,
            float chartWidth,
            float chartHeight,
            float minY,
            float yRange,
            float targetLow,
            float targetHigh,
            float veryLowThreshold,
            float veryHighThreshold,
            boolean isMmol,
            int sensorColor,
            boolean isDark,
            float passAlpha) {
        int size = Math.min(timestamps.size(), values.size());
        if (size < 2) {
            return;
        }

        long previousTimestamp = 0L;
        float previousValue = 0f;
        boolean hasPrevious = false;

        for (int index = 0; index < size; index++) {
            float currentValue = values.get(index);
            long currentTimestamp = timestamps.get(index);
            boolean valid = Float.isFinite(currentValue) && currentValue > 0.1f;
            if (!valid) {
                hasPrevious = false;
                continue;
            }

            if (hasPrevious) {
                float startX = chartLeft + ((previousTimestamp - startTime) / (float) duration) * chartWidth;
                float startY = chartBottom - ((previousValue - minY) / yRange) * chartHeight;
                float endX = chartLeft + ((currentTimestamp - startTime) / (float) duration) * chartWidth;
                float endY = chartBottom - ((currentValue - minY) / yRange) * chartHeight;
                int startColor = resolveSubtlePeerPointColor(
                        previousValue,
                        targetLow,
                        targetHigh,
                        veryLowThreshold,
                        veryHighThreshold,
                        sensorColor,
                        isMmol,
                        isDark,
                        passAlpha);
                int endColor = resolveSubtlePeerPointColor(
                        currentValue,
                        targetLow,
                        targetHigh,
                        veryLowThreshold,
                        veryHighThreshold,
                        sensorColor,
                        isMmol,
                        isDark,
                        passAlpha);
                if (startColor == endColor) {
                    linePaint.setShader(null);
                    linePaint.setColor(startColor);
                } else {
                    linePaint.setShader(new LinearGradient(
                            startX,
                            startY,
                            endX,
                            endY,
                            startColor,
                            endColor,
                            Shader.TileMode.CLAMP));
                }
                canvas.drawLine(startX, startY, endX, endY, linePaint);
                linePaint.setShader(null);
            }

            previousTimestamp = currentTimestamp;
            previousValue = currentValue;
            hasPrevious = true;
        }
    }

    private static void drawSeries(
            Canvas canvas,
            Paint linePaint,
            List<Long> timestamps,
            List<Float> values,
            long startTime,
            long duration,
            float chartLeft,
            float chartBottom,
            float chartWidth,
            float chartHeight,
            float minY,
            float yRange,
            float targetLow,
            float targetHigh,
            float veryLowThreshold,
            float veryHighThreshold,
            boolean isMmol,
            int inRangeColor,
            boolean useThresholdColors,
            int thresholdTintColor,
            float lineAlpha,
            float thresholdAlpha) {
        int size = Math.min(timestamps.size(), values.size());
        if (size < 1) {
            return;
        }

        long previousTimestamp = 0L;
        float previousValue = 0f;
        boolean hasPrevious = false;
        // Whether the point currently held in previous* ever got a line drawn to or from it.
        // A reading with no neighbour close enough to join strokes nothing, so it is drawn as
        // a dot instead of vanishing — the same rule ChartLineRun applies on the Compose side,
        // and the nvgCircle the native renderer draws for a one-point run.
        boolean previousConnected = false;

        for (int index = 0; index < size; index++) {
            float currentValue = values.get(index);
            long currentTimestamp = timestamps.get(index);
            boolean valid = Float.isFinite(currentValue) && currentValue > 0.1f;
            if (!valid) {
                if (hasPrevious && !previousConnected) {
                    drawIsolatedPoint(canvas, linePaint, previousTimestamp, previousValue, startTime, duration,
                            chartLeft, chartBottom, chartWidth, chartHeight, minY, yRange, targetLow, targetHigh,
                            veryLowThreshold, veryHighThreshold, isMmol, inRangeColor, useThresholdColors,
                            thresholdTintColor, lineAlpha, thresholdAlpha);
                }
                hasPrevious = false;
                previousConnected = false;
                continue;
            }

            // A hole in the data has to look like a hole. Without this the line was drawn
            // straight across however long the sensor had been silent, so a dropout the
            // dashboard broke on read as an unremarkable stretch of glucose in the
            // notification, the widget and the AOD overlay. Same constant as the Compose
            // chart's ChartGap, so the two cannot drift apart again.
            boolean joins = hasPrevious && joinsAcross(previousTimestamp, currentTimestamp);
            if (hasPrevious && !joins) {
                if (!previousConnected) {
                    drawIsolatedPoint(canvas, linePaint, previousTimestamp, previousValue, startTime, duration,
                            chartLeft, chartBottom, chartWidth, chartHeight, minY, yRange, targetLow, targetHigh,
                            veryLowThreshold, veryHighThreshold, isMmol, inRangeColor, useThresholdColors,
                            thresholdTintColor, lineAlpha, thresholdAlpha);
                }
                hasPrevious = false;
                previousConnected = false;
            }

            if (joins) {
                float startX = chartLeft + ((previousTimestamp - startTime) / (float) duration) * chartWidth;
                float startY = chartBottom - ((previousValue - minY) / yRange) * chartHeight;
                float endX = chartLeft + ((currentTimestamp - startTime) / (float) duration) * chartWidth;
                float endY = chartBottom - ((currentValue - minY) / yRange) * chartHeight;
                if (useThresholdColors) {
                    int startColor = pointColor(previousValue, targetLow, targetHigh, veryLowThreshold,
                            veryHighThreshold, isMmol, inRangeColor, true, thresholdTintColor,
                            lineAlpha, thresholdAlpha);
                    int endColor = pointColor(currentValue, targetLow, targetHigh, veryLowThreshold,
                            veryHighThreshold, isMmol, inRangeColor, true, thresholdTintColor,
                            lineAlpha, thresholdAlpha);
                    if (startColor == endColor) {
                        linePaint.setShader(null);
                        linePaint.setColor(startColor);
                    } else {
                        linePaint.setShader(new LinearGradient(
                                startX,
                                startY,
                                endX,
                                endY,
                                startColor,
                                endColor,
                                Shader.TileMode.CLAMP));
                    }
                } else {
                    linePaint.setShader(null);
                    linePaint.setColor(lineAlpha < 1f ? withAlpha(inRangeColor, lineAlpha) : inRangeColor);
                }
                canvas.drawLine(startX, startY, endX, endY, linePaint);
                linePaint.setShader(null);
            }

            previousTimestamp = currentTimestamp;
            previousValue = currentValue;
            previousConnected = joins;
            hasPrevious = true;
        }

        if (hasPrevious && !previousConnected) {
            drawIsolatedPoint(canvas, linePaint, previousTimestamp, previousValue, startTime, duration,
                    chartLeft, chartBottom, chartWidth, chartHeight, minY, yRange, targetLow, targetHigh,
                    veryLowThreshold, veryHighThreshold, isMmol, inRangeColor, useThresholdColors,
                    thresholdTintColor, lineAlpha, thresholdAlpha);
        }
    }

    /**
     * Whether two consecutive readings are close enough in time for the line to join them.
     *
     * The same rule, from the same constant, that the Compose charts apply through
     * ui.ChartGap — so a hole reads as a hole on every surface, and the two cannot drift.
     */
    static boolean joinsAcross(long previousTimestamp, long currentTimestamp) {
        return (currentTimestamp - previousTimestamp) <= GlucoseChartGap.THRESHOLD_MS;
    }

    /** The colour one reading contributes to the line, matching the series' colouring mode. */
    private static int pointColor(
            float value,
            float targetLow,
            float targetHigh,
            float veryLowThreshold,
            float veryHighThreshold,
            boolean isMmol,
            int inRangeColor,
            boolean useThresholdColors,
            int thresholdTintColor,
            float lineAlpha,
            float thresholdAlpha) {
        if (!useThresholdColors) {
            return lineAlpha < 1f ? withAlpha(inRangeColor, lineAlpha) : inRangeColor;
        }
        if (lineAlpha < 1f || thresholdAlpha < 1f || thresholdTintColor != 0) {
            return resolvePrimaryPointColor(
                    value,
                    targetLow,
                    targetHigh,
                    veryLowThreshold,
                    veryHighThreshold,
                    inRangeColor,
                    thresholdTintColor,
                    isMmol,
                    lineAlpha,
                    thresholdAlpha);
        }
        return resolveThresholdPointColor(
                value,
                targetLow,
                targetHigh,
                veryLowThreshold,
                veryHighThreshold,
                inRangeColor,
                isMmol);
    }

    /**
     * A reading with no neighbour close enough to connect to, drawn as a dot.
     *
     * Adding the gap rule above without this would have swapped one wrong picture for
     * another: a reading either side of a long silence would simply stop being drawn.
     */
    private static void drawIsolatedPoint(
            Canvas canvas,
            Paint linePaint,
            long timestamp,
            float value,
            long startTime,
            long duration,
            float chartLeft,
            float chartBottom,
            float chartWidth,
            float chartHeight,
            float minY,
            float yRange,
            float targetLow,
            float targetHigh,
            float veryLowThreshold,
            float veryHighThreshold,
            boolean isMmol,
            int inRangeColor,
            boolean useThresholdColors,
            int thresholdTintColor,
            float lineAlpha,
            float thresholdAlpha) {
        float x = chartLeft + ((timestamp - startTime) / (float) duration) * chartWidth;
        float y = chartBottom - ((value - minY) / yRange) * chartHeight;
        linePaint.setShader(null);
        linePaint.setColor(pointColor(value, targetLow, targetHigh, veryLowThreshold, veryHighThreshold,
                isMmol, inRangeColor, useThresholdColors, thresholdTintColor, lineAlpha, thresholdAlpha));
        // The line paint strokes; a stroked circle of this radius would draw a ring around
        // nothing. Fill for the dot and hand the paint back as it was found.
        Paint.Style style = linePaint.getStyle();
        linePaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(x, y, linePaint.getStrokeWidth() / 2f, linePaint);
        linePaint.setStyle(style);
    }

    private static void drawNotificationSourceSeries(
            Canvas canvas,
            Paint linePaint,
            List<Long> timestamps,
            List<Float> values,
            long startTime,
            long duration,
            float chartLeft,
            float chartBottom,
            float chartWidth,
            float chartHeight,
            float minY,
            float yRange,
            float targetLow,
            float targetHigh,
            float veryLowThreshold,
            float veryHighThreshold,
            boolean isMmol,
            int inRangeColor,
            boolean useThresholdColors,
            int thresholdTintColor,
            float lineAlpha,
            float thresholdAlpha,
            float strokeScale) {
        float baseStrokeWidth = linePaint.getStrokeWidth();
        linePaint.setStrokeWidth(baseStrokeWidth * strokeScale);
        drawSeries(
                canvas,
                linePaint,
                timestamps,
                values,
                startTime,
                duration,
                chartLeft,
                chartBottom,
                chartWidth,
                chartHeight,
                minY,
                yRange,
                targetLow,
                targetHigh,
                veryLowThreshold,
                veryHighThreshold,
                isMmol,
                inRangeColor,
                useThresholdColors,
                thresholdTintColor,
                lineAlpha,
                thresholdAlpha);
        linePaint.setStrokeWidth(baseStrokeWidth);
    }

    private static long predictionLeadMillis(long durationMs) {
        long lead = (long) (durationMs * 0.18f);
        long minLead = 15L * 60L * 1000L;
        long maxLead = 35L * 60L * 1000L;
        return Math.max(minLead, Math.min(maxLead, lead));
    }

    private static int withAlpha(int color, float alpha) {
        int resolvedAlpha = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (color & 0x00FFFFFF) | (resolvedAlpha << 24);
    }

    private static int defaultSecondaryValueTextColor(Context context) {
        boolean isDark = useLightOnTransparentPalette(context);
        return isDark ? 0xB3FFFFFF : 0xB3000000;
    }

    private static int defaultTertiaryValueTextColor(Context context) {
        boolean isDark = useLightOnTransparentPalette(context);
        return isDark ? 0x80FFFFFF : 0x80000000;
    }

    private static float predictionUncertainty(NotificationPredictionPoint point, boolean isMmol) {
        float confidence = Math.max(0f, Math.min(1f, point.confidence));
        float uncertainty = 1f - confidence;
        return isMmol
                ? 0.18f + (uncertainty * 1.3f)
                : 3.2f + (uncertainty * 24f);
    }

    private static boolean isPrimaryPrediction(NotificationPredictionSeries series, int viewMode) {
        return series.kind == NotificationPredictionSeries.KIND_CALIBRATED
                || (series.kind == NotificationPredictionSeries.KIND_RAW && (viewMode == 1 || viewMode == 3))
                || (series.kind == NotificationPredictionSeries.KIND_AUTO && (viewMode == 0 || viewMode == 2));
    }

    private static int predictionTint(
            NotificationPredictionSeries series,
            int viewMode,
            boolean hasCalibration,
            int lineColor,
            int lineColorSecondary,
            int lineColorTertiary) {
        if (series.kind == NotificationPredictionSeries.KIND_RAW) {
            if (hasCalibration && viewMode == 2) {
                return lineColorTertiary;
            }
            if (hasCalibration || viewMode == 2) {
                return lineColorSecondary;
            }
            return lineColor;
        }
        if (series.kind == NotificationPredictionSeries.KIND_AUTO) {
            if (hasCalibration && viewMode == 3) {
                return lineColorTertiary;
            }
            if (hasCalibration || viewMode == 3) {
                return lineColorSecondary;
            }
            return lineColor;
        }
        return lineColor;
    }

    private static void addSmoothedPredictionPoints(Path path, List<PointF> samples, boolean moveToFirst) {
        if (samples.isEmpty()) {
            return;
        }
        PointF first = samples.get(0);
        if (moveToFirst) {
            path.moveTo(first.x, first.y);
        } else {
            path.lineTo(first.x, first.y);
        }
        if (samples.size() == 1) {
            return;
        }
        if (samples.size() == 2) {
            PointF last = samples.get(1);
            path.lineTo(last.x, last.y);
            return;
        }
        for (int index = 1; index < samples.size() - 1; index++) {
            PointF current = samples.get(index);
            PointF next = samples.get(index + 1);
            float midX = (current.x + next.x) * 0.5f;
            float midY = (current.y + next.y) * 0.5f;
            path.quadTo(current.x, current.y, midX, midY);
        }
        PointF last = samples.get(samples.size() - 1);
        path.lineTo(last.x, last.y);
    }

    private static PointF predictionPointToOffset(
            long timestamp,
            float value,
            long startTime,
            long duration,
            float chartLeft,
            float chartBottom,
            float chartWidth,
            float chartHeight,
            float minY,
            float yRange) {
        float x = chartLeft + ((timestamp - startTime) / (float) duration) * chartWidth;
        float y = chartBottom - ((value - minY) / yRange) * chartHeight;
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return null;
        }
        return new PointF(x, y);
    }

    private static void drawPredictionSeries(
            Canvas canvas,
            Paint baseLinePaint,
            DisplayMetrics dm,
            NotificationPredictionSeries series,
            boolean isMmol,
            int viewMode,
            boolean hasCalibration,
            int lineColor,
            int lineColorSecondary,
            int lineColorTertiary,
            long startTime,
            long chartDuration,
            float chartLeft,
            float chartBottom,
            float chartWidth,
            float chartHeight,
            float minY,
            float yRange) {
        drawPredictionSeries(canvas, baseLinePaint, dm, series, isMmol, viewMode, hasCalibration,
                lineColor, lineColorSecondary, lineColorTertiary, startTime, chartDuration, chartLeft,
                chartBottom, chartWidth, chartHeight, minY, yRange, false);
    }

    /**
     * @param forcePeer when true, render as a subtle peer prediction: no
     *                  uncertainty band, [lineColor] used directly as the tint
     *                  (already desaturated by the caller), thinner stroke.
     */
    private static void drawPredictionSeries(
            Canvas canvas,
            Paint baseLinePaint,
            DisplayMetrics dm,
            NotificationPredictionSeries series,
            boolean isMmol,
            int viewMode,
            boolean hasCalibration,
            int lineColor,
            int lineColorSecondary,
            int lineColorTertiary,
            long startTime,
            long chartDuration,
            float chartLeft,
            float chartBottom,
            float chartWidth,
            float chartHeight,
            float minY,
            float yRange,
            boolean forcePeer) {
        if (series == null || series.points == null || series.points.size() < 2 || yRange <= 0f) {
            return;
        }

        boolean isPrimary = !forcePeer && isPrimaryPrediction(series, viewMode);
        int tint = forcePeer
                ? lineColor
                : predictionTint(series, viewMode, hasCalibration, lineColor, lineColorSecondary, lineColorTertiary);
        ArrayList<PointF> lineSamples = new ArrayList<>(series.points.size());
        ArrayList<PointF> upperSamples = new ArrayList<>(series.points.size());
        ArrayList<PointF> lowerSamples = new ArrayList<>(series.points.size());

        for (NotificationPredictionPoint point : series.points) {
            if (point == null || !Float.isFinite(point.value) || point.value <= 0.1f) {
                continue;
            }
            PointF lineSample = predictionPointToOffset(
                    point.timestamp,
                    point.value,
                    startTime,
                    chartDuration,
                    chartLeft,
                    chartBottom,
                    chartWidth,
                    chartHeight,
                    minY,
                    yRange);
            if (lineSample == null) {
                continue;
            }
            lineSamples.add(lineSample);

            if (isPrimary) {
                float uncertainty = predictionUncertainty(point, isMmol);
                PointF upper = predictionPointToOffset(
                        point.timestamp,
                        point.value + uncertainty,
                        startTime,
                        chartDuration,
                        chartLeft,
                        chartBottom,
                        chartWidth,
                        chartHeight,
                        minY,
                        yRange);
                PointF lower = predictionPointToOffset(
                        point.timestamp,
                        point.value - uncertainty,
                        startTime,
                        chartDuration,
                        chartLeft,
                        chartBottom,
                        chartWidth,
                        chartHeight,
                        minY,
                        yRange);
                if (upper != null && lower != null) {
                    upperSamples.add(upper);
                    lowerSamples.add(0, lower);
                }
            }
        }
        if (lineSamples.size() < 2) {
            return;
        }

        if (isPrimary && upperSamples.size() >= 2 && lowerSamples.size() >= 2) {
            Path bandPath = new Path();
            addSmoothedPredictionPoints(bandPath, upperSamples, true);
            addSmoothedPredictionPoints(bandPath, lowerSamples, false);
            bandPath.close();

            Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bandPaint.setStyle(Paint.Style.FILL);
            bandPaint.setColor(withAlpha(tint, 0.055f));
            canvas.drawPath(bandPath, bandPaint);
        }

        Path predictionPath = new Path();
        addSmoothedPredictionPoints(predictionPath, lineSamples, true);
        float startX = lineSamples.get(0).x;
        float endX = lineSamples.get(lineSamples.size() - 1).x;
        if (Math.abs(endX - startX) <= 1f) {
            endX = startX + 1f;
        }
        float startAlpha = isPrimary ? 0.58f : 0.34f;
        float midAlpha = isPrimary ? 0.38f : 0.24f;

        Paint predictionPaint = new Paint(baseLinePaint);
        predictionPaint.setStyle(Paint.Style.STROKE);
        predictionPaint.setStrokeCap(Paint.Cap.ROUND);
        predictionPaint.setStrokeJoin(Paint.Join.ROUND);
        predictionPaint.setStrokeWidth(isPrimary
                ? baseLinePaint.getStrokeWidth()
                : Math.max(1f, baseLinePaint.getStrokeWidth() * 0.72f));
        predictionPaint.setPathEffect(new DashPathEffect(
                new float[] { 5f * dm.density, 4f * dm.density },
                0f));
        predictionPaint.setShader(new LinearGradient(
                startX,
                0f,
                endX,
                0f,
                new int[] {
                        withAlpha(tint, startAlpha),
                        withAlpha(tint, midAlpha),
                        withAlpha(tint, 0.04f)
                },
                new float[] { 0f, 0.55f, 1f },
                Shader.TileMode.CLAMP));
        canvas.drawPath(predictionPath, predictionPaint);
    }

    @SuppressWarnings("unchecked")
    private static List<NotificationPredictionSeries> resolvePredictionOverlay(
            Context context,
            List<GlucosePoint> data,
            boolean isMmol,
            int viewMode,
            boolean hasCalibration,
            String calibrationSensorId,
            float targetLow,
            float targetHigh) {
        try {
            Class<?> helper = Class.forName("tk.glucodata.NotificationPredictionOverlay");
            Method method = helper.getMethod(
                    "buildPredictionSeries",
                    Context.class,
                    List.class,
                    boolean.class,
                    int.class,
                    boolean.class,
                    String.class,
                    float.class,
                    float.class);
            Object result = method.invoke(
                    null,
                    context,
                    data,
                    isMmol,
                    viewMode,
                    hasCalibration,
                    calibrationSensorId,
                    targetLow,
                    targetHigh);
            if (result instanceof List<?>) {
                return (List<NotificationPredictionSeries>) result;
            }
        } catch (Throwable ignored) {
        }
        return Collections.emptyList();
    }

    public static int getGlucoseColor(Context context, float value, boolean isMmol) {
        // Defaults
        float low = 70.0f;
        float high = 180.0f;

        // Try to get from Natives
        try {
            float nLow = Natives.targetlow();
            float nHigh = Natives.targethigh();
            if (nLow > 0)
                low = nLow;
            if (nHigh > 0)
                high = nHigh;
        } catch (Throwable t) {
            // ignore
        }

        // Standard Text Color (User requested removal of Red/Orange)
        boolean isDark = useLightOnTransparentPalette(context);
        return isDark ? Color.WHITE : Color.BLACK;
    }

    private static boolean useLightOnTransparentPalette(Context context) {
        if (context != null && AOD_OVERLAY_SERVICE_NAME.equals(context.getClass().getName())) {
            return true;
        }
        int uiMode = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public static Bitmap drawArrow(Context context, float rate, boolean isMmol, int color) {
        // Default: 100% scale
        return drawArrow(context, rate, isMmol, color, 1.0f);
    }

    public static Bitmap drawArrow(Context context, float rate, boolean isMmol, int color, float arrowScale) {
        return drawArrow(context, rate, isMmol, color, arrowScale, false);
    }

    public static Bitmap drawArrow(Context context, float rate, boolean isMmol, int color, float arrowScale,
            boolean outline) {
        float density = context.getResources().getDisplayMetrics().density;
        // Arrow size: 20dp * scale (slightly smaller than 24dp for notifications)
        int size = (int) (20 * density * arrowScale);

        // Render at actual size (no upscaling since ImageView is wrap_content)
        int bitmapSize = size;

        Bitmap bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        // TrendIndicator.kt Logic:
        // strokeWidth = size.width * 0.12f
        float drawSize = bitmapSize;
        float strokeWidth = drawSize * 0.1f;
        paint.setStrokeWidth(strokeWidth);

        Paint outlinePaint = null;
        if (outline) {
            outlinePaint = new Paint(paint);
            outlinePaint.setColor(isLightColor(color) ? 0xB0000000 : 0xC8FFFFFF);
            outlinePaint.setStrokeWidth(strokeWidth * 2.2f);
        }

        // Shared rotation math (45 deg per mg/dL/min, vertical at +/-2, flat
        // below the Flat trend state) — must match TrendIndicator.
        float rotation = TrendArrowAngle.rotationDegrees(rate);

        // Base Dimensions from TrendIndicator.kt
        float headSpan = drawSize * 0.5f;
        float headDepth = headSpan / 2;
        float gap = headDepth * 0.3f;

        boolean showDouble = Math.abs(rate) > 2.0f;

        // Total Length Calculation. The arrow rotates around the bitmap
        // center, so a shaft of up to ~95% of the box never clips at any
        // angle. Opt-in setting: the long-shaft variant matches what other
        // apps render for the same rate; the default keeps the compact glyph.
        boolean largeArrow = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
                .getBoolean("notification_large_trend_arrow", false);
        float arrowLenFactor = largeArrow
                ? (showDouble ? 0.62f : 0.82f)
                : (showDouble ? 0.42f : 0.62f);

        // Slight growth with speed, capped so the double-head variant still
        // fits inside the bitmap.
        float speed = Math.abs(rate);
        float totalScale = largeArrow
                ? 1.0f + Math.min(speed * 0.05f, 0.12f)
                : 1.0f + Math.min(speed * 0.04f, 0.18f);

        float arrowLen = drawSize * arrowLenFactor * totalScale;
        float totalVisualLen = arrowLen;
        if (showDouble) {
            totalVisualLen += gap + headDepth;
        }
        if (totalVisualLen > drawSize * 0.95f) {
            float shrink = (drawSize * 0.95f) / totalVisualLen;
            arrowLen *= shrink;
            totalVisualLen = drawSize * 0.95f;
        }

        float cx = bitmapSize / 2.0f;
        float cy = bitmapSize / 2.0f;

        // Rotate Canvas
        canvas.save();
        canvas.rotate(rotation, cx, cy);

        // Centering Logic
        float startX = cx - totalVisualLen / 2.0f;

        // 1. Draw Main Arrow (->)
        float arrowTipX = startX + arrowLen;
        float arrowWingX = arrowTipX - headDepth;

        Path pArrow = new Path();
        // Shaft
        pArrow.moveTo(startX, cy);
        pArrow.lineTo(arrowTipX, cy);
        // Head
        pArrow.moveTo(arrowWingX, cy - headSpan / 2.0f);
        pArrow.lineTo(arrowTipX, cy);
        pArrow.lineTo(arrowWingX, cy + headSpan / 2.0f);

        if (outlinePaint != null) {
            canvas.drawPath(pArrow, outlinePaint);
        }
        canvas.drawPath(pArrow, paint);

        if (showDouble) {
            // 2. Draw Second Head (>)
            float secondTipX = arrowTipX + gap + headDepth;
            float secondWingX = arrowTipX + gap;

            Path pSecond = new Path();
            pSecond.moveTo(secondWingX, cy - headSpan / 2.0f);
            pSecond.lineTo(secondTipX, cy);
            pSecond.lineTo(secondWingX, cy + headSpan / 2.0f);

            if (outlinePaint != null) {
                canvas.drawPath(pSecond, outlinePaint);
            }
            canvas.drawPath(pSecond, paint);
        }

        canvas.restore();
        return bitmap;
    }

    private static boolean isLightColor(int color) {
        double luminance = (0.299 * Color.red(color)) +
                (0.587 * Color.green(color)) +
                (0.114 * Color.blue(color));
        return luminance >= 150.0;
    }

    public static Bitmap drawGlucoseText(Context context, String text, int color) {
        // Default: 100% size, weight 400, App Font
        return drawGlucoseText(context, text, color, 1.0f, 400, false);
    }

    public static Bitmap drawGlucoseText(Context context, String text, int color, float fontSizeScale, int fontWeight) {
        return drawGlucoseText(context, text, color, fontSizeScale, fontWeight, false);
    }

    public static Bitmap drawGlucoseText(Context context, String text, int color, float fontSizeScale, int fontWeight,
            boolean useSystemFont) {
        return drawGlucoseText(
                context,
                text,
                color,
                fontSizeScale,
                fontWeight,
                useSystemFont,
                defaultSecondaryValueTextColor(context),
                defaultTertiaryValueTextColor(context));
    }

    public static Bitmap drawGlucoseText(
            Context context,
            String text,
            int color,
            float fontSizeScale,
            int fontWeight,
            boolean useSystemFont,
            int secondaryColor,
            int tertiaryColor) {
        float density = context.getResources().getDisplayMetrics().density * 2.0f; // 2x Resolution (Safe for Binder)
        float textSize = 22f * density * fontSizeScale;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setHinting(Paint.HINTING_ON);
        paint.setTextSize(textSize);
        paint.setTextAlign(Paint.Align.LEFT);

        if (useSystemFont) {
            String familyName = "google-sans";
            if (fontWeight >= 500) {
                familyName = "google-sans-medium";
            }

            try {
                android.graphics.Typeface tf = android.graphics.Typeface.create(familyName,
                        android.graphics.Typeface.NORMAL);
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    tf = android.graphics.Typeface.create(tf, fontWeight, false);
                }
                paint.setTypeface(tf);
            } catch (Throwable t) {
                paint.setTypeface(android.graphics.Typeface.DEFAULT);
            }
        } else {
            try {
                android.graphics.Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(context,
                        R.font.ibm_plex_sans_var);
                paint.setTypeface(tf);

                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    paint.setFontVariationSettings("'wght' " + fontWeight + ", 'wdth' 100");
                }
            } catch (Throwable t) {
                paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            }
        }

        // Parse up to 3 parts: "Primary / Secondary · Tertiary" (hero card format)
        String part1 = text;
        String part2 = "";
        String part3 = "";

        // First split by " / " for primary vs rest
        if (text.contains(" · ")) {
            String[] mainSplit = text.split(" · ", 2);
            part1 = mainSplit[0];
            String rest = mainSplit.length > 1 ? mainSplit[1] : "";

            // Then split rest by " · " for secondary vs tertiary
            if (rest.contains(" · ")) {
                String[] subSplit = rest.split(" · ", 2);
                part2 = " · " + subSplit[0];
                part3 = " · " + subSplit[1];
            } else {
                part2 = " · " + rest;
            }
        }

        // Measure Part 1 (Primary - full size)
        float width1 = paint.measureText(part1);

        // Part 2 (Secondary - 0.7x size, gray)
        Paint paint2 = new Paint(paint);
        float width2 = 0;
        if (!part2.isEmpty()) {
            paint2.setTextSize(textSize * 0.7f);
            width2 = paint2.measureText(part2);
        }

        // Part 3 (Tertiary - 0.6x size, lighter gray)
        Paint paint3 = new Paint(paint);
        float width3 = 0;
        if (!part3.isEmpty()) {
            paint3.setTextSize(textSize * 0.6f);
            width3 = paint3.measureText(part3);
        }

        int totalWidth = (int) (width1 + width2 + width3 + 1);
        int height = (int) (28 * density * fontSizeScale);

        if (totalWidth <= 0)
            totalWidth = 1;
        if (height <= 0)
            height = 1;

        Bitmap bitmap = Bitmap.createBitmap(totalWidth, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint.FontMetrics fm = paint.getFontMetrics();
        float y = height / 2.0f - (fm.ascent + fm.descent) / 2.0f;

        float currentX = 0;

        // Draw Part 1 (Primary) - Semantic Color
        paint.setColor(color);
        canvas.drawText(part1, currentX, y, paint);
        currentX += width1;

        // Draw Part 2 (Secondary)
        if (!part2.isEmpty()) {
            paint2.setColor(secondaryColor);
            canvas.drawText(part2, currentX, y, paint2);
            currentX += width2;
        }

        // Draw Part 3 (Tertiary)
        if (!part3.isEmpty()) {
            paint3.setColor(tertiaryColor);
            canvas.drawText(part3, currentX, y, paint3);
        }

        return bitmap;
    }

    public static Bitmap drawMultiGlucoseText(
            Context context,
            String primaryText,
            int primaryColor,
            List<ValueItem> peerValues,
            float fontSizeScale,
            int fontWeight,
            boolean useSystemFont) {
        return drawMultiGlucoseText(context, primaryText, primaryColor, peerValues, fontSizeScale, fontWeight,
                useSystemFont, Float.NaN, true, 1.0f);
    }

    /**
     * Renders "primary value [arrow] peer value [arrow] ..." into one bitmap.
     *
     * When peers exist every value carries its own inline trend arrow (the
     * external arrow view must then be hidden by the caller); peer values use
     * the shared subtle identity tint from {@link SensorVisuals} instead of the
     * raw palette color so they read as secondary to the primary value.
     *
     * @param primaryArrowRate rate for the primary value's inline arrow; pass
     *                         NaN to omit inline arrows for the primary value.
     * @param arrowSizeFactor  user arrow size preference multiplier.
     */
    public static Bitmap drawMultiGlucoseText(
            Context context,
            String primaryText,
            int primaryColor,
            List<ValueItem> peerValues,
            float fontSizeScale,
            int fontWeight,
            boolean useSystemFont,
            float primaryArrowRate,
            boolean isMmol,
            float arrowSizeFactor) {
        return drawMultiGlucoseText(
                context,
                primaryText,
                primaryColor,
                defaultSecondaryValueTextColor(context),
                defaultTertiaryValueTextColor(context),
                peerValues,
                fontSizeScale,
                fontWeight,
                useSystemFont,
                primaryArrowRate,
                isMmol,
                arrowSizeFactor);
    }

    public static Bitmap drawMultiGlucoseText(
            Context context,
            String primaryText,
            int primaryColor,
            int secondaryColor,
            int tertiaryColor,
            List<ValueItem> peerValues,
            float fontSizeScale,
            int fontWeight,
            boolean useSystemFont,
            float primaryArrowRate,
            boolean isMmol,
            float arrowSizeFactor) {
        if (peerValues == null || peerValues.isEmpty()) {
            return drawGlucoseText(context, primaryText, primaryColor, fontSizeScale, fontWeight, useSystemFont,
                    secondaryColor, tertiaryColor);
        }

        float density = context.getResources().getDisplayMetrics().density;
        boolean isDark = useLightOnTransparentPalette(context);
        int baseTextColor = isDark ? Color.WHITE : Color.BLACK;
        boolean drawArrows = !Float.isNaN(primaryArrowRate);
        float safeArrowFactor = arrowSizeFactor > 0f ? arrowSizeFactor : 1.0f;

        ArrayList<Bitmap> bitmaps = new ArrayList<>();
        ArrayList<Boolean> isArrow = new ArrayList<>();

        Bitmap primaryBitmap = drawGlucoseText(context, primaryText, primaryColor, fontSizeScale, fontWeight,
                useSystemFont, secondaryColor, tertiaryColor);
        bitmaps.add(primaryBitmap);
        isArrow.add(false);
        if (drawArrows) {
            // drawArrow renders at 20dp * scale; size the arrow relative to the
            // value bitmap (text renders at 2x density internally).
            float arrowScale = (primaryBitmap.getHeight() * 0.56f * safeArrowFactor) / (20f * density);
            bitmaps.add(drawArrow(context, primaryArrowRate, isMmol, primaryColor, arrowScale));
            isArrow.add(true);
        }

        for (ValueItem item : peerValues) {
            if (item == null || item.text == null || item.text.isEmpty()) {
                continue;
            }
            // ValueItem.color carries the identity color; blend it here where the
            // light/dark base is known so all surfaces share the same treatment.
            int subtleColor = SensorVisuals.blendArgb(baseTextColor, item.color, SensorVisuals.PEER_TEXT_BLEND);
            Bitmap peerBitmap = drawGlucoseText(
                    context,
                    item.text,
                    subtleColor,
                    fontSizeScale * 0.72f,
                    fontWeight,
                    useSystemFont);
            bitmaps.add(peerBitmap);
            isArrow.add(false);
            if (drawArrows && !Float.isNaN(item.rate)) {
                float arrowScale = (peerBitmap.getHeight() * 0.56f * safeArrowFactor) / (20f * density);
                bitmaps.add(drawArrow(context, item.rate, isMmol, subtleColor, arrowScale));
                isArrow.add(true);
            }
        }
        if (bitmaps.size() <= 1) {
            return bitmaps.get(0);
        }

        float layoutDensity = density * 2.0f;
        int gap = Math.max(1, (int) (8f * layoutDensity * fontSizeScale));
        int arrowGap = Math.max(1, (int) (2.5f * layoutDensity * fontSizeScale));
        int totalWidth = 0;
        int maxHeight = 1;
        for (int i = 0; i < bitmaps.size(); i++) {
            Bitmap bitmap = bitmaps.get(i);
            totalWidth += bitmap.getWidth();
            if (i > 0) {
                totalWidth += isArrow.get(i) ? arrowGap : gap;
            }
            maxHeight = Math.max(maxHeight, bitmap.getHeight());
        }
        Bitmap output = Bitmap.createBitmap(Math.max(1, totalWidth), maxHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        int x = 0;
        for (int i = 0; i < bitmaps.size(); i++) {
            Bitmap bitmap = bitmaps.get(i);
            if (i > 0) {
                x += isArrow.get(i) ? arrowGap : gap;
            }
            float y = (maxHeight - bitmap.getHeight()) / 2.0f;
            canvas.drawBitmap(bitmap, x, y, null);
            x += bitmap.getWidth();
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        return output;
    }

    /**
     * Render status text (e.g., "Connecting to sensor") as a bitmap with custom
     * font.
     */
    public static Bitmap drawStatusText(Context context, String text) {
        float density = context.getResources().getDisplayMetrics().density;
        float textSize = 11f * density;

        // Theme detection
        boolean isDark = useLightOnTransparentPalette(context);
        int textColor = isDark ? 0xAAFFFFFF : 0xAA000000; // Info-style gray

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(textSize);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(textColor);

        // Font: IBM Plex Sans Variable
        try {
            android.graphics.Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(context,
                    R.font.ibm_plex_sans_var);
            paint.setTypeface(tf);

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                paint.setFontVariationSettings("'wght' 400, 'wdth' 100");
            }
        } catch (Throwable t) {
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        float textWidth = paint.measureText(text);
        int width = (int) (textWidth + 1);
        int height = (int) (14 * density);

        if (width <= 0)
            width = 1;
        if (height <= 0)
            height = 1;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint.FontMetrics fm = paint.getFontMetrics();
        float y = height / 2.0f - (fm.ascent + fm.descent) / 2.0f;

        canvas.drawText(text, 0, y, paint);

        return bitmap;
    }

    public static Bitmap drawChart(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode) {
        // Default: show target range
        return drawChart(context, data, widthHint, heightHint, isMmol, viewMode, true);
    }

    public static Bitmap drawChart(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode, boolean showTargetRange) {
        boolean compactMode = (heightHint > 0 && heightHint < 150);
        return drawChartInternal(context, data, widthHint, heightHint, isMmol, viewMode, showTargetRange, false,
                compactMode, null, DEFAULT_CHART_DURATION_MS, false);
    }

    public static Bitmap drawChart(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode, boolean showTargetRange, boolean hasCalibration) {
        boolean compactMode = (heightHint > 0 && heightHint < 150);
        return drawChartInternal(context, data, widthHint, heightHint, isMmol, viewMode, showTargetRange,
                hasCalibration, compactMode, null, DEFAULT_CHART_DURATION_MS, false);
    }

    public static Bitmap drawChart(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode, boolean showTargetRange, boolean hasCalibration,
            String calibrationSensorId) {
        boolean compactMode = (heightHint > 0 && heightHint < 150);
        return drawChartInternal(context, data, widthHint, heightHint, isMmol, viewMode, showTargetRange,
                hasCalibration, compactMode, calibrationSensorId, DEFAULT_CHART_DURATION_MS, false);
    }

    public static Bitmap drawChart(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode, boolean showTargetRange, boolean hasCalibration, boolean compactMode) {
        return drawChartInternal(context, data, widthHint, heightHint, isMmol, viewMode, showTargetRange,
                hasCalibration, compactMode, null, DEFAULT_CHART_DURATION_MS, false);
    }

    public static Bitmap drawChart(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode, boolean showTargetRange, boolean hasCalibration, boolean compactMode,
            String calibrationSensorId) {
        return drawChartInternal(context, data, widthHint, heightHint, isMmol, viewMode, showTargetRange,
                hasCalibration, compactMode, calibrationSensorId, DEFAULT_CHART_DURATION_MS, false);
    }

    public static Bitmap drawChart(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode, boolean showTargetRange, boolean hasCalibration, boolean compactMode,
            String calibrationSensorId, long durationMs) {
        return drawChartInternal(context, data, widthHint, heightHint, isMmol, viewMode, showTargetRange,
                hasCalibration, compactMode, calibrationSensorId, durationMs, false);
    }

    public static Bitmap drawChartWithPrediction(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode, boolean showTargetRange, boolean hasCalibration,
            String calibrationSensorId) {
        boolean compactMode = (heightHint > 0 && heightHint < 150);
        return drawChartInternal(context, data, widthHint, heightHint, isMmol, viewMode, showTargetRange,
                hasCalibration, compactMode, calibrationSensorId, DEFAULT_CHART_DURATION_MS, true);
    }

    public static Bitmap drawChartWithPrediction(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode, boolean showTargetRange, boolean hasCalibration, boolean compactMode,
            String calibrationSensorId) {
        return drawChartInternal(context, data, widthHint, heightHint, isMmol, viewMode, showTargetRange,
                hasCalibration, compactMode, calibrationSensorId, DEFAULT_CHART_DURATION_MS, true);
    }

    public static Bitmap drawChartWithPrediction(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode, boolean showTargetRange, boolean hasCalibration, boolean compactMode,
            String calibrationSensorId, List<PeerSeries> peerSeries) {
        return drawChartInternal(context, data, widthHint, heightHint, isMmol, viewMode, showTargetRange,
                hasCalibration, compactMode, calibrationSensorId, DEFAULT_CHART_DURATION_MS, true, peerSeries);
    }

    private static Bitmap drawChartInternal(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode, boolean showTargetRange, boolean hasCalibration, boolean compactMode,
            String calibrationSensorId, long durationMs, boolean showPredictionOverlay) {
        return drawChartInternal(context, data, widthHint, heightHint, isMmol, viewMode, showTargetRange,
                hasCalibration, compactMode, calibrationSensorId, durationMs, showPredictionOverlay,
                Collections.emptyList());
    }

    private static Bitmap drawChartInternal(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode, boolean showTargetRange, boolean hasCalibration, boolean compactMode,
            String calibrationSensorId, long durationMs, boolean showPredictionOverlay, List<PeerSeries> peerSeries) {
        sTrafficLineColors = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
                .getBoolean("glucose_chart_range_colors_enabled", false);
        sTrafficLineDark = useLightOnTransparentPalette(context);
        // Get display metrics for proper sizing
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int width = (widthHint > 0) ? widthHint : dm.widthPixels;
        int height = (heightHint > 0) ? heightHint : (int) (256 * dm.density);

        // Theme detection
        boolean isDark = useLightOnTransparentPalette(context);

        boolean hasPeerSeries = peerSeries != null && !peerSeries.isEmpty();
        int lineColor = dashboardPrimaryLineColor(isDark, calibrationSensorId, hasPeerSeries);
        int primaryIdentityColor = dashboardPrimaryIdentityColor(calibrationSensorId, hasPeerSeries);
        int lineColorSecondary = isDark ? 0xFF9E9E9E : 0xFF757575;
        // Tertiary: Lighter Gray (match ComposeHost tertiaryColor: alpha 0.45 of
        // content)
        // Light: 0x73000000 (45%), Dark: 0x73FFFFFF (45%)
        int lineColorTertiary = isDark ? 0x73FFFFFF : 0x73000000;

        int gridColor = isDark ? 0x33FFFFFF : 0x22000000;
        int textColor = isDark ? 0x88FFFFFF : 0x88000000; // Lighter shade (53% opacity)

        // Create bitmap and canvas
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Keep notification sparkline visually inside the RemoteViews crop.
        float leftMargin = compactMode ? 1.5f * dm.density : 0f;
        float bottomMargin = compactMode ? 2.5f * dm.density : 0f;
        float topMargin = compactMode ? 1.5f * dm.density : 0f;
        float rightMargin = compactMode ? 2.5f * dm.density : 0f;

        float chartLeft = leftMargin;
        float chartRight = width - rightMargin;
        float chartTop = topMargin;
        float chartBottom = height - bottomMargin;
        float chartWidth = chartRight - chartLeft;
        float chartHeight = chartBottom - chartTop;

        // Time range: notification defaults to 3 hours; widgets can request longer.
        long now = System.currentTimeMillis();
        long duration = durationMs > 0L ? durationMs : DEFAULT_CHART_DURATION_MS;
        long startTime = now - duration;

        int smoothingMinutes = DataSmoothing.graphSmoothingMinutes(context);
        List<GlucosePoint> renderSource = DataSmoothing.smoothNativePoints(
                data,
                smoothingMinutes,
                smoothingMinutes > 0 && DataSmoothing.collapseChunks(context)
        );

        // Filter data to visible range
        List<GlucosePoint> visiblePoints = new ArrayList<>();
        List<GlucosePoint> visibleRenderPoints = new ArrayList<>();
        if (data != null) {
            for (GlucosePoint p : data) {
                if (p.timestamp >= startTime) {
                    visiblePoints.add(p);
                }
            }
        }
        if (renderSource != null) {
            for (GlucosePoint p : renderSource) {
                if (p.timestamp >= startTime) {
                    visibleRenderPoints.add(p);
                }
            }
        }
        List<PeerSeries> visiblePeerSeries = new ArrayList<>();
        if (peerSeries != null) {
            for (PeerSeries series : peerSeries) {
                if (series == null || series.points == null || series.points.size() < 2) {
                    continue;
                }
                List<GlucosePoint> renderPeer = DataSmoothing.smoothNativePoints(
                        series.points,
                        smoothingMinutes,
                        smoothingMinutes > 0 && DataSmoothing.collapseChunks(context));
                ArrayList<GlucosePoint> visiblePeerPoints = new ArrayList<>();
                if (renderPeer != null) {
                    for (GlucosePoint p : renderPeer) {
                        if (p.timestamp >= startTime) {
                            visiblePeerPoints.add(p);
                        }
                    }
                }
                if (visiblePeerPoints.size() >= 2) {
                    visiblePeerSeries.add(new PeerSeries(
                            series.sensorId,
                            series.viewMode,
                            series.color,
                            visiblePeerPoints));
                }
            }
        }

        boolean hideInitialWhenCalibrated = hasCalibration &&
                CalibrationAccess.shouldHideInitialWhenCalibrated();
        boolean isRawModeForCal = (viewMode == 1 || viewMode == 3);
        // Determine which lines to show
        boolean hideRawSource = hideInitialWhenCalibrated && isRawModeForCal;
        boolean hideAutoSource = hideInitialWhenCalibrated && !isRawModeForCal;
        boolean showAuto = !hideAutoSource && (viewMode == 0 || viewMode == 2 || viewMode == 3);
        boolean showRaw = !hideRawSource && (viewMode == 1 || viewMode == 2 || viewMode == 3);

        // Determine line colors based on ViewMode and Calibration
        int autoColor = lineColor; // Default Primary
        int rawColor = lineColor; // Default Primary

        if (viewMode == 2) { // Auto+Raw
            if (hasCalibration) {
                autoColor = lineColorSecondary;
                rawColor = lineColorTertiary;
            } else {
                autoColor = lineColor;
                rawColor = lineColorSecondary;
            }
        } else if (viewMode == 3) { // Raw+Auto
            if (hasCalibration) {
                rawColor = lineColorSecondary;
                autoColor = lineColorTertiary;
            } else {
                rawColor = lineColor;
                autoColor = lineColorSecondary;
            }
        } else {
            // Single Modes (0 or 1)
            if (hasCalibration) {
                // If calibrated, the base line becomes Secondary (Calibrated line is Primary)
                // Whether it's Auto or Raw mode, the uncalibrated line is demoted
                autoColor = lineColorSecondary;
                rawColor = lineColorSecondary;
            } else {
            }
        }

        boolean thresholdColorAuto = !hasCalibration && (viewMode == 0 || viewMode == 2);
        boolean thresholdColorRaw = !hasCalibration && (viewMode == 1 || viewMode == 3);
        boolean thresholdColorCalibrated = hasCalibration;

        // Target Range (Get from Natives or Defaults)
        float targetLow = GlucoseRangeColors.defaultLow(isMmol);
        float targetHigh = GlucoseRangeColors.defaultHigh(isMmol);
        float veryLowThreshold = GlucoseRangeColors.defaultVeryLow(isMmol);
        float veryHighThreshold = GlucoseRangeColors.defaultVeryHigh(isMmol);
        try {
            float nLow = Natives.targetlow();
            float nHigh = Natives.targethigh();
            float nVeryLow = Natives.alarmverylow();
            float nVeryHigh = Natives.alarmveryhigh();
            if (nLow > 0)
                targetLow = nLow;
            if (nHigh > 0)
                targetHigh = nHigh;
            if (nVeryLow > 0)
                veryLowThreshold = nVeryLow;
            if (nVeryHigh > 0)
                veryHighThreshold = nVeryHigh;
        } catch (Throwable t) {
        }

        List<NotificationPredictionSeries> predictionSeries = showPredictionOverlay
                ? resolvePredictionOverlay(
                        context,
                        data,
                        isMmol,
                        viewMode,
                        hasCalibration,
                        calibrationSensorId,
                        targetLow,
                        targetHigh)
                : Collections.emptyList();
        // Peer prediction overlays — one per peer series, so the simulation
        // extends every drawn line (matching the dashboard), not just the primary.
        java.util.List<List<NotificationPredictionSeries>> peerPredictionSeries = new ArrayList<>();
        if (showPredictionOverlay && peerSeries != null) {
            for (PeerSeries ps : peerSeries) {
                if (ps == null || ps.points == null || ps.points.size() < 2) {
                    peerPredictionSeries.add(Collections.emptyList());
                    continue;
                }
                peerPredictionSeries.add(resolvePredictionOverlay(
                        context, ps.points, isMmol, ps.viewMode, false, ps.sensorId, targetLow, targetHigh));
            }
        }

        long chartEndTime = now;
        long predictionEnd = 0L;
        for (NotificationPredictionSeries series : predictionSeries) {
            if (series == null || series.points == null || series.points.isEmpty()) {
                continue;
            }
            NotificationPredictionPoint last = series.points.get(series.points.size() - 1);
            if (last != null) {
                predictionEnd = Math.max(predictionEnd, last.timestamp);
            }
        }
        for (List<NotificationPredictionSeries> peerList : peerPredictionSeries) {
            for (NotificationPredictionSeries series : peerList) {
                if (series == null || series.points == null || series.points.isEmpty()) {
                    continue;
                }
                NotificationPredictionPoint last = series.points.get(series.points.size() - 1);
                if (last != null) {
                    predictionEnd = Math.max(predictionEnd, last.timestamp);
                }
            }
        }
        if (predictionEnd > now) {
            chartEndTime = Math.min(predictionEnd, now + predictionLeadMillis(duration));
        }
        long chartDuration = duration + Math.max(0L, chartEndTime - now);
        List<NotificationPredictionSeries> visiblePredictionSeries = new ArrayList<>();
        for (NotificationPredictionSeries series : predictionSeries) {
            if (series == null || series.points == null) {
                continue;
            }
            ArrayList<NotificationPredictionPoint> validSeriesPoints = new ArrayList<>();
            for (NotificationPredictionPoint point : series.points) {
                if (point != null && point.timestamp > 0L) {
                    validSeriesPoints.add(point);
                }
            }
            if (validSeriesPoints.size() < 2) {
                continue;
            }
            int firstVisibleIndex = -1;
            int lastVisibleIndex = -1;
            for (int index = 0; index < validSeriesPoints.size(); index++) {
                long timestamp = validSeriesPoints.get(index).timestamp;
                if (firstVisibleIndex < 0 && timestamp >= startTime) {
                    firstVisibleIndex = index;
                }
                if (timestamp <= chartEndTime) {
                    lastVisibleIndex = index;
                }
            }
            if (firstVisibleIndex < 0 || lastVisibleIndex < 0) {
                continue;
            }
            int predictionStartIndex = Math.max(0, firstVisibleIndex - 1);
            int predictionEndIndex = Math.min(validSeriesPoints.size() - 1, lastVisibleIndex + 1);
            ArrayList<NotificationPredictionPoint> visibleSeriesPoints = new ArrayList<>();
            for (int index = predictionStartIndex; index <= predictionEndIndex; index++) {
                visibleSeriesPoints.add(validSeriesPoints.get(index));
            }
            if (visibleSeriesPoints.size() >= 2) {
                visiblePredictionSeries.add(new NotificationPredictionSeries(series.kind, visibleSeriesPoints));
            }
        }

        // Calculate Y range
        // Standard Strategy: Expand to fit Data, BUT ensure we cover Target Range +
        // Buffer to ensure target lines don't touch edges (User Request: "small gap")
        // approx 10mg/dl or 0.6mmol gap
        float bufferVal = isMmol ? 0.6f : 10.0f;

        float minY = targetLow - bufferVal;
        float maxY = targetHigh + bufferVal;

        for (GlucosePoint p : visiblePoints) {
            if (showAuto && p.value > 0) {
                minY = Math.min(minY, p.value);
                maxY = Math.max(maxY, p.value);
            }
            if (showRaw && p.rawValue > 0) {
                minY = Math.min(minY, p.rawValue);
                maxY = Math.max(maxY, p.rawValue);
            }
        }
        for (PeerSeries series : visiblePeerSeries) {
            boolean peerAuto = showsAuto(series.viewMode);
            boolean peerRaw = showsRaw(series.viewMode);
            for (GlucosePoint p : series.points) {
                if (peerAuto && p.value > 0) {
                    minY = Math.min(minY, p.value);
                    maxY = Math.max(maxY, p.value);
                }
                if (peerRaw && p.rawValue > 0) {
                    minY = Math.min(minY, p.rawValue);
                    maxY = Math.max(maxY, p.rawValue);
                }
            }
        }
        for (NotificationPredictionSeries series : visiblePredictionSeries) {
            boolean primaryPrediction = isPrimaryPrediction(series, viewMode);
            for (NotificationPredictionPoint point : series.points) {
                if (point.value > 0) {
                    float uncertainty = primaryPrediction ? predictionUncertainty(point, isMmol) : 0f;
                    minY = Math.min(minY, point.value - uncertainty);
                    maxY = Math.max(maxY, point.value + uncertainty);
                }
            }
        }
        if (hasCalibration) {
            for (GlucosePoint p : visiblePoints) {
                float baseVal = isRawModeForCal ? p.rawValue : p.value;
                if (baseVal > 0) {
                    float calVal = CalibrationAccess.getCalibratedValue(
                            baseVal,
                            p.timestamp,
                            isRawModeForCal,
                            false,
                            calibrationSensorId);
                    if (calVal > 0.1f) {
                        minY = Math.min(minY, calVal);
                        maxY = Math.max(maxY, calVal);
                    }
                }
            }
        }

        // Add robust extra cosmetic buffer (10%) so lines don't touch edges exact
        // SKIP buffer in ALL modes to maximize visible signal (request: "fill all
        // available space")
        float range = maxY - minY;
        if (range == 0)
            range = 1; // avoid div by zero

        // No extra buffer padding
        // minY -= range * 0.05f;
        // maxY += range * 0.05f;

        // Paints
        Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(gridColor); // 0x22000000 or 0x33FFFFFF
        gridPaint.setStrokeWidth(1);
        gridPaint.setStyle(Paint.Style.STROKE);

        // Target Range Shade Paint
        // Very subtle shade (5% opacity)
        // If Dark Mode: 0x0D4CAF50 (Green ~5%)
        // If Light Mode: 0x0D4CAF50
        Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        targetPaint.setStyle(Paint.Style.FILL);
        targetPaint.setColor(0x0D4CAF50); // ~5% Green

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(textColor);
        textPaint.setTextSize(12 * dm.density);
        try {
            android.graphics.Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(context,
                    R.font.ibm_plex_sans_var);
            textPaint.setTypeface(tf);
        } catch (Throwable t) {
            // ignore
        }

        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        float strokeWidth = (compactMode ? 1.5f : 2.0f) * dm.density;
        linePaint.setStrokeWidth(strokeWidth);

        float yRange = maxY - minY;

        // DRAW TARGET RANGE SHADING (if enabled)
        if (showTargetRange && yRange > 0) {
            float yHigh = chartBottom - ((targetHigh - minY) / yRange) * chartHeight;
            float yLow = chartBottom - ((targetLow - minY) / yRange) * chartHeight;

            // Canvas draws rect Top to Bottom
            // yHigh is visually HIGHER (smaller Y coordinate)
            // yLow is visually LOWER (larger Y coordinate)
            if (yHigh < chartTop)
                yHigh = chartTop;
            if (yLow > chartBottom)
                yLow = chartBottom;

            if (yLow > yHigh) {
                canvas.drawRect(chartLeft, yHigh, chartRight, yLow, targetPaint);
            }
        }

        // Check if collapsed (from Notify.java: passed 100 for collapsed, 180 for
        // expanded)
        boolean isCollapsed = compactMode || (height < 150);

        // Draw grid lines (3 horizontal)
        // yRange already calculated above
        float textHeight = textPaint.getTextSize();
        // Generous offset (e.g. 8dp)
        float gridLabelOffset = 4 * dm.density;

        // Store Y-label bounds for intersection testing
        List<android.graphics.RectF> yLabelRects = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            float yVal = minY + yRange * (i / 4.0f);
            float y = chartBottom - ((yVal - minY) / yRange) * chartHeight;

            // Y label - Only if NOT collapsed
            float lineStart = chartLeft; // Default start

            if (!isCollapsed) {
                String label = String.valueOf(Math.round(yVal));
                textPaint.setTextAlign(Paint.Align.LEFT);
                float labelWidth = textPaint.measureText(label);

                // Text position
                float textX = chartLeft + 4 * dm.density;
                canvas.drawText(label, textX, y + textHeight / 3, textPaint);

                // Capture bounds (with padding) for line breaking
                float pad = 4 * dm.density;
                android.graphics.RectF rect = new android.graphics.RectF(
                        textX - pad,
                        y - textHeight * 0.8f - pad,
                        textX + labelWidth + pad,
                        y + textHeight * 0.4f + pad);
                yLabelRects.add(rect);

                // Line starts after label + offset
                lineStart = textX + labelWidth + gridLabelOffset;
            }

            if (lineStart < chartRight) {
                canvas.drawLine(lineStart, y, chartRight, y, gridPaint);
            }
        }

        // Draw X-axis hour labels and vertical grid lines
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(startTime);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.HOUR_OF_DAY, 1);

        textPaint.setTextAlign(Paint.Align.CENTER);
        while (cal.getTimeInMillis() < chartEndTime) {
            float x = chartLeft + ((cal.getTimeInMillis() - startTime) / (float) chartDuration) * chartWidth;

            // X label inside chart area (bottom)
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            String label = String.valueOf(hour);

            // Label Y position
            float labelY = chartBottom - 3 * dm.density;

            float lineEnd = chartBottom; // Default end

            if (!isCollapsed) {
                canvas.drawText(label, x, labelY, textPaint);
                // Line ends before label (above it)
                lineEnd = labelY - textHeight - gridLabelOffset;
            }

            if (lineEnd > chartTop) {
                // Check for intersections with Y-labels (only if we have labels)
                List<float[]> gaps = new ArrayList<>();
                if (!yLabelRects.isEmpty()) {
                    for (android.graphics.RectF r : yLabelRects) {
                        if (x >= r.left && x <= r.right) {
                            gaps.add(new float[] { r.top, r.bottom });
                        }
                    }
                }

                // Draw line in segments skipping gaps
                if (gaps.isEmpty()) {
                    canvas.drawLine(x, chartTop, x, lineEnd, gridPaint);
                } else {
                    // Sort gaps by top Y
                    java.util.Collections.sort(gaps, new java.util.Comparator<float[]>() {
                        @Override
                        public int compare(float[] o1, float[] o2) {
                            return Float.compare(o1[0], o2[0]);
                        }
                    });

                    float currentY = chartTop;
                    for (float[] gap : gaps) {
                        // Draw segment before gap
                        if (gap[0] > currentY) {
                            // ensure we don't overshoot bottom
                            float segEnd = Math.min(gap[0], lineEnd);
                            if (segEnd > currentY) {
                                canvas.drawLine(x, currentY, x, segEnd, gridPaint);
                            }
                        }
                        // Skip gap
                        currentY = Math.max(currentY, gap[1]);
                    }
                    // Draw final segment
                    if (currentY < lineEnd) {
                        canvas.drawLine(x, currentY, x, lineEnd, gridPaint);
                    }
                }
            }

            cal.add(Calendar.HOUR_OF_DAY, 1);
        }

        if (!visiblePeerSeries.isEmpty()) {
            float primaryStrokeWidth = linePaint.getStrokeWidth();
            linePaint.setStrokeWidth(primaryStrokeWidth * 0.78f);
            for (PeerSeries series : visiblePeerSeries) {
                boolean peerAuto = showsAuto(series.viewMode);
                boolean peerRaw = showsRaw(series.viewMode);
                if (peerRaw) {
                    ArrayList<Long> timestamps = new ArrayList<>(series.points.size());
                    ArrayList<Float> values = new ArrayList<>(series.points.size());
                    for (GlucosePoint p : series.points) {
                        timestamps.add(p.timestamp);
                        values.add(p.rawValue);
                    }
                    drawSubtlePeerSeries(
                            canvas,
                            linePaint,
                            timestamps,
                            values,
                            startTime,
                            chartDuration,
                            chartLeft,
                            chartBottom,
                            chartWidth,
                            chartHeight,
                            minY,
                            yRange,
                            targetLow,
                            targetHigh,
                            veryLowThreshold,
                            veryHighThreshold,
                            isMmol,
                            series.color,
                            isDark,
                            peerPassAlpha(peerRaw, peerAuto, true));
                }
                if (peerAuto) {
                    ArrayList<Long> timestamps = new ArrayList<>(series.points.size());
                    ArrayList<Float> values = new ArrayList<>(series.points.size());
                    for (GlucosePoint p : series.points) {
                        timestamps.add(p.timestamp);
                        values.add(p.value);
                    }
                    drawSubtlePeerSeries(
                            canvas,
                            linePaint,
                            timestamps,
                            values,
                            startTime,
                            chartDuration,
                            chartLeft,
                            chartBottom,
                            chartWidth,
                            chartHeight,
                            minY,
                            yRange,
                            targetLow,
                            targetHigh,
                            veryLowThreshold,
                            veryHighThreshold,
                            isMmol,
                            series.color,
                            isDark,
                            peerPassAlpha(peerRaw, peerAuto, false));
                }
            }
            linePaint.setStrokeWidth(primaryStrokeWidth);
        }

        ArrayList<Long> autoTimestamps = null;
        ArrayList<Float> autoValues = null;
        ArrayList<Long> rawTimestamps = null;
        ArrayList<Float> rawValues = null;
        boolean drawAutoLine = showAuto && !visibleRenderPoints.isEmpty();
        boolean drawRawLine = showRaw && !visibleRenderPoints.isEmpty();
        boolean autoIsPrimary = autoColor == lineColor;
        boolean rawIsPrimary = rawColor == lineColor;

        if (drawAutoLine) {
            autoTimestamps = new ArrayList<>(visibleRenderPoints.size());
            autoValues = new ArrayList<>(visibleRenderPoints.size());
            for (GlucosePoint p : visibleRenderPoints) {
                autoTimestamps.add(p.timestamp);
                autoValues.add(p.value);
            }
        }
        if (drawRawLine) {
            rawTimestamps = new ArrayList<>(visibleRenderPoints.size());
            rawValues = new ArrayList<>(visibleRenderPoints.size());
            for (GlucosePoint p : visibleRenderPoints) {
                rawTimestamps.add(p.timestamp);
                rawValues.add(p.rawValue);
            }
        }

        // Draw companion auto/raw lines first so the primary line stays visually dominant.
        if (drawAutoLine && !autoIsPrimary) {
            drawNotificationSourceSeries(
                    canvas,
                    linePaint,
                    autoTimestamps,
                    autoValues,
                    startTime,
                    chartDuration,
                    chartLeft,
                    chartBottom,
                    chartWidth,
                    chartHeight,
                    minY,
                    yRange,
                    targetLow,
                    targetHigh,
                    veryLowThreshold,
                    veryHighThreshold,
                    isMmol,
                    autoColor,
                    thresholdColorAuto,
                    autoColor == lineColor ? primaryIdentityColor : 0,
                    notificationLineAlpha(autoColor, lineColor, lineColorSecondary),
                    notificationThresholdAlpha(autoColor, lineColor, lineColorSecondary),
                    notificationStrokeScale(autoColor, lineColor, lineColorSecondary));
        }
        if (drawRawLine && !rawIsPrimary) {
            drawNotificationSourceSeries(
                    canvas,
                    linePaint,
                    rawTimestamps,
                    rawValues,
                    startTime,
                    chartDuration,
                    chartLeft,
                    chartBottom,
                    chartWidth,
                    chartHeight,
                    minY,
                    yRange,
                    targetLow,
                    targetHigh,
                    veryLowThreshold,
                    veryHighThreshold,
                    isMmol,
                    rawColor,
                    thresholdColorRaw,
                    rawColor == lineColor ? primaryIdentityColor : 0,
                    notificationLineAlpha(rawColor, lineColor, lineColorSecondary),
                    notificationThresholdAlpha(rawColor, lineColor, lineColorSecondary),
                    notificationStrokeScale(rawColor, lineColor, lineColorSecondary));
        }

        if (drawAutoLine && autoIsPrimary) {
            drawNotificationSourceSeries(
                    canvas,
                    linePaint,
                    autoTimestamps,
                    autoValues,
                    startTime,
                    chartDuration,
                    chartLeft,
                    chartBottom,
                    chartWidth,
                    chartHeight,
                    minY,
                    yRange,
                    targetLow,
                    targetHigh,
                    veryLowThreshold,
                    veryHighThreshold,
                    isMmol,
                    autoColor,
                    thresholdColorAuto,
                    primaryIdentityColor,
                    notificationLineAlpha(autoColor, lineColor, lineColorSecondary),
                    notificationThresholdAlpha(autoColor, lineColor, lineColorSecondary),
                    notificationStrokeScale(autoColor, lineColor, lineColorSecondary));
        }
        if (drawRawLine && rawIsPrimary) {
            drawNotificationSourceSeries(
                    canvas,
                    linePaint,
                    rawTimestamps,
                    rawValues,
                    startTime,
                    chartDuration,
                    chartLeft,
                    chartBottom,
                    chartWidth,
                    chartHeight,
                    minY,
                    yRange,
                    targetLow,
                    targetHigh,
                    veryLowThreshold,
                    veryHighThreshold,
                    isMmol,
                    rawColor,
                    thresholdColorRaw,
                    primaryIdentityColor,
                    notificationLineAlpha(rawColor, lineColor, lineColorSecondary),
                    notificationThresholdAlpha(rawColor, lineColor, lineColorSecondary),
                    notificationStrokeScale(rawColor, lineColor, lineColorSecondary));
        }

        // Draw Calibrated Line (Primary)
        if (hasCalibration && !visibleRenderPoints.isEmpty()) {
            boolean isRawModeforCal = (viewMode == 1 || viewMode == 3);
            ArrayList<Long> timestamps = new ArrayList<>(visibleRenderPoints.size());
            ArrayList<Float> values = new ArrayList<>(visibleRenderPoints.size());

            for (GlucosePoint renderPoint : visibleRenderPoints) {
                float baseVal = isRawModeforCal ? renderPoint.rawValue : renderPoint.value;
                float val = baseVal > 0
                        ? CalibrationAccess.getCalibratedValue(
                                baseVal,
                                renderPoint.timestamp,
                                isRawModeforCal,
                                false,
                                calibrationSensorId)
                        : 0f;
                timestamps.add(renderPoint.timestamp);
                values.add(val);
            }

            drawNotificationSourceSeries(
                    canvas,
                    linePaint,
                    timestamps,
                    values,
                    startTime,
                    chartDuration,
                    chartLeft,
                    chartBottom,
                    chartWidth,
                    chartHeight,
                    minY,
                    yRange,
                    targetLow,
                    targetHigh,
                    veryLowThreshold,
                    veryHighThreshold,
                    isMmol,
                    lineColor,
                    thresholdColorCalibrated,
                    primaryIdentityColor,
                    DASHBOARD_PRIMARY_LINE_ALPHA,
                    DASHBOARD_PRIMARY_THRESHOLD_ALPHA,
                    DASHBOARD_PRIMARY_STROKE_SCALE);
        }

        for (NotificationPredictionSeries series : visiblePredictionSeries) {
            drawPredictionSeries(
                    canvas,
                    linePaint,
                    dm,
                    series,
                    isMmol,
                    viewMode,
                    hasCalibration,
                    lineColor,
                    lineColorSecondary,
                    lineColorTertiary,
                    startTime,
                    chartDuration,
                    chartLeft,
                    chartBottom,
                    chartWidth,
                    chartHeight,
                    minY,
                    yRange);
        }

        // Peer prediction lines (subtle, desaturated peer color, no band).
        for (int peerIdx = 0; peerIdx < peerPredictionSeries.size(); peerIdx++) {
            if (peerSeries == null || peerIdx >= peerSeries.size()) {
                break;
            }
            int peerColor = withAlpha(
                    dashboardPeerLineBase(peerSeries.get(peerIdx).color, isDark),
                    DASHBOARD_PEER_LINE_ALPHA);
            for (NotificationPredictionSeries series : peerPredictionSeries.get(peerIdx)) {
                drawPredictionSeries(
                        canvas,
                        linePaint,
                        dm,
                        series,
                        isMmol,
                        peerSeries.get(peerIdx).viewMode,
                        false,
                        peerColor,
                        peerColor,
                        peerColor,
                        startTime,
                        chartDuration,
                        chartLeft,
                        chartBottom,
                        chartWidth,
                        chartHeight,
                        minY,
                        yRange,
                        true);
            }
        }

        return bitmap;
    }
}
