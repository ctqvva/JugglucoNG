package tk.glucodata

data class CloneSensorHealth(
    val isReceiving: Boolean,
    val liveTransport: CloneTransport?,
    val isDisconnected: Boolean,
)

object CloneSensorHealthPolicy {
    @JvmStatic
    fun resolve(hasRecentData: Boolean, transport: CloneTransport?): CloneSensorHealth {
        val liveTransport = transport.takeIf {
            it == CloneTransport.LOCAL_ICE || it == CloneTransport.TURN
        }
        return CloneSensorHealth(
            // A fresh authenticated reading is direct evidence that this Clone
            // is receiving. Route classification can briefly be unavailable
            // while the native connection moves between ICE states.
            isReceiving = hasRecentData,
            liveTransport = liveTransport,
            isDisconnected = !hasRecentData && liveTransport == null,
        )
    }
}
