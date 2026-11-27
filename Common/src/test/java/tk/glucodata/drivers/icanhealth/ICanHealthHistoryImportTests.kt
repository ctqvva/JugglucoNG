package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthHistoryImportTests {
    private val import = ICanHealthHistoryImport()
    private val rows = HashMap<Int, Long>()
    private val covered = ArrayList<Int>()

    private fun receive(vararg sequences: Int, baseMs: Long = 0L) = sequences.forEach {
        import.record(it, baseMs + it * 60_000L, 100f + it, Float.NaN, Float.NaN)
    }

    private fun persist(succeeds: Boolean): Int = import.persist(
        write = { batch ->
            if (succeeds) batch.records.forEach { rows[it.sequenceNumber] = it.timestampMs }
            succeeds
        },
        onPersisted = { batch -> covered += batch.records.last().sequenceNumber },
    )

    @Test fun aFailedWriteKeepsTheBatchAndReportsNothingStored() {
        receive(3, 6, 9)
        import.completeTransfer()
        assertEquals(0, persist(succeeds = false))
        assertTrue(import.hasUnsaved())
        assertEquals(1, import.consecutiveFailures)
        assertTrue(covered.isEmpty())
    }

    @Test fun repeatedFailuresThenASuccessStoreEveryRecordOnce() {
        receive(3, 6, 9)
        import.completeTransfer()
        repeat(4) { assertEquals(0, persist(succeeds = false)) }
        assertEquals(4, import.consecutiveFailures)
        assertEquals(3, persist(succeeds = true))
        assertEquals(setOf(3, 6, 9), rows.keys)
        assertEquals(listOf(9), covered)
        assertFalse(import.hasUnsaved())
        assertEquals(0, import.consecutiveFailures)
        assertEquals(0, persist(succeeds = true))
    }

    @Test fun onlyTheFirstAttemptIsNotARetry() {
        receive(3)
        import.completeTransfer()
        val retries = ArrayList<Boolean>()
        import.persist({ retries += it.isRetry; false }, {})
        import.persist({ retries += it.isRetry; true }, {})
        receive(6)
        import.completeTransfer()
        import.persist({ retries += it.isRetry; true }, {})
        assertEquals(listOf(false, true, false), retries)
    }

    @Test fun readingTheSameRangeAgainKeepsTheFirstTimestamps() {
        receive(3, 6)
        import.completeTransfer()
        persist(succeeds = false)
        // After a reconnect the anchor has moved by a minute and a half and one record is new.
        receive(3, 6, 9, baseMs = 90_000L)
        import.completeTransfer()
        assertEquals(3, persist(succeeds = true))
        assertEquals(mapOf(3 to 180_000L, 6 to 360_000L, 9 to 630_000L), rows)
    }

    @Test fun anInterruptedTransferNeitherCountsNorTouchesWhatIsOwed() {
        receive(3, 6)
        import.completeTransfer()
        persist(succeeds = false)
        receive(9)
        assertEquals(1, import.incomingCount)
        import.discardIncoming()
        assertEquals(0, import.incomingCount)
        assertEquals(2, persist(succeeds = true))
        assertEquals(setOf(3, 6), rows.keys)
    }

    @Test fun aRecordThatAlsoArrivedLiveIsWrittenToTheLiveRow() {
        receive(3, 6)
        import.completeTransfer()
        persist(succeeds = false)
        import.alignWithLiveReading(6, 385_000L)
        import.alignWithLiveReading(9, 540_000L)
        assertEquals(2, persist(succeeds = true))
        assertEquals(mapOf(3 to 180_000L, 6 to 385_000L), rows)
    }

    @Test fun recordsWithoutATimestampOrGlucoseAreNeverOwed() {
        import.record(3, 0L, 100f, Float.NaN, Float.NaN)
        import.record(6, 360_000L, Float.NaN, 5f, 33f)
        import.completeTransfer()
        assertFalse(import.hasUnsaved())
    }

    @Test fun aThrowingWriteCountsAsAFailure() {
        receive(3)
        import.completeTransfer()
        assertEquals(0, import.persist({ error("disk full") }, {}))
        assertEquals(1, persist(succeeds = true))
    }

    @Test fun aTransferFinishedDuringAWriteIsWrittenByThatWrite() {
        receive(3)
        import.completeTransfer()
        val batches = ArrayList<List<Int>>()
        val saved = import.persist(
            write = { batch ->
                batches += batch.records.map { it.sequenceNumber }
                if (batches.size == 1) {
                    receive(6)
                    import.completeTransfer()
                    // Another thread's attempt: it must not write concurrently.
                    assertEquals(0, persist(succeeds = true))
                }
                true
            },
            onPersisted = {},
        )
        assertEquals(listOf(listOf(3), listOf(6)), batches)
        assertEquals(2, saved)
        assertFalse(import.hasUnsaved())
    }

    @Test fun clearingDuringAWriteReportsNothingForTheRemovedSensor() {
        receive(3)
        import.completeTransfer()
        assertEquals(0, import.persist({ import.clear(); true }, { covered += 3 }))
        assertTrue(covered.isEmpty())
        assertFalse(import.hasUnsaved())
    }

    @Test fun retryDelayDoublesUpToAQuarterOfAnHour() {
        assertEquals(
            listOf(30L, 30L, 60L, 120L, 240L, 480L, 900L, 900L),
            listOf(0, 1, 2, 3, 4, 5, 6, 1_000).map { ICanHealthHistoryPolicy.persistRetryDelayMs(it) / 1000L },
        )
    }

    @Test fun theSensorCannotChangeBetweenTheSnapshotAndTheCompletion() {
        var sensor = "old"
        val seen = ArrayList<String>()
        receive(3, 6)
        import.completeTransfer()
        lateinit var switch: Thread
        import.persist(
            write = {
                switch = startBlocked { import.changeSensor(isSameSensor = false) { sensor = "new" } }
                seen += sensor
                true
            },
            onPersisted = { seen += sensor },
        )
        switch.join(5_000L)
        assertEquals(listOf("old", "old"), seen)
        assertEquals("new", sensor)
    }

    @Test fun aWriteThatHasNotTakenItsBatchYetWritesNothingForTheNextSensor() {
        receive(3, 6)
        import.completeTransfer()
        lateinit var retry: Thread
        import.changeSensor(isSameSensor = false) {
            retry = startBlocked { persist(succeeds = true) }
        }
        retry.join(5_000L)
        assertTrue(rows.isEmpty())
        assertTrue(covered.isEmpty())
    }

    @Test fun learningTheRealIdOfTheSameSensorKeepsTheBatch() {
        receive(3, 6)
        import.completeTransfer()
        import.changeSensor(isSameSensor = true) {}
        assertEquals(2, persist(succeeds = true))
    }

    @Test fun removalWaitsForARunningWrite() {
        receive(3)
        import.completeTransfer()
        receive(6)
        lateinit var removal: Thread
        import.persist(
            write = { removal = startBlocked { import.clear() }; true },
            onPersisted = { assertTrue(removal.isAlive) },
        )
        removal.join(5_000L)
        assertEquals(0, import.incomingCount)
        assertFalse(import.hasUnsaved())
    }

    @Test fun recordsOfTheNextSensorLeftToAWriteThatEndedInASwitchAreWritten() {
        receive(3)
        import.completeTransfer()
        val written = ArrayList<Int>()
        import.persist(
            write = { batch ->
                written += batch.records.map { it.sequenceNumber }
                if (written.size == 1) {
                    import.changeSensor(isSameSensor = false) {}
                    receive(6)
                    import.completeTransfer()
                    import.persist({ error("left to the running write") }, {})
                }
                true
            },
            onPersisted = { batch -> covered += batch.records.last().sequenceNumber },
        )
        assertEquals(listOf(3, 6), written)
        assertEquals("the first batch belongs to the sensor that is gone", listOf(6), covered)
    }

    /** Runs [action] on another thread and returns once it waits for the write lock. */
    private fun startBlocked(action: () -> Unit): Thread = Thread(action).apply {
        start()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(1L)
        assertEquals(Thread.State.WAITING, state)
    }
}
