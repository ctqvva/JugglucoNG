package tk.glucodata.drivers.icanhealth

/**
 * Onboarding candidates picked by name that never accepted a GATT connection. Kept apart from the
 * permanent rejections (wrong serial, no CGM service) so an expiring cooldown can never undo one of
 * those. Addresses are upper case; times are monotonic elapsed milliseconds, never wall clock.
 */
internal class ICanHealthUnreachableCandidates(
    private val strikesToSetAside: Int = ICanHealthConstants.UNREACHABLE_CANDIDATE_REJECT_STRIKES,
    private val cooldownMs: Long = ICanHealthConstants.UNREACHABLE_CANDIDATE_COOLDOWN_MS,
) {
    private val strikes = HashMap<String, Int>()
    private val setAsideUntil = HashMap<String, Long>()

    /** Records how one attempt on [address] ended; true when that attempt set the address aside. */
    @Synchronized
    fun attemptEnded(address: String, reachedConnected: Boolean, nowMs: Long): Boolean {
        if (reachedConnected) {
            strikes.remove(address)
            return false
        }
        val count = (strikes[address] ?: 0) + 1
        if (count < strikesToSetAside) {
            strikes[address] = count
            return false
        }
        strikes.remove(address)
        setAsideUntil[address] = nowMs + cooldownMs
        return true
    }

    @Synchronized
    fun strikes(address: String): Int = strikes[address] ?: 0

    @Synchronized
    fun isSetAside(address: String, nowMs: Long): Boolean {
        val until = setAsideUntil[address] ?: return false
        if (nowMs < until) return true
        setAsideUntil.remove(address)
        return false
    }

    @Synchronized
    fun clear() {
        strikes.clear()
        setAsideUntil.clear()
    }
}
