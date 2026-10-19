package tk.glucodata

import android.util.Log
import java.util.TreeMap

object NotificationHistorySource {
    private const val TAG = "NotifyHistory"
    private const val MGDL_PER_MMOL = 18.0182f

    @JvmStatic
    fun resolveSensorSerial(preferredSerial: String? = null): String? {
        val preferred = SensorIdentity.resolveAppSensorId(preferredSerial) ?: preferredSerial
        if (!preferred.isNullOrBlank()) {
            return preferred
        }
        SensorIdentity.resolveMainSensor()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val lastSensor = SensorIdentity.resolveAppSensorId(Natives.lastsensorname()) ?: Natives.lastsensorname()
        if (!lastSensor.isNullOrBlank()) {
            return lastSensor
        }
        return Natives.activeSensors()
            ?.mapNotNull { SensorIdentity.resolveAppSensorId(it) ?: it }
            ?.firstOrNull { !it.isNullOrBlank() }
    }

    @JvmStatic
    fun getDisplayHistory(startTimeMs: Long, isMmol: Boolean, sensorSerial: String? = null): List<GlucosePoint> {
        return loadHistory(startTimeMs, isMmol, sensorSerial)
    }

    @JvmStatic
    fun getRawHistory(startTimeMs: Long, sensorSerial: String? = null): List<GlucosePoint> {
        return loadHistory(startTimeMs, false, sensorSerial)
    }

    private fun loadHistory(startTimeMs: Long, isMmol: Boolean, sensorSerial: String?): List<GlucosePoint> {
        val roomHistory = loadRoomHistory(startTimeMs, isMmol, sensorSerial)?.takeIf { it.isNotEmpty() }
        if (roomHistory != null) {
            // On the phone Room is authoritative. On the companion it only ever
            // holds what arrived live while the app was running, so returning it
            // hid the WearSync2 backfill sitting in native storage — the watch
            // showed a few hours of a full day. Merge instead of shadowing.
            if (!Applic.isWearable) return roomHistory
            val nativeHistory = loadNativeHistory(startTimeMs, isMmol, sensorSerial)
            if (nativeHistory.isEmpty()) return roomHistory
            // Key by minute: the two sources timestamp the same reading
            // differently (native is minute-aligned, Room keeps the arrival
            // millisecond), which listed one reading two or three times.
            val merged = TreeMap<Long, GlucosePoint>()
            fun minuteOf(timestamp: Long) = timestamp / 60_000L
            nativeHistory.forEach { merged[minuteOf(it.timestamp)] = it }
            roomHistory.forEach { point ->
                val key = minuteOf(point.timestamp)
                val existing = merged[key]
                if (existing == null || shouldReplace(existing, point)) merged[key] = point
            }
            return merged.values.sortedBy { it.timestamp }
        }
        return loadNativeHistory(startTimeMs, isMmol, sensorSerial)
    }

    private fun loadNativeHistory(startTimeMs: Long, isMmol: Boolean, sensorSerial: String?): List<GlucosePoint> {

        val startSec = startTimeMs / 1000L
        val resolvedSerial = resolveSensorSerial(sensorSerial)
        if (resolvedSerial.isNullOrBlank()) {
            return emptyList()
        }
        if (!SensorIdentity.shouldUseNativeHistorySync(resolvedSerial)) {
            return emptyList()
        }
        val history = try {
            Natives.getGlucoseHistoryForSensor(resolvedSerial, startSec)
        } catch (t: Throwable) {
            Log.w(TAG, "loadHistory(${resolvedSerial ?: "main"}, $startSec) failed", t)
            null
        } ?: return emptyList()

        if (history.size < 3) {
            return emptyList()
        }

        val orderedPoints = TreeMap<Long, GlucosePoint>()
        for (i in history.indices step 3) {
            if (i + 2 >= history.size) break

            val timestamp = history[i] * 1000L
            var value = history[i + 1] / 10f
            var rawValue = history[i + 2] / 10f
            if (value <= 0f && rawValue <= 0f) continue

            if (isMmol) {
                value /= MGDL_PER_MMOL
                rawValue /= MGDL_PER_MMOL
            }

            val candidate = GlucosePoint(timestamp, value, rawValue)
            val existing = orderedPoints[timestamp]
            if (existing == null || shouldReplace(existing, candidate)) {
                orderedPoints[timestamp] = candidate
            }
        }
        return ArrayList(orderedPoints.values)
    }

    private fun loadRoomHistory(startTimeMs: Long, isMmol: Boolean, sensorSerial: String?): List<GlucosePoint>? {
        val resolvedSerial = resolveSensorSerial(sensorSerial) ?: return null
        return HistoryRepositoryAccess.getHistoryForSensor(resolvedSerial, startTimeMs, isMmol)
    }

    private fun shouldReplace(existing: GlucosePoint, candidate: GlucosePoint): Boolean {
        val existingScore = score(existing)
        val candidateScore = score(candidate)
        if (candidateScore != existingScore) {
            return candidateScore > existingScore
        }
        return candidate.value >= existing.value
    }

    private fun score(point: GlucosePoint): Int {
        var score = 0
        if (point.value > 0f) score += 1
        if (point.rawValue > 0f) score += 2
        return score
    }
}
