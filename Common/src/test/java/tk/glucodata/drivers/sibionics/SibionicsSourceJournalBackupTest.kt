package tk.glucodata.drivers.sibionics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsSourceJournalBackupTest {
    private val digest = "0123456789abcdef01234567"
    private val relativePath = "sibionics-managed/$digest/source-samples-v1.bin"

    @Test
    fun collectsOnlyNonEmptyJournalsUnderTheManagedRoot() {
        val filesDir = Files.createTempDirectory("sibionics-backup").toFile()
        try {
            SibionicsSampleJournal(File(filesDir, relativePath)).appendAll(listOf(sample(1), sample(2)))
            File(filesDir, "sibionics-managed/ffffffffffffffffffffffff").mkdirs()
            File(filesDir, "sibionics-managed/ffffffffffffffffffffffff/source-samples-v1.bin").writeBytes(ByteArray(0))
            File(filesDir, "settings.dat").writeBytes(byteArrayOf(1))

            val collected = SibionicsSourceJournalBackup.collect(filesDir)

            assertEquals(setOf(relativePath), collected.keys)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun onlyTheDriversOwnLayoutIsARestorablePath() {
        assertTrue(SibionicsSourceJournalBackup.isJournalPath(relativePath))
        assertFalse(SibionicsSourceJournalBackup.isJournalPath("../shared_prefs/x.xml"))
        assertFalse(SibionicsSourceJournalBackup.isJournalPath("sibionics-managed/../settings.dat"))
        assertFalse(SibionicsSourceJournalBackup.isJournalPath("sibionics-managed/$digest/other.bin"))
        assertFalse(SibionicsSourceJournalBackup.isJournalPath("sibionics-managed/ZZ/source-samples-v1.bin"))
    }

    @Test
    fun restoreMergesWithoutOverwritingSamplesTheSensorDeliveredSince() {
        val exportedFrom = Files.createTempDirectory("sibionics-backup-src").toFile()
        val filesDir = Files.createTempDirectory("sibionics-backup-dst").toFile()
        try {
            SibionicsSampleJournal(File(exportedFrom, relativePath))
                .appendAll(listOf(sample(1, 5.1f), sample(2, 5.2f), sample(3, 5.3f)))
            val bytes = File(exportedFrom, relativePath).readBytes()
            val local = SibionicsSampleJournal(File(filesDir, relativePath))
            local.appendAll(listOf(sample(3, 9.9f), sample(4, 5.4f)))

            val imported = SibionicsSourceJournalBackup.restore(filesDir, relativePath, bytes)

            assertEquals(2, imported)
            val merged = SibionicsSampleJournal(File(filesDir, relativePath)).snapshot()
            assertEquals(listOf(1, 2, 3, 4), merged.map { it.index })
            assertEquals(9.9f, merged[2].rawMmol, 0.001f)
            assertFalse(File(filesDir, "sibionics-managed/$digest/source-samples-v1.bin.import").exists())
        } finally {
            exportedFrom.deleteRecursively()
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun restoreIntoAnEmptyAppRecreatesTheJournalWhole() {
        val exportedFrom = Files.createTempDirectory("sibionics-backup-src").toFile()
        val filesDir = Files.createTempDirectory("sibionics-backup-dst").toFile()
        try {
            SibionicsSampleJournal(File(exportedFrom, relativePath)).appendAll((1..50).map { sample(it) })
            val bytes = File(exportedFrom, relativePath).readBytes()

            assertEquals(50, SibionicsSourceJournalBackup.restore(filesDir, relativePath, bytes))
            assertTrue(SibionicsSampleJournal(File(filesDir, relativePath)).hasContiguousBeginning())
        } finally {
            exportedFrom.deleteRecursively()
            filesDir.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun restoreRefusesPathsOutsideTheManagedRoot() {
        val filesDir = Files.createTempDirectory("sibionics-backup-dst").toFile()
        try {
            SibionicsSourceJournalBackup.restore(filesDir, "../evil.bin", byteArrayOf(1, 2, 3))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private fun sample(index: Int, raw: Float = 5f) = SibionicsSourceSample(
        index = index,
        timestampMs = 1_700_000_000_000L + index * 60_000L,
        rawMmol = raw,
        temperatureC = 34.5f,
        impedance = 1_000f,
        variantId = SibionicsConstants.Variant.CHINESE.ordinal,
    )
}
