package tk.glucodata.share.lumiflex

import android.content.Context
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject
import tk.glucodata.CloneSensorRegistry
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth
import tk.glucodata.drivers.aidex.AiDexDriver
import tk.glucodata.drivers.aidex.AiDexSerialIdentity
import tk.glucodata.drivers.aidex.native.protocol.AiDexPairKeyVault

/** Real local pairing metadata. The PAIR credential must never appear in logs or UI. */
class LumiflexCloudSensor(
    serial: String,
    val address: String,
    val startMs: Long,
    pairKey: ByteArray,
    val wearDays: Int = -1,
) {
    val serial: String = AiDexSerialIdentity.bareSerial(serial)
    private val pairKey = pairKey.copyOf()
    val complete: Boolean get() = serial.length in 8..14 && serial.all { it in 'A'..'Z' || it in '0'..'9' } &&
        Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}").matches(address) && startMs > 0 && pairKey.size == 16

    /** IDs are client-assigned by this API; keep them stable across retries and restarts. */
    private fun id(userId: String, kind: String): String = MessageDigest.getInstance("MD5")
        .digest("lumiflex:$userId:$serial:$startMs:$kind".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    fun registrationJson(userId: String, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): JSONObject {
        require(complete && userId.isNotBlank())
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ", Locale.US).apply { timeZone = zone }
        return JSONObject()
            .put("deviceSn", serial).put("deviceMac", address)
            .put("deviceId", id(userId, "device")).put("sensorId", id(userId, "sensor"))
            .put("deviceKey", Base64.getEncoder().encodeToString(pairKey))
            .put("deviceModel", "0").put("deviceType", 2)
            .put("sensorStartUp", formatter.format(Date(startMs)))
            .put("startUpTimeZone", zone.id).put("registerTime", formatter.format(Date(nowMs)))
            .put("dstOffset", if (zone.inDaylightTime(Date(startMs))) (zone.dstSavings / 1000).toString() else "0")
            .also { if (wearDays > 0) it.put("et", wearDays) }
    }

    companion object {
        /** Only sensors paired locally, never Clone mirrors or guessed keys/addresses. */
        fun pairedLocally(context: Context): List<LumiflexCloudSensor> = SensorBluetooth.mygatts().mapNotNull { callback ->
            val driver = callback as? AiDexDriver ?: return@mapNotNull null
            val serial = callback.SerialNumber ?: return@mapNotNull null
            if (CloneSensorRegistry.isCloneSensor(serial) || !driver.isVendorPaired()) return@mapNotNull null
            val address = callback.mActiveDeviceAddress ?: return@mapNotNull null
            if (serial.equals(AiDexSerialIdentity.fallbackCanonicalFromAddress(address), ignoreCase = true)) return@mapNotNull null
            val key = AiDexPairKeyVault.load(context, serial) ?: return@mapNotNull null
            val start = runCatching { Natives.getSensorStartmsec(callback.dataptr) }.getOrDefault(0L)
            LumiflexCloudSensor(serial, address, start, key, driver.getSensorReportedWearDays()).takeIf { it.complete }
        }
    }
}
