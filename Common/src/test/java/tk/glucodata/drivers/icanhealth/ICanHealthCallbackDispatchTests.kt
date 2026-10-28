package tk.glucodata.drivers.icanhealth

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard the callback/Handler boundary without constructing the Android/native BLE manager.
 * Moving all auth replies to Handler lets handshake enqueues race Bluetooth read/write
 * completions on the unsynchronized GATT queue. This checks wiring, not device scheduling.
 */
class ICanHealthCallbackDispatchTests {
    private val manager by lazy {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var source: File? = null
        while (directory != null) {
            val candidate = File(directory, "Common/src/main/java/tk/glucodata/drivers/icanhealth/ICanHealthBleManager.kt")
            if (candidate.isFile) {
                source = candidate
                break
            }
            directory = directory.parentFile
        }
        requireNotNull(source) { "ICanHealthBleManager.kt not found" }.readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
            .replace(Regex("\\s+"), " ")
    }

    @Test fun vendorAuthRunsInlineAfterRejectingStaleGatt() {
        val callback = manager.substringAfter("override fun onCharacteristicChanged(")
            .substringBefore("private fun shortUuid(")
        assertTrue(callback.contains(
            "{ if (gatt !== mBluetoothGatt) return val data = characteristic.value ?: return",
        ))
        assertTrue(callback.contains("ICanHealthConstants.CGM_SPECIFIC_OPS -> handleVendorAuthResponse(data)"))
        assertFalse("Auth must not be dispatched separately from Bluetooth completions", callback.contains("handler.post"))
    }

    @Test fun f2AcknowledgementsStillPostOnlyCurrentChainBookkeeping() {
        val acknowledgement = manager.substringAfter("ICanHealthParser.parseCorrectCodeResult(data) != null -> {")
            .substringBefore("ICanHealthParser.parseStartSensorResult(data) != null -> {")
        assertTrue(acknowledgement.contains("handler.post {"))
        assertTrue(acknowledgement.contains("owner !== mBluetoothGatt"))
        assertTrue(acknowledgement.contains("correctCodePendingChunk !== pending) return@post"))
        assertTrue(acknowledgement.contains("if (result == 1) onCorrectCodeAck() else {"))
        assertTrue(acknowledgement.contains("resetCorrectCodeState()"))
    }

    @Test fun cachedSetupProbesStatusWithoutGoingThroughBackfill() {
        val setup = manager.substringAfter("private fun startPostAuthSetup(reason: String)")
            .substringBefore("private fun resolveAutomaticGlucoseHistoryStartSequence()")
        assertTrue(setup.contains("repairState.statusProbeDue(SystemClock.elapsedRealtime())"))
        assertTrue(setup.contains("enqueueOp(GattOp.Read(ICanHealthConstants.CGM_STATUS))"))
        assertFalse(setup.contains("shouldRequestAuthenticatedHistoryBackfill"))
        assertFalse(setup.contains("suppressAutomaticHistoryBackfill"))
        assertFalse(setup.contains("handler.post"))
    }

    @Test fun repairAttemptsBelongToAcceptedFirstChunkDispatchNotScheduling() {
        val schedule = manager.substringAfter("private fun scheduleCorrectCodeWrite(")
            .substringBefore("private fun scheduleCorrectCodeRecheck()")
        assertFalse(schedule.contains("repairState.startWrite"))
        val dispatch = manager.substringAfter("private fun drainGattQueue()")
            .substringBefore("private fun reconnectAlarmPendingIntent(")
        assertTrue(dispatch.contains("correctCodeChunks === op.correctCodeOwner"))
        assertTrue(dispatch.contains("op.correctCodeOwner != null && op.correctCodeIndex == 0"))
        assertTrue(dispatch.contains("repairState.startWrite("))
        assertTrue(dispatch.contains(
            "nowMs = SystemClock.elapsedRealtime(), ) { gatt.writeCharacteristic(char) }",
        ))
    }
}
