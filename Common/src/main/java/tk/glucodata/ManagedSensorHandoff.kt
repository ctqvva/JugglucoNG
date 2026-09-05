package tk.glucodata

import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.drivers.ManagedSensorIdentityRegistry

object ManagedSensorHandoff {
    private const val LOG_ID = "ManagedSensorHandoff"
    private const val VERSION = 1
    private const val MAIN_PREFS = "tk.glucodata_preferences"
    private const val AIDEX_NATIVE_PREFS = "AiDexNativePrefs"
    private const val VIEW_MODE_PREFS = "managed_sensor_view_modes"
    private const val KEY_MANAGED_CURRENT = "managed_current_sensor"

    private val prefsToExport = arrayOf(MAIN_PREFS, AIDEX_NATIVE_PREFS, VIEW_MODE_PREFS)
    private val managedRecordSetKeys = setOf(
        "aidex_sensors",
        "anytime_sensors",
        "icanhealth_sensors",
        "mq_sensors",
        "ottai_sensors",
        // Sibionics was missing here and below, so a handoff carried nothing at
        // all about a Sibionics sensor: the watch received the takeover, had no
        // record, no auth material and no session state, and sat on "waiting to
        // connect" for good.
        "sibionics_managed_sensors",
    )
    private val managedKeyPrefixes = arrayOf(
        "aidex_",
        "anytime_",
        "icanhealth_",
        "mq_",
        "ottai_",
        "sibionics_",
        "api_glucose_source_",
        "nightscout_follower_",
        "view_mode_",
    )

    @JvmStatic
    fun createOutgoingPayload(context: Context? = Applic.app): ByteArray {
        val ctx = context ?: Applic.app
        val candidates = collectManagedCandidates(ctx)
        val currentManaged = ManagedCurrentSensor.get()
            ?.takeIf { id -> candidates.any { SensorIdentity.matches(it, id) } }

        val root = JSONObject()
            .put("version", VERSION)
            .put("takeover", true)
            .put("managedSensorId", currentManaged ?: JSONObject.NULL)

        val entries = JSONArray()
        if (ctx != null && candidates.isNotEmpty()) {
            prefsToExport.forEach { prefsName ->
                exportPrefs(ctx, prefsName, candidates, entries)
            }
        }
        root.put("entries", entries)
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    @JvmStatic
    fun applyIncoming(context: Context? = Applic.app, data: ByteArray?): Boolean {
        val ctx = context ?: Applic.app ?: return false
        if (data == null) return false
        return runCatching {
            val root = JSONObject(String(data, StandardCharsets.UTF_8))
            if (root.optInt("version", 0) != VERSION || !root.optBoolean("takeover", false)) {
                return@runCatching false
            }
            val entries = root.optJSONArray("entries") ?: JSONArray()
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                importEntry(ctx, entry)
            }
            root.optString("managedSensorId", "")
                .trim()
                .takeIf { it.isNotEmpty() && it != "null" }
                ?.let { sensorId ->
                    if (!SensorIdentity.hasNativeSensorBacking(sensorId)) {
                        ManagedCurrentSensor.set(sensorId)
                    }
                }
            SensorIdentity.invalidateCaches()
            true
        }.getOrElse { th ->
            Log.stack(LOG_ID, "applyIncoming", th)
            false
        }
    }

    private fun collectManagedCandidates(context: Context?): Set<String> {
        val out = LinkedHashSet<String>()
        fun add(id: String?) {
            id?.trim()?.takeIf { it.isNotEmpty() }?.let { sensorId ->
                out.add(sensorId)
                out.add(SensorIdentity.resolveAppSensorId(sensorId) ?: sensorId)
                out.add(SensorIdentity.resolveNativeSensorName(sensorId) ?: sensorId)
                out.add(SensorIdentity.resolveRoomStorageSensorId(sensorId) ?: sensorId)
                SensorIdentity.resolveNativeHistorySensorNames(sensorId).forEach(out::add)
            }
        }

        val selected = SensorIdentity.resolveMainSensor()
        add(ManagedCurrentSensor.get())
        add(selected)
        add(Natives.lastsensorname())
        SensorBluetooth.mygatts()?.forEach { callback ->
            if (selected == null || SensorIdentity.matches(callback.SerialNumber, selected)) {
                add(callback.SerialNumber)
            }
        }
        if (context != null) {
            ManagedSensorIdentityRegistry.persistedSensorIds(context).forEach { persisted ->
                if (selected == null || SensorIdentity.matches(persisted, selected)) {
                    add(persisted)
                }
            }
        }
        return out
    }

    private fun exportPrefs(
        context: Context,
        prefsName: String,
        candidates: Set<String>,
        entries: JSONArray
    ) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.all.forEach { (key, value) ->
            if (shouldExportKey(key, value, candidates, prefsName)) {
                entries.put(encodeEntry(prefsName, key, value))
            }
        }
    }

    private fun shouldExportKey(
        key: String,
        value: Any?,
        candidates: Set<String>,
        prefsName: String
    ): Boolean {
        if (prefsName == VIEW_MODE_PREFS) {
            return keyMatchesCandidate(key, candidates)
        }
        if (key == KEY_MANAGED_CURRENT) {
            val current = value as? String ?: return false
            return candidates.any { SensorIdentity.matches(it, current) }
        }
        if (key in managedRecordSetKeys) {
            @Suppress("UNCHECKED_CAST")
            val set = value as? Set<String> ?: return false
            return set.any { recordMatchesCandidate(it, candidates) }
        }
        if (prefsName == AIDEX_NATIVE_PREFS) {
            return keyMatchesCandidate(key, candidates)
        }
        return managedKeyPrefixes.any { key.startsWith(it) } && keyMatchesCandidate(key, candidates)
    }

    private fun encodeEntry(prefsName: String, key: String, value: Any?): JSONObject {
        val out = JSONObject()
            .put("prefs", prefsName)
            .put("key", key)
        when (value) {
            is String -> out.put("type", "string").put("value", value)
            is Int -> out.put("type", "int").put("value", value)
            is Long -> out.put("type", "long").put("value", value)
            is Float -> out.put("type", "float").put("value", value.toDouble())
            is Boolean -> out.put("type", "boolean").put("value", value)
            is Set<*> -> {
                val array = JSONArray()
                value.filterIsInstance<String>().forEach(array::put)
                out.put("type", "stringSet").put("value", array)
            }
            else -> out.put("type", "unsupported").put("value", JSONObject.NULL)
        }
        return out
    }

    private fun importEntry(context: Context, entry: JSONObject) {
        val prefsName = entry.optString("prefs", "").takeIf { it.isNotBlank() } ?: return
        val key = entry.optString("key", "").takeIf { it.isNotBlank() } ?: return
        val editor = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
        when (entry.optString("type")) {
            "string" -> editor.putString(key, entry.optString("value", ""))
            "int" -> editor.putInt(key, entry.optInt("value"))
            "long" -> editor.putLong(key, entry.optLong("value"))
            "float" -> editor.putFloat(key, entry.optDouble("value").toFloat())
            "boolean" -> editor.putBoolean(key, entry.optBoolean("value"))
            "stringSet" -> mergeStringSet(context, prefsName, key, entry.optJSONArray("value"), editor)
            else -> return
        }
        editor.commit()
        // Written straight through SharedPreferences, so the in-memory cache in
        // ManagedCurrentSensor would otherwise keep serving the pre-handoff value.
        if (prefsName == MAIN_PREFS && key == KEY_MANAGED_CURRENT) {
            ManagedCurrentSensor.invalidate()
        }
    }

    private fun mergeStringSet(
        context: Context,
        prefsName: String,
        key: String,
        array: JSONArray?,
        editor: SharedPreferences.Editor
    ) {
        val merged = LinkedHashSet<String>()
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getStringSet(key, emptySet())
            ?.let(merged::addAll)
        if (array != null) {
            for (index in 0 until array.length()) {
                array.optString(index, "").takeIf { it.isNotEmpty() }?.let(merged::add)
            }
        }
        editor.putStringSet(key, merged)
    }

    /**
     * Whether a per-sensor preference key would travel with a handoff. Exposed so
     * a test can pin every managed driver's namespace: a driver missing from the
     * lists above hands over a sensor the other device cannot connect to, and
     * nothing else fails loudly enough to notice.
     */
    internal fun exportsKeyForSensor(key: String, sensorId: String): Boolean =
        shouldExportKey(key, "", setOf(sensorId), MAIN_PREFS)

    private fun keyMatchesCandidate(key: String, candidates: Set<String>): Boolean =
        candidates.any { candidate ->
            val id = candidate.trim()
            id.isNotEmpty() && (
                key.equals(id, ignoreCase = true) ||
                    key.endsWith(id, ignoreCase = true) ||
                    key.contains(id, ignoreCase = true)
                )
        }

    private fun recordMatchesCandidate(record: String, candidates: Set<String>): Boolean =
        candidates.any { candidate ->
            SensorIdentity.matches(record.substringBefore('|'), candidate) ||
                record.contains(candidate, ignoreCase = true)
        }
}
