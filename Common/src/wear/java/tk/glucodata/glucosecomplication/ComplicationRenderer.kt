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
     * Margin around a PHOTO image. Faces crop these to the slot shape, usually a
     * circle, so the corners are lost — but only just: the inscribed square of a
     * circle needs about 15% off each side, and more than that only makes the
     * artwork small.
     */
    private const val PHOTO_INSET = 0.11f

    /**
     * Margin around an ICON (MonochromaticImage). Faces scale these to the slot
     * themselves rather than cropping, so built-in padding is wasted space —
     * which is why the small arrow and value slots came out tiny.
     */
    private const val ICON_INSET = 0.03f

    /**
     * Icons are tinted by the watch face, so their own colour is only a mask.
     * Drawing them in the range colour left them half-transparent and off-tone
     * next to every other complication on the face.
     */
    const val ICON_TINT = 0xFFFFFFFF.toInt()

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
    @JvmOverloads
    fun arrowBitmap(size: Int, rateMgdlPerMin: Float, color: Int, icon: Boolean = true): Bitmap {
        val (bmp, canvas) = bitmap(size, size)
        val inset = size * (if (icon) ICON_INSET else PHOTO_INSET)
        drawArrow(canvas, size - inset * 2f, size - inset * 2f, rateMgdlPerMin, color, inset, inset)
        return bmp
    }

    /**
     * Arrow with the reading's time underneath — the compact slot the watch
     * faces put next to the value.
     */
    @JvmOverloads
    fun arrowWithTimeBitmap(
        size: Int,
        rateMgdlPerMin: Float,
        timeMillis: Long,
        color: Int,
        icon: Boolean = true,
    ): Bitmap {
        val (bmp, canvas) = bitmap(size, size)
        val inset = size * (if (icon) ICON_INSET else PHOTO_INSET)
        val content = size - inset * 2f
        // The arrow takes three quarters of the height; the time is a caption
        // under it, not a second line competing with it.
        val arrowBox = content * 0.74f
        drawArrow(
            canvas, arrowBox, arrowBox, rateMgdlPerMin, color,
            inset + (content - arrowBox) / 2f, inset,
        )
        drawTimeLabel(canvas, size.toFloat(), inset + content * 0.98f, timeMillis, color)
        return bmp
    }

    private fun drawTimeLabel(
        canvas: Canvas,
        width: Float,
        baselineY: Float,
        timeMillis: Long,
        color: Int,
    ) {
        if (timeMillis <= 0L) return
        val text = runCatching {
            android.text.format.DateFormat.getTimeFormat(Applic.app).format(java.util.Date(timeMillis))
        }.getOrNull() ?: return
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = color
        paint.alpha = 0xC8
        paint.textSize = width * 0.21f
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
    @JvmOverloads
    fun valueBitmap(
        size: Int,
        text: String,
        value: Float,
        isMmol: Boolean,
        color: Int = valueColor(value, isMmol),
        icon: Boolean = true,
    ): Bitmap {
        val (bmp, canvas) = bitmap(size, size)
        val inset = size * (if (icon) ICON_INSET else PHOTO_INSET)
        drawFittedText(
            canvas, text, color,
            inset, inset, size - inset * 2f, size - inset * 2f,
        )
        return bmp
    }

    /** Value with the arrow beside it, for the wider compact slots. */
    @JvmOverloads
    fun valueWithArrowBitmap(
        size: Int,
        text: String,
        value: Float,
        rate: Float,
        isMmol: Boolean,
        color: Int = valueColor(value, isMmol),
        icon: Boolean = false,
    ): Bitmap {
        val (bmp, canvas) = bitmap(size, size)
        val inset = size * (if (icon) ICON_INSET else PHOTO_INSET)
        val content = size - inset * 2f
        // Value on top, arrow beneath: side by side in a round slot leaves both
        // small, because the pair has to fit the slot's width at its narrowest.
        val valueHeight = content * 0.60f
        drawFittedText(canvas, text, color, inset, inset, content, valueHeight)
        val arrowBox = content * 0.34f
        drawArrow(
            canvas, arrowBox, arrowBox, rate, color,
            inset + (content - arrowBox) / 2f,
            inset + content - arrowBox,
        )
        return bmp
    }

    /**
     * Fits [text] to a box by measuring it rather than guessing a size, so a
     * three-digit mg/dL reading and a 4.5 mmol one both fill their slot.
     *
     * Centred on the font's own metrics and advance width, not on the tight ink
     * box. A value like "5,1" has a comma that descends and sits to one side, so
     * centring its ink box put the digits visibly off-centre and left the glyph
     * looking misaligned in the slot.
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
        if (text.isEmpty() || width <= 0f || height <= 0f) return
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.color = color

        // Start from the box height and shrink until both dimensions fit. Cap
        // the cap-height at the box so a short string cannot overshoot.
        paint.textSize = height
        val metrics = paint.fontMetrics
        val glyphHeight = (metrics.descent - metrics.ascent).coerceAtLeast(1f)
        var size = height * (height / glyphHeight)
        paint.textSize = size
        val advance = paint.measureText(text)
        if (advance > width) {
            size *= width / advance
            paint.textSize = size
        }

        // Vertical centre from the metrics: the visual middle of a line sits
        // halfway between ascent and descent, not at the ink box's centre.
        val centred = paint.fontMetrics
        val baseline = top + height / 2f - (centred.ascent + centred.descent) / 2f
        canvas.drawText(text, left + width / 2f, baseline, paint)
    }

    /** A blank of the right shape, for a slot with nothing to show. */
    fun noValueBitmap(size: Int): Bitmap = bitmap(size, size).first

    // -------------------------------------------------------------- sparkline

    // One refresh asks every complication to redraw, and each was reading three
    // hours of history for itself. They are all drawing the same window at the
    // same instant, so the read is shared for a few seconds.
    private const val SPARK_CACHE_MS = 5_000L
    @Volatile private var sparkCache: List<GlucosePoint> = emptyList()
    @Volatile private var sparkCacheAt = 0L

    /**
     * A plausible curve for the complication picker, which asks for preview
     * data before this app necessarily has any history. Answering null there
     * removes the app from the picker entirely.
     */
    private fun syntheticPoints(isMmol: Boolean): List<GlucosePoint> {
        val now = System.currentTimeMillis()
        val step = SPARK_WINDOW_MS / 36
        val mid = if (isMmol) 6.4f else 115f
        val swing = if (isMmol) 1.6f else 29f
        return (0..36).map { index ->
            val phase = index / 36f * 2f * Math.PI.toFloat()
            val value = mid + swing * kotlin.math.sin(phase * 1.6f) * (0.55f + 0.45f * index / 36f)
            GlucosePoint(now - SPARK_WINDOW_MS + index * step, value, value)
        }
    }

    /** Recent history for the sparkline, or empty when there is too little. */
    fun sparkPoints(isMmol: Boolean): List<GlucosePoint> {
        val now = System.currentTimeMillis()
        val cached = sparkCache
        if (cached.isNotEmpty() && now - sparkCacheAt < SPARK_CACHE_MS) return cached
        val from = now - SPARK_WINDOW_MS
        val points = runCatching {
            val sensor = NotificationHistorySource.resolveSensorSerial()
            NotificationHistorySource.getDisplayHistory(from, isMmol, sensor)
                .filter { it.timestamp in from..now && it.value.isFinite() && it.value > 0f }
        }.getOrDefault(emptyList())
        sparkCache = points
        sparkCacheAt = now
        return points
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
        allowSynthetic: Boolean = false,
    ): Bitmap? {
        val points = sparkPoints(isMmol).takeIf { it.size >= 2 }
            ?: if (allowSynthetic) syntheticPoints(isMmol) else emptyList()
        if (points.size < 2 || width <= 0 || height <= 0) return null
        val (bmp, canvas) = bitmap(width, height)
        val marginX = if (inset) width * PHOTO_INSET else 0f
        val marginY = if (inset) height * PHOTO_INSET else 0f
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
        allowSynthetic: Boolean = false,
    ): Bitmap? {
        val points = sparkPoints(isMmol).takeIf { it.size >= 2 }
            ?: if (allowSynthetic) syntheticPoints(isMmol) else emptyList()
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
