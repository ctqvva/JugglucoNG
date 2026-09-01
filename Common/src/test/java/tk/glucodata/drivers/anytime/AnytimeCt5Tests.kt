package tk.glucodata.drivers.anytime

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CT5 Auto behaviour: warm-up, profile, gap repair, interrupted history and
 * end-cycle framing.
 *
 * Hardware reference is the 2026-08-17 trace (fresh activation through
 * `id=288 -> GATT 147 -> reconnect -> id=291`).
 */
class AnytimeCt5Tests {

    private val key = 0x5A

    // ---- Frame fixtures -------------------------------------------------

    /** 11-byte plaintext CT5 record chunk. glucoseMgdl 0 == warm-up, no glucose yet. */
    private fun chunk(
        ibNa: Float,
        iwNa: Float,
        temperatureC: Float,
        trend: Int,
        glucoseMgdl: Int,
        error: Int = 0,
    ): ByteArray {
        val ibRaw = (ibNa * 100f).roundToInt()
        val iwRaw = (iwNa * 100f).roundToInt()
        val tInt = temperatureC.toInt()
        val tFrac = ((temperatureC - tInt) * 100f).roundToInt()
        return byteArrayOf(
            ((ibRaw shr 8) and 0xFF).toByte(),
            (ibRaw and 0xFF).toByte(),
            ((iwRaw shr 8) and 0xFF).toByte(),
            (iwRaw and 0xFF).toByte(),
            (tInt + AnytimeConstants.TEMP_INT_OFFSET).toByte(),
            tFrac.toByte(),
            (((trend and 0x0F) shl 4) or ((glucoseMgdl shr 8) and 0x0F)).toByte(),
            (glucoseMgdl and 0xFF).toByte(),
            error.toByte(),
            0x00,
            0x00,
        )
    }

    private fun livePushFrame(glucoseId: Int, payload: ByteArray): ByteArray {
        val encrypted = AnytimeFrames.ct5Decode(payload, key)
        val frame = ByteArray(4 + encrypted.size)
        frame[0] = AnytimeConstants.RX_CT5_PUSH_GLUCOSE
        frame[1] = (glucoseId and 0xFF).toByte()
        frame[2] = ((glucoseId shr 8) and 0xFF).toByte()
        encrypted.copyInto(frame, destinationOffset = 3)
        frame[frame.lastIndex] = AnytimeFrames.sum(frame, 0, frame.lastIndex - 1)
        return frame
    }

    /** `trailingMarkers` are appended verbatim; the sensor pads a partial batch this way. */
    private fun seriesFrame(
        startId: Int,
        chunks: List<ByteArray>,
        trailingMarkers: List<ByteArray> = emptyList(),
    ): ByteArray {
        val plain = chunks.fold(ByteArray(0)) { acc, c -> acc + c }
        val encrypted = AnytimeFrames.ct5Decode(plain, key)
        val tail = trailingMarkers.fold(ByteArray(0)) { acc, c -> acc + c }
        val frame = ByteArray(4 + encrypted.size + tail.size)
        frame[0] = AnytimeConstants.RX_CT5_SERIES
        frame[1] = (startId and 0xFF).toByte()
        frame[2] = ((startId shr 8) and 0xFF).toByte()
        encrypted.copyInto(frame, destinationOffset = 3)
        tail.copyInto(frame, destinationOffset = 3 + encrypted.size)
        frame[frame.lastIndex] = AnytimeFrames.sum(frame, 0, frame.lastIndex - 1)
        return frame
    }

    private fun marker(value: Int) = ByteArray(AnytimeConstants.CT5_RAW_CHUNK_SIZE) { value.toByte() }

    // ---- Fresh activation / warm-up -------------------------------------

    @Test
    fun warmupRecordsAreProtocolValidAndCarryNoGlucose() {
        // Observed fresh CT5: ids 0..13 are real telemetry with no glucose,
        // first usable glucose lands on id=14.
        for (id in 0..13) {
            val frame = livePushFrame(id, chunk(ibNa = 0f, iwNa = 3.20f, temperatureC = 32.5f, trend = 0, glucoseMgdl = 0))

            val rec = AnytimeFrames.parseCt5CurrentRecord(frame, key)

            assertNotNull("warm-up id=$id must parse, not look malformed", rec)
            rec!!
            assertEquals(id, rec.glucoseId)
            assertFalse("warm-up id=$id must not claim glucose", rec.hasGlucose)
            assertEquals(0, rec.gluMgdl)
            assertEquals(3.20f, rec.iwNa, 0.001f)
            assertEquals(32.5f, rec.temperatureC, 0.01f)
            assertEquals(0, rec.errorCode)
        }
    }

    @Test
    fun firstGlucoseRecordTransitionsOutOfWarmup() {
        val frame = livePushFrame(14, chunk(ibNa = 0f, iwNa = 6.72f, temperatureC = 33.5f, trend = 4, glucoseMgdl = 96))

        val rec = AnytimeFrames.parseCt5CurrentRecord(frame, key)

        assertNotNull(rec)
        rec!!
        assertEquals(14, rec.glucoseId)
        assertTrue(rec.hasGlucose)
        assertEquals(96, rec.gluMgdl)
    }

    @Test
    fun malformedFrameIsStillRejected() {
        val frame = livePushFrame(7, chunk(ibNa = 0f, iwNa = 3f, temperatureC = 32f, trend = 0, glucoseMgdl = 0))
        frame[frame.lastIndex] = (frame[frame.lastIndex] + 1).toByte()

        assertNull("a bad sum is malformed, not warm-up", AnytimeFrames.parseCt5CurrentRecord(frame, key))
    }

    @Test
    fun warmupTimelineAnchorsOnThreeMinuteIds() {
        val intervalMs = AnytimeProfileResolver.resolve("Anytime5252037585").readingIntervalMinutes * 60_000L
        val arrivalOfId13 = 1_786_915_681_684L + 13L * intervalMs

        // timelineStart ~= liveArrival - glucoseId * 3 min, established during warm-up.
        assertEquals(1_786_915_681_684L, arrivalOfId13 - 13L * intervalMs)
        assertEquals(180_000L, intervalMs)
    }

    // ---- CT5 profile ----------------------------------------------------

    @Test
    fun ct5ProfileIsThreeMinuteOwnWarmupSixteenDay() {
        val profile = AnytimeProfileResolver.resolve("Anytime5252037585")

        assertEquals(AnytimeConstants.Family.CT5, profile.family)
        assertEquals(3, profile.readingIntervalMinutes)
        assertEquals(16, profile.ratedLifetimeDays)
        assertEquals(7695, profile.endNumber)

        // The exact figure is a tuning decision; what must hold is that CT5 uses
        // its own window and that the derived values follow it.
        val warmup = AnytimeConstants.CT5_WARMUP_MINUTES
        assertEquals(warmup, profile.warmupMinutes)
        assertEquals(warmup / 3, profile.warmupRecords())
        assertEquals(warmup * 60L * 1000L, profile.warmupMs())
        // Must still cover the observed first-glucose id (14 x 3 min = 42 min was
        // the fresh-activation trace) minus whatever tolerance the tuning allows.
        assertTrue("CT5 warm-up should be at least half an hour", warmup >= 30)
    }

    @Test
    fun ct5WarmupDoesNotChangeTheSharedAnytimeDefault() {
        assertNotEquals(
            "CT5 must carry its own warm-up, not the shared default",
            AnytimeConstants.DEFAULT_WARMUP_MINUTES,
            AnytimeConstants.CT5_WARMUP_MINUTES,
        )
        assertEquals(60, AnytimeConstants.DEFAULT_WARMUP_MINUTES)
        assertEquals(60, AnytimeProfileResolver.resolve("SN16-test").warmupMinutes)
        assertEquals(60, AnytimeProfileResolver.resolve("SN72-test").warmupMinutes)
    }

    @Test
    fun ct5LifetimeStaysOnTheThreeMinuteTickScale() {
        val profile = AnytimeProfileResolver.resolve("Anytime5252037585")

        // endNumber 7695 x 3 min == 16.03 days. A 5-minute reading would give 26.
        assertEquals(16, ((profile.endNumber.toLong() * 3L) / (60L * 24L)).toInt())
        assertEquals(16L * 24L * 60L * 60L * 1000L, profile.ratedLifetimeMs())
    }

    // ---- Reconnect gap repair -------------------------------------------

    @Test
    fun genuineReconnectGapRequestsOnlyTheMissingIds() {
        // Trace: last live id=288, BLE loss, reconnect, next live id=291.
        val gap = ct5ReconnectGap(highestKnownId = 288, liveId = 291, maxRecords = 480)

        assertNotNull(gap)
        gap!!
        assertEquals(289, gap.fromId)
        assertEquals(291, gap.stopBeforeId)
        assertEquals(2, gap.count)
        assertEquals("289..290", gap.toString())
    }

    @Test
    fun contiguousOrRepeatedLiveIdsProduceNoGap() {
        assertNull(ct5ReconnectGap(highestKnownId = 288, liveId = 289, maxRecords = 480))
        assertNull(ct5ReconnectGap(highestKnownId = 288, liveId = 288, maxRecords = 480))
        assertNull(ct5ReconnectGap(highestKnownId = 288, liveId = 200, maxRecords = 480))
    }

    @Test
    fun noStoredHistoryNeverTriggersAReplayFromZero() {
        // The old failure mode: nothing cached => "seed native history from id=0".
        assertNull(ct5ReconnectGap(highestKnownId = -1, liveId = 291, maxRecords = 480))
    }

    @Test
    fun aVeryLongOutageIsCappedToTheNewestRecords() {
        val gap = ct5ReconnectGap(highestKnownId = 0, liveId = 1000, maxRecords = 480)

        assertNotNull(gap)
        gap!!
        assertEquals(520, gap.fromId)
        assertEquals(1000, gap.stopBeforeId)
        assertEquals(480, gap.count)
    }

    // ---- Interrupted / transient history --------------------------------

    @Test
    fun oneHistoryTimeoutDoesNotDisableHistory() {
        val health = AnytimeCt5HistoryHealth(maxTimeoutsPerConnection = 3, retryBackoffMs = 3_000L)

        val backoff = health.onTimeout()

        assertEquals(3_000L, backoff)
        assertFalse("a single timeout must stay transient", health.isPausedForThisConnection())
    }

    @Test
    fun repeatedTimeoutsPauseOnlyForTheCurrentConnection() {
        val health = AnytimeCt5HistoryHealth(maxTimeoutsPerConnection = 3, retryBackoffMs = 3_000L)

        assertEquals(3_000L, health.onTimeout())
        assertEquals(6_000L, health.onTimeout())
        assertNull(health.onTimeout())
        assertTrue(health.isPausedForThisConnection())

        // The 2026-08-17 trace shows the link dying three seconds after the
        // timeout; a new GATT session must start clean.
        health.onGattSessionStarted()

        assertFalse(health.isPausedForThisConnection())
        assertEquals(0, health.timeoutCount())
    }

    @Test
    fun aSuccessfulSeriesResponseClearsTheTimeoutStreak() {
        val health = AnytimeCt5HistoryHealth(maxTimeoutsPerConnection = 3, retryBackoffMs = 3_000L)

        health.onTimeout()
        health.onSeriesReceived()

        assertEquals(0, health.timeoutCount())
        assertEquals(3_000L, health.onTimeout())
    }

    // ---- App/process restart --------------------------------------------

    @Test
    fun aPendingGapSurvivesTheArrivalOfNewerReadings() {
        // The 2026-08-20 regression: gap 1679..1679 was recorded, then the live
        // push for 1680 landed and the gap was silently discarded because progress
        // was measured with the global "highest id seen" watermark. Nothing inside
        // 1679..1680 was ever imported, so the range must still stand.
        val remaining = ct5RemainingGap(
            pendingFromId = 1679,
            pendingStopBeforeId = 1680,
            highestImportedInRange = -1,
        )

        assertNotNull("a newer live id must not cancel an unfilled gap", remaining)
        assertEquals("1679..1679", remaining!!.toString())
    }

    @Test
    fun aGapClosesOnlyWhenItsOwnIdsArriv() {
        // Filled: nothing left.
        assertNull(ct5RemainingGap(1679, 1680, highestImportedInRange = 1679))
        // Partly filled: resume mid-range.
        assertEquals(
            "255..290",
            ct5RemainingGap(240, 291, highestImportedInRange = 254)!!.toString(),
        )
        // Ids outside the range are irrelevant in both directions.
        assertEquals("240..290", ct5RemainingGap(240, 291, highestImportedInRange = 9999)!!.toString())
        assertEquals("240..290", ct5RemainingGap(240, 291, highestImportedInRange = 100)!!.toString())
    }

    @Test
    fun restartResumesTheStoredRange() {
        val remaining = ct5RemainingGap(pendingFromId = 289, pendingStopBeforeId = 301, highestImportedInRange = -1)

        assertNotNull(remaining)
        assertEquals("289..300", remaining!!.toString())
    }

    @Test
    fun anEmptyOrInvertedGapIsDropped() {
        assertNull(ct5RemainingGap(-1, 291, -1))
        assertNull(ct5RemainingGap(291, 291, -1))
        assertNull(ct5RemainingGap(292, 291, -1))
    }

    @Test
    fun aPartlyImportedGapResumesMidRangeInsteadOfRestarting() {
        val afterFirstBatch = ct5GapAfterBatch(pendingFromId = 240, pendingStopBeforeId = 291, maxImportedId = 254)

        assertNotNull(afterFirstBatch)
        assertEquals("255..290", afterFirstBatch!!.toString())
        assertNull(ct5GapAfterBatch(pendingFromId = 255, pendingStopBeforeId = 291, maxImportedId = 290))
    }

    @Test
    fun separateGapsMergeIntoOneCappedRange() {
        // First hole, then another a few pushes later.
        val first = ct5MergeGap(-1, -1, newFromId = 1679, newStopBeforeId = 1680, maxRecords = 480)
        assertEquals("1679..1679", first!!.toString())

        val merged = ct5MergeGap(
            currentFromId = first.fromId,
            currentStopBeforeId = first.stopBeforeId,
            newFromId = 1683,
            newStopBeforeId = 1685,
            maxRecords = 480,
        )
        assertEquals("1679..1684", merged!!.toString())

        // A flaky stretch cannot grow into a session replay.
        val capped = ct5MergeGap(0, 1, newFromId = 900, newStopBeforeId = 1000, maxRecords = 480)
        assertEquals(480, capped!!.count)
        assertEquals("520..999", capped.toString())

        // Nothing new: the outstanding range is preserved.
        assertEquals(
            "1679..1679",
            ct5MergeGap(1679, 1680, newFromId = -1, newStopBeforeId = -1, maxRecords = 480)!!.toString(),
        )
    }

    @Test
    fun sparsePendingEnvelopeRepairsNewestRealHoleFirst() {
        val cached = (7025..7511).toMutableSet().apply {
            remove(7025)
            remove(7508)
            remove(7511)
        }

        assertEquals(
            "7025..7511",
            ct5MissingEnvelope(7025, 7512, cached)!!.toString(),
        )
        assertEquals(
            "7511..7511",
            ct5NewestMissingRange(7025, 7512, cached, maxRecords = 15)!!.toString(),
        )
    }

    @Test
    fun newestMissingRangeKeepsAContiguousRunAndHonoursBatchLimit() {
        val cached = (100..140).toMutableSet().apply {
            (120..137).forEach(::remove)
        }

        assertEquals(
            "123..137",
            ct5NewestMissingRange(100, 141, cached, maxRecords = 15)!!.toString(),
        )

        cached.addAll(123..137)
        assertEquals(
            "120..122",
            ct5NewestMissingRange(100, 141, cached, maxRecords = 15)!!.toString(),
        )
    }

    @Test
    fun filledPendingEnvelopeDisappears() {
        val cached = (289..300).toSet()

        assertNull(ct5MissingEnvelope(289, 301, cached))
        assertNull(ct5NewestMissingRange(289, 301, cached, maxRecords = 15))
    }

    // ---- Reconnect churn -------------------------------------------------

    @Test
    fun aJustRecoveredSessionSurvivesAStaleLossOfSignalAlarm() {
        // Trace: handshake completed at 16:55:37, the alarm armed from the 16:52
        // reading fired at 16:57:51 and killed the link with status 0.
        val handshakeAtMs = 1_786_967_737_000L
        val staleAlarmAtMs = 1_786_967_871_000L
        val graceMs = 3L * 60_000L + 90_000L

        assertTrue(shouldDeferLossOfSignalReconnect(handshakeAtMs, staleAlarmAtMs, graceMs))
        // The next scheduled 3-minute push (16:58:01) would have arrived first.
        assertTrue(staleAlarmAtMs - handshakeAtMs < graceMs)
    }

    @Test
    fun theGracePeriodDoesNotDisableRecoveryForgood() {
        val graceMs = 3L * 60_000L + 90_000L

        assertFalse(shouldDeferLossOfSignalReconnect(1_000L, 1_000L + graceMs, graceMs))
        assertFalse(shouldDeferLossOfSignalReconnect(1_000L, 1_000L + graceMs + 60_000L, graceMs))
        // Not streaming yet / unknown session start must not be protected.
        assertFalse(shouldDeferLossOfSignalReconnect(0L, 1_000_000L, graceMs))
    }

    // ---- History parsing ------------------------------------------------

    @Test
    fun parsesAFullFifteenRecordBatch() {
        val chunks = (0 until 15).map { i ->
            chunk(ibNa = 0f, iwNa = 6.0f + i, temperatureC = 33.0f, trend = 4, glucoseMgdl = 100 + i)
        }

        val records = AnytimeFrames.parseCt5SeriesRecords(seriesFrame(45, chunks), key)

        assertEquals(15, records.size)
        assertEquals(45, records.first().glucoseId)
        assertEquals(59, records.last().glucoseId)
        assertEquals(100, records.first().gluMgdl)
        assertEquals(114, records.last().gluMgdl)
        assertTrue(records.all { it.hasGlucose })
    }

    @Test
    fun warmupRecordsSurviveHistoryParsingWithoutGlucose() {
        val chunks = listOf(
            chunk(ibNa = 0f, iwNa = 3.1f, temperatureC = 32.0f, trend = 0, glucoseMgdl = 0),
            chunk(ibNa = 0f, iwNa = 3.2f, temperatureC = 32.1f, trend = 0, glucoseMgdl = 0),
            chunk(ibNa = 0f, iwNa = 6.7f, temperatureC = 33.5f, trend = 4, glucoseMgdl = 96),
        )

        val records = AnytimeFrames.parseCt5SeriesRecords(seriesFrame(12, chunks), key)

        assertEquals(3, records.size)
        assertEquals(listOf(12, 13, 14), records.map { it.glucoseId })
        assertEquals(listOf(false, false, true), records.map { it.hasGlucose })
        assertEquals(0, records[0].gluMgdl)
        assertEquals(96, records[2].gluMgdl)
    }

    @Test
    fun trailingPaddingAndEndMarkersAreIgnored() {
        val chunks = listOf(
            chunk(ibNa = 0f, iwNa = 6.7f, temperatureC = 33.5f, trend = 4, glucoseMgdl = 96),
            chunk(ibNa = 0f, iwNa = 6.8f, temperatureC = 33.6f, trend = 4, glucoseMgdl = 97),
        )

        val padded = AnytimeFrames.parseCt5SeriesRecords(
            seriesFrame(289, chunks, trailingMarkers = listOf(marker(0xFF), marker(0xFC))),
            key,
        )

        assertEquals(2, padded.size)
        assertEquals(listOf(289, 290), padded.map { it.glucoseId })
    }

    @Test
    fun caughtUpResponseParsesAsEmpty() {
        val emptyFrame = byteArrayOf(AnytimeConstants.RX_CT5_SERIES, 0x21, 0x01, 0x00)
        emptyFrame[3] = AnytimeFrames.sum(emptyFrame, 0, 2)

        assertTrue(AnytimeFrames.parseCt5SeriesRecords(emptyFrame, key).isEmpty())
        assertTrue(
            AnytimeFrames.parseCt5SeriesRecords(
                seriesFrame(300, emptyList(), trailingMarkers = listOf(marker(0xFC))),
                key,
            ).isEmpty()
        )
    }

    @Test
    fun batchTallySummarisesInsteadOfLoggingEveryDuplicate() {
        val tally = AnytimeCt5HistoryBatchTally()
        repeat(46) { tally.countExisting() }
        repeat(14) { tally.countWarmup() }

        assertEquals(60, tally.total)
        assertEquals(
            "CT5 history 0..59 received: 46 existing, 0 inserted, 14 warm-up/no-glucose",
            tally.describe(0, 59),
        )
    }

    // ---- History must never cost the connection --------------------------

    @Test
    fun everyBackfillWriteTagIsRecognisedAsOptional() {
        // Regression: the tag gained ",count=N" while the guards still matched
        // "pullGlucose(backfill)", so a failed history write was treated as a dead
        // link and tore down a healthy GATT (2026-08-20 trace, disconnect loop).
        for (count in 1..15) {
            val tag = anytimeBackfillWriteTag(count)
            assertTrue("$tag must be recognised as a history write", isAnytimeBackfillWriteTag(tag))
        }
        assertEquals("pullGlucose(backfill,count=1)", anytimeBackfillWriteTag(1))
        assertFalse(isAnytimeBackfillWriteTag("ct5-pushAck"))
        assertFalse(isAnytimeBackfillWriteTag("ct5-checkID"))
        assertFalse(isAnytimeBackfillWriteTag("pullGlucose(fallback)"))
    }

    @Test
    fun historyWaitsForAFreeGattWriteSlot() {
        val settleMs = 45_000L
        val since = 1_000_000L

        // A live-push ACK is still outstanding: never stack a 0x37 on top of it.
        assertFalse(
            isCt5HistoryLinkSettled(since, since + settleMs, settleMs, writeInFlight = true)
        )
        assertTrue(
            isCt5HistoryLinkSettled(since, since + settleMs, settleMs, writeInFlight = false)
        )
    }

    @Test
    fun historyWaitsForTheLinkToSettleAfterAHandshake() {
        val settleMs = 45_000L
        val handshakeAtMs = 1_000_000L

        // The 2026-08-20 trace fired a 0x37 within a second of the handshake and
        // lost the connection; the gap is persisted, so waiting is free.
        assertFalse(isCt5HistoryLinkSettled(handshakeAtMs, handshakeAtMs + 750L, settleMs, false))
        assertFalse(isCt5HistoryLinkSettled(handshakeAtMs, handshakeAtMs + 44_999L, settleMs, false))
        assertTrue(isCt5HistoryLinkSettled(handshakeAtMs, handshakeAtMs + 45_000L, settleMs, false))
        // Not streaming at all.
        assertFalse(isCt5HistoryLinkSettled(0L, 1_000_000L, settleMs, false))
    }

    @Test
    fun unavailableCt5GapIsAbandonedAfterThreeFailedGattSessions() {
        val tracker = AnytimeCt5GapFailureTracker(maxFailedSessions = 3)
        val range = AnytimeIdRange(700, 715)

        assertNull(tracker.onFailedSession(range))
        assertNull(tracker.onFailedSession(range))
        assertEquals(range, tracker.onFailedSession(range))
        assertNull(tracker.snapshot())
    }

    @Test
    fun ct5GapProgressClearsThePersistedFailureBudget() {
        val tracker = AnytimeCt5GapFailureTracker(maxFailedSessions = 3)
        val range = AnytimeIdRange(700, 715)
        tracker.onFailedSession(range)

        tracker.onProgress(listOf(706))

        assertNull(tracker.snapshot())
        assertNull(tracker.onFailedSession(range))
        assertEquals(1, tracker.snapshot()?.failures)
    }

    @Test
    fun unsolicitedLivePushDoesNotReleaseAQueuedHistoryResponse() {
        assertFalse(
            anytimeResponseMatchesRequest(
                AnytimeConstants.TX_CT5_PULL_SERIES,
                AnytimeConstants.RX_CT5_PUSH_GLUCOSE,
            )
        )
        assertTrue(
            anytimeResponseMatchesRequest(
                AnytimeConstants.TX_CT5_PULL_SERIES,
                AnytimeConstants.RX_CT5_SERIES,
            )
        )
        assertTrue(anytimeResponseMatchesRequest(0x04.toByte(), AnytimeConstants.RX_SET_DATE_ACK_A))
    }

    @Test
    fun liveAckAndGapRepairOutrankBulkHistoryWrites() {
        assertEquals(
            AnytimeGattWritePriority.LIVE_ACK,
            anytimeGattWritePriority("ct5-pushAck", "ct5-initial"),
        )
        assertEquals(
            AnytimeGattWritePriority.CONTROL,
            anytimeGattWritePriority("lowPower", "ct5-initial"),
        )
        assertEquals(
            AnytimeGattWritePriority.GAP_HISTORY,
            anytimeGattWritePriority(anytimeBackfillWriteTag(15), "ct5-gap(resumed)"),
        )
        assertEquals(
            AnytimeGattWritePriority.BULK_HISTORY,
            anytimeGattWritePriority(anytimeBackfillWriteTag(15), "ct5-initial"),
        )
    }

    // ---- End cycle ------------------------------------------------------

    @Test
    fun endCycleUsesTheOfficialCt5UnbindOpcode() {
        val frame = AnytimeFrames.Builders.ct5EndCycle()

        // The shipped app's actual CT5 unbind call path uses unBindRequest(),
        // not the unused temp-id overload in ProtocolToolsHolder.
        assertEquals(
            listOf(0x58, 0x55, 0xAA, 0x57),
            frame.map { it.toInt() and 0xFF },
        )
        assertEquals(AnytimeConstants.TX_CT5_END_CYCLE, frame[0])
        assertTrue(AnytimeFrames.verifySum(frame))
    }

    // ---- Cipher fallback (unchanged behaviour) --------------------------

    @Test
    fun realHardwareLivePushStillRecoversItsCipher() {
        // First 0x35 of the 2026-08-17 trace; the log decoded it as
        // id=256 mgdl=76 Iw=6.72nA Ib=0.00nA T=33.5C trend=4 err=0.
        val frame = byteArrayOf(
            0x35, 0x00, 0x01,
            0x1D, 0x1D, 0x1C, 0x82.toByte(), 0xDA.toByte(), 0x0E, 0xDD.toByte(), 0x26,
            0xE2.toByte(), 0x86.toByte(), 0x86.toByte(), 0x80.toByte(), 0xCF.toByte(),
            0x1F, 0x0C, 0x61,
        )

        val candidate = AnytimeFrames.inferCt5CipherFromCurrentRecord(frame)

        assertNotNull(candidate)
        candidate!!
        assertEquals(256, candidate.record.glucoseId)
        assertEquals(76, candidate.record.gluMgdl)
        assertTrue(candidate.record.hasGlucose)
        assertEquals(4, candidate.record.trend)
        assertEquals(0, candidate.record.errorCode)
        assertEquals(6.72f, candidate.record.iwNa, 0.01f)
        assertEquals(0.0f, candidate.record.ibNa, 0.01f)
        assertEquals(33.5f, candidate.record.temperatureC, 0.05f)
    }
}
