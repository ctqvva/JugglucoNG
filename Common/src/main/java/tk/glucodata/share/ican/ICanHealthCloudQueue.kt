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
    // True when the last persist failed: memory holds the only complete copy.
    private var dirty = false
    // Account the unsaved rows were queued under, to fence account switches.
    private var dirtyAccount: String? = null

    init {
        synchronized(lock) { sync() }
    }

    fun enqueue(egv: EgvUpload) {
        synchronized(lock) {
            sync()
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
            sync()
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
            sync()
            items.clear()
            persist()
        }
    }

    fun peek(): List<EgvUpload> {
        synchronized(lock) {
            sync()
            return items.toList()
        }
    }

    fun remove(ids: Any?) {
        synchronized(lock) {
            sync()
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
            sync()
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
        if (!file.exists()) {
            // Unsaved in-memory rows survive an externally deleted file.
            if (!(dirty && items.isNotEmpty())) items.clear()
            return
        }
        val text = runCatching { file.readText() }.getOrNull()
        if (text == null) {
            // An unreadable file must not erase the rows we still hold in memory;
            // mark them dirty so the next persist rewrites the durable copy.
            dirty = items.isNotEmpty()
            dirtyAccount = settings.accountKey
            return
        }
        val stored = runCatching { JSONObject(text) }.getOrNull()
        if (stored == null) {
            dirty = items.isNotEmpty()
            return
        }
        // A legacy queue has no provable account owner. Never attach it to a new session.
        if (stored.optString("account") != settings.accountKey) {
            items.clear()
            dirty = false
            return
        }
        val array = stored.optJSONArray("items") ?: run {
            dirty = items.isNotEmpty()
            return
        }
        items.clear()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            items.add(EgvUpload.fromJson(obj))
        }
        items.sortBy { it.timeMs }
        dirty = false
    }

    /**
     * Refresh the in-memory queue from the durable copy — unless the last persist
     * failed, in which case memory is the only complete copy and must not be
     * overwritten by the stale file.
     */
    private fun sync() {
        if (!dirty) {
            load()
            return
        }
        // Dirty rows belong to the account they were queued under.
        if (dirtyAccount != null && dirtyAccount != settings.accountKey) {
            items.clear()
            dirty = false
            dirtyAccount = null
        }
    }

    private fun persist() {
        val ok = runCatching {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("account", settings.accountKey).put("items", array).toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.isSuccess
        dirty = !ok
        dirtyAccount = if (ok) null else settings.accountKey
    }

    companion object {
        private const val MIN_MGDL = 40
        private const val MAX_MGDL = 400

        private val cache = java.util.concurrent.ConcurrentHashMap<String, ICanHealthCloudQueue>()

        /** One instance per backing file: callers share the same in-memory queue. */
        @JvmStatic
        fun shared(context: Context): ICanHealthCloudQueue =
            cache.getOrPut(context.applicationContext.filesDir.absolutePath) {
                ICanHealthCloudQueue(context.applicationContext)
            }
    }
}