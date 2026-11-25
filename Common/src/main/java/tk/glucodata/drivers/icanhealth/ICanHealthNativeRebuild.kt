package tk.glucodata.drivers.icanhealth

import android.content.Context

/**
 * A rebuild of a sensor's native stream that is still owed, and the temperatures it has to put back.
 *
 * Saved before the history the stream mirrors is replaced, and dropped once the stream was rebuilt, so
 * a process that dies anywhere in between leaves it here. The readings themselves come from Room; their
 * temperatures are only in the native stream, which the rebuild clears.
 */
internal object ICanHealthNativeRebuild {
    private const val PREFS_NAME = "icanhealth_native_rebuild"
    private const val MINUTE_MS = 60_000L

    fun minuteOf(timestampMs: Long): Long = timestampMs / MINUTE_MS

    fun isOwed(context: Context, sensorId: String): Boolean = prefs(context).contains(sensorId)

    /** Temperatures by [minuteOf], or null when no rebuild is owed. */
    fun load(context: Context, sensorId: String): Map<Long, Float>? =
        prefs(context).getString(sensorId, null)?.let(::decode)

    /** False when it could not be made durable; nothing may be cleared on the strength of it then. */
    fun save(context: Context, sensorId: String, temperatures: Map<Long, Float>): Boolean =
        prefs(context).edit().putString(sensorId, encode(temperatures)).commit()

    fun clear(context: Context, sensorId: String): Boolean =
        prefs(context).edit().remove(sensorId).commit()

    fun encode(temperatures: Map<Long, Float>): String = buildString(temperatures.size * 14) {
        for ((minute, temperatureC) in temperatures) {
            if (!temperatureC.isFinite() || temperatureC <= 0f) continue
            append(minute).append(',').append(Math.round(temperatureC * 10f)).append(';')
        }
    }

    fun decode(encoded: String): Map<Long, Float> {
        val temperatures = HashMap<Long, Float>()
        for (entry in encoded.split(';')) {
            val minute = entry.substringBefore(',').toLongOrNull() ?: continue
            val tenths = entry.substringAfter(',', "").toIntOrNull() ?: continue
            if (tenths > 0) temperatures[minute] = tenths / 10f
        }
        return temperatures
    }

    /** [tk.glucodata.Natives.getStreamTemperatures] packs a reading's second above its temperature in 0.1 °C. */
    fun unpackStream(packed: LongArray): Map<Long, Float> {
        val temperatures = HashMap<Long, Float>(packed.size * 2)
        for (entry in packed) {
            val tenths = (entry and 0xFFFF).toInt()
            if (tenths > 0) temperatures[(entry shr 16) / 60L] = tenths / 10f
        }
        return temperatures
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
