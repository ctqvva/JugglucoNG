package tk.glucodata

/**
 * Tracks the Android default network separately from every available Internet network.
 *
 * ICE sockets keep using the network on which their current generation was created. When Android
 * selects a different default, a new ICE generation is required to follow that routing decision.
 */
class NetworkHandoverPolicy {
    enum class Action {
        NONE,
        PRESENT,
        HANDOVER,
        DEFER_ABSENCE,
        ABSENT,
    }

    private var defaultNetwork: Long? = null
    private var iceNetwork: Long? = null

    @Synchronized
    fun onDefaultAvailable(network: Long): Action {
        defaultNetwork = network
        val currentIceNetwork = iceNetwork
        if (currentIceNetwork == null) {
            iceNetwork = network
            return Action.PRESENT
        }
        if (currentIceNetwork == network) {
            return Action.PRESENT
        }
        iceNetwork = network
        return Action.HANDOVER
    }

    @Synchronized
    fun onDefaultLost(network: Long): Action {
        if (defaultNetwork == network) {
            defaultNetwork = null
        }
        return if (iceNetwork == network) Action.DEFER_ABSENCE else Action.NONE
    }

    @Synchronized
    fun onInternetNetworkLost(network: Long): Action {
        if (iceNetwork != network) {
            return Action.NONE
        }
        val replacement = defaultNetwork
        if (replacement != null && replacement != network) {
            iceNetwork = replacement
            return Action.HANDOVER
        }
        return Action.DEFER_ABSENCE
    }

    @Synchronized
    fun afterAbsenceGrace(defaultNetworkStillAvailable: Boolean): Action {
        val replacement = defaultNetwork
        if (replacement != null && defaultNetworkStillAvailable) {
            if (iceNetwork == replacement) {
                return Action.PRESENT
            }
            iceNetwork = replacement
            return Action.HANDOVER
        }
        iceNetwork = null
        return Action.ABSENT
    }

    @Synchronized
    fun currentDefaultNetwork(): Long = defaultNetwork ?: 0L

    @Synchronized
    fun currentIceNetwork(): Long = iceNetwork ?: 0L
}

/**
 * Distinguishes a genuine connectivity recovery (offline -> online or default-network handover)
 * from the initial `PRESENT` callback Android delivers at process startup when the device is
 * already connected, so restarting the app during a server outage does not wipe persisted backoff.
 */
class ConnectivityRecoveryDetector(startedOnline: Boolean) {
    private var observedInitialOnline = startedOnline
    private var wasAbsent = !startedOnline

    @Synchronized
    fun shouldNotifyRecovery(action: NetworkHandoverPolicy.Action): Boolean = when (action) {
        NetworkHandoverPolicy.Action.ABSENT -> {
            wasAbsent = true
            false
        }
        NetworkHandoverPolicy.Action.HANDOVER -> {
            observedInitialOnline = true
            wasAbsent = false
            true
        }
        NetworkHandoverPolicy.Action.PRESENT -> {
            if (wasAbsent) {
                observedInitialOnline = true
                wasAbsent = false
                true
            } else if (!observedInitialOnline) {
                observedInitialOnline = true
                false
            } else {
                false
            }
        }
        NetworkHandoverPolicy.Action.DEFER_ABSENCE,
        NetworkHandoverPolicy.Action.NONE -> false
    }
}
