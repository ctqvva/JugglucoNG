package tk.glucodata

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The journal crosses the Data Layer as packed bytes, and the encoder lives on
 * the phone while the decoder runs on the watch, so nothing else would catch the
 * two drifting apart.
 */
class WearJournalSyncCodecTests {

    /**
     * Mirrors the phone's encoder. [version] lets these exercise the v1 shape a
     * not-yet-updated phone still sends, alongside the current one.
     */
    private fun payload(
        enabled: Boolean,
        entries: List<Triple<Long, Long, Pair<Int, Float>>>,
        titles: List<String>,
        presets: List<Pair<Long, String>> = emptyList(),
        version: Int = WearJournalSync.VERSION,
        entryPresetIds: List<Long> = List(entries.size) { 0L },
        entryCurves: List<List<Pair<Int, Float>>> = List(entries.size) { emptyList() },
        presetCurves: List<List<Pair<Int, Float>>> = List(presets.size) { emptyList() },
    ): ByteArray {
        val titleBytes = titles.map { it.toByteArray(StandardCharsets.UTF_8) }
        val presetBytes = presets.map { it.second.toByteArray(StandardCharsets.UTF_8) }
        val size = 1 + 1 + 2 +
            entries.indices.sumOf {
                8 + 8 + 1 + 4 + 1 + titleBytes[it].size +
                    (if (version >= 2) 8 else 0) +
                    (if (version >= 3) 1 + entryCurves[it].size * 6 else 0)
            } +
            2 + presetBytes.indices.sumOf {
                8 + 4 + 1 + presetBytes[it].size +
                    if (version >= 2) 1 + presetCurves[it].size * 6 else 0
            }
        val buffer = ByteBuffer.allocate(size)
        buffer.put(version.toByte())
        buffer.put(if (enabled) 1 else 0)
        buffer.putShort(entries.size.toShort())
        entries.forEachIndexed { index, (timestamp, id, typeAmount) ->
            buffer.putLong(timestamp)
            buffer.putLong(id)
            buffer.put(typeAmount.first.toByte())
            buffer.putFloat(typeAmount.second)
            buffer.put(titleBytes[index].size.toByte())
            buffer.put(titleBytes[index])
            if (version >= 2) buffer.putLong(entryPresetIds[index])
            if (version >= 3) {
                val curve = entryCurves[index]
                buffer.put(curve.size.toByte())
                curve.forEach { (minute, activity) ->
                    buffer.putShort(minute.toShort())
                    buffer.putFloat(activity)
                }
            }
        }
        buffer.putShort(presets.size.toShort())
        presets.forEachIndexed { index, (id, _) ->
            buffer.putLong(id)
            buffer.putFloat(Float.NaN)
            buffer.put(presetBytes[index].size.toByte())
            buffer.put(presetBytes[index])
            if (version >= 2) {
                val curve = presetCurves[index]
                buffer.put(curve.size.toByte())
                curve.forEach { (minute, activity) ->
                    buffer.putShort(minute.toShort())
                    buffer.putFloat(activity)
                }
            }
        }
        return buffer.array()
    }

    @Test
    fun carriesThePresetIdAndCurveAPredictionNeeds() {
        val data = payload(
            enabled = true,
            entries = listOf(Triple(9_000L, 8L, WearJournalSync.TYPE_INSULIN to 2.5f)),
            titles = listOf("Insulin 2.5U"),
            presets = listOf(3L to "Rapid"),
            entryPresetIds = listOf(3L),
            entryCurves = listOf(listOf(0 to 0f, 45 to 1f, 180 to 0f)),
            presetCurves = listOf(listOf(0 to 0f, 30 to 1f, 120 to 0.4f, 240 to 0f)),
        )

        val journal = WearJournalSync.decode(data)

        assertEquals(3L, journal.entries[0].presetId)
        assertEquals(3, journal.entries[0].curveMinutes.size)
        assertEquals(45, journal.entries[0].curveMinutes[1])
        assertEquals(1f, journal.entries[0].curveActivity[1], 0.0001f)
        assertEquals(4, journal.presets[0].curveMinutes.size)
        assertEquals(240, journal.presets[0].curveMinutes[3])
        assertEquals(1f, journal.presets[0].curveActivity[1], 0.0001f)
    }

    @Test
    fun aV1PayloadStillDecodesWithoutTheNewFields() {
        // An older phone sends no preset id and no curve; the watch must read
        // the entries it can rather than reject the whole journal, and a
        // prediction then treats those doses as unmodelled.
        val data = payload(
            enabled = true,
            entries = listOf(Triple(9_000L, 8L, WearJournalSync.TYPE_INSULIN to 2.5f)),
            titles = listOf("Insulin 2.5U"),
            presets = listOf(3L to "Rapid"),
            version = 1,
        )

        val journal = WearJournalSync.decode(data)

        assertTrue(journal.enabled)
        assertEquals(1, journal.entries.size)
        assertEquals(0L, journal.entries[0].presetId)
        assertEquals(0, journal.presets[0].curveMinutes.size)
    }

    @Test
    fun aV2PayloadFallsBackToThePresetCurve() {
        val data = payload(
            enabled = true,
            entries = listOf(Triple(9_000L, 8L, WearJournalSync.TYPE_INSULIN to 2.5f)),
            titles = listOf("Insulin 2.5U"),
            presets = listOf(3L to "Rapid"),
            version = 2,
            entryPresetIds = listOf(3L),
            presetCurves = listOf(listOf(0 to 0f, 30 to 1f, 240 to 0f)),
        )

        val journal = WearJournalSync.decode(data)

        assertEquals(3L, journal.entries[0].presetId)
        assertEquals(0, journal.entries[0].curveMinutes.size)
        assertEquals(3, journal.presets[0].curveMinutes.size)
    }

    @Test
    fun decodesEntriesNewestFirst() {
        val data = payload(
            enabled = true,
            entries = listOf(
                Triple(1_000L, 7L, WearJournalSync.TYPE_CARBS to 30f),
                Triple(9_000L, 8L, WearJournalSync.TYPE_INSULIN to 2.5f),
            ),
            titles = listOf("Carbs 30g", "Insulin 2.5U"),
            presets = listOf(3L to "Rapid"),
        )

        val journal = WearJournalSync.decode(data)

        assertTrue(journal.enabled)
        assertEquals(2, journal.entries.size)
        // Newest first, so the watch list needs no further sorting.
        assertEquals(9_000L, journal.entries[0].timestampMs)
        assertEquals("Insulin 2.5U", journal.entries[0].title)
        assertEquals(WearJournalSync.TYPE_INSULIN, journal.entries[0].type)
        assertEquals(2.5f, journal.entries[0].amount, 0.0001f)
        assertEquals(8L, journal.entries[0].id)
        assertEquals(WearJournalSync.TYPE_CARBS, journal.entries[1].type)
        assertEquals(1, journal.presets.size)
        assertEquals("Rapid", journal.presets[0].name)
    }

    @Test
    fun disabledJournalDecodesAsDisabledAndEmpty() {
        val journal = WearJournalSync.decode(payload(false, emptyList(), emptyList()))

        assertFalse(journal.enabled)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun futureVersionIsIgnoredRatherThanMisread() {
        val data = payload(true, emptyList(), emptyList())
        data[0] = (WearJournalSync.VERSION + 1).toByte()

        val journal = WearJournalSync.decode(data)

        assertFalse(journal.enabled)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun truncatedPayloadDoesNotYieldPartialEntries() {
        val full = payload(
            enabled = true,
            entries = listOf(Triple(1_000L, 1L, WearJournalSync.TYPE_CARBS to 12f)),
            titles = listOf("Carbs 12g"),
        )
        // Cut inside the title: a short Data Layer message must not surface an
        // entry with a mangled label. The entry now ends with its preset id and
        // snapshot curve, so the cut has to clear those to reach the title.
        val entryTailBytes = 8 + 4
        val truncated = full.copyOf(full.size - entryTailBytes)

        val journal = WearJournalSync.decode(truncated)

        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun aPayloadCutInsideThePresetIdKeepsTheEntry() {
        val full = payload(
            enabled = true,
            entries = listOf(Triple(1_000L, 1L, WearJournalSync.TYPE_CARBS to 12f)),
            titles = listOf("Carbs 12g"),
            entryPresetIds = listOf(9L),
        )
        // Everything the entry means is already read by this point; dropping the
        // preset id is exactly what a v1 phone does.
        val journal = WearJournalSync.decode(full.copyOf(full.size - 4))

        assertEquals(1, journal.entries.size)
        assertEquals(0L, journal.entries[0].presetId)
    }

    @Test
    fun commandRoundTripsThroughItsOwnFormat() {
        val data = ByteBuffer.allocate(1 + 1 + 8 + 8 + 1 + 4 + 8)
            .put(WearJournalSync.VERSION.toByte())
            .put(WearJournalSync.CMD_ADD.toByte())
            .putLong(4_321L)
            .putLong(0L)
            .put(WearJournalSync.TYPE_INSULIN.toByte())
            .putFloat(3.5f)
            .putLong(11L)
            .array()

        val command = WearJournalSync.decodeCommand(data)

        requireNotNull(command)
        assertEquals(WearJournalSync.CMD_ADD, command.command)
        assertEquals(4_321L, command.timestampMs)
        assertEquals(WearJournalSync.TYPE_INSULIN, command.type)
        assertEquals(3.5f, command.amount, 0.0001f)
        assertEquals(11L, command.presetId)
    }

    @Test
    fun shortCommandIsRejected() {
        assertNull(WearJournalSync.decodeCommand(byteArrayOf(WearJournalSync.VERSION.toByte(), 1)))
    }

    /** The 31 fixed bytes, plus an optional `<u16 length><utf-8>` entry identity. */
    private fun commandFrame(identity: String? = null): ByteArray {
        val tail = identity?.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + 1 + 8 + 8 + 1 + 4 + 8 + if (tail != null) 2 + tail.size else 0)
            .put(WearJournalSync.VERSION.toByte())
            .put(WearJournalSync.CMD_ADD.toByte())
            .putLong(4_321L)
            .putLong(0L)
            .put(WearJournalSync.TYPE_INSULIN.toByte())
            .putFloat(3.5f)
            .putLong(11L)
            .apply { if (tail != null) putShort(tail.size.toShort()).put(tail) }
            .array()
    }

    @Test
    fun aCommandFromABuildThatPredatesTheIdentityStillDecodes() {
        // The shape every build before this one sent. It must keep working unchanged, because
        // such a watch is exactly the peer that gets a de-duplication-less add.
        val command = WearJournalSync.decodeCommand(commandFrame())
        requireNotNull(command)
        assertEquals("", command.entryIdentity)
        assertEquals(4_321L, command.timestampMs)
        assertEquals(3.5f, command.amount, 0.0001f)
    }

    @Test
    fun aCommandCarriesTheIdentityTheWatchGaveTheEntry() {
        val command = WearJournalSync.decodeCommand(commandFrame("wear:node-1:7"))
        assertEquals("wear:node-1:7", command?.entryIdentity)
    }

    @Test
    fun anUnusableIdentityIsDroppedAndTheCommandStillApplies() {
        // The identity is a de-duplication hint, not content: refusing the command over it would
        // lose the entry, which is the bug #502 is about.
        val tooLong = "w".repeat(WearJournalSync.MAX_IDENTITY_BYTES + 1)
        assertEquals("", WearJournalSync.decodeCommand(commandFrame(tooLong))?.entryIdentity)
        assertEquals("", WearJournalSync.decodeCommand(commandFrame("with\u0000control"))?.entryIdentity)
        // A length that runs past the end of the frame is the same case.
        val lying = commandFrame("wear:node-1:7")
        lying[lying.size - 1] = 0x7F
        requireNotNull(WearJournalSync.decodeCommand(lying))
        assertEquals("", WearJournalSync.decodeCommand(lying)?.entryIdentity)
    }

    @Test
    fun aServedEntryKeepsTheIdentityItWasStoredWith() {
        val journal = WearJournalSync.decode(journalPayload(identities = listOf("wear:node-1:7", "")))
        val entry = journal.entries.first { it.timestampMs == 4_321L }
        assertEquals("wear:node-1:7", entry.entryIdentity)
        // Sorted for display, but the identity follows its own entry.
        assertEquals("", journal.entries.first { it.timestampMs == 9_999L }.entryIdentity)
    }

    @Test
    fun aServedPayloadFromAnOlderPhoneHasNoIdentitiesAndIsUnaffected() {
        val journal = WearJournalSync.decode(journalPayload(identities = null))
        assertTrue(journal.entries.isNotEmpty())
        assertTrue("a payload without the block must not invent identities", journal.entries.all { it.entryIdentity == "" })
    }

    @Test
    fun anImpossibleIdentityCountIsIgnored() {
        val journal = WearJournalSync.decode(journalPayload(identityCountOverride = 9_999))
        assertTrue(journal.entries.all { it.entryIdentity == "" })
    }

    /** One entry, no presets, with the appended identity block when [identities] is given. */
    private fun journalPayload(
        identities: List<String>? = null,
        identityCountOverride: Int? = null,
    ): ByteArray {
        val tail = identities?.map { it.toByteArray(Charsets.UTF_8) }
        // per entry: timestamp 8, id 8, type 1, amount 4, title length 1 + title, preset id 8,
        // curve count 1 + points
        val perEntry = 8 + 8 + 1 + 4 + 1 + 0 + 8 + 1
        val size = 1 + 1 + 2 + perEntry * 2 + 2 + (if (tail != null) 2 + tail.sumOf { 1 + it.size } else 0)
        return ByteBuffer.allocate(size)
            .put(WearJournalSync.VERSION.toByte())
            .put(1)
            .putShort(2)
            .putLong(4_321L)
            .putLong(1L)
            .put(WearJournalSync.TYPE_CARBS.toByte())
            .putFloat(30f)
            .put(0.toByte())
            .putLong(0L)
            .put(0.toByte())
            .putLong(9_999L)
            .putLong(2L)
            .put(WearJournalSync.TYPE_INSULIN.toByte())
            .putFloat(2f)
            .put(0.toByte())
            .putLong(0L)
            .put(0.toByte())
            .putShort(0)
            .apply {
                if (tail != null) {
                    putShort((identityCountOverride ?: tail.size).toShort())
                    tail.forEach {
                        put(it.size.toByte())
                        put(it)
                    }
                }
            }
            .array()
    }
}
