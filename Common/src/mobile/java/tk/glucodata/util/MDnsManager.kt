package tk.glucodata.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

data class DiscoveredMirror(
    val name: String,
    val ip: String,
    val port: Int,
    val mirrorJson: String = "" // The full QR-equivalent JSON from getbackJson
)

internal fun isOwnNsdService(discoveredServiceName: String, registeredServiceName: String?): Boolean =
    registeredServiceName != null && discoveredServiceName == registeredServiceName

class MDnsManager(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_jugglucong._tcp."
    
    @Volatile
    private var registeredServiceName: String? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    /**
     * Register this device as a discoverable Juggluco mirror.
     * Embeds the mirror JSON (same as QR code content) in TXT records
     * so discovering devices can connect using the exact same path as QR scanning.
     *
     * NSD TXT record values are limited to 255 bytes each, so we chunk
     * the JSON across multiple keys (j0, j1, j2, ...) if needed.
     */
    fun registerService(deviceName: String, port: Int = 8795, mirrorJson: String = "") {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "JugglucoNG-$deviceName"
            this.serviceType = this@MDnsManager.serviceType
            this.port = port
            // Chunk the JSON into <=250 byte pieces (leaving room for key overhead)
            if (mirrorJson.isNotEmpty()) {
                val chunks = mirrorJson.chunked(250)
                chunks.forEachIndexed { index, chunk ->
                    setAttribute("j$index", chunk)
                }
                setAttribute("jn", chunks.size.toString())
            }
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                registeredServiceName = NsdServiceInfo.serviceName
                Log.d("MDNS", "Service registered: ${NsdServiceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (registrationListener === this) {
                    registeredServiceName = null
                    registrationListener = null
                }
                Log.e("MDNS", "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                if (registrationListener === this) {
                    registeredServiceName = null
                    registrationListener = null
                }
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("MDNS", "Register error", e)
        }
    }

    fun unregisterService() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {}
    }

    // Scanning (Follower side)
    fun discoverServices(onServiceFound: (DiscoveredMirror) -> Unit) {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("MDNS", "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == serviceType || service.serviceType == "$serviceType.") {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (isOwnNsdService(serviceInfo.serviceName, registeredServiceName)) {
                                Log.d("MDNS", "Ignoring own service: ${serviceInfo.serviceName}")
                                return
                            }
                            val hostAddress = serviceInfo.host?.hostAddress ?: return
                            // Reassemble chunked JSON from TXT records
                            val numChunks = serviceInfo.attributes["jn"]?.let { String(it).toIntOrNull() } ?: 0
                            val json = if (numChunks > 0) {
                                buildString {
                                    for (i in 0 until numChunks) {
                                        val chunk = serviceInfo.attributes["j$i"]?.let { String(it) } ?: ""
                                        append(chunk)
                                    }
                                }
                            } else ""

                            val mirror = DiscoveredMirror(
                                name = serviceInfo.serviceName.removePrefix("JugglucoNG-"),
                                ip = hostAddress,
                                port = serviceInfo.port,
                                mirrorJson = json
                            )
                            Handler(Looper.getMainLooper()).post {
                                onServiceFound(mirror)
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
        }

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e("MDNS", "Discover error", e)
        }
    }

    fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
            discoveryListener = null
        } catch (e: Exception) {}
    }
}
