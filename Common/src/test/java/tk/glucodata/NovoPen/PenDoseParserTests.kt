package tk.glucodata.NovoPen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser turns pen bytes into insulin the journal will count as delivered, so the cases
 * that matter are the ones where a wrong answer invents or loses therapy.
 */
class PenDoseParserTests {

    private val now = 1_700_000_000L
    private val reference = now - 3_600L

    /** 12 byte record: relative time BE, FF 00 marker, tenths BE, 08 00 00 marker, flags. */
    private fun record(relativeSeconds: Long, tenths: Int, flags: Int = 0): ByteArray = byteArrayOf(
        (relativeSeconds shr 24).toByte(),
        (relativeSeconds shr 16).toByte(),
        (relativeSeconds shr 8).toByte(),
        relativeSeconds.toByte(),
        0xFF.toByte(), 0x00,
        (tenths shr 8).toByte(), tenths.toByte(),
        0x08, 0x00, 0x00, flags.toByte(),
    )

    private fun records(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        parts.forEach { part ->
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    @Test
    fun decodesTimeAndUnits() {
        val doses = PenDoseParser.parse(reference, record(relativeSeconds = 600, tenths = 85), now)

        assertEquals(1, doses.size)
        assertEquals(reference + 600, doses[0].timestampSeconds)
        assertEquals(8.5f, doses[0].units, 0.001f)
    }

    @Test
    fun keepsPenStatusFlags() {
        val doses = PenDoseParser.parse(reference, record(600, 20, flags = 0x40), now)

        assertEquals(0x40, doses[0].flags)
    }

    @Test
    fun rejectsRecordWithWrongMarker() {
        val broken = record(600, 85).also { it[4] = 0x00 }

        assertTrue(PenDoseParser.parse(reference, broken, now).isEmpty())
    }

    @Test
    fun rejectsRecordWithWrongTrailer() {
        val broken = record(600, 85).also { it[8] = 0x09 }

        assertTrue(PenDoseParser.parse(reference, broken, now).isEmpty())
    }

    @Test
    fun rejectsImplausiblyLargeDose() {
        // 61.0 U: past what any NovoPen delivers, so it is a decode error, not a bolus.
        assertTrue(PenDoseParser.parse(reference, record(600, 610), now).isEmpty())
    }

    @Test
    fun rejectsZeroDose() {
        assertTrue(PenDoseParser.parse(reference, record(600, 0), now).isEmpty())
    }

    @Test
    fun rejectsDoseInTheFuture() {
        val future = PenDoseParser.parse(reference, record(7_200, 50), now)

        assertTrue(future.isEmpty())
    }

    @Test
    fun rejectsDoseBeyondTheAgeLimit() {
        val ancient = PenDoseParser.parse(
            reference - PenDoseParser.MAX_AGE_SECONDS - 60L,
            record(0, 50),
            now,
        )

        assertTrue(ancient.isEmpty())
    }

    @Test
    fun tallyNamesWhatTheWindowRefused() {
        // One ancient chunk, one chunk with a record far ahead and a good one: a tally
        // spans a scan, and the caller learns about the two it lost without diffing.
        val tally = PenDropTally()
        val ancient = PenDoseParser.parse(
            reference - PenDoseParser.MAX_AGE_SECONDS - 60L, record(0, 50), now, tally,
        )
        val kept = PenDoseParser.parse(reference, records(record(7_200, 40), record(600, 30)), now, tally)

        assertTrue(ancient.isEmpty())
        assertEquals(1, kept.size)
        assertEquals(1, tally.count(PenDropReason.TOO_OLD))
        assertEquals(1, tally.count(PenDropReason.IN_THE_FUTURE))
        assertTrue(tally.describe(), tally.describe().contains("1 too_old, 1 in_the_future"))
        assertTrue(tally.describe(), tally.describe().contains("rel=7200"))
    }

    @Test
    fun tallyIgnoresRecordsThatNeverDecoded() {
        // Garbage is not a dropped dose; only well-formed records the window rejected count.
        val tally = PenDropTally()
        val broken = record(600, 85).also { it[4] = 0x00 }

        PenDoseParser.parse(reference, broken, now, tally)

        assertTrue(tally.isEmpty)
        assertEquals("dropped none", tally.describe())
    }

    @Test
    fun tallyKeepsOnlyTheFirstFewInDetail() {
        // A long log has hundreds past the age limit; count them all, spell out three.
        val tally = PenDropTally()
        val raw = records(*Array(10) { record(it.toLong(), 50) })

        PenDoseParser.parse(reference - PenDoseParser.MAX_AGE_SECONDS - 60L, raw, now, tally)

        assertEquals(10, tally.count(PenDropReason.TOO_OLD))
        assertEquals(3, tally.describe().split(" | ").size)
    }

    @Test
    fun ignoresTrailingPartialRecord() {
        val raw = records(record(600, 50), byteArrayOf(0x00, 0x01, 0x02))

        assertEquals(1, PenDoseParser.parse(reference, raw, now).size)
    }

    @Test
    fun returnsDosesOldestFirst() {
        val raw = records(record(1_200, 30), record(600, 50))

        val doses = PenDoseParser.parse(reference, raw, now)

        assertEquals(listOf(reference + 600, reference + 1_200), doses.map { it.timestampSeconds })
    }

    @Test
    fun flagsSmallDoseShortlyBeforeARealOneAsPriming() {
        val raw = records(record(600, 20), record(630, 80))

        val doses = PenDoseParser.parse(reference, raw, now)

        assertTrue(doses[0].priming)
        assertFalse(doses[1].priming)
    }

    @Test
    fun doesNotFlagSmallDoseStandingOnItsOwn() {
        // Same 2 U, but nothing follows within a minute: a real correction, not an air shot.
        val raw = records(record(600, 20), record(1_200, 80))

        val doses = PenDoseParser.parse(reference, raw, now)

        assertFalse(doses[0].priming)
    }

    @Test
    fun doesNotFlagLargeDoseFollowedClosely() {
        val raw = records(record(600, 30), record(630, 80))

        assertFalse(PenDoseParser.parse(reference, raw, now)[0].priming)
    }

    @Test
    fun mergeDropsDosesTheSegmentsOverlapOn() {
        val first = PenDoseParser.parse(reference, records(record(600, 50), record(1_200, 30)), now)
        val second = PenDoseParser.parse(reference, records(record(1_200, 30), record(1_800, 40)), now)

        val merged = PenDoseParser.merge(listOf(first, second))

        assertEquals(3, merged.size)
        assertEquals(
            listOf(reference + 600, reference + 1_200, reference + 1_800),
            merged.map { it.timestampSeconds },
        )
    }

    @Test
    fun exposesThePenOwnCounterAsTheDoseIdentity() {
        val doses = PenDoseParser.parse(reference, record(relativeSeconds = 600, tenths = 85), now)

        assertEquals(600L, doses[0].relativeSeconds)
    }

    @Test
    fun mergeDropsAnOverlapEvenWhenTheSegmentsWereAnchoredApart() {
        // What issue #195 hit: two segments of one scan, anchored a second apart, so the
        // same injection came out with two different epoch seconds.
        val first = PenDoseParser.parse(reference, record(600, 50), now)
        val second = PenDoseParser.parse(reference + 1, record(600, 50), now)

        val merged = PenDoseParser.merge(listOf(first, second))

        assertEquals(1, merged.size)
    }

    @Test
    fun mergeReclassifiesPrimingAcrossChunkBoundaries() {
        // The air shot and the bolus it precedes arrived in different segments; only the
        // merged view can see that they belong together.
        val first = PenDoseParser.parse(reference, record(600, 20), now)
        val second = PenDoseParser.parse(reference, record(640, 90), now)

        val merged = PenDoseParser.merge(listOf(first, second))

        assertTrue(merged[0].priming)
    }

    @Test
    fun handlesNullPayload() {
        assertTrue(PenDoseParser.parse(reference, null, now).isEmpty())
    }

    /**
     * First segment of a real NovoPen 6 (serial G252101365, firmware 01.08.01), captured
     * from a device trace on 2026-08-08. Anchors the record layout to hardware rather than
     * to a reading of the native decoder.
     */
    private val deviceSegment = hexBytes(
        "00C72CAB FF00 0028 08000000" +
            "00C72C93 FF00 0028 08000000" +
            "00C66413 FF00 003C 08000000" +
            "00C61045 FF00 0014 08000000" +
            "00C5D78F FF00 001E 08000000" +
            "00C5D774 FF00 0028 08000000" +
            "00C55B57 FF00 0014 08000000" +
            "00C515E7 FF00 0014 08000000" +
            "00C51588 FF00 000A 08000000" +
            "00C51586 FF00 000A 08000000" +
            "00C51584 FF00 000A 08000000" +
            "00C51582 FF00 0014 08000000" +
            "00C51580 FF00 000A 08000000" +
            "00C51506 FF00 0028 08000000" +
            "00C4BE9C FF00 001E 08000000" +
            "00C488C5 FF00 001E 08000000" +
            "00C48897 FF00 0028 08000000" +
            "00C41C3B FF00 000A 08000000"
    )

    /** The pen's own relative-time counter at the moment this segment was read. */
    private val devicePenClock = 13_065_895L
    private val deviceReference = now - devicePenClock

    @Test
    fun decodesARealPenSegment() {
        val doses = PenDoseParser.parse(deviceReference, deviceSegment, now)

        assertEquals(18, doses.size)
        // Newest record in the segment: 4.0 U, 12796 s before the read.
        val newest = doses.last()
        assertEquals(4.0f, newest.units, 0.001f)
        assertEquals(now - 12_796L, newest.timestampSeconds)
        // Whole segment is 1–6 U, which is what this pen actually delivers.
        assertTrue(doses.all { it.units in 1.0f..6.0f })
    }

    @Test
    fun realPenSegmentArrivesNewestFirstAndIsSorted() {
        val doses = PenDoseParser.parse(deviceReference, deviceSegment, now)

        // On the wire the newest record comes first; the parser hands back oldest first.
        assertEquals(deviceReference + 12_852_283L, doses.first().timestampSeconds)
        assertEquals(deviceReference + 13_053_099L, doses.last().timestampSeconds)
        assertEquals(doses.map { it.timestampSeconds }.sorted(), doses.map { it.timestampSeconds })
    }

    @Test
    fun flagsTheAirShotBurstInTheRealSegment() {
        val doses = PenDoseParser.parse(deviceReference, deviceSegment, now)
        val burst = doses.filter { it.timestampSeconds >= deviceReference + 12_916_096L &&
            it.timestampSeconds <= deviceReference + 12_916_104L }

        assertEquals(5, burst.size)
        // Four 1–2 U shots two seconds apart get flagged. The last of the burst does not:
        // the next dose is 95 s later, past the window. The reviewer unticks it if it was
        // an air shot too — better than the parser guessing therapy away.
        assertEquals(4, burst.count(PenDose::priming))
        assertFalse(burst.last().priming)
    }

    private fun hexBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
