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
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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

    private val lock = Any()
    private val refreshLock = Any()
    private val stateFlow = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = stateFlow.asStateFlow()

    private const val CAR_PROJECTION_RECHECK_MS = 30_000L

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
    private var carProjectionState: Int = CONNECTION_TYPE_NOT_CONNECTED
    @Volatile
    internal var lastCarProjectionCheckMs: Long = 0L

    /** Cached paired Bluetooth metadata so reading/alarm paths never perform blocking BT IPC. */
    @Volatile
    private var cachedBondedMetas: List<BondedMeta> = emptyList()
    @Volatile
    private var cachedBluetoothEnabled: Boolean = false

    /** Devices reported connected by BT A2DP/HFP broadcasts (helps MAC matching on API 26–27). */
    private val profileConnectedDevices = LinkedHashMap<String, VoiceAudioPolicy.ConnectedDevice>()

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
        if (!config.enabled && !hasUiMonitors()) {
            synchronized(lock) {
                stateFlow.value = Snapshot(config = config)
            }
            return
        }
        updateMonitoringRegistration(app, shouldMonitor = true)
        refreshState(app)
    }

    /** Keeps live device discovery active while the Voice screen or device picker is open. */
    fun acquireUiMonitoring(context: Context) {
        if (!isSupported()) return
        val app = context.applicationContext
        synchronized(lock) {
            appContext = app
            uiMonitorCount++
        }
        updateMonitoringRegistration(app, shouldMonitor = true)
        refreshState(app)
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
        refreshState(app)
        VoiceReadings.changed()
    }

    fun setScope(context: Context, scope: VoiceAudioPolicy.Scope) {
        if (!isSupported()) return
        val app = context.applicationContext
        prefs(app).edit().putString(KEY_SCOPE, scope.prefValue).apply()
        refreshState(app)
        VoiceReadings.changed()
    }

    fun setIncludeCarMode(context: Context, include: Boolean) {
        if (!isSupported()) return
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_CAR_MODE, include).apply()
        if (include && (isEnabled(app) || hasUiMonitors())) refreshCarProjectionAsync(app)
        refreshState(app)
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
        refreshState(app)
        VoiceReadings.changed()
    }

    fun removeSavedDevice(context: Context, id: String) {
        if (!isSupported()) return
        val app = context.applicationContext
        val current = loadConfig(app).savedDevices.filterNot { it.id == id }
        prefs(app).edit().putString(KEY_DEVICES, encodeSavedDevices(current)).apply()
        refreshState(app)
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
        refreshState(context)
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
            refreshState(context)
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

    fun refreshState(context: Context) {
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

        // Serialize query + commit so a slower background reading thread can never restore
        // "connected" after a newer disconnect has already been processed on the main thread.
        synchronized(refreshLock) {
            val config = loadConfig(app)
            val connected = queryConnectedAudioDevices(app)
            val inCarMode = queryInCarMode(app)
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

        if ((!wasMatched && newMatch.matched) || distinctSessionSwitch) {
            Log.i(LOG_ID, "activated: key=${newMatch.triggerKey} label=${newMatch.displayLabel} car=${newMatch.viaCarMode} switch=$distinctSessionSwitch muted=$currentMuted")
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
            Log.i(LOG_ID, "deactivated: audio disconnected")
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

    private fun scheduleConnectAnnouncement(app: Context, expectedGeneration: Long, isRetry: Boolean) {
        cancelPendingConnectAnnouncement()
        val delayMs = if (isRetry) {
            VoiceAudioPolicy.CONNECT_TTS_RETRY_DELAY_MS
        } else {
            VoiceAudioPolicy.CONNECT_SETTLE_DELAY_MS
        }
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
                profileConnectedDevices.clear()
                carProjectionState = CONNECTION_TYPE_NOT_CONNECTED
            } else {
                return
            }
        }

        if (registerNow) {
            val cb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    refreshState(app)
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    refreshState(app)
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
                        refreshState(app)
                    }
                }
            }
            val carProjRec = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == ACTION_CAR_CONNECTION_UPDATED) {
                        refreshCarProjectionAsync(app)
                    }
                }
            }

            try {
                val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
            } catch (t: Throwable) {
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
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "registerBluetoothReceiver", t)
            }
            // UiModeManager car mode broadcasts originate from system_server.
            try {
                val carModeFilter = IntentFilter().apply {
                    addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
                    addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
                }
                ContextCompat.registerReceiver(app, carModeRec, carModeFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "registerCarModeReceiver", t)
            }
            // Android Auto projection updates originate from the Android Auto host app.
            try {
                val carProjFilter = IntentFilter(ACTION_CAR_CONNECTION_UPDATED)
                ContextCompat.registerReceiver(app, carProjRec, carProjFilter, ContextCompat.RECEIVER_EXPORTED)
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "registerCarProjectionReceiver", t)
            }
            synchronized(lock) {
                audioCallback = cb
                bluetoothReceiver = btRec
                carModeReceiver = carModeRec
                carProjectionReceiver = carProjRec
            }
            refreshCarProjectionAsync(app)
            refreshBondedCacheAsync(app)
        } else if (unregisterNow) {
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
                    synchronized(lock) {
                        profileConnectedDevices.clear()
                    }
                    cachedBluetoothEnabled = false
                    cachedBondedMetas = emptyList()
                    refreshState(app)
                } else if (state == BluetoothAdapter.STATE_ON) {
                    refreshBondedCacheAsync(app)
                    refreshState(app)
                }
            }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                refreshBondedCacheAsync(app)
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
            -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state != BluetoothProfile.STATE_CONNECTED && state != BluetoothProfile.STATE_DISCONNECTED) {
                    return
                }
                val device = extractBluetoothDevice(intent)
                if (device != null) {
                    updateProfileCache(app, device, state)
                }
                refreshState(app)
            }
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
    private fun updateProfileCache(app: Context, device: BluetoothDevice, state: Int) {
        if (!hasBluetoothConnectPermission(app)) return
        try {
            // Defense-in-depth for the exported Bluetooth receiver: only cache bonded devices.
            if (state == BluetoothProfile.STATE_CONNECTED && device.bondState != BluetoothDevice.BOND_BONDED) {
                return
            }
            val mac = VoiceAudioPolicy.normalizeAddress(device.address)
            val name = friendlyBluetoothName(device)
            val key = VoiceAudioPolicy.deviceId(mac, name)
            if (key.isEmpty()) return
            synchronized(lock) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        profileConnectedDevices[key] = VoiceAudioPolicy.ConnectedDevice(
                            address = mac,
                            name = name,
                            isBluetoothAudio = true,
                            isCarAudio = isCarBluetoothClass(device.bluetoothClass),
                        )
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        profileConnectedDevices.remove(key)
                    }
                }
            }
        } catch (t: SecurityException) {
            Log.i(LOG_ID, "BT permission unavailable while updating profile cache: ${t.message}")
        } catch (t: Throwable) {
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
     * While Android Auto projection is cached as connected, periodically re-verify it off-thread
     * so a missed disconnect broadcast (e.g. Android Auto crash or force-stop) can never leave
     * speech stuck on the phone speaker.
     */
    private fun maybeScheduleStaleCarProjectionRefresh(app: Context) {
        val cached = carProjectionState
        if (cached != CONNECTION_TYPE_PROJECTION && cached != CONNECTION_TYPE_NATIVE) return
        val now = System.currentTimeMillis()
        if (now - lastCarProjectionCheckMs >= CAR_PROJECTION_RECHECK_MS) {
            lastCarProjectionCheckMs = now
            refreshCarProjectionAsync(app)
        }
    }

    private fun refreshCarProjectionAsync(app: Context) {
        try {
            Applic.scheduler.execute {
                refreshCarProjectionNow(app)
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "refreshCarProjectionAsync", t)
        }
    }

    internal fun refreshCarProjectionNow(app: Context) {
        lastCarProjectionCheckMs = System.currentTimeMillis()
        val updated = queryCarProjectionProvider(app)
        val previous = carProjectionState
        carProjectionState = updated
        if (updated != previous || (updated != CONNECTION_TYPE_NOT_CONNECTED && !isMatchedDeviceConnected(app))) {
            refreshState(app)
        }
    }

    internal fun queryCarProjectionProvider(context: Context): Int {
        return try {
            context.contentResolver.query(
                CAR_CONNECTION_URI,
                arrayOf(CAR_CONNECTION_COLUMN),
                null,
                null,
                null,
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(CAR_CONNECTION_COLUMN)
                if (idx >= 0 && cursor.moveToFirst()) {
                    cursor.getInt(idx)
                } else {
                    CONNECTION_TYPE_NOT_CONNECTED
                }
            } ?: CONNECTION_TYPE_NOT_CONNECTED
        } catch (_: SecurityException) {
            CONNECTION_TYPE_NOT_CONNECTED
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "queryCarProjectionProvider", t)
            CONNECTION_TYPE_NOT_CONNECTED
        }
    }

    private fun queryInCarMode(context: Context): Boolean {
        // Always read the cached Android Auto / AAOS projection state so glucose processing
        // never performs synchronous cross-process IPC against an external content provider.
        // The cache is kept up to date asynchronously on startup, CAR_CONNECTION_UPDATED, and
        // throttled rechecks while connected.
        val cachedProjection = carProjectionState
        if (cachedProjection == CONNECTION_TYPE_PROJECTION || cachedProjection == CONNECTION_TYPE_NATIVE) {
            return true
        }
        // Retain UiModeManager car mode as a separate signal for car docks and Android 8–11.
        return try {
            val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            uiMode?.currentModeType == Configuration.UI_MODE_TYPE_CAR
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "queryInCarMode", t)
            false
        }
    }

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
        readBondedMetadata(app)
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

    private fun queryConnectedAudioDevices(context: Context): List<VoiceAudioPolicy.ConnectedDevice> {
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
        val cachedProfiles = synchronized(lock) { profileConnectedDevices.values.toList() }

        val mediaOutputs = ArrayList<AudioDeviceInfo>()
        val scoProfiles = ArrayList<VoiceAudioPolicy.ConnectedDevice>()
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            for (info in outputs) {
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
            Log.stack(LOG_ID, "queryConnectedAudioDevices", t)
        }

        // SCO outputs may supply MAC/name identity for an active media output, but never trigger alone.
        val enrichmentProfiles = cachedProfiles + scoProfiles
        val raw = ArrayList<VoiceAudioPolicy.ConnectedDevice>(mediaOutputs.size)
        for (info in mediaOutputs) {
            val rawAddr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.address else null
            val rawName = info.productName?.toString()
            val enriched = VoiceAudioPolicy.enrichConnectedAudioOutput(
                rawAddress = rawAddr,
                rawName = rawName,
                profileConnected = enrichmentProfiles,
                bondedHints = bondedHints,
                looksLikeCarName = VoiceAudioPolicy::looksLikeCarName,
                transport = transportForAudioOutputType(info.type),
            )
            if (enriched != null && (enriched.address == null || enriched.address !in wearableMacs)) {
                raw.add(enriched)
            }
        }

        return VoiceAudioPolicy.deduplicateConnectedDevices(raw)
    }

    /** Collapses dual-mode Classic + BLE/LE Audio twins in bonded device metadata into single entries. */
    private fun collapseBondedTwins(
        metas: List<BondedMeta>,
        savedDevices: List<VoiceAudioPolicy.SavedDevice>,
    ): List<BondedMeta> {
        val merged = ArrayList<BondedMeta>(metas.size)
        for (meta in metas) {
            val isCarMeta = meta.isCar || VoiceAudioPolicy.looksLikeCarName(meta.name)
            val existingIndex = merged.indexOfFirst { existing ->
                val existingIsCar = existing.isCar || VoiceAudioPolicy.looksLikeCarName(existing.name)
                VoiceAudioPolicy.areSamePhysicalDevice(
                    addressA = existing.address,
                    nameA = existing.name,
                    transportA = existing.transport,
                    isCarA = existingIsCar,
                    addressB = meta.address,
                    nameB = meta.name,
                    transportB = meta.transport,
                    isCarB = isCarMeta,
                )
            }
            if (existingIndex < 0) {
                merged.add(meta)
            } else {
                val existing = merged[existingIndex]
                val incomingSaved = savedDevices.any {
                    VoiceAudioPolicy.normalizeAddress(it.address) == meta.address
                }
                val existingSaved = savedDevices.any {
                    VoiceAudioPolicy.normalizeAddress(it.address) == existing.address
                }
                val preferIncomingAsPrimary = (!existingSaved && incomingSaved) ||
                    (existingSaved == incomingSaved &&
                        existing.transport == VoiceAudioPolicy.Transport.LE_ONLY &&
                        meta.transport == VoiceAudioPolicy.Transport.CLASSIC_OR_DUAL)
                val primary = if (preferIncomingAsPrimary) meta else existing
                val secondary = if (preferIncomingAsPrimary) existing else meta
                val alts = (existing.alternateAddresses + meta.alternateAddresses + secondary.address)
                    .filter { it != primary.address }
                    .distinct()
                val mergedTransport = when {
                    existing.transport == VoiceAudioPolicy.Transport.CLASSIC_OR_DUAL ||
                        meta.transport == VoiceAudioPolicy.Transport.CLASSIC_OR_DUAL ->
                        VoiceAudioPolicy.Transport.CLASSIC_OR_DUAL
                    existing.transport == VoiceAudioPolicy.Transport.LE_ONLY ||
                        meta.transport == VoiceAudioPolicy.Transport.LE_ONLY ->
                        VoiceAudioPolicy.Transport.LE_ONLY
                    else -> VoiceAudioPolicy.Transport.UNKNOWN
                }
                merged[existingIndex] = primary.copy(
                    isCar = existing.isCar || meta.isCar,
                    isAudio = existing.isAudio || meta.isAudio,
                    isWearable = existing.isWearable && meta.isWearable,
                    transport = mergedTransport,
                    alternateAddresses = alts,
                )
            }
        }
        return merged
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
            val mergedAlts = buildSet {
                conn.alternateAddresses.forEach(::add)
                saved?.alternateAddresses?.forEach(::add)
                // Include any bonded twin MACs for this connected device so saving covers both transports.
                for (meta in bondedMetas) {
                    if (meta.address in allMacs || VoiceAudioPolicy.areSamePhysicalDevice(
                            addressA = conn.address,
                            nameA = conn.name,
                            transportA = conn.transport,
                            isCarA = conn.isCarAudio,
                            addressB = meta.address,
                            nameB = meta.name,
                            transportB = meta.transport,
                            isCarB = meta.isCar || VoiceAudioPolicy.looksLikeCarName(meta.name),
                        )
                    ) {
                        add(meta.address)
                    }
                }
                if (primaryAddr != null) remove(primaryAddr)
            }.toList()
            SelectableDevice(
                id = id,
                address = primaryAddr,
                name = conn.name.ifBlank { saved?.name ?: conn.address.orEmpty() },
                isCar = conn.isCarAudio || saved?.isCar == true,
                isConnected = true,
                isPaired = bondedMetas.any { it.address in allMacs || it.address in mergedAlts },
                isSelected = saved != null,
                alternateAddresses = mergedAlts,
            )
        }.sortedWith(compareByDescending<SelectableDevice> { it.isCar }.thenBy { it.name.lowercase(Locale.ROOT) })

        val nonConnectedBonded = bondedMetas.filter { meta ->
            val metaIsCar = meta.isCar || VoiceAudioPolicy.looksLikeCarName(meta.name)
            connectedDevices.none { conn ->
                val connMacs = buildSet {
                    VoiceAudioPolicy.normalizeAddress(conn.address)?.let(::add)
                    conn.alternateAddresses.mapNotNull(VoiceAudioPolicy::normalizeAddress).forEach(::add)
                }
                meta.address in connMacs || VoiceAudioPolicy.areSamePhysicalDevice(
                    addressA = conn.address,
                    nameA = conn.name,
                    transportA = conn.transport,
                    isCarA = conn.isCarAudio,
                    addressB = meta.address,
                    nameB = meta.name,
                    transportB = meta.transport,
                    isCarB = metaIsCar,
                )
            }
        }

        val collapsedNonConnected = collapseBondedTwins(nonConnectedBonded, config.savedDevices)
        val audioBonded = collapsedNonConnected.filter { meta ->
            val metaMacs = (listOf(meta.address) + meta.alternateAddresses).toSet()
            meta.isAudio || meta.isCar || config.savedDevices.any { saved ->
                val savedMacs = (listOfNotNull(VoiceAudioPolicy.normalizeAddress(saved.address)) +
                    saved.alternateAddresses.mapNotNull(VoiceAudioPolicy::normalizeAddress)).toSet()
                savedMacs.any { it in metaMacs }
            }
        }
        val hiddenCount = (collapsedNonConnected.size - audioBonded.size).coerceAtLeast(0)
        val visibleBonded = if (includeAllPaired) collapsedNonConnected else audioBonded

        val pairedList = visibleBonded.map { meta ->
            val metaMacs = (listOf(meta.address) + meta.alternateAddresses).toSet()
            val saved = config.savedDevices.firstOrNull { s ->
                val savedMacs = (listOfNotNull(VoiceAudioPolicy.normalizeAddress(s.address)) +
                    s.alternateAddresses.mapNotNull(VoiceAudioPolicy::normalizeAddress)).toSet()
                savedMacs.any { it in metaMacs } ||
                    (savedMacs.isEmpty() &&
                        VoiceAudioPolicy.normalizeName(s.name) == VoiceAudioPolicy.normalizeName(meta.name))
            }
            val id = saved?.id ?: VoiceAudioPolicy.deviceId(meta.address, meta.name)
            val mergedAlts = (meta.alternateAddresses + saved?.alternateAddresses.orEmpty())
                .filter { it != meta.address }
                .distinct()
            SelectableDevice(
                id = id,
                address = meta.address,
                name = meta.name,
                isCar = meta.isCar || saved?.isCar == true || VoiceAudioPolicy.looksLikeCarName(meta.name),
                isConnected = false,
                isPaired = true,
                isSelected = saved != null,
                alternateAddresses = mergedAlts,
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
        synchronized(lock) {
            monitoringRegistered = false
            uiMonitorCount = 0
            audioCallback = null
            bluetoothReceiver = null
            carModeReceiver = null
            carProjectionReceiver = null
            appContext = null
            lastMatch = VoiceAudioPolicy.ActiveMatch.NONE
            lastPausedMatch = null
            lastPausedDisconnectedAtMs = 0L
            connectGeneration = 0L
            sessionMuted = false
            lastDisconnectedAtMs = 0L
            lastSpokenAtMs = 0L
            carProjectionState = CONNECTION_TYPE_NOT_CONNECTED
            lastCarProjectionCheckMs = 0L
            cachedBondedMetas = emptyList()
            cachedBluetoothEnabled = false
            profileConnectedDevices.clear()
            stateFlow.value = Snapshot()
        }
    }
}
