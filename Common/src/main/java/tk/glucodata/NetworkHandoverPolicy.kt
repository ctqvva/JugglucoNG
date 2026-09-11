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
