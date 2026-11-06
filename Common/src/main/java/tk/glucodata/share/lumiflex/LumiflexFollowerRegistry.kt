package tk.glucodata.share.lumiflex

import android.content.Context
import java.security.MessageDigest
import java.util.Locale
import tk.glucodata.*
import tk.glucodata.drivers.*

object LumiflexFollowerRegistry : ManagedSensorIdentityAdapter {
    private const val PREFIX = "LMF-"
    override val uiFamily = ManagedSensorUiFamily.GENERIC
    fun sensorId(context: Context, provider: String = LumiflexCloudSettings(context).providerId): String {
        val owner = LumiflexCloudSettings(context).userId
        val digest = MessageDigest.getInstance("SHA-256").digest("$owner:$provider".toByteArray())
        return PREFIX + digest.take(6).joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 255) }
    }
    private fun configured(context: Context): Boolean = LumiflexCloudSettings(context).let {
        it.enabled && it.followMode && it.isSignedIn() && it.providerId.isNotBlank()
    }
    override fun resolveCanonicalSensorId(sensorId: String?) = sensorId?.trim()?.takeIf { it.startsWith(PREFIX, true) }
    override fun matchesCallbackId(callbackId: String?, sensorId: String) =
        resolveCanonicalSensorId(callbackId)?.equals(sensorId, true) == true
    override fun resolveCallbackDataptr(sensorId: String?) = resolveCanonicalSensorId(sensorId)?.let { 0L }
    override fun hasPersistedManagedRecord(sensorId: String?) = Applic.app?.let {
        configured(it) && sensorId(it).equals(sensorId, true)
    } ?: false
    override fun persistedSensorIds(context: Context) = if (configured(context)) listOf(sensorId(context)) else emptyList()
    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? =
        if (configured(context) && sensorId(context).equals(sensorId, true))
            LumiflexFollowerManager(context.applicationContext, LumiflexCloudSettings(context).providerId) else null
    override fun isExternallyManagedBleSensor(sensorId: String?) = resolveCanonicalSensorId(sensorId) != null
    override fun hasNativeSensorBacking(sensorId: String?) = resolveCanonicalSensorId(sensorId)?.let { false }
    override fun shouldUseNativeHistorySync(sensorId: String?) = resolveCanonicalSensorId(sensorId)?.let { false }
    override fun removePersistedSensor(context: Context, sensorId: String?) {
        if (sensorId(context).equals(sensorId, true)) { LumiflexCloudSettings(context).enabled = false; reconcile(context) }
    }
    fun isRegistered(callback: LumiflexFollowerManager) = SensorBluetooth.mygatts().any { it === callback }

    /** Called on startup, role changes and recovery; cloud sensors work with Bluetooth off. */
    @JvmStatic
    fun reconcile(context: Context): LumiflexFollowerManager? {
        val expected = if (configured(context)) sensorId(context) else null
        var added = false
        var removed = false
        val result = synchronized(SensorBluetooth.gattcallbacks) {
            val old = SensorBluetooth.gattcallbacks.filterIsInstance<LumiflexFollowerManager>()
                .filter { !it.SerialNumber.equals(expected, true) }
            old.forEach {
                removed = true
                it.terminateManagedSensor(false)
                it.close()
                SensorBluetooth.gattcallbacks.remove(it)
                ManagedCurrentSensor.clearIfMatches(it.SerialNumber)
            }
            val found = SensorBluetooth.gattcallbacks.filterIsInstance<LumiflexFollowerManager>().firstOrNull()
            found ?: expected?.let {
                (createManagedCallback(context, it, 0L) as? LumiflexFollowerManager)?.also { callback ->
                    SensorBluetooth.gattcallbacks.add(callback); added = true
                }
            }
        }
        if (added || removed) {
            Natives.setmaxsensors(SensorBluetooth.mygatts().size)
            SensorBluetooth.ensureCurrentSensorSelection()
            ManagedSensorUiSignals.markDeviceListDirty()
        }
        val settings = LumiflexCloudSettings(context)
        if (result != null && settings.selectionPending && configured(context)) {
            val session = settings.session()
            // A valid old native sensor makes ensureCurrentSensorSelection return early.
            // Selecting Receive/provider is an explicit handoff; apply it once, then retain
            // any later main-sensor choice across background polls and process restarts.
            SensorBluetooth.setCurrentSensorSelection(result.SerialNumber)
            settings.selectionApplied(session)
        }
        return result
    }
    @JvmStatic
    fun refresh(context: Context) { reconcile(context)?.connectDevice(0) }
}
