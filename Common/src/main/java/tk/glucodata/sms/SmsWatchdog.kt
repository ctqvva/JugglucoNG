package tk.glucodata.sms

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.Keep
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.OutboundApiSettings

/**
 * Runtime behind the SMS destinations.
 *
 * Owns the state [SmsEscalation] needs but cannot compute for itself — which
 * alarms are still unanswered, how long the data path has been down, what has
 * already been texted — persists it across process death, and ticks the pure
 * planner on a timer.
 *
 * All alert bookkeeping arrives through [onAlertFired] / [onAlertAcknowledged] /
 * [onAlertResolved], which `AlertStateTracker` calls for every alert episode.
 * That is the one place in the app where "an alarm fired" and "a human dealt
 * with it" are already distinguished, so the watchdog needs no knowledge of the
 * notification, alarm-activity or retry-session plumbing.
 */
@Keep
object SmsWatchdog {
    private const val LOG_ID = "SmsWatchdog"
    private const val PREFS = "sms_watchdog"
    private const val KEY_ALARMS = "alarms"
    private const val KEY_EPISODES = "episodes"
    private const val KEY_BUDGET = "budget"
    private const val KEY_LAST_RELAY_AT = "last_relay_at"
    private const val KEY_LAST_ACK_AT = "last_ack_at"
    private const val KEY_OFFLINE_SINCE = "offline_since"

    /** An unanswered alarm stops being interesting long after everything else has moved on. */
    private const val ALARM_MAX_AGE_MS = 6 * 60 * 60_000L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "SmsWatchdog")
    }
    private val lock = Any()
    private var tickTask: ScheduledFuture<*>? = null

    // ---------------------------------------------------------------- hooks

    @JvmStatic
    fun onAlertFired(alertId: Int) = post("onAlertFired") {
        val context = Applic.app ?: return@post
        if (!isActive(context)) return@post
        val now = System.currentTimeMillis()
        val alarms = loadAlarms(context).toMutableList()
        val existing = alarms.indexOfFirst { it.alertId == alertId }
        if (existing >= 0) {
            // Same episode re-firing: keep the original moment so the unacked
            // countdown measures how long the person has been ignoring it.
            val previous = alarms[existing]
            if (previous.acknowledgedAtMs > 0L) {
                alarms[existing] = previous.copy(firedAtMs = now, acknowledgedAtMs = 0L)
            }
        } else {
            alarms += ArmedAlarm(alertId = alertId, alertName = "", firedAtMs = now)
        }
        saveAlarms(context, alarms)
        ensureRunning(context)
        scheduleTick(0L)
    }

    @JvmStatic
    fun onAlertAcknowledged(alertId: Int) = post("onAlertAcknowledged") {
        val context = Applic.app ?: return@post
        val now = System.currentTimeMillis()
        val alarms = loadAlarms(context).map { alarm ->
            if (alarm.alertId == alertId && alarm.acknowledgedAtMs <= 0L) {
                alarm.copy(acknowledgedAtMs = now)
            } else {
                alarm
            }
        }
        saveAlarms(context, alarms)
        prefs(context).edit().putLong(KEY_LAST_ACK_AT, now).apply()
        scheduleTick(0L)
    }

    /** The condition itself cleared, whether or not anybody acknowledged it. */
    @JvmStatic
    fun onAlertResolved(alertId: Int) = post("onAlertResolved") {
        val context = Applic.app ?: return@post
        val alarms = loadAlarms(context)
        if (alarms.none { it.alertId == alertId }) return@post
        saveAlarms(context, alarms.filterNot { it.alertId == alertId })
        scheduleTick(0L)
    }

    /** Starts (or stops) the tick loop to match the current configuration. */
    @JvmStatic
    fun ensureRunning(context: Context = Applic.app) {
        val active = isActive(context)
        synchronized(lock) {
            if (!active) {
                tickTask?.cancel(false)
                tickTask = null
                return
            }
            if (tickTask == null) {
                scheduleTickLocked(SmsEscalation.MIN_TICK_MS)
            }
        }
    }

    // ---------------------------------------------------------------- tick

    private fun scheduleTick(delayMs: Long) {
        synchronized(lock) { scheduleTickLocked(delayMs) }
    }

    private fun scheduleTickLocked(delayMs: Long) {
        tickTask?.cancel(false)
        tickTask = scheduler.schedule(
            { runTick() },
            delayMs.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS
        )
    }

    private fun runTick() {
        val context = Applic.app ?: return
        var nextDelayMs = SmsEscalation.MAX_TICK_MS
        try {
            val destinations = smsDestinations(context)
            if (destinations.isEmpty()) {
                synchronized(lock) { tickTask = null }
                return
            }
            nextDelayMs = destinations.minOf { evaluate(context, it) }
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "tick", th)
        }
        synchronized(lock) {
            scheduleTickLocked(nextDelayMs.coerceIn(SmsEscalation.MIN_TICK_MS, SmsEscalation.MAX_TICK_MS))
        }
    }

    private fun evaluate(context: Context, destination: OutboundApiSettings.Destination): Long {
        val policy = destination.smsPolicy.sanitized()
        val now = System.currentTimeMillis()
        val budget = SmsBudget.decode(prefs(context).getString(KEY_BUDGET, null)).pruned(now)
        val reading = SmsMessageComposer.currentReading()
        val state = SmsWatchState(
            alarms = loadAlarms(context).map { it.copy(alertName = SmsMessageComposer.alertName(context, it.alertId)) },
            readingMgdl = reading?.mgdl ?: 0,
            readingAtMs = reading?.timeMillis ?: 0L,
            dataPathDownSinceMs = dataPathDownSince(context, destination, now),
            episodes = loadEpisodes(context, destination.id),
            lastRelayAtMs = prefs(context).getLong(relayKey(destination.id), 0L),
            lastAlarmAckAtMs = prefs(context).getLong(KEY_LAST_ACK_AT, 0L),
            sentInLastHour = budget.countInLastHour(now),
            sentInLastDay = budget.countInLastDay(now)
        )

        val plan = SmsEscalation.plan(policy, state, now)
        var sent = 0
        plan.messages.forEach { message ->
            val text = SmsMessageComposer.compose(context, destination, policy, message, reading)
            message.contacts.forEach { contact ->
                if (SmsGateway.send(context, destination.id, contact.number, text)) {
                    sent += 1
                }
            }
        }

        persistEpisodes(context, destination.id, state.episodes, plan, now)
        if (sent > 0) {
            prefs(context).edit()
                .putString(KEY_BUDGET, budget.record(now, sent).encode())
                .putLong(relayKey(destination.id), now)
                .apply()
            Log.i(LOG_ID, "Sent $sent SMS for destination ${destination.id}")
        }
        pruneAlarms(context, now)
        return plan.nextCheckDelayMs
    }

    /**
     * Merges the planner's view of what is happening with what has already been
     * sent, so the next tick knows which stage each episode reached.
     */
    private fun persistEpisodes(
        context: Context,
        destinationId: String,
        previous: Map<String, EpisodeProgress>,
        plan: SmsPlan,
        nowMs: Long
    ) {
        val next = LinkedHashMap<String, EpisodeProgress>(plan.activeEpisodes.size)
        plan.activeEpisodes.forEach { (key, openedAtMs) ->
            next[key] = previous[key]?.copy(openedAtMs = openedAtMs)
                ?: EpisodeProgress(openedAtMs = openedAtMs)
        }
        plan.messages.forEach { message ->
            if (message.kind == SmsEventKind.RELAY || message.kind == SmsEventKind.ALL_CLEAR) return@forEach
            val current = next[message.episodeKey] ?: return@forEach
            next[message.episodeKey] = current.copy(
                sends = current.sends + 1,
                lastSentAtMs = nowMs,
                maxStageNotified = maxOf(current.maxStageNotified, message.stage)
            )
        }
        saveEpisodes(context, destinationId, next)
    }

    // ------------------------------------------------------------ test send

    const val TEST_SENT = 0
    const val TEST_NO_CONTACTS = 1
    const val TEST_NO_PERMISSION = 2
    const val TEST_NO_TELEPHONY = 3
    const val TEST_BUDGET_EXHAUSTED = 4

    /** Sends one text to every enabled contact so the user can verify the setup. */
    @JvmStatic
    fun sendTest(context: Context, destinationId: String): Int {
        val destination = OutboundApiSettings.load(context).findDestination(destinationId)
            ?: return TEST_NO_CONTACTS
        val policy = destination.smsPolicy.sanitized()
        val contacts = policy.contacts.filter { it.enabled }
        if (contacts.isEmpty()) return TEST_NO_CONTACTS
        when (SmsGateway.availability(context)) {
            SmsGateway.Availability.NO_TELEPHONY -> return TEST_NO_TELEPHONY
            SmsGateway.Availability.PERMISSION_MISSING -> return TEST_NO_PERMISSION
            SmsGateway.Availability.READY -> Unit
        }
        val now = System.currentTimeMillis()
        val budget = SmsBudget.decode(prefs(context).getString(KEY_BUDGET, null)).pruned(now)
        val allowance = minOf(
            policy.maxPerHour - budget.countInLastHour(now),
            policy.maxPerDay - budget.countInLastDay(now)
        )
        if (allowance <= 0) return TEST_BUDGET_EXHAUSTED

        val text = SmsMessageComposer.composeTest(context, destination, policy)
        var sent = 0
        contacts.take(allowance).forEach { contact ->
            if (SmsGateway.send(context, destination.id, contact.number, text)) sent += 1
        }
        if (sent > 0) {
            prefs(context).edit()
                .putString(KEY_BUDGET, budget.record(now, sent).encode())
                .apply()
        }
        return TEST_SENT
    }

    /** Preview of the message a contact would receive, for the settings screen. */
    @JvmStatic
    fun previewText(context: Context, destinationId: String): String {
        val destination = OutboundApiSettings.load(context).findDestination(destinationId)
            ?: return ""
        return SmsMessageComposer.composeTest(context, destination, destination.smsPolicy.sanitized())
    }

    @JvmStatic
    fun remainingToday(context: Context): Int {
        val now = System.currentTimeMillis()
        val budget = SmsBudget.decode(prefs(context).getString(KEY_BUDGET, null)).pruned(now)
        return budget.countInLastDay(now)
    }

    // --------------------------------------------------------------- state

    private fun isActive(context: Context?): Boolean {
        if (context == null) return false
        return smsDestinations(context).isNotEmpty()
    }

    private fun smsDestinations(context: Context): List<OutboundApiSettings.Destination> =
        OutboundApiSettings.load(context).destinations.filter {
            it.enabled && it.normalizedPreset() == OutboundApiSettings.PRESET_SMS &&
                it.smsPolicy.hasUsableContacts()
        }

    /**
     * When the outbound path last worked.
     *
     * "Down" means either the device has no usable network at all, or every other
     * enabled destination's most recent attempt failed — which is what a follower
     * on the far end actually experiences.
     */
    private fun dataPathDownSince(
        context: Context,
        smsDestination: OutboundApiSettings.Destination,
        nowMs: Long
    ): Long {
        val store = prefs(context)
        if (!hasUsableNetwork(context)) {
            val recorded = store.getLong(KEY_OFFLINE_SINCE, 0L)
            if (recorded > 0L) return recorded
            store.edit().putLong(KEY_OFFLINE_SINCE, nowMs).apply()
            return nowMs
        }
        if (store.getLong(KEY_OFFLINE_SINCE, 0L) != 0L) {
            store.edit().putLong(KEY_OFFLINE_SINCE, 0L).apply()
        }

        val networkDestinations = OutboundApiSettings.load(context).destinations.filter {
            it.id != smsDestination.id &&
                it.enabled &&
                it.normalizedPreset() != OutboundApiSettings.PRESET_SMS &&
                it.isReady()
        }
        if (networkDestinations.isEmpty()) return 0L
        val allFailing = networkDestinations.all {
            it.lastAttemptAtMs > 0L && it.lastAttemptAtMs > it.lastSuccessAtMs
        }
        if (!allFailing) return 0L
        return networkDestinations.maxOf { it.lastSuccessAtMs }.takeIf { it > 0L } ?: nowMs
    }

    private fun hasUsableNetwork(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun pruneAlarms(context: Context, nowMs: Long) {
        val alarms = loadAlarms(context)
        val kept = alarms.filter { nowMs - it.firedAtMs < ALARM_MAX_AGE_MS }
        if (kept.size != alarms.size) saveAlarms(context, kept)
    }

    // ---------------------------------------------------------- persistence

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun relayKey(destinationId: String) = "${KEY_LAST_RELAY_AT}_$destinationId"

    private fun episodeKey(destinationId: String) = "${KEY_EPISODES}_$destinationId"

    private fun loadAlarms(context: Context): List<ArmedAlarm> {
        val raw = prefs(context).getString(KEY_ALARMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                ArmedAlarm(
                    alertId = item.optInt("alertId", -1),
                    alertName = item.optString("alertName", ""),
                    firedAtMs = item.optLong("firedAtMs", 0L),
                    acknowledgedAtMs = item.optLong("acknowledgedAtMs", 0L)
                ).takeIf { it.alertId >= 0 && it.firedAtMs > 0L }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAlarms(context: Context, alarms: List<ArmedAlarm>) {
        val array = JSONArray()
        alarms.forEach { alarm ->
            array.put(
                JSONObject()
                    .put("alertId", alarm.alertId)
                    .put("alertName", alarm.alertName)
                    .put("firedAtMs", alarm.firedAtMs)
                    .put("acknowledgedAtMs", alarm.acknowledgedAtMs)
            )
        }
        prefs(context).edit().putString(KEY_ALARMS, array.toString()).apply()
    }

    private fun loadEpisodes(context: Context, destinationId: String): Map<String, EpisodeProgress> {
        val raw = prefs(context).getString(episodeKey(destinationId), null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val out = LinkedHashMap<String, EpisodeProgress>(obj.length())
            obj.keys().forEach { key ->
                val item = obj.optJSONObject(key) ?: return@forEach
                out[key] = EpisodeProgress(
                    openedAtMs = item.optLong("openedAtMs", 0L),
                    sends = item.optInt("sends", 0),
                    lastSentAtMs = item.optLong("lastSentAtMs", 0L),
                    maxStageNotified = item.optInt("maxStageNotified", -1)
                )
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun saveEpisodes(
        context: Context,
        destinationId: String,
        episodes: Map<String, EpisodeProgress>
    ) {
        val obj = JSONObject()
        episodes.forEach { (key, progress) ->
            obj.put(
                key,
                JSONObject()
                    .put("openedAtMs", progress.openedAtMs)
                    .put("sends", progress.sends)
                    .put("lastSentAtMs", progress.lastSentAtMs)
                    .put("maxStageNotified", progress.maxStageNotified)
            )
        }
        prefs(context).edit().putString(episodeKey(destinationId), obj.toString()).apply()
    }

    /**
     * Alert hooks are called from inside `AlertStateTracker`'s monitor, on the
     * thread that is about to make a phone scream. Nothing here may block it, so
     * the work is handed to the watchdog's own executor.
     */
    private fun post(what: String, block: () -> Unit) {
        try {
            scheduler.execute {
                try {
                    block()
                } catch (th: Throwable) {
                    Log.stack(LOG_ID, what, th)
                }
            }
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "post $what", th)
        }
    }
}
