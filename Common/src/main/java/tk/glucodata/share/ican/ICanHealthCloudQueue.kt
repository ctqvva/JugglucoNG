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
    /** Account the in-memory rows were queued/loaded under; null = no trusted rows. */
    private var itemsAccount: String? = null
    // True when the durable copy is behind memory (unreadable file or failed persist):
    // memory holds the only complete copy until the next successful persist.
    private var dirty = false

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
            // Unsaved in-memory rows survive an externally deleted file,
            // but only while they provably belong to the current account.
            if (!(dirty && items.isNotEmpty() && itemsAccount == settings.accountKey)) {
                items.clear()
                itemsAccount = null
            }
            return
        }
        val text = runCatching { file.readText() }.getOrNull()
        if (text == null) {
            rememberUnreadable()
            return
        }
        val stored = runCatching { JSONObject(text) }.getOrNull()
        if (stored == null) {
            rememberUnreadable()
            return
        }
        // A legacy queue has no provable account owner. Never attach it to a new session.
        val storedAccount = stored.optString("account")
        if (storedAccount != settings.accountKey) {
            items.clear()
            itemsAccount = null
            dirty = false
            return
        }
        val array = stored.optJSONArray("items") ?: run {
            rememberUnreadable()
            return
        }
        items.clear()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            items.add(EgvUpload.fromJson(obj))
        }
        items.sortBy { it.timeMs }
        itemsAccount = storedAccount
        dirty = false
    }

    /**
     * The durable copy is unreadable or malformed: keep the rows we still hold in
     * memory, but only if they provably belong to the current account — rows whose
     * owner is unknown or different are dropped rather than leaked across a
     * session change.
     */
    private fun rememberUnreadable() {
        if (itemsAccount != settings.accountKey) {
            items.clear()
            itemsAccount = null
            dirty = false
        } else {
            dirty = items.isNotEmpty()
        }
    }

    /**
     * Refresh the in-memory queue from the durable copy — unless the durable copy
     * is known to be behind memory, in which case memory is authoritative and
     * must not be overwritten by the stale file.
     */
    private fun sync() {
        if (!dirty) {
            load()
            return
        }
        // Dirty rows are kept only while their recorded owner is the current account.
        if (itemsAccount != settings.accountKey) {
            items.clear()
            itemsAccount = null
            dirty = false
        }
    }

    private fun persist() {
        // sync() guarantees in-memory rows belong to the current account before
        // any mutation, so the durable copy records the current owner.
        itemsAccount = settings.accountKey
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