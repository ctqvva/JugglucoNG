package tk.glucodata

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.Keep

/** A short recovery window, independent of the optional persistent receiver lock. */
@Keep
object CloneRecoveryWake {
    internal const val TIMEOUT_MS = 30_000L
    private val leases = CloneRecoveryWakeLeases(SystemClock::elapsedRealtime) { timeout ->
        runCatching {
            val manager = Applic.app?.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (manager == null) null else {
                val wake = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Juggluco::CloneRecovery")
                wake.setReferenceCounted(false)
                wake.acquire(timeout)
                val release: () -> Unit = {
                    runCatching { if (wake.isHeld) wake.release() }
                        .onFailure { Log.stack("CloneRecoveryWake", "release", it) }
                    Unit
                }
                release
            }
        }.onFailure { Log.stack("CloneRecoveryWake", "acquire", it) }.getOrNull()
    }

    @JvmStatic fun acquire(): Long = leases.acquire()
    @JvmStatic fun release(token: Long) = leases.release(token)
    @JvmStatic fun setReceptionEnabled(enabled: Boolean) = leases.setEnabled(enabled)
    @JvmStatic fun releaseAll() = leases.releaseAll()
}

/** Platform acquisition receives a hard timeout; tokens isolate concurrent hosts. */
internal class CloneRecoveryWakeLeases(
    private val now: () -> Long,
    private val acquirePlatform: (Long) -> (() -> Unit)?,
) {
    private data class Lease(val deadline: Long, val release: () -> Unit)
    private val active = mutableMapOf<Long, Lease>()
    private var nextToken = 0L
    private var enabled = true

    @Synchronized fun acquire(): Long {
        if (!enabled) return 0
        val time = now()
        active.filterValues { it.deadline <= time }.keys.toList().forEach(::release)
        val release = acquirePlatform(CloneRecoveryWake.TIMEOUT_MS) ?: return 0
        val token = ++nextToken
        active[token] = Lease(time + CloneRecoveryWake.TIMEOUT_MS, release)
        return token
    }

    @Synchronized fun release(token: Long) {
        active.remove(token)?.release?.invoke()
    }

    @Synchronized fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) releaseAll()
    }

    @Synchronized fun releaseAll() {
        active.keys.toList().forEach(::release)
    }
}
