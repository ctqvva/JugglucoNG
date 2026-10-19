// MQProfile.kt — Per-model lifecycle/cadence profile.
//
// The Glutec ecosystem uses one hardware generation today (reusable transmitter
// plus 16-day rated disposable patch), so we only keep one profile. The structure
// mirrors the iCanHealth driver so future model variants can slot in cleanly.

package tk.glucodata.drivers.mq

data class MQProfile(
    val familyName: String,
    val readingIntervalMinutes: Int,
    val warmupMinutes: Int,
    val ratedLifetimeDays: Int,
) {
    fun ratedLifetimeMs(): Long = ratedLifetimeDays * 24L * 60L * 60L * 1000L
}

object MQProfileResolver {
    private val DEFAULT_PROFILE = MQProfile(
        familyName = "Glutec",
        readingIntervalMinutes = MQConstants.DEFAULT_READING_INTERVAL_MINUTES,
        warmupMinutes = MQConstants.DEFAULT_WARMUP_MINUTES,
        ratedLifetimeDays = MQConstants.DEFAULT_RATED_LIFETIME_DAYS,
    )

    @JvmStatic
    fun resolve(modelName: String? = null, rawSerial: String? = null): MQProfile = DEFAULT_PROFILE
}
