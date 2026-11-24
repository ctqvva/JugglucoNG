package tk.glucodata.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.Applic
import tk.glucodata.HistoryWriteOutcome
import tk.glucodata.SensorIdentity
import tk.glucodata.data.HistoryRepository.BucketReplaceOutcome

/** A batch handed to the store is stored, or somebody hears that it was not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HistoryBatchWriterTests.TestApplication::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HistoryBatchWriterTests {
    class TestApplication : Applic() {
        override fun onCreate() = Unit
    }

    @get:Rule val folder = TemporaryFolder()

    private lateinit var database: HistoryDatabase
    private lateinit var repository: HistoryRepository
    private lateinit var journalFile: File
    private var previousApp: Applic? = null
    private val scopes = ArrayList<CoroutineScope>()
    private val serial = "X-000000TEST"
    private val base = 1_700_000_040_000L

    @Volatile private var databaseBroken = false
    private val attempts = AtomicInteger()

    @Before
    fun setUp() {
        previousApp = Applic.app
        Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
        SensorIdentity.invalidateCaches()
        database = Room.inMemoryDatabaseBuilder(Applic.app, HistoryDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = HistoryRepository(database)
        journalFile = File(folder.root, "pending/history.log")
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        if (::database.isInitialized) database.close()
        Applic.app = previousApp
        SensorIdentity.invalidateCaches()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    private fun writer(
        scope: CoroutineScope = newScope(),
        retryDelaysMs: LongArray = longArrayOf(10L),
        maxAttempts: Int = 1_000,
        maxRetained: Int = 45_000,
        maxBuffered: Int = 512,
        onStored: suspend (HistoryBatchWriter.Stored) -> Unit = {},
        store: suspend (String, List<HistoryReading>) -> HistoryBatchWriter.Stored = ::storeInRoom,
    ) = HistoryBatchWriter(
        scope = scope,
        openJournal = { HistoryBatchJournal(journalFile) },
        coalesceMs = 10L,
        retryDelaysMs = retryDelaysMs,
        maxAttempts = maxAttempts,
        maxRetainedPerSensor = maxRetained,
        maxBuffered = maxBuffered,
        store = store,
        onStored = onStored,
    )

    private suspend fun storeInRoom(sensor: String, readings: List<HistoryReading>): HistoryBatchWriter.Stored {
        attempts.incrementAndGet()
        if (databaseBroken) return HistoryBatchWriter.Stored(BucketReplaceOutcome.FAILED)
        return repository.writeBuckets(sensor, readings, 60_000L)
    }

    private fun reading(minute: Int, value: Float = 100f, secondMs: Long = 0L, sensor: String = serial) = HistoryReading(
        timestamp = base + minute * 60_000L + secondMs,
        sensorSerial = sensor,
        value = value,
        rawValue = 0f,
        rate = null,
    )

    private fun rows(sensor: String = serial): Map<Int, Float> = runBlocking {
        database.historyDao().getSensorReadingsInTimeRange(sensor, 0L, Long.MAX_VALUE)
            .associate { ((it.timestamp - base) / 60_000L).toInt() to it.value }
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5L)
        }
        throw AssertionError("never: $what")
    }

    // ---- database failure ----

    @Test
    fun aFailedBatchIsKeptAndWrittenOnceTheDatabaseRecovers() {
        databaseBroken = true
        val outcomes = CopyOnWriteArrayList<HistoryWriteOutcome>()
        val writer = writer()
        assertEquals(HistoryWriteOutcome.QUEUED, writer.submit(serial, listOf(reading(0), reading(1)), outcomes::add))
        eventually("three failed attempts") { attempts.get() >= 3 }
        assertTrue("a queued batch is not a stored one", outcomes.isEmpty())
        assertEquals(2, writer.pendingCount(serial))

        databaseBroken = false
        eventually("stored") { outcomes.isNotEmpty() }
        assertEquals(listOf(HistoryWriteOutcome.PERSISTED), outcomes.toList())
        assertEquals(mapOf(0 to 100f, 1 to 100f), rows())
        eventually("nothing left to replay") { !journalFile.exists() }
    }

    @Test
    fun aThrowingStoreIsAFailedWrite() {
        val writer = writer(store = { sensor, readings ->
            if (attempts.incrementAndGet() == 1) error("no such database")
            repository.writeBuckets(sensor, readings, 60_000L)
        })
        writer.submit(serial, listOf(reading(0)))
        eventually("stored") { rows() == mapOf(0 to 100f) }
    }

    @Test
    fun retriesAreBoundedAndTheCallerHearsOfTheLoss() {
        databaseBroken = true
        val outcomes = CopyOnWriteArrayList<HistoryWriteOutcome>()
        val writer = writer(maxAttempts = 4)
        writer.submit(serial, listOf(reading(0)), outcomes::add)
        eventually("given up") { outcomes.isNotEmpty() }
        assertEquals(listOf(HistoryWriteOutcome.FAILED), outcomes.toList())
        assertEquals(4, attempts.get())
        Thread.sleep(100L)
        assertEquals("nothing spins once it has given up", 4, attempts.get())
        assertFalse(journalFile.exists())
    }

    @Test
    fun retriesBackOffWhileNewBatchesKeepArriving() {
        databaseBroken = true
        val writer = writer(retryDelaysMs = longArrayOf(60_000L))
        writer.submit(serial, listOf(reading(0)))
        eventually("first attempt") { attempts.get() == 1 }
        repeat(50) { writer.submit(serial, listOf(reading(it + 1))) }
        Thread.sleep(150L)
        assertEquals("traffic does not turn the back-off into a spin", 1, attempts.get())
        assertEquals(51, writer.pendingCount(serial))
    }

    @Test
    fun trafficDuringAFailingWriteDoesNotSkipTheBackOff() {
        val writing = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val writer = writer(retryDelaysMs = longArrayOf(60_000L), maxBuffered = 2, store = { _, _ ->
            attempts.incrementAndGet()
            writing.complete(Unit)
            proceed.await()
            HistoryBatchWriter.Stored(BucketReplaceOutcome.FAILED)
        })
        writer.submit(serial, listOf(reading(0)))
        runBlocking { writing.await() }
        // Enough to ask for an immediate write, while this one is still going to fail.
        writer.submit(serial, listOf(reading(1), reading(2), reading(3)))
        proceed.complete(Unit)
        Thread.sleep(200L)
        assertEquals(1, attempts.get())
    }

    @Test
    fun aChunkThatNeverStoresDoesNotHoldUpTheRest() {
        val poison = base
        val writer = writer(store = { sensor, readings ->
            if (readings.any { it.timestamp == poison }) HistoryBatchWriter.Stored(BucketReplaceOutcome.FAILED)
            else repository.writeBuckets(sensor, readings, 60_000L)
        })
        // Two write chunks; the first holds the reading that cannot be stored.
        writer.submit(serial, (0 until 1_500).map { reading(it) })
        eventually("second chunk stored") { rows().size == 500 }
        assertEquals(1_000, writer.pendingCount(serial))
    }

    @Test
    fun onlyTheOldestAreGivenUpWhenTooManyWait() {
        databaseBroken = true
        val outcomes = CopyOnWriteArrayList<HistoryWriteOutcome>()
        val writer = writer(maxRetained = 3)
        writer.submit(serial, listOf(reading(0)), outcomes::add)
        writer.submit(serial, listOf(reading(1), reading(2), reading(3)))
        eventually("told") { outcomes.isNotEmpty() }
        assertEquals(listOf(HistoryWriteOutcome.FAILED), outcomes.toList())
        databaseBroken = false
        eventually("rest stored") { rows().keys == setOf(1, 2, 3) }
    }

    // ---- cancellation ----

    @Test
    fun aCancelledWriteIsRetried() {
        val writer = writer(store = { sensor, readings ->
            if (attempts.incrementAndGet() == 1) throw CancellationException("transaction cancelled")
            repository.writeBuckets(sensor, readings, 60_000L)
        })
        writer.submit(serial, listOf(reading(0)))
        eventually("stored") { rows() == mapOf(0 to 100f) }
    }

    @Test
    fun cancellingTheWriterMidWriteLosesNothing() {
        val writing = CompletableDeferred<Unit>()
        val scope = newScope()
        val writer = writer(scope, store = { _, _ ->
            writing.complete(Unit)
            CompletableDeferred<HistoryBatchWriter.Stored>().await()
        })
        writer.submit(serial, listOf(reading(0), reading(1)))
        runBlocking { writing.await() }
        scope.cancel()
        assertEquals(2, writer.pendingCount(serial))

        writer().resume()
        eventually("stored by the next writer") { rows() == mapOf(0 to 100f, 1 to 100f) }
    }

    // ---- ordering ----

    @Test
    fun theLaterOfTwoOverlappingBatchesWins() {
        databaseBroken = true
        val outcomes = ConcurrentHashMap<String, HistoryWriteOutcome>()
        val writer = writer()
        // The earlier one is also the later second of its minute: submission order decides.
        writer.submit(serial, listOf(reading(0, 111f, secondMs = 40_000L), reading(1, 111f))) { outcomes["first"] = it }
        eventually("first failed") { attempts.get() >= 1 }
        writer.submit(serial, listOf(reading(0, 222f, secondMs = 5_000L))) { outcomes["second"] = it }
        databaseBroken = false
        eventually("both settled") { outcomes.size == 2 }
        assertEquals(mapOf(0 to 222f, 1 to 111f), rows())
        assertEquals(setOf(HistoryWriteOutcome.PERSISTED), outcomes.values.toSet())
    }

    @Test
    fun aBatchSubmittedWhileAnOlderOneIsBeingWrittenIsWrittenAfterIt() {
        val writing = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val outcomes = CopyOnWriteArrayList<String>()
        val writer = writer(store = { sensor, readings ->
            if (attempts.incrementAndGet() == 1) {
                writing.complete(Unit)
                proceed.await()
            }
            repository.writeBuckets(sensor, readings, 60_000L)
        })
        writer.submit(serial, listOf(reading(0, 111f))) { outcomes += "first=$it" }
        runBlocking { writing.await() }
        writer.submit(serial, listOf(reading(0, 222f))) { outcomes += "second=$it" }
        proceed.complete(Unit)
        eventually("settled") { outcomes.size == 2 }
        assertEquals(mapOf(0 to 222f), rows())
    }

    @Test
    fun aDirectWriteIsNotOverwrittenByAnOlderQueuedBatch() {
        databaseBroken = true
        val outcomes = CopyOnWriteArrayList<HistoryWriteOutcome>()
        val writer = writer()
        writer.submit(serial, listOf(reading(0, 111f), reading(1, 111f)), outcomes::add)
        eventually("failed") { attempts.get() >= 1 }
        runBlocking {
            val ticket = writer.ticket()
            writer.exclusive {
                repository.writeBuckets(serial, listOf(reading(0, 222f)), 60_000L)
                writer.supersede(serial, listOf(reading(0).timestamp), ticket)
            }
        }
        databaseBroken = false
        eventually("settled") { outcomes.isNotEmpty() }
        assertEquals(mapOf(0 to 222f, 1 to 111f), rows())
        assertEquals(listOf(HistoryWriteOutcome.PERSISTED), outcomes.toList())
    }

    @Test
    fun aDirectWriteWaitsForTheQueuedWriteInProgress() {
        val writing = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val writer = writer(store = { sensor, readings ->
            writing.complete(Unit)
            proceed.await()
            repository.writeBuckets(sensor, readings, 60_000L)
        })
        writer.submit(serial, listOf(reading(0, 111f)))
        runBlocking {
            writing.await()
            val direct = launch(Dispatchers.IO) {
                writer.exclusive { repository.writeBuckets(serial, listOf(reading(0, 222f)), 60_000L) }
            }
            proceed.complete(Unit)
            direct.join()
        }
        eventually("drained") { writer.pendingCount(serial) == 0 }
        assertEquals(mapOf(0 to 222f), rows())
    }

    @Test
    fun aDirectWriteLeavesALaterSubmissionAlone() {
        val writer = writer()
        runBlocking {
            val ticket = writer.ticket()
            writer.exclusive {
                repository.writeBuckets(serial, listOf(reading(0, 111f)), 60_000L)
                writer.submit(serial, listOf(reading(0, 222f)))
                writer.supersede(serial, listOf(reading(0).timestamp), ticket)
            }
        }
        eventually("later value stored") { rows() == mapOf(0 to 222f) }
    }

    @Test
    fun aLiveReadingOnlyOverridesBatchesThatHaveBeenHeldBack() {
        databaseBroken = true
        val writer = writer(retryDelaysMs = longArrayOf(60_000L))
        writer.submit(serial, listOf(reading(0)))
        eventually("failed") { attempts.get() == 1 && writer.pendingCount(serial) == 1 }
        writer.submit(serial, listOf(reading(1)))
        runBlocking {
            val ticket = writer.ticket()
            writer.exclusive {
                writer.supersede(
                    serial, listOf(reading(0).timestamp, reading(1).timestamp), ticket, retainedOnly = true,
                )
            }
        }
        assertEquals("the one still in its first wait stays", 1, writer.pendingCount(serial))
    }

    // ---- deletions ----

    @Test
    fun readingsTheUserDeletedAreSkippedNotRetried() {
        runBlocking {
            database.historyDao().insert(reading(0))
            repository.deleteReadingRows(listOf(serial), reading(0).timestamp)
        }
        val outcomes = ConcurrentHashMap<String, HistoryWriteOutcome>()
        val writer = writer()
        writer.submit(serial, listOf(reading(0))) { outcomes["deleted"] = it }
        eventually("settled") { outcomes.size == 1 }
        writer.submit(serial, listOf(reading(0), reading(1))) { outcomes["mixed"] = it }
        eventually("settled") { outcomes.size == 2 }
        assertEquals(HistoryWriteOutcome.SKIPPED, outcomes["deleted"])
        assertEquals(HistoryWriteOutcome.PERSISTED, outcomes["mixed"])
        assertEquals(mapOf(1 to 100f), rows())
        assertEquals(2, attempts.get())
        assertFalse(journalFile.exists())
    }

    @Test
    fun deletedHistoryDoesNotComeBackFromTheQueue() {
        databaseBroken = true
        val outcomes = CopyOnWriteArrayList<HistoryWriteOutcome>()
        val writer = writer()
        val other = "X-OTHER"
        writer.submit(serial, listOf(reading(0)), outcomes::add)
        writer.submit(other, listOf(reading(0, sensor = other)))
        runBlocking {
            val ticket = writer.ticket()
            writer.exclusive { writer.discard(listOf(serial), ticket) }
        }
        writer.submit(serial, listOf(reading(1)))
        databaseBroken = false
        eventually("the rest is stored") { rows() == mapOf(1 to 100f) && rows(other).isNotEmpty() }
        eventually("told") { outcomes.isNotEmpty() }
        assertEquals(listOf(HistoryWriteOutcome.SKIPPED), outcomes.toList())
    }

    @Test
    fun clearingTombstonesWhatCouldNotBeWritten() {
        databaseBroken = true
        val writer = writer()
        writer.submit(serial, listOf(reading(0)))
        runBlocking {
            val ticket = writer.ticket()
            writer.flushPending()
            writer.exclusive {
                database.clearHistoryRows(ClearScope.READINGS, writer.waiting(ticket))
                writer.discard(sensorSerials = null, ticket = ticket)
            }
        }
        databaseBroken = false
        // The sensor sends it again.
        val outcomes = CopyOnWriteArrayList<HistoryWriteOutcome>()
        writer.submit(serial, listOf(reading(0)), outcomes::add)
        eventually("settled") { outcomes.isNotEmpty() }
        assertEquals(listOf(HistoryWriteOutcome.SKIPPED), outcomes.toList())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun nothingUsableIsSkippedAtOnce() {
        val writer = writer()
        assertEquals(HistoryWriteOutcome.SKIPPED, writer.submit(serial, emptyList()) { error("already final") })
        assertEquals(HistoryWriteOutcome.SKIPPED, writer.submit(serial, listOf(reading(0).copy(timestamp = 0L))))
    }

    // ---- concurrency ----

    @Test
    fun concurrentSubmissionsAndFailingFlushesStoreEverythingOnce() {
        val sensors = listOf("X-A", "X-B")
        val random = java.util.Random(42L)
        val writer = writer(store = { sensor, readings ->
            attempts.incrementAndGet()
            if (synchronized(random) { random.nextInt(3) } == 0) {
                HistoryBatchWriter.Stored(BucketReplaceOutcome.FAILED)
            } else {
                repository.writeBuckets(sensor, readings, 60_000L)
            }
        })
        val outcomes = CopyOnWriteArrayList<HistoryWriteOutcome>()
        val threads = (0 until 8).map { worker ->
            thread {
                for (batch in 0 until 20) {
                    val sensor = sensors[worker % 2]
                    // Workers of one sensor own different minutes and rewrite them, so the last value is theirs.
                    val minutes = (worker * 100 until worker * 100 + 16)
                    writer.submit(sensor, minutes.map { reading(it, 100f + batch, sensor = sensor) }, outcomes::add)
                    if (batch % 5 == 0) runBlocking { writer.flushPending() }
                }
            }
        }
        threads.forEach { it.join() }
        eventually("all settled") { outcomes.size == 160 }
        assertEquals(setOf(HistoryWriteOutcome.PERSISTED), outcomes.toSet())
        sensors.forEach { sensor ->
            assertEquals(0, writer.pendingCount(sensor))
            assertEquals(64, rows(sensor).size)
            assertEquals(setOf(119f), rows(sensor).values.toSet())
        }
        eventually("journal drained") { !journalFile.exists() }
    }

    // ---- process restart ----

    @Test
    fun aBatchAcceptedByAProcessThatDiedIsWrittenByTheNext() {
        databaseBroken = true
        val scope = newScope()
        val writer = writer(scope)
        val stored = reading(1, 222f, secondMs = 7_000L)
        writer.submit(serial, listOf(reading(0, 111f), reading(1, 111f)))
        writer.submit(serial, listOf(stored))
        scope.cancel()

        databaseBroken = false
        writer().resume()
        eventually("stored after restart") { rows() == mapOf(0 to 111f, 1 to 222f) }
        val row = runBlocking {
            database.historyDao().getSensorReadingsInTimeRange(serial, stored.timestamp, stored.timestamp + 1L).single()
        }
        assertEquals(stored.copy(id = row.id), row)
        eventually("journal drained") { !journalFile.exists() }
    }

    @Test
    fun aRestartDoesNotReplayWhatWasAlreadyStored() {
        val scope = newScope()
        val writer = writer(scope, store = { sensor, readings ->
            if (sensor != serial) HistoryBatchWriter.Stored(BucketReplaceOutcome.FAILED)
            else repository.writeBuckets(sensor, readings, 60_000L)
        })
        writer.submit("X-STUCK", listOf(reading(0, sensor = "X-STUCK")))
        writer.submit(serial, listOf(reading(0, 111f)))
        eventually("stored") { writer.pendingCount(serial) == 0 && journalFile.readLines().size == 1 }
        scope.cancel()
        runBlocking { repository.writeBuckets(serial, listOf(reading(0, 222f)), 60_000L) }

        writer().resume()
        eventually("the stuck one is stored") { rows("X-STUCK").isNotEmpty() }
        assertEquals(mapOf(0 to 222f), rows())
    }

    @Test
    fun aBatchThatCannotBeJournaledIsNotAccepted() {
        databaseBroken = true
        // A directory where the file should be: every append fails.
        assertTrue(journalFile.mkdirs())
        val scope = newScope()
        val writer = writer(scope)
        assertEquals(
            HistoryWriteOutcome.FAILED,
            writer.submit(serial, listOf(reading(0))) { error("already final") },
        )
        // Not promised, but not thrown away either.
        databaseBroken = false
        eventually("stored anyway") { rows() == mapOf(0 to 100f) }
    }

    @Test
    fun aRefusedBatchDoesNotDisturbTheJournaledOneItReplaces() {
        databaseBroken = true
        val scope = newScope()
        val writer = writer(scope, retryDelaysMs = longArrayOf(60_000L))
        assertEquals(HistoryWriteOutcome.QUEUED, writer.submit(serial, listOf(reading(0, 111f))))
        val kept = journalFile.readText()
        journalFile.setReadOnly()
        val refused = writer.submit(serial, listOf(reading(0, 222f)))
        journalFile.setWritable(true)
        scope.cancel()
        assertEquals(HistoryWriteOutcome.FAILED, refused)
        assertEquals(kept, journalFile.readText())

        databaseBroken = false
        writer().resume()
        eventually("the accepted one survives") { rows() == mapOf(0 to 111f) }
    }

    @Test
    fun whatIsStoredLeavesTheJournalBeforeAnyOtherWriteCanRun() {
        val forwarding = CompletableDeferred<Unit>()
        val scope = newScope()
        val writer = writer(scope, onStored = {
            forwarding.complete(Unit)
            CompletableDeferred<Unit>().await() // forwarding to the exchanges takes its time
        })
        writer.submit(serial, listOf(reading(0, 111f)))
        runBlocking {
            forwarding.await()
            assertFalse("gone while the outputs are still being served", journalFile.exists())
            val ticket = writer.ticket()
            writer.exclusive {
                repository.writeBuckets(serial, listOf(reading(0, 222f)), 60_000L)
                writer.supersede(serial, listOf(reading(0).timestamp), ticket)
            }
        }
        scope.cancel() // the process dies here
        val restarted = writer()
        restarted.resume()
        runBlocking { restarted.flushPending() }
        assertEquals(mapOf(0 to 222f), rows())
    }

    @Test
    fun aDirectWriteBeforeTheJournalIsOpenedStillOutranksIt() {
        databaseBroken = true
        val scope = newScope()
        writer(scope).submit(serial, listOf(reading(0, 111f)))
        scope.cancel()
        databaseBroken = false
        val restarted = writer()
        runBlocking {
            val ticket = restarted.ticket()
            restarted.exclusive {
                repository.writeBuckets(serial, listOf(reading(0, 222f)), 60_000L)
                restarted.supersede(serial, listOf(reading(0).timestamp), ticket, retainedOnly = true)
            }
            restarted.flushPending()
        }
        assertEquals(mapOf(0 to 222f), rows())
    }

    @Test
    fun whatIsStoredLeavesTheJournalEvenWithoutRoomForASecondCopy() {
        val scope = newScope()
        val writer = writer(scope, store = { sensor, readings ->
            if (sensor != serial) HistoryBatchWriter.Stored(BucketReplaceOutcome.FAILED)
            else repository.writeBuckets(sensor, readings, 60_000L)
        })
        // Where the new copy would be staged: writing it fails.
        assertTrue(File(journalFile.path + ".new/occupied").mkdirs())
        writer.submit("X-STUCK", listOf(reading(0, sensor = "X-STUCK")))
        writer.submit(serial, listOf(reading(0, 111f)))
        eventually("stored") { writer.pendingCount(serial) == 0 }
        runBlocking { writer.exclusive { repository.writeBuckets(serial, listOf(reading(0, 222f)), 60_000L) } }
        assertEquals(1, journalFile.readLines().size)
        scope.cancel()

        writer().resume()
        eventually("the stuck one is stored") { rows("X-STUCK").isNotEmpty() }
        assertEquals(mapOf(0 to 222f), rows())
    }

    @Test
    fun aLineCutShortDoesNotSwallowTheNextBatch() {
        databaseBroken = true
        val first = newScope()
        writer(first).submit(serial, listOf(reading(0), reading(1)))
        first.cancel()
        journalFile.writeText(journalFile.readText().dropLast(6))

        // The next process accepts a batch and dies as well before it stores anything.
        val second = newScope()
        writer(second, retryDelaysMs = longArrayOf(60_000L)).submit(serial, listOf(reading(5), reading(6)))
        second.cancel()

        databaseBroken = false
        writer().resume()
        eventually("only the torn line is gone") { rows().keys == setOf(0, 5, 6) }
    }

    @Test
    fun aLineCutShortCostsOnlyItself() {
        databaseBroken = true
        val scope = newScope()
        writer(scope).submit(serial, listOf(reading(0), reading(1), reading(2)))
        scope.cancel()
        journalFile.writeText(journalFile.readText().dropLast(6))

        databaseBroken = false
        writer().resume()
        eventually("the whole lines are stored") { rows() == mapOf(0 to 100f, 1 to 100f) }
    }

    @Test
    fun resubmittingWhatCannotBeStoredDoesNotGrowTheJournalWithoutEnd() {
        databaseBroken = true
        val writer = writer(retryDelaysMs = longArrayOf(60_000L))
        repeat(100) { writer.submit(serial, (0 until 100).map { minute -> reading(minute) }) }
        assertEquals(100, writer.pendingCount(serial))
        assertTrue(journalFile.readLines().size <= 4_200)
    }
}
