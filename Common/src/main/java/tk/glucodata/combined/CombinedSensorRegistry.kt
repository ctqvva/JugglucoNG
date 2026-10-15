package tk.glucodata.combined

import android.content.Context
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tk.glucodata.*
import tk.glucodata.alerts.DualSensorConsensus
import tk.glucodata.drivers.ManagedSensorUiSignals

/** Owns one explicit pair. Display selection changes never silently replace a contributor. */
object CombinedSensorRegistry {
    const val PREFIX = "COMB-"
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY_INPUTS = "combined_sensor_inputs"
    private const val KEY_ENABLED = "combined_sensor_enabled"
    data class Config(val enabled: Boolean = false, val inputs: List<String> = emptyList()) {
        val sensorId: String get() = idFor(inputs)
    }
    private var cached: Config? = null
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    @Volatile internal var manager: CombinedSensorManager? = null
    private val publicationLock = Any()

    fun isCombined(id: String?): Boolean = id?.startsWith(PREFIX, true) == true

    fun idFor(inputs: List<String>): String = PREFIX + MessageDigest.getInstance("SHA-256")
        .digest(inputs.sorted().joinToString("\n").toByteArray())
        .take(4).joinToString("") { "%02X".format(it) }

    @Synchronized fun config(): Config {
        cached?.let { return it }
        val prefs = Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return Config()
        val ids = prefs.getString(KEY_INPUTS, "").orEmpty().split('\n').filter { it.isNotBlank() }
        return Config(prefs.getBoolean(KEY_ENABLED, false) && ids.size == 2, ids).also { cached = it }
    }

    fun setEnabled(enabled: Boolean, inputs: List<String>) = synchronized(publicationLock) {
        require(!enabled || (inputs.size == 2 && inputs.all(SensorIdentity::isUsableSensorId) && inputs.none(::isCombined) && !SensorIdentity.matches(inputs[0], inputs[1])))
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
            .putBoolean(KEY_ENABLED, enabled).putString(KEY_INPUTS, next.inputs.joinToString("\n")).apply()
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
            MultiSensorSelection.setSelectedOrder(listOf(next.sensorId) + next.inputs)
            Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size)
            callback.connectDevice(0)
        } else {
            val fallback = previous.inputs.firstOrNull()
            if (SensorIdentity.matches(SensorIdentity.resolveMainSensor(), previous.sensorId) && fallback != null) {
                SensorBluetooth.setCurrentSensorSelection(fallback)
            }
            MultiSensorSelection.setSelectedOrder(previous.inputs)
        }
        mutableRevision.value++
        ManagedSensorUiSignals.markDeviceListDirty()
        UiRefreshBus.requestDataRefresh()
    }

    internal fun register(candidate: CombinedSensorManager) {
        if (!Applic.isWearable && config().enabled && config().sensorId == candidate.SerialNumber) manager = candidate
    }

    internal fun publishIfCurrent(candidate: CombinedSensorManager, action: () -> Unit) = synchronized(publicationLock) {
        if (manager === candidate && config().enabled && !candidate.isStopped) action()
    }

    /** Called before the physical source's primary-only gate, without changing its stored data. */
    @JvmStatic fun onPhysicalReading(id: String?, reading: LiveReadingLanes, rate: Float, timeMs: Long, generation: Int) {
        if (isCombined(id)) return
        val target = manager ?: return
        if (target.inputs.none { SensorIdentity.matches(it, id) }) return
        val snapshot = runCatching { CurrentDisplaySource.resolveIncomingReading(reading, rate, timeMs, id, generation,
            0, "combination-input", DisplayTrendSource.TREND_WINDOW_MS, true) }
            .onFailure { Log.stack("CombinedSensor", "input resolution", it) }.getOrNull() ?: return
        target.observe(snapshot)
    }

    internal fun hasFreshOutput(): Boolean = manager?.getManagedCurrentSnapshot(DualSensorConsensus.MAX_AGE_MS) != null
    internal fun result(): DualSensorConsensus.Result? = manager?.result
}
