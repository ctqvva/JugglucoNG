package tk.glucodata

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.glucosemeter.SatelliteMeterCredentials

data class GlucoseMeterSnapshot(
    val index: Int,
    val name: String,
    val address: String?,
    val active: Boolean,
    val connected: Boolean,
    val lastReadingAt: Long,
)

/** Compose-facing facade over the legacy, protocol-capable glucose meter runtime. */
object GlucoseMeterManager {
    private const val TAG = "GlucoseMeterManager"

    fun satelliteCode(): String = SatelliteMeterCredentials.load()

    fun isSatelliteCodeValid(code: String): Boolean = SatelliteMeterCredentials.isValid(code)

    fun updateSatelliteCode(code: String) {
        SatelliteMeterCredentials.save(code)
        if (SatelliteMeterCredentials.isValid(code)) {
            BluetoothGlucoseMeter.retrySatelliteSessions()
        }
    }

    fun configuredMeters(): List<GlucoseMeterSnapshot> =
        (0 until Natives.GlucoseMeterCount()).mapNotNull(::snapshot)

    fun setEnabled(index: Int, enabled: Boolean) {
        if (enabled) {
            Natives.GlucoseMeterSetActive(index, true)
            BluetoothGlucoseMeter.addDevice(index, null)
        } else {
            BluetoothGlucoseMeter.removeDevice(index)
            Natives.GlucoseMeterSetActive(index, false)
        }
    }

    /**
     * Forgets a meter completely: stops using it, drops the saved address and
     * unpairs it from the phone, the way xDrip's "Forget Pair" does. Readings
     * already stored in the journal are left alone.
     */
    @SuppressLint("MissingPermission")
    fun forget(index: Int): Boolean {
        val address = Natives.GlucoseMeterDeviceAddress(index)
        setEnabled(index, false)
        val unbound = if (address.isNullOrBlank()) {
            true
        } else {
            runCatching {
                // same reflection the anytime driver uses, removeBond is not
                // visible to us directly
                val device = BluetoothGlucoseMeter.mBluetoothAdapter?.getRemoteDevice(address)
                    ?: return@runCatching false
                (device.javaClass.getMethod("removeBond").invoke(device) as? Boolean) ?: false
            }.onFailure { Log.stack(TAG, "removeBond", it) }.getOrDefault(false)
        }
        Natives.GlucoseMeterSetDeviceAddress(index, null)
        // lastTime is deliberately kept: it is what stops the native store from
        // taking the same readings a second time if this meter is paired again.
        return unbound
    }

    @SuppressLint("MissingPermission")
    fun add(device: BluetoothDevice, displayName: String): Int {
        val existingIndex = Natives.GlucoseMeterHasIndex(displayName)
        val index = if (existingIndex >= 0) existingIndex else Natives.GlucoseMeterGetIndex(displayName)
        if (index < 0) return -1
        val address = runCatching { device.address }.getOrNull() ?: return -1
        if (!Natives.GlucoseMeterSetDeviceAddress(index, address)) return -1
        if (existingIndex < 0) {
            // A new meter may safely offer its stored history. Native timestamp gating prevents replay.
            Natives.GlucoseMeterSetLastTime(index, 0L)
        } else {
            Natives.GlucoseMeterSetActive(index, true)
        }
        BluetoothGlucoseMeter.addDevice(index, device)
        return index
    }

    private fun snapshot(index: Int): GlucoseMeterSnapshot? {
        val name = Natives.GlucoseMeterDeviceName(index)?.takeIf(String::isNotBlank) ?: return null
        // The native table holds a row per supported meter model, and the name
        // is on that row whether the meter was ever added or not, so a stored
        // device address is what actually makes one configured. Filtering on the
        // name alone lists every known model and leaves forget() with no row to
        // remove. An active row still counts without one: the legacy meter list
        // enables a meter before the scanner has found it, and hiding that row
        // would leave no way to switch it off here.
        val address = Natives.GlucoseMeterDeviceAddress(index)?.takeIf { it.isNotBlank() }
        if (address == null && !Natives.GlucoseMeterGetActive(index)) return null
        val gatt = BluetoothGlucoseMeter.getExistingGatt(index)
        return GlucoseMeterSnapshot(
            index = index,
            name = name,
            address = address,
            active = Natives.GlucoseMeterGetActive(index),
            connected = gatt?.connected == true,
            lastReadingAt = Natives.GlucoseMeterGetLastTime(index),
        )
    }
}

/** Persists only the value and timestamp produced by the native meter decoder. */
@Keep
object GlucoseMeterJournalBridge {
    private const val LOG_ID = "GlucoseMeterJournal"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun record(meterIndex: Int, timestampMillis: Long, mgdlTenths: Long) {
        val now = System.currentTimeMillis()
        val decoded = MeterJournalPolicy.decode(meterIndex, timestampMillis, mgdlTenths, now)
        if (decoded == null) {
            Log.e(LOG_ID, "Rejected invalid decoded meter reading")
            return
        }
        val meterName = Natives.GlucoseMeterDeviceName(meterIndex)?.takeIf(String::isNotBlank)
        scope.launch {
            runCatching {
                JournalRepository().upsertEntry(
                    JournalEntryInput(
                        timestamp = decoded.timestampMillis,
                        type = JournalEntryType.FINGERSTICK,
                        title = Applic.app.getString(R.string.journal_type_fingerstick),
                        note = meterName,
                        glucoseValueMgDl = decoded.glucoseMgDl,
                        source = JournalEntrySource.METER,
                        sourceRecordId = decoded.sourceRecordId,
                    )
                )
                UiRefreshBus.requestDataRefresh()
                if (Natives.getpostTreatments()) Natives.wakeuploader()
            }.onFailure { error ->
                Log.e(LOG_ID, "Failed to journal meter reading: ${Log.stackline(error)}")
            }
        }
    }
}

internal data class DecodedMeterJournalRecord(
    val timestampMillis: Long,
    val glucoseMgDl: Float,
    val sourceRecordId: String,
)

internal object MeterJournalPolicy {
    private const val MAX_FUTURE_MILLIS = 24 * 60 * 60 * 1000L

    fun decode(
        meterIndex: Int,
        timestampMillis: Long,
        mgdlTenths: Long,
        nowMillis: Long,
    ): DecodedMeterJournalRecord? {
        if (meterIndex < 0 || timestampMillis <= 0L || timestampMillis > nowMillis + MAX_FUTURE_MILLIS) {
            return null
        }
        if (mgdlTenths !in 1L..20_000L) return null
        return DecodedMeterJournalRecord(
            timestampMillis = timestampMillis,
            glucoseMgDl = mgdlTenths.toFloat() / 10f,
            sourceRecordId = "meter:$meterIndex:$timestampMillis",
        )
    }
}
