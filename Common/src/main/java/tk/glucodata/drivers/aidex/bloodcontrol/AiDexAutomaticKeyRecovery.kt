package tk.glucodata.drivers.aidex.bloodcontrol

import tk.glucodata.drivers.aidex.AiDexProvisionedKeys

/** BLE recovery state; connection cleanup can abandon an attempt from outside the handler. */
internal class AiDexAutomaticKeyRecovery(private val now: () -> Long = System::currentTimeMillis) {
    class Attempt internal constructor(val tryCn: Boolean)
    private var inFlight: Attempt? = null
    private var cnAttempted = false
    private var terminal = false
    private var retryAtMillis = 0L
    private var transientFailures = 0

    @Synchronized
    fun begin(): Attempt? {
        if (inFlight != null || terminal || now() < retryAtMillis) return null
        return Attempt(tryCn = !cnAttempted).also { inFlight = it }
    }

    /** Cleanup can remove a posted completion before it ever calls complete(). */
    @Synchronized
    fun abandonInFlight() {
        inFlight = null
    }

    /** Stage for the sensor exchange only; persistence follows direct live-frame validation. */
    @Synchronized
    fun complete(
        attempt: Attempt,
        result: BloodControlAidex.AutomaticResult,
        isCurrent: () -> Boolean,
        stage: (AiDexProvisionedKeys) -> Boolean,
    ): Boolean {
        if (inFlight !== attempt) return false
        inFlight = null
        if (!isCurrent()) return false
        cnAttempted = true
        val keys = result.keys
        if (keys != null) {
            val staged = stage(keys)
            terminal = result.usedBloodControl || !staged
            retryAtMillis = 0
            return staged
        }
        val deadline = result.retryAtMillis
        if (deadline != null && ++transientFailures < MAX_TRANSIENT_ATTEMPTS) {
            retryAtMillis = maxOf(deadline, now() + 1_000)
        } else {
            terminal = true
        }
        return false
    }

    companion object {
        // Initial request plus at most four later reconnect attempts, each after provider backoff.
        private const val MAX_TRANSIENT_ATTEMPTS = 5
    }
}
