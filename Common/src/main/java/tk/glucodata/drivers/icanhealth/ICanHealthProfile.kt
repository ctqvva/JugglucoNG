package tk.glucodata.drivers.icanhealth

import java.util.Locale

data class ICanHealthProfile(
    val familyName: String,
    val readingIntervalMinutes: Int,
    val warmupMinutes: Int,
    val ratedLifetimeDays: Int,
    val advisoryExpectedDays: Int = ICanHealthConstants.ADVISORY_EXPECTED_LIFETIME_DAYS,
) {
    fun ratedLifetimeMs(): Long = ratedLifetimeDays * 24L * 60L * 60L * 1000L
    fun expectedLifetimeMs(): Long = advisoryExpectedDays * 24L * 60L * 60L * 1000L
}

object ICanHealthProfileResolver {

    private val DEFAULT_PROFILE = ICanHealthProfile(
        familyName = "iCan",
        readingIntervalMinutes = 3,
        warmupMinutes = 120,
        ratedLifetimeDays = 15,
    )

    @JvmStatic
    fun resolve(
        modelName: String?,
        rawSerial: String? = null,
        softwareVersion: String? = null,
    ): ICanHealthProfile {
        val normalizedModel = normalize(modelName)
        val normalizedRawSerial = normalize(rawSerial)
        val normalizedSoftware = normalize(softwareVersion)
        val combined = listOf(normalizedModel, normalizedRawSerial, normalizedSoftware)
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        return when {
            combined.contains("i7e") ->
                ICanHealthProfile("i7e", 1, 30, 9)
            combined.contains("i7pro") || combined.contains("h7pro") ||
                combined.contains("i7s") || combined.contains(" i7") ||
                combined.startsWith("i7") ->
                ICanHealthProfile("i7", 1, 30, 15)
            combined.contains("h6-14") ->
                ICanHealthProfile("H6-14", 3, 120, 14)
            combined.contains("h6-15s") || combined.contains("h6-15") ||
                (combined.contains("h6") && (combined.contains(" yk") || combined.contains(" zz") || combined.contains(" zb"))) ->
                ICanHealthProfile("H6-15", 3, 30, 15)
            combined.contains("h6-7") ||
                (combined.contains(" h6") && !combined.contains("h6-14") && !combined.contains("h6-15")) ->
                ICanHealthProfile("H6", 3, if (combined.contains(" yk")) 30 else 120, 7)
            combined.contains("h3") ->
                ICanHealthProfile("H3", 3, 120, 8)
            combined.contains("i6pro") || combined.contains("i6s") ||
                combined.contains(" i6") || combined.startsWith("i6") ||
                combined.contains("t6") ->
                ICanHealthProfile("i6", 3, 30, 15)
            combined.contains("o3") ->
                ICanHealthProfile("o3", 3, 120, 15)
            combined.contains("t3") || combined.contains("i3a") ||
                combined.contains(" i3") || combined.startsWith("i3") ->
                ICanHealthProfile("i3", 3, 120, 15)
            else -> DEFAULT_PROFILE
        }
    }

    private fun normalize(value: String?): String {
        return value
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace('_', ' ')
            ?.replace('-', ' ')
            .orEmpty()
    }
}
