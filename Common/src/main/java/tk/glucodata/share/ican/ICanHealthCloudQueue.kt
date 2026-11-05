package tk.glucodata.share.ican

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ICanHealthCloudQueue(context: Context) {

    private val file = File(context.applicationContext.filesDir, "ican_cloud_queue.json")
    private val settings = ICanHealthCloudSettings(context)
    private val lock = ICanHealthCloudSettings.sessionLock
    private val items = ArrayList<EgvUpload>()

    init {
        synchronized(lock) { load() }
    }

    fun enqueue(egv: EgvUpload) {
        synchronized(lock) {
            load()
            val clamped = egv.copy(mgdl = egv.mgdl.coerceIn(MIN_MGDL, MAX_MGDL))
            if (items.any { it.serial == clamped.serial && it.timeMs == clamped.timeMs }) return
            items.add(clamped)
            items.sortBy { it.timeMs }
            persist()
        }
    }

    fun enqueueAll(egvs: List<EgvUpload>): Int {
        if (egvs.isEmpty()) return 0
        synchronized(lock) {
            load()
            val keys = items.map { it.serial to it.timeMs }.toHashSet()
            var added = 0
            for (egv in egvs) {
                val clamped = egv.copy(mgdl = egv.mgdl.coerceIn(MIN_MGDL, MAX_MGDL))
                val key = clamped.serial to clamped.timeMs
                if (!keys.add(key)) continue
                items.add(clamped)
                added++
            }
            if (added > 0) {
                items.sortBy { it.timeMs }
                persist()
            }
            return added
        }
    }

    fun clear() {
        synchronized(lock) {
            load()
            items.clear()
            persist()
        }
    }

    fun peek(): List<EgvUpload> {
        synchronized(lock) {
            load()
            return items.toList()
        }
    }

    fun remove(ids: Any?) {
        synchronized(lock) {
            load()
            val targets = when (ids) {
                is Iterable<*> -> ids.toList()
                is Array<*> -> ids.toList()
                is IntArray -> ids.map { it.toLong() }
                is LongArray -> ids.toList()
                is ShortArray -> ids.map { it.toLong() }
                null -> emptyList()
                else -> listOf(ids)
            }
            if (targets.isEmpty()) {
                persist()
                return
            }
            val keys = targets.map { toKey(it) }.toSet()
            items.removeAll { egv ->
                keys.any { key -> matches(key, egv) }
            }
            persist()
        }
    }

    fun size(): Int {
        synchronized(lock) {
            load()
            return items.size
        }
    }

    private fun toKey(value: Any?): Any? = when (value) {
        is EgvUpload -> Pair(value.serial, value.timeMs)
        is Number -> value.toLong()
        else -> value
    }

    private fun matches(key: Any?, egv: EgvUpload): Boolean {
        return when (key) {
            is Pair<*, *> -> key.first == egv.serial && key.second == egv.timeMs
            is Long -> key == egv.timeMs
            is Int -> key.toLong() == egv.timeMs
            is String -> {
                val separator = if (key.contains("|")) "|" else if (key.contains(":")) ":" else null
                if (separator != null) {
                    val parts = key.split(separator, limit = 2)
                    parts.size == 2 && parts[0] == egv.serial && parts[1].toLongOrNull() == egv.timeMs
                } else {
                    key == egv.serial || key == egv.timeMs.toString()
                }
            }
            else -> key == egv
        }
    }

    private fun load() {
        items.clear()
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val stored = runCatching { JSONObject(text) }.getOrNull() ?: return
        // A legacy queue has no provable account owner. Never attach it to a new session.
        if (stored.optString("account") != settings.accountKey) return
        val array = stored.optJSONArray("items") ?: return
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            items.add(EgvUpload.fromJson(obj))
        }
        items.sortBy { it.timeMs }
    }

    private fun persist() {
        runCatching {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("account", settings.accountKey).put("items", array).toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    companion object {
        private const val MIN_MGDL = 40
        private const val MAX_MGDL = 400
    }
}