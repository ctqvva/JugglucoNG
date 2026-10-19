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
 * Nothing here decides when readings stop; that stays on the `0x10` byte.
 */
object AiDexWearProfile {
    private const val SECONDS_PER_DAY = 86_400
    private const val WEAR_SECONDS_OFFSET = 4

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
     * Life shown on the card and the dashboard. Display only: reading cutoffs and expiry
     * stay on the sensor's own startup `0x10` byte.
     *
     * The byte always wins, so the card agrees with the remaining hours and the cutoff:
     * a 16-day sensor still reports model `GX-01S`. The rating fills in only while no
     * byte has been read.
     */
    fun resolve(sensorDays: Int?, modelDays: Int?): Int? =
        sensorDays?.takeIf { it > 0 } ?: modelDays?.takeIf { it > 0 }

    /**
     * As [resolve], with the byte this sensor sent on an earlier connection in between.
     *
     * A 16-day sensor reports model `GX-01S`, whose rating is 15, so until `0x10` is read
     * again after a restart or reconnect the rating alone shows the wrong end. The remembered
     * byte is still the sensor's own report, so it ranks above the rating and below a fresh byte.
     */
    fun resolve(sensorDays: Int?, rememberedSensorDays: Int?, modelDays: Int?): Int? =
        resolve(sensorDays?.takeIf { it > 0 } ?: rememberedSensorDays, modelDays)
}
