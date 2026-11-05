package tk.glucodata.share.lumiflex

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class LumiflexCloudQueue(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()
    private val items = ArrayList<CgmRecord>()
    private var ownerAccount: String = ""

    init {
        synchronized(lock) { load() }
    }

    fun enqueue(record: CgmRecord, owner: String = ""): Boolean {
        synchronized(lock) {
            adoptOwner(owner)
            var added = false
            append(record) { added = true }
            if (added) persist()
            return added
        }
    }

    fun enqueueAll(records: List<CgmRecord>, owner: String = "") {
        synchronized(lock) {
            adoptOwner(owner)
            var changed = false
            records.forEach { record -> append(record) { changed = true } }
            if (changed) persist()
        }
    }

    /** Records for a different account must not be appended to the previous account's backlog. */
    private fun adoptOwner(owner: String) {
        if (owner.isBlank()) return
        if (ownerAccount != owner) {
            items.clear()
            ownerAccount = owner
        }
    }

    private inline fun append(record: CgmRecord, onAdded: () -> Unit) {
        val duplicate = items.any {
            it.serial == record.serial && it.timeOffsetMinutes == record.timeOffsetMinutes
        }
        if (duplicate) return
        items.add(record)
        items.sortBy { it.timeMs }
        while (items.size > LumiflexCloudConstants.QUEUE_CAP) {
            items.removeAt(0)
        }
        onAdded()
    }

    fun peek(): List<CgmRecord> = synchronized(lock) { items.toList() }

    /**
     * Removes matching records. When [owner] is given, the removal only happens while the queue
     * still belongs to that account, so a late reply from a previous session cannot delete the
     * backlog of the session that replaced it.
     */
    fun remove(records: Collection<CgmRecord>, owner: String? = null): Boolean {
        synchronized(lock) {
            if (records.isEmpty()) return false
            if (owner != null && ownerAccount != owner) return false
            val keys = records.map { it.serial to it.timeOffsetMinutes }.toSet()
            items.removeAll { keys.contains(it.serial to it.timeOffsetMinutes) }
            persist()
            return true
        }
    }

    fun size(): Int = synchronized(lock) { items.size }

    fun owner(): String = synchronized(lock) { ownerAccount }

    /** Drops every queued record, e.g. when the authenticated account has changed. */
    fun clear() {
        synchronized(lock) {
            items.clear()
            ownerAccount = ""
            persist()
        }
    }

    /** Drops mismatched or legacy unowned records before they can reach an arbitrary account. */
    fun clearIfOwnerMismatch(expected: String): Boolean {
        synchronized(lock) {
            if (ownerAccount != expected) {
                items.clear()
                ownerAccount = expected
                persist()
                return true
            }
            return false
        }
    }

    private fun load() {
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root != null) {
            ownerAccount = root.optString("owner", "")
            parseRecords(root.optJSONArray("records"))
        } else {
            val array = runCatching { JSONArray(text) }.getOrNull() ?: return
            parseRecords(array)
        }
    }

    private fun parseRecords(array: JSONArray?) {
        items.clear()
        if (array == null) return
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            items.add(
                CgmRecord(
                    serial = obj.optString("serial", ""),
                    sensorId = obj.optString("sensorId", obj.optString("serial", "")),
                    sensorIndex = obj.optInt("sensorIndex", 0),
                    timeOffsetMinutes = obj.optInt("timeOffsetMinutes", 0),
                    timeMs = obj.optLong("timeMs", 0L),
                    mgdl = obj.optInt("mgdl", 0),
                    trend = obj.optInt("trend", 0),
                ),
            )
        }
        items.sortBy { it.timeMs }
    }

    private fun persist() {
        runCatching {
            val array = JSONArray()
            items.forEach { record ->
                array.put(
                    JSONObject()
                        .put("serial", record.serial)
                        .put("sensorId", record.sensorId)
                        .put("sensorIndex", record.sensorIndex)
                        .put("timeOffsetMinutes", record.timeOffsetMinutes)
                        .put("timeMs", record.timeMs)
                        .put("mgdl", record.mgdl)
                        .put("trend", record.trend),
                )
            }
            val root = JSONObject()
                .put("owner", ownerAccount)
                .put("records", array)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    companion object {
        private const val FILE_NAME = "lumiflex_cloud_queue.json"
    }
}
