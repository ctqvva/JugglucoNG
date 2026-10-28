package tk.glucodata.drivers.icanhealth

/** Only the captured 13-character factory identity can build the regular CE/RU list. */
internal object ICanHealthProvisioningPolicy {
    enum class Eligibility { UNKNOWN_SENSOR, INSTALLED_TABLE, INVALID_FACTORY_SERIAL, FRESH }

    fun factoryCode(serial: String?): Float? {
        if (serial == null || !serial.matches(Regex("[0-9A-Za-z]{13}")) ||
            !serial.substring(9, 11).all { it in '0'..'9' }) return null
        return serial.substring(9, 11).toInt() / 100f
    }

    fun eligibility(marker: Int?, serial: String?): Eligibility = when {
        marker == null -> Eligibility.UNKNOWN_SENSOR
        marker != 0xffff -> Eligibility.INSTALLED_TABLE
        factoryCode(serial) == null -> Eligibility.INVALID_FACTORY_SERIAL
        else -> Eligibility.FRESH
    }

    /** Follow-up writes allowed when a table landed but the sensor stayed silent. */
    const val MAX_REPAIR_ATTEMPTS = 3

    /**
     * A sensor with a table installed that has produced nothing for this long is treated as
     * stalled. Its records arrive every three minutes, so ten minutes is three missed slots.
     */
    const val REPAIR_STALL_MS = 10 * 60 * 1000L

    /** Minimum gap between repair writes, so an 8-second reconnect loop cannot hammer the sensor. */
    const val REPAIR_COOLDOWN_MS = 20 * 60 * 1000L

    enum class RepairDecision { NONE, RETRY, EXHAUSTED }

    /**
     * Decides whether a table that is already installed should be written again.
     *
     * Unprovisioned iCan sensors stop producing after roughly a week, and writing the table into
     * a stalled one has been observed to bring it back. Our own write can also land without that
     * effect, and once `F6` is no longer `0xFFFF` the normal eligibility check refuses every
     * further attempt — one ineffective write would close the rescue path for good.
     *
     * @param installedMarker last `F6` value; anything but `0xFFFF` means a table is installed
     * @param sensorSilentMs elapsed time since last observed forward production progress
     * @param sinceLastWriteMs elapsed time since a table write started/completed, or [Long.MAX_VALUE]
     * @param attempts repair writes already made for this sensor in this process
     */
    fun repairDecision(
        installedMarker: Int?,
        sensorSilentMs: Long,
        sinceLastWriteMs: Long,
        attempts: Int,
        stallMs: Long = REPAIR_STALL_MS,
        cooldownMs: Long = REPAIR_COOLDOWN_MS,
    ): RepairDecision = when {
        installedMarker == null || installedMarker == 0xffff -> RepairDecision.NONE
        attempts >= MAX_REPAIR_ATTEMPTS -> RepairDecision.EXHAUSTED
        sensorSilentMs < stallMs -> RepairDecision.NONE
        sinceLastWriteMs < cooldownMs -> RepairDecision.NONE
        else -> RepairDecision.RETRY
    }

    // Known server fallback for unknown identities. It is reference data, never provisioning data.
    val defaultTable: Map<String, Float> = mapOf(
        "x1" to 0.015f,
        "x2" to 32.0f,
        "x3" to 25.0f,
        "x4" to 1.0f,
        "x5" to 0.8f,
        "x6" to 0.0f,
        "x7" to 40.0f,
        "x8" to 0.3f,
        "x9" to 1.0f,
        "x10" to 1.2f,
        "x11" to 13.0f,
        "x12" to 1.5f,
        "x13" to 0.4f,
        "x14" to 3.5f,
        "x15" to 15.0f,
        "x16" to 15.0f,
        "x17" to 0.1f,
        "x18" to 0.6f,
        "x19" to 0.2f,
        "x20" to 0.6f,
        "x21" to 0.1f,
        "x22" to 16.0f,
        "x23" to 1.0f,
        "x24" to 2.1f,
        "x25" to 2.2f,
    )

    /** Compare every coefficient we write, ignoring the box-derived x13. */
    fun isGenericTable(table: Map<String, Float>): Boolean =
        (1..15).filter { it != 13 }.all { table["x$it"] == defaultTable["x$it"] }
}

/** A canceled lookup cannot commit or schedule retries after removal or a positive F6. */
internal class ICanHealthCloudFetchOwnership {
    class Ticket(val serial: String)
    private val active = HashMap<String, Ticket>()

    @Synchronized
    fun begin(serial: String): Ticket = active.getOrPut(serial) { Ticket(serial) }

    @Synchronized
    fun current(ticket: Ticket): Boolean = active[ticket.serial] === ticket

    @Synchronized
    fun commitIfCurrent(ticket: Ticket, commit: () -> Boolean): Boolean =
        active[ticket.serial] === ticket && commit()

    @Synchronized
    fun cancel(serial: String) { active.remove(serial) }
}
