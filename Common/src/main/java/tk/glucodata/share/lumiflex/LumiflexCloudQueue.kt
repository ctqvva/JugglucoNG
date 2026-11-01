package tk.glucodata.share.lumiflex

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class LumiflexCloudQueue(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()
    private val items = ArrayList<CgmRecord>()

    init {
        synchronized(lock) { load() }
    }

    fun enqueue(record: CgmRecord) {
        synchronized(lock) {
            val duplicate = items.any {
                it.serial == record.serial && it.timeOffsetMinutes == record.timeOffsetMinutes
            }
            if (duplicate) return
            items.add(record)
            items.sortBy { it.timeMs }
            while (items.size > LumiflexCloudConstants.QUEUE_CAP) {
                items.removeAt(0)
            }
            persist()
        }
    }

    fun peek(): List<CgmRecord> = synchronized(lock) { items.toList() }

    fun remove(records: Collection<CgmRecord>) {
        synchronized(lock) {
            if (records.isEmpty()) return
            val keys = records.map { it.serial to it.timeOffsetMinutes }.toSet()
            items.removeAll { keys.contains(it.serial to it.timeOffsetMinutes) }
            persist()
        }
    }

    fun size(): Int = synchronized(lock) { items.size }

    private fun load() {
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return
        items.clear()
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
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(array.toString())
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
