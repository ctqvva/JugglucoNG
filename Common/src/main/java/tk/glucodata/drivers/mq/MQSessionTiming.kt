package tk.glucodata.drivers.mq

internal object MQSessionTiming {
    /** Estimate only: a running transmitter's packet counter survives app resets. */
    fun estimateStartMs(receivedAtMs: Long, newestPacket: Int, intervalMinutes: Int): Long? {
        if (receivedAtMs <= 0 || newestPacket < 1 || newestPacket >= 15000 || intervalMinutes <= 0) return null
        return (receivedAtMs - newestPacket * intervalMinutes * 60_000L).takeIf { it > 0 }
    }

    fun reconcileStartMs(existingStartMs: Long, receivedAtMs: Long, newestPacket: Int, intervalMinutes: Int): Long {
        val estimate = estimateStartMs(receivedAtMs, newestPacket, intervalMinutes) ?: return existingStartMs
        // Preserve real session timing and tolerate radio delay. Repair the old connection-time
        // fallback only when it contradicts the transmitter's age by more than two intervals.
        return if (existingStartMs <= 0 || existingStartMs > estimate + 2L * intervalMinutes * 60_000L) {
            estimate
        } else existingStartMs
    }
}
