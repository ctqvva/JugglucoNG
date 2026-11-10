package tk.glucodata.drivers.aidex

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiDexSampleJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val start = 1_790_000_000_000L
    private fun sample(i: Int, origin: Int = AiDexSourceSample.LIVE, factory: Float = 100f) =
        AiDexSourceSample(start + i * 60_000, start, factory, 80f, Float.NaN, origin)

    @Test fun `factory raw temperature and activation survive recreation exactly`() {
        val file = File(temporary.newFolder(), "source.bin")
        val journal = AiDexSampleJournal(file)
        val values = (0..200).map { sample(it) }
        assertEquals(201, journal.appendAll(values))
        assertEquals(values, AiDexSampleJournal(file).snapshot())
    }

    @Test fun `broadcast and imported rendered values cannot replace authoritative factory inputs`() {
        val journal = AiDexSampleJournal(File(temporary.newFolder(), "source.bin"))
        journal.appendAll(listOf(sample(0)))
        assertEquals(0, journal.appendAll(listOf(sample(0, AiDexSourceSample.BOOTSTRAP, 180f), sample(0, AiDexSourceSample.BROADCAST, 190f))))
        assertEquals(100f, journal.snapshot().single().factoryMgDl, 0f)
    }

    @Test fun `matched raw history upgrades a rawless broadcast`() {
        val journal = AiDexSampleJournal(File(temporary.newFolder(), "source.bin"))
        journal.appendAll(listOf(sample(0, AiDexSourceSample.BROADCAST).copy(rawMgDl = Float.NaN)))
        journal.appendAll(listOf(sample(0, AiDexSourceSample.HISTORY)))
        assertEquals(80f, journal.snapshot().single().rawMgDl, 0f)
    }

    @Test fun `torn unpublished append preserves all complete source records`() {
        val file = File(temporary.newFolder(), "source.bin")
        val journal = AiDexSampleJournal(file)
        journal.appendAll(listOf(sample(0)))
        val validLength = file.length()
        file.appendBytes(byteArrayOf(1, 2, 3))
        assertEquals(listOf(sample(0)), AiDexSampleJournal(file).snapshot())
        assertEquals(validLength, file.length())
    }

    @Test fun `restored revisions fold exactly as latestRevisions does`() {
        val folder = temporary.newFolder()
        val broadcast = sample(3, AiDexSourceSample.BROADCAST, 100f)
        val history = sample(3, AiDexSourceSample.HISTORY, 104f)
        val laterBroadcast = sample(3, AiDexSourceSample.BROADCAST, 101f)
        fun bytesOf(name: String, vararg rows: AiDexSourceSample) =
            AiDexSampleJournal(File(folder, name)).apply { rows.forEach { appendAll(listOf(it)) } }.bytes()
        // An export can carry several revisions of one minute, in append order.
        val bytes = bytesOf("a.bin", broadcast, history) + bytesOf("b.bin", laterBroadcast)
        assertEquals(3, AiDexSampleJournal.decode(bytes).size)
        val root = temporary.newFolder()
        val path = AiDexSourceJournalBackup.file(root, "X-test").relativeTo(root).invariantSeparatorsPath
        AiDexSourceJournalBackup.restore(root, path, bytes)
        val restored = AiDexSampleJournal(File(root, path)).snapshot()
        assertEquals(AiDexSampleJournal.latestRevisions(AiDexSampleJournal.decode(bytes)).values.toList(), restored)
        assertEquals(history, restored.single())
    }

    @Test fun `full record corruption is moved aside and never stops later appends`() {
        val folder = temporary.newFolder()
        val file = File(folder, "source.bin")
        AiDexSampleJournal(file).appendAll(listOf(sample(0)))
        val bytes = file.readBytes().apply { this[20] = (this[20].toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        val journal = AiDexSampleJournal(file)
        var reported: File? = null
        journal.quarantineListener = { moved, _ -> reported = moved }
        // Nothing corrupt is decoded as factory input, and the evidence is kept.
        assertEquals(emptyList<AiDexSourceSample>(), journal.snapshot())
        assertArrayEquals(bytes, reported!!.readBytes())
        assertTrue(reported!!.name.startsWith("source.bin.corrupt-"))
        assertEquals(1, journal.appendAll(listOf(sample(1))))
        assertEquals(listOf(sample(1)), AiDexSampleJournal(file).snapshot())
    }

    @Test fun `backup restores originals while local inputs win conflicts`() {
        val first = temporary.newFolder()
        val journal = AiDexSampleJournal(AiDexSourceJournalBackup.file(first, "X-test"))
        journal.appendAll((0..10).map { sample(it) })
        val exported = AiDexSourceJournalBackup.collect(first).entries.single()
        val second = temporary.newFolder()
        val local = AiDexSampleJournal(AiDexSourceJournalBackup.file(second, "X-test"))
        local.appendAll(listOf(sample(0, factory = 110f)))
        assertEquals(10, AiDexSourceJournalBackup.restore(second, exported.key, exported.value))
        assertEquals(11, local.snapshot().size)
        assertEquals(110f, local.snapshot().first().factoryMgDl, 0f)
        assertEquals(0, AiDexSourceJournalBackup.restore(second, exported.key, exported.value))
    }

    @Test fun `backup rejects traversal foreign names incomplete and corrupt data before writing`() {
        val root = temporary.newFolder()
        val file = AiDexSourceJournalBackup.file(root, "X-test")
        AiDexSampleJournal(file).appendAll(listOf(sample(0)))
        val exported = AiDexSourceJournalBackup.collect(root).entries.single()
        for (path in listOf("../outside", exported.key.replace(".bin", "xbin"), exported.key.replace("source-samples-v1", "checkpoint"))) {
            assertFalse(AiDexSourceJournalBackup.isJournalPath(path))
            assertThrows(IllegalArgumentException::class.java) { AiDexSourceJournalBackup.restore(root, path, exported.value) }
        }
        assertThrows(IllegalArgumentException::class.java) { AiDexSourceJournalBackup.restore(root, exported.key, exported.value.copyOf(10)) }
    }

    @Test fun `compaction retains highest quality original for every minute`() {
        val file = File(temporary.newFolder(), "source.bin")
        val journal = AiDexSampleJournal(file)
        repeat(400) { journal.appendAll(listOf(sample(0, factory = 100f + it % 2))) }
        assertEquals(listOf(sample(0, factory = 101f)), AiDexSampleJournal(file).snapshot())
        assertTrue(file.length() < 44 * 260)
    }
}
