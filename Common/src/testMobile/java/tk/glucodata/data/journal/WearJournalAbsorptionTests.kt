package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.WearJournalSync

/**
 * Slice 2 of the watch COB plan (option iii): every carb entry reaches the watch with the
 * absorption minutes the phone's own COB would use, so the watch never needs a rate fallback.
 * Goes through the real encoder and decoder, and compares against `JournalIobMath.activeCarbsGrams`
 * -- the phone's number -- rather than a recomputed expectation.
 */
class WearJournalAbsorptionTests {

    private val t0 = 1_700_000_000_000L

    private fun minutes(min: Long) = min * 60_000L

    private fun entry(
        type: JournalEntryType = JournalEntryType.CARBS,
        amount: Float? = 40f,
        durationMinutes: Int? = null,
        timestamp: Long = t0,
        id: Long = 1L,
    ) = JournalEntry(
        id = id,
        timestamp = timestamp,
        sensorSerial = null,
        type = type,
        title = "",
        note = null,
        amount = amount,
        glucoseValueMgDl = null,
        durationMinutes = durationMinutes,
        intensity = null,
        insulinPresetId = null,
        foodId = null,
        proteinGrams = null,
        fatGrams = null,
        source = JournalEntrySource.MANUAL,
        sourceRecordId = null,
        createdAt = timestamp,
        updatedAt = timestamp,
    )

    private fun roundTrip(entries: List<JournalEntry>, rate: (Long) -> Float): List<WearJournalSync.Entry> =
        WearJournalSync.decode(WearJournalBridge.encode(entries, emptyList(), rate))
            .entries.sortedBy { it.id }

    @Test
    fun anExplicitDurationIsCarriedAsIs() {
        val out = roundTrip(listOf(entry(durationMinutes = 90))) { 35f }
        assertEquals(90, out.single().absorptionMinutes)
    }

    @Test
    fun aMissingDurationIsResolvedFromTheRateAtThatInstant() {
        // 40 g at 20 g/h is 120 min; the rate is read at the entry's own timestamp.
        val out = roundTrip(
            listOf(entry(timestamp = t0, id = 1), entry(timestamp = t0 + minutes(600), id = 2)),
        ) { ts -> if (ts < t0 + minutes(300)) 20f else 40f }
        assertEquals(120, out[0].absorptionMinutes)
        assertEquals(60, out[1].absorptionMinutes)
    }

    @Test
    fun aResolvedDurationIsClampedLikeThePhonesCob() {
        val tiny = roundTrip(listOf(entry(amount = 5f))) { 35f }.single()
        val huge = roundTrip(listOf(entry(amount = 300f))) { 10f }.single()
        assertEquals(30, tiny.absorptionMinutes)
        assertEquals(360, huge.absorptionMinutes)
    }

    @Test
    fun aHostileExplicitDurationIsClampedToTheUnsignedShortAndNeverThrows() {
        val out = roundTrip(
            listOf(
                entry(durationMinutes = 1_000_000, id = 1),
                entry(durationMinutes = 0, id = 2),
                entry(durationMinutes = -5, id = 3),
            ),
        ) { 35f }
        assertEquals(65_535, out[0].absorptionMinutes)
        // The phone treats anything under a minute as a minute, and 0 on the wire means absent.
        assertEquals(1, out[1].absorptionMinutes)
        assertEquals(1, out[2].absorptionMinutes)
    }

    @Test
    fun aHostileRateNeverThrowsAndStillClamps() {
        for (rate in listOf(0f, -3f, Float.POSITIVE_INFINITY, Float.NaN)) {
            val out = roundTrip(listOf(entry())) { rate }.single().absorptionMinutes
            assertTrue("rate=$rate -> $out", out == null || out in 30..360)
        }
    }

    @Test
    fun onlyCarbEntriesCarryMinutes() {
        val out = roundTrip(
            listOf(
                entry(type = JournalEntryType.INSULIN, amount = 4f, durationMinutes = 90, id = 1),
                entry(type = JournalEntryType.NOTE, amount = null, id = 2),
            ),
        ) { 35f }
        assertNull(out[0].absorptionMinutes)
        assertNull(out[1].absorptionMinutes)
    }

    @Test
    fun anUnusableAmountCarriesNoMinutes() {
        for (amount in listOf(0f, -2f, Float.NaN, null)) {
            val out = roundTrip(listOf(entry(amount = amount))) { 35f }.single()
            assertNull("amount=$amount", out.absorptionMinutes)
        }
    }

    @Test
    fun theWatchSeesTheMinutesThePhoneUsesForCob() {
        val entries = listOf(
            entry(amount = 40f, durationMinutes = 90, timestamp = t0 - minutes(30), id = 1),
            entry(amount = 24f, timestamp = t0 - minutes(60), id = 2),
            entry(amount = 10f, timestamp = t0 - minutes(10), id = 3),
        )
        val rate = { _: Long -> 30f }
        val phoneCarbs = entries.map {
            CarbEntry(it.timestamp, it.amount!!, it.durationMinutes?.toFloat())
        }
        val wireCarbs = roundTrip(entries, rate).map {
            CarbEntry(it.timestampMs, it.amount, it.absorptionMinutes?.toFloat())
        }
        for (offset in listOf(0L, 20, 45, 90, 400)) {
            val at = t0 + minutes(offset)
            assertEquals(
                "COB at +$offset min",
                JournalIobMath.activeCarbsGrams(phoneCarbs, at, rate),
                JournalIobMath.activeCarbsGrams(wireCarbs, at) { error("watch must not need a rate") },
                // The wire rounds a resolved duration to whole minutes: well under a gram.
                0.2f,
            )
        }
    }

    @Test
    fun aWorstCaseJournalStillFitsOneDataLayerMessage() {
        val title = "t".repeat(40)
        val entries = (1..60).map {
            entry(timestamp = t0 + it, id = it.toLong(), durationMinutes = 90).copy(
                title = title,
                sourceRecordId = "w".repeat(WearJournalSync.MAX_IDENTITY_BYTES),
                insulinCurveJsonSnapshot = (0 until 40).joinToString(";") { m -> "${m * 5}:${(m % 7) / 7f}" },
            )
        }
        val presets = (1..20).map {
            JournalInsulinPreset(
                id = it.toLong(), displayName = "p".repeat(40), onsetMinutes = 0, durationMinutes = 240,
                accentColor = 0, curveJson = (0 until 40).joinToString(";") { m -> "${m * 5}:${(m % 7) / 7f}" },
                isBuiltIn = false, isArchived = false, countsTowardIob = true, sortOrder = it,
            )
        }
        val bytes = WearJournalBridge.encode(entries, presets) { 35f }
        // MessageClient.MAX_MESSAGE_SIZE is 100 KiB.
        assertTrue("payload is ${bytes.size} bytes", bytes.size < 100 * 1024)
        val journal = WearJournalSync.decode(bytes)
        assertEquals(60, journal.entries.size)
        assertTrue(journal.entries.all { it.absorptionMinutes == 90 })
        assertTrue(journal.entries.all { it.entryIdentity.length == WearJournalSync.MAX_IDENTITY_BYTES })
    }
}
