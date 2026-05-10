package tk.glucodata.drivers.api

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorCurrentSnapshot
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSnapshot
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

class ApiGlucoseSourceManager(
    serial: String,
    private val preset: String,
    private val url: String,
    private val token: String,
    private val peerId: String,
    private val apiVersion: String,
    private val headers: String,
    private val format: String,
    pollSeconds: Int,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SENSOR_GEN), ManagedBluetoothSensorDriver {

    companion object {
        private const val TAG = "ApiGlucoseSource"
        private const val SENSOR_GEN = 0
        private const val RETRY_INTERVAL_MS = 30_000L
        private const val MGDL_PER_MMOLL = 18.0182f
    }

    private enum class Phase {
        IDLE,
        SYNCING,
        FOLLOWING,
    }

    private val pollIntervalMs = pollSeconds.coerceAtLeast(30) * 1000L
    private val handlerThread = HandlerThread("ApiGlucoseSource-$serial").also { it.start() }
    private val handler = Handler(handlerThread.looper)
    private val pollRunnable = Runnable { refresh("poll") }

    @Volatile private var phase: Phase = Phase.IDLE
    @Volatile private var status: String = localizedString(R.string.api_source_status_idle, "API source idle")
    @Volatile private var lastImportedHistoryTailMs: Long = 0L
    @Volatile private var latestReadingTimeMs: Long = 0L
    @Volatile private var latestReadingMgdl: Float = Float.NaN
    @Volatile private var latestRawMgdl: Float = Float.NaN
    @Volatile private var latestRateMgdlPerMin: Float = 0f

    init {
        mActiveDeviceAddress = url
    }

    override var viewMode: Int = 0

    override fun canConnectWithoutDataptr(): Boolean = true

    override fun hasNativeSensorBacking(): Boolean = false

    override fun shouldUseNativeHistorySync(): Boolean = false

    override fun managesLiveRoomStorage(): Boolean = true

    override fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = false

    override fun isManagedOutsideNativeActiveSet(): Boolean = true

    override fun shouldShowSearchingStatusWhenIdle(): Boolean = false

    override fun matchesManagedSensorId(sensorId: String?): Boolean =
        ApiGlucoseSourceRegistry.matchesSensorId(SerialNumber, sensorId)

    override fun mygetDeviceName(): String = localizedString(R.string.api_source_title, "API source")

    override fun getDetailedBleStatus(): String = status

    override fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? {
        val timestampMs = latestReadingTimeMs
        val glucoseMgdl = latestReadingMgdl
        val rawMgdl = latestRawMgdl
        if (timestampMs <= 0L || !glucoseMgdl.isFinite() || glucoseMgdl <= 0f) return null
        if (kotlin.math.abs(System.currentTimeMillis() - timestampMs) > maxAgeMillis) return null
        val glucoseDisplay = if (Applic.unit == 1) glucoseMgdl / MGDL_PER_MMOLL else glucoseMgdl
        val rawDisplay = if (rawMgdl.isFinite() && rawMgdl > 0f) {
            if (Applic.unit == 1) rawMgdl / MGDL_PER_MMOLL else rawMgdl
        } else {
            Float.NaN
        }
        val rateDisplay = if (Applic.unit == 1) latestRateMgdlPerMin / MGDL_PER_MMOLL else latestRateMgdlPerMin
        return ManagedSensorCurrentSnapshot(
            timeMillis = timestampMs,
            glucoseValue = glucoseDisplay,
            rawGlucoseValue = rawDisplay,
            rate = rateDisplay,
            sensorGen = SENSOR_GEN,
        )
    }

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot =
        ManagedSensorUiSnapshot(
            serial = SerialNumber,
            displayName = localizedString(R.string.api_source_title, "API source"),
            deviceAddress = url,
            uiFamily = ManagedSensorUiFamily.GENERIC,
            connectionStatus = when (phase) {
                Phase.FOLLOWING -> localizedString(R.string.api_source_status_following, "Following API source")
                Phase.SYNCING -> localizedString(R.string.api_source_status_syncing, "Refreshing API source")
                Phase.IDLE -> localizedString(R.string.api_source_title, "API source")
            },
            detailedStatus = status,
            subtitleStatus = status,
            showConnectionStatusInDetails = true,
            isUiEnabled = true,
            isActive = SensorIdentity.matches(activeSensorId, SerialNumber),
            dataptr = 0L,
            viewMode = viewMode,
            supportsDisplayModes = false,
            supportsManualCalibration = false,
            supportsHardwareReset = false,
            isVendorConnected = phase == Phase.FOLLOWING,
            vendorModel = localizedString(R.string.api_source_title, "API source"),
        )

    override fun connectDevice(delayMillis: Long): Boolean {
        stop = false
        scheduleRefresh(delayMillis.coerceAtLeast(0L))
        return true
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        runCatching { handlerThread.quitSafely() }
        super.close()
    }

    override fun softDisconnect() {
        stop = true
        handler.removeCallbacksAndMessages(null)
        setStatus(Phase.IDLE, localizedString(R.string.api_source_status_paused, "API source paused"))
    }

    override fun softReconnect() {
        stop = false
        scheduleRefresh(0L)
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        stop = true
        handler.removeCallbacksAndMessages(null)
        if (wipeData) {
            Applic.app?.let { ApiGlucoseSourceRegistry.disableSourceSensor(it) }
        }
    }

    override fun removeManagedPersistence(context: Context) {
        ApiGlucoseSourceRegistry.disableSourceSensor(context)
    }

    private fun localizedString(resId: Int, fallback: String): String =
        Applic.app?.getString(resId) ?: fallback

    private fun setStatus(phase: Phase, status: String) {
        this.phase = phase
        this.status = status
        UiRefreshBus.requestStatusRefresh()
    }

    private fun scheduleRefresh(delayMillis: Long) {
        handler.removeCallbacks(pollRunnable)
        if (!stop) {
            handler.postDelayed(pollRunnable, delayMillis)
        }
    }

    private fun refresh(reason: String) {
        if (stop) return
        if (url.isBlank()) {
            setStatus(Phase.IDLE, localizedString(R.string.api_source_status_config_needed, "Enter source URL"))
            return
        }
        setStatus(Phase.SYNCING, localizedString(R.string.api_source_status_syncing, "Refreshing API source"))
        try {
            val readings = fetchReadings()
            if (readings.isEmpty()) {
                setStatus(Phase.IDLE, localizedString(R.string.api_source_status_no_readings, "No API readings yet"))
                scheduleRefresh(pollIntervalMs)
                return
            }
            importHistory(readings)
            publishLatest(readings)
            setStatus(Phase.FOLLOWING, localizedString(R.string.api_source_status_following, "Following API source"))
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "API source refreshed (%s): %s points latest=%.1f",
                    reason,
                    readings.size,
                    readings.last().glucoseMgdl,
                ),
            )
            UiRefreshBus.requestDataRefresh()
            scheduleRefresh(pollIntervalMs)
        } catch (t: Throwable) {
            Log.stack(TAG, "refresh($reason)", t)
            setStatus(Phase.IDLE, localizedString(R.string.api_source_status_sync_failed, "API source sync failed"))
            scheduleRefresh(RETRY_INTERVAL_MS)
        }
    }

    private fun importHistory(readings: List<VirtualGlucoseSensorBridge.Reading>) {
        val tailMs = readings.maxOfOrNull { it.timestampMs } ?: 0L
        if (tailMs > 0L && tailMs <= lastImportedHistoryTailMs) return
        VirtualGlucoseSensorBridge.importHistory(
            sensorSerial = SerialNumber,
            readings = readings,
            logLabel = "API source",
        )
        if (tailMs > 0L) {
            lastImportedHistoryTailMs = tailMs
        }
    }

    private fun publishLatest(readings: List<VirtualGlucoseSensorBridge.Reading>) {
        val latest = readings.lastOrNull() ?: return
        val previous = readings.dropLast(1).lastOrNull()
        val rate = if (latest.rate.isFinite()) {
            latest.rate
        } else if (previous != null && latest.timestampMs > previous.timestampMs) {
            val minutes = (latest.timestampMs - previous.timestampMs) / 60000f
            if (minutes > 0f) (latest.glucoseMgdl - previous.glucoseMgdl) / minutes else 0f
        } else {
            0f
        }
        latestReadingTimeMs = latest.timestampMs
        latestReadingMgdl = latest.glucoseMgdl
        latestRawMgdl = latest.rawMgdl
        latestRateMgdlPerMin = rate
        VirtualGlucoseSensorBridge.publishCurrent(
            sensorSerial = SerialNumber,
            reading = latest.copy(rate = rate),
            sensorGen = SENSOR_GEN,
            logLabel = "API source",
        )
    }

    private fun fetchReadings(): List<VirtualGlucoseSensorBridge.Reading> {
        if (ApiGlucoseSourceRegistry.normalizePreset(preset) == ApiGlucoseSourceRegistry.PRESET_VK_DIRECT) {
            return fetchVkDirectReadings()
        }
        return fetchHttpReadings()
    }

    private fun fetchHttpReadings(): List<VirtualGlucoseSensorBridge.Reading> {
        val connection = (URL(ApiGlucoseSourceRegistry.normalizeUrl(url)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json, text/plain")
            setRequestProperty("User-Agent", "JugglucoNG API source")
            applyHeaders(headers)
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("API source HTTP $code: ${body.take(160)}")
            }
            return when (ApiGlucoseSourceRegistry.normalizeFormat(format)) {
                ApiGlucoseSourceRegistry.FORMAT_GLUCO_WATCH_TEXT -> parseGlucoWatchText(body)
                else -> parseOutboundJson(body)
            }.distinctBy { it.timestampMs }
                .sortedBy { it.timestampMs }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchVkDirectReadings(): List<VirtualGlucoseSensorBridge.Reading> {
        val fields = linkedMapOf(
            "access_token" to token.trim(),
            "v" to apiVersion.trim().ifBlank { ApiGlucoseSourceRegistry.DEFAULT_VK_API_VERSION },
            "peer_id" to peerId.trim(),
            "count" to "200"
        )
        val connection = (URL(ApiGlucoseSourceRegistry.normalizeUrl(url)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "JugglucoNG API source")
        }
        try {
            connection.outputStream.use { it.write(formEncode(fields).toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("VK source HTTP $code: ${body.take(160)}")
            }
            val root = JSONObject(body)
            root.optJSONObject("error")?.let { error ->
                throw IllegalStateException(
                    error.optString("error_msg", "VK API error").ifBlank { "VK API error" }
                )
            }
            val items = root.optJSONObject("response")?.optJSONArray("items") ?: JSONArray()
            val messages = ArrayList<String>(items.length())
            for (index in 0 until items.length()) {
                items.optJSONObject(index)
                    ?.optString("text", "")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(messages::add)
            }
            val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            return messages.asSequence()
                .mapNotNull(::parseGlucoWatchMessage)
                .filter { it.timestampMs >= cutoff }
                .distinctBy { it.timestampMs }
                .sortedBy { it.timestampMs }
                .toList()
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.applyHeaders(rawHeaders: String) {
        rawHeaders.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@forEach
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    setRequestProperty(name, value)
                }
            }
    }

    private fun parseOutboundJson(body: String): List<VirtualGlucoseSensorBridge.Reading> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        val objects = when {
            trimmed.startsWith("[") -> jsonArrayObjects(JSONArray(trimmed))
            else -> {
                val root = JSONObject(trimmed)
                when {
                    root.optJSONArray("readings") != null -> jsonArrayObjects(root.getJSONArray("readings"))
                    root.optJSONArray("entries") != null -> jsonArrayObjects(root.getJSONArray("entries"))
                    else -> listOf(root)
                }
            }
        }
        return objects.mapNotNull(::parseJsonReading)
    }

    private fun jsonArrayObjects(array: JSONArray): List<JSONObject> {
        val objects = ArrayList<JSONObject>(array.length())
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let(objects::add)
        }
        return objects
    }

    private fun parseJsonReading(entry: JSONObject): VirtualGlucoseSensorBridge.Reading? {
        importJournal(entry)
        val mgdl = firstFinite(
            entry.optDouble("glucose_mgdl", Double.NaN),
            entry.optDouble("sgv", Double.NaN),
            entry.optDouble("mgdl", Double.NaN),
        ) ?: firstFinite(
            entry.optDouble("glucose_mmol", Double.NaN),
            entry.optDouble("mmol", Double.NaN),
        )?.let { it * MGDL_PER_MMOLL } ?: return null

        val timestamp = normalizeTimestamp(
            firstLong(
                entry.optLong("timestamp", 0L),
                entry.optLong("date", 0L),
                entry.optLong("mills", 0L),
                entry.optLong("datetime", 0L),
            )
        ) ?: return null

        val rate = firstFiniteAny(entry.optDouble("rate_mgdl_per_min", Double.NaN))?.toFloat()
            ?: firstFiniteAny(entry.optDouble("rate_mmol_per_min", Double.NaN))
                ?.let { (it * MGDL_PER_MMOLL).toFloat() }
            ?: Float.NaN

        val rawMgdl = firstFinite(
            entry.optDouble("raw_value", Double.NaN),
            entry.optDouble("raw_glucose_mgdl", Double.NaN),
            entry.optDouble("raw_mgdl", Double.NaN),
            entry.optDouble("rawMgdl", Double.NaN),
        ) ?: firstFinite(
            entry.optDouble("raw_glucose_mmol", Double.NaN),
            entry.optDouble("raw_mmol", Double.NaN),
        )?.let { it * MGDL_PER_MMOLL } ?: Double.NaN

        return VirtualGlucoseSensorBridge.Reading(
            timestampMs = timestamp,
            glucoseMgdl = mgdl.toFloat(),
            rawMgdl = rawMgdl.toFloat(),
            rate = rate,
        )
    }

    private fun parseGlucoWatchText(body: String): List<VirtualGlucoseSensorBridge.Reading> =
        extractMessageTexts(body).mapNotNull(::parseGlucoWatchMessage)

    private fun extractMessageTexts(body: String): List<String> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching {
            when {
                trimmed.startsWith("[") -> collectJsonMessages(JSONArray(trimmed))
                trimmed.startsWith("{") -> collectJsonMessages(JSONObject(trimmed))
                else -> trimmed.lineSequence().toList()
            }
        }.getOrElse {
            trimmed.lineSequence().toList()
        }.filter { it.contains("GV:") || it.contains("MGDL:") }
    }

    private fun collectJsonMessages(value: Any?): List<String> {
        val out = ArrayList<String>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    listOf("message", "text", "body").forEach { key ->
                        node.optString(key, "").takeIf { it.isNotBlank() }?.let(out::add)
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        walk(node.opt(keys.next()))
                    }
                }
                is JSONArray -> {
                    for (index in 0 until node.length()) {
                        walk(node.opt(index))
                    }
                }
            }
        }
        walk(value)
        return out
    }

    private fun parseGlucoWatchMessage(message: String): VirtualGlucoseSensorBridge.Reading? {
        val fields = message
            .split('|')
            .mapNotNull { part ->
                val separator = part.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                part.substring(0, separator).trim().uppercase(Locale.US) to
                    part.substring(separator + 1).trim()
            }
            .toMap()

        val timestamp = normalizeTimestamp(fields["TS"]?.toLongOrNull() ?: 0L) ?: return null
        val glucoseMgdl = fields["MGDL"]?.toDoubleOrNull()
            ?: fields["GV"]?.toDoubleOrNull()?.let { it * MGDL_PER_MMOLL }
            ?: return null
        if (!glucoseMgdl.isFinite() || glucoseMgdl <= 0.0) return null
        val rate = fields["RT"]?.toDoubleOrNull()
            ?.let { (it * MGDL_PER_MMOLL).toFloat() }
            ?: Float.NaN
        val rawMgdl = fields["RMGDL"]?.toDoubleOrNull()
            ?: fields["RAW_MGDL"]?.toDoubleOrNull()
            ?: fields["RAW"]?.toDoubleOrNull()
            ?: fields["RMMOL"]?.toDoubleOrNull()?.let { it * MGDL_PER_MMOLL }
            ?: Double.NaN
        return VirtualGlucoseSensorBridge.Reading(
            timestampMs = timestamp,
            glucoseMgdl = glucoseMgdl.toFloat(),
            rawMgdl = rawMgdl.toFloat(),
            rate = rate,
        )
    }

    private fun importJournal(entry: JSONObject) {
        val journal = when {
            entry.has("journal") -> entry.opt("journal")
            entry.has("events") -> JSONObject().put("events", entry.optJSONArray("events"))
            else -> null
        } ?: return
        runCatching {
            val type = Class.forName("tk.glucodata.OutboundApiJournalSnapshot")
            val method = type.getMethod("importFromJson", String::class.java)
            method.invoke(null, journal.toString())
        }.onFailure {
            Log.w(TAG, "journal import ignored: ${it.message}")
        }
    }

    private fun firstFinite(vararg values: Double): Double? =
        values.firstOrNull { it.isFinite() && it > 0.0 }

    private fun firstFiniteAny(vararg values: Double): Double? =
        values.firstOrNull { it.isFinite() }

    private fun firstLong(vararg values: Long): Long =
        values.firstOrNull { it > 0L } ?: 0L

    private fun normalizeTimestamp(raw: Long): Long? {
        if (raw <= 0L) return null
        val millis = if (raw < 10_000_000_000L) raw * 1000L else raw
        return millis.takeIf { it > 0L }
    }

    private fun formEncode(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}
