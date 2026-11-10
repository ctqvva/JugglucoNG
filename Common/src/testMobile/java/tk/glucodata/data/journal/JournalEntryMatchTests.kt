package tk.glucodata.data.journal

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The rule that decides whether an incoming journal entry is an entry the phone already has.
 *
 * This is what makes a repeated command idempotent, and it is the reason a watch can re-send an add
 * without producing a second row (#511, #502): the watch's identity arrives as the row's
 * `sourceRecordId`, and a repeat matches it here. It had no test — `upsertEntry` is a Room
 * transaction and nothing exercised it — so the decision was extracted into [existingEntry] and
 * pinned here. The wiring is source-checked by `WearJournalIdentityWiringTests`; the order the
 * identities are trusted in is the part that can be got wrong silently, so it is the part tested.
 */
class JournalEntryMatchTests {

    private fun entry(id: Long, sourceRecordId: String? = null) = JournalEntryEntity(
        id = id,
        timestamp = 1_700_000_000_000L,
        sensorSerial = "SIBI:0123456789ABCDEF",
        entryType = JournalEntryType.INSULIN.storageValue,
        title = "3.5 U",
        note = null,
        amount = 3.5f,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = null,
        foodId = null,
        source = JournalEntrySource.MANUAL.storageValue,
        sourceRecordId = sourceRecordId,
        recoveryId = null,
        createdAt = 0L,
        updatedAt = 0L,
        nsRemoteId = null,
    )

    @Test
    fun theSourceRecordIdMatchIsWhatMakesARepeatedAddOneRow() {
        val written = entry(1L, "wear:abcd1234:7")
        assertSame(
            "the same source record id is the same entry, whatever row the incoming one would have " +
                "been -- this is what stops a re-sent watch add becoming a second row",
            written,
            existingEntry(
                idMatch = null,
                recoveryMatch = null,
                overlapKeeper = null,
                sourceMatch = written,
                remoteMatch = null,
            ),
        )
        // A different watch entry, and a bare serial with no identity, are both new rows.
        assertNull(
            existingEntry(
                idMatch = null,
                recoveryMatch = null,
                overlapKeeper = null,
                sourceMatch = null,
                remoteMatch = null,
            ),
        )
    }

    @Test
    fun nothingMatchingIsANewRow() {
        assertNull(
            existingEntry(
                idMatch = null,
                recoveryMatch = null,
                overlapKeeper = null,
                sourceMatch = null,
                remoteMatch = null,
            ),
        )
    }

    @Test
    fun anExplicitIdBeatsEveryOtherIdentity() {
        val byId = entry(10L, "wear:x:1")
        val bySource = entry(11L, "wear:x:1")
        val byRemote = entry(12L)
        assertSame(
            byId,
            existingEntry(
                idMatch = byId,
                recoveryMatch = entry(13L),
                overlapKeeper = entry(14L),
                sourceMatch = bySource,
                remoteMatch = byRemote,
            ),
        )
    }

    @Test
    fun aCloneRecoveryIdBeatsTheOverlapAndTheSourceId() {
        val byRecovery = entry(20L)
        assertSame(
            byRecovery,
            existingEntry(
                idMatch = null,
                recoveryMatch = byRecovery,
                overlapKeeper = entry(21L),
                sourceMatch = entry(22L, "wear:x:2"),
                remoteMatch = entry(23L),
            ),
        )
    }

    @Test
    fun theOverlapKeeperBeatsTheSourceId() {
        // The overlap is what resolves two rows the server already ties together, so when it has a
        // keeper that row is the one to update, even if a sourceRecordId also matches another.
        val keeper = entry(30L)
        assertSame(
            keeper,
            existingEntry(
                idMatch = null,
                recoveryMatch = null,
                overlapKeeper = keeper,
                sourceMatch = entry(31L, "wear:x:3"),
                remoteMatch = entry(32L),
            ),
        )
    }

    @Test
    fun aNightscoutRemoteIdIsTheLastThingTried() {
        val byRemote = entry(40L)
        assertSame(
            byRemote,
            existingEntry(
                idMatch = null,
                recoveryMatch = null,
                overlapKeeper = null,
                sourceMatch = null,
                remoteMatch = byRemote,
            ),
        )
    }
}
