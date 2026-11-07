package tk.glucodata.drivers.aidex.bloodcontrol

import tk.glucodata.drivers.aidex.AiDexProvisionedKeys

/** Handler-owned recovery state: one CN attempt, then BC, with bounded transient retries. */
internal class AiDexAutomaticKeyRecovery(private val now: () -> Long = System::currentTimeMillis) {
    class Attempt internal constructor(val tryCn: Boolean)
    private var inFlight: Attempt? = null
    private var cnAttempted = false
    private var terminal = false
    private var retryAtMillis = 0L
    private var transientFailures = 0

    fun begin(): Attempt? {
        if (inFlight != null || terminal || now() < retryAtMillis) return null
        return Attempt(tryCn = !cnAttempted).also { inFlight = it }
    }

    /** No persistence callback runs before freshness has been checked on the BLE handler. */
    fun complete(
        attempt: Attempt,
        result: BloodControlAidex.AutomaticResult,
        isCurrent: () -> Boolean,
        install: (AiDexProvisionedKeys) -> Boolean,
    ): Boolean {
        if (inFlight !== attempt) return false
        inFlight = null
        if (!isCurrent()) return false
        cnAttempted = true
        val keys = result.keys
        if (keys != null) {
            val installed = install(keys)
            terminal = result.usedBloodControl || !installed
            retryAtMillis = 0
            return installed
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
