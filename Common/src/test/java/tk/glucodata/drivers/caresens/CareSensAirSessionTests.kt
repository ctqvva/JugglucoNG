package tk.glucodata.drivers.caresens

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tk.glucodata.drivers.caresens.CareSensAirSession.Arrival

class CareSensAirSessionTests {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun restartReplaysTheSameFilterAndAFailedAheadRequestDoesNotMoveTheCursor() {
        val original = journal()
        val config = config()
        original.saveConfig(config)
        val first = CareSensAirSession(original, config, statefulEngine())

        assertEquals(Arrival.Gap, first.receive(record(2), now))
        assertEquals(0, first.cursor)
        val one = accepted(first.receive(record(1), now))
        assertEquals(Arrival.Duplicate, first.receive(record(1), now))
        val two = accepted(first.receive(record(2), now))
        assertEquals(2, first.cursor)
        assertEquals(listOf(one, two), reopened(original).recover())
        val three = accepted(first.receive(record(3), now))
        assertEquals(listOf(one, two, three), reopened(original).recover())
    }

    @Test
    fun nativeDeviceErrorIsJournaledWithoutCallingTheAlgorithm() {
        val journal = journal()
        val config = config()
        journal.saveConfig(config)
        val session = CareSensAirSession(journal, config, statefulEngine())
        val error = session.receive(record(1, deviceError = -2), now) as Arrival.Accepted
        assertNull(error.computed)
        assertEquals(1, session.cursor)
        val next = accepted(session.receive(record(2), now))
        assertEquals(101.0, next.output.glucoseMgdl, 0.0)
        assertEquals(listOf(next), reopened(journal).recover())
    }

    @Test
    fun interruptedAppendIsRetriedButCorruptCompleteRecordFailsClosed() {
        val directory = temporary.newFolder()
        val journal = CareSensAirJournal(directory, "engine-a", SENSOR)
        val config = config()
        journal.saveConfig(config)
        val session = CareSensAirSession(journal, config, statefulEngine())
        val first = accepted(session.receive(record(1), now))
        val records = File(directory, "records.v1")
        records.appendBytes(ByteArray(84))
        val restored = reopened(journal)
        assertEquals(listOf(first), restored.recover())
        assertEquals(1, restored.cursor)
        accepted(restored.receive(record(2), now))

        RandomAccessFile(records, "rw").use { file ->
            val offset = file.length() - 1
            file.seek(offset)
            val damaged = file.readUnsignedByte().xor(1)
            file.seek(offset)
            file.write(damaged)
        }
        assertThrows(IllegalArgumentException::class.java) { reopened(journal).recover() }
        assertThrows(IllegalArgumentException::class.java) {
            CareSensAirJournal(directory, "engine-b", SENSOR).loadConfig()
        }
        assertThrows(IllegalArgumentException::class.java) {
            CareSensAirJournal(directory, "engine-a", "different").loadConfig()
        }
    }

    @Test
    fun invalidClockRepairCanOnlyEnterHistory() {
        val journal = journal()
        val config = config()
        journal.saveConfig(config)
        val session = CareSensAirSession(journal, config, statefulEngine())
        assertEquals(Arrival.Invalid, session.receive(record(1, timestamp = 0), start + 360))
        assertEquals(0, session.cursor)
        val reading = accepted(session.receive(record(1, timestamp = 0), now))
        assertFalse(reading.hasSensorTime)
        assertEquals(start + 300, reading.output.timestampSeconds)
        assertEquals(listOf(reading), reopened(journal).recover())
    }

    private fun journal() = CareSensAirJournal(temporary.newFolder(), "engine-a", SENSOR)

    private fun reopened(journal: CareSensAirJournal) =
        CareSensAirSession(journal, checkNotNull(journal.loadConfig()), statefulEngine())

    private fun statefulEngine(): CareSensAirEngine {
        var samplesProcessed = 0
        return CareSensAirEngine { seq, time, _, _ ->
            CareSensAirOutput(seq, time, 100.0 + ++samplesProcessed, 0.0, 0, 1)
        }
    }

    private fun accepted(result: Arrival) = checkNotNull((result as Arrival.Accepted).computed)

    private fun config(): CareSensAirJournal.Config {
        val packed = ByteArray(CareSensAirFactoryConfig.PACKED_BYTES)
        packed.littleEndian().putInt(CareSensAirFactoryConfig.START_OFFSET, start.toInt())
        return CareSensAirJournal.Config(packed, ByteArray(0), IntArray(0))
    }

    private fun record(sequence: Int, timestamp: Long = start + sequence * 300, deviceError: Int = 0): ByteArray {
        val raw = ByteArray(84)
        raw.littleEndian().put(0, 0xC5.toByte()).put(1, 1).put(2, deviceError.toByte())
            .putInt(12, sequence).putInt(16, timestamp.toInt()).putShort(22, 3700)
        return raw
    }

    private companion object {
        const val start = 1_709_726_400L
        const val now = start + 3_600
        const val SENSOR = "synthetic"
    }
}
