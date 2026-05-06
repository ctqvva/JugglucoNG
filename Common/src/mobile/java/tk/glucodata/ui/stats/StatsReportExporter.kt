package tk.glucodata.ui.stats

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.R
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.util.GlucoseFormatter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt

object StatsReportExporter {
    private const val TAG = "StatsReportExporter"

    enum class PdfVisualStyle(
        val prefValue: String,
        @StringRes val labelResId: Int
    ) {
        CURRENT("current", R.string.report_style_current),
        MINIMAL_SWISS("minimal_swiss", R.string.report_style_minimal_swiss),
        MEDICAL_JOURNAL("medical_journal", R.string.report_style_medical_journal),
        PREMIUM_DARK_INK("premium_dark_ink", R.string.report_style_dark_ink),
        ELEGANT_TYPOGRAPHY("elegant_typography", R.string.report_style_elegant_typography);

        companion object {
            fun fromPref(raw: String?): PdfVisualStyle =
                entries.firstOrNull { it.prefValue == raw } ?: CURRENT
        }
    }

    private data class PdfVisualTheme(
        val paper: Int,
        val text: Int,
        val muted: Int,
        val primary: Int,
        val primarySoft: Int,
        val secondary: Int,
        val secondarySoft: Int,
        val emerald: Int,
        val targetBand: Int,
        val danger: Int,
        val warn: Int,
        val grid: Int,
        val surface: Int,
        val surfaceSoft: Int,
        val border: Int,
        val shadow: Int,
        val insightPositive: Int,
        val insightAttention: Int,
        val insightCaution: Int,
        val titleTextSize: Float,
        val sectionTextSize: Float,
        val bodyTextSize: Float,
        val metricValueTextSize: Float,
        val metricLabelTextSize: Float,
        val kickerTextSize: Float,
        val footerTextSize: Float,
        val heroRadius: Float,
        val cardRadius: Float,
        val chartRadius: Float,
        val showTopGeometry: Boolean,
        val showSideStripe: Boolean,
        val sectionChipAlpha: Int,
        val geometryHeight: Float,
        val stripeWidth: Float,
        val useSerifFont: Boolean,
        val useOutfitFont: Boolean,
        val shadowOffsetY: Float
    )

    private fun resolveVisualTheme(style: PdfVisualStyle): PdfVisualTheme = when (style) {
        PdfVisualStyle.CURRENT -> PdfVisualTheme(
            paper = android.graphics.Color.rgb(245, 248, 253),
            text = android.graphics.Color.rgb(20, 28, 40),
            muted = android.graphics.Color.rgb(88, 102, 126),
            primary = android.graphics.Color.rgb(26, 82, 176),
            primarySoft = android.graphics.Color.argb(30, 26, 82, 176),
            secondary = android.graphics.Color.rgb(214, 95, 63),
            secondarySoft = android.graphics.Color.argb(28, 214, 95, 63),
            emerald = android.graphics.Color.rgb(33, 131, 105),
            targetBand = android.graphics.Color.argb(40, 63, 148, 92),
            danger = android.graphics.Color.rgb(198, 45, 52),
            warn = android.graphics.Color.rgb(232, 131, 35),
            grid = android.graphics.Color.rgb(210, 221, 237),
            surface = android.graphics.Color.WHITE,
            surfaceSoft = android.graphics.Color.rgb(250, 252, 255),
            border = android.graphics.Color.rgb(202, 214, 233),
            shadow = android.graphics.Color.argb(30, 19, 31, 52),
            insightPositive = android.graphics.Color.rgb(56, 142, 60),
            insightAttention = android.graphics.Color.rgb(245, 124, 0),
            insightCaution = android.graphics.Color.rgb(211, 47, 47),
            titleTextSize = 50f,
            sectionTextSize = 28f,
            bodyTextSize = 21f,
            metricValueTextSize = 36f,
            metricLabelTextSize = 18f,
            kickerTextSize = 15f,
            footerTextSize = 14f,
            heroRadius = 22f,
            cardRadius = 18f,
            chartRadius = 20f,
            showTopGeometry = true,
            showSideStripe = true,
            sectionChipAlpha = 26,
            geometryHeight = 286f,
            stripeWidth = 16f,
            useSerifFont = false,
            useOutfitFont = false,
            shadowOffsetY = 5f
        )
        PdfVisualStyle.MINIMAL_SWISS -> PdfVisualTheme(
            paper = android.graphics.Color.rgb(250, 251, 253),
            text = android.graphics.Color.rgb(24, 28, 34),
            muted = android.graphics.Color.rgb(90, 95, 103),
            primary = android.graphics.Color.rgb(24, 24, 24),
            primarySoft = android.graphics.Color.argb(18, 24, 24, 24),
            secondary = android.graphics.Color.rgb(187, 32, 37),
            secondarySoft = android.graphics.Color.argb(22, 187, 32, 37),
            emerald = android.graphics.Color.rgb(37, 121, 96),
            targetBand = android.graphics.Color.argb(28, 37, 121, 96),
            danger = android.graphics.Color.rgb(187, 32, 37),
            warn = android.graphics.Color.rgb(188, 114, 26),
            grid = android.graphics.Color.rgb(218, 221, 226),
            surface = android.graphics.Color.WHITE,
            surfaceSoft = android.graphics.Color.rgb(246, 247, 249),
            border = android.graphics.Color.rgb(214, 217, 223),
            shadow = android.graphics.Color.argb(10, 0, 0, 0),
            insightPositive = android.graphics.Color.rgb(37, 121, 96),
            insightAttention = android.graphics.Color.rgb(188, 114, 26),
            insightCaution = android.graphics.Color.rgb(187, 32, 37),
            titleTextSize = 46f,
            sectionTextSize = 26f,
            bodyTextSize = 20f,
            metricValueTextSize = 34f,
            metricLabelTextSize = 17f,
            kickerTextSize = 14f,
            footerTextSize = 13f,
            heroRadius = 8f,
            cardRadius = 6f,
            chartRadius = 8f,
            showTopGeometry = false,
            showSideStripe = true,
            sectionChipAlpha = 0,
            geometryHeight = 0f,
            stripeWidth = 8f,
            useSerifFont = false,
            useOutfitFont = false,
            shadowOffsetY = 2f
        )
        PdfVisualStyle.MEDICAL_JOURNAL -> PdfVisualTheme(
            paper = android.graphics.Color.rgb(248, 246, 241),
            text = android.graphics.Color.rgb(35, 39, 47),
            muted = android.graphics.Color.rgb(98, 102, 113),
            primary = android.graphics.Color.rgb(41, 87, 137),
            primarySoft = android.graphics.Color.argb(30, 41, 87, 137),
            secondary = android.graphics.Color.rgb(119, 73, 53),
            secondarySoft = android.graphics.Color.argb(28, 119, 73, 53),
            emerald = android.graphics.Color.rgb(46, 113, 92),
            targetBand = android.graphics.Color.argb(34, 46, 113, 92),
            danger = android.graphics.Color.rgb(171, 56, 50),
            warn = android.graphics.Color.rgb(172, 116, 46),
            grid = android.graphics.Color.rgb(215, 213, 207),
            surface = android.graphics.Color.rgb(255, 254, 252),
            surfaceSoft = android.graphics.Color.rgb(245, 242, 236),
            border = android.graphics.Color.rgb(207, 204, 195),
            shadow = android.graphics.Color.argb(16, 44, 38, 31),
            insightPositive = android.graphics.Color.rgb(46, 113, 92),
            insightAttention = android.graphics.Color.rgb(172, 116, 46),
            insightCaution = android.graphics.Color.rgb(171, 56, 50),
            titleTextSize = 48f,
            sectionTextSize = 27f,
            bodyTextSize = 20f,
            metricValueTextSize = 35f,
            metricLabelTextSize = 17f,
            kickerTextSize = 15f,
            footerTextSize = 13f,
            heroRadius = 14f,
            cardRadius = 12f,
            chartRadius = 14f,
            showTopGeometry = true,
            showSideStripe = false,
            sectionChipAlpha = 18,
            geometryHeight = 220f,
            stripeWidth = 0f,
            useSerifFont = true,
            useOutfitFont = false,
            shadowOffsetY = 3f
        )
        PdfVisualStyle.PREMIUM_DARK_INK -> PdfVisualTheme(
            paper = android.graphics.Color.rgb(19, 23, 31),
            text = android.graphics.Color.rgb(232, 237, 246),
            muted = android.graphics.Color.rgb(159, 173, 196),
            primary = android.graphics.Color.rgb(127, 171, 255),
            primarySoft = android.graphics.Color.argb(36, 127, 171, 255),
            secondary = android.graphics.Color.rgb(219, 158, 110),
            secondarySoft = android.graphics.Color.argb(34, 219, 158, 110),
            emerald = android.graphics.Color.rgb(109, 202, 172),
            targetBand = android.graphics.Color.argb(44, 109, 202, 172),
            danger = android.graphics.Color.rgb(255, 120, 122),
            warn = android.graphics.Color.rgb(241, 190, 108),
            grid = android.graphics.Color.rgb(64, 74, 94),
            surface = android.graphics.Color.rgb(26, 32, 43),
            surfaceSoft = android.graphics.Color.rgb(31, 38, 52),
            border = android.graphics.Color.rgb(70, 81, 104),
            shadow = android.graphics.Color.argb(60, 8, 10, 15),
            insightPositive = android.graphics.Color.rgb(109, 202, 172),
            insightAttention = android.graphics.Color.rgb(241, 190, 108),
            insightCaution = android.graphics.Color.rgb(255, 120, 122),
            titleTextSize = 50f,
            sectionTextSize = 28f,
            bodyTextSize = 21f,
            metricValueTextSize = 36f,
            metricLabelTextSize = 18f,
            kickerTextSize = 15f,
            footerTextSize = 14f,
            heroRadius = 22f,
            cardRadius = 18f,
            chartRadius = 20f,
            showTopGeometry = true,
            showSideStripe = true,
            sectionChipAlpha = 32,
            geometryHeight = 300f,
            stripeWidth = 18f,
            useSerifFont = false,
            useOutfitFont = false,
            shadowOffsetY = 6f
        )
        PdfVisualStyle.ELEGANT_TYPOGRAPHY -> PdfVisualTheme(
            paper = android.graphics.Color.rgb(252, 251, 249),
            text = android.graphics.Color.rgb(33, 31, 44),
            muted = android.graphics.Color.rgb(108, 101, 126),
            primary = android.graphics.Color.rgb(86, 68, 139),
            primarySoft = android.graphics.Color.argb(28, 86, 68, 139),
            secondary = android.graphics.Color.rgb(176, 127, 87),
            secondarySoft = android.graphics.Color.argb(24, 176, 127, 87),
            emerald = android.graphics.Color.rgb(78, 136, 112),
            targetBand = android.graphics.Color.argb(34, 78, 136, 112),
            danger = android.graphics.Color.rgb(181, 76, 94),
            warn = android.graphics.Color.rgb(192, 142, 79),
            grid = android.graphics.Color.rgb(219, 214, 226),
            surface = android.graphics.Color.rgb(255, 255, 255),
            surfaceSoft = android.graphics.Color.rgb(247, 244, 250),
            border = android.graphics.Color.rgb(217, 209, 227),
            shadow = android.graphics.Color.argb(18, 41, 29, 68),
            insightPositive = android.graphics.Color.rgb(78, 136, 112),
            insightAttention = android.graphics.Color.rgb(192, 142, 79),
            insightCaution = android.graphics.Color.rgb(181, 76, 94),
            titleTextSize = 48f,
            sectionTextSize = 28f,
            bodyTextSize = 20f,
            metricValueTextSize = 38f,
            metricLabelTextSize = 18f,
            kickerTextSize = 16f,
            footerTextSize = 14f,
            heroRadius = 24f,
            cardRadius = 20f,
            chartRadius = 22f,
            showTopGeometry = true,
            showSideStripe = false,
            sectionChipAlpha = 24,
            geometryHeight = 250f,
            stripeWidth = 0f,
            useSerifFont = true,
            useOutfitFont = false,
            shadowOffsetY = 4f
        )
    }

    data class PatientInfo(
        val name: String = "",
        val identifier: String = "",
        val dateOfBirth: String = "",
        val clinician: String = ""
    ) {
        fun hasContent(): Boolean =
            name.isNotBlank() || identifier.isNotBlank() || dateOfBirth.isNotBlank() || clinician.isNotBlank()

        fun sanitized(): PatientInfo = copy(
            name = name.trim(),
            identifier = identifier.trim(),
            dateOfBirth = dateOfBirth.trim(),
            clinician = clinician.trim()
        )
    }

    suspend fun exportComprehensivePdf(
        context: Context,
        uri: Uri,
        uiState: StatsUiState,
        reportDays: Int = uiState.selectedRange?.days?.takeIf { it > 0 }
            ?: uiState.activeRange?.daySpan?.takeIf { it > 0 }
            ?: 90,
        patientInfo: PatientInfo? = null,
        reportStyle: PdfVisualStyle = PdfVisualStyle.CURRENT
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val reportWindowDays = reportDays.coerceAtLeast(1)
            val pageWidth = 1240
            val pageHeight = 1754
            val margin = 52f
            val summary = uiState.summary
            val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            val firstReadingText = if (summary.firstTimestamp > 0L) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(summary.firstTimestamp))
            } else "-"
            val lastReadingText = if (summary.lastTimestamp > 0L) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(summary.lastTimestamp))
            } else "-"
            val rangeLabel = context.getString(R.string.auto_reset_days, reportWindowDays)
            val reportPatientInfo = patientInfo?.sanitized()?.takeIf { it.hasContent() }
            val unitLabel = if (uiState.unit == GlucoseUnit.MMOL) {
                context.getString(R.string.unit_mmol)
            } else {
                context.getString(R.string.unit_mg)
            }

            val theme = resolveVisualTheme(reportStyle)
            val colorPaper = theme.paper
            val colorText = theme.text
            val colorMuted = theme.muted
            val colorPrimary = theme.primary
            val colorPrimarySoft = theme.primarySoft
            val colorSecondary = theme.secondary
            val colorSecondarySoft = theme.secondarySoft
            val colorEmerald = theme.emerald
            val colorTargetBand = theme.targetBand
            val colorDanger = theme.danger
            val colorWarn = theme.warn
            val colorGrid = theme.grid
            val colorSurface = theme.surface
            val colorSurfaceSoft = theme.surfaceSoft
            val colorBorder = theme.border
            val colorShadow = theme.shadow

            fun mixColors(start: Int, end: Int, ratio: Float): Int {
                val clamped = ratio.coerceIn(0f, 1f)
                val inv = 1f - clamped
                return android.graphics.Color.argb(
                    ((android.graphics.Color.alpha(start) * inv) + (android.graphics.Color.alpha(end) * clamped)).roundToInt().coerceIn(0, 255),
                    ((android.graphics.Color.red(start) * inv) + (android.graphics.Color.red(end) * clamped)).roundToInt().coerceIn(0, 255),
                    ((android.graphics.Color.green(start) * inv) + (android.graphics.Color.green(end) * clamped)).roundToInt().coerceIn(0, 255),
                    ((android.graphics.Color.blue(start) * inv) + (android.graphics.Color.blue(end) * clamped)).roundToInt().coerceIn(0, 255)
                )
            }

            val colorTirVeryLow = mixColors(colorDanger, colorWarn, 0.35f)
            val colorTirLow = mixColors(colorWarn, colorEmerald, 0.15f)
            val colorTirInRange = colorEmerald
            val colorTirHigh = mixColors(colorSecondary, colorWarn, 0.25f)
            val colorTirVeryHigh = mixColors(colorSecondary, colorDanger, 0.38f)

            fun loadFontSafely(fontRes: Int): android.graphics.Typeface? =
                runCatching { ResourcesCompat.getFont(context, fontRes) }
                    .onFailure { throwable ->
                        Log.w(TAG, "Unable to load font resource $fontRes, falling back", throwable)
                    }
                    .getOrNull()

            val preferredTypeface = when {
                theme.useOutfitFont -> loadFontSafely(R.font.outfit)
                theme.useSerifFont -> android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
                else -> loadFontSafely(R.font.ibm_plex_sans_var)
            }
            val plexRegular = preferredTypeface ?: android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
            val plexMedium = android.graphics.Typeface.create(plexRegular, android.graphics.Typeface.NORMAL)
            val plexBold = android.graphics.Typeface.create(plexRegular, android.graphics.Typeface.BOLD)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorText
                textSize = theme.titleTextSize
                typeface = plexBold
            }
            val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorMuted
                textSize = theme.bodyTextSize
                typeface = plexRegular
            }
            val sectionTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorText
                textSize = theme.sectionTextSize
                typeface = plexBold
            }
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorText
                textSize = theme.bodyTextSize
                typeface = plexMedium
            }
            val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorMuted
                textSize = theme.metricLabelTextSize
                typeface = plexRegular
            }
            val metricValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorText
                textSize = theme.metricValueTextSize
                typeface = plexBold
            }
            val metricLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorMuted
                textSize = theme.metricLabelTextSize
                typeface = plexRegular
            }
            val kickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorPrimary
                textSize = theme.kickerTextSize
                typeface = plexBold
            }
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorMuted
                textSize = theme.footerTextSize
                typeface = plexRegular
            }
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorGrid
                strokeWidth = 2f
                style = Paint.Style.STROKE
            }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = colorSurface
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = colorBorder
            }
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = colorShadow
            }
            val agpMedianPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = colorPrimary
                strokeWidth = 4.2f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val agpPercentilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = android.graphics.Color.argb(190, android.graphics.Color.red(colorPrimary), android.graphics.Color.green(colorPrimary), android.graphics.Color.blue(colorPrimary))
                strokeWidth = 2.2f
                strokeCap = Paint.Cap.ROUND
            }
            val agpIqrFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = android.graphics.Color.argb(52, android.graphics.Color.red(colorPrimary), android.graphics.Color.green(colorPrimary), android.graphics.Color.blue(colorPrimary))
            }
            val dailyLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = colorPrimary
                strokeWidth = 3.8f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val dailyAreaFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = colorPrimarySoft
            }
            val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = colorPrimary
            }
            val tirSegmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val hrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorText
                textSize = theme.metricLabelTextSize - 1f
                typeface = plexRegular
            }
            val tableHeaderFillColor = mixColors(colorPrimarySoft, colorSurface, 0.45f)
            val tableAltRowFillColor = mixColors(colorSurfaceSoft, colorPrimarySoft, 0.35f)
            val tableValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mixColors(colorText, colorMuted, 0.22f)
                textSize = theme.metricLabelTextSize
                typeface = plexMedium
            }

            val doc = PdfDocument()
            var pageNumber = 0

            fun formatGlucose(valueMgDl: Float, withUnit: Boolean = true): String {
                val isMmol = uiState.unit == GlucoseUnit.MMOL
                val formatted = if (isMmol) {
                    String.format(Locale.getDefault(), "%.1f", GlucoseFormatter.mgToMmol(valueMgDl))
                } else {
                    valueMgDl.roundToInt().toString()
                }
                return if (withUnit) "$formatted $unitLabel" else formatted
            }

            fun formatPercent(value: Float): String =
                String.format(Locale.getDefault(), "%.1f%%", value)

            fun formatHours(valuePercent: Float): String {
                val hours = reportWindowDays * 24f * (valuePercent / 100f)
                return String.format(Locale.getDefault(), "%.1f h", hours)
            }

            fun formatDailyDuration(valuePercent: Float): String {
                val totalMinutes = (24f * 60f * (valuePercent / 100f)).roundToInt().coerceAtLeast(0)
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60
                return if (minutes == 0) "${hours}h/day" else "${hours}h ${minutes}m/day"
            }

            fun formatNullableGlucose(valueMgDl: Float?): String =
                valueMgDl?.let { formatGlucose(it, withUnit = false) } ?: "-"

            fun drawWrappedText(
                canvas: android.graphics.Canvas,
                text: String,
                left: Float,
                top: Float,
                maxWidth: Float,
                lineHeight: Float,
                paint: Paint
            ): Float {
                if (text.isBlank()) return top
                var yCursor = top
                var line = StringBuilder()
                text.split(" ").forEach { word ->
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (paint.measureText(candidate) > maxWidth) {
                        canvas.drawText(line.toString(), left, yCursor, paint)
                        yCursor += lineHeight
                        line = StringBuilder(word)
                    } else {
                        line = StringBuilder(candidate)
                    }
                }
                if (line.isNotEmpty()) {
                    canvas.drawText(line.toString(), left, yCursor, paint)
                    yCursor += lineHeight
                }
                return yCursor
            }

            fun fitTextSingleLine(
                text: String,
                maxWidth: Float,
                paint: Paint
            ): String {
                if (text.isBlank()) return text
                if (paint.measureText(text) <= maxWidth) return text
                val ellipsis = "..."
                var end = text.length
                while (end > 0 && paint.measureText(text.substring(0, end) + ellipsis) > maxWidth) {
                    end--
                }
                return if (end <= 0) ellipsis else text.substring(0, end) + ellipsis
            }

            fun fitTextLines(
                text: String,
                maxWidth: Float,
                paint: Paint,
                maxLines: Int
            ): List<String> {
                if (text.isBlank() || maxLines <= 0) return emptyList()
                val words = text.trim().split(Regex("\\s+"))
                if (words.isEmpty()) return emptyList()
                val lines = mutableListOf<String>()
                var current = ""
                words.forEach { word ->
                    val candidate = if (current.isEmpty()) word else "$current $word"
                    if (paint.measureText(candidate) <= maxWidth) {
                        current = candidate
                    } else {
                        if (current.isNotEmpty()) {
                            lines += current
                            if (lines.size == maxLines) {
                                return lines.toMutableList().also { rendered ->
                                    val last = rendered.last() + "..."
                                    rendered[rendered.lastIndex] = fitTextSingleLine(last, maxWidth, paint)
                                }
                            }
                        }
                        current = word
                    }
                }
                if (current.isNotEmpty()) lines += current
                if (lines.size > maxLines) {
                    val trimmed = lines.take(maxLines).toMutableList()
                    trimmed[trimmed.lastIndex] = fitTextSingleLine(trimmed.last() + "...", maxWidth, paint)
                    return trimmed
                }
                if (lines.isNotEmpty()) {
                    val lastIndex = lines.lastIndex
                    lines[lastIndex] = fitTextSingleLine(lines[lastIndex], maxWidth, paint)
                }
                return lines
            }

            fun drawCardShadow(canvas: android.graphics.Canvas, rect: RectF, radius: Float) {
                val shadowRect = RectF(rect.left, rect.top + theme.shadowOffsetY, rect.right, rect.bottom + theme.shadowOffsetY)
                canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)
            }

            fun drawPageChrome(
                canvas: android.graphics.Canvas,
                accentColor: Int,
                accentSoftColor: Int
            ) {
                fillPaint.color = colorPaper
                canvas.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), fillPaint)

                if (theme.showTopGeometry) {
                    fillPaint.color = colorSurfaceSoft
                    canvas.drawRect(0f, 0f, pageWidth.toFloat(), 208f, fillPaint)

                    val geometry = Path().apply {
                        moveTo(pageWidth.toFloat(), 0f)
                        lineTo(pageWidth.toFloat(), theme.geometryHeight)
                        lineTo(pageWidth - 276f, 0f)
                        close()
                    }
                    fillPaint.color = accentSoftColor
                    canvas.drawPath(geometry, fillPaint)
                }

                if (theme.showSideStripe && theme.stripeWidth > 0f) {
                    val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = accentColor
                    }
                    canvas.drawRect(0f, 0f, theme.stripeWidth, pageHeight.toFloat(), stripe)
                }
            }

            fun drawHeader(
                canvas: android.graphics.Canvas,
                pageTitle: String,
                pageSubtitle: String,
                accentColor: Int,
                accentSoftColor: Int
            ): Float {
                drawPageChrome(canvas, accentColor, accentSoftColor)

                val heroRect = RectF(margin, margin - 6f, pageWidth - margin, margin + 176f)
                drawCardShadow(canvas, heroRect, theme.heroRadius)
                fillPaint.color = colorSurface
                canvas.drawRoundRect(heroRect, theme.heroRadius, theme.heroRadius, fillPaint)
                borderPaint.color = colorBorder
                canvas.drawRoundRect(heroRect, theme.heroRadius, theme.heroRadius, borderPaint)

                val accentRail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = accentColor
                }
                canvas.drawRoundRect(RectF(heroRect.left + 14f, heroRect.top + 14f, heroRect.left + 24f, heroRect.bottom - 14f), 8f, 8f, accentRail)

                val badgeWidth = 300f
                val badgeRect = RectF(heroRect.right - badgeWidth - 20f, heroRect.top + 22f, heroRect.right - 20f, heroRect.top + 96f)
                fillPaint.color = colorSurfaceSoft
                canvas.drawRoundRect(badgeRect, 16f, 16f, fillPaint)
                borderPaint.color = colorBorder
                canvas.drawRoundRect(badgeRect, 16f, 16f, borderPaint)

                val textLeft = heroRect.left + 42f
                val textRight = badgeRect.left - 20f
                val titleWidth = (textRight - textLeft).coerceAtLeast(240f)
                val headerTitlePaint = Paint(titlePaint)
                val headerSubtitlePaint = Paint(subtitlePaint)
                var titleLines = fitTextLines(pageTitle, titleWidth, headerTitlePaint, maxLines = 2).ifEmpty {
                    listOf(fitTextSingleLine(pageTitle, titleWidth, headerTitlePaint))
                }
                var subtitleText = fitTextSingleLine(pageSubtitle, titleWidth, headerSubtitlePaint)
                val maxTextHeight = heroRect.height() - 40f
                while (headerTitlePaint.textSize > 34f) {
                    val estimatedHeight =
                        (titleLines.size * headerTitlePaint.textSize * 1.06f) + (headerSubtitlePaint.textSize * 1.14f)
                    if (estimatedHeight <= maxTextHeight) break
                    headerTitlePaint.textSize -= 2f
                    headerSubtitlePaint.textSize = (headerSubtitlePaint.textSize - 1f).coerceAtLeast(15f)
                    titleLines = fitTextLines(pageTitle, titleWidth, headerTitlePaint, maxLines = 2).ifEmpty {
                        listOf(fitTextSingleLine(pageTitle, titleWidth, headerTitlePaint))
                    }
                    subtitleText = fitTextSingleLine(pageSubtitle, titleWidth, headerSubtitlePaint)
                }

                val titleLineHeight = headerTitlePaint.textSize * 1.06f
                var yCursor = heroRect.top + 24f + headerTitlePaint.textSize
                titleLines.forEach { line ->
                    canvas.drawText(line, textLeft, yCursor, headerTitlePaint)
                    yCursor += titleLineHeight
                }
                canvas.drawText(subtitleText, textLeft, yCursor, headerSubtitlePaint)

                val badgeTextPaint = Paint(mutedPaint).apply {
                    textSize = theme.kickerTextSize + 1f
                    color = colorMuted
                }
                val badgeText = fitTextSingleLine(
                    context.getString(R.string.report_generated_page, generatedAt, pageNumber),
                    badgeRect.width() - 24f,
                    badgeTextPaint
                )
                val badgeTextY = badgeRect.centerY() + (badgeTextPaint.textSize * 0.34f)
                canvas.drawText(badgeText, badgeRect.left + 12f, badgeTextY, badgeTextPaint)

                return heroRect.bottom + 24f
            }

            fun drawFooter(
                canvas: android.graphics.Canvas,
                accentColor: Int
            ) {
                val footerY = pageHeight - 26f
                val footerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    alpha = 120
                }
                canvas.drawLine(margin, footerY - 20f, pageWidth - margin, footerY - 20f, footerLine)
                canvas.drawText(
                    context.getString(R.string.app_name) + " · " + context.getString(R.string.export_readable_report),
                    margin,
                    footerY,
                    footerPaint
                )
                val pageText = pageNumber.toString()
                val width = footerPaint.measureText(pageText)
                canvas.drawText(pageText, pageWidth - margin - width, footerY, footerPaint)
            }

            fun drawSectionHeader(
                canvas: android.graphics.Canvas,
                title: String,
                top: Float,
                accentColor: Int
            ): Float {
                val labelText = fitTextSingleLine(
                    title,
                    pageWidth - (margin * 2f) - 28f,
                    sectionTitlePaint
                )
                if (theme.sectionChipAlpha > 0) {
                    val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = accentColor
                        alpha = theme.sectionChipAlpha
                    }
                    val labelRect = RectF(margin, top - 26f, margin + 16f + sectionTitlePaint.measureText(labelText), top + 10f)
                    canvas.drawRoundRect(labelRect, 12f, 12f, chipPaint)
                }
                sectionTitlePaint.color = colorText
                canvas.drawText(labelText, margin + 12f, top, sectionTitlePaint)
                val dividerPaint = Paint(linePaint).apply { color = accentColor; alpha = 92 }
                canvas.drawLine(margin, top + 12f, pageWidth - margin, top + 12f, dividerPaint)
                return top + 36f
            }

            fun drawPatientInfoCard(
                canvas: android.graphics.Canvas,
                info: PatientInfo,
                top: Float,
                accentColor: Int
            ): Float {
                val cardWidth = 420f
                val rowHeight = 22f
                val rows = listOfNotNull(
                    info.name.takeIf { it.isNotBlank() }?.let { "${context.getString(R.string.patient_name_label)}: $it" },
                    info.identifier.takeIf { it.isNotBlank() }?.let { "${context.getString(R.string.patient_id_label)}: $it" },
                    info.dateOfBirth.takeIf { it.isNotBlank() }?.let { "${context.getString(R.string.patient_dob_label)}: $it" },
                    info.clinician.takeIf { it.isNotBlank() }?.let { "${context.getString(R.string.patient_clinician_label)}: $it" }
                )
                if (rows.isEmpty()) return top
                val cardHeight = 44f + (rows.size * rowHeight) + 18f
                val left = pageWidth - margin - cardWidth
                val rect = RectF(left, top, left + cardWidth, top + cardHeight)
                drawCardShadow(canvas, rect, theme.cardRadius)
                fillPaint.color = colorSurface
                canvas.drawRoundRect(rect, theme.cardRadius, theme.cardRadius, fillPaint)
                borderPaint.color = colorBorder
                canvas.drawRoundRect(rect, theme.cardRadius, theme.cardRadius, borderPaint)
                val topStrip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = accentColor
                    alpha = 28
                }
                canvas.drawRoundRect(RectF(rect.left + 10f, rect.top + 10f, rect.right - 10f, rect.top + 34f), 10f, 10f, topStrip)
                kickerPaint.color = accentColor
                canvas.drawText(context.getString(R.string.report_patient_information), left + 18f, top + 28f, kickerPaint)
                var yCursor = top + 56f
                rows.forEach { row ->
                    canvas.drawText(
                        fitTextSingleLine(row, cardWidth - 34f, bodyPaint),
                        left + 18f,
                        yCursor,
                        bodyPaint
                    )
                    yCursor += rowHeight
                }
                return rect.bottom
            }

            fun drawMetricCard(
                canvas: android.graphics.Canvas,
                left: Float,
                top: Float,
                width: Float,
                height: Float,
                title: String,
                value: String,
                subtitle: String,
                accentColor: Int,
                dense: Boolean = false
            ) {
                val rect = RectF(left, top, left + width, top + height)
                drawCardShadow(canvas, rect, theme.cardRadius)
                fillPaint.color = colorSurface
                canvas.drawRoundRect(rect, theme.cardRadius, theme.cardRadius, fillPaint)
                borderPaint.color = colorBorder
                canvas.drawRoundRect(rect, theme.cardRadius, theme.cardRadius, borderPaint)

                val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(RectF(left + 10f, top + 10f, left + 20f, top + height - 10f), 8f, 8f, accentPaint)

                val textWidth = width - 44f
                val titleText = fitTextSingleLine(title, textWidth, metricLabelPaint)
                val subtitleText = fitTextSingleLine(subtitle, textWidth, metricLabelPaint)
                val valuePaint = Paint(metricValuePaint)
                val valueMinTextSize = if (dense) 22f else 26f
                valuePaint.textSize = if (dense) 30f else metricValuePaint.textSize
                while (valuePaint.textSize > valueMinTextSize && valuePaint.measureText(value) > textWidth) {
                    valuePaint.textSize -= 1f
                }
                val titleY = top + if (dense) 31f else 35f
                val subtitleY = top + height - if (dense) 13f else 18f
                val valueY = minOf(top + if (dense) 65f else 84f, subtitleY - (valuePaint.textSize * 0.34f))
                canvas.drawText(titleText, left + 28f, titleY, metricLabelPaint)
                canvas.drawText(value, left + 28f, valueY, valuePaint)
                canvas.drawText(subtitleText, left + 28f, subtitleY, metricLabelPaint)
            }

            fun drawAgpChart(
                canvas: android.graphics.Canvas,
                top: Float,
                height: Float,
                accentColor: Int
            ) {
                val chartRect = RectF(margin, top, pageWidth - margin, top + height)
                drawCardShadow(canvas, chartRect, theme.chartRadius)
                fillPaint.color = colorSurface
                canvas.drawRoundRect(chartRect, theme.chartRadius, theme.chartRadius, fillPaint)
                borderPaint.color = colorBorder
                canvas.drawRoundRect(chartRect, theme.chartRadius, theme.chartRadius, borderPaint)

                val bins = summary.agpByHour.filter { it.sampleCount > 0 }
                if (bins.isEmpty()) {
                    canvas.drawText(context.getString(R.string.report_not_enough_agp), chartRect.left + 24f, chartRect.top + 70f, mutedPaint)
                    return
                }

                val plotRect = RectF(chartRect.left + 74f, chartRect.top + 52f, chartRect.right - 28f, chartRect.bottom - 80f)
                val allValues = bins.flatMap { listOfNotNull(it.p10MgDl, it.p25MgDl, it.medianMgDl, it.p75MgDl, it.p90MgDl) }
                val targetLow = uiState.targets.lowMgDl
                val targetHigh = uiState.targets.highMgDl
                val spanPadding = ((targetHigh - targetLow).coerceAtLeast(40f) * 0.2f).coerceIn(12f, 40f)
                val minY = minOf(allValues.minOrNull() ?: targetLow, targetLow - spanPadding)
                val maxY = maxOf(allValues.maxOrNull() ?: targetHigh, targetHigh + spanPadding).coerceAtLeast(minY + 10f)

                fun xForHour(hour: Int): Float = plotRect.left + (hour / 23f) * plotRect.width()
                fun yFor(value: Float): Float {
                    val ratio = ((value - minY) / (maxY - minY)).coerceIn(0f, 1f)
                    return plotRect.bottom - (ratio * plotRect.height())
                }

                val targetTop = yFor(targetHigh)
                val targetBottom = yFor(targetLow)
                val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = colorTargetBand
                }
                canvas.drawRoundRect(
                    RectF(plotRect.left, targetTop, plotRect.right, targetBottom),
                    9f,
                    9f,
                    targetPaint
                )

                val horizontalGuides = listOf(maxY, targetHigh, targetLow, minY).distinct().sortedDescending()
                horizontalGuides.forEach { value ->
                    val yLine = yFor(value)
                    canvas.drawLine(plotRect.left, yLine, plotRect.right, yLine, linePaint)
                    val axisLabel = formatGlucose(value, withUnit = false)
                    canvas.drawText(axisLabel, chartRect.left + 8f, yLine + 6f, hrPaint)
                }

                val p10Path = Path()
                val p90Path = Path()
                val medianPath = Path()
                val upperIqr = mutableListOf<Pair<Float, Float>>()
                val lowerIqr = mutableListOf<Pair<Float, Float>>()
                var p10Started = false
                var p90Started = false
                var medianStarted = false

                bins.forEach { bin ->
                    val x = xForHour(bin.hour)
                    bin.p10MgDl?.let {
                        val y = yFor(it)
                        if (!p10Started) {
                            p10Path.moveTo(x, y)
                            p10Started = true
                        } else {
                            p10Path.lineTo(x, y)
                        }
                    } ?: run { p10Started = false }
                    bin.p90MgDl?.let {
                        val y = yFor(it)
                        if (!p90Started) {
                            p90Path.moveTo(x, y)
                            p90Started = true
                        } else {
                            p90Path.lineTo(x, y)
                        }
                    } ?: run { p90Started = false }
                    bin.medianMgDl?.let {
                        val y = yFor(it)
                        if (!medianStarted) {
                            medianPath.moveTo(x, y)
                            medianStarted = true
                        } else {
                            medianPath.lineTo(x, y)
                        }
                    } ?: run { medianStarted = false }
                    bin.p75MgDl?.let { upperIqr += x to yFor(it) }
                    bin.p25MgDl?.let { lowerIqr += x to yFor(it) }
                }

                if (upperIqr.size >= 2 && lowerIqr.size >= 2) {
                    val iqrPath = Path().apply {
                        moveTo(upperIqr.first().first, upperIqr.first().second)
                        upperIqr.drop(1).forEach { (x, y) -> lineTo(x, y) }
                        lowerIqr.asReversed().forEach { (x, y) -> lineTo(x, y) }
                        close()
                    }
                    canvas.drawPath(iqrPath, agpIqrFillPaint)
                }

                canvas.drawPath(p10Path, agpPercentilePaint)
                canvas.drawPath(p90Path, agpPercentilePaint)
                canvas.drawPath(medianPath, agpMedianPaint)

                listOf(0, 6, 12, 18, 23).forEach { hour ->
                    val x = xForHour(hour)
                    canvas.drawLine(x, plotRect.top, x, plotRect.bottom, linePaint)
                    val label = String.format(Locale.getDefault(), "%02d:00", hour)
                    val textWidth = metricLabelPaint.measureText(label)
                    canvas.drawText(label, x - (textWidth / 2f), plotRect.bottom + 30f, metricLabelPaint)
                }

                val legendTop = chartRect.bottom - 30f
                val legendItems = listOf(
                    Triple(context.getString(R.string.gmi_target), colorTargetBand, false),
                    Triple(context.getString(R.string.report_p10_p90), agpPercentilePaint.color, true),
                    Triple(context.getString(R.string.report_iqr_short), agpIqrFillPaint.color, false),
                    Triple(context.getString(R.string.median), accentColor, true)
                )
                var legendX = chartRect.left + 20f
                legendItems.forEach { (label, color, stroke) ->
                    val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = color
                        style = if (stroke) Paint.Style.STROKE else Paint.Style.FILL
                        strokeWidth = if (stroke) 3f else 0f
                    }
                    canvas.drawRoundRect(RectF(legendX, legendTop - 12f, legendX + 18f, legendTop + 6f), 5f, 5f, legendPaint)
                    canvas.drawText(label, legendX + 24f, legendTop + 2f, metricLabelPaint)
                    legendX += 24f + metricLabelPaint.measureText(label) + 26f
                }
            }

            fun drawDailyChart(
                canvas: android.graphics.Canvas,
                top: Float,
                height: Float
            ) {
                val chartRect = RectF(margin, top, pageWidth - margin, top + height)
                drawCardShadow(canvas, chartRect, theme.chartRadius)
                fillPaint.color = colorSurface
                canvas.drawRoundRect(chartRect, theme.chartRadius, theme.chartRadius, fillPaint)
                borderPaint.color = colorBorder
                canvas.drawRoundRect(chartRect, theme.chartRadius, theme.chartRadius, borderPaint)

                val days = summary.dailyStats
                if (days.isEmpty()) {
                    canvas.drawText(context.getString(R.string.report_not_enough_daily_trend), chartRect.left + 24f, chartRect.top + 70f, mutedPaint)
                    return
                }

                val plotRect = RectF(chartRect.left + 70f, chartRect.top + 48f, chartRect.right - 26f, chartRect.bottom - 62f)
                val values = days.map { it.averageMgDl }
                val targetLow = uiState.targets.lowMgDl
                val targetHigh = uiState.targets.highMgDl
                val spanPadding = ((targetHigh - targetLow).coerceAtLeast(40f) * 0.2f).coerceIn(12f, 40f)
                val minY = minOf(values.minOrNull() ?: targetLow, targetLow - spanPadding)
                val maxY = maxOf(values.maxOrNull() ?: targetHigh, targetHigh + spanPadding).coerceAtLeast(minY + 10f)

                fun xForIndex(index: Int): Float {
                    val denominator = (days.size - 1).coerceAtLeast(1)
                    return plotRect.left + (index.toFloat() / denominator.toFloat()) * plotRect.width()
                }
                fun yFor(value: Float): Float {
                    val ratio = ((value - minY) / (maxY - minY)).coerceIn(0f, 1f)
                    return plotRect.bottom - (ratio * plotRect.height())
                }

                val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = colorTargetBand
                }
                canvas.drawRoundRect(
                    RectF(plotRect.left, yFor(targetHigh), plotRect.right, yFor(targetLow)),
                    9f,
                    9f,
                    targetPaint
                )

                val linePath = Path()
                val areaPath = Path()
                days.forEachIndexed { index, day ->
                    val x = xForIndex(index)
                    val yPoint = yFor(day.averageMgDl)
                    if (index == 0) {
                        linePath.moveTo(x, yPoint)
                        areaPath.moveTo(x, yPoint)
                    } else {
                        linePath.lineTo(x, yPoint)
                        areaPath.lineTo(x, yPoint)
                    }
                }
                areaPath.lineTo(plotRect.right, plotRect.bottom)
                areaPath.lineTo(plotRect.left, plotRect.bottom)
                areaPath.close()
                canvas.drawPath(areaPath, dailyAreaFillPaint)
                canvas.drawPath(linePath, dailyLinePaint)

                days.forEachIndexed { index, day ->
                    val x = xForIndex(index)
                    val yPoint = yFor(day.averageMgDl)
                    pointPaint.color = when {
                        day.inRangePercent >= 70f -> colorEmerald
                        day.inRangePercent >= 50f -> colorWarn
                        else -> colorDanger
                    }
                    canvas.drawCircle(x, yPoint, if (index == days.lastIndex) 5f else 4f, pointPaint)
                }

                listOf(0, (days.lastIndex / 2).coerceAtLeast(0), days.lastIndex).distinct().forEach { index ->
                    val x = xForIndex(index)
                    canvas.drawLine(x, plotRect.top, x, plotRect.bottom, linePaint)
                    val label = days[index].date.toString().substring(5)
                    val textWidth = metricLabelPaint.measureText(label)
                    canvas.drawText(label, x - (textWidth / 2f), plotRect.bottom + 28f, metricLabelPaint)
                }

                listOf(maxY, targetHigh, targetLow, minY).distinct().sortedDescending().forEach { value ->
                    val yLine = yFor(value)
                    canvas.drawLine(plotRect.left, yLine, plotRect.right, yLine, linePaint)
                    canvas.drawText(formatGlucose(value, withUnit = false), chartRect.left + 8f, yLine + 6f, hrPaint)
                }
            }

            data class ExposureTableRow(
                val label: String,
                val hoursPerDay: Float,
                val eventsPerDay: Float,
                val avgDurationHours: Float
            )

            fun buildExposureRows(daysWindow: Int): List<ExposureTableRow> {
                val readings = uiState.readings.sortedBy { it.timestamp }
                if (readings.isEmpty()) return emptyList()
                val days = daysWindow.coerceAtLeast(1).toFloat()
                val thresholds = listOf(
                    "< ${formatGlucose(50f, withUnit = false)}" to { value: Float -> value < 50f },
                    "< ${formatGlucose(60f, withUnit = false)}" to { value: Float -> value < 60f },
                    "< ${formatGlucose(70f, withUnit = false)}" to { value: Float -> value < 70f },
                    "> ${formatGlucose(180f, withUnit = false)}" to { value: Float -> value > 180f },
                    "> ${formatGlucose(250f, withUnit = false)}" to { value: Float -> value > 250f },
                    "> ${formatGlucose(400f, withUnit = false)}" to { value: Float -> value > 400f }
                )

                fun evaluate(predicate: (Float) -> Boolean): Triple<Float, Float, Float> {
                    var episodeCount = 0
                    var totalMinutes = 0f
                    var runStart: Long? = null
                    var lastMatch: Long? = null
                    readings.forEach { point ->
                        if (predicate(point.value)) {
                            if (runStart == null) runStart = point.timestamp
                            lastMatch = point.timestamp
                        } else if (runStart != null && lastMatch != null) {
                            val durationMinutes = ((lastMatch!! - runStart!!) / 60_000f + 5f).coerceAtLeast(5f)
                            if (durationMinutes >= 10f) {
                                episodeCount += 1
                                totalMinutes += durationMinutes
                            }
                            runStart = null
                            lastMatch = null
                        }
                    }
                    if (runStart != null && lastMatch != null) {
                        val durationMinutes = ((lastMatch!! - runStart!!) / 60_000f + 5f).coerceAtLeast(5f)
                        if (durationMinutes >= 10f) {
                            episodeCount += 1
                            totalMinutes += durationMinutes
                        }
                    }
                    val hoursPerDay = (totalMinutes / 60f) / days
                    val eventsPerDay = episodeCount / days
                    val avgDurationHours = if (episodeCount > 0) {
                        (totalMinutes / 60f) / episodeCount.toFloat()
                    } else {
                        0f
                    }
                    return Triple(hoursPerDay, eventsPerDay, avgDurationHours)
                }

                return thresholds.map { (label, predicate) ->
                    val (hoursPerDay, eventsPerDay, avgDurationHours) = evaluate(predicate)
                    ExposureTableRow(
                        label = label,
                        hoursPerDay = hoursPerDay,
                        eventsPerDay = eventsPerDay,
                        avgDurationHours = avgDurationHours
                    )
                }
            }

            fun drawDailyProfileMiniChart(
                canvas: android.graphics.Canvas,
                dayLabel: String,
                dayReadings: List<GlucosePoint>,
                top: Float,
                height: Float
            ) {
                val chartRect = RectF(margin, top, pageWidth - margin, top + height)
                drawCardShadow(canvas, chartRect, theme.cardRadius)
                fillPaint.color = colorSurface
                canvas.drawRoundRect(chartRect, theme.cardRadius, theme.cardRadius, fillPaint)
                borderPaint.color = colorBorder
                canvas.drawRoundRect(chartRect, theme.cardRadius, theme.cardRadius, borderPaint)
                canvas.drawText(dayLabel, chartRect.left + 16f, chartRect.top + 24f, bodyPaint)

                if (dayReadings.isEmpty()) {
                    canvas.drawText(context.getString(R.string.report_no_readings_day), chartRect.left + 16f, chartRect.top + 56f, mutedPaint)
                    return
                }

                val plotRect = RectF(chartRect.left + 56f, chartRect.top + 36f, chartRect.right - 84f, chartRect.bottom - 24f)
                val minY = 35f
                val maxY = 300f

                fun xFor(point: GlucosePoint): Float {
                    val local = java.time.Instant.ofEpochMilli(point.timestamp).atZone(java.time.ZoneId.systemDefault())
                    val hour = local.hour + (local.minute / 60f)
                    return plotRect.left + (hour / 24f) * plotRect.width()
                }
                fun yFor(value: Float): Float {
                    val ratio = ((value - minY) / (maxY - minY)).coerceIn(0f, 1f)
                    return plotRect.bottom - (ratio * plotRect.height())
                }

                listOf(minY, 70f, 120f, 180f, 250f, maxY).forEach { value ->
                    val yLine = yFor(value)
                    canvas.drawLine(plotRect.left, yLine, plotRect.right, yLine, linePaint)
                }
                val targetLowY = yFor(uiState.targets.lowMgDl)
                val targetHighY = yFor(uiState.targets.highMgDl)
                val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                thresholdPaint.color = colorDanger
                canvas.drawLine(plotRect.left, targetLowY, plotRect.right, targetLowY, thresholdPaint)
                thresholdPaint.color = colorWarn
                canvas.drawLine(plotRect.left, targetHighY, plotRect.right, targetHighY, thresholdPaint)

                val path = Path()
                dayReadings.sortedBy { it.timestamp }.forEachIndexed { index, point ->
                    val x = xFor(point)
                    val yPoint = yFor(point.value)
                    if (index == 0) path.moveTo(x, yPoint) else path.lineTo(x, yPoint)
                }
                val profilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = colorPrimary
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                canvas.drawPath(path, profilePaint)

                listOf(0, 6, 12, 18, 24).forEach { hour ->
                    val x = plotRect.left + (hour / 24f) * plotRect.width()
                    val label = String.format(Locale.getDefault(), "%02d:00", hour % 24)
                    val textWidth = metricLabelPaint.measureText(label)
                    canvas.drawText(label, x - (textWidth / 2f), plotRect.bottom + 18f, metricLabelPaint)
                }
            }

            // Page 1
            pageNumber += 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            var canvas = page.canvas
            val headerBottom = drawHeader(
                canvas = canvas,
                pageTitle = context.getString(R.string.report_page1_title),
                pageSubtitle = context.getString(
                    R.string.report_page1_subtitle,
                    rangeLabel,
                    summary.readingCount,
                    firstReadingText,
                    lastReadingText
                ),
                accentColor = colorPrimary,
                accentSoftColor = colorSecondarySoft
            )

            val patientCardBottom = reportPatientInfo?.let { info ->
                drawPatientInfoCard(canvas, info, headerBottom, colorPrimary)
            } ?: 0f
            var y = if (patientCardBottom > 0f) patientCardBottom + 24f else headerBottom + 6f
            val cardGap = 14f
            val cardWidth = ((pageWidth - (margin * 2f) - (cardGap * 3f)) / 4f)
            val cardHeight = 124f
            drawMetricCard(
                canvas,
                margin,
                y,
                cardWidth,
                cardHeight,
                context.getString(R.string.average_glucose),
                formatGlucose(summary.avgMgDl, withUnit = false),
                unitLabel,
                colorEmerald
            )
            drawMetricCard(
                canvas,
                margin + cardWidth + cardGap,
                y,
                cardWidth,
                cardHeight,
                context.getString(R.string.a1c_gmi_label),
                String.format(Locale.getDefault(), "%.1f%%", summary.gmiPercent),
                context.getString(R.string.report_estimated_a1c),
                colorSecondary
            )
            drawMetricCard(
                canvas,
                margin + (cardWidth + cardGap) * 2f,
                y,
                cardWidth,
                cardHeight,
                context.getString(R.string.time_in_range),
                formatPercent(summary.tir.inRangePercent),
                "${context.getString(R.string.gmi_target)} ${formatGlucose(uiState.targets.lowMgDl, false)}-${formatGlucose(uiState.targets.highMgDl, false)}",
                colorEmerald
            )
            drawMetricCard(
                canvas,
                margin + (cardWidth + cardGap) * 3f,
                y,
                cardWidth,
                cardHeight,
                "${context.getString(R.string.variability)} (${context.getString(R.string.cv)})",
                formatPercent(summary.cvPercent),
                "${context.getString(R.string.std_dev_short)} ${formatGlucose(summary.stdDevMgDl, true)}",
                colorWarn
            )

            val expectedReadings = (reportWindowDays * 288f).coerceAtLeast(1f)
            val readingsPerDay = summary.readingCount / reportWindowDays.toFloat()
            val capturePercent = (summary.readingCount / expectedReadings * 100f).coerceIn(0f, 100f)
            val burdenCardGap = 14f
            val burdenCardWidth = ((pageWidth - (margin * 2f) - (burdenCardGap * 2f)) / 3f)
            val burdenCardHeight = 100f

            y += cardHeight + 20f
            drawMetricCard(
                canvas,
                margin,
                y,
                burdenCardWidth,
                burdenCardHeight,
                context.getString(R.string.report_below_target),
                formatPercent(summary.tir.belowRangePercent),
                formatDailyDuration(summary.tir.belowRangePercent),
                colorDanger,
                dense = true
            )
            drawMetricCard(
                canvas,
                margin + burdenCardWidth + burdenCardGap,
                y,
                burdenCardWidth,
                burdenCardHeight,
                context.getString(R.string.report_above_target),
                formatPercent(summary.tir.aboveRangePercent),
                formatDailyDuration(summary.tir.aboveRangePercent),
                colorWarn,
                dense = true
            )
            drawMetricCard(
                canvas,
                margin + (burdenCardWidth + burdenCardGap) * 2f,
                y,
                burdenCardWidth,
                burdenCardHeight,
                context.getString(R.string.report_data_density),
                String.format(Locale.getDefault(), "%.0f/day", readingsPerDay),
                context.getString(R.string.report_capture_cadence, formatPercent(capturePercent)),
                colorPrimary,
                dense = true
            )

            y += burdenCardHeight + 30f
            y = drawSectionHeader(canvas, context.getString(R.string.report_tir_distribution), y, colorPrimary)

            val tirSegments = listOf(
                Triple(summary.tir.veryLowPercent, colorTirVeryLow, context.getString(R.string.very_low)),
                Triple(summary.tir.lowPercent, colorTirLow, context.getString(R.string.low)),
                Triple(summary.tir.inRangePercent, colorTirInRange, context.getString(R.string.in_range)),
                Triple(summary.tir.highPercent, colorTirHigh, context.getString(R.string.high)),
                Triple(summary.tir.veryHighPercent, colorTirVeryHigh, context.getString(R.string.very_high))
            )
            val tirBarWidth = 88f
            val tirBarHeight = 250f
            val tirBarRect = RectF(margin + 10f, y, margin + 10f + tirBarWidth, y + tirBarHeight)
            var segmentBottom = tirBarRect.bottom
            tirSegments.forEach { (percent, color, _) ->
                val height = tirBarRect.height() * (percent.coerceAtLeast(0f) / 100f)
                if (height > 0f) {
                    tirSegmentPaint.color = color
                    canvas.drawRect(tirBarRect.left, segmentBottom - height, tirBarRect.right, segmentBottom, tirSegmentPaint)
                    segmentBottom -= height
                }
            }
            borderPaint.color = colorBorder
            canvas.drawRoundRect(tirBarRect, 8f, 8f, borderPaint)

            val legendX = tirBarRect.right + 28f
            val legendColumnWidth = 320f
            val legendRowHeight = 34f
            val legendTop = y + 28f
            tirSegments.asReversed().forEachIndexed { index, (percent, color, label) ->
                val column = index / 3
                val row = index % 3
                val rowX = legendX + (column * legendColumnWidth)
                val rowY = legendTop + (row * legendRowHeight)
                tirSegmentPaint.color = color
                canvas.drawCircle(rowX + 8f, rowY - 6f, 6f, tirSegmentPaint)
                canvas.drawText(
                    "$label  ${formatPercent(percent)}  ·  ${formatHours(percent)}",
                    rowX + 22f,
                    rowY,
                    metricLabelPaint
                )
            }
            val summaryX = legendX + (legendColumnWidth * 2f)
            val summaryY = y + 42f
            if (summaryX < pageWidth - margin - 180f) {
                val tirGoalMet = if (summary.tir.inRangePercent >= 70f) {
                    context.getString(R.string.report_goal_met)
                } else {
                    context.getString(R.string.report_below_goal)
                }
                canvas.drawText(context.getString(R.string.report_tir_target), summaryX, summaryY, metricLabelPaint)
                canvas.drawText(formatPercent(summary.tir.inRangePercent), summaryX, summaryY + 34f, metricValuePaint)
                canvas.drawText(tirGoalMet, summaryX, summaryY + 62f, mutedPaint)
                canvas.drawText(
                    context.getString(R.string.report_below_duration, formatDailyDuration(summary.tir.belowRangePercent)),
                    summaryX,
                    summaryY + 94f,
                    metricLabelPaint
                )
                canvas.drawText(
                    context.getString(R.string.report_above_duration, formatDailyDuration(summary.tir.aboveRangePercent)),
                    summaryX,
                    summaryY + 122f,
                    metricLabelPaint
                )
            }

            val tirSectionBottom = maxOf(tirBarRect.bottom, legendTop + (legendRowHeight * 3f), summaryY + 132f)
            y = tirSectionBottom + 24f
            y = drawSectionHeader(canvas, context.getString(R.string.report_agp_section_title), y, colorPrimary)
            drawAgpChart(canvas, top = y, height = 560f, accentColor = colorPrimary)
            y += 590f

            canvas.drawText(
                context.getString(R.string.report_reference_targets),
                margin,
                y,
                subtitlePaint
            )
            drawFooter(canvas, colorPrimary)
            doc.finishPage(page)

            // Page 2
            pageNumber += 1
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = drawHeader(
                canvas = canvas,
                pageTitle = context.getString(R.string.report_page2_title),
                pageSubtitle = context.getString(R.string.report_page2_subtitle),
                accentColor = colorEmerald,
                accentSoftColor = colorPrimarySoft
            )
            y = drawSectionHeader(canvas, context.getString(R.string.report_daily_average_trend), y, colorEmerald)
            drawDailyChart(canvas, top = y, height = 460f)
            y += 494f

            y = drawSectionHeader(canvas, context.getString(R.string.report_detailed_metrics), y, colorEmerald)
            val rows = listOf(
                "${context.getString(R.string.median)} ${context.getString(R.string.glucose)}" to formatGlucose(summary.medianMgDl, true),
                context.getString(R.string.report_std_deviation) to formatGlucose(summary.stdDevMgDl, true),
                context.getString(R.string.gvi) to String.format(Locale.getDefault(), "%.2f (${context.getString(R.string.stability)} %.0f%%)", summary.gvi.value, summary.gvi.stability),
                context.getString(R.string.report_psg_baseline) to "${formatGlucose(summary.psg.baselineMgDl, true)} (${context.getString(R.string.report_confidence_value, String.format(Locale.getDefault(), "%.0f%%", summary.psg.confidence))})",
                context.getString(R.string.report_min_max_glucose) to "${formatGlucose(summary.minMgDl, true)} / ${formatGlucose(summary.maxMgDl, true)}",
                context.getString(R.string.report_target_low_high) to "${formatGlucose(uiState.targets.lowMgDl, true)} / ${formatGlucose(uiState.targets.highMgDl, true)}",
                context.getString(R.string.report_very_low_high) to "${formatGlucose(uiState.targets.veryLowMgDl, true)} / ${formatGlucose(uiState.targets.veryHighMgDl, true)}"
            )
            rows.forEach { (label, value) ->
                canvas.drawText(label, margin, y, bodyPaint)
                val valueWidth = bodyPaint.measureText(value)
                canvas.drawText(value, pageWidth - margin - valueWidth, y, bodyPaint)
                y += 30f
            }

            y += 14f
            y = drawSectionHeader(canvas, context.getString(R.string.report_clinical_insights), y, colorEmerald)
            val insights = summary.insights.ifEmpty {
                listOf(
                    StatsInsight(
                        context.getString(R.string.report_no_generated_insights_title),
                        context.getString(R.string.report_no_generated_insights_message),
                        InsightSeverity.POSITIVE
                    )
                )
            }
            insights.take(8).forEach { insight ->
                val tone = when (insight.severity) {
                    InsightSeverity.POSITIVE -> theme.insightPositive
                    InsightSeverity.ATTENTION -> theme.insightAttention
                    InsightSeverity.CAUTION -> theme.insightCaution
                }
                tirSegmentPaint.color = tone
                canvas.drawCircle(margin + 8f, y - 8f, 6f, tirSegmentPaint)
                canvas.drawText(insight.title, margin + 24f, y, bodyPaint)
                y += 24f
                val messageWords = insight.message.split(" ")
                var line = StringBuilder()
                val maxWidth = pageWidth - margin * 2f - 24f
                messageWords.forEach { word ->
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (mutedPaint.measureText(candidate) > maxWidth) {
                        canvas.drawText(line.toString(), margin + 24f, y, mutedPaint)
                        y += 22f
                        line = StringBuilder(word)
                    } else {
                        line = StringBuilder(candidate)
                    }
                }
                if (line.isNotEmpty()) {
                    canvas.drawText(line.toString(), margin + 24f, y, mutedPaint)
                    y += 24f
                }
                y += 6f
            }

            y += 6f
            y = drawSectionHeader(canvas, context.getString(R.string.report_agp_consensus_title), y, colorEmerald)
            y = drawWrappedText(
                canvas = canvas,
                text = context.getString(R.string.report_agp_consensus_body),
                left = margin,
                top = y,
                maxWidth = pageWidth - margin * 2f,
                lineHeight = 22f,
                paint = mutedPaint
            )

            drawFooter(canvas, colorEmerald)
            doc.finishPage(page)

            // Page 3
            pageNumber += 1
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = drawHeader(
                canvas = canvas,
                pageTitle = context.getString(R.string.report_page3_title),
                pageSubtitle = context.getString(R.string.report_page3_subtitle),
                accentColor = colorSecondary,
                accentSoftColor = colorSecondarySoft
            )
            y = drawSectionHeader(canvas, context.getString(R.string.report_hourly_agp_table), y, colorSecondary)

            val tableLeft = margin
            val tableRight = pageWidth - margin
            val tableWidth = tableRight - tableLeft
            val agpRowHeight = 28f
            val agpHourX = tableLeft + 12f
            val agpSamplesX = tableLeft + tableWidth * 0.18f
            val agpMedianX = tableLeft + tableWidth * 0.32f
            val agpIqrX = tableLeft + tableWidth * 0.50f
            val agpBandX = tableLeft + tableWidth * 0.72f
            fillPaint.color = tableHeaderFillColor
            canvas.drawRoundRect(RectF(tableLeft, y, tableRight, y + agpRowHeight), 8f, 8f, fillPaint)
            canvas.drawText(context.getString(R.string.report_hour), agpHourX, y + 20f, bodyPaint)
            canvas.drawText(context.getString(R.string.report_samples), agpSamplesX, y + 20f, bodyPaint)
            canvas.drawText(context.getString(R.string.median), agpMedianX, y + 20f, bodyPaint)
            canvas.drawText(context.getString(R.string.report_iqr), agpIqrX, y + 20f, bodyPaint)
            canvas.drawText(context.getString(R.string.report_p10_p90), agpBandX, y + 20f, bodyPaint)
            y += agpRowHeight + 4f

            val agpRows = summary.agpByHour.sortedBy { it.hour }
            if (agpRows.isEmpty()) {
                canvas.drawText(context.getString(R.string.report_no_hourly_agp), tableLeft + 8f, y + 20f, mutedPaint)
                y += 42f
            } else {
                agpRows.forEachIndexed { index, bin ->
                    if (index % 2 == 0) {
                        fillPaint.color = tableAltRowFillColor
                        canvas.drawRect(tableLeft, y, tableRight, y + agpRowHeight, fillPaint)
                    }
                    val iqrText = if (bin.p25MgDl != null && bin.p75MgDl != null) {
                        "${formatGlucose(bin.p25MgDl, false)}-${formatGlucose(bin.p75MgDl, false)}"
                    } else {
                        "-"
                    }
                    val p10p90Text = if (bin.p10MgDl != null && bin.p90MgDl != null) {
                        "${formatGlucose(bin.p10MgDl, false)}-${formatGlucose(bin.p90MgDl, false)}"
                    } else {
                        "-"
                    }
                    canvas.drawText(String.format(Locale.getDefault(), "%02d:00", bin.hour), agpHourX, y + 20f, tableValuePaint)
                    canvas.drawText(bin.sampleCount.toString(), agpSamplesX, y + 20f, tableValuePaint)
                    canvas.drawText(formatNullableGlucose(bin.medianMgDl), agpMedianX, y + 20f, tableValuePaint)
                    canvas.drawText(iqrText, agpIqrX, y + 20f, tableValuePaint)
                    canvas.drawText(p10p90Text, agpBandX, y + 20f, tableValuePaint)
                    y += agpRowHeight
                }
            }

            y += 16f
            y = drawSectionHeader(canvas, context.getString(R.string.report_daily_outcomes_table), y, colorSecondary)

            val dailyRowHeight = 30f
            val dailyDateX = tableLeft + 12f
            val dailyAvgX = tableLeft + tableWidth * 0.42f
            val dailyTirX = tableLeft + tableWidth * 0.62f
            val dailyCountX = tableLeft + tableWidth * 0.80f
            fillPaint.color = tableHeaderFillColor
            canvas.drawRoundRect(RectF(tableLeft, y, tableRight, y + dailyRowHeight), 8f, 8f, fillPaint)
            canvas.drawText(context.getString(R.string.date), dailyDateX, y + 21f, bodyPaint)
            canvas.drawText(context.getString(R.string.average_glucose), dailyAvgX, y + 21f, bodyPaint)
            canvas.drawText(context.getString(R.string.tir), dailyTirX, y + 21f, bodyPaint)
            canvas.drawText(context.getString(R.string.readings), dailyCountX, y + 21f, bodyPaint)
            y += dailyRowHeight + 4f

            val dailyRows = summary.dailyStats.sortedByDescending { it.date }
            val maxDailyRows = 26
            dailyRows.take(maxDailyRows).forEachIndexed { index, day ->
                if (index % 2 == 0) {
                    fillPaint.color = tableAltRowFillColor
                    canvas.drawRect(tableLeft, y, tableRight, y + dailyRowHeight, fillPaint)
                }
                canvas.drawText(day.date.toString(), dailyDateX, y + 21f, tableValuePaint)
                canvas.drawText(formatGlucose(day.averageMgDl, withUnit = false), dailyAvgX, y + 21f, tableValuePaint)
                canvas.drawText(formatPercent(day.inRangePercent), dailyTirX, y + 21f, tableValuePaint)
                canvas.drawText(day.readingCount.toString(), dailyCountX, y + 21f, tableValuePaint)
                y += dailyRowHeight
            }
            if (dailyRows.size > maxDailyRows) {
                y += 8f
                canvas.drawText(
                    context.getString(R.string.report_showing_latest_days, maxDailyRows, dailyRows.size),
                    margin,
                    y,
                    subtitlePaint
                )
            }
            drawFooter(canvas, colorSecondary)
            doc.finishPage(page)

            // Page 4
            pageNumber += 1
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = drawHeader(
                canvas = canvas,
                pageTitle = context.getString(R.string.report_page4_title),
                pageSubtitle = context.getString(R.string.report_page4_subtitle),
                accentColor = colorWarn,
                accentSoftColor = colorPrimarySoft
            )
            y = drawSectionHeader(canvas, context.getString(R.string.report_hypo_hyper_events), y, colorWarn)

            val eventRows = buildExposureRows(reportWindowDays)
            val eventLeft = margin
            val eventRight = pageWidth - margin
            val eventWidth = eventRight - eventLeft
            val eventRowHeight = 30f
            val thresholdX = eventLeft + 12f
            val hoursX = eventLeft + eventWidth * 0.40f
            val eventsX = eventLeft + eventWidth * 0.62f
            val durationX = eventLeft + eventWidth * 0.80f
            fillPaint.color = tableHeaderFillColor
            canvas.drawRoundRect(RectF(eventLeft, y, eventRight, y + eventRowHeight), 8f, 8f, fillPaint)
            canvas.drawText(context.getString(R.string.threshold), thresholdX, y + 21f, bodyPaint)
            canvas.drawText(context.getString(R.string.report_hours_per_day), hoursX, y + 21f, bodyPaint)
            canvas.drawText(context.getString(R.string.report_events_per_day), eventsX, y + 21f, bodyPaint)
            canvas.drawText(context.getString(R.string.report_avg_duration_h), durationX, y + 21f, bodyPaint)
            y += eventRowHeight + 4f

            eventRows.forEachIndexed { index, row ->
                if (index % 2 == 0) {
                    fillPaint.color = tableAltRowFillColor
                    canvas.drawRect(eventLeft, y, eventRight, y + eventRowHeight, fillPaint)
                }
                canvas.drawText(row.label, thresholdX, y + 21f, tableValuePaint)
                canvas.drawText(String.format(Locale.getDefault(), "%.2f", row.hoursPerDay), hoursX, y + 21f, tableValuePaint)
                canvas.drawText(String.format(Locale.getDefault(), "%.2f", row.eventsPerDay), eventsX, y + 21f, tableValuePaint)
                canvas.drawText(String.format(Locale.getDefault(), "%.2f", row.avgDurationHours), durationX, y + 21f, tableValuePaint)
                y += eventRowHeight
            }

            y += 16f
            y = drawSectionHeader(canvas, context.getString(R.string.report_daily_glucose_profiles), y, colorWarn)

            val zone = java.time.ZoneId.systemDefault()
            val dayFormatter = java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
            val dailyProfiles = uiState.readings
                .groupBy { point ->
                    java.time.Instant.ofEpochMilli(point.timestamp).atZone(zone).toLocalDate()
                }
                .toSortedMap()
                .entries
                .toList()
                .takeLast(3)
                .reversed()
            val profileHeight = 240f
            val profileGap = 16f
            dailyProfiles.forEach { (date, points) ->
                if (y + profileHeight > pageHeight - margin - 36f) return@forEach
                drawDailyProfileMiniChart(
                    canvas = canvas,
                    dayLabel = date.format(dayFormatter),
                    dayReadings = points,
                    top = y,
                    height = profileHeight
                )
                y += profileHeight + profileGap
            }

            drawFooter(canvas, colorWarn)
            doc.finishPage(page)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                doc.writeTo(out)
            } ?: throw IOException("Unable to open destination file")
            doc.close()
        }.onFailure {
            Log.e(TAG, "exportComprehensivePdf failed", it)
        }
    }

    suspend fun publishInteractiveReport(
        workerBaseUrl: String,
        uiState: StatsUiState,
        reportDays: Int? = null,
        patientInfo: PatientInfo? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = workerBaseUrl.trim().trimEnd('/')
            require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
                "Invalid Cloudflare Worker URL"
            }

            val newResponse = requestJson("$baseUrl/api/reports/new", "POST", body = "{}")
            val putUrl = newResponse.getString("putUrl")
            val viewUrl = newResponse.getString("viewUrl")

            val payload = buildInteractivePayload(uiState, reportDays, patientInfo)
            val gzippedPayload = gzip(payload.toByteArray(Charsets.UTF_8))
            putBinary(putUrl, gzippedPayload)

            viewUrl
        }.onFailure {
            Log.e(TAG, "publishInteractiveReport failed", it)
        }
    }

    private fun buildInteractivePayload(uiState: StatsUiState, reportDays: Int?, patientInfo: PatientInfo?): String {
        val summary = uiState.summary
        val payloadRangeDays = reportDays
            ?: uiState.selectedRange?.days?.takeIf { it > 0 }
            ?: uiState.activeRange?.daySpan
            ?: 0
        val payloadPatientInfo = patientInfo?.sanitized()?.takeIf { it.hasContent() }
        val root = JSONObject()
            .put("generatedAt", System.currentTimeMillis())
            .put("unit", uiState.unit.name.lowercase(Locale.US))
            .put("rangeDays", payloadRangeDays)
            .put(
                "targets",
                JSONObject()
                    .put("lowMgDl", uiState.targets.lowMgDl)
                    .put("highMgDl", uiState.targets.highMgDl)
                    .put("veryLowMgDl", uiState.targets.veryLowMgDl)
                    .put("veryHighMgDl", uiState.targets.veryHighMgDl)
            )

        if (payloadPatientInfo != null) {
            root.put(
                "patientInfo",
                JSONObject()
                    .put("name", payloadPatientInfo.name)
                    .put("identifier", payloadPatientInfo.identifier)
                    .put("dateOfBirth", payloadPatientInfo.dateOfBirth)
                    .put("clinician", payloadPatientInfo.clinician)
            )
        }
        root.put(
            "summary",
            JSONObject()
                .put("readingCount", summary.readingCount)
                .put("avgMgDl", summary.avgMgDl)
                .put("medianMgDl", summary.medianMgDl)
                .put("stdDevMgDl", summary.stdDevMgDl)
                .put("cvPercent", summary.cvPercent)
                .put("gmiPercent", summary.gmiPercent)
                .put("gvi", summary.gvi.value)
                .put("psgBaselineMgDl", summary.psg.baselineMgDl)
                .put("minMgDl", summary.minMgDl)
                .put("maxMgDl", summary.maxMgDl)
                .put("firstTimestamp", summary.firstTimestamp)
                .put("lastTimestamp", summary.lastTimestamp)
                .put(
                    "tir",
                    JSONObject()
                        .put("veryLow", summary.tir.veryLowPercent)
                        .put("low", summary.tir.lowPercent)
                        .put("inRange", summary.tir.inRangePercent)
                        .put("high", summary.tir.highPercent)
                        .put("veryHigh", summary.tir.veryHighPercent)
                )
        )

        val agpArray = JSONArray()
        summary.agpByHour.forEach { bin ->
            agpArray.put(
                JSONObject()
                    .put("hour", bin.hour)
                    .put("p10MgDl", bin.p10MgDl)
                    .put("p25MgDl", bin.p25MgDl)
                    .put("medianMgDl", bin.medianMgDl)
                    .put("p75MgDl", bin.p75MgDl)
                    .put("p90MgDl", bin.p90MgDl)
                    .put("sampleCount", bin.sampleCount)
            )
        }
        root.put("agpByHour", agpArray)

        val dailyArray = JSONArray()
        summary.dailyStats.forEach { day ->
            dailyArray.put(
                JSONObject()
                    .put("date", day.date.toString())
                    .put("averageMgDl", day.averageMgDl)
                    .put("inRangePercent", day.inRangePercent)
                    .put("readingCount", day.readingCount)
            )
        }
        root.put("dailyStats", dailyArray)

        val insightsArray = JSONArray()
        summary.insights.forEach { insight ->
            insightsArray.put(
                JSONObject()
                    .put("title", insight.title)
                    .put("message", insight.message)
                    .put("severity", insight.severity.name)
            )
        }
        root.put("insights", insightsArray)

        val readingsArray = JSONArray()
        uiState.readings.forEach { point ->
            readingsArray.put(
                JSONObject()
                    .put("timestamp", point.timestamp)
                    .put("valueMgDl", point.value)
                    .put("rawValueMgDl", point.rawValue)
                    .put("rate", point.rate ?: JSONObject.NULL)
            )
        }
        root.put("readings", readingsArray)

        return root.toString()
    }

    private fun requestJson(url: String, method: String, body: String? = null): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 20_000
            doInput = true
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val payload = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code: $payload")
            }
            return JSONObject(payload)
        } finally {
            connection.disconnect()
        }
    }

    private fun putBinary(url: String, payload: ByteArray) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 20_000
            readTimeout = 30_000
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Content-Encoding", "gzip")
            setRequestProperty("Content-Length", payload.size.toString())
        }
        try {
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Upload failed: HTTP $code: $errorText")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzip ->
            gzip.write(bytes)
        }
        return bos.toByteArray()
    }
}
