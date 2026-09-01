package tk.glucodata.data.journal

import kotlin.math.pow
import kotlin.math.roundToInt

enum class JournalEntryType(val storageValue: String) {
    INSULIN("insulin"),
    CARBS("carbs"),
    FINGERSTICK("fingerstick"),
    ACTIVITY("activity"),
    NOTE("note");

    companion object {
        fun fromStorage(value: String?): JournalEntryType {
            return entries.firstOrNull { it.storageValue == value } ?: NOTE
        }
    }
}

enum class JournalIntensity(val storageValue: String) {
    LIGHT("light"),
    MODERATE("moderate"),
    INTENSE("intense");

    companion object {
        fun fromStorage(value: String?): JournalIntensity? {
            return entries.firstOrNull { it.storageValue == value }
        }
    }
}

enum class JournalEntrySource(val storageValue: String) {
    MANUAL("manual"),
    HEALTH_CONNECT("health_connect"),
    METER("meter"),
    PEN("pen"),
    AAPS("aaps"),
    NIGHTSCOUT("nightscout"),
    API("api"),
    CLONE("clone"),
    CLONE_LOCAL_ICE("clone_local_ice"),
    CLONE_TURN("clone_turn");

    companion object {
        fun fromStorage(value: String?): JournalEntrySource {
            return entries.firstOrNull { it.storageValue == value } ?: MANUAL
        }
    }
}

data class JournalEntry(
    val id: Long,
    val timestamp: Long,
    val sensorSerial: String?,
    val type: JournalEntryType,
    val title: String,
    val note: String?,
    val amount: Float?,
    val glucoseValueMgDl: Float?,
    val durationMinutes: Int?,
    val intensity: JournalIntensity?,
    val insulinPresetId: Long?,
    val foodId: Long?,
    val proteinGrams: Float?,
    val fatGrams: Float?,
    val source: JournalEntrySource,
    val sourceRecordId: String?,
    val createdAt: Long,
    val updatedAt: Long
)

data class JournalEntryInput(
    val id: Long? = null,
    val timestamp: Long,
    val sensorSerial: String? = null,
    val type: JournalEntryType,
    val title: String,
    val note: String? = null,
    val amount: Float? = null,
    val glucoseValueMgDl: Float? = null,
    val durationMinutes: Int? = null,
    val intensity: JournalIntensity? = null,
    val insulinPresetId: Long? = null,
    val foodId: Long? = null,
    val proteinGrams: Float? = null,
    val fatGrams: Float? = null,
    val source: JournalEntrySource = JournalEntrySource.MANUAL,
    val sourceRecordId: String? = null,
    val nsRemoteId: String? = null
)

data class JournalFood(
    val id: Long,
    val displayName: String,
    val carbsGrams: Float,
    val proteinGrams: Float?,
    val fatGrams: Float?,
    val absorptionMinutes: Int,
    val accentColor: Int,
    val isBuiltIn: Boolean,
    val isArchived: Boolean,
    val sortOrder: Int
)

data class JournalFoodInput(
    val id: Long? = null,
    val displayName: String,
    val carbsGrams: Float,
    val proteinGrams: Float? = null,
    val fatGrams: Float? = null,
    val absorptionMinutes: Int,
    val accentColor: Int,
    val isBuiltIn: Boolean = false,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0
)

data class JournalCurvePoint(
    val minute: Int,
    val activity: Float
)

data class JournalInsulinPreset(
    val id: Long,
    val displayName: String,
    val onsetMinutes: Int,
    val durationMinutes: Int,
    val accentColor: Int,
    val curveJson: String,
    val isBuiltIn: Boolean,
    val isArchived: Boolean,
    val countsTowardIob: Boolean,
    val sortOrder: Int,
    val useForCalculation: Boolean = true
) {
    val curvePoints: List<JournalCurvePoint> = resolveJournalCurve(curveJson, onsetMinutes, durationMinutes)

    fun activeStartAt(timestamp: Long): Long {
        val startMinute = curvePoints.firstOrNull { it.activity > 0.01f }?.minute ?: onsetMinutes
        return timestamp + (startMinute.coerceAtLeast(0) * 60_000L)
    }

    fun activeEndAt(timestamp: Long): Long {
        val endMinute = curvePoints.lastOrNull()?.minute ?: durationMinutes
        return timestamp + (endMinute.coerceAtLeast(onsetMinutes) * 60_000L)
    }

    fun activityFractionAt(doseTimestamp: Long, atMillis: Long): Float {
        val elapsedMinutes = ((atMillis - doseTimestamp) / 60_000f).coerceAtLeast(0f)
        return interpolateJournalCurve(curvePoints, elapsedMinutes)
    }
}

data class JournalInsulinPresetInput(
    val id: Long? = null,
    val displayName: String,
    val onsetMinutes: Int,
    val durationMinutes: Int,
    val accentColor: Int,
    val curveJson: String = "",
    val isBuiltIn: Boolean = false,
    val isArchived: Boolean = false,
    val countsTowardIob: Boolean = true,
    val sortOrder: Int = 0,
    val useForCalculation: Boolean = true
)

data class JournalChartMarker(
    val entryId: Long,
    val timestamp: Long,
    val type: JournalEntryType,
    val title: String,
    val accentColor: Int,
    val badgeText: String,
    val detailText: String,
    val amount: Float? = null,
    val chartGlucoseValue: Float? = null,
    val chartYFraction: Float? = null,
    val durationMinutes: Int? = null,
    val curvePoints: List<JournalCurvePoint> = emptyList(),
    val activeStartMillis: Long? = null,
    val activeEndMillis: Long? = null
)

data class JournalActiveInsulinSummary(
    val activeEntryCount: Int,
    val totalUnits: Float,
    val weightedActivityPercent: Int,
    val activeUntil: Long?,
    val iobUnits: Float = 0f,
    val eiobUnits: Float = 0f
)

enum class JournalBuiltInCurveProfile {
    RAPID_GENERIC,
    LONG_BASAL_GENERIC,
    HUMAN_REGULAR,
    ASPART,
    LISPRO,
    GLULISINE,
    FIASP,
    URLI,
    AFREZZA,
    NPH,
    ULTRA_LONG_BASAL
}

fun serializeJournalCurve(points: List<JournalCurvePoint>): String {
    return normalizeJournalCurvePoints(points).joinToString(";") { point ->
        "${point.minute}:${formatJournalCurveValue(point.activity)}"
    }
}

fun parseJournalCurve(serialized: String?): List<JournalCurvePoint> {
    if (serialized.isNullOrBlank()) return emptyList()
    return serialized.split(';').mapNotNull { token ->
        val minutePart = token.substringBefore(':', "").trim()
        val activityPart = token.substringAfter(':', "").trim()
        val minute = minutePart.toIntOrNull() ?: return@mapNotNull null
        val activity = activityPart.toFloatOrNull() ?: return@mapNotNull null
        JournalCurvePoint(
            minute = minute.coerceAtLeast(0),
            activity = activity.coerceIn(0f, 1f)
        )
    }
}

fun normalizeJournalCurvePoints(
    points: List<JournalCurvePoint>,
    fallbackDurationMinutes: Int = points.maxOfOrNull { it.minute } ?: 0
): List<JournalCurvePoint> {
    val cleaned = points
        .map {
            JournalCurvePoint(
                minute = it.minute.coerceAtLeast(0),
                activity = it.activity.coerceIn(0f, 1f)
            )
        }
        .sortedBy { it.minute }
        .distinctBy { it.minute }
        .toMutableList()

    if (cleaned.isEmpty()) {
        val safeDuration = fallbackDurationMinutes.coerceAtLeast(240)
        return listOf(
            JournalCurvePoint(0, 0f),
            JournalCurvePoint((safeDuration * 0.2f).roundToInt(), 0.2f),
            JournalCurvePoint((safeDuration * 0.35f).roundToInt(), 1f),
            JournalCurvePoint((safeDuration * 0.7f).roundToInt(), 0.35f),
            JournalCurvePoint(safeDuration, 0f)
        )
    }

    if (cleaned.first().minute != 0) {
        cleaned.add(0, JournalCurvePoint(0, 0f))
    } else {
        cleaned[0] = cleaned.first().copy(activity = 0f)
    }

    val lastMinute = cleaned.last().minute.coerceAtLeast(fallbackDurationMinutes)
    if (cleaned.last().activity > 0f) {
        cleaned.add(JournalCurvePoint(lastMinute.coerceAtLeast(cleaned.last().minute), 0f))
    } else if (cleaned.last().minute < lastMinute) {
        cleaned.add(JournalCurvePoint(lastMinute, 0f))
    }

    return cleaned
}

fun defaultJournalCurve(onsetMinutes: Int, durationMinutes: Int): List<JournalCurvePoint> {
    val safeOnset = onsetMinutes.coerceAtLeast(0)
    val safeDuration = durationMinutes.coerceAtLeast((safeOnset + 30).coerceAtLeast(180))
    val peakMinute = (safeOnset + ((safeDuration - safeOnset) * 0.24f)).roundToInt().coerceAtLeast(safeOnset + 15)
    val settleMinute = (safeOnset + ((safeDuration - safeOnset) * 0.72f)).roundToInt().coerceAtMost(safeDuration - 15)
    return normalizeJournalCurvePoints(
        listOf(
            JournalCurvePoint(0, 0f),
            JournalCurvePoint(safeOnset, 0.2f),
            JournalCurvePoint(peakMinute, 1f),
            JournalCurvePoint(settleMinute, 0.38f),
            JournalCurvePoint(safeDuration, 0f)
        ),
        fallbackDurationMinutes = safeDuration
    )
}

fun resolveJournalCurve(
    curveJson: String?,
    onsetMinutes: Int,
    durationMinutes: Int
): List<JournalCurvePoint> {
    val parsed = parseJournalCurve(curveJson)
    return if (parsed.isNotEmpty()) {
        normalizeJournalCurvePoints(parsed, fallbackDurationMinutes = durationMinutes)
    } else {
        defaultJournalCurve(onsetMinutes, durationMinutes)
    }
}

private fun interpolateJournalCurve(points: List<JournalCurvePoint>, minute: Float): Float {
    if (points.isEmpty()) return 0f
    if (minute <= points.first().minute.toFloat()) return points.first().activity
    if (minute >= points.last().minute.toFloat()) return points.last().activity

    val upperIndex = points.indexOfFirst { it.minute >= minute }.takeIf { it >= 0 } ?: return 0f
    val upper = points[upperIndex]
    val lower = points.getOrNull(upperIndex - 1) ?: return upper.activity
    val span = (upper.minute - lower.minute).toFloat().coerceAtLeast(1f)
    val progress = ((minute - lower.minute) / span).coerceIn(0f, 1f)
    return lower.activity + ((upper.activity - lower.activity) * progress)
}

private fun formatJournalCurveValue(value: Float): String {
    return ((value.coerceIn(0f, 1f) * 100f).roundToInt() / 100f).toString()
}

fun builtInJournalCurve(profile: JournalBuiltInCurveProfile): List<JournalCurvePoint> {
    return when (profile) {
        JournalBuiltInCurveProfile.RAPID_GENERIC -> buildPolynomialCurve(
            spanMinutes = 360,
            peakMinutes = 40,
            coefficients = doubleArrayOf(0.0, 34.05, -185.9, 439.8, -536.6, 327.1, -78.44)
        )
        JournalBuiltInCurveProfile.LONG_BASAL_GENERIC -> buildTimingCurve(
            durationMinutes = 1440,
            onsetMinutes = 90,
            peakMinutes = 600,
            shoulderFraction = 0.7f,
            lateTailFraction = 0.42f
        )
        JournalBuiltInCurveProfile.HUMAN_REGULAR -> buildPolynomialCurve(
            spanMinutes = 510,
            peakMinutes = 140,
            coefficients = doubleArrayOf(0.0, 11.7, 2.329, -124.5, 256.1, -206.4, 60.84)
        )
        JournalBuiltInCurveProfile.ASPART -> buildPolynomialCurve(
            spanMinutes = 360,
            peakMinutes = 40,
            coefficients = doubleArrayOf(0.0, 34.05, -185.9, 439.8, -536.6, 327.1, -78.44)
        )
        JournalBuiltInCurveProfile.LISPRO -> buildPolynomialCurve(
            spanMinutes = 330,
            peakMinutes = 60,
            coefficients = doubleArrayOf(0.0, 31.6, -144.7, 259.5, -211.3, 64.93, 0.0)
        )
        JournalBuiltInCurveProfile.GLULISINE -> buildPolynomialCurve(
            spanMinutes = 480,
            peakMinutes = 65,
            coefficients = doubleArrayOf(0.0, 51.75, -325.2, 831.0, -1053.0, 655.7, -159.9)
        )
        JournalBuiltInCurveProfile.FIASP -> buildPolynomialCurve(
            spanMinutes = 360,
            peakMinutes = 55,
            coefficients = doubleArrayOf(0.0, 41.25, -235.5, 561.2, -675.6, 403.4, -94.76)
        )
        JournalBuiltInCurveProfile.URLI -> buildTimingCurve(
            durationMinutes = 370,
            onsetMinutes = 15,
            peakMinutes = 95,
            shoulderFraction = 0.82f,
            lateTailFraction = 0.28f
        )
        JournalBuiltInCurveProfile.AFREZZA -> buildTimingCurve(
            durationMinutes = 210,
            onsetMinutes = 12,
            peakMinutes = 45,
            shoulderFraction = 0.52f,
            lateTailFraction = 0.14f
        )
        JournalBuiltInCurveProfile.NPH -> buildTimingCurve(
            durationMinutes = 720,
            onsetMinutes = 90,
            peakMinutes = 360,
            shoulderFraction = 0.76f,
            lateTailFraction = 0.36f
        )
        JournalBuiltInCurveProfile.ULTRA_LONG_BASAL -> buildTimingCurve(
            durationMinutes = 2160,
            onsetMinutes = 180,
            peakMinutes = 960,
            shoulderFraction = 0.66f,
            lateTailFraction = 0.3f
        )
    }
}

private fun buildPolynomialCurve(
    spanMinutes: Int,
    peakMinutes: Int,
    coefficients: DoubleArray
): List<JournalCurvePoint> {
    val anchors = buildAnchorMinutes(spanMinutes, peakMinutes)
    val sampledMax = (0..spanMinutes step 5).maxOfOrNull { minute ->
        evaluateNormalizedPolynomial(coefficients, minute.toFloat() / spanMinutes.toFloat())
    }?.coerceAtLeast(0.001f) ?: 1f

    return normalizeJournalCurvePoints(
        anchors.map { minute ->
            val activity = if (minute == 0 || minute == spanMinutes) {
                0f
            } else {
                (evaluateNormalizedPolynomial(coefficients, minute.toFloat() / spanMinutes.toFloat()) / sampledMax)
                    .coerceIn(0f, 1f)
            }
            JournalCurvePoint(minute = minute, activity = activity)
        },
        fallbackDurationMinutes = spanMinutes
    )
}

private fun buildTimingCurve(
    durationMinutes: Int,
    onsetMinutes: Int,
    peakMinutes: Int,
    shoulderFraction: Float,
    lateTailFraction: Float
): List<JournalCurvePoint> {
    val safeDuration = durationMinutes.coerceAtLeast((peakMinutes + 30).coerceAtLeast(120))
    val riseMinute = (onsetMinutes + ((peakMinutes - onsetMinutes) * 0.45f)).roundToInt()
        .coerceIn(onsetMinutes, peakMinutes)
    val shoulderMinute = (peakMinutes + ((safeDuration - peakMinutes) * 0.28f)).roundToInt()
        .coerceIn(peakMinutes, safeDuration)
    val tailMinute = (peakMinutes + ((safeDuration - peakMinutes) * 0.68f)).roundToInt()
        .coerceIn(shoulderMinute, safeDuration)

    return normalizeJournalCurvePoints(
        listOf(
            JournalCurvePoint(0, 0f),
            JournalCurvePoint(onsetMinutes.coerceAtLeast(0), 0.16f),
            JournalCurvePoint(riseMinute, 0.62f),
            JournalCurvePoint(peakMinutes.coerceAtLeast(onsetMinutes + 1), 1f),
            JournalCurvePoint(shoulderMinute, shoulderFraction.coerceIn(0.2f, 0.95f)),
            JournalCurvePoint(tailMinute, lateTailFraction.coerceIn(0.05f, 0.6f)),
            JournalCurvePoint(safeDuration, 0f)
        ),
        fallbackDurationMinutes = safeDuration
    )
}

private fun buildAnchorMinutes(
    spanMinutes: Int,
    peakMinutes: Int
): List<Int> {
    val tailSpan = (spanMinutes - peakMinutes).coerceAtLeast(1)
    return listOf(
        0,
        (peakMinutes * 0.35f).roundToInt(),
        (peakMinutes * 0.7f).roundToInt(),
        peakMinutes,
        (peakMinutes + (tailSpan * 0.18f)).roundToInt(),
        (peakMinutes + (tailSpan * 0.42f)).roundToInt(),
        (peakMinutes + (tailSpan * 0.7f)).roundToInt(),
        spanMinutes
    ).map { it.coerceIn(0, spanMinutes) }
        .distinct()
        .sorted()
}

private fun evaluateNormalizedPolynomial(coefficients: DoubleArray, t: Float): Float {
    val normalizedTime = t.coerceIn(0f, 1f).toDouble()
    var total = 0.0
    coefficients.forEachIndexed { power, coefficient ->
        total += coefficient * normalizedTime.pow(power)
    }
    return total.coerceAtLeast(0.0).toFloat()
}
