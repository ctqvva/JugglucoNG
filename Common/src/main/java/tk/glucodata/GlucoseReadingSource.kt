package tk.glucodata

/** Stable database values describing how a glucose row reached this phone. */
object GlucoseReadingSource {
    const val SENSOR = "sensor"
    const val NIGHTSCOUT = "nightscout"
    const val API = "api"
    const val MQ_FOLLOWER = "mq_follower"
    const val IMPORT = "import"
    const val CLONE = "clone"
    const val CLONE_LOCAL_ICE = "clone_local_ice"
    const val CLONE_TURN = "clone_turn"

    fun forCloneTransport(transport: CloneTransport?): String = when (transport) {
        CloneTransport.LOCAL_ICE -> CLONE_LOCAL_ICE
        CloneTransport.TURN -> CLONE_TURN
        CloneTransport.UNKNOWN, null -> CLONE
    }

    fun cloneTransport(source: String?): CloneTransport? = when (source) {
        CLONE_LOCAL_ICE -> CloneTransport.LOCAL_ICE
        CLONE_TURN -> CloneTransport.TURN
        CLONE -> CloneTransport.UNKNOWN
        else -> null
    }
}
