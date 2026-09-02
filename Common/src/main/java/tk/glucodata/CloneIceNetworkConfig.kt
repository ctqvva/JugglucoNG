package tk.glucodata

import android.content.Context

data class CloneIceNetworkConfig(
    val rendezvousHost: String = "",
    val rendezvousPort: Int = DEFAULT_RENDEZVOUS_PORT,
    val useTurnForStun: Boolean = false,
    val verifyRendezvousCertificate: Boolean = true,
    val useLocalDiscovery: Boolean = true,
) {
    init {
        require(rendezvousHost.length <= MAX_HOST_LENGTH)
        require(rendezvousPort in 1..65535)
    }

    companion object {
        const val DEFAULT_RENDEZVOUS_PORT = 6789
        const val DEFAULT_STUN_HOST = "stun.l.google.com"
        const val DEFAULT_STUN_PORT = 19302
        const val MAX_HOST_LENGTH = 191
    }
}

object CloneIceNetworkConfigStore {
    private const val PREFS_NAME = "clone_ice_network"
    private const val KEY_RENDEZVOUS_HOST = "rendezvous_host"
    private const val KEY_RENDEZVOUS_PORT = "rendezvous_port"
    private const val KEY_USE_TURN_FOR_STUN = "use_turn_for_stun"
    private const val KEY_VERIFY_RENDEZVOUS_CERTIFICATE = "verify_rendezvous_certificate"
    private const val KEY_USE_LOCAL_DISCOVERY = "use_local_discovery"

    @JvmStatic
    fun load(context: Context): CloneIceNetworkConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_RENDEZVOUS_HOST, "").orEmpty().trim()
            .take(CloneIceNetworkConfig.MAX_HOST_LENGTH)
        val storedPort = prefs.getInt(
            KEY_RENDEZVOUS_PORT,
            CloneIceNetworkConfig.DEFAULT_RENDEZVOUS_PORT,
        )
        val rendezvousPort = if (host.isEmpty()) {
            CloneIceNetworkConfig.DEFAULT_RENDEZVOUS_PORT
        } else {
            storedPort.takeIf { it in 1..65535 }
                ?: CloneIceNetworkConfig.DEFAULT_RENDEZVOUS_PORT
        }
        return CloneIceNetworkConfig(
            rendezvousHost = host,
            rendezvousPort = rendezvousPort,
            useTurnForStun = prefs.getBoolean(KEY_USE_TURN_FOR_STUN, false),
            verifyRendezvousCertificate = prefs.getBoolean(
                KEY_VERIFY_RENDEZVOUS_CERTIFICATE,
                true,
            ),
            useLocalDiscovery = prefs.getBoolean(KEY_USE_LOCAL_DISCOVERY, true),
        )
    }

    @JvmStatic
    fun save(context: Context, config: CloneIceNetworkConfig): Boolean {
        if (config.useTurnForStun && Natives.TurnServerNR() <= 0) return false
        val normalizedHost = config.rendezvousHost.trim()
        val normalized = config.copy(
            rendezvousHost = normalizedHost,
            rendezvousPort = if (normalizedHost.isEmpty()) {
                CloneIceNetworkConfig.DEFAULT_RENDEZVOUS_PORT
            } else {
                config.rendezvousPort
            },
        )
        val committed = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RENDEZVOUS_HOST, normalized.rendezvousHost)
            .putInt(KEY_RENDEZVOUS_PORT, normalized.rendezvousPort)
            .putBoolean(KEY_USE_TURN_FOR_STUN, normalized.useTurnForStun)
            .putBoolean(
                KEY_VERIFY_RENDEZVOUS_CERTIFICATE,
                normalized.verifyRendezvousCertificate,
            )
            .putBoolean(KEY_USE_LOCAL_DISCOVERY, normalized.useLocalDiscovery)
            .commit()
        if (committed) applyToNative(normalized)
        return committed
    }

    @JvmStatic
    fun prepareForNativeStartup(context: Context) {
        // Startup restores saved ICE hosts inside setlibrary(). Apply the
        // persisted endpoint first so their constructors never snapshot the
        // built-in rendezvous service. TURN validity is checked after the
        // native backup is available by initialize().
        applyToNative(load(context))
    }

    @JvmStatic
    fun initialize(context: Context) {
        val stored = load(context)
        if (stored.useTurnForStun && Natives.TurnServerNR() <= 0) {
            save(context, stored.copy(useTurnForStun = false))
        } else {
            applyToNative(stored)
        }
    }

    private fun applyToNative(config: CloneIceNetworkConfig) {
        Natives.setCloneICEConfig(
            config.rendezvousHost,
            config.rendezvousPort,
            config.useTurnForStun,
            config.verifyRendezvousCertificate,
            config.useLocalDiscovery,
        )
    }
}
