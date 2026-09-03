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
import tk.glucodata.GlucoseReadingSource
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorCurrentSnapshot
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSnapshot
import tk.glucodata.drivers.ManagedSensorViewModeStore
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
        private const val MIN_REASONABLE_TIMESTAMP_MS = 946_684_800_000L
        private const val MAX_FUTURE_TIMESTAMP_DRIFT_MS = 10L * 60L * 1000L
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
    @Volatile private var latestAutoMgdl: Float = Float.NaN
    @Volatile private var latestCalibratedMgdl: Float = Float.NaN
    @Volatile private var latestRawMgdl: Float = Float.NaN
    @Volatile private var latestRateMgdlPerMin: Float = 0f
    @Volatile private var viewModeValue: Int = ManagedSensorViewModeStore.read(Applic.app, serial, 0)

    init {
        mActiveDeviceAddress = url
    }

    override var viewMode: Int
        get() = viewModeValue
        set(value) {
            val normalized = ManagedSensorViewModeStore.sanitize(value)
            viewModeValue = normalized
            ManagedSensorViewModeStore.write(Applic.app, SerialNumber, normalized)
        }

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
        val glucoseMgdl = latestAutoMgdl
            .takeIf { it.isFinite() && it > 0f }
            ?: latestReadingMgdl
        val rawMgdl = latestRawMgdl
        if (timestampMs <= 0L || !glucoseMgdl.isFinite() || glucoseMgdl <= 0f) return null
        if (kotlin.math.abs(System.currentTimeMillis() - timestampMs) > maxAgeMillis) return null
        val glucoseDisplay = if (Applic.unit == 1) glucoseMgdl / MGDL_PER_MMOLL else glucoseMgdl
        val calibratedDisplay = if (latestCalibratedMgdl.isFinite() && latestCalibratedMgdl > 0f) {
            if (Applic.unit == 1) latestCalibratedMgdl / MGDL_PER_MMOLL else latestCalibratedMgdl
        } else {
            Float.NaN
        }
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
            calibratedGlucoseValue = calibratedDisplay,
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
            supportsDisplayModes = supportsDisplayModes(),
            supportsManualCalibration = false,
            supportsHardwareReset = false,
            isVendorConnected = phase == Phase.FOLLOWING,
            vendorModel = localizedString(R.string.api_source_title, "API source"),
        )

    override fun supportsDisplayModes(): Boolean = true

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
        lastImportedHistoryTailMs = 0L
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
            VirtualGlucoseSensorBridge.pruneFutureHistory(SerialNumber, "API source")
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
            source = GlucoseReadingSource.API,
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
        latestAutoMgdl = latest.storageGlucoseMgdl
        latestCalibratedMgdl = latest.calibratedMgdl
        latestRawMgdl = latest.rawMgdl
        latestRateMgdlPerMin = rate
        VirtualGlucoseSensorBridge.publishCurrent(
            sensorSerial = SerialNumber,
            reading = latest.copy(rate = rate),
            sensorGen = SENSOR_GEN,
            logLabel = "API source",
            source = GlucoseReadingSource.API,
        )
    }

    private fun fetchReadings(): List<VirtualGlucoseSensorBridge.Reading> {
        return when (ApiGlucoseSourceRegistry.normalizePreset(preset)) {
            ApiGlucoseSourceRegistry.PRESET_TELEGRAM_BOT -> fetchTelegramReadings()
            ApiGlucoseSourceRegistry.PRESET_VK_DIRECT -> fetchVkDirectReadings()
            else -> fetchHttpReadings()
        }
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
                .flatMap { parseMessageText(it).asSequence() }
                .filter { it.timestampMs >= cutoff }
                .distinctBy { it.timestampMs }
                .sortedBy { it.timestampMs }
                .toList()
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchTelegramReadings(): List<VirtualGlucoseSensorBridge.Reading> {
        val context = Applic.app
        val offset = context?.let(ApiGlucoseSourceRegistry::loadTelegramUpdateOffset) ?: 0L
        val fields = linkedMapOf(
            "limit" to "100",
            "timeout" to "0",
            "allowed_updates" to JSONArray()
                .put("message")
                .put("edited_message")
                .put("channel_post")
                .put("edited_channel_post")
                .toString()
        )
        if (offset > 0L) {
            fields["offset"] = offset.toString()
        }
        val telegramUrl = ApiGlucoseSourceRegistry.normalizeUrl(
            url.replace("{token}", token.trim().removePrefix("bot"))
        )
        val connection = (URL(telegramUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "JugglucoNG API source")
            applyHeaders(headers)
        }
        try {
            connection.outputStream.use { it.write(formEncode(fields).toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Telegram source HTTP $code: ${body.take(160)}")
            }
            val root = JSONObject(body)
            if (!root.optBoolean("ok", false)) {
                throw IllegalStateException(
                    root.optString("description", "Telegram Bot API error")
                        .ifBlank { "Telegram Bot API error" }
                )
            }
            val updates = root.optJSONArray("result") ?: JSONArray()
            val allowedPeers = parseTelegramAllowedPeers(peerId)
            val readings = ArrayList<VirtualGlucoseSensorBridge.Reading>()
            var nextOffset = offset
            for (index in 0 until updates.length()) {
                val update = updates.optJSONObject(index) ?: continue
                val updateId = update.optLong("update_id", 0L)
                if (updateId >= nextOffset) {
                    nextOffset = updateId + 1L
                }
                val message = telegramMessage(update) ?: continue
                if (!isTelegramMessageAllowed(message, allowedPeers)) continue
                val text = message.optString("text", "").ifBlank {
                    message.optString("caption", "")
                }
                if (text.isNotBlank()) {
                    readings += parseMessageText(text)
                }
            }
            if (context != null && nextOffset > offset) {
                ApiGlucoseSourceRegistry.saveTelegramUpdateOffset(context, nextOffset)
            }
            return readings.distinctBy { it.timestampMs }
                .sortedBy { it.timestampMs }
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
        importJournalPayload(trimmed)
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

    private fun parseMessageText(message: String): List<VirtualGlucoseSensorBridge.Reading> {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val jsonReadings = runCatching { parseOutboundJson(trimmed) }
                .getOrDefault(emptyList())
            if (jsonReadings.isNotEmpty()) return jsonReadings
        }
        return parseGlucoWatchMessage(trimmed)?.let(::listOf).orEmpty()
    }

    private fun jsonArrayObjects(array: JSONArray): List<JSONObject> {
        val objects = ArrayList<JSONObject>(array.length())
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let(objects::add)
        }
        return objects
    }

    private fun parseJsonReading(entry: JSONObject): VirtualGlucoseSensorBridge.Reading? {
        val primaryMgdl = firstFiniteField(
            entry,
            "glucose_mgdl",
            "sgv",
            "mgdl",
            "calibrated_glucose_mgdl",
            "calibrated_mgdl",
            "calibratedMgdl"
        ) ?: firstFiniteField(
            entry,
            "glucose_mmol",
            "mmol",
            "calibrated_glucose_mmol",
            "calibrated_mmol"
        )?.let { it * MGDL_PER_MMOLL } ?: return null

        val autoMgdl = firstFiniteField(
            entry,
            "auto_glucose_mgdl",
            "auto_mgdl",
            "autoMgdl",
            "uncalibrated_glucose_mgdl",
            "uncalibrated_mgdl"
        ) ?: firstFiniteField(
            entry,
            "auto_glucose_mmol",
            "auto_mmol",
            "uncalibrated_glucose_mmol",
            "uncalibrated_mmol"
        )?.let { it * MGDL_PER_MMOLL } ?: Double.NaN

        val calibratedMgdl = if (autoMgdl.isFinite() && autoMgdl > 0.0) {
            primaryMgdl
        } else {
            firstFiniteField(
                entry,
                "calibrated_glucose_mgdl",
                "calibrated_mgdl",
                "calibratedMgdl"
            ) ?: firstFiniteField(
                entry,
                "calibrated_glucose_mmol",
                "calibrated_mmol"
            )?.let { it * MGDL_PER_MMOLL } ?: Double.NaN
        }

        val timestamp = normalizeTimestamp(
            firstLong(
                entry.optLong("timestamp", 0L),
                entry.optLong("date", 0L),
                entry.optLong("mills", 0L),
                entry.optLong("datetime", 0L),
            ),
            entry.toString()
        ) ?: return null

        val rate = firstFiniteAny(entry.optDouble("rate_mgdl_per_min", Double.NaN))?.toFloat()
            ?: firstFiniteAny(entry.optDouble("rate_mmol_per_min", Double.NaN))
                ?.let { (it * MGDL_PER_MMOLL).toFloat() }
            ?: Float.NaN

        val rawMgdl = parseJsonRawMgdl(entry) ?: Double.NaN

        return VirtualGlucoseSensorBridge.Reading(
            timestampMs = timestamp,
            glucoseMgdl = primaryMgdl.toFloat(),
            autoMgdl = autoMgdl.toFloat(),
            calibratedMgdl = calibratedMgdl.toFloat(),
            rawMgdl = rawMgdl.toFloat(),
            rate = rate,
        )
    }

    private fun parseGlucoWatchText(body: String): List<VirtualGlucoseSensorBridge.Reading> =
        extractMessageTexts(body).flatMap(::parseMessageText)

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
        }.filter { text ->
            val normalized = text.trim()
            normalized.isNotEmpty() && (
                normalized.startsWith("{") ||
                    normalized.startsWith("[") ||
                    normalized.contains("GV:") ||
                    normalized.contains("MGDL:")
                )
        }
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

        val timestampRaw = fields["TS"]?.toLongOrNull()
            ?: fields["TIMESTAMP"]?.toLongOrNull()
            ?: 0L
        val timestamp = normalizeTimestamp(timestampRaw, message) ?: return null
        val glucoseMgdl = fields["MGDL"]?.toDoubleOrNull()
            ?: fields["GLUCOSE_MGDL"]?.toDoubleOrNull()
            ?: fields["GV"]?.toDoubleOrNull()?.let { it * MGDL_PER_MMOLL }
            ?: fields["MMOL"]?.toDoubleOrNull()?.let { it * MGDL_PER_MMOLL }
            ?: return null
        if (!glucoseMgdl.isFinite() || glucoseMgdl <= 0.0) return null
        val autoMgdl = fields["AUTO_MGDL"]?.toDoubleOrNull()
            ?: fields["AMGDL"]?.toDoubleOrNull()
            ?: fields["AUTO_VALUE_MGDL"]?.toDoubleOrNull()
            ?: fields["AUTO_MMOL"]?.toDoubleOrNull()?.let { it * MGDL_PER_MMOLL }
            ?: fields["AMMOL"]?.toDoubleOrNull()?.let { it * MGDL_PER_MMOLL }
            ?: fields["AUTO"]?.toDoubleOrNull()?.let(::inferCompactGlucoseMgdl)
            ?: fields["AUTO_VALUE"]?.toDoubleOrNull()?.let(::inferCompactGlucoseMgdl)
            ?: Double.NaN
        val calibratedMgdl = if (autoMgdl.isFinite() && autoMgdl > 0.0) glucoseMgdl else Double.NaN
        val rate = fields["RATE_MGDL"]?.toDoubleOrNull()
            ?: fields["RTMGDL"]?.toDoubleOrNull()
            ?: fields["RATE_MGDL_PER_MIN"]?.toDoubleOrNull()
            ?: fields["RT"]?.toDoubleOrNull()?.let { it * MGDL_PER_MMOLL }
            ?: fields["RATE_MMOL"]?.toDoubleOrNull()?.let { it * MGDL_PER_MMOLL }
            ?: fields["RATE_MMOL_PER_MIN"]?.toDoubleOrNull()?.let { it * MGDL_PER_MMOLL }
        val rawMgdl = fields["RMGDL"]?.toDoubleOrNull()
            ?: fields["RAW_MGDL"]?.toDoubleOrNull()
            ?: fields["RAW_GLUC_MGDL"]?.toDoubleOrNull()
            ?: fields["RMMOL"]?.toDoubleOrNull()?.let { it * MGDL_PER_MMOLL }
            ?: fields["RAW_MMOL"]?.toDoubleOrNull()?.let { it * MGDL_PER_MMOLL }
            ?: fields["RAW"]?.toDoubleOrNull()?.let(::inferCompactGlucoseMgdl)
            ?: Double.NaN
        return VirtualGlucoseSensorBridge.Reading(
            timestampMs = timestamp,
            glucoseMgdl = glucoseMgdl.toFloat(),
            autoMgdl = autoMgdl.toFloat(),
            calibratedMgdl = calibratedMgdl.toFloat(),
            rawMgdl = rawMgdl.toFloat(),
            rate = rate?.toFloat() ?: Float.NaN,
        )
    }

    private fun parseTelegramAllowedPeers(raw: String): Set<String> =
        raw.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.lowercase(Locale.US) }
            .toSet()

    private fun telegramMessage(update: JSONObject): JSONObject? =
        listOf("message", "edited_message", "channel_post", "edited_channel_post")
            .firstNotNullOfOrNull { key -> update.optJSONObject(key) }

    private fun isTelegramMessageAllowed(message: JSONObject, allowedPeers: Set<String>): Boolean {
        if (allowedPeers.isEmpty()) return false
        if ("*" in allowedPeers) return true
        val chat = message.optJSONObject("chat") ?: return false
        val candidates = ArrayList<String>(4)
        chat.opt("id")?.toString()?.takeIf { it.isNotBlank() }?.let(candidates::add)
        chat.optString("username", "")
            .takeIf { it.isNotBlank() }
            ?.lowercase(Locale.US)
            ?.let { username ->
                candidates += username
                candidates += "@$username"
            }
        message.optJSONObject("from")
            ?.optString("username", "")
            ?.takeIf { it.isNotBlank() }
            ?.lowercase(Locale.US)
            ?.let { username ->
                candidates += username
                candidates += "@$username"
            }
        return candidates.any { it.lowercase(Locale.US) in allowedPeers }
    }

    private fun parseJsonRawMgdl(entry: JSONObject): Double? {
        val explicit = firstFiniteField(
            entry,
            "raw_glucose_mgdl",
            "raw_mgdl",
            "rawMgdl",
            "raw_gluc_mgdl"
        ) ?: firstFiniteField(
            entry,
            "raw_glucose_mmol",
            "raw_mmol",
            "rawMmol"
        )?.let { it * MGDL_PER_MMOLL }
        if (explicit != null) return explicit

        val rawValue = firstFiniteField(entry, "raw_value", "raw") ?: return null
        val unit = entry.optString("raw_unit", "")
            .ifBlank { entry.optString("display_unit", "") }
            .ifBlank { entry.optString("unit", "") }
            .lowercase(Locale.US)
        return when {
            unit.contains("mmol") -> rawValue * MGDL_PER_MMOLL
            unit.contains("mg") -> rawValue
            rawValue in 1.0..40.0 -> rawValue * MGDL_PER_MMOLL
            rawValue in 40.0..600.0 -> rawValue
            else -> null
        }
    }

    private fun inferCompactGlucoseMgdl(value: Double): Double =
        if (value in 1.0..40.0) value * MGDL_PER_MMOLL else value

    private fun importJournalPayload(raw: String): Int {
        if (raw.isBlank()) return 0
        return runCatching {
            val type = Class.forName("tk.glucodata.OutboundApiJournalSnapshot")
            val sourcePrefix = "api:$SerialNumber"
            val method = runCatching {
                type.getMethod("importFromJsonForSource", String::class.java, String::class.java)
            }.getOrNull()
            if (method != null) {
                (method.invoke(null, raw, sourcePrefix) as? Int) ?: 0
            } else {
                val legacyMethod = type.getMethod("importFromJson", String::class.java)
                (legacyMethod.invoke(null, raw) as? Int) ?: 0
            }
        }.onSuccess { imported ->
            if (imported > 0) {
                Log.i(TAG, "Imported $imported API journal entries")
                UiRefreshBus.requestDataRefresh()
            }
        }.onFailure {
            Log.w(TAG, "journal import ignored: ${it.message}")
        }.getOrDefault(0)
    }

    private fun firstFinite(vararg values: Double): Double? =
        values.firstOrNull { it.isFinite() && it > 0.0 }

    private fun firstFiniteAny(vararg values: Double): Double? =
        values.firstOrNull { it.isFinite() }

    private fun firstFiniteField(entry: JSONObject, vararg keys: String): Double? =
        keys.asSequence()
            .map { key -> entry.optDouble(key, Double.NaN) }
            .firstOrNull { it.isFinite() && it > 0.0 }

    private fun firstLong(vararg values: Long): Long =
        values.firstOrNull { it > 0L } ?: 0L

    private fun normalizeTimestamp(raw: Long, sourcePreview: String = ""): Long? {
        if (raw <= 0L) return null
        val millis = when (raw) {
            in 1_000_000_000L..9_999_999_999L -> raw * 1_000L
            in 1_000_000_000_000L..9_999_999_999_999L -> raw
            in 10_000_000_000_000L..99_999_999_999_999L -> {
                val repaired = raw / 10L
                if (raw % 10L == 0L && isPlausibleTimestamp(repaired)) {
                    Log.w(TAG, "Repaired API source timestamp $raw -> $repaired")
                    repaired
                } else {
                    logRejectedTimestamp(raw, "unsupported precision", sourcePreview)
                    return null
                }
            }
            in 1_000_000_000_000_000L..9_999_999_999_999_999L -> raw / 1_000L
            in 1_000_000_000_000_000_000L..Long.MAX_VALUE -> raw / 1_000_000L
            else -> {
                logRejectedTimestamp(raw, "unsupported precision", sourcePreview)
                return null
            }
        }
        if (!isPlausibleTimestamp(millis)) {
            logRejectedTimestamp(raw, "out-of-range timestamp", sourcePreview)
            return null
        }
        return millis
    }

    private fun isPlausibleTimestamp(timestampMs: Long): Boolean {
        val maxAccepted = System.currentTimeMillis() + MAX_FUTURE_TIMESTAMP_DRIFT_MS
        return timestampMs in MIN_REASONABLE_TIMESTAMP_MS..maxAccepted
    }

    private fun logRejectedTimestamp(raw: Long, reason: String, sourcePreview: String) {
        val preview = sourcePreview
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(160)
        Log.w(TAG, "Ignored API source reading with $reason: ts=$raw preview=$preview")
    }

    private fun formEncode(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}
