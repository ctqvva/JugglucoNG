package tk.glucodata.combined

import android.content.Context
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tk.glucodata.*
import tk.glucodata.alerts.DualSensorConsensus
import tk.glucodata.drivers.ManagedSensorUiSignals

/** Owns explicitly selected contributors. Display selection changes never silently replace a contributor. */
object CombinedSensorRegistry {
    const val PREFIX = "COMB-"
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY_INPUTS = "combined_sensor_inputs"
    private const val KEY_DISPLAY_VERSION = "combined_sensor_display_version"
    private const val KEY_ENABLED = "combined_sensor_enabled"
    data class Config(val enabled: Boolean = false, val inputs: List<String> = emptyList()) {
        val sensorId: String get() = idFor(inputs)
    }
    private var cached: Config? = null
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    @Volatile internal var manager: CombinedSensorManager? = null
    private val publicationLock = Any()

    fun isCombined(id: String?): Boolean = id?.startsWith(PREFIX, true) == true || canonicalId(id) != null

    internal fun canonicalId(id: String?): String? {
        val raw = id?.uppercase(java.util.Locale.ROOT) ?: return null
        return when {
            raw.matches(Regex("COMB-[0-9A-F]{8}")) -> raw
            raw.matches(Regex("MB-[0-9A-F]{8}")) -> "CO$raw"
            raw.matches(Regex("[0-9A-F]{8}")) -> {
                val full = "COMB-$raw"
                if (config().sensorId == full || runCatching { Natives.resolveFullSensorName(raw) }.getOrNull() == full) full else null
            }
            else -> null
        }
    }

    fun idFor(inputs: List<String>): String = PREFIX + MessageDigest.getInstance("SHA-256")
        .digest(inputs.sorted().joinToString("\n").toByteArray())
        .take(4).joinToString("") { "%02X".format(it) }

    @Synchronized fun config(): Config {
        cached?.let { return it }
        val prefs = Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return Config()
        val ids = prefs.getString(KEY_INPUTS, "").orEmpty().split('\n').filter { it.isNotBlank() }
        return Config(prefs.getBoolean(KEY_ENABLED, false) && ids.size >= 2, ids).also { cached = it }
    }

    fun setEnabled(enabled: Boolean, inputs: List<String>) = synchronized(publicationLock) {
        require(!enabled || (inputs.size >= 2 && inputs.all(SensorIdentity::isUsableSensorId) && inputs.none(::isCombined) && inputs.indices.all { i -> inputs.take(i).none { SensorIdentity.matches(it, inputs[i]) } }))
        val previous = config()
        val next = Config(enabled, if (enabled) inputs.map { SensorIdentity.canonicalSensorId(it) ?: it } else previous.inputs)
        if (enabled) check(Natives.ensureSensorShellWithCapacity(next.sensorId,
            System.currentTimeMillis() / 1000 - 6 * 60 * 60, 14 * 24 * 60) != 0L) { "Unable to create combined sensor history" }
        SensorBluetooth.gattcallbacks.filterIsInstance<CombinedSensorManager>().toList().forEach {
            it.shutdown()
            SensorBluetooth.gattcallbacks.remove(it)
        }
        manager = null
        Applic.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).putString(KEY_INPUTS, next.inputs.joinToString("\n"))
            .putInt(KEY_DISPLAY_VERSION, 1).apply()
        synchronized(this) { cached = next }
        SensorIdentity.invalidateCaches()
        if (enabled) {
            // A real native mirror makes the same derived history available to native exports/Wear.
            val callback = synchronized(SensorBluetooth.blueone ?: publicationLock) {
                SensorBluetooth.gattcallbacks.filterIsInstance<CombinedSensorManager>()
                    .firstOrNull { it.SerialNumber == next.sensorId }
                    ?: CombinedSensorManager(next.sensorId, next.inputs).also { SensorBluetooth.gattcallbacks.add(it) }
            }
            SensorBluetooth.setCurrentSensorSelection(next.sensorId)
            MultiSensorSelection.setSelectedOrder(listOf(next.sensorId))
            Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size)
            callback.connectDevice(0)
        } else {
            val fallback = previous.inputs.firstOrNull()
            if (SensorIdentity.matches(SensorIdentity.resolveMainSensor(), previous.sensorId) && fallback != null) {
                SensorBluetooth.setCurrentSensorSelection(fallback)
            }
            MultiSensorSelection.setSelectedOrder(previous.inputs)
        }
        mutableRevision.update { it + 1 }
        ManagedSensorUiSignals.markDeviceListDirty()
        UiRefreshBus.requestDataRefresh()
    }

    /** Upgrade only the old automatic combined-plus-contributors selection, once. */
    internal fun migrateDisplaySelection(candidate: CombinedSensorManager) = publishIfCurrent(candidate) {
        val prefs = Applic.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_DISPLAY_VERSION, 0) < 1) {
            val oldDefault = listOf(candidate.SerialNumber) + candidate.inputs
            val selected = MultiSensorSelection.selectedOrder()
            if (selected.size == oldDefault.size && selected.zip(oldDefault).all { (a, b) -> SensorIdentity.matches(a, b) }) {
                MultiSensorSelection.setSelectedOrder(listOf(candidate.SerialNumber))
                UiRefreshBus.requestDataRefresh()
            }
            prefs.edit().putInt(KEY_DISPLAY_VERSION, 1).apply()
        }
    }

    internal fun register(candidate: CombinedSensorManager) {
        if (!Applic.isWearable && config().enabled && config().sensorId == candidate.SerialNumber) manager = candidate
    }

    internal fun publishIfCurrent(candidate: CombinedSensorManager, action: () -> Unit) = synchronized(publicationLock) {
        if (manager === candidate && config().enabled && !candidate.isStopped) action()
    }

    /** Called before the physical source's primary-only gate, without changing its stored data. */
    @JvmStatic fun onPhysicalReading(id: String?, reading: LiveReadingLanes, rate: Float, timeMs: Long, generation: Int) {
        // This runs on GATT callbacks too: all identity/history/calibration work
        // belongs to the manager's worker, never the transport callback.
        manager?.observeIncoming(id, reading, rate, timeMs, generation)
    }

    /** Every primary-selection route (including watch commands) uses this exit. */
    @JvmStatic fun onPrimarySelection(id: String?) {
        if (!Applic.isWearable && !id.isNullOrBlank() && !isCombined(id) && config().enabled) {
            setEnabled(false, emptyList())
            MultiSensorSelection.moveToFront(id)
        }
    }

    @JvmStatic fun shouldExportToHealthConnect(id: String): Boolean =
        !config().enabled || SensorIdentity.matches(id, config().sensorId)

    internal fun assessmentChanged() {
        mutableRevision.update { it + 1 }
        UiRefreshBus.requestStatusRefresh()
    }

    internal fun hasFreshOutput(): Boolean = manager?.getManagedCurrentSnapshot(DualSensorConsensus.MAX_AGE_MS) != null
    internal fun result(nowMs: Long = System.currentTimeMillis()): DualSensorConsensus.Result? = manager?.assessment(nowMs)
}
