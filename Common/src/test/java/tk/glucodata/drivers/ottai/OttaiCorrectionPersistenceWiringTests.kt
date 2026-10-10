package tk.glucodata.drivers.ottai

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiCorrectionPersistenceWiringTests {

    @Test
    fun provisionalCorrectionUsesAtomicRetimeWithoutAUserDeletion() {
        val manager = source("Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt")
        val storage = manager.substringAfter("private fun storeDecodedReadings(")
            .substringBefore("private fun mirrorHistoryIntoNative(")
        val repository = source("Common/src/mobile/java/tk/glucodata/data/HistoryRepository.kt")
        val replacement = repository.substringAfter("suspend fun replaceProvisionalHistory(")
            .substringBefore("Drops recorded main values")

        assertTrue(manager.contains("replaceProvisionalHistoryAsync("))
        assertTrue(manager.contains("correctsProvisionalHistory = currentDecision.replacesProvisionalTail || provisionalAnchorShifted"))
        assertFalse(storage.contains("deleteReadingsForSensorAfter"))
        assertTrue(replacement.contains("deleteSensorReadingsAtTimestamps("))
        assertFalse(replacement.contains("insertDeletedReadings("))
    }

    @Test
    fun repairRetimesTheWholeProvisionalBatchAndAddsTheNewerLiveRecord() {
        val minute = 60_000L
        val oldTail = 1_800_120_000_000L
        val repair = buildOttaiProvisionalHistoryRepair(
            provisional = listOf(
                OttaiProvisionalHistoryPoint(40, oldTail - 2 * minute, 100f),
                OttaiProvisionalHistoryPoint(41, oldTail - minute, 101f),
            ),
            correctedDataNo = 42,
            provisionalTimestampMs = oldTail,
            correctedTimestampMs = oldTail - minute,
            correctedMgdl = 103f,
        )!!

        assertArrayEquals(
            longArrayOf(oldTail - 2 * minute, oldTail - minute, oldTail),
            repair.provisionalTimestamps,
        )
        assertArrayEquals(
            longArrayOf(oldTail - 3 * minute, oldTail - 2 * minute, oldTail - minute),
            repair.correctedTimestamps,
        )
        assertArrayEquals(floatArrayOf(100f, 101f, 103f), repair.valuesMgdl, 0f)
    }

    @Test
    fun deferredNativeReconciliationRunsFromRoomCompletion() {
        val manager = source("Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt")
        val storage = manager.substringAfter("private fun storeDecodedReadings(")
            .substringBefore("private fun mirrorHistoryIntoNative(")

        assertTrue(storage.contains("storeSensorHistoryBatchWithCompletionAsync("))
        assertTrue(storage.contains("onProvisionalHistoryWriteFinished(id, stored)"))
        assertTrue(manager.contains("requestNativeReconcileAfterRoomWrite(id)"))
    }

    @Test
    fun repositoryCompletionRunsAfterTheRequestedRoomOperation() {
        val repository = source("Common/src/mobile/java/tk/glucodata/data/HistoryRepository.kt")
        val batch = repository.substringAfter("fun storeHistoryBatchWithCompletionAsync(")
            .substringBefore("fun storeHistoryBatchWithSourceAsync(")
        val replacement = repository.substringAfter("fun replaceProvisionalHistoryAsync(")
            .substringBefore("fun storeHistoryBatchWithSourceAsync(")

        assertTrue(batch.indexOf("storeHistoryBatchWithSourceBlocking(") in 0 until batch.indexOf("completion.complete(stored)"))
        assertTrue(replacement.indexOf("HistoryRepository().replaceProvisionalHistory(") in
            0 until replacement.lastIndexOf("completion.complete(stored)"))
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
