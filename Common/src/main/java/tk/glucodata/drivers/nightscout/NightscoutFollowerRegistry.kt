package tk.glucodata.drivers.nightscout

import android.content.Context
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.Locale
import tk.glucodata.GlucoseUpdateBroadcaster
import tk.glucodata.ManagedCurrentSensor
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorUiSignals

object NightscoutFollowerRegistry {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_ENABLED = "nightscout_follower_enabled"
    private const val PREF_URL = "nightscout_follower_url"
    private const val PREF_SECRET = "nightscout_follower_secret"
    //The uploader's v3 flag is an uploader setting and never reaches here: a follower may point
    //at an entirely different server than the one this phone uploads to.
    private const val PREF_USE_V3 = "nightscout_follower_v3"
    private const val PREF_COMPLETE_HISTORY_PREFIX = "nightscout_follower_complete_history_v1_"
    private const val PREF_LOCAL_FOLLOWER_PREFIX = "nightscout_follower_local_v1_"
    private const val PREF_POLL_MINUTES = "nightscout_follower_poll_minutes"
    private const val PREF_SEND_TREATMENTS = "nightscout_follower_send_treatments"
    const val SENSOR_PREFIX = "NSF-"

    data class Config(
        val enabled: Boolean,
        val url: String,
        val secret: String,
        val useV3: Boolean = false,
    ) {
        val sensorId: String get() = deriveSensorId(url)
        val isUsable: Boolean get() = enabled && url.isNotBlank()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun normalizeUrl(url: String?): String =
        url?.trim()
            ?.removeSuffix("/")
            ?.takeIf { it.isNotEmpty() }
            ?.let { raw ->
                if (raw.startsWith("http://", ignoreCase = true) ||
                    raw.startsWith("https://", ignoreCase = true)
                ) {
                    raw
                } else {
                    "https://$raw"
                }
            }
            .orEmpty()

    fun deriveSensorId(url: String?): String {
        val normalized = normalizeUrl(url)
        if (normalized.isEmpty()) return SENSOR_PREFIX + "UNCONFIGURED"
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(normalized.lowercase(Locale.US).toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02X".format(Locale.US, it) }
        return SENSOR_PREFIX + digest
    }

    fun loadConfig(context: Context): Config =
        Config(
            enabled = prefs(context).getBoolean(PREF_ENABLED, false),
            url = normalizeUrl(prefs(context).getString(PREF_URL, null)),
            secret = prefs(context).getString(PREF_SECRET, null).orEmpty(),
            useV3 = prefs(context).getBoolean(PREF_USE_V3, false),
        )

    fun saveConfig(
        context: Context,
        enabled: Boolean,
        url: String?,
        secret: String?,
        useV3: Boolean = loadConfig(context).useV3,
    ) {
        // Migrate the previous configured id before a URL edit can erase the only evidence
        // that an interrupted first import belonged to this phone.
        loadConfig(context).takeIf { it.isUsable }?.let { markLocalFollower(context, it.sensorId) }
        prefs(context).edit()
            .putBoolean(PREF_ENABLED, enabled)
            .putString(PREF_URL, normalizeUrl(url).takeIf { it.isNotEmpty() })
            .putString(PREF_SECRET, secret?.trim()?.takeIf { it.isNotEmpty() })
            .putBoolean(PREF_USE_V3, useV3)
            .apply()
        ManagedSensorUiSignals.markDeviceListDirty()
        // The sensor id is derived from the URL, so every save is a possible change of which
        // server this phone follows. The settings screen saves without connecting (on every
        // toggle and when it closes), so the connect call cannot be the place that notices.
        retireSupersededFollowers(context)
    }

    /** How often the follower asks, in minutes. Sanitized on the way out and on the way in. */
    fun loadPollMinutes(context: Context?): Int =
        context?.let {
            NightscoutFollowerPollPolicy.sanitizeMinutes(
                prefs(it).getInt(PREF_POLL_MINUTES, NightscoutFollowerPollPolicy.DEFAULT_MINUTES)
            )
        } ?: NightscoutFollowerPollPolicy.DEFAULT_MINUTES

    fun savePollMinutes(context: Context, minutes: Int) {
        prefs(context).edit()
            .putInt(PREF_POLL_MINUTES, NightscoutFollowerPollPolicy.sanitizeMinutes(minutes))
            .apply()
        // apply() updates the in-memory value at once, so the follower already reads the new
        // interval here; its pending alarm was booked with the old one.
        findRunningFollower(loadConfig(context).sensorId)?.onPollIntervalChanged()
    }

    /**
     * Whether a follower sends this phone's own journal entries to the server it follows.
     * Opt-in: a parent following a child's Nightscout logs the doses and meals they gave, and
     * without this those only went out by switching to Upload, which also stops following.
     */
    fun loadSendTreatments(context: Context): Boolean =
        prefs(context).getBoolean(PREF_SEND_TREATMENTS, false)

    fun saveSendTreatments(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_SEND_TREATMENTS, enabled).apply()
    }

    /** Follow mode is on and sending was asked for. Upload mode has its own switch. */
    fun sendsTreatments(context: Context?): Boolean {
        context ?: return false
        return loadConfig(context).isUsable && loadSendTreatments(context)
    }

    fun persistedSensorIds(context: Context): List<String> =
        loadConfig(context).takeIf { it.isUsable }?.let { listOf(it.sensorId) }.orEmpty()

    fun hasCompleteHistoryImport(context: Context?, sensorId: String): Boolean =
        context != null &&
            prefs(context).getBoolean(PREF_COMPLETE_HISTORY_PREFIX + sensorId.uppercase(Locale.US), false)

    fun markCompleteHistoryImport(context: Context?, sensorId: String) {
        if (context == null || sensorId.isBlank()) return
        prefs(context).edit()
            .putBoolean(PREF_COMPLETE_HISTORY_PREFIX + sensorId.uppercase(Locale.US), true)
            .apply()
    }

    internal fun hasLocalFollower(context: Context, sensorId: String): Boolean =
        prefs(context).getBoolean(PREF_LOCAL_FOLLOWER_PREFIX + sensorId.uppercase(Locale.US), false) ||
            hasCompleteHistoryImport(context, sensorId)

    private fun markLocalFollower(context: Context, sensorId: String) {
        prefs(context).edit()
            .putBoolean(PREF_LOCAL_FOLLOWER_PREFIX + sensorId.uppercase(Locale.US), true)
            .apply()
    }

    fun createRestoredCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        val config = loadConfig(context)
        if (!config.isUsable || !matchesSensorId(sensorId, config.sensorId)) return null
        markLocalFollower(context, config.sensorId)
        return NightscoutFollowerManager(
            serial = config.sensorId,
            url = config.url,
            secret = config.secret,
            useV3 = config.useV3,
            dataptr = dataptr,
        )
    }

    /**
     * Restore the configured follower without requiring Bluetooth initialization.
     *
     * Application.onCreate runs before an alarm receiver. Restoring here means an alarm that
     * starts a fresh process has a callback to hand its wakelock to instead of ending the poll
     * chain. The list lock serializes this compound update with roster rebuilds.
     */
    @JvmOverloads
    fun restoreConfiguredFollower(
        context: Context,
        sensorId: String = loadConfig(context).sensorId,
    ): NightscoutFollowerManager? {
        val existing = findRunningFollower(sensorId)
        if (existing != null) {
            applyConfigTo(existing, loadConfig(context))
            return existing
        }

        var added = false
        val follower = synchronized(SensorBluetooth.gattcallbacks) {
            findRunningFollowerLocked(sensorId)?.also {
                applyConfigTo(it, loadConfig(context))
            } ?: run {
                val restored = createRestoredCallback(context, sensorId, 0L) as? NightscoutFollowerManager
                    ?: return@synchronized null
                SensorBluetooth.gattcallbacks.add(restored)
                Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size)
                added = true
                restored
            }
        } ?: return null

        if (added) {
            SensorBluetooth.ensureCurrentSensorSelection()
            ManagedSensorUiSignals.markDeviceListDirty()
        }
        return follower
    }

    fun recoverOnNetworkAvailable(context: Context) {
        val config = loadConfig(context)
        if (!config.isUsable) return
        restoreConfiguredFollower(context, config.sensorId)
            ?.recoverIfNeeded("network", forceWhenIdle = true)
    }

    /** The app came to the foreground: fetch now rather than at the next scheduled poll. */
    fun refreshOnForeground(context: Context): Boolean {
        val config = loadConfig(context)
        if (!config.isUsable) return false
        return restoreConfiguredFollower(context, config.sensorId)?.requestForegroundRefresh() == true
    }

    /**
     * Bring the follower back after the Bluetooth roster was torn down. Turning Bluetooth use
     * off frees every callback, the follower included, although it never used Bluetooth; that
     * also cancels its alarm, so nothing would poll again until the network changed.
     */
    fun ensureFollowerRunning(context: Context?) {
        context ?: return
        val config = loadConfig(context)
        if (!config.isUsable) return
        if (findRunningFollower(config.sensorId) != null) return
        connectSensor(context, config.sensorId)
    }

    /**
     * Whether [follower] may cancel the poll alarm. All instances for one sensor book the same
     * PendingIntent, so closing one that is not the registered follower (a discarded duplicate,
     * a stale instance) must leave the registered one's alarm alone.
     */
    internal fun ownsSharedPollAlarm(follower: NightscoutFollowerManager): Boolean =
        mayCancelSharedAlarm(follower, SensorBluetooth.mygatts()) { other ->
            other is NightscoutFollowerManager && other.matchesManagedSensorId(follower.SerialNumber)
        }

    internal fun <T> mayCancelSharedAlarm(
        self: T,
        running: List<T>,
        sharesAlarm: (T) -> Boolean,
    ): Boolean = running.none { other -> other !== self && sharesAlarm(other) }

    private fun applyConfigTo(follower: NightscoutFollowerManager, config: Config) {
        // Only the follower the config names: a stale instance must not adopt another server.
        if (!matchesSensorId(follower.SerialNumber, config.sensorId)) return
        follower.updateConfig(config.url, config.secret, config.useV3)
    }

    /**
     * What [retireSupersededFollowers] needs from the running app, so the rules can be tested
     * without a roster, native code or a selection store.
     */
    internal interface FollowerRoster {
        /** Serials of the Nightscout followers that currently have a callback. */
        fun runningFollowers(): List<String>
        /** Whether [serial] is the main sensor, or would lose that role if it went. */
        fun holdsMain(serial: String): Boolean
        /** Stop [serial] and drop its callback, keeping its history. */
        fun retire(serial: String)
        /** Make sure the follower for [sensorId] has a callback and is polling. */
        fun connect(sensorId: String)
        fun selectMain(sensorId: String)
        /** Give the main role [retired] held to whatever else is available, or to nobody. */
        fun selectReplacement(retired: String)
        fun refreshDisplays()
    }

    /**
     * Retire every running follower the saved configuration no longer names. Returns whether
     * any was retired.
     *
     * Changing the URL changes the sensor id, so the old follower is a different sensor that
     * went on polling its old server, and, as the main sensor, kept the widget and floating
     * window on stale data. The replacement takes the main role only if the old follower held
     * it: someone who picked a physical CGM as main keeps it. The role is asked before
     * anything is retired, because retiring a sensor is what takes it away.
     *
     * Follow on with the URL cleared names no server at all: nothing replaces the follower
     * then, and what it held of the main role goes to whatever else is available. The settings
     * screen saves exactly that state when the field is emptied.
     *
     * Follow off uses the same retirement path, including when Bluetooth was never started.
     */
    internal fun retireSupersededFollowers(config: Config, roster: FollowerRoster): Boolean {
        val expected = config.sensorId.takeIf { config.isUsable }
        val superseded = roster.runningFollowers()
            .filterNot { expected != null && matchesSensorId(it, expected) }
        if (superseded.isEmpty()) return false
        val holder = superseded.firstOrNull(roster::holdsMain)
        superseded.forEach(roster::retire)
        // A URL edit saved with connectFollower=false must still replace the polling source,
        // even when a physical sensor holds the main role.
        if (expected != null) roster.connect(expected)
        if (holder != null) {
            if (expected != null) {
                roster.selectMain(expected)
            } else {
                roster.selectReplacement(holder)
            }
        }
        roster.refreshDisplays()
        return true
    }

    fun retireSupersededFollowers(context: Context): Boolean =
        retireSupersededFollowers(loadConfig(context), LiveFollowerRoster(context))

    private class LiveFollowerRoster(private val context: Context) : FollowerRoster {
        override fun runningFollowers(): List<String> =
            SensorBluetooth.mygatts()
                .filterIsInstance<NightscoutFollowerManager>()
                .map { it.SerialNumber }

        override fun holdsMain(serial: String): Boolean = SensorIdentity.lostMainRole(serial)

        // Works on the roster itself: the Bluetooth object is gone on a phone that only
        // follows, and sensorEnded() does nothing then, leaving the stopped callback in the roster.
        override fun retire(serial: String) {
            synchronized(SensorBluetooth.gattcallbacks) {
                SensorBluetooth.gattcallbacks
                    .filter { it is NightscoutFollowerManager && matchesSensorId(it.SerialNumber, serial) }
                    .forEach { callback ->
                        (callback as ManagedBluetoothSensorDriver).terminateManagedSensor(wipeData = false)
                        callback.close()
                        SensorBluetooth.gattcallbacks.remove(callback)
                    }
                Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size)
            }
            NightscoutFollowerDeviceStatus.clear()
            ManagedCurrentSensor.clearIfMatches(serial)
            // Retire the native mirror without deleting its history. Readings from the same
            // server later revive it.
            runCatching { Natives.finishSensorById(serial) }
        }

        override fun connect(sensorId: String) {
            connectSensor(context, sensorId)
        }

        override fun selectMain(sensorId: String) {
            SensorBluetooth.setCurrentSensorSelection(sensorId)
        }

        override fun selectReplacement(retired: String) {
            SensorBluetooth.setCurrentSensorSelection(
                SensorBluetooth.resolveReplacementSensorSerial(retired).orEmpty()
            )
        }

        override fun refreshDisplays() {
            ManagedSensorUiSignals.markDeviceListDirty()
            runCatching { GlucoseUpdateBroadcaster.sendNow() }
        }
    }

    /**
     * Followers that were retired by a URL change before that was handled: the old id is
     * still the stored main sensor (and its native mirror still unfinished), so the widget
     * kept the stale server after every restart.
     *
     * Only an id this phone ran itself counts. A phone receiving a clone holds mirrored
     * sensors that were never its own, and those must stay as they are.
     *
     * Native storage names a sensor without the first five characters of its id, which is
     * where the `NSF-` prefix is, so [fullName] gives each candidate back its full id before
     * anything is asked about it. A name it does not know stays as it is.
     */
    internal fun orphanedFollowerIds(
        candidates: Collection<String?>,
        config: Config,
        ranHere: (String) -> Boolean,
        fullName: (String) -> String? = { it },
    ): List<String> {
        if (config.url.isBlank()) return emptyList()
        return candidates.asSequence()
            .filterNotNull()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { name -> fullName(name)?.trim()?.takeIf { it.isNotEmpty() } ?: name }
            .filter { isFollowerSensorId(it) && !matchesSensorId(it, config.sensorId) && ranHere(it) }
            .distinctBy { it.uppercase(Locale.US) }
            .toList()
    }

    /** Run after [restoreConfiguredFollower] at start-up. Never throws. */
    fun reconcileOrphanedFollowers(context: Context) {
        runCatching {
            val config = loadConfig(context)
            if (config.url.isBlank()) return
            val managed = ManagedCurrentSensor.get()
            val nativeCurrent = runCatching { Natives.currentsensorname() }.getOrNull()
            val shells = runCatching { Natives.activeSensors() }.getOrNull().orEmpty().toList()
            val ranHere = { id: String -> hasLocalFollower(context, id) }
            val fullName = { name: String -> runCatching { Natives.resolveFullSensorName(name) }.getOrNull() }
            val orphanShells = orphanedFollowerIds(shells + nativeCurrent, config, ranHere, fullName)
            // Managed selection is authoritative even before it has a native mirror. A stale
            // native reference may need retirement, but cannot give away somebody else's role.
            val orphanMain = orphanedFollowerIds(listOf(managed ?: nativeCurrent), config, ranHere, fullName)
            if (orphanShells.isEmpty() && orphanMain.isEmpty()) return
            orphanShells.forEach { runCatching { Natives.finishSensorById(it) } }
            if (orphanMain.isNotEmpty()) {
                orphanMain.forEach { ManagedCurrentSensor.clearIfMatches(it) }
                val target = config.sensorId.takeIf { config.isUsable }
                    ?: SensorBluetooth.resolveReplacementSensorSerial(orphanMain.first())
                SensorBluetooth.setCurrentSensorSelection(target.orEmpty())
            }
            ManagedSensorUiSignals.markDeviceListDirty()
            runCatching { GlucoseUpdateBroadcaster.sendNow() }
        }
    }

    fun enableFollowerSensor(
        context: Context,
        url: String?,
        secret: String?,
        connectNow: Boolean = true,
        useV3: Boolean = loadConfig(context).useV3,
    ): String? {
        val normalizedUrl = normalizeUrl(url)
        if (normalizedUrl.isEmpty()) return null
        saveConfig(context, enabled = true, url = normalizedUrl, secret = secret, useV3 = useV3)
        val sensorId = deriveSensorId(normalizedUrl)
        if (connectNow) {
            connectSensor(context, sensorId)
        }
        return sensorId
    }

    fun disableFollowerSensor(context: Context) {
        val config = loadConfig(context)
        NightscoutFollowerDeviceStatus.clear()
        saveConfig(context, enabled = false, url = config.url, secret = config.secret, useV3 = config.useV3)
        // Also clear a persisted selection if there was no callback left to retire.
        ManagedCurrentSensor.clearIfMatches(config.sensorId)
    }

    fun connectSensor(context: Context, sensorId: String) {
        val callback = restoreConfiguredFollower(context, sensorId) ?: return
        SensorBluetooth.ensureCurrentSensorSelection()
        callback.connectDevice(0)
        ManagedSensorUiSignals.markDeviceListDirty()
    }

    private fun findRunningFollower(sensorId: String): NightscoutFollowerManager? =
        SensorBluetooth.mygatts().firstOrNull { callback ->
            callback is NightscoutFollowerManager && callback.matchesManagedSensorId(sensorId)
        } as? NightscoutFollowerManager

    private fun findRunningFollowerLocked(sensorId: String): NightscoutFollowerManager? =
        SensorBluetooth.gattcallbacks.firstOrNull { callback ->
            callback is NightscoutFollowerManager && callback.matchesManagedSensorId(sensorId)
        } as? NightscoutFollowerManager

    fun matchesSensorId(candidate: String?, expected: String?): Boolean {
        val left = candidate?.trim().orEmpty()
        val right = expected?.trim().orEmpty()
        return left.isNotEmpty() && right.isNotEmpty() && left.equals(right, ignoreCase = true)
    }

    fun isFollowerSensorId(candidate: String?): Boolean =
        candidate?.trim()?.startsWith(SENSOR_PREFIX, ignoreCase = true) == true

    fun applyAuth(connection: HttpURLConnection, secret: String) {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
            connection.setRequestProperty("Authorization", trimmed)
            return
        }
        if (trimmed.startsWith("token=", ignoreCase = true)) {
            connection.setRequestProperty("Authorization", "Bearer ${trimmed.substringAfter('=')}")
            return
        }
        connection.setRequestProperty(
            "api-secret",
            if (isSha1Hex(trimmed)) trimmed else sha1(trimmed)
        )
    }

    // Replaces Regex("^[0-9a-fA-F]{40}$") to avoid repeated ICU JNI allocation on
    // the NightscoutFollower HandlerThread.  On Samsung Android 15 with Scudo+MTE the
    // ReleaseIntArrayElements call inside MatcherNative_matchesImpl corrupts the chunk
    // header after many poll cycles, resulting in a fatal SIGABRT.
    private fun isSha1Hex(s: String): Boolean {
        if (s.length != 40) return false
        return s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it) }
}
