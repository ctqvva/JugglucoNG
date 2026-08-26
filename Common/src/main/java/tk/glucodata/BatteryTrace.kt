package tk.glucodata

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object BatteryTrace {
    private const val TAG = "BatteryTrace"
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    @JvmStatic
    fun bump(key: String, logEvery: Long = 50L, detail: String? = null): Long {
        val count = counters.getOrPut(key) { AtomicLong(0L) }.incrementAndGet()
        if (count <= 3L || (logEvery > 0L && count % logEvery == 0L)) {
            val suffix = detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            Log.i(TAG, "$key #$count$suffix")
        }
        return count
    }
}
