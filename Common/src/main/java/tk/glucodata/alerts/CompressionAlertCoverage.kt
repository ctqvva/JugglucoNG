package tk.glucodata.alerts

/**
 * The glucose alarms that sensor-pressure handling may delay.
 *
 * The persisted form is a bit mask built from stable [AlertType.id] values. Unknown
 * bits are ignored so a value written by a newer build is safe to read after rollback.
 */
internal object CompressionAlertCoverage {
    val eligibleTypes: List<AlertType> = listOf(
        AlertType.LOW,
        AlertType.VERY_LOW,
        AlertType.PRE_LOW,
        AlertType.FALLING_FAST,
        AlertType.HIGH,
        AlertType.VERY_HIGH,
        AlertType.PRE_HIGH,
        AlertType.RISING_FAST
    )

    val defaultTypes: Set<AlertType> = setOf(
        AlertType.LOW,
        AlertType.PRE_LOW,
        AlertType.FALLING_FAST,
        AlertType.PRE_HIGH,
        AlertType.RISING_FAST
    )

    val defaultMask: Int = encode(defaultTypes)

    fun decode(mask: Int): Set<AlertType> =
        eligibleTypes.filterTo(linkedSetOf()) { mask and bit(it) != 0 }

    fun encode(types: Set<AlertType>): Int =
        types.filter { it in eligibleTypes }.fold(0) { mask, type -> mask or bit(type) }

    fun updated(current: Set<AlertType>, type: AlertType, covered: Boolean): Set<AlertType> {
        if (type !in eligibleTypes) return decode(encode(current))
        return decode(encode(if (covered) current + type else current - type))
    }

    private fun bit(type: AlertType): Int = 1 shl type.id
}
