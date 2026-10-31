package tk.glucodata.share.ican

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ICanHealthCloudQueue(context: Context) {

    private val file = File(context.applicationContext.filesDir, "ican_cloud_queue.json")
    private val lock = Any()
    private val items = ArrayList<EgvUpload>()

    init {
        synchronized(lock) { load() }
    }

    fun enqueue(egv: EgvUpload) {
        synchronized(lock) {
            val clamped = egv.copy(mgdl = egv.mgdl.coerceIn(MIN_MGDL, MAX_MGDL))
            if (items.any { it.serial == clamped.serial && it.timeMs == clamped.timeMs }) return
            items.add(clamped)
            items.sortBy { it.timeMs }
            persist()
        }
    }

    fun peek(): List<EgvUpload> {
        synchronized(lock) {
            return items.toList()
        }
    }

    fun remove(ids: Any?) {
        synchronized(lock) {
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
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val parsed = runCatching { JSONArray(text) }.getOrNull()
        val array = parsed ?: runCatching { JSONObject(text).optJSONArray("items") }.getOrNull()
        if (array == null) return
        items.clear()
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
            file.writeText(array.toString())
        }
    }

    companion object {
        private const val MIN_MGDL = 40
        private const val MAX_MGDL = 400
    }
}