package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch's outbox for journal entries (#502).
 *
 * The bug it fixes: an entry added while the phone was out of reach was announced as saved and then
 * vanished on the next serve, because the send went nowhere and the local cache was the only copy.
 * These pin the two halves of the fix that can be tested without a device — the persisted form, and
 * what a serve is able to prove.
 *
 * They cannot cover the transport or the store, which is why [WearJournalIdentityWiringTests] exists
 * for the rest of the path, and they deliberately do not claim to test the retry rules: those read
 * `MessageSender`, so they are pinned by reading the source.
 */
class WearJournalOutboxTests {

    private val newline = "\n"

    private val add = WearJournalSync.Pending(
        identity = "wear:abcd1234:1",
        command = WearJournalSync.CMD_ADD,
        timestampMs = 1_700_000_000_000L,
        entryId = 0L,
        type = WearJournalSync.TYPE_INSULIN,
        amount = 3.5f,
        presetId = 11L,
        sentOnce = true,
        title = "3.5 U",
    )

    private val delete = WearJournalSync.Pending(
        identity = "wear:abcd1234:2",
        command = WearJournalSync.CMD_DELETE,
        timestampMs = 1_700_000_000_000L,
        entryId = 42L,
        type = WearJournalSync.TYPE_NOTE,
        amount = Float.NaN,
        presetId = 0L,
        sentOnce = true,
    )

    @Test
    fun theQueueSurvivesBeingWrittenAndReadBack() {
        val restored = WearJournalSync.decodeOutbox(WearJournalSync.encodeOutbox(listOf(add, delete)))
        assertEquals(listOf(add, delete), restored)
    }

    @Test
    fun anAmountKeepsItsExactValueThroughStorage() {
        // A queue that turned 3.5 into 3.4999999 would be a quiet data change in the dose the phone
        // ends up storing.
        for (amount in listOf(0.1f, 3.5f, 17f, 0.75f, 12.25f)) {
            val item = add.copy(amount = amount)
            assertEquals(
                amount,
                WearJournalSync.decodeOutbox(WearJournalSync.encodeOutbox(listOf(item)))[0].amount,
                0f,
            )
        }
    }

    @Test
    fun aTitleWithASeparatorInItSurvivesStorage() {
        // The queue is a line-based string, so the only thing keeping a title intact is that the
        // title is the last field and the separator is a control character.
        val item = add.copy(title = "3.5 U ${WearJournalSync.TYPE_CARBS}: with a colon")
        val restored = WearJournalSync.decodeOutbox(WearJournalSync.encodeOutbox(listOf(item)))
        assertEquals(item.title, restored[0].title)
    }

    @Test
    fun oneUnreadableLineCostsThatItemAndNotTheQueue() {
        val unknownCommand = WearJournalSync.encodeOutbox(listOf(delete.copy(command = 99)))
        val stored = listOf(
            WearJournalSync.encodeOutbox(listOf(add)),
            "not-a-record",
            unknownCommand,
        ).joinToString(newline)
        val restored = WearJournalSync.decodeOutbox(stored)
        assertEquals(listOf(add), restored)
    }

    @Test
    fun anEmptyOrAbsentStoreIsAnEmptyQueue() {
        assertTrue(WearJournalSync.decodeOutbox(null).isEmpty())
        assertTrue(WearJournalSync.decodeOutbox("").isEmpty())
        assertTrue(WearJournalSync.decodeOutbox("   $newline  ").isEmpty())
    }

    @Test
    fun theEchoIsReportedByTheCodecOnlyWhenTheBlockIsThere() {
        val withIdentity = frame(entryIdentity = add.identity)
        assertTrue(
            "a block with one identity means the phone de-duplicates watch entries",
            WearJournalSync.decode(withIdentity).identityEcho,
        )
        assertEquals(add.identity, WearJournalSync.decode(withIdentity).entries[0].entryIdentity)

        assertTrue(
            "a count of zero is still the block",
            WearJournalSync.decode(frame(entryIdentity = "")).identityEcho,
        )
        assertTrue(
            "no block at all means this phone de-duplicates nothing, and a re-sent add would land twice",
            !WearJournalSync.decode(frame(entryIdentity = null)).identityEcho,
        )
    }

    @Test
    fun aPendingEntryIsDistinguishableFromThePhonesOwn() {
        val pending = WearJournalSync.Entry(
            timestampMs = add.timestampMs,
            id = 0L,
            type = add.type,
            amount = add.amount,
            title = add.title,
            entryIdentity = add.identity,
            pending = true,
        )
        val confirmed = pending.copy(id = 77L, pending = false)
        assertTrue(pending.pending)
        assertTrue(!confirmed.pending)
        // Equal apart from the flag, so the row does not re-render as a new entry when the serve
        // confirms it -- only the id the phone assigns changes.
        assertEquals(pending.copy(id = 77L, pending = false), confirmed)
    }

    @Test
    fun anAddIsConfirmedOnlyByItsOwnIdentityOnAPhoneThatEchoes() {
        val arrived = entry(id = 77L, identity = add.identity)
        assertTrue(
            "our identity, in a serve that echoes identities: this is ours",
            WearJournalSync.confirmedBy(served(arrived, echo = true), add),
        )
        assertTrue(
            "the same serve without the echo block proves nothing -- this is the case that would " +
                "silently drop a pending entry",
            !WearJournalSync.confirmedBy(served(arrived, echo = false), add),
        )
        assertTrue(
            "an equal timestamp and amount is the phone's own entry, not proof of ours",
            !WearJournalSync.confirmedBy(
                served(entry(id = 77L, identity = ""), echo = true),
                add,
            ),
        )
    }

    @Test
    fun aDeleteIsConfirmedByTheRowBeingGone() {
        assertTrue(
            "a serve without the row proves the delete landed, and any phone can confirm that",
            WearJournalSync.confirmedBy(served(entry(id = 77L, identity = ""), echo = false), delete),
        )
        assertTrue(
            "the row is still there, so the delete is not done",
            !WearJournalSync.confirmedBy(
                served(entry(id = delete.entryId, identity = ""), echo = false),
                delete,
            ),
        )
    }

    /**
     * Whether an item **that has already been sent** may go again; the never-sent case is handled
     * by the caller, which the wiring test pins.
     */
    @Test
    fun anAddIsOnlyRepeatedAgainstAPhoneThatDeDuplicates() {
        assertTrue("a delete is idempotent, so it repeats against anyone", WearJournalSync.mayRepeat(delete, false))
        assertTrue("with the echo, an add may go again", WearJournalSync.mayRepeat(add, true))
        assertTrue(
            "without the echo, a re-sent add is a second row on the phone",
            !WearJournalSync.mayRepeat(add, false),
        )
    }

    private fun entry(id: Long, identity: String) = WearJournalSync.Entry(
        timestampMs = add.timestampMs,
        id = id,
        type = add.type,
        amount = add.amount,
        title = add.title,
        entryIdentity = identity,
    )

    private fun served(vararg entries: WearJournalSync.Entry, echo: Boolean) =
        WearJournalSync.Journal(enabled = true, entries = entries.toList(), identityEcho = echo)

    @Test
    fun onlyTheIdentityEchoCanProveAnAddArrived() {
        val arrived = WearJournalSync.Entry(
            timestampMs = add.timestampMs,
            id = 77L,
            type = add.type,
            amount = add.amount,
            title = add.title,
            entryIdentity = add.identity,
        )
        assertTrue("this is our entry, so the queue can drop it", arrived.entryIdentity == add.identity)
        assertTrue(
            "the same entry without an identity is the phone's own, and proves nothing about ours",
            arrived.copy(entryIdentity = "").entryIdentity != add.identity,
        )
    }

    /** One entry, no presets, with the appended identity block unless [entryIdentity] is null. */
    private fun frame(entryIdentity: String?): ByteArray {
        val tail = entryIdentity?.toByteArray(Charsets.UTF_8)
        val size = 1 + 1 + 2 + (8 + 8 + 1 + 4 + 1 + 8 + 1) + 2 + if (tail != null) 2 + 1 + tail.size else 0
        return Frame(size)
            .putByte(WearJournalSync.VERSION.toByte())
            .putByte(1)
            .putShort(1)
            .putLong(1_700_000_000_000L)
            .putLong(1L)
            .putByte(WearJournalSync.TYPE_CARBS.toByte())
            .putFloat(30f)
            .putByte(0)
            .putLong(0L)
            .putByte(0)
            .putShort(0)
            .also { frame ->
                if (tail != null) frame.putShort(1).putByte(tail.size.toByte()).putBytes(tail)
            }
            .array()
    }
}

/** A growable big-endian frame, so these tests read like the wire they describe. */
private class Frame(private val size: Int) {
    private val bytes = ByteArray(size)
    private var at = 0

    fun putByte(value: Byte) = apply { bytes[at++] = value }

    fun putShort(value: Int) = apply {
        putByte((value shr 8).toByte())
        putByte(value.toByte())
    }

    fun putLong(value: Long) = apply {
        for (shift in 56 downTo 0 step 8) putByte((value shr shift).toByte())
    }

    fun putFloat(value: Float) = apply {
        val bits = java.lang.Float.floatToIntBits(value)
        putByte((bits shr 24).toByte())
        putByte((bits shr 16).toByte())
        putByte((bits shr 8).toByte())
        putByte(bits.toByte())
    }

    fun putBytes(value: ByteArray) = apply { value.forEach { putByte(it) } }

    fun array(): ByteArray = bytes
}
