// JugglucoNG — AiDex Native Kotlin Driver
// Rated wear from the official default-parameter catalog, resolved against 0x10.

package tk.glucodata.drivers.aidex.native.protocol

/**
 * How long an AiDex sensor is rated to run.
 *
 * Every official parameter file stores that rating as a little-endian uint32 of seconds
 * at byte 4 of `settingContent`. The value is the same for every firmware of a model:
 * GX-01S is 15 days, GX-02S is 10, GX-03S is 8, and the GXXXS files are 7, 14 and 16.
 * The card used to assume 15 whenever startup `0x10` had not supplied `wear_days`.
 */
object AiDexWearProfile {
    private const val SECONDS_PER_DAY = 86_400
    private const val MINUTES_PER_DAY = 24L * 60L
    private const val WEAR_SECONDS_OFFSET = 4

    /**
     * A new Aidex shell is created at 14 days, and that is also what the native end
     * reports before any wear write. A stored 14-day rating cannot be told apart from
     * that shell, so it is not restored from native; the model read supplies it.
     */
    const val SHELL_DAYS = 14

    private val ratedDaysBySettingType: Map<String, Int> = AiDexOfficialDpCatalogSnapshot.entries
        .groupBy { it.settingType }
        .mapNotNull { (settingType, entries) ->
            val days = entries.mapNotNull { catalogWearDays(it.settingContent) }.distinct()
            days.singleOrNull()?.let { settingType to it }
        }
        .toMap()

    /** Days encoded at [WEAR_SECONDS_OFFSET], or null when the field is not a whole number of days. */
    fun catalogWearDays(settingContentHex: String): Int? {
        val hex = settingContentHex.trim()
        if (hex.length < (WEAR_SECONDS_OFFSET + 4) * 2 || hex.length % 2 != 0) return null
        val seconds = (0 until 4).sumOf { index ->
            val byte = hex.substring((WEAR_SECONDS_OFFSET + index) * 2, (WEAR_SECONDS_OFFSET + index) * 2 + 2)
                .toIntOrNull(16) ?: return null
            byte shl (8 * index)
        }
        if (seconds <= 0 || seconds % SECONDS_PER_DAY != 0) return null
        return seconds / SECONDS_PER_DAY
    }

    /**
     * Rated days for a model name from DIS or startup `0x10` (`GX-02S`, `1034_GX02S`, …).
     * Null when the model is unknown or its catalog files do not agree.
     */
    fun ratedDays(modelName: String?): Int? {
        val settingType = AiDexDefaultParamProvisioning.normalizeCatalogModelName(modelName) ?: return null
        return ratedDaysBySettingType[settingType]
    }

    /**
     * Life used for the card, the dashboard and the history cutoff.
     *
     * A startup byte that names a different life than the model file is kept when it is
     * specific: a 16-day sensor still reports model `GX-01S`. A byte of 15 on a model whose
     * files are not 15 days is the GX-01S default, not that sensor's rating — GX-02S stays 10.
     */
    fun resolve(sensorDays: Int?, modelDays: Int?): Int? {
        val sensor = sensorDays?.takeIf { it > 0 }
        val model = modelDays?.takeIf { it > 0 }
        return when {
            sensor == null -> model
            model == null -> sensor
            sensor == model -> sensor
            model != 15 && sensor == 15 -> model
            sensor > model -> sensor
            else -> sensor
        }
    }

    /**
     * Days previously written by [aidexSetWearDays], recovered from the native end.
     * Null for a missing end, a non-whole-day span, or the 14-day shell default.
     */
    fun persistedWearDays(startMs: Long, officialEndMs: Long): Int? {
        if (startMs <= 0L || officialEndMs <= startMs) return null
        val minutes = (officialEndMs - startMs) / 60_000L
        if (minutes <= 0L || minutes % MINUTES_PER_DAY != 0L) return null
        val days = (minutes / MINUTES_PER_DAY).toInt()
        if (days !in 1..45 || days == SHELL_DAYS) return null
        return days
    }
}
