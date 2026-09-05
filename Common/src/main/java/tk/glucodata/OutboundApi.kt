package tk.glucodata

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.Keep
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.ManagedSensorUiFamily

@Keep
object OutboundApi {
    const val TEST_QUEUED = 0
    const val TEST_NOT_CONFIGURED = 1
    const val TEST_NO_CURRENT_READING = 2

    private const val MGDL_PER_MMOLL = 18.0182f
    private const val MAX_PENDING_SENDS = 12
    private const val QUEUE_BUSY_STATUS_INTERVAL_MS = 5 * 60 * 1000L
    private const val ERROR_QUEUE_BUSY = "API send queue is busy; dropped stale live reading"
    private const val ERROR_NETWORK_UNAVAILABLE = "Network unavailable; skipped live API send"
    private const val STALE_READING_LOOKBACK_MS = 12 * 60 * 60 * 1000L

    private val pendingSends = AtomicInteger(0)
    private val lastQueueBusyStatusAtMs = AtomicLong(0)
    private val lastNetworkUnavailableStatusAtMs = AtomicLong(0)
    private val sendExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OutboundApiSend")
    }

    private const val IN_DESTINATION_ID = "destination_id"
    private const val IN_RECIPIENT = "recipient"
    private const val IN_EVENT_ID = "event_id"
    private const val IN_SENSOR_ID = "sensor_id"
    private const val IN_PRIMARY_TEXT = "primary_text"
    private const val IN_DISPLAY_VALUE = "display_value"
    private const val IN_MGDL = "mgdl"
    private const val IN_AUTO_VALUE = "auto_value"
    private const val IN_AUTO_MGDL = "auto_mgdl"
    private const val IN_RAW_VALUE = "raw_value"
    private const val IN_RATE = "rate"
    private const val IN_TREND_INDEX = "trend_index"
    private const val IN_TREND_NAME = "trend_name"
    private const val IN_TREND_RATE = "trend_rate"
    private const val IN_TIME_MILLIS = "time_millis"
    private const val IN_SENSOR_GEN = "sensor_gen"
    private const val IN_ALARM = "alarm"
    private const val IN_TEST = "test"

    @JvmStatic
    fun enqueueGlucose(
        sensorId: String?,
        primaryText: String?,
        primaryDisplayValue: Double,
        primaryMgdl: Int,
        rate: Float,
        timeMillis: Long,
        sensorGen: Int,
        alarm: Int
    ) {
        enqueueGlucose(
            sensorId = sensorId,
            primaryText = primaryText,
            primaryDisplayValue = primaryDisplayValue,
            primaryMgdl = primaryMgdl,
            rate = rate,
            timeMillis = timeMillis,
            sensorGen = sensorGen,
            autoValue = Float.NaN,
            autoMgdl = 0,
            rawValue = Float.NaN,
            alarm = alarm
        )
    }

    @JvmStatic
    fun enqueueGlucose(
        sensorId: String?,
        primaryText: String?,
        primaryDisplayValue: Double,
        primaryMgdl: Int,
        rate: Float,
        timeMillis: Long,
        sensorGen: Int,
        rawValue: Float,
        alarm: Int
    ) {
        enqueueGlucose(
            sensorId = sensorId,
            primaryText = primaryText,
            primaryDisplayValue = primaryDisplayValue,
            primaryMgdl = primaryMgdl,
            rate = rate,
            timeMillis = timeMillis,
            sensorGen = sensorGen,
            autoValue = Float.NaN,
            autoMgdl = 0,
            rawValue = rawValue,
            alarm = alarm
        )
    }

    @JvmStatic
    fun enqueueGlucose(
        sensorId: String?,
        primaryText: String?,
        primaryDisplayValue: Double,
        primaryMgdl: Int,
        rate: Float,
        timeMillis: Long,
        sensorGen: Int,
        autoValue: Float,
        autoMgdl: Int,
        rawValue: Float,
        alarm: Int
    ) {
        val trend = ExchangeTrend.resolve(sensorId, timeMillis, rate)
        enqueueGlucose(
            sensorId = sensorId,
            primaryText = primaryText,
            primaryDisplayValue = primaryDisplayValue,
            primaryMgdl = primaryMgdl,
            rate = rate,
            timeMillis = timeMillis,
            sensorGen = sensorGen,
            autoValue = autoValue,
            autoMgdl = autoMgdl,
            rawValue = rawValue,
            trendIndex = trend.index,
            trendName = trend.name,
            trendRate = trend.rateMgdlPerMinute,
            alarm = alarm
        )
    }

    @JvmStatic
    fun enqueueGlucose(
        sensorId: String?,
        primaryText: String?,
        primaryDisplayValue: Double,
        primaryMgdl: Int,
        rate: Float,
        timeMillis: Long,
        sensorGen: Int,
        autoValue: Float,
        autoMgdl: Int,
        rawValue: Float,
        trendIndex: Int,
        trendName: String?,
        trendRate: Float,
        alarm: Int
    ) {
        enqueueGlucoseInternal(
            context = Applic.app,
            sensorId = sensorId,
            primaryText = primaryText,
            primaryDisplayValue = primaryDisplayValue,
            primaryMgdl = primaryMgdl,
            rawValue = rawValue,
            rate = rate,
            timeMillis = timeMillis,
            sensorGen = sensorGen,
            autoValue = autoValue,
            autoMgdl = autoMgdl,
            trendIndex = trendIndex,
            trendName = trendName,
            trendRate = trendRate,
            alarm = alarm,
            test = false,
            destinationId = null
        )
    }

    @JvmStatic
    @JvmOverloads
    fun enqueueCurrentTest(context: Context = Applic.app, destinationId: String? = null): Int {
        val config = OutboundApiSettings.load(context)
        val destinations = resolveDestinations(config, destinationId)
        if (destinations.isEmpty()) {
            return TEST_NOT_CONFIGURED
        }
        val current = CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout) ?: return TEST_NO_CURRENT_READING
        val trend = ExchangeTrend.resolve(current.sensorId, current.timeMillis, current.rate)
        enqueueGlucoseInternal(
            context = context,
            sensorId = current.sensorId,
            primaryText = current.primaryStr,
            primaryDisplayValue = current.primaryValue.toDouble(),
            primaryMgdl = current.sharedMgdl,
            rawValue = current.rawValue,
            rate = current.rate,
            timeMillis = current.timeMillis,
            sensorGen = current.sensorGen,
            autoValue = current.autoValue,
            autoMgdl = displayToMgdl(current.autoValue),
            trendIndex = trend.index,
            trendName = trend.name,
            trendRate = trend.rateMgdlPerMinute,
            alarm = 0,
            test = true,
            destinationId = destinationId
        )
        return TEST_QUEUED
    }

    private fun enqueueGlucoseInternal(
        context: Context,
        sensorId: String?,
        primaryText: String?,
        primaryDisplayValue: Double,
        primaryMgdl: Int,
        rawValue: Float,
        rate: Float,
        timeMillis: Long,
        sensorGen: Int,
        autoValue: Float,
        autoMgdl: Int,
        trendIndex: Int,
        trendName: String?,
        trendRate: Float,
        alarm: Int,
        test: Boolean,
        destinationId: String?
    ) {
        val appContext = context.applicationContext
        val config = OutboundApiSettings.load(appContext)
        if (timeMillis <= 0L || primaryMgdl <= 0) return

        val eventId = if (test) {
            "test-${System.currentTimeMillis()}"
        } else {
            "${sensorId.orEmpty()}:$timeMillis:$primaryMgdl"
        }
        val now = System.currentTimeMillis()
        resolveDestinations(config, destinationId).forEach { destination ->
            if (!test && !destination.shouldSendForGlucose(primaryMgdl)) {
                return@forEach
            }
            if (!test && !OutboundApiSettings.shouldQueue(appContext, destination, eventId, now)) {
                return@forEach
            }
            if (!test) {
                OutboundApiSettings.recordQueued(appContext, destination.id, eventId, now)
            }
            enqueueForDestination(
                context = appContext,
                destination = destination,
                eventId = eventId,
                sensorId = sensorId,
                primaryText = primaryText,
                primaryDisplayValue = primaryDisplayValue,
                primaryMgdl = primaryMgdl,
                rawValue = rawValue,
                rate = rate,
                timeMillis = timeMillis,
                sensorGen = sensorGen,
                autoValue = autoValue,
                autoMgdl = autoMgdl,
                trendIndex = trendIndex,
                trendName = trendName,
                trendRate = trendRate,
                alarm = alarm,
                test = test
            )
        }
    }

    private fun resolveDestinations(
        config: OutboundApiSettings.Config,
        destinationId: String?
    ): List<OutboundApiSettings.Destination> {
        val candidates = destinationId
            ?.let { id -> listOfNotNull(config.findDestination(id)) }
            ?: config.activeDestinations()
        // SMS destinations are event-driven (see tk.glucodata.sms.SmsWatchdog) and must
        // never be fed by the reading stream — a text per reading would be ruinous.
        return candidates.filter { it.isReady() && !it.isSms() }
    }

    /**
     * The latest reading as the template engine sees it, or null when the app has
     * never had one. Tolerates stale data on purpose: the SMS watchdog needs the
     * last known value precisely when readings have stopped arriving.
     */
    internal fun currentReadingOrNull(
        recipient: String = "",
        maxAgeMillis: Long = STALE_READING_LOOKBACK_MS
    ): Reading? {
        val current = CurrentDisplaySource.resolveCurrent(maxAgeMillis) ?: return null
        if (current.timeMillis <= 0L || current.sharedMgdl <= 0) return null
        val trend = ExchangeTrend.resolve(current.sensorId, current.timeMillis, current.rate)
        return Reading(
            eventId = "${current.sensorId.orEmpty()}:${current.timeMillis}:${current.sharedMgdl}",
            recipient = recipient,
            sensorId = current.sensorId.orEmpty(),
            primaryText = current.primaryStr,
            displayValue = current.primaryValue.toDouble(),
            mgdl = current.sharedMgdl,
            autoValue = current.autoValue,
            autoMgdl = displayToMgdl(current.autoValue),
            rawValue = current.rawValue,
            rateMgdlPerMinute = current.rate,
            trendIndex = trend.index,
            trendNameFromInput = trend.name.orEmpty(),
            trendRateMgdlPerMinute = trend.rateMgdlPerMinute,
            timeMillis = current.timeMillis,
            sensorGen = current.sensorGen,
            alarm = 0,
            test = false
        )
    }

    private fun enqueueForDestination(
        context: Context,
        destination: OutboundApiSettings.Destination,
        eventId: String,
        sensorId: String?,
        primaryText: String?,
        primaryDisplayValue: Double,
        primaryMgdl: Int,
        rawValue: Float,
        rate: Float,
        timeMillis: Long,
        sensorGen: Int,
        autoValue: Float,
        autoMgdl: Int,
        trendIndex: Int,
        trendName: String?,
        trendRate: Float,
        alarm: Int,
        test: Boolean
    ) {
        val recipients = when (destination.normalizedPreset()) {
            OutboundApiSettings.PRESET_TELEGRAM_BOT,
            OutboundApiSettings.PRESET_GLUCO_WATCH_VK,
            OutboundApiSettings.PRESET_VK_MESSAGES -> destination.recipients()
            else -> listOf("")
        }
        recipients.forEach { recipient ->
            val input = Data.Builder()
                .putString(IN_DESTINATION_ID, destination.id)
                .putString(IN_RECIPIENT, recipient)
                .putString(IN_EVENT_ID, eventId)
                .putString(IN_SENSOR_ID, sensorId.orEmpty())
                .putString(IN_PRIMARY_TEXT, primaryText.orEmpty())
                .putDouble(IN_DISPLAY_VALUE, primaryDisplayValue)
                .putInt(IN_MGDL, primaryMgdl)
                .putFloat(IN_AUTO_VALUE, autoValue)
                .putInt(IN_AUTO_MGDL, autoMgdl)
                .putFloat(IN_RAW_VALUE, rawValue)
                .putFloat(IN_RATE, rate)
                .putInt(IN_TREND_INDEX, trendIndex)
                .putString(IN_TREND_NAME, trendName.orEmpty())
                .putFloat(IN_TREND_RATE, trendRate)
                .putLong(IN_TIME_MILLIS, timeMillis)
                .putInt(IN_SENSOR_GEN, sensorGen)
                .putInt(IN_ALARM, alarm)
                .putBoolean(IN_TEST, test)
                .build()

            sendInProcess(context, destination.id, input, test)
        }
    }

    private fun sendInProcess(context: Context, destinationId: String, input: Data, test: Boolean) {
        if (!test && !hasUsableNetwork(context)) {
            recordThrottledAttempt(
                context = context,
                destinationId = destinationId,
                lastStatusAtMs = lastNetworkUnavailableStatusAtMs,
                error = ERROR_NETWORK_UNAVAILABLE
            )
            return
        }

        val pending = pendingSends.incrementAndGet()
        if (!test && pending > MAX_PENDING_SENDS) {
            pendingSends.decrementAndGet()
            recordThrottledAttempt(
                context = context,
                destinationId = destinationId,
                lastStatusAtMs = lastQueueBusyStatusAtMs,
                error = ERROR_QUEUE_BUSY
            )
            return
        }
        sendExecutor.execute {
            try {
                OutboundApiWorker.runOnce(
                    context = context.applicationContext,
                    inputData = input,
                    runAttemptCount = 0,
                    allowRetry = false
                )
            } finally {
                pendingSends.decrementAndGet()
            }
        }
    }

    private fun hasUsableNetwork(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun recordThrottledAttempt(
        context: Context,
        destinationId: String,
        lastStatusAtMs: AtomicLong,
        error: String
    ) {
        val now = System.currentTimeMillis()
        val last = lastStatusAtMs.get()
        if (now - last >= QUEUE_BUSY_STATUS_INTERVAL_MS &&
            lastStatusAtMs.compareAndSet(last, now)
        ) {
            OutboundApiSettings.recordAttempt(context, destinationId, -1, error)
        }
    }

    internal fun inputToReading(input: Data): Reading? {
        val timeMillis = input.getLong(IN_TIME_MILLIS, 0L)
        val mgdl = input.getInt(IN_MGDL, 0)
        if (timeMillis <= 0L || mgdl <= 0) return null
        val displayValue = input.getDouble(IN_DISPLAY_VALUE, Double.NaN)
        return Reading(
            eventId = input.getString(IN_EVENT_ID).orEmpty(),
            recipient = input.getString(IN_RECIPIENT).orEmpty(),
            sensorId = input.getString(IN_SENSOR_ID).orEmpty(),
            primaryText = input.getString(IN_PRIMARY_TEXT).orEmpty(),
            displayValue = displayValue,
            mgdl = mgdl,
            autoValue = input.getFloat(IN_AUTO_VALUE, Float.NaN),
            autoMgdl = input.getInt(IN_AUTO_MGDL, 0),
            rawValue = input.getFloat(IN_RAW_VALUE, Float.NaN),
            rateMgdlPerMinute = input.getFloat(IN_RATE, Float.NaN),
            trendIndex = input.getInt(IN_TREND_INDEX, 0),
            trendNameFromInput = input.getString(IN_TREND_NAME).orEmpty(),
            trendRateMgdlPerMinute = input.getFloat(IN_TREND_RATE, Float.NaN),
            timeMillis = timeMillis,
            sensorGen = input.getInt(IN_SENSOR_GEN, 0),
            alarm = input.getInt(IN_ALARM, 0),
            test = input.getBoolean(IN_TEST, false)
        )
    }

    internal data class Reading(
        val eventId: String,
        val recipient: String,
        val sensorId: String,
        val primaryText: String,
        val displayValue: Double,
        val mgdl: Int,
        val autoValue: Float,
        val autoMgdl: Int,
        val rawValue: Float,
        val rateMgdlPerMinute: Float,
        val trendIndex: Int,
        val trendNameFromInput: String,
        val trendRateMgdlPerMinute: Float,
        val timeMillis: Long,
        val sensorGen: Int,
        val alarm: Int,
        val test: Boolean
    ) {
        val unit: String get() = if (Applic.unit == 1) "mmol/L" else "mg/dL"
        val mmol: Float get() = mgdl / MGDL_PER_MMOLL
        val autoMmol: Float get() = autoMgdl / MGDL_PER_MMOLL
        val rawGlucoseMgdl: Float
            get() = rawValue.takeIf { rawValueLooksGlucoseScaled(sensorId, it) }
                ?.let { if (Applic.unit == 1) it * MGDL_PER_MMOLL else it }
                ?: Float.NaN
        val rawGlucoseMmol: Float get() = rawGlucoseMgdl / MGDL_PER_MMOLL
        val rateMmolPerMinute: Float get() = rateMgdlPerMinute / MGDL_PER_MMOLL
        val trendRateMmolPerMinute: Float get() = trendRateMgdlPerMinute / MGDL_PER_MMOLL
        val trendName: String get() = trendNameFromInput.ifBlank {
            ExchangeTrend.nameForIndex(trendIndex).ifBlank {
                Natives.getxDripTrendName(rateMgdlPerMinute) ?: ""
            }
        }
        val trendArrow: String get() = trendArrow(trendName)
        val iob: Float get() = runCatching { Natives.getIOBvalue(timeMillis) }.getOrDefault(Float.NaN)
        val journal: JournalSnapshot get() = loadJournalSnapshot(timeMillis)
        val displayText: String
            get() = primaryText.ifBlank {
                if (Applic.unit == 1) formatNumber(mmol, 1) else mgdl.toString()
            }
        val autoDisplayText: String
            get() {
                if (!autoValue.isFinite() || autoValue <= 0f) return ""
                return if (Applic.unit == 1) formatNumber(autoValue, 1) else formatNumber(autoValue, 0)
            }
        val rawDisplayText: String
            get() {
                if (!rawValue.isFinite() || rawValue <= 0f) return ""
                val decimals = when {
                    rawValue < 20f -> 2
                    rawValue < 100f -> 1
                    else -> 0
                }
                return formatNumber(rawValue, decimals)
            }
    }

    internal data class JournalSnapshot(
        val iob: Float = Float.NaN,
        val cob: Float = Float.NaN,
        val json: JSONObject? = null
    )

    internal fun renderMessage(
        template: String,
        reading: Reading,
        destination: OutboundApiSettings.Destination? = null,
        status: String? = null
    ): String {
        val time = DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date(reading.timeMillis))
        val journal = if (needsJournalSnapshot(template)) reading.journal else JournalSnapshot()
        val effectiveIob = journal.iob.takeIf { it.isFinite() } ?: reading.iob
        val journalEvents = journalEventsJsonArray(journal)
        val effectiveStatus = status
            ?: destination?.rangeStatus(reading.mgdl)
            ?: OutboundApiSettings.TUNNEL_STATUS_IN_RANGE
        val statusEmojiValue = statusEmoji(effectiveStatus)
        val rendered = template
            .replace("{event_id}", reading.eventId)
            .replace("{recipient}", reading.recipient)
            .replace("{value}", reading.displayText)
            .replace("{unit}", reading.unit)
            .replace("{mgdl}", reading.mgdl.toString())
            .replace("{mmol}", formatNumber(reading.mmol, 2))
            .replace("{auto}", reading.autoDisplayText)
            .replace("{auto_value}", reading.autoDisplayText)
            .replace("{auto_mgdl}", if (reading.autoMgdl > 0) reading.autoMgdl.toString() else "")
            .replace("{auto_mmol}", if (reading.autoMgdl > 0) formatNumber(reading.autoMmol, 2) else "")
            .replace("{raw}", reading.rawDisplayText)
            .replace("{raw_mgdl}", if (reading.rawGlucoseMgdl.isFinite() && reading.rawGlucoseMgdl > 0f) {
                formatNumber(reading.rawGlucoseMgdl, 0)
            } else {
                ""
            })
            .replace("{raw_mmol}", if (reading.rawGlucoseMgdl.isFinite() && reading.rawGlucoseMgdl > 0f) {
                formatNumber(reading.rawGlucoseMmol, 2)
            } else {
                ""
            })
            .replace("{trend}", reading.trendName)
            .replace("{trend_arrow}", reading.trendArrow)
            .replace("{rate_mgdl}", formatNumber(reading.rateMgdlPerMinute, 1))
            .replace("{rate_mmol}", formatNumber(reading.rateMmolPerMinute, 3))
            .replace("{timestamp}", reading.timeMillis.toString())
            .replace("{time}", time)
            .replace("{sensor}", reading.sensorId)
            .replace("{sensor_gen}", reading.sensorGen.toString())
            .replace("{alarm}", reading.alarm.toString())
            .replace("{iob}", if (effectiveIob.isFinite()) formatNumber(effectiveIob, 2) else "0")
            .replace("{journal_iob}", if (journal.iob.isFinite()) formatNumber(journal.iob, 2) else "0")
            .replace("{cob}", if (journal.cob.isFinite()) formatNumber(journal.cob, 1) else "0")
            .replace("{journal_cob}", if (journal.cob.isFinite()) formatNumber(journal.cob, 1) else "0")
            .replace("{journal_events}", journalEvents?.toString().orEmpty())
            .replace("{journal}", journal.json?.toString().orEmpty())
            .replace("{test}", reading.test.toString())
            .replace("{status}", effectiveStatus)
            .replace("{status_emoji}", statusEmojiValue)
        return OutboundApiInsulinTokens.expand(
            template = rendered,
            types = OutboundApiInsulinTokens.parse(journal.json),
            atMillis = reading.timeMillis,
            formatTime = ::formatTimeOfDay
        )
    }

    private fun formatTimeOfDay(timeMillis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(timeMillis))

    /**
     * Tokens that need no journal read. Anything else in a template — `{iob}`,
     * `{cob}`, `{journal…}` or a per-insulin-type token, whose spelling depends
     * on what the user named their insulin — means the snapshot has to be
     * loaded, which costs a Room query per send.
     */
    private val STATIC_TOKENS = setOf(
        "event_id", "recipient", "value", "unit", "mgdl", "mmol",
        "auto", "auto_value", "auto_mgdl", "auto_mmol",
        "raw", "raw_mgdl", "raw_mmol",
        "trend", "trend_arrow", "rate_mgdl", "rate_mmol",
        "timestamp", "time", "sensor", "sensor_gen", "alarm",
        "test", "status", "status_emoji"
    )

    private val PLACEHOLDER = Regex("\\{[^{}\\s]+}")

    internal fun needsJournalSnapshot(template: String): Boolean =
        PLACEHOLDER.findAll(template).any { match ->
            match.value.removeSurrounding("{", "}") !in STATIC_TOKENS
        }

    internal fun statusEmoji(status: String): String = when (status) {
        OutboundApiSettings.TUNNEL_STATUS_HIGH -> "\uD83D\uDFE1"      // 🟡
        OutboundApiSettings.TUNNEL_STATUS_LOW -> "\uD83D\uDD34"       // 🔴
        OutboundApiSettings.TUNNEL_STATUS_STALE -> "\u26A0\uFE0F"     // ⚠️
        OutboundApiSettings.TUNNEL_STATUS_MISSED -> "\u26AA"          // ⚪
        else -> "\uD83D\uDFE2"                                        // 🟢
    }

    internal fun formatNumber(value: Float, decimals: Int): String {
        if (!value.isFinite()) return ""
        return "%.${decimals}f".format(Locale.US, value)
    }

    internal fun displayToMgdl(value: Float): Int {
        if (!value.isFinite() || value <= 0f) return 0
        return (if (Applic.unit == 1) value * MGDL_PER_MMOLL else value).roundToInt()
    }

    private fun rawValueLooksGlucoseScaled(sensorId: String?, rawValue: Float): Boolean {
        if (!rawValue.isFinite() || rawValue <= 0f) return false
        val family = runCatching {
            ManagedSensorRuntime.resolveUiSnapshot(sensorId, sensorId)?.uiFamily
        }.getOrNull()
        if (family == ManagedSensorUiFamily.MQ || family == ManagedSensorUiFamily.ANYTIME) {
            return false
        }
        val asMgdl = if (Applic.unit == 1) rawValue * MGDL_PER_MMOLL else rawValue
        return asMgdl.isFinite() && asMgdl in 1f..600f
    }

    private fun loadJournalSnapshot(timeMillis: Long): JournalSnapshot {
        val raw = runCatching {
            val type = Class.forName("tk.glucodata.OutboundApiJournalSnapshot")
            val method = type.getMethod("snapshotJson", java.lang.Long.TYPE)
            method.invoke(null, timeMillis) as? String
        }.getOrNull() ?: return JournalSnapshot()
        if (raw.isBlank()) return JournalSnapshot()
        return runCatching {
            val json = JSONObject(raw)
            JournalSnapshot(
                iob = json.optDouble("iob", Double.NaN).toFloat(),
                cob = json.optDouble("cob", Double.NaN).toFloat(),
                json = json
            )
        }.getOrDefault(JournalSnapshot())
    }

    internal fun journalEventsJsonArray(journal: JournalSnapshot): JSONArray? =
        journal.json?.optJSONArray("events") ?: journal.json?.optJSONArray("treatments")

    private fun trendArrow(trendName: String): String =
        when (trendName) {
            "DoubleUp" -> "\u2191\u2191"
            "SingleUp" -> "\u2191"
            "FortyFiveUp" -> "\u2197"
            "Flat" -> "\u2192"
            "FortyFiveDown" -> "\u2198"
            "SingleDown" -> "\u2193"
            "DoubleDown" -> "\u2193\u2193"
            else -> "\u2192"
        }
}

class OutboundApiWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result =
        runOnce(
            context = applicationContext,
            inputData = inputData,
            runAttemptCount = runAttemptCount,
            allowRetry = false
        )

    companion object {
        private const val TAG = "OutboundApiWorker"
        private const val IN_DESTINATION_ID = "destination_id"

        internal fun runOnce(
            context: Context,
            inputData: Data,
            runAttemptCount: Int,
            allowRetry: Boolean
        ): Result {
            val destinationId = inputData.getString(IN_DESTINATION_ID).orEmpty()
            val config = OutboundApiSettings.load(context)
            val destination = config.findDestination(destinationId) ?: return Result.success()
            if (!destination.isReady()) {
                return Result.success()
            }
            val reading = OutboundApi.inputToReading(inputData) ?: return Result.success()

            return try {
                val response = send(context, destination, reading)
                if (response.ok) {
                    OutboundApiSettings.recordSuccess(context, destination.id, response.code)
                    val nowMs = System.currentTimeMillis()
                    if (!response.suppressed) {
                        OutboundApiSettings.recordBubbleSent(
                            context = context.applicationContext,
                            destinationId = destination.id,
                            recipient = reading.recipient,
                            messageId = response.messageId,
                            sentAtMs = nowMs,
                            mgdl = reading.mgdl
                        )
                    } else {
                        // Reading arrived but bubble text was unchanged — still update the
                        // "last received" timestamp so the stale timer doesn't fire during
                        // a flat-glucose stretch where every reading is suppressed.
                        OutboundApiSettings.recordReadingArrived(
                            context = context.applicationContext,
                            destinationId = destination.id,
                            recipient = reading.recipient,
                            arrivedAtMs = nowMs
                        )
                    }
                    if (destination.normalizedPreset() == OutboundApiSettings.PRESET_TELEGRAM_BOT) {
                        TelegramStaleCheckWork.schedule(
                            context = context.applicationContext,
                            destinationId = destination.id,
                            delayMs = (destination.staleThresholdMinutes.coerceIn(1, 120) * 60_000L) +
                                OutboundApiSettings.STALE_CHECK_SLACK_MS
                        )
                    }
                    Result.success()
                } else {
                    OutboundApiSettings.recordAttempt(context, destination.id, response.code, response.error)
                    if (allowRetry && response.retryable && runAttemptCount < 5) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            } catch (th: Throwable) {
                Log.e(TAG, "send failed: ${Log.stackline(th)}")
                OutboundApiSettings.recordAttempt(context, destination.id, -1, friendlyError(destination, th))
                if (allowRetry && runAttemptCount < 5) Result.retry() else Result.failure()
            }
        }

        private data class SendResponse(
            val code: Int,
            val ok: Boolean,
            val retryable: Boolean,
            val error: String?,
            val messageId: Long? = null,
            val suppressed: Boolean = false
        )

        private data class ApiError(
            val message: String,
            val code: Int? = null,
            val retryable: Boolean = false
        )

        private fun send(
            context: Context,
            destination: OutboundApiSettings.Destination,
            reading: OutboundApi.Reading
        ): SendResponse {
            val message = OutboundApi.renderMessage(
                template = destination.resolvedTemplate(),
                reading = reading,
                destination = destination
            )
            return when (destination.normalizedPreset()) {
                OutboundApiSettings.PRESET_TELEGRAM_BOT -> sendTelegram(context, destination, reading, message)
                OutboundApiSettings.PRESET_GLUCO_WATCH_VK,
                OutboundApiSettings.PRESET_VK_MESSAGES -> sendVk(destination, reading, message)
                else -> sendJson(destination, reading, message)
            }
        }

        private fun sendTelegram(
            context: Context,
            destination: OutboundApiSettings.Destination,
            reading: OutboundApi.Reading,
            message: String
        ): SendResponse {
            val now = System.currentTimeMillis()
            val lastMsgId = destination.lastMessageIdByRecipient[reading.recipient] ?: 0L
            val lastSentMs = destination.lastSentAtMsByRecipient[reading.recipient] ?: 0L
            val lastMgdl = destination.lastSentMgdlByRecipient[reading.recipient] ?: 0
            val windowMs = destination.refreshWindowMinutes.coerceIn(1, 60) * 60_000L
            val withinWindow = destination.refreshInPlaceEnabled &&
                lastMsgId > 0L && lastSentMs > 0L &&
                (now - lastSentMs) <= windowMs
            val suppressThreshold = destination.suppressDeltaBelowMgdl.coerceIn(0, 100)
            // Test sends use the same reading data every time, so never suppress them —
            // the user needs to be able to verify edit-in-place via the test button.
            val shouldSuppress = withinWindow && !reading.test && suppressThreshold > 0 &&
                kotlin.math.abs(reading.mgdl - lastMgdl) < suppressThreshold

            if (shouldSuppress) {
                return SendResponse(
                    code = 200,
                    ok = true,
                    retryable = false,
                    error = null,
                    messageId = lastMsgId,
                    suppressed = true
                )
            }
            if (withinWindow) {
                val editResponse = sendTelegramEdit(
                    destination = destination,
                    reading = reading,
                    message = message,
                    messageId = lastMsgId
                )
                if (editResponse.ok) return editResponse
                // Telegram rejects edits where the content hasn't changed (400 "not modified").
                // This is a success — the bubble is already correct — so preserve state and
                // return without posting a new message.
                if (editResponse.error?.contains("not modified") == true) {
                    return SendResponse(
                        code = 200,
                        ok = true,
                        retryable = false,
                        error = null,
                        messageId = lastMsgId
                    )
                }
                // Transient failure (rate limit, server error): preserve the message ID so
                // the next reading retries the edit. Don't attempt a fresh send that will
                // likely also fail.
                if (editResponse.retryable) return editResponse
                // Definitive rejection (message deleted, chat not found, etc.):
                // clear stale state so the next reading starts a fresh bubble.
                OutboundApiSettings.clearRecipientState(
                    context = context.applicationContext,
                    destinationId = destination.id,
                    recipient = reading.recipient
                )
            }
            return sendTelegramSend(destination, reading, message)
        }

        private fun sendTelegramSend(
            destination: OutboundApiSettings.Destination,
            reading: OutboundApi.Reading,
            message: String
        ): SendResponse {
            val body = JSONObject()
                .put("chat_id", reading.recipient)
                .put("text", message)
                .toString()
                .toByteArray(Charsets.UTF_8)
            return executePost(
                urlString = destination.resolvedUrl(),
                contentType = "application/json; charset=UTF-8",
                headers = destination.headers,
                body = body,
                parseApiError = ::parseTelegramError,
                connectTimeoutMs = 20_000,
                readTimeoutMs = 30_000,
                parseMessageId = ::parseTelegramMessageId
            )
        }

        private fun sendTelegramEdit(
            destination: OutboundApiSettings.Destination,
            reading: OutboundApi.Reading,
            message: String,
            messageId: Long
        ): SendResponse {
            val editUrl = destination.resolvedUrl()
                .replace(Regex("/sendMessage$"), "/editMessageText")
            val body = JSONObject()
                .put("chat_id", reading.recipient)
                .put("message_id", messageId)
                .put("text", message)
                .toString()
                .toByteArray(Charsets.UTF_8)
            return executePost(
                urlString = editUrl,
                contentType = "application/json; charset=UTF-8",
                headers = destination.headers,
                body = body,
                parseApiError = ::parseTelegramError,
                connectTimeoutMs = 20_000,
                readTimeoutMs = 30_000,
                parseMessageId = ::parseTelegramMessageId
            )
        }

        private fun sendVk(
            destination: OutboundApiSettings.Destination,
            reading: OutboundApi.Reading,
            message: String
        ): SendResponse {
            val fields = linkedMapOf(
                "access_token" to destination.token.trim(),
                "v" to destination.apiVersion.trim().ifBlank { OutboundApiSettings.DEFAULT_VK_API_VERSION },
                "peer_id" to reading.recipient,
                "random_id" to stableRandomId(reading).toString(),
                "message" to message
            )
            return executePost(
                urlString = destination.resolvedUrl(),
                contentType = "application/x-www-form-urlencoded; charset=UTF-8",
                headers = "",
                body = formEncode(fields).toByteArray(Charsets.UTF_8),
                parseApiError = ::parseVkError
            )
        }

        private fun sendJson(
            destination: OutboundApiSettings.Destination,
            reading: OutboundApi.Reading,
            message: String
        ): SendResponse =
            executePost(
                urlString = destination.resolvedUrl(),
                contentType = "application/json; charset=UTF-8",
                headers = destination.headers,
                body = buildJsonBody(destination, reading, message).toString().toByteArray(Charsets.UTF_8),
                parseApiError = { null }
            )

        private fun executePost(
            urlString: String,
            contentType: String,
            headers: String,
            body: ByteArray,
            parseApiError: (String) -> ApiError?,
            connectTimeoutMs: Int = 15_000,
            readTimeoutMs: Int = 30_000,
            preResponseRetries: Int = 0,
            parseMessageId: (String) -> Long? = { null }
        ): SendResponse {
            var lastFailure: Throwable? = null
            repeat(preResponseRetries + 1) { attempt ->
                try {
                    return executePostOnce(
                        urlString = urlString,
                        contentType = contentType,
                        headers = headers,
                        body = body,
                        parseApiError = parseApiError,
                        connectTimeoutMs = connectTimeoutMs,
                        readTimeoutMs = readTimeoutMs,
                        parseMessageId = parseMessageId
                    )
                } catch (th: Throwable) {
                    lastFailure = th
                    if (attempt >= preResponseRetries) throw th
                    Thread.sleep(750L * (attempt + 1))
                }
            }
            throw lastFailure ?: IllegalStateException("HTTP request failed")
        }

        private fun executePostOnce(
            urlString: String,
            contentType: String,
            headers: String,
            body: ByteArray,
            parseApiError: (String) -> ApiError?,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
            parseMessageId: (String) -> Long?
        ): SendResponse {
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Accept", "application/json, text/plain")
                setRequestProperty("User-Agent", "JugglucoNG API destinations")
                applyHeaders(headers)
            }

            try {
                connection.outputStream.use { stream ->
                    stream.write(body)
                }

                val code = connection.responseCode
                val responseText = readResponse(connection, code)
                parseApiError(responseText)?.let { apiError ->
                    val responseCode = apiError.code ?: code
                    return SendResponse(
                        code = responseCode,
                        ok = false,
                        retryable = apiError.retryable || responseCode == 429 || responseCode >= 500,
                        error = apiError.message
                    )
                }
                val ok = code in 200..299
                val messageId = if (ok) parseMessageId(responseText) else null
                return SendResponse(
                    code = code,
                    ok = ok,
                    retryable = code == 429 || code >= 500,
                    error = if (ok) null else responseText.take(500).ifBlank { "HTTP $code" },
                    messageId = messageId
                )
            } finally {
                connection.disconnect()
            }
        }

        private fun buildJsonBody(
            destination: OutboundApiSettings.Destination,
            reading: OutboundApi.Reading,
            message: String
        ): JSONObject {
            val journal = reading.journal
            val journalEvents = OutboundApi.journalEventsJsonArray(journal)
            return JSONObject()
                .put("schema", "tk.glucodata.outbound.glucose.v1")
                .put("type", "glucose")
                .put("destination_id", destination.id)
                .put("destination_preset", destination.normalizedPreset())
                .put("event_id", reading.eventId)
                .put("test", reading.test)
                .put("app", "JugglucoNG")
                .put("sensor_id", reading.sensorId)
                .put("sensor_gen", reading.sensorGen)
                .put("timestamp", reading.timeMillis)
                .put("glucose_mgdl", reading.mgdl)
                .put("glucose_mmol", OutboundApi.formatNumber(reading.mmol, 2).toDoubleOrNull())
                .put("auto_glucose_mgdl", reading.autoMgdl.takeIf { it > 0 })
                .put("auto_glucose_mmol", if (reading.autoMgdl > 0) {
                    OutboundApi.formatNumber(reading.autoMmol, 2).toDoubleOrNull()
                } else {
                    null
                })
                .put("auto_display_value", reading.autoDisplayText.takeIf { it.isNotBlank() })
                .put("raw_value", reading.rawValue.takeIf { it.isFinite() && it > 0f })
                .put("raw_glucose_mgdl", reading.rawGlucoseMgdl.takeIf { it.isFinite() && it > 0f })
                .put(
                    "raw_glucose_mmol",
                    if (reading.rawGlucoseMgdl.isFinite() && reading.rawGlucoseMgdl > 0f) {
                        OutboundApi.formatNumber(reading.rawGlucoseMmol, 2).toDoubleOrNull()
                    } else {
                        null
                    }
                )
                .put("display_value", reading.displayText)
                .put("display_unit", reading.unit)
                .put("rate_mgdl_per_min", reading.rateMgdlPerMinute.takeIf { it.isFinite() })
                .put("rate_mmol_per_min", reading.rateMmolPerMinute.takeIf { it.isFinite() })
                .put("trend", reading.trendName)
                .put("trend_arrow", reading.trendArrow)
                .put("trend_index", reading.trendIndex.takeIf { it > 0 })
                .put("trend_rate_mgdl_per_min", reading.trendRateMgdlPerMinute.takeIf { it.isFinite() })
                .put("trend_rate_mmol_per_min", reading.trendRateMmolPerMinute.takeIf { it.isFinite() })
                .put("alarm", reading.alarm)
                .put("iob", reading.iob.takeIf { it.isFinite() })
                .put("journal_iob", journal.iob.takeIf { it.isFinite() })
                .put("cob", journal.cob.takeIf { it.isFinite() })
                .put("journal_cob", journal.cob.takeIf { it.isFinite() })
                .put("journal_events", journalEvents)
                .put(
                    OutboundApiInsulinTokens.JSON_ARRAY,
                    journal.json?.optJSONArray(OutboundApiInsulinTokens.JSON_ARRAY)
                )
                .put("journal", journal.json)
                .put("message", message)
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
                    if (name.equals("Content-Type", ignoreCase = true)) return@forEach
                    if (name.isNotEmpty() && value.isNotEmpty()) {
                        setRequestProperty(name, value)
                    }
                }
        }

        private fun formEncode(fields: Map<String, String>): String =
            fields.entries.joinToString("&") { (key, value) ->
                "${urlEncode(key)}=${urlEncode(value)}"
            }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8")

        private fun stableRandomId(reading: OutboundApi.Reading): Int {
            var hash = reading.eventId.hashCode()
            hash = 31 * hash + reading.recipient.hashCode()
            hash = 31 * hash + reading.timeMillis.hashCode()
            hash = 31 * hash + reading.mgdl
            return hash and Int.MAX_VALUE
        }

        private fun readResponse(connection: HttpURLConnection, code: Int): String {
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: runCatching { connection.inputStream }.getOrNull()
            } ?: return ""
            return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                buildString {
                    var line = reader.readLine()
                    while (line != null) {
                        append(line)
                        line = reader.readLine()
                    }
                }
            }
        }

        private fun parseVkError(responseText: String): ApiError? {
            if (responseText.isBlank()) return null
            return try {
                val root = JSONObject(responseText)
                val error = root.optJSONObject("error") ?: return null
                val code = error.optInt("error_code", 0).takeIf { it != 0 }
                ApiError(
                    message = error.optString("error_msg", "VK API error").ifBlank { "VK API error" },
                    code = code,
                    retryable = code == 6 || code == 9 || code == 10
                )
            } catch (_: Throwable) {
                null
            }
        }

        private fun parseTelegramError(responseText: String): ApiError? {
            if (responseText.isBlank()) return null
            return try {
                val root = JSONObject(responseText)
                if (root.optBoolean("ok", true)) return null
                val code = root.optInt("error_code", 0).takeIf { it != 0 }
                ApiError(
                    message = root.optString("description", "Telegram API error").ifBlank { "Telegram API error" },
                    code = code,
                    retryable = code == 429 || code == 500 || code == 502 || code == 503
                )
            } catch (_: Throwable) {
                null
            }
        }

        private fun parseTelegramMessageId(responseText: String): Long? {
            if (responseText.isBlank()) return null
            return try {
                val root = JSONObject(responseText)
                if (!root.optBoolean("ok", true)) return null
                val result = root.optJSONObject("result") ?: return null
                val id = result.optLong("message_id", 0L)
                if (id > 0L) id else null
            } catch (_: Throwable) {
                null
            }
        }

        private fun friendlyError(destination: OutboundApiSettings.Destination, th: Throwable): String {
            val raw = th.message ?: th.javaClass.simpleName
            if (destination.normalizedPreset() == OutboundApiSettings.PRESET_TELEGRAM_BOT &&
                raw.contains("api.telegram.org", ignoreCase = true)
            ) {
                return "Telegram Bot API connection failed before a JSON response: $raw"
            }
            return raw
        }
    }
}
