package tk.glucodata.data.prediction

import android.content.SharedPreferences
import java.util.Calendar
import java.util.TimeZone

data class PredictionModelParameters(
    val carbRatioGramsPerUnit: Float,
    val insulinSensitivityMgDlPerUnit: Float,
    val carbAbsorptionGramsPerHour: Float = PredictionModelProfile.DEFAULT_CARB_ABSORPTION_GRAMS_PER_HOUR
)

data class PredictionModelBlock(
    val startMinuteOfDay: Int,
    val carbRatioGramsPerUnit: Float,
    val insulinSensitivityMgDlPerUnit: Float,
    val carbAbsorptionGramsPerHour: Float
) {
    val parameters: PredictionModelParameters
        get() = PredictionModelParameters(
            carbRatioGramsPerUnit = carbRatioGramsPerUnit,
            insulinSensitivityMgDlPerUnit = insulinSensitivityMgDlPerUnit,
            carbAbsorptionGramsPerHour = carbAbsorptionGramsPerHour
        )
}

@ConsistentCopyVisibility
data class PredictionModelProfile private constructor(
    val blocks: List<PredictionModelBlock>
) {
    fun parametersAt(
        timestamp: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): PredictionModelParameters {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = timestamp }
        return parametersAtMinute(
            calendar.get(Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR + calendar.get(Calendar.MINUTE)
        )
    }

    fun parametersAtMinute(minuteOfDay: Int): PredictionModelParameters {
        val minute = minuteOfDay.coerceIn(0, LAST_MINUTE_OF_DAY)
        return blocks.lastOrNull { it.startMinuteOfDay <= minute }?.parameters
            ?: blocks.first().parameters
    }

    fun addBlock(startMinuteOfDay: Int): PredictionModelProfile {
        val start = startMinuteOfDay.coerceIn(1, LAST_MINUTE_OF_DAY)
        if (blocks.any { it.startMinuteOfDay == start } || blocks.size >= MAX_BLOCKS) return this
        val inherited = parametersAtMinute(start)
        return create(
            blocks + PredictionModelBlock(
                startMinuteOfDay = start,
                carbRatioGramsPerUnit = inherited.carbRatioGramsPerUnit,
                insulinSensitivityMgDlPerUnit = inherited.insulinSensitivityMgDlPerUnit,
                carbAbsorptionGramsPerHour = inherited.carbAbsorptionGramsPerHour
            ),
            blocks.first().parameters
        )
    }

    fun removeBlock(startMinuteOfDay: Int): PredictionModelProfile {
        if (startMinuteOfDay == 0 || blocks.size == 1) return this
        return create(blocks.filterNot { it.startMinuteOfDay == startMinuteOfDay }, blocks.first().parameters)
    }

    fun moveBlock(oldStartMinuteOfDay: Int, newStartMinuteOfDay: Int): PredictionModelProfile {
        if (oldStartMinuteOfDay == 0) return this
        val newStart = newStartMinuteOfDay.coerceIn(1, LAST_MINUTE_OF_DAY)
        if (newStart != oldStartMinuteOfDay && blocks.any { it.startMinuteOfDay == newStart }) return this
        val moving = blocks.firstOrNull { it.startMinuteOfDay == oldStartMinuteOfDay } ?: return this
        return create(
            blocks.filterNot { it.startMinuteOfDay == oldStartMinuteOfDay } +
                moving.copy(startMinuteOfDay = newStart),
            blocks.first().parameters
        )
    }

    fun updateBlock(
        startMinuteOfDay: Int,
        carbRatioGramsPerUnit: Float? = null,
        insulinSensitivityMgDlPerUnit: Float? = null,
        carbAbsorptionGramsPerHour: Float? = null
    ): PredictionModelProfile {
        if (blocks.none { it.startMinuteOfDay == startMinuteOfDay }) return this
        return create(
            blocks.map { block ->
                if (block.startMinuteOfDay != startMinuteOfDay) {
                    block
                } else {
                    block.copy(
                        carbRatioGramsPerUnit = carbRatioGramsPerUnit
                            ?.normalizeCarbRatio()
                            ?: block.carbRatioGramsPerUnit,
                        insulinSensitivityMgDlPerUnit = insulinSensitivityMgDlPerUnit
                            ?.normalizeInsulinSensitivity()
                            ?: block.insulinSensitivityMgDlPerUnit,
                        carbAbsorptionGramsPerHour = carbAbsorptionGramsPerHour
                            ?.normalizeCarbAbsorption()
                            ?: block.carbAbsorptionGramsPerHour
                    )
                }
            },
            blocks.first().parameters
        )
    }

    fun endMinuteFor(blockIndex: Int): Int {
        return blocks.getOrNull(blockIndex + 1)?.startMinuteOfDay?.minus(1) ?: LAST_MINUTE_OF_DAY
    }

    fun suggestedSplitMinute(): Int {
        val largest = blocks.indices.maxByOrNull { index ->
            endMinuteFor(index) - blocks[index].startMinuteOfDay + 1
        } ?: return MINUTES_PER_DAY / 2
        val start = blocks[largest].startMinuteOfDay
        val endExclusive = endMinuteFor(largest) + 1
        val midpoint = start + (endExclusive - start) / 2
        val rounded = ((midpoint + 15) / 30) * 30
        return rounded.coerceIn(1, LAST_MINUTE_OF_DAY)
    }

    fun encode(): String = blocks.joinToString(BLOCK_SEPARATOR) { block ->
        listOf(
            block.startMinuteOfDay,
            block.carbRatioGramsPerUnit,
            block.insulinSensitivityMgDlPerUnit,
            block.carbAbsorptionGramsPerHour
        ).joinToString(FIELD_SEPARATOR)
    }

    companion object {
        const val MINUTES_PER_HOUR = 60
        const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
        const val LAST_MINUTE_OF_DAY = MINUTES_PER_DAY - 1
        const val MAX_BLOCKS = 8
        const val CARB_RATIO_MIN = 3f
        const val CARB_RATIO_MAX = 30f
        const val INSULIN_SENSITIVITY_MIN = 10f
        const val INSULIN_SENSITIVITY_MAX = 180f
        const val CARB_ABSORPTION_MIN = 10f
        const val CARB_ABSORPTION_MAX = 90f
        const val DEFAULT_CARB_ABSORPTION_GRAMS_PER_HOUR = 35f

        private const val BLOCK_SEPARATOR = ";"
        private const val FIELD_SEPARATOR = ","

        fun single(
            carbRatioGramsPerUnit: Float,
            insulinSensitivityMgDlPerUnit: Float,
            carbAbsorptionGramsPerHour: Float = DEFAULT_CARB_ABSORPTION_GRAMS_PER_HOUR
        ): PredictionModelProfile {
            val fallback = PredictionModelParameters(
                carbRatioGramsPerUnit = carbRatioGramsPerUnit.normalizeCarbRatio(),
                insulinSensitivityMgDlPerUnit = insulinSensitivityMgDlPerUnit.normalizeInsulinSensitivity(),
                carbAbsorptionGramsPerHour = carbAbsorptionGramsPerHour.normalizeCarbAbsorption()
            )
            return create(
                listOf(
                    PredictionModelBlock(
                        startMinuteOfDay = 0,
                        carbRatioGramsPerUnit = fallback.carbRatioGramsPerUnit,
                        insulinSensitivityMgDlPerUnit = fallback.insulinSensitivityMgDlPerUnit,
                        carbAbsorptionGramsPerHour = fallback.carbAbsorptionGramsPerHour
                    )
                ),
                fallback
            )
        }

        fun decode(
            encoded: String?,
            fallback: PredictionModelParameters
        ): PredictionModelProfile {
            if (encoded.isNullOrBlank()) {
                return single(
                    fallback.carbRatioGramsPerUnit,
                    fallback.insulinSensitivityMgDlPerUnit,
                    fallback.carbAbsorptionGramsPerHour
                )
            }
            val parsed = encoded.split(BLOCK_SEPARATOR).mapNotNull { rawBlock ->
                val fields = rawBlock.split(FIELD_SEPARATOR)
                if (fields.size !in 3..4) return@mapNotNull null
                val start = fields[0].toIntOrNull() ?: return@mapNotNull null
                val carbRatio = fields[1].toFloatOrNull() ?: return@mapNotNull null
                val sensitivity = fields[2].toFloatOrNull() ?: return@mapNotNull null
                val absorption = fields.getOrNull(3)?.toFloatOrNull()
                    ?: fallback.carbAbsorptionGramsPerHour
                PredictionModelBlock(start, carbRatio, sensitivity, absorption)
            }
            return create(parsed, fallback)
        }

        private fun create(
            candidates: List<PredictionModelBlock>,
            fallback: PredictionModelParameters
        ): PredictionModelProfile {
            val normalizedFallback = PredictionModelParameters(
                carbRatioGramsPerUnit = fallback.carbRatioGramsPerUnit.normalizeCarbRatio(),
                insulinSensitivityMgDlPerUnit = fallback.insulinSensitivityMgDlPerUnit.normalizeInsulinSensitivity(),
                carbAbsorptionGramsPerHour = fallback.carbAbsorptionGramsPerHour.normalizeCarbAbsorption()
            )
            val normalized = candidates
                .asSequence()
                .filter { it.startMinuteOfDay in 0..LAST_MINUTE_OF_DAY }
                .map { block ->
                    block.copy(
                        carbRatioGramsPerUnit = block.carbRatioGramsPerUnit
                            .takeIf(Float::isFinite)
                            ?.coerceIn(CARB_RATIO_MIN, CARB_RATIO_MAX)
                            ?: normalizedFallback.carbRatioGramsPerUnit,
                        insulinSensitivityMgDlPerUnit = block.insulinSensitivityMgDlPerUnit
                            .takeIf(Float::isFinite)
                            ?.coerceIn(INSULIN_SENSITIVITY_MIN, INSULIN_SENSITIVITY_MAX)
                            ?: normalizedFallback.insulinSensitivityMgDlPerUnit,
                        carbAbsorptionGramsPerHour = block.carbAbsorptionGramsPerHour
                            .takeIf(Float::isFinite)
                            ?.coerceIn(CARB_ABSORPTION_MIN, CARB_ABSORPTION_MAX)
                            ?: normalizedFallback.carbAbsorptionGramsPerHour
                    )
                }
                .distinctBy { it.startMinuteOfDay }
                .sortedBy { it.startMinuteOfDay }
                .toMutableList()
            if (normalized.none { it.startMinuteOfDay == 0 }) {
                normalized += PredictionModelBlock(
                    startMinuteOfDay = 0,
                    carbRatioGramsPerUnit = normalizedFallback.carbRatioGramsPerUnit,
                    insulinSensitivityMgDlPerUnit = normalizedFallback.insulinSensitivityMgDlPerUnit,
                    carbAbsorptionGramsPerHour = normalizedFallback.carbAbsorptionGramsPerHour
                )
                normalized.sortBy { it.startMinuteOfDay }
            }
            return PredictionModelProfile(normalized.take(MAX_BLOCKS))
        }

        private fun Float.normalizeCarbRatio(): Float {
            return takeIf(Float::isFinite)?.coerceIn(CARB_RATIO_MIN, CARB_RATIO_MAX)
                ?: DEFAULT_CARB_RATIO_GRAMS_PER_UNIT
        }

        private fun Float.normalizeInsulinSensitivity(): Float {
            return takeIf(Float::isFinite)?.coerceIn(INSULIN_SENSITIVITY_MIN, INSULIN_SENSITIVITY_MAX)
                ?: DEFAULT_INSULIN_SENSITIVITY_MGDL_PER_UNIT
        }

        private fun Float.normalizeCarbAbsorption(): Float {
            return takeIf(Float::isFinite)?.coerceIn(CARB_ABSORPTION_MIN, CARB_ABSORPTION_MAX)
                ?: DEFAULT_CARB_ABSORPTION_GRAMS_PER_HOUR
        }
    }
}

object PredictionModelProfileStore {
    const val PROFILE_KEY = "dashboard_prediction_model_profile_v1"
    const val CARB_RATIO_KEY = "dashboard_prediction_carb_ratio_g_per_u"
    const val INSULIN_SENSITIVITY_KEY = "dashboard_prediction_insulin_sensitivity_mgdl_per_u"
    const val CARB_ABSORPTION_KEY = "dashboard_prediction_carb_absorption_g_per_h"
    const val DEFAULT_CARB_RATIO_GRAMS_PER_UNIT = 10f
    const val DEFAULT_INSULIN_SENSITIVITY_MGDL_PER_UNIT = 54f

    @JvmStatic
    fun load(preferences: SharedPreferences): PredictionModelProfile {
        val fallback = PredictionModelParameters(
            carbRatioGramsPerUnit = preferences
                .getFloat(CARB_RATIO_KEY, DEFAULT_CARB_RATIO_GRAMS_PER_UNIT)
                .coerceIn(PredictionModelProfile.CARB_RATIO_MIN, PredictionModelProfile.CARB_RATIO_MAX),
            insulinSensitivityMgDlPerUnit = preferences
                .getFloat(INSULIN_SENSITIVITY_KEY, DEFAULT_INSULIN_SENSITIVITY_MGDL_PER_UNIT)
                .coerceIn(
                    PredictionModelProfile.INSULIN_SENSITIVITY_MIN,
                    PredictionModelProfile.INSULIN_SENSITIVITY_MAX
                ),
            carbAbsorptionGramsPerHour = preferences
                .getFloat(CARB_ABSORPTION_KEY, PredictionModelProfile.DEFAULT_CARB_ABSORPTION_GRAMS_PER_HOUR)
                .coerceIn(
                    PredictionModelProfile.CARB_ABSORPTION_MIN,
                    PredictionModelProfile.CARB_ABSORPTION_MAX
                )
        )
        return PredictionModelProfile.decode(preferences.getString(PROFILE_KEY, null), fallback)
    }

    @JvmStatic
    fun save(preferences: SharedPreferences, profile: PredictionModelProfile) {
        val first = profile.blocks.first().parameters
        preferences.edit()
            .putString(PROFILE_KEY, profile.encode())
            .putFloat(CARB_RATIO_KEY, first.carbRatioGramsPerUnit)
            .putFloat(INSULIN_SENSITIVITY_KEY, first.insulinSensitivityMgDlPerUnit)
            .putFloat(CARB_ABSORPTION_KEY, first.carbAbsorptionGramsPerHour)
            .apply()
    }

    @JvmStatic
    fun parametersAt(preferences: SharedPreferences, timestamp: Long): PredictionModelParameters {
        return load(preferences).parametersAt(timestamp)
    }

    /**
     * Has a profile ever been written, or is [load] handing out the built-in defaults? Any
     * of the three keys counts, because the tuning screen and the older single-value
     * settings write different subsets of them.
     *
     * Read this to *say* what a number is computed from, never to switch a feature off: a
     * feature that shows as enabled and silently does nothing is the failure this exists to
     * avoid.
     */
    @JvmStatic
    fun isSaved(preferences: SharedPreferences): Boolean =
        preferences.contains(PROFILE_KEY) ||
            preferences.contains(INSULIN_SENSITIVITY_KEY) ||
            preferences.contains(CARB_RATIO_KEY)
}

private const val DEFAULT_CARB_RATIO_GRAMS_PER_UNIT = 10f
private const val DEFAULT_INSULIN_SENSITIVITY_MGDL_PER_UNIT = 54f
