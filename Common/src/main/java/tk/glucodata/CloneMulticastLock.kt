package tk.glucodata

import android.content.Context
import android.net.wifi.WifiManager
import androidx.annotation.Keep

/** One Wi-Fi receive lock shared by the active native LAN listeners on either peer. */
@Keep
object CloneMulticastLock {
    private var platformLock: WifiManager.MulticastLock? = null
    private val leases = CloneMulticastLeases(
        acquirePlatform = {
            runCatching {
                val wifi = Applic.app?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifi == null) false else {
                    val lock = wifi.createMulticastLock("Juggluco::CloneDiscovery")
                    lock.setReferenceCounted(false)
                    lock.acquire()
                    platformLock = lock
                    true
                }
            }.onFailure { Log.stack("CloneMulticastLock", "acquire", it) }.getOrDefault(false)
        },
        releasePlatform = {
            runCatching { platformLock?.release() }
                .onFailure { Log.stack("CloneMulticastLock", "release", it) }
            platformLock = null
        },
    )

    // Called from native session start/stop. Keep listening while connected so
    // a restarted peer can reach us. This is not a CPU wake lock.
    @JvmStatic fun acquire(): Boolean = leases.acquire()
    @JvmStatic fun release() = leases.release()
}

internal class CloneMulticastLeases(
    private val acquirePlatform: () -> Boolean,
    private val releasePlatform: () -> Unit,
) {
    private var count = 0

    @Synchronized fun acquire(): Boolean {
        if (count == 0 && !acquirePlatform()) return false
        count++
        return true
    }

    @Synchronized fun release() {
        if (count == 0) return
        if (--count == 0) releasePlatform()
    }
}
