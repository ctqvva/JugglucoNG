package tk.glucodata

import android.content.Context
import java.util.concurrent.Executors
import tk.glucodata.drivers.ManagedSensorIdentityRegistry

/**
 * "Whichever device can reach the sensor reads it."
 *
 * [SensorOwnershipRuntime] has always been able to arbitrate this: each device
 * announces whether it holds a sensor, and stands down when the other one is the
 * one getting readings. What was missing was the watch ever being in a position
 * to take part. Its Bluetooth is armed only by the phone's explicit "Direct
 * sensor on watch" handover, so until the user performed that handover the watch
 * never scanned, and the arbitration had exactly one candidate.
 *
 * This switch arms the watch without assigning the sensor to it. The watch scans
 * and connects only when the arbitration says the phone is not reading — so the
 * phone keeps the sensor whenever it can reach it, and the watch picks it up
 * when the phone cannot, in either direction and without the user touching
 * anything.
 *
 * It is deliberately independent of the direct-sensor handover: with both on,
 * the watch is the assigned owner and the phone still takes the sensor back the
 * moment the watch stops being reachable, rather than waiting out the peer
 * silence timeout.
 */
object AutoSensorSwitch {
    private const val LOG_ID = "AutoSensorSwitch"
    private const val PREFS = "tk.glucodata_preferences"

    /** Phone-owned; mirrored to the watch by [WearToggleSync]. */
    const val PREF_KEY = "wear_auto_sensor_switch"

    /** [WearToggleSync] scope-`p` id for the mirrored copy. */
    const val TOGGLE_ID = "autoswitch"

    @JvmStatic
    fun isEnabled(): Boolean = runCatching {
        Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(PREF_KEY, false)
    }.getOrNull() ?: false

    /**
     * Writes the setting on this device and brings its radio into line.
     *
     * Called on the phone when the user flips the switch, and on the watch when
     * the mirrored value arrives.
     */
    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        runCatching {
            Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()
                ?.putBoolean(PREF_KEY, enabled)
                ?.apply()
        }.onFailure { Log.stack(LOG_ID, "persist($enabled)", it) }
        Log.i(LOG_ID, "automatic sensor switching $enabled")
        applyRadioState(enabled)
        UiRefreshBus.requestStatusRefresh()
    }

    /**
     * Re-arms the watch after the app restarts, next to
     * [WearSensorClaim.restoreOnStart]. Without this the setting survives but
     * the scanning it enables does not, which is the failure mode direct mode
     * already had: the switch reads "on" and nothing is listening.
     */
    @JvmStatic
    fun restoreOnStart() {
        if (!Applic.isWearable || !isEnabled()) return
        Log.i(LOG_ID, "re-arming automatic sensor switching after restart")
        applyRadioState(true)
    }

    /**
     * Watch: bring the radio back if something below this setting turned it off.
     *
     * Native netinfo writes `nobluetooth=true` on the watch at every handshake in
     * which the phone says it is reading the sensor (watch/netinfo.cpp,
     * setmynetinfo). The `/bluetooth` guard in MessageReceiver never sees that
     * write. Under automatic switching it is exactly wrong: the next
     * `updateDevices` then tears the watch's drivers down, and when the phone
     * later loses the sensor the watch has nothing left to take it with. The
     * switch still reads "on" on both screens.
     *
     * Only acts on a disarmed radio, so a healthy one is never restarted.
     */
    @JvmStatic
    fun keepWatchRadioArmed(reason: String) {
        if (!Applic.isWearable || !isEnabled()) return
        if (runCatching { Natives.getusebluetooth() }.getOrDefault(true)) return
        Log.i(LOG_ID, "re-arming watch Bluetooth for automatic switching ($reason)")
        if (SensorBluetooth.blueone?.bluetoothStarted == true) {
            // Drivers still running: restoring the flag is all that is needed,
            // and a full start would kick every connection.
            runCatching { Natives.setusebluetooth(true) }
                .onFailure { Log.stack(LOG_ID, "setusebluetooth(true)", it) }
        } else {
            // Torn down, or only the records-only holder updateDevices builds
            // while Bluetooth is off: that one never scans, so start properly.
            applyRadioState(true)
        }
    }

    private const val PROVISION_PREFS = "wear_auto_switch_provisioned"
    private const val PROVISIONED_AT_SUFFIX = ".at"

    /**
     * How often the setup is sent again while this phone is the one reading the
     * sensor. A successful send only means the Data Layer took the message, not
     * that the watch applied it, so a lost one has to be repeated; and while the
     * phone reads the sensor its copy is the newest there is.
     */
    private const val PROVISION_REFRESH_MS = 60L * 60L * 1000L
    private val provisionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AutoSensorSwitchSetup").apply { isDaemon = true }
    }

    /** Phone: the watch [nodeId] has been sent [sensorId]'s setup. */
    @JvmStatic
    fun recordProvisioned(nodeId: String, sensorId: String?) {
        if (sensorId.isNullOrBlank()) return
        runCatching {
            Applic.app?.getSharedPreferences(PROVISION_PREFS, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(nodeId, sensorId)
                ?.putLong(nodeId + PROVISIONED_AT_SUFFIX, System.currentTimeMillis())
                ?.apply()
        }
    }

    /**
     * Phone, at a watch's sync handshake: send it the current sensor's setup if
     * it has not had it.
     *
     * The setup used to travel once, when the switch was turned on. A send that
     * did not land was never repeated, although the settings screen said it
     * would be, and the next sensor never travelled at all: after the first
     * sensor change the watch was armed for a sensor that had ended and had no
     * record of the one being worn.
     */
    @JvmStatic
    fun provisionIfNeeded(nodeId: String?) {
        if (Applic.isWearable || nodeId.isNullOrBlank() || !isEnabled()) return
        provisionExecutor.execute {
            runCatching { provisionNow(nodeId) }
                .onFailure { Log.stack(LOG_ID, "provision $nodeId", it) }
        }
    }

    private fun provisionNow(nodeId: String) {
        val context = Applic.app ?: return
        val main = SensorIdentity.resolveMainSensor()
        val canTravel = main != null && ManagedSensorHandoff.supportsSensor(main) && (
            ManagedSensorIdentityRegistry.persistedSensorIds(context).any { SensorIdentity.matches(it, main) } ||
                DexcomWatchSetup.canSendToWatch(main)
            )
        val prefs = context.getSharedPreferences(PROVISION_PREFS, Context.MODE_PRIVATE)
        val send = shouldProvisionWatch(
            enabled = isEnabled(),
            mainSensor = main,
            mainCanTravel = canTravel,
            lastProvisioned = prefs.getString(nodeId, null),
            lastProvisionedAtMs = prefs.getLong(nodeId + PROVISIONED_AT_SUFFIX, 0L),
            peerReadsMain = SensorOwnershipRuntime.peerReportsOwning(main),
            phoneReadsMain = SensorOwnershipRuntime.readsLocally(main),
            nowMs = System.currentTimeMillis(),
            refreshMs = PROVISION_REFRESH_MS,
            sameSensor = SensorIdentity::matches,
        )
        if (!send || main == null) return
        val sender = MessageSender.getMessageSender() ?: return
        val refresh = prefs.getString(nodeId, null)?.let { SensorIdentity.matches(it, main) } == true
        val payload = ManagedSensorHandoff.createOutgoingPayload(context, refresh)
        if (sender.sendSensorHandoff(nodeId, payload)) {
            recordProvisioned(nodeId, main)
            Log.i(LOG_ID, "sent $main setup to $nodeId for automatic switching")
        } else {
            Log.w(LOG_ID, "could not send $main setup to $nodeId; retrying at the next handshake")
        }
    }

    /**
     * Arming is watch-only. The phone's Bluetooth is driven by its own sensors
     * and by the handover protocol, and must not be switched off here: turning
     * this setting off means "stop taking the sensor automatically", not "stop
     * reading the sensors you own".
     */
    private fun applyRadioState(enabled: Boolean) {
        if (!Applic.isWearable) return
        // A watch that is the assigned direct owner keeps scanning regardless;
        // WearSensorClaim owns that case and must not be undone here.
        if (!enabled && WearSensorClaim.isDirectRequested()) return
        val context = MainActivity.thisone ?: Applic.app ?: return
        runCatching { Applic.setbluetooth(context, enabled) }
            .onFailure { Log.stack(LOG_ID, "setbluetooth($enabled)", it) }
    }
}

/**
 * Whether the phone should send the watch the current sensor's setup.
 *
 * Only managed sensors and Dexcom G7/ONE+ have setup that can travel (see
 * [DexcomWatchSetup]); Libre records live in native storage, which no longer
 * reaches the watch. Never while the
 * watch says it is reading the sensor itself: its driver state is then newer
 * than the phone's copy, and overwriting it rewinds the sensor's cursors.
 *
 * A sensor the watch has not been sent goes at once. One it has been sent goes
 * again every [refreshMs], but only while this phone is the one reading it: a
 * successful send is not proof the watch applied it, and only then is the
 * phone's copy certainly the newest.
 */
internal fun shouldProvisionWatch(
    enabled: Boolean,
    mainSensor: String?,
    mainCanTravel: Boolean,
    lastProvisioned: String?,
    lastProvisionedAtMs: Long,
    peerReadsMain: Boolean,
    phoneReadsMain: Boolean,
    nowMs: Long,
    refreshMs: Long,
    sameSensor: (String?, String?) -> Boolean,
): Boolean {
    if (!enabled || mainSensor.isNullOrBlank() || !mainCanTravel) return false
    if (peerReadsMain) return false
    if (lastProvisioned.isNullOrBlank() || !sameSensor(lastProvisioned, mainSensor)) return true
    return phoneReadsMain && nowMs - lastProvisionedAtMs >= refreshMs
}
