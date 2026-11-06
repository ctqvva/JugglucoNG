package tk.glucodata.alerts

import android.content.Context

internal data class SignalRestoredReading(val sensorId: String, val readingMs: Long)

internal interface SignalRestoredReadingStore {
    fun load(): SignalRestoredReading?
    fun save(reading: SignalRestoredReading)
}

/** Device-local runtime evidence, independent of display freshness and Wear settings sync. */
internal class SignalRestoredReadingPreferences(context: Context) : SignalRestoredReadingStore {
    private val prefs = context.getSharedPreferences("signal_restored_reading", Context.MODE_PRIVATE)

    override fun load(): SignalRestoredReading? {
        val id = prefs.getString("sensor", null)?.takeIf { it.isNotBlank() } ?: return null
        val time = prefs.getLong("time", 0L).takeIf { it > 0L } ?: return null
        return SignalRestoredReading(id, time)
    }

    override fun save(reading: SignalRestoredReading) {
        prefs.edit().putString("sensor", reading.sensorId).putLong("time", reading.readingMs).apply()
    }
}
