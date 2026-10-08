package tk.glucodata.drivers.ottai

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiCorrectionPersistenceWiringTests {

    @Test
    fun provisionalCorrectionDeletesOnlyTheReplacedTimestampAsynchronously() {
        val manager = source("Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt")
        val storage = manager.substringAfter("private fun storeDecodedReadings(")
            .substringBefore("private fun mirrorHistoryIntoNative(")

        assertTrue(storage.contains("deleteReadingAsync(id, provisionalMs"))
        assertFalse(storage.contains("deleteReadingsForSensorAfter"))
    }

    @Test
    fun deferredNativeReconciliationRunsFromRoomCompletion() {
        val manager = source("Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt")
        val storage = manager.substringAfter("private fun storeDecodedReadings(")
            .substringBefore("private fun mirrorHistoryIntoNative(")

        assertTrue(storage.contains("storeSensorHistoryBatchWithCompletionAsync("))
        assertTrue(storage.contains("requestNativeReconcileAfterRoomWrite(id)"))
    }

    @Test
    fun repositoryCompletionRunsAfterTheRequestedRoomOperation() {
        val repository = source("Common/src/mobile/java/tk/glucodata/data/HistoryRepository.kt")
        val batch = repository.substringAfter("fun storeHistoryBatchWithCompletionAsync(")
            .substringBefore("fun storeHistoryBatchWithSourceAsync(")
        val deletion = repository.substringAfter("fun deleteReadingAsync(")
            .substringBefore("Blocking version for Notify.java")

        assertTrue(batch.indexOf("storeHistoryBatchWithSourceBlocking(") in 0 until batch.indexOf("completion.run()"))
        assertTrue(deletion.indexOf("HistoryRepository().deleteReading(timestamp, sensorSerial)") in
            0 until deletion.lastIndexOf("completion.run()"))
    }

    @Test
    fun publicationWatermarkDoesNotCommitOnGattCallback() {
        val registry = source("Common/src/main/java/tk/glucodata/drivers/ottai/OttaiRegistry.kt")
        val save = registry.substringAfter("fun saveLastPublishedGlucoseAtMs")
            .substringBefore("The sensor's learned BLE record layout")

        assertTrue(save.contains(".apply()"))
        assertFalse(save.contains(".commit()"))
    }

    private fun source(relative: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate $relative")
    }
}
