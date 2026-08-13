package tk.glucodata.glucosecomplication

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
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
 *
 * Sizing note: a SMALL_IMAGE of type PHOTO is drawn edge to edge in its slot,
 * so every bitmap keeps a [CONTENT_INSET] margin. Without it the artwork runs
 * into the slot's rim and, in the arrow's case, came out looking enormous.
 */
internal object ComplicationRenderer {

    /** Wear renders complications on dark backgrounds. */
    private const val DARK = true

    /** How much history the sparkline shows. */
    const val SPARK_WINDOW_MS = 3L * 60L * 60L * 1000L

    /**
     * Fraction of the bitmap left empty around the artwork. Watch faces crop
     * PHOTO images to the slot shape, usually a circle, so content in the
     * corners is lost and content at the edge touches the rim.
     */
    private const val CONTENT_INSET = 0.17f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()

    private fun neutral(): Int = 0xFFE3E2DE.toInt()

    fun isMmol(): Boolean = runCatching { Applic.unit == 1 }.getOrDefault(false)

    private fun threshold(source: () -> Float, fallback: Float): Float =
        runCatching(source).getOrNull()?.takeIf { it.isFinite() && it > 0f } ?: fallback

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

    private fun bitmap(width: Int, height: Int): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return bmp to Canvas(bmp)
    }

    // ------------------------------------------------------------------ arrow

    /**
     * The app's trend arrow, at [TrendArrowAngle]'s rotation and in the same
     * proportions the Compose renderer uses, so a glance at the watch face and a
     * glance at the app agree.
     */
    fun arrowBitmap(size: Int, rateMgdlPerMin: Float, color: Int): Bitmap {
        val (bmp, canvas) = bitmap(size, size)
        val inset = size * CONTENT_INSET
        drawArrow(canvas, size - inset * 2f, size - inset * 2f, rateMgdlPerMin, color, inset, inset)
        return bmp
    }

    /**
     * Arrow with the reading's time underneath — the compact slot the watch
     * faces put next to the value.
     */
    fun arrowWithTimeBitmap(size: Int, rateMgdlPerMin: Float, timeMillis: Long, color: Int): Bitmap {
        val (bmp, canvas) = bitmap(size, size)
        val inset = size * CONTENT_INSET
        val content = size - inset * 2f
        // The arrow keeps the upper two-thirds; the time sits under it, small.
        val arrowBox = content * 0.68f
        drawArrow(
            canvas, arrowBox, arrowBox, rateMgdlPerMin, color,
            inset + (content - arrowBox) / 2f, inset,
        )
        drawTimeLabel(canvas, size.toFloat(), inset + arrowBox + content * 0.24f, timeMillis)
        return bmp
    }

    private fun drawTimeLabel(canvas: Canvas, width: Float, baselineY: Float, timeMillis: Long) {
        if (timeMillis <= 0L) return
        val text = runCatching {
            android.text.format.DateFormat.getTimeFormat(Applic.app).format(java.util.Date(timeMillis))
        }.getOrNull() ?: return
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = neutral()
        paint.alpha = 0xB0
        paint.textSize = width * 0.16f
        canvas.drawText(text, width / 2f, baselineY, paint)
    }

    fun drawArrow(
        canvas: Canvas,
        width: Float,
        height: Float,
        rateMgdlPerMin: Float,
        color: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) {
        val cx = offsetX + width / 2f
        val cy = offsetY + height / 2f
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
        // Bounded so a fast rate cannot push the head past the content box.
        val arrowLen = (span * (if (showDouble) 0.35f else 0.6f) * scale)
            .coerceAtMost(span * (if (showDouble) 0.42f else 0.72f))
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

    // ------------------------------------------------------------------ value

    /** The number alone, sized to the slot. */
    fun valueBitmap(size: Int, text: String, value: Float, isMmol: Boolean): Bitmap {
        val (bmp, canvas) = bitmap(size, size)
        val inset = size * CONTENT_INSET
        drawFittedText(
            canvas, text, valueColor(value, isMmol),
            inset, inset, size - inset * 2f, size - inset * 2f,
        )
        return bmp
    }

    /** Value with the arrow beside it, for the wider compact slots. */
    fun valueWithArrowBitmap(size: Int, text: String, value: Float, rate: Float, isMmol: Boolean): Bitmap {
        val (bmp, canvas) = bitmap(size, size)
        val inset = size * CONTENT_INSET
        val content = size - inset * 2f
        val color = valueColor(value, isMmol)
        // Value takes the left two-thirds, arrow the rest, both centred.
        val valueWidth = content * 0.62f
        drawFittedText(canvas, text, color, inset, inset, valueWidth, content)
        val arrowBox = content * 0.36f
        drawArrow(
            canvas, arrowBox, arrowBox, rate, color,
            inset + valueWidth + content * 0.02f,
            inset + (content - arrowBox) / 2f,
        )
        return bmp
    }

    /**
     * Fits [text] to a box by measuring it rather than guessing a size, so a
     * three-digit mg/dL reading and a 4.5 mmol one both fill their slot.
     */
    private fun drawFittedText(
        canvas: Canvas,
        text: String,
        color: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        if (text.isEmpty()) return
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.color = color
        paint.textSize = height
        paint.getTextBounds(text, 0, text.length, bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val scale = minOf(width / bounds.width(), height / bounds.height())
            paint.textSize = height * scale
        }
        paint.getTextBounds(text, 0, text.length, bounds)
        val baseline = top + (height + bounds.height()) / 2f
        canvas.drawText(text, left + width / 2f, baseline, paint)
    }

    // -------------------------------------------------------------- sparkline

    /** Recent history for the sparkline, or empty when there is too little. */
    fun sparkPoints(isMmol: Boolean): List<GlucosePoint> {
        val now = System.currentTimeMillis()
        val from = now - SPARK_WINDOW_MS
        return runCatching {
            val sensor = NotificationHistorySource.resolveSensorSerial()
            NotificationHistorySource.getDisplayHistory(from, isMmol, sensor)
                .filter { it.timestamp in from..now && it.value.isFinite() && it.value > 0f }
        }.getOrDefault(emptyList())
    }

    /**
     * A sparkline of the last [SPARK_WINDOW_MS], banded by range the way the
     * charts are, with the value over it.
     *
     * Returns null when there is nothing worth drawing, so the caller can offer
     * no data rather than an empty box.
     */
    fun sparklineBitmap(
        width: Int,
        height: Int,
        isMmol: Boolean,
        valueText: String? = null,
        value: Float = Float.NaN,
        inset: Boolean = true,
    ): Bitmap? {
        val points = sparkPoints(isMmol)
        if (points.size < 2 || width <= 0 || height <= 0) return null
        val (bmp, canvas) = bitmap(width, height)
        val marginX = if (inset) width * CONTENT_INSET * 0.6f else 0f
        val marginY = if (inset) height * CONTENT_INSET * 0.6f else 0f
        val now = System.currentTimeMillis()

        // The value sits above the trace rather than on it: overlaid text at
        // complication size buries the shape it is meant to accompany.
        val labelHeight = if (valueText != null) (height - marginY * 2f) * 0.42f else 0f
        val chartTop = marginY + labelHeight
        val chartHeight = height - marginY - chartTop
        canvas.save()
        canvas.translate(marginX, chartTop)
        drawSparkline(
            canvas, width - marginX * 2f, chartHeight, points, isMmol,
            now - SPARK_WINDOW_MS, now,
        )
        canvas.restore()

        if (valueText != null) {
            drawFittedText(
                canvas, valueText, valueColor(value, isMmol),
                marginX, marginY, width - marginX * 2f, labelHeight * 0.86f,
            )
        }
        return bmp
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
        if (points.size < 2 || width <= 0f || height <= 0f) return
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
        paint.color = (GlucoseRangeColors.targetBackground(DARK) and 0x00FFFFFF) or (0x26 shl 24)
        canvas.drawRect(0f, y(high), width, y(low), paint)

        // One segment per pair so each takes the colour of where it sits; a
        // single stroke could only ever be one band.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (minOf(width, height) * 0.06f).coerceIn(2f, 7f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
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
        canvas.drawCircle(x(newest.timestamp), y(newest.value), paint.strokeWidth * 1.15f, paint)
    }

    /**
     * The wide form: value and arrow on the left, sparkline filling the rest.
     * For the long slots a watch face gives to weather and the like, where a
     * square sparkline would be a postage stamp in the middle of the row.
     */
    fun wideChartBitmap(
        width: Int,
        height: Int,
        isMmol: Boolean,
        valueText: String,
        value: Float,
        rate: Float,
    ): Bitmap? {
        val points = sparkPoints(isMmol)
        if (points.size < 2 || width <= 0 || height <= 0) return null
        val (bmp, canvas) = bitmap(width, height)
        val marginY = height * 0.10f
        val contentHeight = height - marginY * 2f
        val color = valueColor(value, isMmol)

        val valueWidth = width * 0.26f
        drawFittedText(canvas, valueText, color, 0f, marginY, valueWidth, contentHeight * 0.78f)
        val arrowBox = contentHeight * 0.5f
        drawArrow(
            canvas, arrowBox, arrowBox, rate, color,
            valueWidth, marginY + (contentHeight - arrowBox) / 2f,
        )

        val chartLeft = valueWidth + arrowBox + width * 0.03f
        canvas.save()
        canvas.translate(chartLeft, marginY)
        val now = System.currentTimeMillis()
        drawSparkline(
            canvas, width - chartLeft, contentHeight, points, isMmol,
            now - SPARK_WINDOW_MS, now,
        )
        canvas.restore()
        return bmp
    }
}
