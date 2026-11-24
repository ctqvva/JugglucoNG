package tk.glucodata.data.journal

import androidx.room.withTransaction
import tk.glucodata.data.HistoryDatabase

/**
 * Whether both rows say the same thing to a destination: everything but the record of what has
 * been sent where. Compared in full and not by [JournalEntryEntity.updatedAt] alone, which two
 * edits in one millisecond share and a corrected clock can repeat.
 */
internal fun JournalEntryEntity.isSameRevision(other: JournalEntryEntity): Boolean =
    copy(
        nsUploadedAt = other.nsUploadedAt,
        nsRemoteId = other.nsRemoteId,
        lvUploadedAt = other.lvUploadedAt,
    ) == other

/** The upload time to record for a revision that went out; never one that reads as an edit since. */
internal fun JournalEntryEntity.deliveredAt(now: Long): Long = maxOf(now, updatedAt)

/** Marks the rows still saying what was sent. One edited or deleted meanwhile is left as it is. */
internal suspend fun HistoryDatabase.acknowledgeLibreviewUpload(sent: List<JournalEntryEntity>, now: Long) =
    withTransaction {
        val dao = journalDao()
        for (entry in sent) {
            val current = dao.getEntryById(entry.id) ?: continue
            if (current.isSameRevision(entry)) {
                dao.markEntryUploadedToLibreview(entry.id, current.deliveredAt(now))
            }
        }
    }

/** One request about to go out: the revision it carries and the document it may create. */
internal class NightscoutUploadAttempt(
    val entry: JournalEntryEntity,
    val identity: String,
    /** This attempt wrote the in-flight record; an earlier one whose outcome is unknown did not. */
    val recorded: Boolean,
    /** An earlier attempt at the same document was never answered, so it may exist already. */
    val resumed: Boolean,
)

internal class NightscoutUploadAck(
    /** False when the entry was deleted while its upload was on the way. */
    val entryKept: Boolean,
    /** False when the entry was edited meanwhile, and so still has to be sent. */
    val revisionCurrent: Boolean,
    /** Documents this upload made surplus, already queued for deletion. */
    val queuedDeletes: List<JournalPendingDeleteEntity>,
)

/**
 * Keeps an entry, its Nightscout document and the queue of documents to delete in agreement
 * while requests are on the way. The network is never inside a transaction, so every step
 * looks again at what the row says now:
 *
 *  - [begin] only sends what the row still says, and records the document first;
 *  - [accept] acknowledges the revision that was sent, not whatever the row holds by then. An
 *    edited entry keeps the accepted document's id but stays due, so its next upload replaces
 *    that document; a deleted one queues the document for deletion;
 *  - [settleAbandoned] queues documents whose entry went while nobody was waiting for the
 *    answer: deleted after a timeout, or around the end of the process.
 */
internal class NightscoutUploadLedger(private val database: HistoryDatabase) {
    private val dao = database.journalDao()

    /** @return null when the entry was edited or deleted since it was read; then nothing may be sent */
    suspend fun begin(entry: JournalEntryEntity, identity: String, now: Long): NightscoutUploadAttempt? =
        database.withTransaction {
            val current = dao.getEntryById(entry.id)
            if (current == null || !current.isSameRevision(entry)) return@withTransaction null
            // A change of the document the row already names creates nothing new.
            if (identity == current.nsRemoteId) {
                return@withTransaction NightscoutUploadAttempt(current, identity, recorded = false, resumed = false)
            }
            val resumed = dao.getNightscoutUpload(identity) != null
            dao.recordNightscoutUpload(
                JournalNightscoutUploadEntity(
                    remoteId = identity,
                    entryId = current.id,
                    entryCreatedAt = current.createdAt,
                    startedAt = now,
                )
            )
            NightscoutUploadAttempt(current, identity, recorded = !resumed, resumed = resumed)
        }

    /** The server answered and stored nothing, so this attempt left no document behind. */
    suspend fun refused(attempt: NightscoutUploadAttempt) {
        if (attempt.recorded) dao.clearNightscoutUpload(attempt.identity)
    }

    /**
     * @param acceptedRemoteId the id the server stored the document under, or null when it took
     *        the document without saying (v1 replacing one in place)
     */
    suspend fun accept(
        attempt: NightscoutUploadAttempt,
        acceptedRemoteId: String?,
        now: Long,
    ): NightscoutUploadAck = database.withTransaction {
        val sent = attempt.entry
        val current = dao.getEntryById(sent.id)?.takeIf { it.createdAt == sent.createdAt }
        val surplus = LinkedHashSet<String>()

        for (other in dao.getNightscoutUploadsOf(sent.id, sent.createdAt)) {
            if (other.remoteId == attempt.identity) continue
            // An earlier, unanswered attempt under another name (the time was corrected since).
            surplus += other.remoteId
            dao.clearNightscoutUpload(other.remoteId)
        }
        if (attempt.resumed && acceptedRemoteId != null && acceptedRemoteId != attempt.identity) {
            // v1: the unanswered attempt may have left a second document under the same name.
            surplus += attempt.identity
        }

        val revisionCurrent: Boolean
        if (current == null) {
            revisionCurrent = false
            surplus += acceptedRemoteId ?: attempt.identity
            sent.nsRemoteId?.let { surplus += it }
            dao.clearNightscoutUpload(attempt.identity)
        } else {
            revisionCurrent = current.isSameRevision(sent)
            // Not knowing the id means the server kept the document it had, so that one is
            // not surplus; the in-flight record stays, as the name to find it by.
            val remoteId = acceptedRemoteId
                ?: sent.nsRemoteId
                ?: JournalTreatmentUploader.localIdentifier(sent.id)
            dao.markEntryUploadedToNightscout(
                id = sent.id,
                remoteId = remoteId,
                uploadedAt = if (revisionCurrent) {
                    current.deliveredAt(now)
                } else {
                    // Sent, so the document is known as ours, but older than the row as it is.
                    minOf(sent.updatedAt, current.updatedAt - 1)
                },
            )
            if (acceptedRemoteId != null) dao.clearNightscoutUpload(attempt.identity)
            // The document is this entry's again, whatever an earlier deletion wanted of it.
            dao.clearPendingNightscoutDelete(remoteId)
            surplus -= remoteId
            if (JournalTreatmentUploader.oldCopyAction(sent.nsRemoteId, remoteId) ==
                JournalTreatmentUploader.OldCopyAction.DELETE
            ) {
                surplus += sent.nsRemoteId!!
            }
        }

        NightscoutUploadAck(
            entryKept = current != null,
            revisionCurrent = revisionCurrent,
            queuedDeletes = surplus.map { queueDelete(sent.id, it, now) },
        )
    }

    /** Queues the documents of entries that are gone. Runs between passes, when nothing is on the way. */
    suspend fun settleAbandoned(now: Long) = database.withTransaction {
        for (upload in dao.getNightscoutUploads()) {
            if (dao.getEntryById(upload.entryId)?.createdAt == upload.entryCreatedAt) continue
            queueDelete(upload.entryId, upload.remoteId, now)
            dao.clearNightscoutUpload(upload.remoteId)
        }
    }

    /** Keeps the refusal count of a deletion that was queued already. */
    private suspend fun queueDelete(entryId: Long, remoteId: String, now: Long): JournalPendingDeleteEntity {
        val tombstone = JournalPendingDeleteEntity(entryId = entryId, nsRemoteId = remoteId, deletedAt = now)
        dao.enqueuePendingNightscoutDeleteIfAbsent(tombstone)
        return dao.getPendingNightscoutDelete(remoteId) ?: tombstone
    }
}
