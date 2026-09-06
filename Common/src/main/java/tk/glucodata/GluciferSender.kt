package tk.glucodata

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import tk.glucodata.alerts.AlertStateTracker
import tk.glucodata.alerts.AlertType
import tk.glucodata.drivers.ManagedSensorRuntime

/** One bounded sender queue, independent of other API destinations and sensor callbacks. */
object GluciferSender {
    private const val PREFS = "glucifer_sender"
    private val limiter = GluciferDeliveryBudget()
    private val started = AtomicBoolean(false)
    private val updateLock = Any()
    private var updateTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var updateDueMs = Long.MAX_VALUE
    private var updateGeneration = 0L
    private val fallbackClock = GluciferFallbackClock()
    private var fallbackTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val historyTasks = mutableMapOf<String, java.util.concurrent.ScheduledFuture<*>>()
    private val journalTasks = mutableMapOf<String, java.util.concurrent.ScheduledFuture<*>>()
    private val tests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    internal fun requestTest(destinationId: String) {
        tests.add(destinationId)
        requestUpdate()
    }
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "GluciferSend").apply { isDaemon = true }
    }

    @JvmStatic
    fun ensureRunning(context: Context) {
        started.set(OutboundApiSettings.load(context).destinations.any { it.isGlucifer() && it.isReady() })
        executor.execute { scheduleFallback() }
        requestUpdate()
    }

    @JvmStatic
    fun requestUpdate() = scheduleUpdate(300)

    private fun scheduleUpdate(delayMs: Long) {
        if (!started.get()) return
        synchronized(updateLock) {
            val due = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(1)
            if (updateTask != null && updateDueMs <= due) return
            updateTask?.cancel(false)
            updateDueMs = due
            val generation = ++updateGeneration
            updateTask = executor.schedule({
                val current = synchronized(updateLock) {
                    if (generation != updateGeneration) false else {
                        updateTask = null
                        updateDueMs = Long.MAX_VALUE
                        true
                    }
                }
                if (current) cycle()
            }, delayMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        }
    }

    // Only used on the sender executor. No fixed polling cadence competes with source events.
    private fun scheduleFallback() {
        fallbackTask?.cancel(false)
        val context = Applic.app ?: return
        val ready = OutboundApiSettings.load(context).destinations.filter { it.isGlucifer() && it.isReady() }
        fallbackClock.retain(ready.map { it.id }.toSet())
        if (ready.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val delay = ready.minOf { maxOf(fallbackClock.remaining(it.id, it.gluciferFallbackSeconds, now), remaining(it, false)) }
        fallbackTask = executor.schedule({
            val instant = SystemClock.elapsedRealtime()
            val due = OutboundApiSettings.load(context).destinations.filter {
                it.isGlucifer() && it.isReady() && fallbackClock.remaining(it.id, it.gluciferFallbackSeconds, instant) == 0L
            }.map { it.id }.toSet()
            // Missing glucose or failed requests must not create a busy loop at the deadline.
            due.forEach { fallbackClock.reset(it, instant) }
            cycle(due)
        }, delay.coerceAtLeast(1), TimeUnit.MILLISECONDS)
    }

    internal fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null && uri.query == null &&
            Regex(".*/api/webhook/[A-Za-z0-9_-]+/?").matches(uri.path)
    }.getOrDefault(false)

    private fun cycle(fallbackIds: Set<String>? = null) {
        val context = Applic.app ?: return
        val config = OutboundApiSettings.load(context)
        config.destinations.filter { it.isGlucifer() && it.isReady() && (fallbackIds == null || it.id in fallbackIds) }.forEach { destination ->
            try {
                scheduleJournal(context, destination, fallbackIds == null)
                val remaining = remaining(destination, fallbackIds == null)
                if (remaining == 0L) send(context, destination, fallbackIds == null)
                else if (fallbackIds == null) scheduleUpdate(remaining)
            } catch (_: Exception) {
                // Exceptions can contain a secret endpoint URL; retain only a fixed status.
                OutboundApiSettings.recordAttempt(context, destination.id, -1, context.getString(R.string.glucifer_delivery_failed))
            }
        }
        scheduleFallback()
    }

    private fun send(context: Context, destination: OutboundApiSettings.Destination, live: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = destination.id
        val previous = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val oldPayload = previous?.optJSONObject("payload")
        val current = OutboundApi.currentReadingOrNull()
        val oldGlucose = oldPayload?.optJSONObject("glucose")
        val time = current?.timeMillis ?: oldGlucose?.optLong("time_ms") ?: return
        val mgdl = current?.mgdl ?: oldGlucose?.optInt("mgdl") ?: return
        if (time <= 0 || mgdl <= 0) return
        // A brief source transition must not replace the receiver's newer glucose.
        if (oldGlucose != null && time < oldGlucose.optLong("time_ms")) return
        val now = System.currentTimeMillis()
        val sequence = (oldPayload?.optLong("sequence") ?: 0L) + 1
        val availableAlerts = AlertType.entries.map { "alert:" + it.name.lowercase(Locale.ROOT) }.toSet()
        val selected = destination.gluciferFields.intersect(GluciferPayload.supported)
            .filter { !it.startsWith("alert:") || it in availableAlerts }.toSet()
        val values = optionalValues(context, current, selected, now)
        val states = AlertType.entries.associate { type ->
            type.name.lowercase(Locale.ROOT) to AlertStateTracker.activeEpisodeForExport(type)
        }
        val schemaVersion = if (destination.gluciferHistory || selected.any { it in setOf("sensor_started_ms", "sensor_expires_ms", "sensor_warmup") }) 2 else 1
        val candidate = GluciferPayload.build(destination.id, sequence, now, time, mgdl, selected, values, states, schemaVersion)
        val pending = previous?.optBoolean("pending", false) == true
        val force = tests.remove(destination.id)
        val payload = GluciferPayload.nextDelivery(oldPayload, candidate, pending, force) ?: run {
            if (oldPayload != null) sendHistory(context, destination, oldPayload)
            return
        }
        val record = JSONObject().put("payload", payload).put("pending", true)
        // Persist sequence and retry body together before exposing the snapshot on the wire.
        if (!prefs.edit().putString(key, record.toString()).commit()) return
        // Re-read after snapshot creation so a disabled or edited destination cancels old work.
        val latest = OutboundApiSettings.load(context).findDestination(destination.id) ?: return
        if (!latest.isGlucifer() || !latest.isReady() || latest.url != destination.url ||
            latest.gluciferFields != destination.gluciferFields || latest.gluciferHistory != destination.gluciferHistory) return
        if (!acquire(latest, live)) {
            if (force) tests.add(destination.id)
            return
        }
        val result = post(destination.resolvedUrl(), payload.toString())
        if (result.first in 200..299 && GluciferPayload.acknowledged(
                result.second, destination.id, payload.getLong("sequence"), payload.getInt("schema_version"))) {
            prefs.edit().putString(key, record.put("pending", false).toString())
                .putBoolean("backfill_status:" + destination.id, GluciferBackfillStatus.supported(result.second))
                .putBoolean("journal_capable:" + destination.id, GluciferJournal.supported(result.second)).apply()
            OutboundApiSettings.recordSuccess(context, destination.id, result.first)
            fallbackClock.reset(destination.id, SystemClock.elapsedRealtime())
            if (destination.gluciferHistory) {
                val history = historyState(prefs, destination)
                GluciferHistoryLedger.acknowledge(history, listOf(payload.getJSONObject("glucose").getLong("time_ms")), now)
                prefs.edit().putString("history:" + destination.id, history.toString()).commit()
            }
            scheduleHistory(context, destination, payload)
        } else {
            OutboundApiSettings.recordAttempt(context, destination.id, result.first,
                context.getString(R.string.glucifer_not_acknowledged))
        }
    }

    private fun optionalValues(
        context: Context, reading: OutboundApi.Reading?, selected: Set<String>, now: Long
    ): Map<String, Any?> {
        val values = mutableMapOf<String, Any?>()
        if (reading != null) {
            if ("trend" in selected) values["trend"] = reading.trendName.takeIf { it.isNotBlank() }
            if ("rate_mgdl_min" in selected) values["rate_mgdl_min"] = reading.trendRateMgdlPerMinute
            if ("raw_mgdl" in selected) values["raw_mgdl"] = reading.rawGlucoseMgdl
            if ("auto_mgdl" in selected) values["auto_mgdl"] = reading.autoMgdl.takeIf { it > 0 }
            if ("sensor_id" in selected) values["sensor_id"] = reading.sensorId.takeIf { it.isNotBlank() }
            if ("sensor_generation" in selected) values["sensor_generation"] = reading.sensorGen
            if ("delta_mgdl" in selected) values["delta_mgdl"] = delta(reading)
        }
        if (selected.any { it in setOf("sensor_started_ms", "sensor_expires_ms", "sensor_warmup") }) {
            val sensorId = reading?.sensorId ?: NotificationHistorySource.resolveSensorSerial()
            val managed = ManagedSensorRuntime.resolveUiSnapshot(sensorId, sensorId)
            val native = if (managed == null && sensorId != null) runCatching { Natives.str2sensorptr(SensorIdentity.resolveNativeSensorName(sensorId) ?: sensorId) }.getOrDefault(0L) else 0L
            val start = managed?.startTimeMs ?: if (native != 0L) runCatching { Natives.getSensorStartmsecFromSensorptr(native) }.getOrDefault(0L) else 0L
            val end = managed?.let { it.expectedEndMs.takeIf { end -> end > 0 } ?: it.officialEndMs } ?: if (native != 0L) runCatching { Natives.getSensorEndTimeFromSensorptr(native, false) }.getOrDefault(0L) else 0L
            if ("sensor_started_ms" in selected) values["sensor_started_ms"] = start.takeIf { it > 0 }
            if ("sensor_expires_ms" in selected) values["sensor_expires_ms"] = end.takeIf { it > 0 }
            if ("sensor_warmup" in selected) {
                val kind = if (native != 0L) runCatching { Natives.getSensorptrLibreVersion(native) }.getOrDefault(-1) else -1
                values["sensor_warmup"] = ManagedSensorRuntime.resolveDriver(sensorId)?.getWarmupState()
                    ?: if (kind == 0x40 && start > 0 && now >= start) now - start < 30 * 60000L else null
            }
        }
        if ("iob_u" in selected || "cob_g" in selected) {
            val journal = OutboundApi.loadJournalSnapshot(now)
            if ("iob_u" in selected) values["iob_u"] = journal.iob
            if ("cob_g" in selected) values["cob_g"] = journal.cob
        }
        if ("battery_percent" in selected) {
            val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            values["battery_percent"] = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
        }
        return values
    }

    private fun delta(reading: OutboundApi.Reading): Float? = runCatching {
        val points = NotificationHistorySource.getDisplayHistory(
            reading.timeMillis - 15 * 60_000L, Applic.unit == 1, reading.sensorId
        )
        val previous = points.lastOrNull {
            reading.timeMillis - it.timestamp >= GlucoseDelta.minGapMillis(5)
        } ?: return null
        val raw = CurrentDisplaySource.resolveViewModeForSensor(reading.sensorId) in setOf(1, 3)
        fun value(point: GlucosePoint) = if (raw && point.rawValue > 0) point.rawValue else point.value
        val previousMgdl = value(previous) * if (Applic.unit == 1) 18.0182f else 1f
        GlucoseDelta.delta(reading.timeMillis, reading.mgdl.toFloat(), previous.timestamp, previousMgdl, 5)
            .takeIf { it.isFinite() }
    }.getOrNull()

    private fun historyState(prefs: android.content.SharedPreferences, destination: OutboundApiSettings.Destination): JSONObject =
        prefs.getString("history:" + destination.id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.optString("url") == destination.url } ?: JSONObject().put("url", destination.url)

    // A scheduled live event gets the next slot before history or diagnostic work.
    private fun liveUpdateWaiting(): Boolean = synchronized(updateLock) { updateTask != null }
    private fun liveUpdateDelay(): Long = synchronized(updateLock) {
        if (updateTask == null) 1000L else (updateDueMs - SystemClock.elapsedRealtime()).coerceAtLeast(1000L)
    }

    private fun sendHistory(context: Context, destination: OutboundApiSettings.Destination, current: JSONObject) {
        if (liveUpdateWaiting() || journalTasks.isNotEmpty()) {
            val journalDelay = journalTasks.values.minOfOrNull { it.getDelay(TimeUnit.MILLISECONDS) } ?: 0L
            scheduleHistory(context, destination, current, maxOf(liveUpdateDelay(), journalDelay))
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "history:" + destination.id
        val saved = historyState(prefs, destination)
        GluciferHistoryLedger.unseen(saved, emptyList())
        val now = System.currentTimeMillis()
        var cursor = saved.optLong("cursor", now - GluciferHistory.RETENTION_MS)
        if (!saved.has("pending") && saved.optLong("refresh_at", 0) in 1..now) {
            cursor = now - GluciferHistory.RETENTION_MS
            saved.remove("refresh_at")
        }
        val through = current.getJSONObject("glucose").getLong("time_ms")
        if (!destination.gluciferHistory) saved.remove("pending")
        val pending = if (!destination.gluciferHistory) null else saved.optJSONObject("pending") ?: run {
            if (cursor >= through) null else {
                val sensor = NotificationHistorySource.resolveSensorSerial()
                val points = if (sensor == null) emptyList() else NotificationHistorySource.getDisplayHistory(cursor, false, sensor)
                val raw = sensor != null && CurrentDisplaySource.resolveViewModeForSensor(sensor) in setOf(1, 3)
                GluciferHistory.build(destination.id, GluciferHistoryLedger.unseen(saved, points), cursor, through, now, raw)
            }
        }
        if (pending != null) saved.put("pending", pending)
        else if (destination.gluciferHistory) {
            saved.put("cursor", through)
            if (!saved.has("refresh_at")) saved.put("refresh_at", now + 3600000L)
        }
        if (!prefs.edit().putString(key, saved.toString()).commit()) return
        val statusReady = reportBackfillStatus(context, destination, saved, pending != null)
        if (statusReady != true) {
            if (statusReady == false) scheduleHistory(context, destination, current, liveUpdateDelay())
            return
        }
        if (pending == null) return
        val latest = OutboundApiSettings.load(context).findDestination(destination.id) ?: return
        if (!latest.isReady() || !latest.isGlucifer() || !latest.gluciferHistory || latest.url != destination.url) return
        if (liveUpdateWaiting() || !acquire(latest, false)) {
            scheduleHistory(context, latest, current, maxOf(remaining(latest, false), liveUpdateDelay()))
            return
        }
        val result = post(destination.resolvedUrl(), pending.toString())
        if (result.first in 200..299 && GluciferHistory.acknowledged(result.second, pending)) {
            val readings = pending.getJSONArray("readings")
            GluciferHistoryLedger.acknowledge(saved, (0 until readings.length()).map {
                readings.getJSONObject(it).getLong("time_ms")
            }, now)
            saved.put("cursor", GluciferHistory.through(pending)).remove("pending")
            prefs.edit().putString(key, saved.toString()).commit()
            // The next check sends remaining batches or reports completion once.
            scheduleHistory(context, latest, current)
        } else {
            OutboundApiSettings.recordAttempt(context, destination.id, result.first, context.getString(R.string.glucifer_history_failed))
        }
    }

    // true: already reported or accepted; false: wait for a slot; null: failed, retry on next update.
    private fun reportBackfillStatus(context: Context, destination: OutboundApiSettings.Destination,
        state: JSONObject, active: Boolean): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("backfill_status:" + destination.id, false)) return true
        if (!GluciferBackfillStatus.needsReport(state, active)) return true
        val payload = state.optJSONObject("status_pending")?.takeIf { it.opt("active") == active }
            ?: GluciferBackfillStatus.build(destination.id, active)
        state.put("status_pending", payload)
        if (!prefs.edit().putString("history:" + destination.id, state.toString()).commit()) return null
        val latest = OutboundApiSettings.load(context).findDestination(destination.id) ?: return null
        if (!latest.isGlucifer() || !latest.isReady() || latest.url != destination.url || (active && !latest.gluciferHistory)) return null
        if (liveUpdateWaiting() || !limiter.acquire(destination.id, 1, true, true, SystemClock.elapsedRealtime())) return false
        val result = post(destination.resolvedUrl(), payload.toString())
        if (result.first !in 200..299 || !GluciferBackfillStatus.acknowledged(result.second, payload)) {
            OutboundApiSettings.recordAttempt(context, destination.id, result.first, context.getString(R.string.glucifer_history_failed))
            return null
        }
        state.put("backfill_active", active).remove("status_pending")
        prefs.edit().putString("history:" + destination.id, state.toString()).commit()
        return true
    }

    private fun scheduleHistory(context: Context, destination: OutboundApiSettings.Destination, payload: JSONObject, delayMs: Long = 1000L) {
        historyTasks.remove(destination.id)?.cancel(false)
        // Sleep until the available slot; completion transitions use the short request limit.
        historyTasks[destination.id] = executor.schedule({
            historyTasks.remove(destination.id)
            val latest = OutboundApiSettings.load(context).findDestination(destination.id)
            if (latest != null && latest.isGlucifer() && latest.isReady() && latest.url == destination.url) {
                try { sendHistory(context, latest, payload) } catch (_: Exception) {
                    OutboundApiSettings.recordAttempt(context, destination.id, -1, context.getString(R.string.glucifer_history_failed))
                }
            }
        }, delayMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)
    }

    private fun scheduleJournal(context: Context, destination: OutboundApiSettings.Destination,
        live: Boolean, delayMs: Long = 1000L) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!destination.gluciferJournal && !prefs.contains("journal:" + destination.id)) return
        if (!live && journalTasks.containsKey(destination.id)) return
        journalTasks.remove(destination.id)?.cancel(false)
        journalTasks[destination.id] = executor.schedule({
            journalTasks.remove(destination.id)
            val latest = OutboundApiSettings.load(context).findDestination(destination.id)
            if (latest != null && latest.isGlucifer() && latest.isReady()) {
                try { sendJournal(context, latest, live) } catch (_: Exception) {
                    OutboundApiSettings.recordAttempt(context, latest.id, -1, context.getString(R.string.glucifer_journal_failed))
                }
            }
        }, delayMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)
    }

    private fun sendJournal(context: Context, destination: OutboundApiSettings.Destination, live: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("journal_capable:" + destination.id, false)) return
        if (liveUpdateWaiting()) {
            scheduleJournal(context, destination, live, liveUpdateDelay())
            return
        }
        val key = "journal:" + destination.id
        val saved = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        if (saved.optString("url") != destination.url) {
            saved.remove("pending"); saved.remove("acknowledged"); saved.remove("settings")
            saved.put("url", destination.url)
        }
        val now = System.currentTimeMillis()
        val current = if (destination.gluciferJournal) {
            // Journal exists in the mobile source set only. Failure must not look like an empty journal.
            val type = Class.forName("tk.glucodata.OutboundApiJournalSnapshot")
            val method = type.getMethod("gluciferJournalJson", Integer.TYPE, java.lang.Boolean.TYPE, java.lang.Long.TYPE)
            val entries = org.json.JSONArray(method.invoke(null, destination.gluciferJournalDays, destination.gluciferJournalNotes, now).toString())
            (0 until entries.length()).map { entries.getJSONObject(it) }
        } else emptyList()
        val payload = GluciferJournal.next(saved, destination.id, current, destination.gluciferJournal,
            destination.gluciferJournalDays, destination.gluciferJournalNotes, now) ?: return
        if (!prefs.edit().putString(key, saved.toString()).commit()) return
        val latest = OutboundApiSettings.load(context).findDestination(destination.id) ?: return
        if (!latest.isReady() || !latest.isGlucifer() || latest.url != destination.url ||
            latest.gluciferJournal != destination.gluciferJournal || latest.gluciferJournalDays != destination.gluciferJournalDays ||
            latest.gluciferJournalNotes != destination.gluciferJournalNotes) return
        if (!acquire(latest, live)) {
            scheduleJournal(context, latest, live, remaining(latest, live))
            return
        }
        val result = post(latest.resolvedUrl(), payload.toString())
        if (result.first in 200..299 && GluciferJournal.acknowledged(result.second, payload)) {
            GluciferJournal.accept(saved, payload)
            if (!prefs.edit().putString(key, saved.toString()).commit()) return
            OutboundApiSettings.recordSuccess(context, destination.id, result.first)
            if (live) fallbackClock.reset(destination.id, SystemClock.elapsedRealtime())
            scheduleJournal(context, latest, live)
        } else {
            OutboundApiSettings.recordAttempt(context, destination.id, result.first, context.getString(R.string.glucifer_journal_failed))
        }
    }

    private fun remaining(destination: OutboundApiSettings.Destination, live: Boolean): Long =
        limiter.remaining(destination.id, destination.gluciferMinIntervalSeconds, destination.gluciferLiveBypass, live, SystemClock.elapsedRealtime())

    private fun acquire(destination: OutboundApiSettings.Destination, live: Boolean): Boolean =
        limiter.acquire(destination.id, destination.gluciferMinIntervalSeconds, destination.gluciferLiveBypass, live, SystemClock.elapsedRealtime())

    internal fun post(url: String, body: String): Pair<Int, String> {
        require(validUrl(url))
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 5_000
            readTimeout = 5_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use {
                val buffer = ByteArray(8192)
                var count = 0
                while (count < buffer.size) {
                    val read = it.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                String(buffer, 0, count, Charsets.UTF_8)
            }.orEmpty()
            status to text
        } finally {
            connection.disconnect()
        }
    }
}
