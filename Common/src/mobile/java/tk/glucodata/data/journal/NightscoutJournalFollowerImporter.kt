package tk.glucodata.data.journal

import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.NightscoutTreatmentImportBridge
import tk.glucodata.data.HistoryDatabase

@Keep
object NightscoutJournalFollowerImporter : NightscoutTreatmentImportBridge {
    private const val LOG_ID = "NightscoutJournalFollowerImporter"

    /**
     * Held while a follower posts its own entries to the server it reads. An import landing
     * between a document's POST and the row being marked as uploaded would not yet know the
     * document is ours, and would add it again as a Nightscout row.
     *
     * The import does not wait for it: it runs on the follower's poll, and a send held up by a
     * slow server would hold up the next reading and its alerts with it. A batch skipped here
     * is fetched again by the next poll.
     */
    internal val followerSendLock = Mutex()

    @Keep
    override fun importTreatments(sensorId: String, treatmentsJson: String): Int {
        if (!followerSendLock.tryLock()) {
            Log.i(LOG_ID, "Nightscout journal sync deferred: own entries are being sent")
            return 0
        }
        try {
            return runBlocking {
                withContext(Dispatchers.IO) {
                    runCatching { importInternal(sensorId, treatmentsJson) }
                        .onFailure { Log.e(LOG_ID, "importTreatments failed: ${Log.stackline(it)}") }
                        .getOrDefault(0)
                }
            }
        } finally {
            followerSendLock.unlock()
        }
    }

    private suspend fun importInternal(sensorId: String, treatmentsJson: String): Int {
        val trimmed = treatmentsJson.trim()
        if (trimmed.isEmpty()) return 0
        val array = JSONArray(trimmed)
        if (array.length() == 0) return 0

        val repository = JournalRepository()
        repository.deleteAapsSenderOnlyNotesOnce()
        repository.ensureDefaultInsulinPresets()
        val presets = repository.getInsulinPresetsSnapshot()
        val journalDao = HistoryDatabase.getInstance(Applic.app).journalDao()
        val pendingDeleteRemoteIds = journalDao
            .getPendingNightscoutDeletes()
            .mapNotNull { it.nsRemoteId.trim().takeIf(String::isNotBlank) }
            .toSet()
        // Remote IDs this device itself uploaded to Nightscout. Re-importing them
        // would duplicate the local rows they came from, so these — and only these
        // — are skipped. Therapy uploaded by other JugglucoNG devices, or fetched by
        // a follow-only install, is still imported.
        val ownUploadedRemoteIds = journalDao
            .getOwnUploadedNightscoutRemoteIds()
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .toSet()
        val sourcePrefix = "nightscout:${sensorId.trim().ifBlank { "unknown" }}"
        var imported = 0
        var deleted = 0
        val context = Applic.app

        val treatments = (0 until array.length()).mapNotNull(array::optJSONObject)
            .map { it to NightscoutInsulinIdentity.fromTreatment(it) }
        // A failed remote delete can leave multiple revisions visible. Compare both the
        // current batch and the dose already stored from a previous poll.
        val newestInsulinRevisions = mutableMapOf<String, Long>()
        for ((_, identity) in treatments) {
            if (identity == null) continue
            newestInsulinRevisions.merge(identity.baseId, identity.revision, ::maxOf)
        }
        val sourceIds = newestInsulinRevisions.keys.associateWith { baseId ->
            JournalTreatmentTransfer.sourceRecordIdForBaseId(sourcePrefix, baseId, JournalEntryType.INSULIN)
        }
        val storedInsulinIdentities = sourceIds.values.chunked(500)
            .flatMap { journalDao.getEntriesBySourceRecordIds(it) }
            .associate { it.sourceRecordId to NightscoutInsulinIdentity.parse(it.nsRemoteId) }

        for ((treatment, identity) in treatments) {
            if (JournalTreatmentTransfer.hasAnyRemoteIdentifier(treatment, ownUploadedRemoteIds)) continue
            if (JournalTreatmentTransfer.hasAnyRemoteIdentifier(treatment, pendingDeleteRemoteIds)) continue
            if (identity != null) {
                if (identity.revision < newestInsulinRevisions.getValue(identity.baseId)) continue
                val stored = storedInsulinIdentities[sourceIds.getValue(identity.baseId)]
                if (stored?.baseId == identity.baseId && stored.revision > identity.revision) continue
            }
            val parsed = JournalTreatmentTransfer.parseTreatment(
                context = context,
                treatment = treatment,
                source = JournalEntrySource.NIGHTSCOUT,
                sourcePrefix = sourcePrefix,
                insulinPresets = presets
            ) ?: continue

            if (parsed.deleteOnly) {
                deleted += repository.deleteEntriesBySourceRecordIds(parsed.candidateSourceRecordIds)
                continue
            }

            for (input in parsed.inputs) {
                repository.upsertEntry(input)
                imported++
            }
            val importedIds = parsed.inputs.mapNotNull { it.sourceRecordId }.toSet()
            val staleIds = parsed.candidateSourceRecordIds.filterNot { it in importedIds }
            deleted += repository.deleteEntriesBySourceRecordIds(staleIds)
        }

        if (imported > 0 || deleted > 0) {
            Log.i(LOG_ID, "Nightscout journal sync imported=$imported deleted=$deleted")
        }
        return imported
    }
}
