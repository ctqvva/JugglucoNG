package tk.glucodata.drivers.sibionics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsSampleJournalTest {
    @Test
    fun reloadsLatestSampleForEachIndexInOrder() {
        val directory = Files.createTempDirectory("sibionics-journal").toFile()
        try {
            val file = File(directory, "samples.dat")
            val journal = SibionicsSampleJournal(file)
            journal.append(sample(index = 1, raw = 5.1f))
            journal.append(sample(index = 2, raw = 5.2f))
            journal.append(sample(index = 1, raw = 6.1f))

            val restored = SibionicsSampleJournal(file).snapshot()

            assertEquals(listOf(1, 2), restored.map { it.index })
            assertEquals(6.1f, restored[0].rawMmol, 0.001f)
            assertEquals(1_700_000_060_000L, restored[0].timestampMs)
            assertTrue(SibionicsSampleJournal(file).hasContiguousBeginning())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun detectsMissingBeginningOrMiddle() {
        val directory = Files.createTempDirectory("sibionics-journal-gap").toFile()
        try {
            val file = File(directory, "samples.dat")
            val journal = SibionicsSampleJournal(file)
            journal.appendAll(listOf(sample(2), sample(3)))
            assertFalse(journal.hasContiguousBeginning())

            journal.clear()
            journal.appendAll(listOf(sample(1), sample(3)))
            assertFalse(journal.hasContiguousBeginning())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ignoresTruncatedTailAndKeepsCompleteRecords() {
        val directory = Files.createTempDirectory("sibionics-journal-tail").toFile()
        try {
            val file = File(directory, "samples.dat")
            SibionicsSampleJournal(file).appendAll(listOf(sample(0), sample(1)))
            file.appendBytes(byteArrayOf(1, 2, 3))

            assertEquals(listOf(0, 1), SibionicsSampleJournal(file).snapshot().map { it.index })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun firstMissingIndexFindsTheHoleBelowTheCursor() {
        val directory = Files.createTempDirectory("sibionics-journal-missing").toFile()
        try {
            val journal = SibionicsSampleJournal(File(directory, "samples.dat"))
            assertEquals(1, journal.firstMissingIndex(1, 10))
            journal.appendAll(listOf(sample(1), sample(2), sample(3), sample(6), sample(7)))
            assertEquals(4, journal.firstMissingIndex(1, 8))
            assertEquals(4, journal.firstMissingIndex(4, 8))
            assertEquals(5, journal.firstMissingIndex(5, 8))
            assertEquals(null, journal.firstMissingIndex(6, 8))
            assertEquals(null, journal.firstMissingIndex(1, 4))
            assertEquals(null, journal.firstMissingIndex(0, 4))
            journal.appendAll(listOf(sample(4), sample(5)))
            assertEquals(null, journal.firstMissingIndex(1, 8))
            assertEquals(8, journal.firstMissingIndex(1, 9))
        } finally {
            directory.deleteRecursively()
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
