package tk.glucodata

object CloneTransportPresentation {
    /**
     * A sensor's latest imported route remains useful while its ICE connection
     * is between generations. Connection diagnostics intentionally do not use
     * this fallback because they report the native state at this instant.
     */
    @JvmStatic
    fun sensorTransport(
        live: CloneTransport?,
        lastConfirmed: CloneTransport?,
    ): CloneTransport? = knownTransport(live) ?: knownTransport(lastConfirmed)

    private fun knownTransport(transport: CloneTransport?): CloneTransport? =
        transport?.takeIf { it == CloneTransport.LOCAL_ICE || it == CloneTransport.TURN }

    @JvmStatic
    fun statusTextRes(transport: CloneTransport?): Int = when (transport) {
        CloneTransport.LOCAL_ICE -> R.string.clone_transport_local_ice
        CloneTransport.TURN -> R.string.clone_transport_turn_ice
        CloneTransport.UNKNOWN, null -> R.string.clone_transport_unknown
    }
}
