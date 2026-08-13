package tk.glucodata.glucosecomplication

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import tk.glucodata.Applic
import tk.glucodata.GlucosePoint
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.GlucoseValueTone
import tk.glucodata.Natives
import tk.glucodata.NotificationHistorySource
import tk.glucodata.TrendArrowAngle
import kotlin.math.abs

/**
 * Draws the complications the way the app itself draws.
 *
 * The legacy renderer had its own arrow geometry, driven through
 * `Natives.thresholdchange`, so the complication pointed somewhere the app's own
 * arrow never did — the two disagreed on the same reading. It also took its
 * colours from three standalone `Natives.getComplication*Color` settings, which
 * meant the palette the user picked on the phone reached every surface except
 * these.
 *
 * Everything here goes through [TrendArrowAngle] and [GlucoseRangeColors], the
 * same sources the phone and the watch UI use.
 */
internal object ComplicationRenderer {

    /** Wear renders complications on dark backgrounds. */
    private const val DARK = true

    /** How much history the sparkline shows. */
    const val SPARK_WINDOW_MS = 3L * 60L * 60L * 1000L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun neutral(): Int = 0xFFE3E2DE.toInt()

    /**
     * Colour for a value: neutral unless the user asked for range colouring,
     * exactly as the watch's own readouts decide it.
     */
    fun valueColor(value: Float, isMmol: Boolean): Int = GlucoseValueTone.valueColorArgb(
        value = value,
        isDark = DARK,
        isMmol = isMmol,
        targetLow = threshold(Natives::targetlow, GlucoseRangeColors.defaultLow(isMmol)),
        targetHigh = threshold(Natives::targethigh, GlucoseRangeColors.defaultHigh(isMmol)),
        veryLowThreshold = threshold(Natives::alarmverylow, GlucoseRangeColors.defaultVeryLow(isMmol)),
        veryHighThreshold = threshold(Natives::alarmveryhigh, GlucoseRangeColors.defaultVeryHigh(isMmol)),
        fallbackArgb = neutral(),
    )

    /** Band colour for a value, or the neutral tone while it is in range. */
    fun bandColor(value: Float, isMmol: Boolean): Int = GlucoseRangeColors.colorForValue(
        value,
        threshold(Natives::targetlow, GlucoseRangeColors.defaultLow(isMmol)),
        threshold(Natives::targethigh, GlucoseRangeColors.defaultHigh(isMmol)),
        threshold(Natives::alarmverylow, GlucoseRangeColors.defaultVeryLow(isMmol)),
        threshold(Natives::alarmveryhigh, GlucoseRangeColors.defaultVeryHigh(isMmol)),
        neutral(),
        DARK,
        isMmol,
    )

    private fun threshold(source: () -> Float, fallback: Float): Float =
        runCatching(source).getOrNull()?.takeIf { it.isFinite() && it > 0f } ?: fallback

    /**
     * The app's trend arrow, at [TrendArrowAngle]'s rotation and in the same
     * proportions the Compose renderer uses, so a glance at the watch face and a
     * glance at the app agree.
     */
    fun arrowBitmap(size: Int, rateMgdlPerMin: Float, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawArrow(canvas, size.toFloat(), size.toFloat(), rateMgdlPerMin, color)
        return bitmap
    }

    fun drawArrow(canvas: Canvas, width: Float, height: Float, rateMgdlPerMin: Float, color: Int) {
        val cx = width / 2f
        val cy = height / 2f
        val span = minOf(width, height)
        // Mirrors TrendArrowCanvas: a double head past 2 mg/dL/min, the same
        // stroke weight and head proportions, scaled a little with speed.
        val speed = abs(rateMgdlPerMin)
        val showDouble = speed > 2f
        val scale = 1f + (speed * 0.12f).coerceAtMost(0.5f)
        val strokeWidth = span * 0.12f
        val headSpan = span * 0.55f
        val headDepth = headSpan / 2f
        val gap = headDepth * 0.5f
        val arrowLen = span * (if (showDouble) 0.35f else 0.6f) * scale
        val totalLen = if (showDouble) arrowLen + gap + headDepth else arrowLen

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = color

        canvas.save()
        canvas.rotate(TrendArrowAngle.rotationDegrees(rateMgdlPerMin), cx, cy)
        val startX = cx - totalLen / 2f
        val tipX = startX + arrowLen
        val wingX = tipX - headDepth
        val path = Path().apply {
            moveTo(startX, cy)
            lineTo(tipX, cy)
            moveTo(wingX, cy - headSpan / 2f)
            lineTo(tipX, cy)
            lineTo(wingX, cy + headSpan / 2f)
            if (showDouble) {
                val secondTipX = tipX + gap + headDepth
                val secondWingX = secondTipX - headDepth
                moveTo(secondWingX, cy - headSpan / 2f)
                lineTo(secondTipX, cy)
                lineTo(secondWingX, cy + headSpan / 2f)
            }
        }
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    /**
     * A sparkline of the last [SPARK_WINDOW_MS], banded by range the way the
     * charts are: the trace is neutral in range and takes the band tone where it
     * leaves it, so a glance says both level and shape.
     *
     * Returns null when there is nothing worth drawing, so the caller can offer
     * no data rather than an empty box.
     */
    fun sparklineBitmap(width: Int, height: Int, isMmol: Boolean): Bitmap? {
        val now = System.currentTimeMillis()
        val from = now - SPARK_WINDOW_MS
        val points = runCatching {
            val sensor = NotificationHistorySource.resolveSensorSerial()
            NotificationHistorySource.getDisplayHistory(from, isMmol, sensor)
                .filter { it.timestamp in from..now && it.value.isFinite() && it.value > 0f }
        }.getOrDefault(emptyList())
        if (points.size < 2 || width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawSparkline(canvas, width.toFloat(), height.toFloat(), points, isMmol, from, now)
        return bitmap
    }

    fun drawSparkline(
        canvas: Canvas,
        width: Float,
        height: Float,
        points: List<GlucosePoint>,
        isMmol: Boolean,
        from: Long,
        to: Long,
    ) {
        if (points.size < 2) return
        val low = threshold(Natives::targetlow, GlucoseRangeColors.defaultLow(isMmol))
        val high = threshold(Natives::targethigh, GlucoseRangeColors.defaultHigh(isMmol))

        var minValue = points.minOf { it.value }
        var maxValue = points.maxOf { it.value }
        // Keep the target band in view so the line's height means something.
        minValue = minOf(minValue, low)
        maxValue = maxOf(maxValue, high)
        val span = (maxValue - minValue).coerceAtLeast(if (isMmol) 1f else 18f)
        val pad = span * 0.12f
        val bottom = minValue - pad
        val range = (span + pad * 2f).coerceAtLeast(0.1f)
        val duration = (to - from).toFloat().coerceAtLeast(1f)

        fun x(timestamp: Long) = ((timestamp - from) / duration) * width
        fun y(value: Float) = height - ((value - bottom) / range) * height

        paint.reset()
        paint.isAntiAlias = true

        // Target band, faint, so in-range reads as "inside the shaded strip".
        paint.style = Paint.Style.FILL
        paint.color = (GlucoseRangeColors.targetBackground(DARK) and 0x00FFFFFF) or (0x22 shl 24)
        canvas.drawRect(0f, y(high), width, y(low), paint)

        // One segment per pair so each takes the colour of where it sits; a
        // single stroke could only ever be one band.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (minOf(width, height) * 0.07f).coerceAtLeast(2f)
        paint.strokeCap = Paint.Cap.ROUND
        for (index in 0 until points.size - 1) {
            val a = points[index]
            val b = points[index + 1]
            paint.color = bandColor((a.value + b.value) / 2f, isMmol)
            canvas.drawLine(x(a.timestamp), y(a.value), x(b.timestamp), y(b.value), paint)
        }

        // The newest reading gets a dot, as the app's charts do.
        val newest = points.last()
        paint.style = Paint.Style.FILL
        paint.color = bandColor(newest.value, isMmol)
        canvas.drawCircle(x(newest.timestamp), y(newest.value), paint.strokeWidth * 1.1f, paint)
    }

    /**
     * The sparkline with the current value written over it, for the complication
     * slots big enough to carry both.
     */
    fun sparklineWithValueBitmap(
        width: Int,
        height: Int,
        isMmol: Boolean,
        valueText: String,
        value: Float,
    ): Bitmap? {
        val bitmap = sparklineBitmap(width, height, isMmol) ?: return null
        val canvas = Canvas(bitmap)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = height * 0.42f
        paint.textAlign = Paint.Align.LEFT
        // A shadow rather than a plate: the trace stays readable underneath.
        paint.setShadowLayer(height * 0.09f, 0f, 0f, 0xC0000000.toInt())
        paint.color = valueColor(value, isMmol)
        canvas.drawText(valueText, width * 0.06f, height * 0.38f, paint)
        paint.clearShadowLayer()
        return bitmap
    }

    fun isMmol(): Boolean = runCatching { Applic.unit == 1 }.getOrDefault(false)
}
