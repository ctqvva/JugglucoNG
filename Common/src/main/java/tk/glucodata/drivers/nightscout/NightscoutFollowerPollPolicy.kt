package tk.glucodata.drivers.nightscout

/**
 * How often a follower asks its Nightscout server, and what a stored value is allowed to mean.
 *
 * The interval is the follower's whole relationship with the data: a reading that arrived at
 * the server thirty seconds ago is not on this phone until the next poll, and an alert raised
 * on a followed value is late by the same amount. So it is a setting rather than a constant,
 * and the numbers offered are the cadences a CGM source actually publishes at.
 *
 * Kept free of Android so the rules can be tested; the manager and the settings screen both
 * read them from here rather than each having their own idea of what is allowed.
 */
object NightscoutFollowerPollPolicy {

    /** What the picker offers, in minutes. */
    val CHOICES_MINUTES: List<Int> = listOf(1, 2, 5, 10, 15, 30)

    /**
     * Five minutes: most sources publish on that cadence or slower, and a follower phone has
     * no sensor of its own keeping it awake, so every poll is a wake-up it pays for. Someone
     * following a one-minute source, or relying on the follower for alerts, moves it down.
     */
    const val DEFAULT_MINUTES: Int = 5

    /**
     * A stored or hand-edited value that is not on the list is pulled onto the nearest one.
     * No whole number sits exactly between two of these choices, so nearest is unambiguous
     * and there is no tie to break.
     */
    fun sanitizeMinutes(minutes: Int): Int =
        CHOICES_MINUTES.minByOrNull { choice -> kotlin.math.abs(choice - minutes) } ?: DEFAULT_MINUTES

    fun intervalMillis(minutes: Int): Long = sanitizeMinutes(minutes) * 60_000L

    /**
     * What Doze does to this. An allow-while-idle alarm fires at most about every nine
     * minutes while the phone is idle, unless the app is exempt from battery optimisation.
     * A shorter interval is therefore a ceiling rather than a promise, which is worth saying
     * in the settings row instead of leaving someone to wonder why their minute became ten.
     */
    const val DOZE_FLOOR_MINUTES: Int = 9

    fun isThrottledByDoze(minutes: Int): Boolean = sanitizeMinutes(minutes) < DOZE_FLOOR_MINUTES
}
