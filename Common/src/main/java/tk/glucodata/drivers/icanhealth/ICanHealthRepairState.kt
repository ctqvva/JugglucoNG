package tk.glucodata.drivers.icanhealth

/** Per-sensor repair bookkeeping. Times are monotonic elapsed milliseconds, never wall clock. */
internal class ICanHealthRepairState {
    companion object {
        const val STATUS_PROBE_INTERVAL_MS = 60_000L
    }

    private var sensorId: String? = null
    private var lastSequence = -1
    private var lastProgressAtMs: Long? = null
    private var lastProbeAtMs: Long? = null
    private var lastWriteAtMs: Long? = null
    private var attempts = 0

    @Synchronized
    fun selectSensor(id: String): Boolean {
        if (id == sensorId) return false
        sensorId = id
        lastSequence = -1
        lastProgressAtMs = null
        lastProbeAtMs = null
        lastWriteAtMs = null
        attempts = 0
        return true
    }

    @Synchronized
    fun statusProbeDue(nowMs: Long): Boolean =
        lastProbeAtMs?.let { nowMs - it >= STATUS_PROBE_INTERVAL_MS } ?: true

    @Synchronized
    fun statusProbeRequested(nowMs: Long) { lastProbeAtMs = nowMs }

    /** Both status and live frames can establish forward progress; repeats cannot reset a stall. */
    @Synchronized
    fun observeSequence(sequence: Int, nowMs: Long): Boolean {
        if (sequence < 0) return false
        if (lastProgressAtMs == null) lastProgressAtMs = nowMs
        if (sequence <= lastSequence) return false
        val advanced = lastSequence >= 0
        lastSequence = sequence
        lastProgressAtMs = nowMs
        attempts = 0
        return advanced
    }

    @Synchronized
    fun decision(marker: Int?, counterCapped: Boolean, nowMs: Long): ICanHealthProvisioningPolicy.RepairDecision {
        // The 28-day status cap is not evidence that production stopped. Never rewrite a
        // late-life sensor based on that counter, including while its history lane is active.
        if (counterCapped) return ICanHealthProvisioningPolicy.RepairDecision.NONE
        val progressAt = lastProgressAtMs ?: return ICanHealthProvisioningPolicy.RepairDecision.NONE
        return ICanHealthProvisioningPolicy.repairDecision(
            installedMarker = marker,
            sensorSilentMs = nowMs - progressAt,
            sinceLastWriteMs = lastWriteAtMs?.let { nowMs - it } ?: Long.MAX_VALUE,
            attempts = attempts,
        )
    }

    /** Recheck liveness and charge only a first chunk accepted by the transport. */
    @Synchronized
    fun startWrite(
        repair: Boolean,
        marker: Int?,
        counterCapped: Boolean,
        nowMs: Long,
        write: () -> Boolean,
    ): Boolean {
        if (repair && decision(marker, counterCapped, nowMs) != ICanHealthProvisioningPolicy.RepairDecision.RETRY) {
            return false
        }
        if (!write()) return false
        lastWriteAtMs = nowMs
        if (repair) attempts++
        return true
    }

    @Synchronized
    fun writeCompleted(nowMs: Long) { lastWriteAtMs = nowMs }

    @Synchronized
    fun attemptCount(): Int = attempts
}
