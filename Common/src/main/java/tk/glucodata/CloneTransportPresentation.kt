package tk.glucodata

object CloneTransportPresentation {
    @JvmStatic
    fun statusTextRes(transport: CloneTransport?): Int = when (transport) {
        CloneTransport.LOCAL_ICE -> R.string.clone_transport_local_ice
        CloneTransport.TURN -> R.string.clone_transport_turn_ice
        CloneTransport.UNKNOWN, null -> R.string.clone_transport_unknown
    }
}
