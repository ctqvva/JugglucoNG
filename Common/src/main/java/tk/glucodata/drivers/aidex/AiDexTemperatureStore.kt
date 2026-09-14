// JugglucoNG — AiDex skin-temperature sidecar store
//
// The AiDex F003 live frame and 0x24 history rows carry skin temperature in the
// `i2` channel (u16 LE / 100, already in °C — verified against a co-worn
// Sibionics sensor: r ≈ 0.72 over ~5.5 h, identical 27–36 °C span, −0.08 °C
// mean bias, lockstep minute-by-minute tracking through a ~6 °C excursion).
//
// This store keeps accepted temperature samples next to the glucose Room store,
// mirroring the Ottai/Anytime temperature-history pattern, so the Statistics
// temperature card can read them. Display-only: nothing here feeds back into
// glucose storage or calibration.

package tk.glucodata.drivers.aidex

import android.content.Context

object AiDexTemperatureStore {

    private const val PREFS_NAME = "AiDexTemperaturePrefs"
    private const val KEY_PREFIX = "aidex_temp_history_"

    /** ~1/min cadence: 9000 samples cover about 6 days, matching the Ottai cap. */
    const val HISTORY_LIMIT = 9000

    data class TemperatureRecord(
        val timestampMs: Long,
        val temperatureC: Float,
    )

    /**
     * Plausibility gate for skin temperature. Deliberately wide (matches the
     * Statistics card filter) — out-of-range values are dropped, never stored.
     */
    fun isPlausibleSkinTemperatureC(t: Float): Boolean =
        t.isFinite() && t > -20f && t < 80f

    /**
     * Canonical storage id: uppercase `X-…` form (e.g. `X-222227KT3T`).
     * Bare serials gain the prefix; anything else is uppercased as-is.
     */
    fun canonicalId(sensorId: String): String {
        val trimmed = sensorId.trim().uppercase()
        return if (trimmed.startsWith("X-")) trimmed else "X-$trimmed"
    }

    /** Pure encoder — unit-testable without Android. Format: `ts,tempX10;…`. */
    fun encode(records: List<TemperatureRecord>): String =
        buildString(records.size * 20) {
            records.forEach { rec ->
                append(rec.timestampMs)
                append(',')
                append(Math.round(rec.temperatureC * 10f))
                append(';')
            }
        }

    /** Pure decoder — skips malformed tokens and implausible values. */
    fun decode(encoded: String): List<TemperatureRecord> {
        if (encoded.isBlank()) return emptyList()
        val out = ArrayList<TemperatureRecord>()
        encoded.split(';').forEach { token ->
            if (token.isBlank()) return@forEach
            val parts = token.split(',')
            if (parts.size != 2) return@forEach
            val timestampMs = parts[0].toLongOrNull() ?: return@forEach
            val temperatureC = (parts[1].toIntOrNull() ?: return@forEach) / 10f
            if (timestampMs <= 0L || !isPlausibleSkinTemperatureC(temperatureC)) return@forEach
            out.add(TemperatureRecord(timestampMs, temperatureC))
        }
        return out.distinctBy { it.timestampMs }.sortedBy { it.timestampMs }
    }

    @JvmStatic
    fun loadTemperatureHistory(c: Context, id: String): List<TemperatureRecord> {
        val key = KEY_PREFIX + canonicalId(id)
        return try {
            val encoded = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(key, null).orEmpty()
            decode(encoded)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @JvmStatic
    fun appendTemperatureHistory(c: Context, id: String, records: Collection<TemperatureRecord>) {
        if (records.isEmpty()) return
        val key = KEY_PREFIX + canonicalId(id)
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val merged = (decode(prefs.getString(key, null).orEmpty()).asSequence() + records.asSequence())
                .filter { it.timestampMs > 0L && isPlausibleSkinTemperatureC(it.temperatureC) }
                .distinctBy { it.timestampMs }
                .sortedBy { it.timestampMs }
                .toList()
                .takeLast(HISTORY_LIMIT)
            if (merged.isEmpty()) {
                prefs.edit().remove(key).apply()
                return
            }
            prefs.edit().putString(key, encode(merged)).apply()
        } catch (_: Throwable) {
            // Temperature is display-only telemetry — never crash BLE flow on it.
        }
    }
}
