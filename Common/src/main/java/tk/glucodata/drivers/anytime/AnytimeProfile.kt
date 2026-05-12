// AnytimeProfile.kt — Per-family lifecycle/cadence resolver.
//
// CT3 4/4H is the primary target (cadence ~1/min, 16-day rated). CT4 / CT3_PLUS
// have shorter or longer rated lives depending on chemistry. CT5 (no separate
// transmitter) uses the same 3-minute tick scale in the official SDK
// (`Anytime`, endNumber 7695 => 16 days) and adds an encrypted identity/data
// layer on top of the usual 0x1000 GATT service.

package tk.glucodata.drivers.anytime

data class AnytimeProfile(
    val family: AnytimeConstants.Family,
    val readingIntervalMinutes: Int,
    val warmupMinutes: Int,
    val ratedLifetimeDays: Int,
    /** SDK `algorithm` int (selects per-chemistry pipeline inside libalgorithm-jni.so). */
    val algorithmId: Int,
    /** Maximum 5-min-tick packet count before session naturally ends. */
    val endNumber: Int,
    /** Battery-low threshold (Volts) for the family. */
    val lowBatteryVolts: Float,
) {
    fun ratedLifetimeMs(): Long = ratedLifetimeDays * 24L * 60L * 60L * 1000L
}

object AnytimeProfileResolver {

    private val DEFAULT_PROFILE = AnytimeProfile(
        family = AnytimeConstants.Family.UNKNOWN,
        readingIntervalMinutes = AnytimeConstants.DEFAULT_READING_INTERVAL_MINUTES,
        warmupMinutes = AnytimeConstants.DEFAULT_WARMUP_MINUTES,
        ratedLifetimeDays = AnytimeConstants.DEFAULT_RATED_LIFETIME_DAYS,
        algorithmId = 3,
        endNumber = 6740,
        lowBatteryVolts = AnytimeConstants.BATTERY_LOW_VOLTS_CT3,
    )

    @JvmStatic
    @JvmOverloads
    fun resolve(deviceName: String? = null): AnytimeProfile {
        val entry = AnytimeConstants.resolveFamily(deviceName)
        if (entry.family == AnytimeConstants.Family.UNKNOWN) return DEFAULT_PROFILE

        val readingMinutes = when (entry.family) {
            AnytimeConstants.Family.CT4 -> 3
            AnytimeConstants.Family.CT5 -> 3
            else -> AnytimeConstants.DEFAULT_READING_INTERVAL_MINUTES
        }
        val lowVolts = when (entry.family) {
            AnytimeConstants.Family.CT2_5 -> AnytimeConstants.BATTERY_LOW_VOLTS_CT2_5
            AnytimeConstants.Family.CT3_PLUS,
            AnytimeConstants.Family.CT3_YUWELL,
            AnytimeConstants.Family.CT3_ULTRASONIC -> AnytimeConstants.BATTERY_LOW_VOLTS_CT3_A
            else -> AnytimeConstants.BATTERY_LOW_VOLTS_CT3
        }
        return AnytimeProfile(
            family = entry.family,
            readingIntervalMinutes = readingMinutes,
            warmupMinutes = AnytimeConstants.DEFAULT_WARMUP_MINUTES,
            ratedLifetimeDays = ((entry.endNumber.toLong() * readingMinutes) / (60L * 24L)).toInt().coerceAtLeast(7),
            algorithmId = entry.algorithm,
            endNumber = entry.endNumber,
            lowBatteryVolts = lowVolts,
        )
    }

    @JvmStatic
    fun familyEntry(deviceName: String?): AnytimeConstants.FamilyEntry =
        AnytimeConstants.resolveFamily(deviceName)
}
