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
    private val stateFlow = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = stateFlow.asStateFlow()

    private var appContext: Context? = null
    private var monitoringRegistered = false
    private var uiMonitorCount = 0
    private var audioCallback: AudioDeviceCallback? = null
    private var receiver: BroadcastReceiver? = null
    private var pendingConnectTask: ScheduledFuture<*>? = null

    private var lastMatch: VoiceAudioPolicy.ActiveMatch = VoiceAudioPolicy.ActiveMatch.NONE
    private var connectGeneration: Long = 0L
    @Volatile
    private var sessionMuted: Boolean = false
    @Volatile
    private var lastDisconnectedAtMs: Long = 0L
    @Volatile
    private var lastSpokenAtMs: Long = 0L

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
        updateMonitoringRegistration(app, shouldMonitor = config.enabled || hasUiMonitors())
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

    /** True when Bluetooth/car audio is actively triggering periodic glucose speech right now. */
    @JvmStatic
    fun isAutoReadingSpeechActive(context: Context?): Boolean {
        if (!isSupported() || context == null || !isEnabled(context)) return false
        return synchronized(lock) { lastMatch.matched && !sessionMuted }
    }

    /**
     * Reconciles live AudioManager / Car mode state on the reading path so speech can never
     * stay stuck on after leaving a car or miss a connection after a process restart.
     */
    @JvmStatic
    fun refreshAndCheckReadingSpeech(context: Context?): Boolean {
        if (!isSupported() || context == null || !isEnabled(context)) return false
        refreshState(context)
        return isAutoReadingSpeechActive(context)
    }

    /** True when Bluetooth/car audio is actively enabling spoken alarms right now. */
    @JvmStatic
    fun isAutoAlarmSpeechActive(context: Context?): Boolean {
        if (!isSupported() || context == null) return false
        val config = loadConfig(context)
        if (!config.enabled || !config.speakAlarms) return false
        return synchronized(lock) { lastMatch.matched && !sessionMuted }
    }

    /** True when glucose alarms should be spoken (either manually or via active car/BT audio). */
    @JvmStatic
    fun isAlarmSpeechEnabled(context: Context?): Boolean {
        if (Applic.DontTalk) return false
        if (isSupported() && context != null && isEnabled(context)) {
            refreshState(context)
        }
        if (Natives.speakalarms()) return true
        return isAutoAlarmSpeechActive(context)
    }

    /** True when the TTS engine must stay alive for Bluetooth/car audio speech. */
    @JvmStatic
    fun isSpeakingActive(context: Context?): Boolean =
        isAutoReadingSpeechActive(context) || isAutoAlarmSpeechActive(context)

    /** True when active Bluetooth/car audio speech should route over the Media stream. */
    @JvmStatic
    fun useMediaStreamNow(context: Context?): Boolean {
        if (!isSupported() || context == null) return false
        val config = loadConfig(context)
        if (!config.enabled || !config.useMediaStream) return false
        return synchronized(lock) { lastMatch.matched && !sessionMuted }
    }

    @JvmStatic
    fun effectiveIntervalSeconds(context: Context?, defaultSeconds: Int): Int {
        val safeDefault = defaultSeconds.coerceAtLeast(1)
        if (!isSupported() || context == null) return safeDefault
        val config = loadConfig(context)
        val autoActive = config.enabled && synchronized(lock) { lastMatch.matched && !sessionMuted }
        return if (autoActive && config.intervalSeconds > 0) config.intervalSeconds else safeDefault
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
        val config = loadConfig(app)
        val connected = queryConnectedAudioDevices(app)
        val inCarMode = queryInCarMode(app)
        val newMatch = VoiceAudioPolicy.evaluateMatch(config, connected, inCarMode)

        val now = System.currentTimeMillis()
        val wasMatched: Boolean
        val matchChanged: Boolean
        val scheduleAnnounce: Boolean
        val currentMuted: Boolean
        val generation: Long

        synchronized(lock) {
            wasMatched = lastMatch.matched
            matchChanged = lastMatch != newMatch
            lastMatch = newMatch

            if (!wasMatched && newMatch.matched) {
                // New drive / connection starts unmuted.
                sessionMuted = false
                connectGeneration++
            } else if (wasMatched && !newMatch.matched) {
                sessionMuted = false
                lastDisconnectedAtMs = now
                connectGeneration++
            }
            generation = connectGeneration
            currentMuted = sessionMuted
            scheduleAnnounce = VoiceAudioPolicy.shouldScheduleConnectAnnouncement(
                wasMatched = wasMatched,
                nowMatched = newMatch.matched,
                config = config,
                sessionMuted = currentMuted,
                nowMs = now,
                lastDisconnectedAtMs = lastDisconnectedAtMs,
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

        if (!wasMatched && newMatch.matched) {
            Log.i(LOG_ID, "activated: key=${newMatch.triggerKey} label=${newMatch.displayLabel} car=${newMatch.viaCarMode}")
            Talker.nexttime = 0L
            if (SuperGattCallback.talker == null) {
                SuperGattCallback.newtalker(app)
            }
            if (scheduleAnnounce) {
                scheduleConnectAnnouncement(app, generation, isRetry = false)
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
        talker.speakConnectAnnouncement(text, useMedia, chime)
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
        val oldReceiver: BroadcastReceiver?

        synchronized(lock) {
            if (shouldMonitor && !monitoringRegistered) {
                monitoringRegistered = true
                registerNow = true
                unregisterNow = false
                oldCallback = null
                oldReceiver = null
            } else if (!shouldMonitor && monitoringRegistered) {
                monitoringRegistered = false
                registerNow = false
                unregisterNow = true
                oldCallback = audioCallback
                oldReceiver = receiver
                audioCallback = null
                receiver = null
                profileConnectedDevices.clear()
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
            val rec = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    handleSystemBroadcast(app, intent)
                }
            }
            try {
                val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "registerAudioDeviceCallback", t)
            }
            try {
                val filter = IntentFilter().apply {
                    addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
                    addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
                }
                ContextCompat.registerReceiver(app, rec, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "registerReceiver", t)
            }
            synchronized(lock) {
                audioCallback = cb
                receiver = rec
            }
        } else if (unregisterNow) {
            if (oldCallback != null) {
                try {
                    val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    audioManager?.unregisterAudioDeviceCallback(oldCallback)
                } catch (t: Throwable) {
                    Log.stack(LOG_ID, "unregisterAudioDeviceCallback", t)
                }
            }
            if (oldReceiver != null) {
                try {
                    app.unregisterReceiver(oldReceiver)
                } catch (_: IllegalArgumentException) {
                    // Already unregistered.
                } catch (t: Throwable) {
                    Log.stack(LOG_ID, "unregisterReceiver", t)
                }
            }
        }
    }

    private fun handleSystemBroadcast(app: Context, intent: Intent) {
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    synchronized(lock) {
                        profileConnectedDevices.clear()
                    }
                }
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
            -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                val device = extractBluetoothDevice(intent)
                if (device != null) {
                    updateProfileCache(app, device, state)
                }
            }
        }
        refreshState(app)
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
    // Device discovery (AudioManager + Bonded Bluetooth devices + Car Mode)
    // -------------------------------------------------------------------------

    fun hasBluetoothConnectPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun queryInCarMode(context: Context): Boolean {
        return try {
            val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            uiMode?.currentModeType == Configuration.UI_MODE_TYPE_CAR
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "queryInCarMode", t)
            false
        }
    }

    private fun isBluetoothAudioOutputType(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
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

    private data class BondedMeta(
        val address: String,
        val name: String,
        val isCar: Boolean,
        val isAudio: Boolean,
    )

    @SuppressLint("MissingPermission")
    private fun readBondedMetadata(context: Context): Pair<Boolean, List<BondedMeta>> {
        val adapter = getBluetoothAdapter(context) ?: return false to emptyList()
        val enabled = try {
            adapter.isEnabled
        } catch (_: Throwable) {
            false
        }
        if (!hasBluetoothConnectPermission(context)) {
            return enabled to emptyList()
        }
        val bonded = try {
            adapter.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            return enabled to emptyList()
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "bondedDevices", t)
            return enabled to emptyList()
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
                )
            } catch (_: Throwable) {
                null
            }
        }
        return enabled to metas
    }

    private fun queryConnectedAudioDevices(context: Context): List<VoiceAudioPolicy.ConnectedDevice> {
        val (_, bondedMetas) = readBondedMetadata(context)
        val bondedHints = bondedMetas.map {
            VoiceAudioPolicy.BondedHint(address = it.address, name = it.name, isCar = it.isCar)
        }
        val cachedProfiles = synchronized(lock) { profileConnectedDevices.values.toList() }

        val raw = ArrayList<VoiceAudioPolicy.ConnectedDevice>()
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            for (info in outputs) {
                if (!isBluetoothAudioOutputType(info.type)) continue
                val rawAddr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.address else null
                val rawName = info.productName?.toString()
                val enriched = VoiceAudioPolicy.enrichConnectedAudioOutput(
                    rawAddress = rawAddr,
                    rawName = rawName,
                    profileConnected = cachedProfiles,
                    bondedHints = bondedHints,
                    looksLikeCarName = ::looksLikeCarName,
                )
                if (enriched != null) {
                    raw.add(enriched)
                }
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "queryConnectedAudioDevices", t)
        }

        return VoiceAudioPolicy.deduplicateConnectedDevices(raw)
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
            SelectableDevice(
                id = id,
                address = conn.address ?: saved?.address,
                name = conn.name.ifBlank { saved?.name ?: conn.address.orEmpty() },
                isCar = conn.isCarAudio || saved?.isCar == true,
                isConnected = true,
                isPaired = conn.address != null && bondedMetas.any { it.address == conn.address },
                isSelected = saved != null,
            )
        }.sortedWith(compareByDescending<SelectableDevice> { it.isCar }.thenBy { it.name.lowercase(Locale.ROOT) })

        val nonConnectedBonded = bondedMetas.filter { meta ->
            connectedDevices.none { conn ->
                val connMac = VoiceAudioPolicy.normalizeAddress(conn.address)
                if (connMac != null) connMac == meta.address
                else VoiceAudioPolicy.normalizeName(conn.name) == VoiceAudioPolicy.normalizeName(meta.name)
            }
        }

        val audioBonded = nonConnectedBonded.filter { meta ->
            meta.isAudio || meta.isCar || config.savedDevices.any {
                VoiceAudioPolicy.normalizeAddress(it.address) == meta.address
            }
        }
        val hiddenCount = (nonConnectedBonded.size - audioBonded.size).coerceAtLeast(0)
        val visibleBonded = if (includeAllPaired) nonConnectedBonded else audioBonded

        val pairedList = visibleBonded.map { meta ->
            val saved = config.savedDevices.firstOrNull {
                VoiceAudioPolicy.normalizeAddress(it.address) == meta.address ||
                    (VoiceAudioPolicy.normalizeAddress(it.address) == null &&
                        VoiceAudioPolicy.normalizeName(it.name) == VoiceAudioPolicy.normalizeName(meta.name))
            }
            val id = saved?.id ?: VoiceAudioPolicy.deviceId(meta.address, meta.name)
            SelectableDevice(
                id = id,
                address = meta.address,
                name = meta.name,
                isCar = meta.isCar || saved?.isCar == true || looksLikeCarName(meta.name),
                isConnected = false,
                isPaired = true,
                isSelected = saved != null,
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
        val deviceClass = btClass.deviceClass
        return deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
    }

    private fun isAudioBluetoothClass(btClass: BluetoothClass?): Boolean {
        if (btClass == null) return false
        val major = btClass.majorDeviceClass
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) return true
        // Exclude common non-audio classes (CGMs/meters, watches, keyboards, phones, computers).
        if (major == BluetoothClass.Device.Major.HEALTH ||
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

    private fun looksLikeCarName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.contains("car") ||
            lower.contains("auto") ||
            lower.contains("handsfree") ||
            lower.contains("hands-free") ||
            lower.contains("uconnect") ||
            lower.contains("sync") ||
            lower.contains("myford") ||
            lower.contains("mazda") ||
            lower.contains("toyota") ||
            lower.contains("honda") ||
            lower.contains("bmw") ||
            lower.contains("audi") ||
            lower.contains("mercedes") ||
            lower.contains("tesla") ||
            lower.contains("volvo") ||
            lower.contains("subaru") ||
            lower.contains("hyundai") ||
            lower.contains("kia") ||
            lower.contains("nissan") ||
            lower.contains("vw") ||
            lower.contains("skoda") ||
            lower.contains("ford") ||
            lower.contains("chevy") ||
            lower.contains("chevrolet")
    }

    // -------------------------------------------------------------------------
    // JSON serialization for saved devices
    // -------------------------------------------------------------------------

    private fun sameSavedIdentity(a: VoiceAudioPolicy.SavedDevice, b: VoiceAudioPolicy.SavedDevice): Boolean {
        if (a.id.isNotEmpty() && a.id == b.id) return true
        val macA = VoiceAudioPolicy.normalizeAddress(a.address)
        val macB = VoiceAudioPolicy.normalizeAddress(b.address)
        if (macA != null && macB != null) return macA == macB
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
                list.add(
                    VoiceAudioPolicy.SavedDevice(
                        id = id,
                        address = address,
                        name = name,
                        isCar = obj.optBoolean("isCar", false),
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
            receiver = null
            appContext = null
            lastMatch = VoiceAudioPolicy.ActiveMatch.NONE
            connectGeneration = 0L
            sessionMuted = false
            lastDisconnectedAtMs = 0L
            lastSpokenAtMs = 0L
            profileConnectedDevices.clear()
            stateFlow.value = Snapshot()
        }
    }
}
