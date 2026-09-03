package tk.glucodata

object CloneTransportPresentation {
    /**
     * Live Clone surfaces report the current ICE generation. Historical rows
     * keep their independently persisted route.
     */
    @JvmStatic
    fun sensorTransport(live: CloneTransport?): CloneTransport? = knownTransport(live)

    private fun knownTransport(transport: CloneTransport?): CloneTransport? =
        transport?.takeIf { it == CloneTransport.LOCAL_ICE || it == CloneTransport.TURN }

    @JvmStatic
    fun statusTextRes(transport: CloneTransport?): Int = when (transport) {
        CloneTransport.LOCAL_ICE -> R.string.clone_transport_local_ice
        CloneTransport.TURN -> R.string.clone_transport_turn_ice
        CloneTransport.UNKNOWN, null -> R.string.clone_transport_reconnecting
    }
}
