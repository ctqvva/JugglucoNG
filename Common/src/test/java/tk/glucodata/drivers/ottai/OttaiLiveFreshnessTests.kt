package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiLiveFreshnessTests {

    @Test
    fun acceptsV17MinuteFlooredLiveSampleFromTrace() {
        assertTrue(
            OttaiBleManager.isFreshLiveSample(
                receivedAtMs = 1_782_823_566_000L,
                sampleMs = 1_782_823_440_000L,
            ),
        )
    }

    @Test
    fun rejectsClearlyStaleLiveSample() {
        assertFalse(
            OttaiBleManager.isFreshLiveSample(
                receivedAtMs = 1_782_823_566_000L,
                sampleMs = 1_782_823_320_000L,
            ),
        )
    }

    @Test
    fun rejectsMissingTimestamps() {
        assertFalse(OttaiBleManager.isFreshLiveSample(0L, 1_782_823_440_000L))
        assertFalse(OttaiBleManager.isFreshLiveSample(1_782_823_566_000L, 0L))
    }

    private val arrival = 1_782_823_566_000L
    private val minute = 60_000L

    /**
     * bd2730ef's corrupt front, ~17k on a sensor at ~1.6k, dated from the anchor its live frame
     * set: ~10.7 days past its arrival. Stored, it became the mark every later live sample had to
     * clear.
     */
    @Test
    fun aSampleDatedDaysPastItsArrivalIsRefused() {
        val anchor = arrival - 1_600L * minute
        assertTrue(OttaiBleManager.isSampleAheadOfArrival(arrival, anchor + 17_000L * minute))
    }

    /** The bound shares the live freshness margin, so a sample that test accepts is not refused. */
    @Test
    fun theFutureBoundSitsAtTheLiveFreshnessMargin() {
        val margin = 180_000L
        assertFalse(OttaiBleManager.isSampleAheadOfArrival(arrival, 1_782_823_440_000L))
        assertFalse(OttaiBleManager.isSampleAheadOfArrival(arrival, arrival + margin))
        assertTrue(OttaiBleManager.isFreshLiveSample(arrival, arrival + margin))
        assertTrue(OttaiBleManager.isSampleAheadOfArrival(arrival, arrival + margin + 1L))
        assertFalse(OttaiBleManager.isFreshLiveSample(arrival, arrival + margin + 1L))
    }

    /**
     * History used to move the last-glucose mark, so a backfilled record dated ahead became the bar
     * every later live sample had to clear. Only a fresh live sample moves it now, and a mark left
     * past the margin by a clock step back counts as none.
     */
    @Test
    fun onlyAFreshLiveSampleMovesTheGlucoseMark() {
        // History dated fresh enough to pass for a live sample still does not move the mark: under
        // the HEAD rule (!live || fresh) a backfilled record became the bar live samples had to
        // clear, and publishing and storing stopped until the clock caught up with it.
        assertFalse(OttaiBleManager.advancesGlucoseMark(live = false, receivedAtMs = arrival, sampleMs = arrival - 30_000L, previousGlucoseAtMs = arrival - 2L * minute))
        assertFalse(OttaiBleManager.advancesGlucoseMark(live = false, receivedAtMs = arrival, sampleMs = arrival + 10L * minute, previousGlucoseAtMs = 0L))
        // An unfresh live sample does not move it either.
        assertFalse(OttaiBleManager.advancesGlucoseMark(live = true, receivedAtMs = arrival, sampleMs = arrival - 10L * minute, previousGlucoseAtMs = 0L))
        assertTrue(OttaiBleManager.advancesGlucoseMark(live = true, receivedAtMs = arrival, sampleMs = arrival, previousGlucoseAtMs = arrival - minute))
        assertFalse(OttaiBleManager.advancesGlucoseMark(live = true, receivedAtMs = arrival, sampleMs = arrival - 2L * minute, previousGlucoseAtMs = arrival - minute))

        assertEquals(0L, OttaiBleManager.glucoseMarkBaseline(arrival, arrival + 10L * minute))
        assertEquals(arrival + 180_000L, OttaiBleManager.glucoseMarkBaseline(arrival, arrival + 180_000L))
        assertEquals(arrival - 10L * minute, OttaiBleManager.glucoseMarkBaseline(arrival, arrival - 10L * minute))
    }

    private fun takesAnchor(live: Boolean, dataNo: Int, lastDataNo: Int, confirmed: Boolean, anchoredMs: Long) =
        OttaiBleManager.datesFromStreamAnchor(live, dataNo, lastDataNo, confirmed, arrival, anchoredMs)

    /**
     * A confirmed start used to freeze the stream anchor for the life of the process (#11), so a
     * clock step left every later live sample unfresh and unpublished. The anchor now dates a new
     * live record only while that date agrees with the arrival. History has no arrival time of
     * its own and always takes it; an unconfirmed new record goes back through the live path,
     * which is where the second corroborating read comes from.
     */
    @Test
    fun aConfirmedAnchorIsHeldToTheArrivalTime() {
        val onTime = arrival - 45_000L
        val stepped = arrival - 10L * minute
        assertTrue(takesAnchor(live = true, dataNo = 1_601, lastDataNo = 1_600, confirmed = true, anchoredMs = onTime))
        assertFalse(takesAnchor(live = true, dataNo = 1_601, lastDataNo = 1_600, confirmed = true, anchoredMs = stepped))
        assertTrue(takesAnchor(live = false, dataNo = 1_601, lastDataNo = 1_600, confirmed = false, anchoredMs = stepped))
        assertFalse(takesAnchor(live = true, dataNo = 1_601, lastDataNo = 1_600, confirmed = false, anchoredMs = onTime))
    }

    /**
     * The last stored record read again — a notify and the poll behind it, or a counter that
     * stopped — keeps its anchored date however stale: re-dated by its arrival it would be stored
     * and published as a new reading. A record below the stored mark does not: a corrupt dataNo
     * can leave the mark ahead of the sensor, and the true records behind it must still re-anchor
     * rather than wait for the counter to catch up.
     */
    @Test
    fun onlyTheLastStoredRecordReadAgainIsHeldToAStaleAnchor() {
        val stale = arrival - 10L * minute
        assertTrue(takesAnchor(live = true, dataNo = 1_600, lastDataNo = 1_600, confirmed = true, anchoredMs = stale))
        assertTrue(takesAnchor(live = true, dataNo = 1_600, lastDataNo = 1_600, confirmed = false, anchoredMs = stale))
        assertFalse(takesAnchor(live = true, dataNo = 1_601, lastDataNo = 1_720, confirmed = true, anchoredMs = stale))
    }

    /**
     * The claim a held frame leaves behind is what the next record is checked against, so a stale
     * claim has to give way to the frame that disagrees with it. Keeping the standing claim
     * instead (the mutant: claim only when there is none) leaves that stale claim standing for the
     * life of the process, and the frames that disagree with it stay held — #11 again, live data
     * neither stored nor published.
     */
    @Test
    fun aHeldFrameTakesOverTheClaimAndTheNextRecordAgreesWithIt() {
        val stale = arrival - 1_700L * minute // a claim left behind by an earlier record
        val stepped = stale + 10L * minute // the start every frame claims after a clock step
        assertEquals(stepped to 1_701, OttaiBleManager.heldLiveClaim(stale, 1_600, stepped, 1_701))
        // A claim from a corrupt dataNo above the sensor gives way to the genuine record below it
        // too: taken over only from above, it would hold every record up to the corrupt one.
        val genuine = arrival - 1_601L * minute
        assertEquals(genuine to 1_601, OttaiBleManager.heldLiveClaim(stale, 1_700, genuine, 1_601))
        // The same record held again keeps its first claim rather than re-stamping it later.
        val claim = stepped to 1_701
        assertEquals(claim, OttaiBleManager.heldLiveClaim(claim.first, claim.second, stepped + minute, 1_701))
        // The next record claims the same start, so the anchor may move onto it.
        assertTrue(OttaiBleManager.datesLiveByArrival(true, 1_702, 1_700, claim.first, claim.second, stepped))
    }

    /**
     * Once the start is confirmed, a single frame moves the anchor only onto the confirmed start.
     * monitor-live may not anchor anywhere else: a 12-byte record whose dataNo and runtime disagree
     * would shift it by the disagreement. A frame dated by neither is held until a frame of another
     * record claims the same start, so one corrupt dataNo cannot re-date the stream (and the
     * history requested behind it).
     */
    @Test
    fun aConfirmedAnchorMovesOnlyWhenTwoRecordsAgree() {
        val confirmedStart = arrival - 1_700L * minute
        assertTrue(OttaiBleManager.monitorMayAnchor(confirmedStartMs = 0L, monitorStartMs = arrival))
        assertTrue(OttaiBleManager.monitorMayAnchor(confirmedStart, confirmedStart + minute))
        assertFalse(OttaiBleManager.monitorMayAnchor(confirmedStart, confirmedStart - 10L * minute))

        val held = arrival - 1_601L * minute // the start claimed by the held frame of record 1601
        // The first frame after the commit that disagrees with the anchor: held.
        assertFalse(OttaiBleManager.datesLiveByArrival(true, 1_601, 1_600, 0L, -1, held))
        // The next record claims the same start: the anchor moves.
        assertTrue(OttaiBleManager.datesLiveByArrival(true, 1_602, 1_600, held, 1_601, held))
        // The held record read again proves nothing.
        assertFalse(OttaiBleManager.datesLiveByArrival(true, 1_601, 1_600, held, 1_601, held))
        // A corrupt front held earlier does not vouch for a genuine claim.
        assertFalse(OttaiBleManager.datesLiveByArrival(true, 1_602, 1_600, arrival - 1_650L * minute, 1_650, held))
        // The last stored record read again is not dated by its arrival once confirmed, even when
        // the claim of the record just below it sits in the window and agrees on the start.
        assertFalse(OttaiBleManager.datesLiveByArrival(true, 1_602, 1_602, held, 1_601, held))
        // Unconfirmed, the arrival dates a new frame at once, as before.
        assertTrue(OttaiBleManager.datesLiveByArrival(false, 1_601, 1_600, 0L, -1, held))
        // A claim from hundreds of records ago is not a second read of this disagreement: both
        // starts are the arrival minute minus dataNo * interval, so they agree as long as the clock
        // offset has not changed in between, and one frame would move the confirmed anchor alone.
        assertFalse(OttaiBleManager.datesLiveByArrival(true, 1_961, 1_960, held, 1_601, held))
        // A frame below the claiming record — where a corrupt low dataNo lands — is not one either.
        assertFalse(OttaiBleManager.datesLiveByArrival(true, 1_598, 1_600, held, 1_601, held))
        // A gap of more than one record is fine: frames are lost to jamming all the time.
        assertTrue(OttaiBleManager.datesLiveByArrival(true, 1_603, 1_600, held, 1_601, held))
        // A neighbouring record whose claimed start disagrees is refused on the start, not the gap.
        assertFalse(OttaiBleManager.datesLiveByArrival(true, 1_602, 1_600, arrival - 1_650L * minute, 1_601, held))
    }

    /**
     * The 2026-08-01 room-backfill round trip: live read at 1785605688, history requested at
     * 1785605689, its payload back a second later — the re-read at 1785605690 returned the same
     * front=14332 the live read had just brought in.
     */
    @Test
    fun skipsPostHistoryLiveReadRightAfterALiveFrame() {
        assertFalse(
            OttaiBleManager.shouldReadLiveAfterHistory(
                receivedAtMs = 1_785_605_689_000L,
                lastLiveFrameAtMs = 1_785_605_688_000L,
            ),
        )
    }

    @Test
    fun readsLiveAfterAHistoryBurstThatEndedBehindWallTime() {
        assertTrue(
            OttaiBleManager.shouldReadLiveAfterHistory(
                receivedAtMs = 1_785_605_689_000L,
                lastLiveFrameAtMs = 1_785_605_299_000L,
            ),
        )
    }

    @Test
    fun readsLiveWhenNoLiveFrameHasBeenSeenYet() {
        assertTrue(OttaiBleManager.shouldReadLiveAfterHistory(1_785_605_689_000L, 0L))
    }

    /** One whole record has passed, so the sensor has something new to give. */
    @Test
    fun readsLiveExactlyOneRecordIntervalAfterTheLastFrame() {
        assertTrue(
            OttaiBleManager.shouldReadLiveAfterHistory(
                receivedAtMs = 1_785_605_688_000L + 60_000L,
                lastLiveFrameAtMs = 1_785_605_688_000L,
            ),
        )
        assertFalse(
            OttaiBleManager.shouldReadLiveAfterHistory(
                receivedAtMs = 1_785_605_688_000L + 59_999L,
                lastLiveFrameAtMs = 1_785_605_688_000L,
            ),
        )
    }

    @Test
    fun readsLiveWhenTheClockSteppedBackwards() {
        assertTrue(
            OttaiBleManager.shouldReadLiveAfterHistory(
                receivedAtMs = 1_785_605_688_000L,
                lastLiveFrameAtMs = 1_785_605_988_000L,
            ),
        )
    }

    @Test
    fun unfreshLiveSampleDoesNotPersistOrAdvanceDataNo() {
        assertFalse(
            OttaiBleManager.shouldPersistGlucoseSample(
                live = true,
                freshLiveSample = false,
                sampleMs = 1_000L,
                previousGlucoseAtMs = 0L,
            ),
        )
        assertFalse(OttaiBleManager.shouldAdvanceSeenDataNo(advancesDataNo = true, persist = false))
    }

    @Test
    fun freshNewerLiveSamplePersistsAndAdvancesDataNo() {
        assertTrue(
            OttaiBleManager.shouldPersistGlucoseSample(
                live = true,
                freshLiveSample = true,
                sampleMs = 2_000L,
                previousGlucoseAtMs = 1_000L,
            ),
        )
        assertTrue(OttaiBleManager.shouldAdvanceSeenDataNo(advancesDataNo = true, persist = true))
    }

    @Test
    fun historyAlwaysPersistsEvenWhenUnfresh() {
        assertTrue(
            OttaiBleManager.shouldPersistGlucoseSample(
                live = false,
                freshLiveSample = false,
                sampleMs = 1_000L,
                previousGlucoseAtMs = 0L,
            ),
        )
    }

    @Test
    fun endedLiveIndexIgnoresCorruptFrontAboveCeiling() {
        fun mk(dataNo: Int, runtime: Int) = OttaiRecord(dataNo, 5, runtime, 100, 35.0, ByteArray(12))
        val latest = OttaiBleManager.endedLiveIndexRecord(
            listOf(mk(1600, 96_000), mk(17_000, 17_000 * 60), mk(65535, 0)),
            ceiling = 1720,
        )
        assertEquals(1600, latest!!.dataNo)
    }

    @Test
    fun endedLiveIndexIgnoresInvalidAndInsaneRecordsWhenUnbounded() {
        fun mk(dataNo: Int, runtime: Int) = OttaiRecord(dataNo, 5, runtime, 100, 35.0, ByteArray(12))
        val latest = OttaiBleManager.endedLiveIndexRecord(
            listOf(mk(65535, 0), mk(200, 0), mk(100, 6_000)),
            ceiling = Int.MAX_VALUE,
        )
        assertEquals(100, latest!!.dataNo)
    }

    @Test
    fun unfreshLiveBackfillAsksForTheSampleDataNo() {
        assertEquals(
            1601,
            OttaiBleManager.liveBackfillEndExclusive(
                persisted = false,
                lastDataNo = 1599,
                sampleDataNo = 1600,
            ),
        )
        assertEquals(
            1600,
            OttaiBleManager.liveBackfillEndExclusive(
                persisted = true,
                lastDataNo = 1600,
                sampleDataNo = 1600,
            ),
        )
        val missingUnfresh = 1601 - 1599 - 1
        assertEquals(1, missingUnfresh)
    }

    @Test
    fun unfreshLiveAfterRoomBackfillStillRequestsHistory() {
        assertTrue(
            OttaiBleManager.shouldFetchHistoryAfterUnfreshLive(
                persisted = false,
                historyAlreadyIssued = false,
                previousForHistory = 1599,
                liveEndExclusive = 1601,
            ),
        )
        assertFalse(
            OttaiBleManager.shouldFetchHistoryAfterUnfreshLive(
                persisted = false,
                historyAlreadyIssued = true,
                previousForHistory = 1599,
                liveEndExclusive = 1601,
            ),
        )
        assertFalse(
            OttaiBleManager.shouldFetchHistoryAfterUnfreshLive(
                persisted = true,
                historyAlreadyIssued = false,
                previousForHistory = 1600,
                liveEndExclusive = 1600,
            ),
        )
        assertFalse(
            OttaiBleManager.shouldAdvanceSeenDataNo(advancesDataNo = true, persist = false),
        )
        assertTrue(
            OttaiBleManager.shouldArmHoleRetryForSkippedLive(
                previousLastDataNo = 100,
                newDataNo = 103,
            ),
        )
        assertFalse(
            OttaiBleManager.shouldArmHoleRetryForSkippedLive(
                previousLastDataNo = 100,
                newDataNo = 101,
            ),
        )
    }

    @Test
    fun persistedLiveJumpLedgersTheSkippedRange() {
        val skipped = OttaiBleManager.skippedLiveRange(previousLastDataNo = 100, newDataNo = 103)
        assertEquals(101, skipped!!.first)
        assertEquals(102, skipped.last)
    }

    @Test
    fun endedIndexCeilingCapsCorruptE12FrontWithoutStart() {
        val ceiling = OttaiBleManager.endedLiveDataNoCeiling(
            authoritativeStartMs = 0L,
            nowMs = 1_785_000_000_000L,
            lastDataNo = 1_600,
        )
        assertEquals(1_720, ceiling)
        fun mk(dataNo: Int, runtime: Int) = OttaiRecord(dataNo, 5, runtime, 100, 35.0, ByteArray(12))
        val latest = OttaiBleManager.endedLiveIndexRecord(
            listOf(mk(1_600, 96_000), mk(17_000, 17_000 * 60)),
            ceiling = ceiling,
        )
        assertEquals(1_600, latest!!.dataNo)
    }

    @Test
    fun endedIndexCeilingUsesElapsedWhenStartIsKnown() {
        val start = 1_785_000_000_000L - 20_000L * 60_000L
        val now = 1_785_000_000_000L
        val ceiling = OttaiBleManager.endedLiveDataNoCeiling(
            authoritativeStartMs = start,
            nowMs = now,
            lastDataNo = 1_600,
        )
        assertEquals(20_120, ceiling)
        assertTrue(OttaiBleManager.isPersistedDataNoAheadOfLive(17_000, 1_600))
        assertFalse(OttaiBleManager.isPersistedDataNoAheadOfLive(1_600, 20_000))
    }

    /**
     * The 2026-09-22 field case, a sensor activated by this app, with its times shifted by whole
     * minutes: the command was acknowledged at 1700000282 and record 0 arrived at 1700000344, then
     * one a minute. A 9-byte record's runtime is
     * synthesized as dataNo * 60, the start of its minute, so a monitor time built on the bare
     * command instant passed the monitor-live arrival check and anchored the stream at 1700000280,
     * before the command was written; every reading was stamped 64-66 s before it arrived.
     * Without that instant the first frame goes to the arrival path, as it did once re-added.
     */
    @Test
    fun anInAppActivationDatesItsFirstNineByteRecordByArrival() {
        val version = "E1.2.3(V1.7.SH2542.1)"
        val ack = 1_700_000_282_400L
        fun frame(dataNo: Int) = byteArrayOf(
            0, 0, 0, 0,
            (dataNo and 0xFF).toByte(), (dataNo shr 8).toByte(),
            0xFF.toByte(), 0xFF.toByte(),
            0x1F, 0x3C, 0x34, 0xE1.toByte(), 0x15, 0xBA.toByte(), 0x47, 0xC5.toByte(), 0x0B,
        )
        fun monitor(dataNo: Int, baseMs: Long) = OttaiParser.toReading(
            OttaiParser.frameRecords(frame(dataNo), version).single(), "", emptyList(), baseMs,
        ).monitorTimeMs
        fun base(dataNo: Int, cloudMs: Long, streamMs: Long, reliable: Boolean) =
            OttaiBleManager.monitorBaseStartMs(frame(dataNo), version, null, cloudMs, streamMs, reliable, ack, 0L)

        // The defect: on the bare command instant, record 0 passes the monitor-live check and
        // floors to a start two seconds before the command was acknowledged.
        val arrival0 = 1_700_000_344_000L
        val old = monitor(0, OttaiBleManager.officialStartMs(0L, 0L, false, ack))
        assertEquals(1_700_000_280_000L, OttaiBleManager.monitorLiveStartMs(arrival0, old, 0, 0L))

        // Fixed: no monitor time, so monitor-live cannot anchor it; unconfirmed, its arrival does.
        assertEquals(0L, base(0, 0L, 0L, false))
        assertEquals(0L, monitor(0, base(0, 0L, 0L, false)))
        // What resolveSampleTimeMs then seeds: floor(arrival) - dataNo minutes, "live". Computed
        // here, not run: that path is private. monitorBaseStartMsFeedsTheParser pins only the base
        // that keeps record 0 off the monitor-live path.
        val start = arrival0 / minute * minute

        // Field-data walk-through, not a second test of the dating logic: record 1 keeps the
        // reliable anchor record 0 left (only the bare command instant is withheld), so its
        // monitor time lands inside the monitor-live window and agrees with that start.
        val start1 = OttaiBleManager.monitorLiveStartMs(1_700_000_405_000L, monitor(1, base(1, 0L, start, true)), 1, 0L)
        assertEquals(start, start1)
        assertTrue(OttaiBleManager.startsCorroborate(start, 0, start1, 1))

        // Records 10-15 as they arrived, dated from the committed start: each stamp inside its own
        // minute before arrival (the defect put them 64-66 s early).
        listOf(345L, 406L, 466L, 526L, 586L, 646L).forEachIndexed { i, s ->
            val dataNo = 10 + i
            val arrival = (1_700_000_600L + s) * 1000L
            val anchored = OttaiBleManager.trustedStreamStartMs(start, false, start) + dataNo * minute
            assertTrue(OttaiBleManager.datesFromStreamAnchor(true, dataNo, dataNo - 1, true, arrival, anchored))
            assertEquals(anchored, monitor(dataNo, base(dataNo, start, start, false)))
            assertTrue("lag=${arrival - anchored}ms", arrival - anchored in 0L until minute)
        }
    }

    /**
     * Only the bare command instant is withheld, and only from a record whose runtime the parser
     * made up. A cloud or committed start and a checked anchor are grid origins already: moving
     * them would re-date a running sensor (a vendor-activated one among them). A real
     * runtime is the sensor's own count from activation and keeps the command instant. The layout
     * is decided as frameRecords decides it, learned size included.
     */
    @Test
    fun onlyTheBareCommandInstantIsWithheldFromASynthesizedRuntime() {
        val cloud = 1_697_588_520_000L
        val anchor = 1_700_000_340_000L
        val ack = 1_700_000_282_400L
        val nine = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(),
            0x1F, 0x3C, 0x34, 0xE1.toByte(), 0x15, 0xBA.toByte(), 0x47, 0xC5.toByte(), 0x0B,
        )
        val e12 = "E1.2.3(V1.7.SH2542.1)"
        fun base(payload: ByteArray, version: String, learned: Int?, cloudMs: Long, streamMs: Long, reliable: Boolean, pendingMs: Long = 0L) =
            OttaiBleManager.monitorBaseStartMs(payload, version, learned, cloudMs, streamMs, reliable, ack, pendingMs)
        assertEquals(cloud, base(nine, e12, null, cloud, anchor, true))
        assertEquals(anchor, base(nine, e12, null, 0L, anchor, true))
        assertEquals(0L, base(nine, e12, null, 0L, anchor, false))
        assertEquals(0L, base(nine, e12, null, 0L, 0L, false))
        assertEquals(
            OttaiBleManager.officialStartMs(0L, anchor, false, ack),
            base(ByteArray(16), "V1.5", null, 0L, anchor, false),
        )
        assertEquals(0L, base(nine, "", OttaiParser.BLE_RECORD_SIZE_E12, 0L, 0L, false))
        assertEquals(ack, base(nine, "", OttaiParser.BLE_RECORD_SIZE, 0L, 0L, false))
        // The pending claim is the fallback only: a cloud or committed start and a checked anchor win.
        val pending = 1_700_000_400_000L
        assertEquals(cloud, base(nine, e12, null, cloud, anchor, true, pending))
        assertEquals(cloud, base(nine, e12, null, cloud, 0L, false, pending))
        assertEquals(anchor, base(nine, e12, null, 0L, anchor, true, pending))
        // Unchecked anchor, synthesized runtime: the claim, never the command instant.
        assertEquals(pending, base(nine, e12, null, 0L, anchor, false, pending))
        // A real runtime keeps the instant and never takes the claim.
        assertEquals(ack, base(nine, "", OttaiParser.BLE_RECORD_SIZE, 0L, 0L, false, pending))
    }

    /**
     * The fix lives in one call: handleGlucosePayload must build the parser's base with
     * monitorBaseStartMs, after learnRecordSize (the layout decides whether the runtime is the
     * record's own). Put effectiveActiveTimeMs() back and record 0 of an in-app activation is
     * floored to the minute before its command again; every test above would stay green, because
     * handleGlucosePayload is private and BLE-driven. So its wiring is pinned in the source, the
     * way OttaiNativeShellCapacityTests pins the native sizing.
     */
    @Test
    fun monitorBaseStartMsFeedsTheParser() {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !java.io.File(dir, "Common/src/main/java").isDirectory) dir = dir.parentFile
        val manager = java.io.File(dir ?: error("repo root not found"),
            "Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt")
            .readText().replace(Regex("\\s+"), " ")
        val from = manager.indexOf("private fun handleGlucosePayload(")
        assertTrue(from >= 0)
        val body = manager.substring(from, manager.indexOf("val readings = if (live)", from))
        val learned = body.indexOf("learnRecordSize(payload)")
        val base = body.indexOf(
            "val activeMs = monitorBaseStartMs( payload, materials.deviceVersion, heldRecordSize(), " +
                "materials.activeTimeMs, streamStartTimeMs, streamStartReliable, activationCommandSentAtMs, " +
                "pendingConfirmedStartMs, )",
        )
        assertTrue("activeMs must come from monitorBaseStartMs", base >= 0)
        assertTrue("and only once the record size is learned", learned in 0 until base)
        // Both parser calls, live and history, take that base and nothing else.
        val parsing = manager.substring(from).substringBefore("val previousDataNo = lastDataNo")
        assertFalse(parsing.contains("effectiveActiveTimeMs()"))
        assertTrue(parsing.contains(
            "OttaiParser.toReading(records.last(), materials.method, materials.coefficients, activeMs)",
        ))
        assertTrue(parsing.contains(
            "records.map { OttaiParser.toReading(it, materials.method, materials.coefficients, activeMs) }",
        ))
        assertEquals(2, Regex("OttaiParser\\.toReading\\(").findAll(parsing).count())
    }

    /**
     * A reconnect between record 0 and the commit, with the 2026-09-22 field case's timing (times
     * shifted by whole minutes): record 0 was
     * notified at 1700000344, so its claim is 1700000340 (second p = 4), and record 1 at
     * 1700000405. The new link does not trust the kept anchor, and the first frame is the post-auth
     * read, whose phase in the minute is random. Dated by arrival, it claimed one minute late
     * whenever p plus its delay crossed a minute. That claim corroborated the pending one and was
     * committed. Built on the pending claim, the read keeps the start.
     */
    @Test
    fun aReconnectBeforeTheCommitKeepsTheFirstRecordsStart() {
        // Same fixture as anInAppActivationDatesItsFirstNineByteRecordByArrival.
        val version = "E1.2.3(V1.7.SH2542.1)"
        val ack = 1_700_000_282_400L
        val pending = 1_700_000_340_000L
        fun frame(dataNo: Int) = byteArrayOf(
            0, 0, 0, 0,
            (dataNo and 0xFF).toByte(), (dataNo shr 8).toByte(),
            0xFF.toByte(), 0xFF.toByte(),
            0x1F, 0x3C, 0x34, 0xE1.toByte(), 0x15, 0xBA.toByte(), 0x47, 0xC5.toByte(), 0x0B,
        )
        // The claim a live frame makes on the monitor-live path, or 0 when it goes to the arrival path.
        fun claim(dataNo: Int, arrivalMs: Long, streamMs: Long, reliable: Boolean, pendingMs: Long): Long {
            val base = OttaiBleManager.monitorBaseStartMs(
                frame(dataNo), version, null, 0L, streamMs, reliable, ack, pendingMs,
            )
            val monitor = OttaiParser.toReading(
                OttaiParser.frameRecords(frame(dataNo), version).single(), "", emptyList(), base,
            ).monitorTimeMs
            return OttaiBleManager.monitorLiveStartMs(arrivalMs, monitor, dataNo, 0L)
        }
        // A copy of resolveSampleTimeMs's arrival start, floor(arrival) - dataNo minutes. Computed
        // here, not run: that path is private. Used only to show the old outcome.
        fun arrivalStart(arrivalMs: Long, dataNo: Int) = arrivalMs / minute * minute - dataNo * minute

        // Record 1 read at 1700000461, 56 s after its notify would have come.
        val read1 = 1_700_000_461_000L
        // Walk-through of the old base (no claim): the arrival path claims a minute late, and
        // that claim corroborates the pending one.
        assertEquals(0L, claim(1, read1, pending, false, 0L))
        assertEquals(pending + minute, arrivalStart(read1, 1))
        assertTrue(OttaiBleManager.startsCorroborate(pending, 0, arrivalStart(read1, 1), 1))
        // Fixed: the read echoes the pending claim and passes its own arrival check.
        assertEquals(pending, claim(1, read1, pending, false, pending))

        // Record 0 read again at 1700000402, before record 1 exists. The offer ignores the same
        // record, but the seed moved the anchor before it did.
        val read0 = 1_700_000_402_000L
        val notify1 = 1_700_000_405_000L
        // Walk-through of the old base: the arrival seed moved the anchor to 1700000400, record
        // 1's notify echoed that reliable anchor, and the start corroborated the pending one.
        assertEquals(0L, claim(0, read0, pending, false, 0L))
        assertEquals(pending + minute, arrivalStart(read0, 0))
        val lateEcho = claim(1, notify1, pending + minute, true, pending)
        assertEquals(pending + minute, lateEcho)
        assertTrue(OttaiBleManager.startsCorroborate(pending, 0, lateEcho, 1))
        // Fixed: the re-read claims the pending start, so the anchor stays on it (the notify on
        // that anchor is a walk-through: a reliable anchor was already the base).
        assertEquals(pending, claim(0, read0, pending, false, pending))
        assertEquals(pending, claim(1, notify1, pending, true, pending))

        // A claim this frame's arrival disagrees with (a corrupt dataNo three days off) does not
        // anchor: the frame falls to its arrival and the pair disagrees, as before.
        val wrong = pending - 3L * 24 * 60 * minute
        assertEquals(0L, claim(1, read1, pending, false, wrong))
        assertFalse(OttaiBleManager.startsCorroborate(wrong, 0, arrivalStart(read1, 1), 1))
        // Confirmed, a monitor time may anchor only within CONFIRMED_START_AGREEMENT_MS of the
        // confirmed start, boundary included.
        assertEquals(pending, OttaiBleManager.monitorLiveStartMs(read1, pending + minute, 1, pending))
        assertEquals(pending + 2 * minute, OttaiBleManager.monitorLiveStartMs(read1, pending + 3 * minute, 1, pending))
        assertEquals(0L, OttaiBleManager.monitorLiveStartMs(read1, pending + 4 * minute, 1, pending))
    }
}
