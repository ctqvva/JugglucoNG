package tk.glucodata.drivers.nightscout

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.Applic
import tk.glucodata.HistorySyncAccess
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

class NightscoutFollowerManager(
    serial: String,
    private val url: String,
    private val secret: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SENSOR_GEN), ManagedBluetoothSensorDriver {

    companion object {
        private const val TAG = "NightscoutFollower"
        private const val SENSOR_GEN = 0
        private const val POLL_INTERVAL_MS = 60_000L
        private const val RETRY_INTERVAL_MS = 30_000L
        /** A server that is down overnight must not wake the phone twice a minute until morning. */
        private const val RETRY_BACKOFF_CEILING_MS = 15L * 60_000L
        private const val PROBE_INTERVAL_MS = 59_000L
        private const val DEVICE_STATUS_COUNT = 5
        private const val MMOL_TO_MGDL = 18.0182f
    }

    private enum class Phase {
        IDLE,
        SYNCING,
        FOLLOWING,
    }

    private val handlerThread = HandlerThread("NightscoutFollower-$serial").also { it.start() }
    private val handler = Handler(handlerThread.looper)
    private val pollRunnable = Runnable { refresh("poll") }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val probeRunnable = Runnable { reconnect(System.currentTimeMillis()) }

    @Volatile private var phase: Phase = Phase.IDLE
    @Volatile private var status: String = localizedString(R.string.nightscout_follow_status_idle, "Nightscout follower idle")
    @Volatile private var consecutiveFailures: Int = 0
    @Volatile private var lastImportedHistoryTailMs: Long = 0L
    @Volatile private var latestReadingTimeMs: Long = 0L
    @Volatile private var latestReadingMgdl: Float = Float.NaN
    @Volatile private var latestRateMgdlPerMin: Float = 0f
    @Volatile private var bootstrapHistoryPending =
        !NightscoutFollowerRegistry.hasCompleteHistoryImport(Applic.app, SerialNumber)

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
        NightscoutFollowerRegistry.matchesSensorId(SerialNumber, sensorId)

    override fun mygetDeviceName(): String = localizedString(R.string.nightscout_follow_title, "Nightscout follower")

    override fun getDetailedBleStatus(): String = status

    override fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? {
        val timestampMs = latestReadingTimeMs
        val glucoseMgdl = latestReadingMgdl
        if (timestampMs <= 0L || !glucoseMgdl.isFinite() || glucoseMgdl <= 0f) return null
        if (kotlin.math.abs(System.currentTimeMillis() - timestampMs) > maxAgeMillis) return null
        val glucoseDisplay = if (Applic.unit == 1) glucoseMgdl / MMOL_TO_MGDL else glucoseMgdl
        val rateDisplay = if (Applic.unit == 1) latestRateMgdlPerMin / MMOL_TO_MGDL else latestRateMgdlPerMin
        return ManagedSensorCurrentSnapshot(
            timeMillis = timestampMs,
            glucoseValue = glucoseDisplay,
            rawGlucoseValue = Float.NaN,
            rate = rateDisplay,
            sensorGen = SENSOR_GEN,
        )
    }

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot =
        ManagedSensorUiSnapshot(
            serial = SerialNumber,
            displayName = localizedString(R.string.nightscout_follow_title, "Nightscout follower"),
            deviceAddress = url,
            uiFamily = ManagedSensorUiFamily.GENERIC,
            connectionStatus = when (phase) {
                Phase.FOLLOWING -> localizedString(R.string.nightscout_follow_status_following, "Following Nightscout")
                Phase.SYNCING -> localizedString(R.string.nightscout_follow_status_syncing, "Refreshing Nightscout")
                Phase.IDLE -> localizedString(R.string.nightscout_follow_title, "Nightscout follower")
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
            vendorModel = localizedString(R.string.nightscout_follow_title, "Nightscout follower"),
        )

    override fun connectDevice(delayMillis: Long): Boolean {
        stop = false
        scheduleRefresh(delayMillis.coerceAtLeast(0L))
        mainHandler.removeCallbacks(probeRunnable)
        mainHandler.postDelayed(probeRunnable, PROBE_INTERVAL_MS)
        return true
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        cancelPollAlarm()
        if (stop) {
            // Permanent shutdown: free() sets stop=true before calling close().
            // Quit the HandlerThread so it doesn't outlive the sensor object.
            mainHandler.removeCallbacks(probeRunnable)
            runCatching { handlerThread.quitSafely() }
        } else {
            // Transient disconnect (e.g. Bluetooth off, network drop).
            // Keep the HandlerThread alive and reset to IDLE so reconnect() can
            // restart polling without needing a full sensor teardown/reinit.
            setStatus(Phase.IDLE, localizedString(R.string.nightscout_follow_status_idle, "Nightscout follower idle"))
        }
        super.close()
    }

    override fun softDisconnect() {
        stop = true
        handler.removeCallbacksAndMessages(null)
        cancelPollAlarm()
        mainHandler.removeCallbacks(probeRunnable)
        NightscoutFollowerDeviceStatus.clear()
        setStatus(Phase.IDLE, localizedString(R.string.nightscout_follow_status_paused, "Nightscout follower paused"))
    }

    override fun softReconnect() {
        stop = false
        scheduleRefresh(0L)
    }

    override fun reconnect(now: Long): Boolean {
        if (!stop) {
            if (phase == Phase.IDLE) connectDevice(0)
            mainHandler.removeCallbacks(probeRunnable)
            mainHandler.postDelayed(probeRunnable, PROBE_INTERVAL_MS)
        }
        return true
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        stop = true
        handler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacks(probeRunnable)
        NightscoutFollowerDeviceStatus.clear()
        if (wipeData) {
            Applic.app?.let { NightscoutFollowerRegistry.disableFollowerSensor(it) }
        }
    }

    override fun removeManagedPersistence(context: Context) {
        NightscoutFollowerRegistry.disableFollowerSensor(context)
    }

    private fun localizedString(resId: Int, fallback: String): String =
        Applic.app?.getString(resId) ?: fallback

    private fun setStatus(phase: Phase, status: String) {
        this.phase = phase
        this.status = status
        UiRefreshBus.requestStatusRefresh()
    }

    /** What the user asked for, read fresh so a change applies from the next poll on. */
    private fun pollIntervalMillis(): Long =
        NightscoutFollowerPollPolicy.intervalMillis(
            NightscoutFollowerRegistry.loadPollMinutes(Applic.app)
        )

    private fun pollIntent(): PendingIntent? {
        val app = Applic.app ?: return null
        val intent = Intent(app, NightscoutFollowerPollReceiver::class.java).apply {
            action = NightscoutFollowerPollReceiver.ACTION_POLL
            putExtra(NightscoutFollowerPollReceiver.EXTRA_SERIAL, SerialNumber)
        }
        return PendingIntent.getBroadcast(
            app,
            SerialNumber.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The next poll is an alarm, not a delayed message. A handler measures in uptime, which
     * stops while the phone sleeps, so on a follower phone (nothing else wakes it: no sensor,
     * no bluetooth) the minute became however long the phone was left alone. An alarm wakes
     * the device for it.
     *
     * An immediate poll stays on the handler: there is nothing to wake for, and it keeps the
     * connect and reconnect paths as responsive as they were.
     */
    private fun scheduleRefresh(delayMillis: Long) {
        handler.removeCallbacks(pollRunnable)
        if (stop) return
        if (delayMillis <= 0L) {
            handler.post(pollRunnable)
            return
        }
        val app = Applic.app
        val alarms = app?.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pending = pollIntent()
        if (alarms == null || pending == null) {
            handler.postDelayed(pollRunnable, delayMillis)
            return
        }
        val at = SystemClock.elapsedRealtime() + delayMillis
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            } else {
                // Without the exact-alarm permission this is the strongest thing left, and it
                // still wakes the device; only its timing is the system's to choose.
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            }
        }.onFailure {
            Log.w(TAG, "Poll alarm refused, falling back to an in-process timer: ${it.message}")
            handler.postDelayed(pollRunnable, delayMillis)
        }
    }

    private fun cancelPollAlarm() {
        val alarms = Applic.app?.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        pollIntent()?.let { pending -> runCatching { alarms?.cancel(pending) } }
    }

    /**
     * Called from the alarm. The receiver's wakelock is handed over here because the fetch
     * runs on this driver's thread and outlives onReceive; whatever happens, it is released
     * once, after the poll has scheduled its successor.
     */
    internal fun onPollAlarm(wakelock: PowerManager.WakeLock?) {
        if (stop) {
            runCatching { if (wakelock?.isHeld == true) wakelock.release() }
            return
        }
        handler.post {
            try {
                refresh("alarm")
            } finally {
                runCatching { if (wakelock?.isHeld == true) wakelock.release() }
            }
        }
    }

    private fun refresh(reason: String) {
        if (stop) return
        if (url.isBlank()) {
            setStatus(Phase.IDLE, localizedString(R.string.nightscout_follow_status_config_needed, "Enter Nightscout URL"))
            return
        }
        setStatus(Phase.SYNCING, localizedString(R.string.nightscout_follow_status_syncing, "Refreshing Nightscout"))
        try {
            val fetched = fetchReadings()
            val readings = fetched.latestReadings
            importRemoteTreatments()
            refreshRemoteDeviceStatus()
            if (readings.isEmpty()) {
                setStatus(Phase.IDLE, localizedString(R.string.nightscout_follow_status_no_readings, "No Nightscout readings yet"))
                scheduleRefresh(pollIntervalMillis())
                return
            }
            if (!fetched.historyImported) {
                importHistory(readings)
            }
            publishLatest(readings)
            bootstrapHistoryPending = false
            setStatus(Phase.FOLLOWING, localizedString(R.string.nightscout_follow_status_following, "Following Nightscout"))
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "Nightscout follower refreshed (%s): %s points latest=%.1f",
                    reason,
                    fetched.fetchedCount,
                    readings.last().glucoseMgdl,
                ),
            )
            UiRefreshBus.requestDataRefresh()
            consecutiveFailures = 0
            scheduleRefresh(pollIntervalMillis())
        } catch (t: Throwable) {
            Log.stack(TAG, "refresh($reason)", t)
            setStatus(Phase.IDLE, localizedString(R.string.nightscout_follow_status_sync_failed, "Nightscout sync failed"))
            // The first retry stays quick, since most failures are a moment without network.
            // One that keeps failing doubles its way up to the ceiling instead: a poll now
            // wakes the phone rather than waiting until something else does, so an unreachable
            // server would otherwise cost a wake-up every thirty seconds all night. The retry
            // never waits longer than the interval the user asked for.
            consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(9)
            val backoff = (RETRY_INTERVAL_MS shl (consecutiveFailures - 1))
                .coerceAtMost(RETRY_BACKOFF_CEILING_MS)
                .coerceAtMost(pollIntervalMillis().coerceAtLeast(RETRY_INTERVAL_MS))
            scheduleRefresh(backoff)
        }
    }

    private fun importHistory(readings: List<VirtualGlucoseSensorBridge.Reading>) {
        val tailMs = readings.maxOfOrNull { it.timestampMs } ?: 0L
        if (tailMs > 0L && tailMs <= lastImportedHistoryTailMs) return
        VirtualGlucoseSensorBridge.importHistory(
            sensorSerial = SerialNumber,
            readings = readings,
            logLabel = "Nightscout follower",
        )
        if (tailMs > 0L) {
            lastImportedHistoryTailMs = tailMs
        }
    }

    private fun publishLatest(readings: List<VirtualGlucoseSensorBridge.Reading>) {
        val latest = readings.lastOrNull() ?: return
        val previous = readings.dropLast(1).lastOrNull()
        val rate = if (previous != null && latest.timestampMs > previous.timestampMs) {
            val minutes = (latest.timestampMs - previous.timestampMs) / 60000f
            if (minutes > 0f) (latest.glucoseMgdl - previous.glucoseMgdl) / minutes else 0f
        } else {
            0f
        }
        latestReadingTimeMs = latest.timestampMs
        latestReadingMgdl = latest.glucoseMgdl
        latestRateMgdlPerMin = rate
        VirtualGlucoseSensorBridge.publishCurrent(
            sensorSerial = SerialNumber,
            reading = latest.copy(rate = rate),
            sensorGen = SENSOR_GEN,
            logLabel = "Nightscout follower",
        )
    }

    private data class FetchedReadings(
        val latestReadings: List<VirtualGlucoseSensorBridge.Reading>,
        val fetchedCount: Int,
        val historyImported: Boolean,
    )

    private fun fetchReadings(): FetchedReadings {
        val latestStoredMs = HistorySyncAccess.getLatestTimestampForSensor(SerialNumber)
        val lowerBoundMs = NightscoutFollowerHistoryPaging.lowerBoundMs(
            latestStoredMs = latestStoredMs,
            bootstrap = bootstrapHistoryPending,
        )
        if (bootstrapHistoryPending || lowerBoundMs == null) {
            val result = NightscoutFollowerHistoryPaging.consumePages(
                lowerBoundMs = null,
                fetchPage = { beforeExclusiveMs ->
                    fetchReadingsPage(lowerBoundMs = null, beforeExclusiveMs = beforeExclusiveMs)
                },
                consumePage = { page ->
                    val imported = VirtualGlucoseSensorBridge.importHistory(
                        sensorSerial = SerialNumber,
                        readings = page,
                        logLabel = "Nightscout follower",
                    )
                    check(imported > 0) {
                        "Nightscout history page could not be stored"
                    }
                },
            )
            lastImportedHistoryTailMs =
                result.newestReadings.maxOfOrNull { it.timestampMs } ?: lastImportedHistoryTailMs
            if (result.reachedEnd) {
                NightscoutFollowerRegistry.markCompleteHistoryImport(Applic.app, SerialNumber)
            }
            return FetchedReadings(
                latestReadings = result.newestReadings,
                fetchedCount = result.fetchedCount,
                historyImported = true,
            )
        }

        val readings = fetchReadingsPage(lowerBoundMs, beforeExclusiveMs = null)
        return FetchedReadings(
            latestReadings = readings,
            fetchedCount = readings.size,
            historyImported = false,
        )
    }

    private fun fetchReadingsPage(
        lowerBoundMs: Long?,
        beforeExclusiveMs: Long?,
    ): List<VirtualGlucoseSensorBridge.Reading> {
        val endpoint = NightscoutFollowerHistoryPaging.endpoint(
            baseUrl = NightscoutFollowerRegistry.normalizeUrl(url),
            lowerBoundMs = lowerBoundMs,
            beforeExclusiveMs = beforeExclusiveMs,
        )
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "JugglucoNG Nightscout follower")
            NightscoutFollowerRegistry.applyAuth(this, secret)
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Nightscout HTTP $code: ${body.take(160)}")
            }
            val array = JSONArray(body)
            val readings = ArrayList<VirtualGlucoseSensorBridge.Reading>(array.length())
            for (index in 0 until array.length()) {
                parseEntry(array.optJSONObject(index))?.let(readings::add)
            }
            return readings
                .distinctBy { it.timestampMs }
                .sortedBy { it.timestampMs }
        } finally {
            connection.disconnect()
        }
    }

    private fun importRemoteTreatments(): Int {
        val method = runCatching {
            val type = Class.forName("tk.glucodata.data.journal.NightscoutJournalFollowerImporter")
            type.getMethod("importTreatments", String::class.java, String::class.java)
        }.getOrElse { error ->
            if (error !is ClassNotFoundException) {
                Log.w(TAG, "Nightscout journal importer unavailable: ${error.message}")
            }
            return 0
        }

        fun importBatch(label: String, body: () -> String): Int =
            runCatching {
                val json = body()
                if (json.isBlank() || json == "[]") 0
                else method.invoke(null, SerialNumber, json) as? Int ?: 0
            }.getOrElse { error ->
                Log.w(TAG, "Nightscout $label import ignored: ${error.message}")
                0
            }

        val imported = importBatch("treatment", ::fetchTreatmentsJson) +
            importBatch("finger-stick", ::fetchFingersticksJson)
        if (imported > 0) {
            UiRefreshBus.requestDataRefresh()
        }
        return imported
    }

    // Devicestatus is optional enrichment on top of entries/treatments: a
    // failing endpoint (404 on old servers, 401, malformed body) must never
    // block the glucose refresh, so the whole step stays inside runCatching.
    private fun refreshRemoteDeviceStatus() {
        runCatching {
            val body = fetchDeviceStatusJson()
            if (stop || body.isBlank() || body == "[]") return
            NightscoutFollowerDeviceStatus.update(NightscoutFollowerDeviceStatus.parseNewest(body))
        }.onFailure { error ->
            Log.w(TAG, "Nightscout devicestatus ignored: ${error.message}")
        }
    }

    private fun fetchDeviceStatusJson(): String {
        val endpoint = "${NightscoutFollowerRegistry.normalizeUrl(url)}/api/v1/devicestatus.json?count=$DEVICE_STATUS_COUNT"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "JugglucoNG Nightscout follower")
            NightscoutFollowerRegistry.applyAuth(this, secret)
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code == 404) return "[]"
            if (code !in 200..299) {
                throw IllegalStateException("Nightscout devicestatus HTTP $code: ${body.take(160)}")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchTreatmentsJson(): String {
        return fetchJournalJson(
            endpoint = NightscoutFollowerJournalEndpoints.treatments(
                NightscoutFollowerRegistry.normalizeUrl(url),
            ),
            label = "treatments",
        )
    }

    private fun fetchFingersticksJson(): String {
        return fetchJournalJson(
            endpoint = NightscoutFollowerJournalEndpoints.fingersticks(
                NightscoutFollowerRegistry.normalizeUrl(url),
            ),
            label = "finger-stick entries",
        )
    }

    private fun fetchJournalJson(endpoint: String, label: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "JugglucoNG Nightscout follower")
            NightscoutFollowerRegistry.applyAuth(this, secret)
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code == 404) return "[]"
            if (code !in 200..299) {
                throw IllegalStateException("Nightscout $label HTTP $code: ${body.take(160)}")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun parseEntry(entry: JSONObject?): VirtualGlucoseSensorBridge.Reading? {
        entry ?: return null
        val mgdl = entry.optDouble("sgv", Double.NaN)
            .takeIf { it.isFinite() && it > 0.0 }
            ?: entry.optDouble("mbg", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
            ?: return null
        val timestampMs = when {
            entry.has("date") -> entry.optLong("date", 0L)
            entry.has("mills") -> entry.optLong("mills", 0L)
            else -> 0L
        }.takeIf { it > 0L } ?: return null
        return VirtualGlucoseSensorBridge.Reading(
            timestampMs = timestampMs,
            glucoseMgdl = mgdl.toFloat(),
        )
    }

}

internal object NightscoutFollowerJournalEndpoints {
    private const val JOURNAL_COUNT = 512

    fun treatments(baseUrl: String): String =
        "$baseUrl/api/v1/treatments.json?count=$JOURNAL_COUNT"

    fun fingersticks(baseUrl: String): String =
        "$baseUrl/api/v1/entries/mbg.json?count=$JOURNAL_COUNT"
}

internal object NightscoutFollowerHistoryPaging {
    private const val INCREMENTAL_OVERLAP_MS = 5L * 60L * 1000L
    private const val PAGE_COUNT = 1_000

    fun lowerBoundMs(
        latestStoredMs: Long,
        bootstrap: Boolean,
    ): Long? =
        if (bootstrap || latestStoredMs <= 0L) null
        else (latestStoredMs - INCREMENTAL_OVERLAP_MS).coerceAtLeast(1L)

    fun endpoint(
        baseUrl: String,
        lowerBoundMs: Long?,
        beforeExclusiveMs: Long?,
    ): String {
        val parameters = buildList {
            add("count=$PAGE_COUNT")
            if (lowerBoundMs != null) {
                add("${encoded("find[date][\$gte]")}=$lowerBoundMs")
            }
            if (beforeExclusiveMs != null) {
                add("${encoded("find[date][\$lt]")}=$beforeExclusiveMs")
            }
        }
        return "$baseUrl/api/v1/entries/sgv.json?${parameters.joinToString("&")}"
    }

    data class Result(
        val newestReadings: List<VirtualGlucoseSensorBridge.Reading>,
        val fetchedCount: Int,
        val reachedEnd: Boolean,
    )

    fun consumePages(
        lowerBoundMs: Long?,
        fetchPage: (beforeExclusiveMs: Long?) -> List<VirtualGlucoseSensorBridge.Reading>,
        consumePage: (List<VirtualGlucoseSensorBridge.Reading>) -> Unit,
    ): Result {
        var newestReadings = emptyList<VirtualGlucoseSensorBridge.Reading>()
        var fetchedCount = 0
        var beforeExclusiveMs: Long? = null
        var reachedEnd = false

        while (true) {
            val page = fetchPage(beforeExclusiveMs)
            if (page.isEmpty()) {
                reachedEnd = true
                break
            }
            val oldestPageTimestamp = page.minOf { it.timestampMs }
            if (beforeExclusiveMs?.let { oldestPageTimestamp >= it } == true) {
                break
            }

            val accepted = page.filter { reading ->
                (lowerBoundMs?.let { reading.timestampMs >= it } ?: true) &&
                    (beforeExclusiveMs?.let { reading.timestampMs < it } ?: true)
            }
            if (newestReadings.isEmpty()) {
                newestReadings = accepted
            }
            if (accepted.isNotEmpty()) {
                consumePage(accepted)
                fetchedCount += accepted.size
            }

            if (lowerBoundMs != null && oldestPageTimestamp <= lowerBoundMs) {
                reachedEnd = true
                break
            }
            beforeExclusiveMs = oldestPageTimestamp
        }

        return Result(
            newestReadings = newestReadings,
            fetchedCount = fetchedCount,
            reachedEnd = reachedEnd,
        )
    }

    private fun encoded(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
