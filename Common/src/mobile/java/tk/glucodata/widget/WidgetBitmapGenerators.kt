package tk.glucodata.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import tk.glucodata.ui.GlucosePoint
import kotlin.math.abs

object WidgetBitmapGenerators {

    fun generateTrendBitmap(
        context: Context,
        velocity: Float,
        color: Int,
        sizeDto: Int = 48
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val sizePx = (sizeDto * density).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val sensitivity = 25f
        val rotation = (-velocity * sensitivity).coerceIn(-90f, 90f)

        // Paint setup for Arrow
        val paint = Paint().apply {
            this.color = color
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.12f // Medium thick stroke, matching reference
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val cx = sizePx / 2f
        val cy = sizePx / 2f

        canvas.save()
        canvas.rotate(rotation, cx, cy)

        val showDouble = abs(velocity) > 2.0f
        
        // Visual center adjustment
        val yOffset = if (showDouble) sizePx * 0.05f else 0f
        
        val arrowHeight = sizePx * 0.45f
        val arrowWidth = sizePx * 0.45f 
        
        // Draw centered arrow
        drawCleanArrow(canvas, cx, cy - yOffset, arrowWidth, arrowHeight, paint)

        if (showDouble) {
            // Draw second arrow offset upwards
            drawCleanArrow(canvas, cx, cy - yOffset - (arrowHeight * 0.6f), arrowWidth, arrowHeight, paint)
        }

        canvas.restore()
        return bitmap
    }
    
    private fun drawCleanArrow(canvas: Canvas, cx: Float, cy: Float, width: Float, height: Float, paint: Paint) {
        val path = Path()
        
        val tipY = cy - height / 2f
        val baseY = cy + height / 2f
        
        // Shaft (from base to tip)
        path.moveTo(cx, baseY)
        path.lineTo(cx, tipY)
        
        // Arrow head (wings matching reference)
        val headSize = width / 2f
        path.moveTo(cx - headSize, tipY + headSize)
        path.lineTo(cx, tipY)
        path.lineTo(cx + headSize, tipY + headSize)
        
        canvas.drawPath(path, paint)
    }

    fun generateChartBitmap(
        context: Context,
        history: List<GlucosePoint>,
        widthDto: Int = 200, // Increased default
        heightDto: Int = 100, // Increased default
        color: Int,
        isMmol: Boolean
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val widthPx = (widthDto * density).toInt()
        val heightPx = (heightDto * density).toInt()
        
        if (widthPx <= 0 || heightPx <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (history.isEmpty()) return bitmap

        // 1. Setup Paints
        val linePaint = Paint().apply {
            this.color = color
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 3f * density // Thicker line
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        
        val fillPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            val fadeColor = Color.argb(40, Color.red(color), Color.green(color), Color.blue(color))
            val transparentColor = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, heightPx.toFloat(),
                fadeColor, transparentColor,
                android.graphics.Shader.TileMode.CLAMP
            )
        }

        // 2. Data Processing
        val sorted = history.sortedBy { it.timestamp }
        if (sorted.isEmpty()) return bitmap

        val minTime = sorted.first().timestamp
        val maxTime = sorted.last().timestamp
        val timeSpan = (maxTime - minTime).coerceAtLeast(1)

        val vals = sorted.map { it.value }
        // Add padding to Y-axis range to avoid clipping peaks
        val rawMin = vals.minOrNull() ?: 0f
        val rawMax = vals.maxOrNull() ?: 100f
        val range = (rawMax - rawMin).coerceAtLeast(10f)
        val minVal = rawMin - (range * 0.1f)
        val maxVal = rawMax + (range * 0.1f)
        val ySpan = maxVal - minVal

        val padding = 4f * density
        val drawWidth = widthPx.toFloat()
        val drawHeight = heightPx.toFloat()

        // 3. Construct Path
        val path = Path()
        val fillPath = Path()
        
        fillPath.moveTo(0f, heightPx.toFloat()) // Start at bottom-left
        
        sorted.forEachIndexed { i, point ->
            val value = point.value
            // X: Linear time mapping
            val x = ((point.timestamp - minTime).toFloat() / timeSpan) * drawWidth
            
            // Y: Inverted (0 is top)
            val normalizedY = (value - minVal) / ySpan
            val y = drawHeight - (normalizedY * drawHeight)
            
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        
        // Close fill path
        fillPath.lineTo(drawWidth, heightPx.toFloat())
        fillPath.close()

        // 4. Draw
        canvas.drawPath(fillPath, fillPaint) // Draw gradient fill first
        canvas.drawPath(path, linePaint)     // Draw stroke on top
        
        return bitmap
    }
    
    fun generateSensorProgressBitmap(
        widthPx: Int,
        heightPx: Int,
        progressRatio: Float,
        bgColor: Int,
        fgColor: Int,
        cornerRadiusPx: Float
    ): Bitmap {
        if (widthPx <= 0 || heightPx <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        
        val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fgColor
            style = Paint.Style.FILL
        }
        
        // Draw full background
        val rectF = android.graphics.RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
        canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, bgPaint)
        
        // Draw progress foreground (clipped to same rounded rect)
        val fillWidth = widthPx * progressRatio.coerceIn(0f, 1f)
        if (fillWidth > 0f) {
            canvas.save()
            // Clip to the bounds of the background round rect so the progress bar doesn't draw outside corners
            val clipPath = Path().apply {
                addRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            
            // Draw a standard rect, it gets clipped by the path
            val progressRect = android.graphics.RectF(0f, 0f, fillWidth, heightPx.toFloat())
            canvas.drawRect(progressRect, fgPaint)
            
            canvas.restore()
        }
        
        return bitmap
    }
}
