package tk.glucodata.glucosemeter

/**
 * The step a Glucose Profile meter takes between reading the manufacturer name and
 * subscribing to records. Picked only from characteristics the meter actually exposes:
 * a missing vendor clock (i-SENS FFF1, discussion #787) used to stall the sync before
 * any record was requested.
 */
enum class MeterClockStep {
    /** Roche: read Date Time (0x2A08). */
    READ_DATE_TIME,
    /** Legacy i-SENS: notify on FFF1, then write the clock to it. */
    WRITE_ISENS_TIME,
    /** Standard Current Time (0x2A2B) read. */
    READ_CURRENT_TIME,
    /** No clock step; subscribe to records straight away. */
    NONE,
}

object MeterClockSync {
    @JvmStatic
    fun step(
        manufacturer: String,
        hasDateTime: Boolean,
        hasIsensTime: Boolean,
        hasCurrentTime: Boolean,
    ): MeterClockStep {
        if (manufacturer.startsWith("Roche") && hasDateTime) return MeterClockStep.READ_DATE_TIME
        if (manufacturer.startsWith("i-SENS") && hasIsensTime) return MeterClockStep.WRITE_ISENS_TIME
        if (hasCurrentTime) return MeterClockStep.READ_CURRENT_TIME
        return MeterClockStep.NONE
    }
}
