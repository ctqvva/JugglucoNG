package tk.glucodata

import android.Manifest
import android.annotation.SuppressLint
import android.app.UiModeManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Monitors Bluetooth audio outputs and Android Auto / Car mode so JugglucoNG can automatically
 * speak glucose readings and alarms while driving or wearing headphones, without mutating the
 * user's manual Voice settings and without Activity Recognition / GPS battery drain.
 *
 * All live state is derived from [AudioManager.getDevices] and [UiModeManager], so a process
 * restart mid-drive recovers immediately and leaving the car can never get stuck on.
 */
object VoiceAudioTrigger {
    private const val LOG_ID = "VoiceAudioTrigger"
    private const val PREFS = "tk.glucodata_preferences"

    private const val KEY_ENABLED = "voice_bt_audio_enabled"
    private const val KEY_SCOPE = "voice_bt_audio_scope"
    private const val KEY_CAR_MODE = "voice_bt_audio_car_mode"
    private const val KEY_DEVICES = "voice_bt_audio_devices"
    private const val KEY_INTERVAL_SECONDS = "voice_bt_audio_interval_sec"
    private const val KEY_SPEAK_ALARMS = "voice_bt_audio_speak_alarms"
    private const val KEY_ANNOUNCE_CONNECT = "voice_bt_audio_announce_connect"
    private const val KEY_ANNOUNCE_CONNECT_DELAY_SECONDS = "voice_bt_audio_announce_delay_sec"
    private const val KEY_MEDIA_STREAM = "voice_bt_audio_media_stream"

    /** AndroidX CarConnection content provider & update broadcast for Android Auto (Android 12+). */
    internal const val CAR_CONNECTION_AUTHORITY = "androidx.car.app.connection"
    internal const val CAR_CONNECTION_COLUMN = "CarConnectionState"
    internal const val ACTION_CAR_CONNECTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
    internal const val CONNECTION_TYPE_NOT_CONNECTED = 0
    internal const val CONNECTION_TYPE_NATIVE = 1
    internal const val CONNECTION_TYPE_PROJECTION = 2
    private val CAR_CONNECTION_URI: Uri = Uri.Builder()
        .scheme("content")
        .authority(CAR_CONNECTION_AUTHORITY)
        .build()

    data class Snapshot(
        val config: VoiceAudioPolicy.Config = VoiceAudioPolicy.Config(),
        val connectedDevices: List<VoiceAudioPolicy.ConnectedDevice> = emptyList(),
        val inCarMode: Boolean = false,
        val activeMatch: VoiceAudioPolicy.ActiveMatch = VoiceAudioPolicy.ActiveMatch.NONE,
        val sessionMuted: Boolean = false,
    ) {
        val autoActive: Boolean get() = config.enabled && activeMatch.matched && !sessionMuted
    }

    data class SelectableDevice(
        val id: String,
        val address: String?,
        val name: String,
        val isCar: Boolean,
        val isConnected: Boolean,
        val isPaired: Boolean,
        val isSelected: Boolean,
        val alternateAddresses: List<String> = emptyList(),
    )

    data class DeviceListResult(
        val connected: List<SelectableDevice>,
        val paired: List<SelectableDevice>,
        val savedUnavailable: List<SelectableDevice>,
        val hasBluetoothPermission: Boolean,
        val bluetoothEnabled: Boolean,
        val hiddenNonAudioPairedCount: Int,
    )

    internal enum class CarProjectionStatus {
        UNKNOWN,
        CONNECTED,
        DISCONNECTED,
    }

    internal sealed class CarProjectionQueryResult {
        data class Definitive(val state: Int) : CarProjectionQueryResult()
        data class Failure(val reason: String, val retryable: Boolean) : CarProjectionQueryResult()
    }

    private val lock = Any()
    private val refreshLock = Any()
    private val carProjectionLock = ReentrantLock()
    private val carProjectionIdleCondition = carProjectionLock.newCondition()
    private var carProjectionRefreshInFlight = false
    private var carProjectionRefreshPending = false
    private var carProjectionQueryGeneration = 0L
    private var carProjectionRetryCount = 0
    private var carProjectionRetryTask: ScheduledFuture<*>? = null
    private val carProjectionThreadSeq = AtomicInteger(0)
    // Bounded 0..2 daemon pool so normal single-flight uses 1 thread (0 when idle), while a single
    // hung Binder provider that ignores CancellationSignal cannot permanently jam future queries.
    private val carProjectionExecutor: ExecutorService = ThreadPoolExecutor(
        0,
        2,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { runnable ->
        Thread(runnable, "VoiceCarProjection-${carProjectionThreadSeq.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private val stateFlow = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = stateFlow.asStateFlow()

    private const val CAR_PROJECTION_RECHECK_MS = 30_000L
    private const val CAR_PROJECTION_QUERY_TIMEOUT_MS = 5_000L
    private const val DEFAULT_CAR_PROJECTION_GRACE_MS = 90_000L
    private val DEFAULT_CAR_PROJECTION_RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L)
    @Volatile
    internal var carProjectionQueryTimeoutMs: Long = CAR_PROJECTION_QUERY_TIMEOUT_MS
    @Volatile
    internal var carProjectionGraceMs: Long = DEFAULT_CAR_PROJECTION_GRACE_MS
    @Volatile
    internal var carProjectionRetryDelaysMs: LongArray = DEFAULT_CAR_PROJECTION_RETRY_DELAYS_MS

    private var appContext: Context? = null
    private var monitoringRegistered = false
    private var uiMonitorCount = 0
    private var audioCallback: AudioDeviceCallback? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var carModeReceiver: BroadcastReceiver? = null
    private var carProjectionReceiver: BroadcastReceiver? = null
    private var pendingConnectTask: ScheduledFuture<*>? = null

    private var lastMatch: VoiceAudioPolicy.ActiveMatch = VoiceAudioPolicy.ActiveMatch.NONE
    private var lastPausedMatch: VoiceAudioPolicy.ActiveMatch? = null
    internal var lastPausedDisconnectedAtMs: Long = 0L
    private var connectGeneration: Long = 0L
    @Volatile
    private var sessionMuted: Boolean = false
    @Volatile
    private var lastDisconnectedAtMs: Long = 0L
    @Volatile
    private var lastSpokenAtMs: Long = 0L
    @Volatile
    internal var carProjectionStatus: CarProjectionStatus = CarProjectionStatus.UNKNOWN
    @Volatile
    private var carProjectionState: Int = CONNECTION_TYPE_NOT_CONNECTED
    @Volatile
    internal var carProjectionConfirmedAtMs: Long = 0L
    @Volatile
    private var lastCarProjectionQuerySucceeded: Boolean = false
    @Volatile
    internal var lastCarProjectionCheckMs: Long = 0L

    /** Cached paired Bluetooth metadata so reading/alarm paths never perform blocking BT IPC. */
    @Volatile
    private var cachedBondedMetas: List<BondedMeta> = emptyList()
    @Volatile
    private var cachedBluetoothEnabled: Boolean = false
    @Volatile
    private var lastDiagnosticSignature: String? = null
    @Volatile
    private var lastCarProjectionOutcomeKey: String? = null

    /** Independent A2DP and HFP connected-device caches populated via profile proxies and broadcasts. */
    private val a2dpConnectedDevices = LinkedHashMap<String, VoiceAudioPolicy.ConnectedDevice>()
    private val hfpConnectedDevices = LinkedHashMap<String, VoiceAudioPolicy.ConnectedDevice>()
    private var a2dpProxy: BluetoothProfile? = null
    private var hfpProxy: BluetoothProfile? = null
    private var a2dpProxyRequested = false
    private var hfpProxyRequested = false

    private fun isSupported(): Boolean = !Applic.isWearable && !Applic.DontTalk

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun initialize(context: Context) {
        if (!isSupported()) return
        val app = context.applicationContext
        synchronized(lock) {
            appContext = app
        }
        val config = loadConfig(app)
        Log.i(
            LOG_ID,
            "init ${formatConfigSummary(config)} btPerm=${hasBluetoothConnectPermission(app)} uiMonitors=${if (hasUiMonitors()) 1 else 0}"
        )
        if (!config.enabled && !hasUiMonitors()) {
            synchronized(lock) {
                stateFlow.value = Snapshot(config = config)
            }
            return
        }
        updateMonitoringRegistration(app, shouldMonitor = true)
        refreshState(app, reason = "init")
    }

    /** Keeps live device discovery active while the Voice screen or device picker is open. */
    fun acquireUiMonitoring(context: Context) {
        if (!isSupported()) return
        val app = context.applicationContext
        val count: Int
        val alreadyRegistered: Boolean
        synchronized(lock) {
            appContext = app
            uiMonitorCount++
            count = uiMonitorCount
            alreadyRegistered = monitoringRegistered
        }
        val config = loadConfig(app)
        Log.i(
            LOG_ID,
            "uiAcquire count=$count registered=$alreadyRegistered ${formatConfigSummary(config)} btPerm=${hasBluetoothConnectPermission(app)}"
        )
        updateMonitoringRegistration(app, shouldMonitor = true)
        if (alreadyRegistered) {
            // Re-verify Android Auto projection, BT profile proxies, and empty bonded metadata
            // when opening Voice settings so a missed broadcast or startup race recovers cleanly.
            if (config.includeCarMode) {
                refreshCarProjectionAsync(app)
            }
            if (hasBluetoothConnectPermission(app)) {
                acquireOrReconcileBluetoothProfileProxies(app)
                if (cachedBondedMetas.isEmpty()) {
                    refreshBondedCacheAsync(app)
                }
            }
        }
        refreshState(app, reason = "ui_acquire")
    }

    fun releaseUiMonitoring(context: Context) {
        if (!isSupported()) return
        val app = context.applicationContext
        val remaining: Int
        synchronized(lock) {
            uiMonitorCount = (uiMonitorCount - 1).coerceAtLeast(0)
            remaining = uiMonitorCount
        }
        val enabled = isEnabled(app)
        Log.i(LOG_ID, "uiRelease remaining=$remaining enabled=$enabled")
        updateMonitoringRegistration(app, shouldMonitor = enabled || remaining > 0)
    }

    private fun hasUiMonitors(): Boolean = synchronized(lock) { uiMonitorCount > 0 }

    // -------------------------------------------------------------------------
    // Config persistence
    // -------------------------------------------------------------------------

    @JvmStatic
    fun isEnabled(context: Context?): Boolean {
        if (!isSupported() || context == null) return false
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun loadConfig(context: Context): VoiceAudioPolicy.Config {
        val p = prefs(context)
        return VoiceAudioPolicy.Config(
            enabled = p.getBoolean(KEY_ENABLED, false),
            scope = VoiceAudioPolicy.Scope.fromPref(p.getString(KEY_SCOPE, null)),
            includeCarMode = p.getBoolean(KEY_CAR_MODE, true),
            savedDevices = decodeSavedDevices(p.getString(KEY_DEVICES, null)),
            intervalSeconds = p.getInt(KEY_INTERVAL_SECONDS, 0).coerceAtLeast(0),
            speakAlarms = p.getBoolean(KEY_SPEAK_ALARMS, true),
            announceOnConnect = p.getBoolean(KEY_ANNOUNCE_CONNECT, true),
            connectAnnounceDelaySeconds = VoiceAudioPolicy.normalizeConnectDelaySeconds(
                p.getInt(KEY_ANNOUNCE_CONNECT_DELAY_SECONDS, VoiceAudioPolicy.DEFAULT_CONNECT_ANNOUNCE_DELAY_SECONDS)
            ),
            useMediaStream = p.getBoolean(KEY_MEDIA_STREAM, true),
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (!isSupported()) return
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            VoiceReadings.markUsed(app)
            refreshCarProjectionAsync(app)
        } else {
            synchronized(lock) { connectGeneration++ }
            cancelPendingConnectAnnouncement()
        }
        updateMonitoringRegistration(app, shouldMonitor = enabled || hasUiMonitors())
        refreshState(app, reason = "set_enabled")
        VoiceReadings.changed()
    }

    fun setScope(context: Context, scope: VoiceAudioPolicy.Scope) {
        if (!isSupported()) return
        val app = context.applicationContext
        prefs(app).edit().putString(KEY_SCOPE, scope.prefValue).apply()
        refreshState(app, reason = "set_scope")
        VoiceReadings.changed()
    }

    fun setIncludeCarMode(context: Context, include: Boolean) {
        if (!isSupported()) return
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_CAR_MODE, include).apply()
        if (include && (isEnabled(app) || hasUiMonitors())) refreshCarProjectionAsync(app)
        refreshState(app, reason = "set_include_car")
        VoiceReadings.changed()
    }

    fun setIntervalSeconds(context: Context, seconds: Int) {
        if (!isSupported()) return
        val app = context.applicationContext
        prefs(app).edit().putInt(KEY_INTERVAL_SECONDS, seconds.coerceAtLeast(0)).apply()
        // Allow the newly chosen interval to take effect cleanly on the next reading.
        if (isAutoReadingSpeechActive(app)) {
            Talker.nexttime = 0L
        }
        refreshState(app)
        VoiceReadings.changed()
    }

    fun setSpeakAlarms(context: Context, speakAlarms: Boolean) {
        if (!isSupported()) return
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_SPEAK_ALARMS, speakAlarms).apply()
        refreshState(app)
    }

    fun setAnnounceOnConnect(context: Context, announce: Boolean) {
        if (!isSupported()) return
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_ANNOUNCE_CONNECT, announce).apply()
        if (!announce) {
            synchronized(lock) { connectGeneration++ }
            cancelPendingConnectAnnouncement()
        }
        refreshState(app)
    }

    fun setAnnounceConnectDelaySeconds(context: Context, seconds: Int) {
        if (!isSupported()) return
        val app = context.applicationContext
        val normalized = VoiceAudioPolicy.normalizeConnectDelaySeconds(seconds)
        prefs(app).edit().putInt(KEY_ANNOUNCE_CONNECT_DELAY_SECONDS, normalized).apply()
        refreshState(app)
    }

    fun setUseMediaStream(context: Context, useMedia: Boolean) {
        if (!isSupported()) return
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_MEDIA_STREAM, useMedia).apply()
        refreshState(app)
    }

    fun toggleSavedDevice(context: Context, device: VoiceAudioPolicy.SavedDevice) {
        if (!isSupported()) return
        val app = context.applicationContext
        val current = loadConfig(app).savedDevices.toMutableList()
        val existingIdx = current.indexOfFirst { sameSavedIdentity(it, device) }
        if (existingIdx >= 0) {
            current.removeAt(existingIdx)
        } else {
            val normalizedId = device.id.ifBlank { VoiceAudioPolicy.deviceId(device.address, device.name) }
            if (normalizedId.isNotEmpty()) {
                current.add(
                    device.copy(
                        id = normalizedId,
                        address = VoiceAudioPolicy.normalizeAddress(device.address),
                        name = device.name.trim(),
                    )
                )
            }
        }
        prefs(app).edit().putString(KEY_DEVICES, encodeSavedDevices(current)).apply()
        refreshState(app, reason = "toggle_device")
        VoiceReadings.changed()
    }

    fun removeSavedDevice(context: Context, id: String) {
        if (!isSupported()) return
        val app = context.applicationContext
        val current = loadConfig(app).savedDevices.filterNot { it.id == id }
        prefs(app).edit().putString(KEY_DEVICES, encodeSavedDevices(current)).apply()
        refreshState(app, reason = "remove_device")
        VoiceReadings.changed()
    }

    // -------------------------------------------------------------------------
    // Live decision queries (called from Talker, SuperGattCallback, Notify, VoiceReadings)
    // -------------------------------------------------------------------------

    @JvmStatic
    fun isMatchedDeviceConnected(context: Context?): Boolean {
        if (!isSupported() || context == null || !isEnabled(context)) return false
        return synchronized(lock) { lastMatch.matched }
    }

    @JvmStatic
    fun isSessionMuted(): Boolean = sessionMuted

    /** True when the user paused voice for the currently connected car/Bluetooth session. */
    @JvmStatic
    fun isSessionPaused(context: Context?): Boolean {
        if (!isSupported() || context == null || !isEnabled(context)) return false
        return synchronized(lock) { lastMatch.matched && sessionMuted }
    }

    @JvmStatic
    fun setSessionMuted(context: Context, muted: Boolean) {
        if (!isSupported()) return
        val app = context.applicationContext
        val matched: Boolean
        synchronized(lock) {
            sessionMuted = muted
            if (muted) connectGeneration++
            matched = lastMatch.matched
            val prev = stateFlow.value
            stateFlow.value = prev.copy(sessionMuted = muted)
        }
        if (muted) {
            cancelPendingConnectAnnouncement()
            if (!Talker.shouldtalk()) {
                SuperGattCallback.endtalk()
            }
        } else if (matched) {
            Talker.nexttime = 0L
            if (SuperGattCallback.talker == null) {
                SuperGattCallback.newtalker(app)
            }
        }
        VoiceReadings.changed()
    }

    /** Single source of truth for effective voice behavior, routed through [VoiceAudioPolicy.resolveEffective]. */
    @JvmStatic
    @JvmOverloads
    fun resolveEffectiveDecision(
        context: Context?,
        manualSpeakGlucose: Boolean = SuperGattCallback.dotalk,
        defaultIntervalSeconds: Int = Talker.getSeparationSeconds().coerceAtLeast(1),
    ): VoiceAudioPolicy.EffectiveDecision {
        val withinSchedule = context == null || SpeakSchedule.isWithinSchedule(context)
        val manualSpeakAlarms = Natives.speakalarms()
        if (!isSupported() || context == null) {
            return VoiceAudioPolicy.resolveEffective(
                manualSpeakGlucose = manualSpeakGlucose,
                withinSchedule = withinSchedule,
                manualSpeakAlarms = manualSpeakAlarms,
                defaultIntervalSeconds = defaultIntervalSeconds,
                config = VoiceAudioPolicy.Config(enabled = false),
                activeMatch = VoiceAudioPolicy.ActiveMatch.NONE,
                sessionMuted = false,
            )
        }
        val config = loadConfig(context)
        val (match, muted) = synchronized(lock) { lastMatch to sessionMuted }
        return VoiceAudioPolicy.resolveEffective(
            manualSpeakGlucose = manualSpeakGlucose,
            withinSchedule = withinSchedule,
            manualSpeakAlarms = manualSpeakAlarms,
            defaultIntervalSeconds = defaultIntervalSeconds,
            config = config,
            activeMatch = match,
            sessionMuted = muted,
        )
    }

    /** True when Bluetooth/car audio is actively triggering periodic glucose speech right now. */
    @JvmStatic
    fun isAutoReadingSpeechActive(context: Context?): Boolean {
        if (!isSupported() || context == null) return false
        val decision = resolveEffectiveDecision(context, manualSpeakGlucose = false)
        return decision.autoActive && decision.shouldSpeakReading
    }

    /**
     * Reconciles live AudioManager / Car mode state on the reading path so speech can never
     * stay stuck on after leaving a car or miss a connection after a process restart.
     */
    @JvmStatic
    fun refreshAndCheckReadingSpeech(context: Context?): Boolean {
        if (!isSupported() || context == null || !isEnabled(context)) return false
        maybeScheduleStaleCarProjectionRefresh(context.applicationContext)
        refreshState(context, reason = "reading_path")
        return isAutoReadingSpeechActive(context)
    }

    /** True when Bluetooth/car audio is actively enabling spoken alarms right now. */
    @JvmStatic
    fun isAutoAlarmSpeechActive(context: Context?): Boolean {
        if (!isSupported() || context == null) return false
        val decision = resolveEffectiveDecision(context, manualSpeakGlucose = false)
        return decision.autoActive && loadConfig(context).speakAlarms
    }

    /** True when glucose alarms should be spoken (either manually or via active car/BT audio). */
    @JvmStatic
    fun isAlarmSpeechEnabled(context: Context?): Boolean {
        if (Applic.DontTalk) return false
        if (isSupported() && context != null && isEnabled(context)) {
            maybeScheduleStaleCarProjectionRefresh(context.applicationContext)
            refreshState(context, reason = "alarm_path")
        }
        return resolveEffectiveDecision(context).shouldSpeakAlarm
    }

    /** True when the TTS engine must stay alive for Bluetooth/car audio speech. */
    @JvmStatic
    fun isSpeakingActive(context: Context?): Boolean =
        isAutoReadingSpeechActive(context) || isAutoAlarmSpeechActive(context)

    /** True when active Bluetooth/car audio speech should route over the Media stream. */
    @JvmStatic
    fun useMediaStreamNow(context: Context?): Boolean {
        if (!isSupported() || context == null) return false
        return resolveEffectiveDecision(context).useMediaStream
    }

    @JvmStatic
    fun effectiveIntervalSeconds(context: Context?, defaultSeconds: Int): Int {
        return resolveEffectiveDecision(context, defaultIntervalSeconds = defaultSeconds).effectiveIntervalSeconds
    }

    @JvmStatic
    fun effectiveIntervalMs(context: Context?, defaultMs: Long): Long {
        val defaultSeconds = (defaultMs / 1000L).toInt().coerceAtLeast(1)
        return effectiveIntervalSeconds(context, defaultSeconds) * 1000L
    }

    fun currentMatch(context: Context?): VoiceAudioPolicy.ActiveMatch {
        if (!isSupported() || context == null || !isEnabled(context)) {
            return VoiceAudioPolicy.ActiveMatch.NONE
        }
        return synchronized(lock) { lastMatch }
    }

    @JvmStatic
    fun recordSpokenNow(nowMs: Long) {
        lastSpokenAtMs = nowMs
    }

    @JvmStatic
    fun wasSpokenRecently(nowMs: Long): Boolean {
        val last = lastSpokenAtMs
        return last > 0L && nowMs - last < VoiceAudioPolicy.CONNECT_ANNOUNCE_COOLDOWN_MS
    }

    // -------------------------------------------------------------------------
    // Live system state reconciliation
    // -------------------------------------------------------------------------

    @JvmOverloads
    fun refreshState(context: Context, reason: String = "refresh") {
        if (!isSupported()) return
        val app = context.applicationContext
        val now: Long
        val wasMatched: Boolean
        val distinctSessionSwitch: Boolean
        val matchChanged: Boolean
        val scheduleAnnounce: Boolean
        val currentMuted: Boolean
        val generation: Long
        val newMatch: VoiceAudioPolicy.ActiveMatch
        val config: VoiceAudioPolicy.Config
        val queryResult: AudioQueryResult
        val carModeResult: CarModeResult

        // Serialize query + commit so a slower background reading thread can never restore
        // "connected" after a newer disconnect has already been processed on the main thread.
        synchronized(refreshLock) {
            config = loadConfig(app)
            queryResult = queryConnectedAudioDevicesWithDiagnostics(app)
            val connected = queryResult.devices
            carModeResult = queryInCarModeDetailed(app)
            val inCarMode = carModeResult.inCarMode
            val previousSnapshotMatch = synchronized(lock) { lastMatch }
            newMatch = VoiceAudioPolicy.evaluateMatch(config, connected, inCarMode, previousMatch = previousSnapshotMatch)

            now = System.currentTimeMillis()
            synchronized(lock) {
                val previousMatch = lastMatch
                wasMatched = previousMatch.matched
                distinctSessionSwitch = VoiceAudioPolicy.isDistinctSessionSwitch(previousMatch, newMatch)
                matchChanged = previousMatch != newMatch
                lastMatch = newMatch

                if (!wasMatched && newMatch.matched) {
                    val keepPaused = VoiceAudioPolicy.shouldPreservePauseOnReconnect(
                        previousPausedMatch = lastPausedMatch,
                        reconnectedMatch = newMatch,
                        nowMs = now,
                        pausedDisconnectedAtMs = lastPausedDisconnectedAtMs,
                    )
                    sessionMuted = keepPaused
                    lastPausedMatch = null
                    lastPausedDisconnectedAtMs = 0L
                    connectGeneration++
                } else if (distinctSessionSwitch) {
                    // Switching to a genuinely different matched device starts unmuted.
                    sessionMuted = false
                    lastPausedMatch = null
                    lastPausedDisconnectedAtMs = 0L
                    connectGeneration++
                } else if (wasMatched && !newMatch.matched) {
                    if (sessionMuted) {
                        lastPausedMatch = previousMatch
                        lastPausedDisconnectedAtMs = now
                    } else {
                        lastPausedMatch = null
                        lastPausedDisconnectedAtMs = 0L
                    }
                    sessionMuted = false
                    lastDisconnectedAtMs = now
                    connectGeneration++
                }
                generation = connectGeneration
                currentMuted = sessionMuted
                scheduleAnnounce = VoiceAudioPolicy.shouldScheduleConnectAnnouncement(
                    wasMatched = wasMatched && !distinctSessionSwitch,
                    nowMatched = newMatch.matched,
                    config = config,
                    sessionMuted = currentMuted,
                    nowMs = now,
                    lastDisconnectedAtMs = if (distinctSessionSwitch) 0L else lastDisconnectedAtMs,
                    lastSpokenAtMs = lastSpokenAtMs,
                )
                stateFlow.value = Snapshot(
                    config = config,
                    connectedDevices = connected,
                    inCarMode = inCarMode,
                    activeMatch = newMatch,
                    sessionMuted = currentMuted,
                )
            }
        }

        logDiagnosticSnapshotIfChanged(
            app = app,
            reason = reason,
            config = config,
            query = queryResult,
            carMode = carModeResult,
            match = newMatch,
            muted = currentMuted,
        )

        if ((!wasMatched && newMatch.matched) || distinctSessionSwitch) {
            Log.i(
                LOG_ID,
                "activated: key=${VoiceAudioPolicy.sanitizeTriggerKey(newMatch.triggerKey)} label=${newMatch.displayLabel} car=${newMatch.viaCarMode} switch=$distinctSessionSwitch muted=$currentMuted announce=$scheduleAnnounce"
            )
            cancelPendingConnectAnnouncement()
            if (!currentMuted) {
                Talker.nexttime = 0L
                if (SuperGattCallback.talker == null) {
                    SuperGattCallback.newtalker(app)
                }
                if (scheduleAnnounce) {
                    scheduleConnectAnnouncement(app, generation, isRetry = false)
                }
            }
            VoiceReadings.changed()
        } else if (wasMatched && !newMatch.matched) {
            Log.i(LOG_ID, "deactivated: reason=$reason")
            cancelPendingConnectAnnouncement()
            Talker.clampNextTimeToDefault(now)
            if (!Talker.shouldtalk()) {
                SuperGattCallback.endtalk()
            }
            VoiceReadings.changed()
        } else if (matchChanged) {
            VoiceReadings.changed()
        }
    }

    private fun isConnectGenerationActive(app: Context, expectedGeneration: Long): Boolean {
        val config = loadConfig(app)
        if (!config.enabled || !config.announceOnConnect) return false
        return synchronized(lock) {
            connectGeneration == expectedGeneration && lastMatch.matched && !sessionMuted
        }
    }

    private fun isLiveConnectionStillMatched(app: Context): Boolean {
        val config = loadConfig(app)
        if (!config.enabled || !config.announceOnConnect) return false
        val connected = queryConnectedAudioDevices(app)
        val inCarMode = queryInCarMode(app)
        return VoiceAudioPolicy.evaluateMatch(config, connected, inCarMode).matched
    }

    internal fun connectAnnouncementDelayMs(app: Context, isRetry: Boolean): Long =
        if (isRetry) {
            VoiceAudioPolicy.CONNECT_TTS_RETRY_DELAY_MS
        } else {
            loadConfig(app).connectAnnounceDelaySeconds * 1_000L
        }

    private fun scheduleConnectAnnouncement(app: Context, expectedGeneration: Long, isRetry: Boolean) {
        cancelPendingConnectAnnouncement()
        val delayMs = connectAnnouncementDelayMs(app, isRetry)
        try {
            val future = Applic.scheduler.schedule({
                runConnectAnnouncement(app, expectedGeneration, isRetry)
            }, delayMs, TimeUnit.MILLISECONDS)
            synchronized(lock) {
                if (connectGeneration == expectedGeneration && lastMatch.matched && !sessionMuted) {
                    pendingConnectTask = future
                } else {
                    future.cancel(false)
                }
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "scheduleConnectAnnouncement", t)
        }
    }

    private fun runConnectAnnouncement(app: Context, expectedGeneration: Long, isRetry: Boolean) {
        if (!isSupported()) return
        if (!isConnectGenerationActive(app, expectedGeneration)) return
        val now = System.currentTimeMillis()
        if (wasSpokenRecently(now)) return

        val current = try {
            CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout)
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "resolveCurrent for connect announcement", t)
            null
        } ?: return

        val postResolveNow = System.currentTimeMillis()
        if (!VoiceAudioPolicy.isReadingFreshForConnectAnnouncement(
                readingTimeMs = current.timeMillis,
                nowMs = postResolveNow,
                maxAgeMs = Notify.glucosetimeout,
            )
        ) {
            Log.i(LOG_ID, "skipping connect announcement: resolved reading is stale")
            return
        }

        // Re-verify session and live connection after blocking history resolution so a pause,
        // disable, or disconnect during resolveCurrent can never speak on phone speakers.
        if (!isConnectGenerationActive(app, expectedGeneration) || !isLiveConnectionStillMatched(app)) {
            return
        }
        if (wasSpokenRecently(postResolveNow)) return

        val talker = SuperGattCallback.talker
        if (talker == null) {
            if (!isRetry && isConnectGenerationActive(app, expectedGeneration)) {
                SuperGattCallback.newtalker(app)
                scheduleConnectAnnouncement(app, expectedGeneration, isRetry = true)
            }
            return
        }
        if (!talker.engineReady) {
            if (!isRetry && isConnectGenerationActive(app, expectedGeneration)) {
                scheduleConnectAnnouncement(app, expectedGeneration, isRetry = true)
            }
            return
        }

        val text = GlucoseSpeech.format(app, current)
        val useMedia = useMediaStreamNow(app)
        val chime = Talker.getChimeBeforeGlucose(app)
        if (!isConnectGenerationActive(app, expectedGeneration)) return
        talker.speakConnectAnnouncement(text, useMedia, chime, current.timeMillis)
    }

    private fun cancelPendingConnectAnnouncement() {
        val task: ScheduledFuture<*>?
        synchronized(lock) {
            task = pendingConnectTask
            pendingConnectTask = null
        }
        task?.cancel(false)
    }

    // -------------------------------------------------------------------------
    // System listeners (AudioDeviceCallback + BT profile & Car mode broadcasts)
    // -------------------------------------------------------------------------

    private fun updateMonitoringRegistration(app: Context, shouldMonitor: Boolean) {
        val registerNow: Boolean
        val unregisterNow: Boolean
        val oldCallback: AudioDeviceCallback?
        val oldBtReceiver: BroadcastReceiver?
        val oldCarModeReceiver: BroadcastReceiver?
        val oldCarProjectionReceiver: BroadcastReceiver?

        synchronized(lock) {
            if (shouldMonitor && !monitoringRegistered) {
                monitoringRegistered = true
                registerNow = true
                unregisterNow = false
                oldCallback = null
                oldBtReceiver = null
                oldCarModeReceiver = null
                oldCarProjectionReceiver = null
            } else if (!shouldMonitor && monitoringRegistered) {
                monitoringRegistered = false
                registerNow = false
                unregisterNow = true
                oldCallback = audioCallback
                oldBtReceiver = bluetoothReceiver
                oldCarModeReceiver = carModeReceiver
                oldCarProjectionReceiver = carProjectionReceiver
                audioCallback = null
                bluetoothReceiver = null
                carModeReceiver = null
                carProjectionReceiver = null
                a2dpConnectedDevices.clear()
                hfpConnectedDevices.clear()
                carProjectionStatus = CarProjectionStatus.UNKNOWN
                carProjectionState = CONNECTION_TYPE_NOT_CONNECTED
                carProjectionConfirmedAtMs = 0L
                lastCarProjectionQuerySucceeded = false
            } else {
                return
            }
        }

        if (registerNow) {
            val cb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    val summary = formatAudioDeviceInfos(addedDevices)
                    if (summary.isNotEmpty()) {
                        Log.i(LOG_ID, "audioCallback added=[$summary]")
                    }
                    if (loadConfig(app).includeCarMode) {
                        refreshCarProjectionAsync(app)
                    }
                    refreshState(app, reason = "audio_added")
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    val summary = formatAudioDeviceInfos(removedDevices)
                    if (summary.isNotEmpty()) {
                        Log.i(LOG_ID, "audioCallback removed=[$summary]")
                    }
                    if (loadConfig(app).includeCarMode) {
                        refreshCarProjectionAsync(app)
                    }
                    refreshState(app, reason = "audio_removed")
                }
            }
            val btRec = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    handleBluetoothBroadcast(app, intent)
                }
            }
            val carModeRec = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val action = intent.action
                    if (action == UiModeManager.ACTION_ENTER_CAR_MODE ||
                        action == UiModeManager.ACTION_EXIT_CAR_MODE
                    ) {
                        Log.i(LOG_ID, "carModeBroadcast action=$action")
                        refreshState(app, reason = "car_mode_broadcast")
                    }
                }
            }
            val carProjRec = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == ACTION_CAR_CONNECTION_UPDATED) {
                        val includeCar = loadConfig(app).includeCarMode
                        val uiActive = hasUiMonitors()
                        Log.i(LOG_ID, "carProjBroadcast includeCar=$includeCar ui=$uiActive")
                        if (includeCar || uiActive) {
                            refreshCarProjectionAsync(app)
                        }
                    }
                }
            }

            var audioCbOk = false
            var btRecOk = false
            var carModeOk = false
            var carProjOk = false
            try {
                val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
                audioCbOk = audioManager != null
            } catch (t: Throwable) {
                Log.w(LOG_ID, "registerAudioDeviceCallback failed: ${t.javaClass.simpleName}: ${t.message}")
                Log.stack(LOG_ID, "registerAudioDeviceCallback", t)
            }
            // Bluetooth connection broadcasts originate from the Bluetooth process (non-system UID),
            // so Android 14+ requires RECEIVER_EXPORTED to deliver them reliably.
            try {
                val btFilter = IntentFilter().apply {
                    addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                }
                ContextCompat.registerReceiver(app, btRec, btFilter, ContextCompat.RECEIVER_EXPORTED)
                btRecOk = true
            } catch (t: Throwable) {
                Log.w(LOG_ID, "registerBluetoothReceiver failed: ${t.javaClass.simpleName}: ${t.message}")
                Log.stack(LOG_ID, "registerBluetoothReceiver", t)
            }
            // UiModeManager car mode broadcasts originate from system_server.
            try {
                val carModeFilter = IntentFilter().apply {
                    addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
                    addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
                }
                ContextCompat.registerReceiver(app, carModeRec, carModeFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
                carModeOk = true
            } catch (t: Throwable) {
                Log.w(LOG_ID, "registerCarModeReceiver failed: ${t.javaClass.simpleName}: ${t.message}")
                Log.stack(LOG_ID, "registerCarModeReceiver", t)
            }
            // Android Auto projection updates originate from the Android Auto host app.
            try {
                val carProjFilter = IntentFilter(ACTION_CAR_CONNECTION_UPDATED)
                ContextCompat.registerReceiver(app, carProjRec, carProjFilter, ContextCompat.RECEIVER_EXPORTED)
                carProjOk = true
            } catch (t: Throwable) {
                Log.w(LOG_ID, "registerCarProjectionReceiver failed: ${t.javaClass.simpleName}: ${t.message}")
                Log.stack(LOG_ID, "registerCarProjectionReceiver", t)
            }
            synchronized(lock) {
                audioCallback = cb
                bluetoothReceiver = btRec
                carModeReceiver = carModeRec
                carProjectionReceiver = carProjRec
            }
            Log.i(LOG_ID, "monitoring registered audioCb=$audioCbOk btRec=$btRecOk carModeRec=$carModeOk carProjRec=$carProjOk")
            acquireOrReconcileBluetoothProfileProxies(app)
            refreshCarProjectionAsync(app)
            refreshBondedCacheAsync(app)
        } else if (unregisterNow) {
            Log.i(LOG_ID, "monitoring unregistered")
            cancelCarProjectionRetry()
            releaseBluetoothProfileProxies(app)
            if (oldCallback != null) {
                try {
                    val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    audioManager?.unregisterAudioDeviceCallback(oldCallback)
                } catch (t: Throwable) {
                    Log.stack(LOG_ID, "unregisterAudioDeviceCallback", t)
                }
            }
            for (oldRec in listOfNotNull(oldBtReceiver, oldCarModeReceiver, oldCarProjectionReceiver)) {
                try {
                    app.unregisterReceiver(oldRec)
                } catch (_: IllegalArgumentException) {
                    // Already unregistered.
                } catch (t: Throwable) {
                    Log.stack(LOG_ID, "unregisterReceiver", t)
                }
            }
        }
    }

    private fun handleBluetoothBroadcast(app: Context, intent: Intent) {
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    Log.i(LOG_ID, "btBroadcast adapterState=$state clearing profile/bonded cache")
                    releaseBluetoothProfileProxies(app)
                    synchronized(lock) {
                        a2dpConnectedDevices.clear()
                        hfpConnectedDevices.clear()
                    }
                    cachedBluetoothEnabled = false
                    cachedBondedMetas = emptyList()
                    refreshState(app, reason = "bt_adapter_off")
                } else if (state == BluetoothAdapter.STATE_ON) {
                    Log.i(LOG_ID, "btBroadcast adapterState=ON")
                    acquireOrReconcileBluetoothProfileProxies(app)
                    refreshBondedCacheAsync(app)
                    refreshState(app, reason = "bt_adapter_on")
                }
            }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                Log.i(LOG_ID, "btBroadcast bondState=$bondState")
                refreshBondedCacheAsync(app)
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
            -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state != BluetoothProfile.STATE_CONNECTED && state != BluetoothProfile.STATE_DISCONNECTED) {
                    return
                }
                val profile = if (intent.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                    BluetoothProfile.A2DP
                } else {
                    BluetoothProfile.HEADSET
                }
                val profileName = profileName(profile)
                val stateName = if (state == BluetoothProfile.STATE_CONNECTED) "CONNECTED" else "DISCONNECTED"
                val device = extractBluetoothDevice(intent)
                if (device != null) {
                    updateProfileCache(app, profile, device, state, profileName, stateName)
                } else {
                    Log.i(LOG_ID, "btBroadcast profile=$profileName state=$stateName device=null")
                }
                refreshState(app, reason = "bt_${profileName.lowercase(Locale.ROOT)}_${stateName.lowercase(Locale.ROOT)}")
            }
        }
    }

    private fun profileName(profile: Int): String = when (profile) {
        BluetoothProfile.A2DP -> "A2DP"
        BluetoothProfile.HEADSET -> "HFP"
        else -> "PROFILE_$profile"
    }

    private fun acquireOrReconcileBluetoothProfileProxies(app: Context) {
        if (!hasBluetoothConnectPermission(app)) return
        val adapter = getBluetoothAdapter(app) ?: return
        val btOn = try {
            adapter.isEnabled
        } catch (_: Throwable) {
            false
        }
        if (!btOn) return

        acquireOrReconcileSingleProxy(app, adapter, BluetoothProfile.A2DP)
        acquireOrReconcileSingleProxy(app, adapter, BluetoothProfile.HEADSET)
    }

    private fun acquireOrReconcileSingleProxy(app: Context, adapter: BluetoothAdapter, profile: Int) {
        val existingProxy: BluetoothProfile?
        val shouldRequest: Boolean
        synchronized(lock) {
            if (!monitoringRegistered) return
            when (profile) {
                BluetoothProfile.A2DP -> {
                    existingProxy = a2dpProxy
                    shouldRequest = existingProxy == null && !a2dpProxyRequested
                    if (shouldRequest) a2dpProxyRequested = true
                }
                BluetoothProfile.HEADSET -> {
                    existingProxy = hfpProxy
                    shouldRequest = existingProxy == null && !hfpProxyRequested
                    if (shouldRequest) hfpProxyRequested = true
                }
                else -> return
            }
        }
        if (existingProxy != null) {
            reconcileProfileProxyAsync(app, profile, existingProxy)
            return
        }
        if (!shouldRequest) return

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(connectedProfile: Int, proxy: BluetoothProfile) {
                val keep: Boolean
                synchronized(lock) {
                    keep = monitoringRegistered
                    if (keep) {
                        when (connectedProfile) {
                            BluetoothProfile.A2DP -> {
                                a2dpProxy = proxy
                                a2dpProxyRequested = false
                            }
                            BluetoothProfile.HEADSET -> {
                                hfpProxy = proxy
                                hfpProxyRequested = false
                            }
                        }
                    } else {
                        when (connectedProfile) {
                            BluetoothProfile.A2DP -> a2dpProxyRequested = false
                            BluetoothProfile.HEADSET -> hfpProxyRequested = false
                        }
                    }
                }
                if (!keep) {
                    closeProfileProxySafely(adapter, connectedProfile, proxy)
                    return
                }
                reconcileProfileProxyAsync(app, connectedProfile, proxy)
            }

            override fun onServiceDisconnected(disconnectedProfile: Int) {
                Log.i(LOG_ID, "btProxy disconnected profile=${profileName(disconnectedProfile)}")
                synchronized(lock) {
                    when (disconnectedProfile) {
                        BluetoothProfile.A2DP -> {
                            a2dpProxy = null
                            a2dpProxyRequested = false
                        }
                        BluetoothProfile.HEADSET -> {
                            hfpProxy = null
                            hfpProxyRequested = false
                        }
                    }
                }
            }
        }

        val accepted = try {
            adapter.getProfileProxy(app, listener, profile)
        } catch (t: Throwable) {
            Log.w(LOG_ID, "getProfileProxy(${profileName(profile)}) failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        if (!accepted) {
            synchronized(lock) {
                when (profile) {
                    BluetoothProfile.A2DP -> a2dpProxyRequested = false
                    BluetoothProfile.HEADSET -> hfpProxyRequested = false
                }
            }
        }
    }

    private fun releaseBluetoothProfileProxies(app: Context) {
        val oldA2dp: BluetoothProfile?
        val oldHfp: BluetoothProfile?
        synchronized(lock) {
            oldA2dp = a2dpProxy
            oldHfp = hfpProxy
            a2dpProxy = null
            hfpProxy = null
            a2dpProxyRequested = false
            hfpProxyRequested = false
        }
        val adapter = getBluetoothAdapter(app) ?: return
        if (oldA2dp != null) closeProfileProxySafely(adapter, BluetoothProfile.A2DP, oldA2dp)
        if (oldHfp != null) closeProfileProxySafely(adapter, BluetoothProfile.HEADSET, oldHfp)
    }

    private fun closeProfileProxySafely(adapter: BluetoothAdapter, profile: Int, proxy: BluetoothProfile) {
        try {
            adapter.closeProfileProxy(profile, proxy)
        } catch (t: Throwable) {
            Log.w(LOG_ID, "closeProfileProxy(${profileName(profile)}) failed: ${t.message}")
        }
    }

    private fun reconcileProfileProxyAsync(app: Context, profile: Int, proxy: BluetoothProfile) {
        try {
            Applic.scheduler.execute {
                reconcileProfileProxyNow(app, profile, proxy)
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "reconcileProfileProxyAsync", t)
        }
    }

    @SuppressLint("MissingPermission")
    internal fun reconcileProfileProxyNow(app: Context, profile: Int, proxy: BluetoothProfile) {
        if (!hasBluetoothConnectPermission(app)) return
        val devices = try {
            proxy.connectedDevices.orEmpty()
        } catch (se: SecurityException) {
            Log.w(LOG_ID, "btProxy ${profileName(profile)} getConnectedDevices denied: ${se.message}")
            return
        } catch (t: Throwable) {
            Log.w(LOG_ID, "btProxy ${profileName(profile)} getConnectedDevices failed: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        reconcileConnectedProfileDevices(app, profile, devices)
    }

    @SuppressLint("MissingPermission")
    internal fun reconcileConnectedProfileDevices(
        app: Context,
        profile: Int,
        devices: List<BluetoothDevice>,
    ) {
        val nextMap = LinkedHashMap<String, VoiceAudioPolicy.ConnectedDevice>()
        for (device in devices) {
            try {
                if (device.bondState != BluetoothDevice.BOND_BONDED) continue
                val mac = VoiceAudioPolicy.normalizeAddress(device.address)
                val name = friendlyBluetoothName(device)
                val key = VoiceAudioPolicy.deviceId(mac, name)
                if (key.isEmpty()) continue
                nextMap[key] = VoiceAudioPolicy.ConnectedDevice(
                    address = mac,
                    name = name,
                    isBluetoothAudio = true,
                    isCarAudio = isCarBluetoothClass(device.bluetoothClass),
                )
            } catch (_: Throwable) {
            }
        }
        val changed: Boolean
        val stillMonitoring: Boolean
        synchronized(lock) {
            stillMonitoring = monitoringRegistered
            if (!stillMonitoring) return
            val target = when (profile) {
                BluetoothProfile.A2DP -> a2dpConnectedDevices
                BluetoothProfile.HEADSET -> hfpConnectedDevices
                else -> return
            }
            changed = target != nextMap
            if (changed) {
                target.clear()
                target.putAll(nextMap)
            }
        }
        if (changed) {
            val summary = nextMap.values.joinToString(",") { formatIdentity(it.name, it.address) }.ifEmpty { "none" }
            Log.i(LOG_ID, "btProxy reconciled profile=${profileName(profile)} connected=[$summary]")
            refreshState(app, reason = "bt_proxy_${profileName(profile).lowercase(Locale.ROOT)}")
        }
    }

    @Suppress("DEPRECATION")
    private fun extractBluetoothDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @SuppressLint("MissingPermission")
    internal fun updateProfileCache(
        app: Context,
        profile: Int,
        device: BluetoothDevice,
        state: Int,
        profileName: String = profileName(profile),
        stateName: String = if (state == BluetoothProfile.STATE_CONNECTED) "CONNECTED" else "DISCONNECTED",
    ) {
        if (!hasBluetoothConnectPermission(app)) {
            Log.i(LOG_ID, "btBroadcast profile=$profileName state=$stateName skipped=no_bt_permission")
            return
        }
        try {
            // Defense-in-depth for the exported Bluetooth receiver: only cache bonded devices.
            val bondState = device.bondState
            val mac = VoiceAudioPolicy.normalizeAddress(device.address)
            val name = friendlyBluetoothName(device)
            val isCar = isCarBluetoothClass(device.bluetoothClass)
            if (state == BluetoothProfile.STATE_CONNECTED && bondState != BluetoothDevice.BOND_BONDED) {
                Log.i(
                    LOG_ID,
                    "btBroadcast profile=$profileName state=$stateName dev=${formatIdentity(name, mac)} skipped=not_bonded($bondState)"
                )
                return
            }
            val key = VoiceAudioPolicy.deviceId(mac, name)
            Log.i(
                LOG_ID,
                "btBroadcast profile=$profileName state=$stateName dev=${formatIdentity(name, mac)} isCar=$isCar"
            )
            if (key.isEmpty()) return
            synchronized(lock) {
                val target = when (profile) {
                    BluetoothProfile.A2DP -> a2dpConnectedDevices
                    BluetoothProfile.HEADSET -> hfpConnectedDevices
                    else -> return
                }
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        target[key] = VoiceAudioPolicy.ConnectedDevice(
                            address = mac,
                            name = name,
                            isBluetoothAudio = true,
                            isCarAudio = isCar,
                        )
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        target.remove(key)
                    }
                }
            }
        } catch (t: SecurityException) {
            Log.w(LOG_ID, "btBroadcast profile=$profileName state=$stateName securityException=${t.message}")
        } catch (t: Throwable) {
            Log.w(LOG_ID, "updateProfileCache failed: ${t.javaClass.simpleName}: ${t.message}")
            Log.stack(LOG_ID, "updateProfileCache", t)
        }
    }

    // -------------------------------------------------------------------------
    // Device discovery (AudioManager + Bonded Bluetooth devices + Car Mode / Android Auto)
    // -------------------------------------------------------------------------

    fun hasBluetoothConnectPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /**
     * Queries the AndroidX CarConnection content provider (`content://androidx.car.app.connection`)
     * off the main thread. On Android 12+, Android Auto no longer switches `UI_MODE_TYPE_CAR`, so
     * this provider is the authoritative signal for wired and wireless Android Auto projection.
     */
    /**
     * Periodically re-verifies Android Auto projection off-thread whenever car-mode detection is
     * enabled, both while connected (so a missed disconnect broadcast can never leave speech stuck
     * on) and while disconnected (so a transient startup/update provider error recovers cleanly).
     */
    private fun maybeScheduleStaleCarProjectionRefresh(app: Context) {
        if (!loadConfig(app).includeCarMode) return
        val now = System.currentTimeMillis()
        if (now - lastCarProjectionCheckMs >= CAR_PROJECTION_RECHECK_MS) {
            lastCarProjectionCheckMs = now
            refreshCarProjectionAsync(app)
        }
    }

    internal fun refreshCarProjectionAsync(app: Context) {
        val appCtx = app.applicationContext
        val generation: Long = carProjectionLock.withLock {
            if (carProjectionRefreshInFlight) {
                carProjectionRefreshPending = true
                return
            }
            carProjectionRefreshInFlight = true
            carProjectionRefreshPending = false
            carProjectionQueryGeneration++
            carProjectionQueryGeneration
        }
        submitCarProjectionAttempt(appCtx, generation)
    }

    private fun submitCarProjectionAttempt(appCtx: Context, initialGeneration: Long) {
        try {
            carProjectionExecutor.execute {
                var currentGen = initialGeneration
                while (true) {
                    val result = try {
                        queryCarProjectionProviderAttempt(appCtx, currentGen)
                    } catch (t: Throwable) {
                        Log.stack(LOG_ID, "refreshCarProjectionAsync", t)
                        CarProjectionQueryResult.Failure("error(${t.javaClass.simpleName}:${t.message})", retryable = true)
                    }

                    val stillCurrent = carProjectionLock.withLock {
                        currentGen == carProjectionQueryGeneration
                    }
                    if (!stillCurrent) {
                        // This attempt already timed out and was superseded; ignore its stale return value.
                        return@execute
                    }
                    applyCarProjectionResult(appCtx, result)

                    val nextGen: Long? = carProjectionLock.withLock {
                        if (currentGen != carProjectionQueryGeneration) {
                            null
                        } else if (carProjectionRefreshPending) {
                            carProjectionRefreshPending = false
                            carProjectionQueryGeneration++
                            carProjectionQueryGeneration
                        } else {
                            carProjectionRefreshInFlight = false
                            carProjectionIdleCondition.signalAll()
                            null
                        }
                    }
                    if (nextGen == null) {
                        return@execute
                    }
                    currentGen = nextGen
                }
            }
        } catch (_: RejectedExecutionException) {
            carProjectionLock.withLock {
                if (initialGeneration == carProjectionQueryGeneration) {
                    carProjectionRefreshInFlight = false
                    carProjectionRefreshPending = false
                    carProjectionIdleCondition.signalAll()
                }
            }
            applyCarProjectionResult(
                appCtx,
                CarProjectionQueryResult.Failure("executor_busy", retryable = true),
            )
        } catch (t: Throwable) {
            carProjectionLock.withLock {
                if (initialGeneration == carProjectionQueryGeneration) {
                    carProjectionRefreshInFlight = false
                    carProjectionRefreshPending = false
                    carProjectionIdleCondition.signalAll()
                }
            }
            Log.stack(LOG_ID, "refreshCarProjectionAsync", t)
        }
    }

    private fun onCarProjectionAttemptTimedOut(appCtx: Context, attemptGeneration: Long, timeoutMs: Long) {
        val shouldApply: Boolean = carProjectionLock.withLock {
            if (attemptGeneration != carProjectionQueryGeneration || !carProjectionRefreshInFlight) {
                false
            } else {
                // Advance generation immediately so the hung thread's eventual return value is ignored.
                carProjectionQueryGeneration++
                true
            }
        }
        if (!shouldApply) return

        applyCarProjectionResult(
            appCtx,
            CarProjectionQueryResult.Failure("timeout(${timeoutMs}ms)", retryable = true),
        )

        val pendingGen: Long? = carProjectionLock.withLock {
            if (carProjectionRefreshPending) {
                carProjectionRefreshPending = false
                carProjectionQueryGeneration++
                carProjectionQueryGeneration
            } else {
                carProjectionRefreshInFlight = false
                carProjectionIdleCondition.signalAll()
                null
            }
        }
        if (pendingGen != null) {
            submitCarProjectionAttempt(appCtx, pendingGen)
        }
    }

    internal fun awaitCarProjectionIdleForTests(timeoutMs: Long = 5_000L): Boolean {
        var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1L))
        carProjectionLock.withLock {
            while (carProjectionRefreshInFlight || carProjectionRefreshPending) {
                if (remainingNanos <= 0L) return false
                remainingNanos = carProjectionIdleCondition.awaitNanos(remainingNanos)
            }
            return true
        }
    }

    internal fun refreshCarProjectionNow(app: Context) {
        val result = queryCarProjectionProviderDetailed(app)
        applyCarProjectionResult(app.applicationContext, result)
    }

    internal fun queryCarProjectionProvider(context: Context): Int =
        when (val result = queryCarProjectionProviderDetailed(context)) {
            is CarProjectionQueryResult.Definitive -> result.state
            is CarProjectionQueryResult.Failure -> CONNECTION_TYPE_NOT_CONNECTED
        }

    internal fun queryCarProjectionProviderDetailed(context: Context): CarProjectionQueryResult =
        queryCarProjectionProviderAttempt(context, attemptGeneration = null)

    private fun queryCarProjectionProviderAttempt(
        context: Context,
        attemptGeneration: Long?,
    ): CarProjectionQueryResult {
        val appCtx = context.applicationContext
        val cancellationSignal = CancellationSignal()
        val timeoutMs = carProjectionQueryTimeoutMs.coerceAtLeast(1L)
        val timeoutTask: ScheduledFuture<*>? = try {
            Applic.scheduler.schedule(
                {
                    try {
                        cancellationSignal.cancel()
                    } catch (_: Throwable) {
                    }
                    if (attemptGeneration != null) {
                        onCarProjectionAttemptTimedOut(appCtx, attemptGeneration, timeoutMs)
                    }
                },
                timeoutMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: Throwable) {
            null
        }
        return try {
            val cursor = appCtx.contentResolver.query(
                CAR_CONNECTION_URI,
                arrayOf(CAR_CONNECTION_COLUMN),
                null,
                null,
                null,
                cancellationSignal,
            )
            if (cursor == null) {
                CarProjectionQueryResult.Failure("null_cursor", retryable = true)
            } else {
                cursor.use { c ->
                    val idx = c.getColumnIndex(CAR_CONNECTION_COLUMN)
                    if (idx >= 0 && c.moveToFirst()) {
                        CarProjectionQueryResult.Definitive(c.getInt(idx))
                    } else {
                        CarProjectionQueryResult.Failure(
                            "missing_col_or_empty(idx=$idx,count=${c.count})",
                            retryable = true,
                        )
                    }
                }
            }
        } catch (_: OperationCanceledException) {
            CarProjectionQueryResult.Failure("timeout(${timeoutMs}ms)", retryable = true)
        } catch (se: SecurityException) {
            CarProjectionQueryResult.Failure("security_exception(${se.message})", retryable = false)
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "queryCarProjectionProvider", t)
            CarProjectionQueryResult.Failure("error(${t.javaClass.simpleName}:${t.message})", retryable = true)
        } finally {
            timeoutTask?.cancel(false)
        }
    }

    internal fun applyCarProjectionResult(
        app: Context,
        result: CarProjectionQueryResult,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        lastCarProjectionCheckMs = nowMs
        when (result) {
            is CarProjectionQueryResult.Definitive -> {
                cancelCarProjectionRetry()
                carProjectionLock.withLock {
                    carProjectionRetryCount = 0
                }
                lastCarProjectionQuerySucceeded = true
                val isConnected = result.state == CONNECTION_TYPE_PROJECTION ||
                    result.state == CONNECTION_TYPE_NATIVE
                val nextStatus = if (isConnected) CarProjectionStatus.CONNECTED else CarProjectionStatus.DISCONNECTED
                val nextState = if (isConnected) result.state else CONNECTION_TYPE_NOT_CONNECTED
                val outcomeKey = "definitive=${nextStatus.name}(${result.state})"
                if (outcomeKey != lastCarProjectionOutcomeKey) {
                    lastCarProjectionOutcomeKey = outcomeKey
                    Log.i(LOG_ID, "carProjection $outcomeKey")
                }
                val prevStatus = carProjectionStatus
                val prevState = carProjectionState
                carProjectionStatus = nextStatus
                carProjectionState = nextState
                carProjectionConfirmedAtMs = if (isConnected) nowMs else 0L
                if (nextStatus != prevStatus || nextState != prevState || (isConnected && !isMatchedDeviceConnected(app))) {
                    refreshState(app, reason = "car_projection")
                }
            }
            is CarProjectionQueryResult.Failure -> {
                lastCarProjectionQuerySucceeded = false
                val prevStatus = carProjectionStatus
                val withinGrace = prevStatus == CarProjectionStatus.CONNECTED &&
                    carProjectionConfirmedAtMs > 0L &&
                    nowMs - carProjectionConfirmedAtMs < carProjectionGraceMs
                val expired = prevStatus == CarProjectionStatus.CONNECTED && !withinGrace
                if (expired) {
                    carProjectionStatus = CarProjectionStatus.UNKNOWN
                    carProjectionState = CONNECTION_TYPE_NOT_CONNECTED
                    carProjectionConfirmedAtMs = 0L
                } else if (prevStatus != CarProjectionStatus.CONNECTED) {
                    carProjectionStatus = CarProjectionStatus.UNKNOWN
                    carProjectionState = CONNECTION_TYPE_NOT_CONNECTED
                }
                val outcomeKey = "failure=${result.reason},status=${carProjectionStatus.name},grace=$withinGrace"
                if (outcomeKey != lastCarProjectionOutcomeKey) {
                    lastCarProjectionOutcomeKey = outcomeKey
                    Log.w(LOG_ID, "carProjection $outcomeKey")
                }
                if (expired) {
                    refreshState(app, reason = "car_projection_expired")
                }
                if (result.retryable) {
                    scheduleCarProjectionRetry(app)
                }
            }
        }
    }

    private fun scheduleCarProjectionRetry(app: Context) {
        val shouldRetry: Boolean
        val delayMs: Long
        synchronized(lock) {
            shouldRetry = monitoringRegistered && (loadConfig(app).includeCarMode || hasUiMonitors())
        }
        if (!shouldRetry) return
        val delays = carProjectionRetryDelaysMs
        carProjectionLock.withLock {
            if (carProjectionRetryCount >= delays.size) return
            if (carProjectionRetryTask != null && !carProjectionRetryTask!!.isDone) return
            delayMs = delays[carProjectionRetryCount].coerceAtLeast(1L)
            carProjectionRetryCount++
            try {
                carProjectionRetryTask = Applic.scheduler.schedule(
                    {
                        carProjectionLock.withLock {
                            carProjectionRetryTask = null
                        }
                        refreshCarProjectionAsync(app)
                    },
                    delayMs,
                    TimeUnit.MILLISECONDS,
                )
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "scheduleCarProjectionRetry", t)
            }
        }
    }

    private fun cancelCarProjectionRetry() {
        val task: ScheduledFuture<*>? = carProjectionLock.withLock {
            val existing = carProjectionRetryTask
            carProjectionRetryTask = null
            existing
        }
        task?.cancel(false)
    }

    private data class CarModeResult(
        val inCarMode: Boolean,
        val projectionStatus: CarProjectionStatus,
        val projectionState: Int,
        val uiCarMode: Boolean,
    )

    private fun queryInCarModeDetailed(context: Context): CarModeResult {
        // Always read the cached Android Auto / AAOS projection state so glucose processing
        // never performs synchronous cross-process IPC against an external content provider.
        // A previously confirmed CONNECTED state remains valid across transient provider errors
        // only within carProjectionGraceMs so speech never stays stuck on indefinitely.
        val status = carProjectionStatus
        val cachedProjection = carProjectionState
        val confirmedStillValid = status == CarProjectionStatus.CONNECTED &&
            (lastCarProjectionQuerySucceeded ||
                (carProjectionConfirmedAtMs > 0L && System.currentTimeMillis() - carProjectionConfirmedAtMs < carProjectionGraceMs))
        val projectionActive = confirmedStillValid &&
            (cachedProjection == CONNECTION_TYPE_PROJECTION || cachedProjection == CONNECTION_TYPE_NATIVE)
        // Retain UiModeManager car mode as a separate signal for car docks and Android 8–11.
        val uiCarMode = try {
            val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            uiMode?.currentModeType == Configuration.UI_MODE_TYPE_CAR
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "queryInCarMode", t)
            false
        }
        return CarModeResult(
            inCarMode = projectionActive || uiCarMode,
            projectionStatus = if (status == CarProjectionStatus.CONNECTED && !confirmedStillValid) {
                CarProjectionStatus.UNKNOWN
            } else {
                status
            },
            projectionState = if (projectionActive) cachedProjection else CONNECTION_TYPE_NOT_CONNECTED,
            uiCarMode = uiCarMode,
        )
    }

    private fun queryInCarMode(context: Context): Boolean = queryInCarModeDetailed(context).inCarMode

    /**
     * Only outputs that actually carry media/notification audio qualify as standalone triggers.
     * Call-only SCO outputs (e.g. smartwatches or headsets with Media Audio turned off) never
     * receive media/notification TTS and would otherwise speak out of the phone speaker.
     */
    private fun isMediaBluetoothAudioOutputType(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            type == AudioDeviceInfo.TYPE_BLE_BROADCAST
        ) {
            return true
        }
        return false
    }

    private fun isScoBluetoothOutputType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    private fun transportForAudioOutputType(type: Int): VoiceAudioPolicy.Transport {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return VoiceAudioPolicy.Transport.CLASSIC_OR_DUAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID) {
            return VoiceAudioPolicy.Transport.LE_ONLY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        ) {
            return VoiceAudioPolicy.Transport.LE_ONLY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            type == AudioDeviceInfo.TYPE_BLE_BROADCAST
        ) {
            return VoiceAudioPolicy.Transport.LE_ONLY
        }
        return VoiceAudioPolicy.Transport.UNKNOWN
    }

    @SuppressLint("MissingPermission")
    private fun transportForBluetoothDevice(device: BluetoothDevice): VoiceAudioPolicy.Transport {
        return try {
            when (device.type) {
                BluetoothDevice.DEVICE_TYPE_LE -> VoiceAudioPolicy.Transport.LE_ONLY
                BluetoothDevice.DEVICE_TYPE_CLASSIC,
                BluetoothDevice.DEVICE_TYPE_DUAL -> VoiceAudioPolicy.Transport.CLASSIC_OR_DUAL
                else -> VoiceAudioPolicy.Transport.UNKNOWN
            }
        } catch (_: Throwable) {
            VoiceAudioPolicy.Transport.UNKNOWN
        }
    }

    private data class BondedMeta(
        val address: String,
        val name: String,
        val isCar: Boolean,
        val isAudio: Boolean,
        val isWearable: Boolean,
        val transport: VoiceAudioPolicy.Transport = VoiceAudioPolicy.Transport.UNKNOWN,
        val alternateAddresses: List<String> = emptyList(),
    )

    private fun refreshBondedCacheAsync(app: Context) {
        try {
            Applic.scheduler.execute {
                refreshBondedCacheNow(app)
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "refreshBondedCacheAsync", t)
        }
    }

    internal fun refreshBondedCacheNow(app: Context) {
        val prevEnabled = cachedBluetoothEnabled
        val prevMetas = cachedBondedMetas
        val (enabled, metas) = readBondedMetadata(app)
        if (enabled != prevEnabled || metas != prevMetas) {
            Log.i(
                LOG_ID,
                "bondedCache updated btOn=$enabled count=${metas.size} btPerm=${hasBluetoothConnectPermission(app)}"
            )
            val shouldRefresh = synchronized(lock) { monitoringRegistered }
            if (shouldRefresh) {
                refreshState(app, reason = "bonded_cache")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBondedMetadata(context: Context): Pair<Boolean, List<BondedMeta>> {
        val adapter = getBluetoothAdapter(context) ?: run {
            cachedBluetoothEnabled = false
            cachedBondedMetas = emptyList()
            return false to emptyList()
        }
        val enabled = try {
            adapter.isEnabled
        } catch (_: Throwable) {
            false
        }
        if (!hasBluetoothConnectPermission(context)) {
            cachedBluetoothEnabled = enabled
            cachedBondedMetas = emptyList()
            return enabled to emptyList()
        }
        val bonded = try {
            adapter.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            cachedBluetoothEnabled = enabled
            cachedBondedMetas = emptyList()
            return enabled to emptyList()
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "bondedDevices", t)
            cachedBluetoothEnabled = enabled
            return enabled to cachedBondedMetas
        }

        val metas = bonded.mapNotNull { device ->
            try {
                val mac = VoiceAudioPolicy.normalizeAddress(device.address) ?: return@mapNotNull null
                val name = friendlyBluetoothName(device).ifBlank { mac }
                val btClass = device.bluetoothClass
                BondedMeta(
                    address = mac,
                    name = name,
                    isCar = isCarBluetoothClass(btClass),
                    isAudio = isAudioBluetoothClass(btClass),
                    isWearable = btClass?.majorDeviceClass == BluetoothClass.Device.Major.WEARABLE,
                    transport = transportForBluetoothDevice(device),
                )
            } catch (_: Throwable) {
                null
            }
        }
        cachedBluetoothEnabled = enabled
        cachedBondedMetas = metas
        return enabled to metas
    }

    private data class AudioQueryResult(
        val devices: List<VoiceAudioPolicy.ConnectedDevice>,
        val rawExternalOutputs: List<String>,
        val enrichmentNotes: List<String>,
        val filteredNotes: List<String>,
        val rawMediaCount: Int,
        val bondedCount: Int,
        val a2dpProfileCount: Int,
        val hfpProfileCount: Int,
    )

    private fun queryConnectedAudioDevices(context: Context): List<VoiceAudioPolicy.ConnectedDevice> =
        queryConnectedAudioDevicesWithDiagnostics(context).devices

    private fun queryConnectedAudioDevicesWithDiagnostics(context: Context): AudioQueryResult {
        val bondedMetas = cachedBondedMetas
        val bondedHints = bondedMetas.map {
            VoiceAudioPolicy.BondedHint(
                address = it.address,
                name = it.name,
                isCar = it.isCar,
                transport = it.transport,
            )
        }
        val wearableMacs = bondedMetas.filter { it.isWearable }.map { it.address }.toSet()
        val (a2dpProfiles, hfpProfiles) = synchronized(lock) {
            a2dpConnectedDevices.values.toList() to hfpConnectedDevices.values.toList()
        }

        val mediaOutputs = ArrayList<AudioDeviceInfo>()
        val scoProfiles = ArrayList<VoiceAudioPolicy.ConnectedDevice>()
        val rawExternalOutputs = ArrayList<String>()
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            for (info in outputs) {
                if (isRelevantDiagnosticOutputType(info.type)) {
                    rawExternalOutputs.add(formatAudioDeviceInfo(info))
                }
                when {
                    isMediaBluetoothAudioOutputType(info.type) -> mediaOutputs.add(info)
                    isScoBluetoothOutputType(info.type) -> {
                        val rawAddr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.address else null
                        val rawName = info.productName?.toString()
                        val mac = VoiceAudioPolicy.normalizeAddress(rawAddr)
                        val name = rawName?.trim().orEmpty()
                        if (mac != null && name.isNotEmpty()) {
                            scoProfiles.add(
                                VoiceAudioPolicy.ConnectedDevice(
                                    address = mac,
                                    name = name,
                                    isBluetoothAudio = true,
                                    isCarAudio = VoiceAudioPolicy.looksLikeCarName(name),
                                )
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(LOG_ID, "queryConnectedAudioDevices failed: ${t.javaClass.simpleName}: ${t.message}")
            Log.stack(LOG_ID, "queryConnectedAudioDevices", t)
        }

        // A2DP, HFP, and SCO profile identities may enrich an active media output, but never trigger alone.
        val enrichmentProfiles = a2dpProfiles + hfpProfiles + scoProfiles
        val raw = ArrayList<VoiceAudioPolicy.ConnectedDevice>(mediaOutputs.size)
        val enrichmentNotes = ArrayList<String>(mediaOutputs.size)
        val filteredNotes = ArrayList<String>()
        for (info in mediaOutputs) {
            val rawAddr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.address else null
            val rawName = info.productName?.toString()
            val detail = VoiceAudioPolicy.enrichConnectedAudioOutputDetailed(
                rawAddress = rawAddr,
                rawName = rawName,
                profileConnected = enrichmentProfiles,
                bondedHints = bondedHints,
                looksLikeCarName = VoiceAudioPolicy::looksLikeCarName,
                transport = transportForAudioOutputType(info.type),
            )
            val enriched = detail.device
            if (enriched == null) {
                filteredNotes.add("${audioDeviceTypeName(info.type)}:empty")
            } else if (enriched.address != null && enriched.address in wearableMacs) {
                filteredNotes.add("${formatIdentity(enriched.name, enriched.address)}:wearable")
            } else {
                raw.add(enriched)
                enrichmentNotes.add(
                    "${formatIdentity(enriched.name, enriched.address)}[${detail.source},car=${enriched.isCarAudio},amb=${enriched.ambiguousName}]"
                )
            }
        }

        val deduped = VoiceAudioPolicy.deduplicateConnectedDevices(raw)
        return AudioQueryResult(
            devices = deduped,
            rawExternalOutputs = rawExternalOutputs,
            enrichmentNotes = enrichmentNotes,
            filteredNotes = filteredNotes,
            rawMediaCount = raw.size,
            bondedCount = bondedMetas.size,
            a2dpProfileCount = a2dpProfiles.size,
            hfpProfileCount = hfpProfiles.size,
        )
    }

    private fun formatIdentity(name: String?, address: String?): String {
        val cleanName = name?.trim().orEmpty().ifEmpty { "?" }
        val sanitizedMac = VoiceAudioPolicy.sanitizeAddress(address)
        return "$cleanName($sanitizedMac)"
    }

    private fun formatConfigSummary(config: VoiceAudioPolicy.Config): String {
        val saved = if (config.savedDevices.isEmpty()) {
            "none"
        } else {
            config.savedDevices.joinToString(",") { formatIdentity(it.name, it.address) }
        }
        return "enabled=${config.enabled} scope=${config.scope.prefValue} includeCar=${config.includeCarMode} saved=[$saved]"
    }

    private fun isRelevantDiagnosticOutputType(type: Int): Boolean {
        if (isMediaBluetoothAudioOutputType(type) || isScoBluetoothOutputType(type)) return true
        if (type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
            type == AudioDeviceInfo.TYPE_LINE_DIGITAL ||
            type == AudioDeviceInfo.TYPE_AUX_LINE
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && type == AudioDeviceInfo.TYPE_USB_HEADSET) {
            return true
        }
        return false
    }

    private fun audioDeviceTypeName(type: Int): String = when {
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "A2DP"
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "SCO"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && type == AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
        type == AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEV"
        type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACC"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && type == AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HS"
        type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HP"
        type == AudioDeviceInfo.TYPE_LINE_ANALOG || type == AudioDeviceInfo.TYPE_LINE_DIGITAL || type == AudioDeviceInfo.TYPE_AUX_LINE -> "LINE_OUT"
        else -> "TYPE_$type"
    }

    private fun formatAudioDeviceInfo(info: AudioDeviceInfo): String {
        val rawAddr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.address else null
        val rawName = info.productName?.toString()
        return "${audioDeviceTypeName(info.type)}:${formatIdentity(rawName, rawAddr)}"
    }

    private fun formatAudioDeviceInfos(devices: Array<out AudioDeviceInfo>): String =
        devices.filter { isRelevantDiagnosticOutputType(it.type) }
            .joinToString(",") { formatAudioDeviceInfo(it) }

    private fun buildRejectionSummary(
        config: VoiceAudioPolicy.Config,
        connected: List<VoiceAudioPolicy.ConnectedDevice>,
        match: VoiceAudioPolicy.ActiveMatch,
    ): String {
        if (!config.enabled || match.matched) return ""
        if (connected.isEmpty()) return "no_media_bt_outputs"
        if (config.scope != VoiceAudioPolicy.Scope.SELECTED_DEVICES) return ""
        if (config.savedDevices.isEmpty()) return "no_saved_devices"
        val notes = ArrayList<String>()
        for (conn in connected) {
            val reasons = config.savedDevices.map { saved ->
                val detail = VoiceAudioPolicy.explainDeviceMatch(saved, conn)
                "${formatIdentity(saved.name, saved.address)}=${detail.reason}"
            }
            notes.add("${formatIdentity(conn.name, conn.address)} vs [${reasons.joinToString(",")}]")
        }
        return notes.joinToString("; ")
    }

    private fun logDiagnosticSnapshotIfChanged(
        app: Context,
        reason: String,
        config: VoiceAudioPolicy.Config,
        query: AudioQueryResult,
        carMode: CarModeResult,
        match: VoiceAudioPolicy.ActiveMatch,
        muted: Boolean,
    ) {
        val rawStr = query.rawExternalOutputs.joinToString(",").ifEmpty { "none" }
        val enrichedStr = query.enrichmentNotes.joinToString(",").ifEmpty { "none" }
        val filteredStr = query.filteredNotes.joinToString(",")
        val dedupNote = if (query.rawMediaCount != query.devices.size) {
            " dedup=${query.rawMediaCount}->${query.devices.size}"
        } else {
            ""
        }
        val rejectStr = buildRejectionSummary(config, query.devices, match)
        val matchStr = if (match.matched) {
            "MATCH(${VoiceAudioPolicy.sanitizeTriggerKey(match.triggerKey)},car=${match.viaCarMode},muted=$muted)"
        } else {
            "NO_MATCH"
        }
        val signature = buildString {
            append(formatConfigSummary(config))
            append("|raw=").append(rawStr)
            append("|enr=").append(enrichedStr)
            append("|flt=").append(filteredStr)
            append(dedupNote)
            append("|bonded=").append(query.bondedCount)
            append("|a2dp=").append(query.a2dpProfileCount)
            append("|hfp=").append(query.hfpProfileCount)
            append("|perm=").append(hasBluetoothConnectPermission(app))
            append("|proj=").append(carMode.projectionStatus.name).append('(').append(carMode.projectionState).append(')')
            append("|uiCar=").append(carMode.uiCarMode)
            append("|").append(matchStr)
            append("|rej=").append(rejectStr)
        }
        if (signature == lastDiagnosticSignature) return
        lastDiagnosticSignature = signature

        // Split into concise <256-char lines so SensorTraceRing never truncates critical fields.
        Log.i(
            LOG_ID,
            "state[$reason] $matchStr enabled=${config.enabled} scope=${config.scope.prefValue} includeCar=${config.includeCarMode} proj=${carMode.projectionStatus.name}(${carMode.projectionState}) uiCar=${carMode.uiCarMode} bonded=${query.bondedCount} a2dp=${query.a2dpProfileCount} hfp=${query.hfpProfileCount} perm=${hasBluetoothConnectPermission(app)}"
        )
        val filterPart = if (filteredStr.isNotEmpty()) " filtered=[$filteredStr]" else ""
        Log.i(
            LOG_ID,
            "outputs[$reason] raw=[$rawStr] enriched=[$enrichedStr]$dedupNote$filterPart"
        )
        if (rejectStr.isNotEmpty()) {
            Log.i(LOG_ID, "match[$reason] rejected: $rejectStr")
        }
    }

    /** Builds the structured list of connected, paired, and saved devices for the picker sheet. */
    fun listSelectableDevices(context: Context, includeAllPaired: Boolean): DeviceListResult {
        val app = context.applicationContext
        val config = loadConfig(app)
        val hasPerm = hasBluetoothConnectPermission(app)
        val (btEnabled, bondedMetas) = readBondedMetadata(app)
        val connectedDevices = queryConnectedAudioDevices(app)

        val connectedList = connectedDevices.map { conn ->
            val saved = config.savedDevices.firstOrNull { VoiceAudioPolicy.devicesMatch(it, conn) }
            val id = saved?.id ?: VoiceAudioPolicy.deviceId(conn.address, conn.name)
            val allMacs = (listOfNotNull(conn.address) + conn.alternateAddresses).toSet()
            val primaryAddr = conn.address ?: saved?.address
            // Only keep MACs that Android itself reported simultaneously connected (plus any
            // previously saved alternates) — never pull unrelated paired MACs by name/OUI.
            val mergedAlts = (conn.alternateAddresses + saved?.alternateAddresses.orEmpty())
                .filter { it != primaryAddr }
                .distinct()
            SelectableDevice(
                id = id,
                address = primaryAddr,
                name = conn.name.ifBlank { saved?.name ?: conn.address.orEmpty() },
                isCar = conn.isCarAudio || saved?.isCar == true,
                isConnected = true,
                isPaired = bondedMetas.any { it.address in allMacs },
                isSelected = saved != null,
                alternateAddresses = mergedAlts,
            )
        }.sortedWith(compareByDescending<SelectableDevice> { it.isCar }.thenBy { it.name.lowercase(Locale.ROOT) })

        val nonConnectedBonded = bondedMetas.filter { meta ->
            connectedDevices.none { conn ->
                val connMacs = buildSet {
                    VoiceAudioPolicy.normalizeAddress(conn.address)?.let(::add)
                    conn.alternateAddresses.mapNotNull(VoiceAudioPolicy::normalizeAddress).forEach(::add)
                }
                if (connMacs.isNotEmpty()) {
                    meta.address in connMacs
                } else {
                    VoiceAudioPolicy.normalizeName(conn.name) == VoiceAudioPolicy.normalizeName(meta.name)
                }
            }
        }

        // Hide non-audio BLE companion GATT entries (DEVICE_TYPE_LE without AUDIO_VIDEO class)
        // from the default "Paired Bluetooth audio" list so dual-mode earbuds don't appear twice,
        // while keeping every distinct audio MAC separate and accessible under "Show all paired".
        val audioBonded = nonConnectedBonded.filter { meta ->
            val isExplicitlySaved = config.savedDevices.any { saved ->
                val savedMacs = (listOfNotNull(VoiceAudioPolicy.normalizeAddress(saved.address)) +
                    saved.alternateAddresses.mapNotNull(VoiceAudioPolicy::normalizeAddress)).toSet()
                meta.address in savedMacs
            }
            val isBleCompanionDuplicate = meta.transport == VoiceAudioPolicy.Transport.LE_ONLY && !meta.isAudio
            isExplicitlySaved || (!isBleCompanionDuplicate && (meta.isAudio || meta.isCar))
        }
        val hiddenCount = (nonConnectedBonded.size - audioBonded.size).coerceAtLeast(0)
        val visibleBonded = if (includeAllPaired) nonConnectedBonded else audioBonded

        val pairedList = visibleBonded.map { meta ->
            val saved = config.savedDevices.firstOrNull { s ->
                val savedMacs = (listOfNotNull(VoiceAudioPolicy.normalizeAddress(s.address)) +
                    s.alternateAddresses.mapNotNull(VoiceAudioPolicy::normalizeAddress)).toSet()
                meta.address in savedMacs ||
                    (savedMacs.isEmpty() &&
                        VoiceAudioPolicy.normalizeName(s.name) == VoiceAudioPolicy.normalizeName(meta.name))
            }
            val id = saved?.id ?: VoiceAudioPolicy.deviceId(meta.address, meta.name)
            SelectableDevice(
                id = id,
                address = meta.address,
                name = meta.name,
                isCar = meta.isCar || saved?.isCar == true || VoiceAudioPolicy.looksLikeCarName(meta.name),
                isConnected = false,
                isPaired = true,
                isSelected = saved != null,
                alternateAddresses = saved?.alternateAddresses?.filter { it != meta.address }.orEmpty(),
            )
        }.sortedWith(compareByDescending<SelectableDevice> { it.isCar }.thenBy { it.name.lowercase(Locale.ROOT) })

        val shownSavedIds = (connectedList + pairedList).filter { it.isSelected }.map { it.id }.toSet()
        val savedUnavailable = config.savedDevices
            .filter { it.id !in shownSavedIds }
            .map { saved ->
                SelectableDevice(
                    id = saved.id,
                    address = saved.address,
                    name = saved.name.ifBlank { saved.address.orEmpty() },
                    isCar = saved.isCar,
                    isConnected = false,
                    isPaired = false,
                    isSelected = true,
                    alternateAddresses = saved.alternateAddresses,
                )
            }

        return DeviceListResult(
            connected = connectedList,
            paired = pairedList,
            savedUnavailable = savedUnavailable,
            hasBluetoothPermission = hasPerm,
            bluetoothEnabled = btEnabled,
            hiddenNonAudioPairedCount = hiddenCount,
        )
    }

    private fun getBluetoothAdapter(context: Context): BluetoothAdapter? = try {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    } catch (_: Throwable) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun friendlyBluetoothName(device: BluetoothDevice): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val alias = device.alias?.trim()
            if (!alias.isNullOrEmpty()) return alias
        }
        return device.name?.trim().orEmpty()
    }

    private fun isCarBluetoothClass(btClass: BluetoothClass?): Boolean {
        if (btClass == null) return false
        return btClass.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
    }

    private fun isAudioBluetoothClass(btClass: BluetoothClass?): Boolean {
        if (btClass == null) return false
        val major = btClass.majorDeviceClass
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) return true
        // Exclude common non-audio classes (CGMs/meters, watches, keyboards, phones, computers).
        if (major == BluetoothClass.Device.Major.HEALTH ||
            major == BluetoothClass.Device.Major.WEARABLE ||
            major == BluetoothClass.Device.Major.PERIPHERAL ||
            major == BluetoothClass.Device.Major.IMAGING ||
            major == BluetoothClass.Device.Major.COMPUTER ||
            major == BluetoothClass.Device.Major.PHONE ||
            major == BluetoothClass.Device.Major.TOY
        ) {
            return false
        }
        return btClass.hasService(BluetoothClass.Service.AUDIO) ||
            btClass.hasService(BluetoothClass.Service.RENDER)
    }

    // -------------------------------------------------------------------------
    // JSON serialization for saved devices
    // -------------------------------------------------------------------------

    private fun sameSavedIdentity(a: VoiceAudioPolicy.SavedDevice, b: VoiceAudioPolicy.SavedDevice): Boolean {
        if (a.id.isNotEmpty() && a.id == b.id) return true
        val macsA = (listOfNotNull(VoiceAudioPolicy.normalizeAddress(a.address)) +
            a.alternateAddresses.mapNotNull(VoiceAudioPolicy::normalizeAddress)).toSet()
        val macsB = (listOfNotNull(VoiceAudioPolicy.normalizeAddress(b.address)) +
            b.alternateAddresses.mapNotNull(VoiceAudioPolicy::normalizeAddress)).toSet()
        if (macsA.isNotEmpty() && macsB.isNotEmpty()) return macsA.any { it in macsB }
        val nameA = VoiceAudioPolicy.normalizeName(a.name)
        val nameB = VoiceAudioPolicy.normalizeName(b.name)
        return nameA.isNotEmpty() && nameA == nameB
    }

    private fun encodeSavedDevices(devices: List<VoiceAudioPolicy.SavedDevice>): String {
        val array = JSONArray()
        for (d in devices) {
            val obj = JSONObject()
            obj.put("id", d.id)
            if (d.address != null) obj.put("address", d.address)
            obj.put("name", d.name)
            obj.put("isCar", d.isCar)
            if (d.alternateAddresses.isNotEmpty()) {
                val alts = JSONArray()
                d.alternateAddresses.forEach { alts.put(it) }
                obj.put("altAddresses", alts)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun decodeSavedDevices(raw: String?): List<VoiceAudioPolicy.SavedDevice> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<VoiceAudioPolicy.SavedDevice>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val address = VoiceAudioPolicy.normalizeAddress(obj.optString("address", "").ifBlank { null })
                val name = obj.optString("name", "").trim()
                val id = obj.optString("id", "").ifBlank { VoiceAudioPolicy.deviceId(address, name) }
                if (id.isEmpty()) continue
                val altsJson = obj.optJSONArray("altAddresses")
                val altAddresses = if (altsJson != null) {
                    (0 until altsJson.length())
                        .mapNotNull { VoiceAudioPolicy.normalizeAddress(altsJson.optString(it, null)) }
                        .filter { it != address }
                        .distinct()
                } else {
                    emptyList()
                }
                list.add(
                    VoiceAudioPolicy.SavedDevice(
                        id = id,
                        address = address,
                        name = name,
                        isCar = obj.optBoolean("isCar", false),
                        alternateAddresses = altAddresses,
                    )
                )
            }
            list
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "decodeSavedDevices", t)
            emptyList()
        }
    }

    internal fun resetForTests() {
        cancelPendingConnectAnnouncement()
        cancelCarProjectionRetry()
        carProjectionLock.withLock {
            carProjectionRefreshPending = false
            carProjectionRetryCount = 0
        }
        awaitCarProjectionIdleForTests(2_000L)
        synchronized(lock) {
            monitoringRegistered = false
            uiMonitorCount = 0
            audioCallback = null
            bluetoothReceiver = null
            carModeReceiver = null
            carProjectionReceiver = null
            a2dpProxy = null
            hfpProxy = null
            a2dpProxyRequested = false
            hfpProxyRequested = false
            appContext = null
            lastMatch = VoiceAudioPolicy.ActiveMatch.NONE
            lastPausedMatch = null
            lastPausedDisconnectedAtMs = 0L
            connectGeneration = 0L
            sessionMuted = false
            lastDisconnectedAtMs = 0L
            lastSpokenAtMs = 0L
            carProjectionStatus = CarProjectionStatus.UNKNOWN
            carProjectionState = CONNECTION_TYPE_NOT_CONNECTED
            carProjectionConfirmedAtMs = 0L
            lastCarProjectionQuerySucceeded = false
            lastCarProjectionCheckMs = 0L
            carProjectionQueryTimeoutMs = CAR_PROJECTION_QUERY_TIMEOUT_MS
            carProjectionGraceMs = DEFAULT_CAR_PROJECTION_GRACE_MS
            carProjectionRetryDelaysMs = DEFAULT_CAR_PROJECTION_RETRY_DELAYS_MS
            cachedBondedMetas = emptyList()
            cachedBluetoothEnabled = false
            lastDiagnosticSignature = null
            lastCarProjectionOutcomeKey = null
            a2dpConnectedDevices.clear()
            hfpConnectedDevices.clear()
            stateFlow.value = Snapshot()
        }
    }
}
