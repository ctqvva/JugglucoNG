package tk.glucodata.data

import androidx.room.withTransaction
import java.io.File
import tk.glucodata.CloneHistoryRecoveryProtocol
import tk.glucodata.CloneRecoveryCategories
import tk.glucodata.CloneRecoveryDirection
import tk.glucodata.CloneRecoveryManifest
import tk.glucodata.CloneRecoveryMode
import tk.glucodata.CloneRecoveryPackageIO
import tk.glucodata.CloneRecoveryRecord
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.journal.CloneJournalRecoveryRecords
import tk.glucodata.data.journal.CloneJournalRecoveryStore

/** Coordinates the mandatory glucose stream and optional recovery providers. */
internal class CloneHistoryRecoveryStore(
    private val database: HistoryDatabase,
) {
    private val glucose = CloneGlucoseRecoveryStore(database)
    private val journal = CloneJournalRecoveryStore(database)

    suspend fun createPackage(
        file: File,
        direction: CloneRecoveryDirection,
        mode: CloneRecoveryMode,
        includeJournal: Boolean = false,
        includeHypoClassifications: Boolean = false,
    ): CloneRecoveryManifest {
        require(!includeHypoClassifications) {
            "Hypo classification recovery is not available in this build"
        }
        val categories = CloneRecoveryCategories.selected(
            includeJournal = includeJournal,
            includeHypoClassifications = false,
        )
        val recordTypes = linkedSetOf<String>().apply {
            addAll(CloneGlucoseRecoveryRecords.recordTypes)
            if (includeJournal) addAll(CloneJournalRecoveryRecords.recordTypes)
        }
        val stats = CloneRecoveryPackageIO.write(
            file = file,
            declaredRecordTypes = recordTypes,
        ) { sink ->
            glucose.exportRecords(sink)
            if (includeJournal) journal.exportRecords(sink)
        }
        return CloneRecoveryManifest(
            protocolVersion = CloneHistoryRecoveryProtocol.PROTOCOL_VERSION,
            jobId = CloneHistoryRecoveryProtocol.newJobId(),
            direction = direction,
            mode = mode,
            categories = categories,
            compressedBytes = stats.compressedBytes,
            uncompressedBytes = stats.uncompressedBytes,
            recordCounts = stats.recordCounts,
            sha256 = stats.sha256,
        ).also(CloneHistoryRecoveryProtocol::validateManifest)
    }

    suspend fun importPackage(
        file: File,
        manifest: CloneRecoveryManifest,
        recoverySource: String,
    ) {
        require(manifest.categories and CloneRecoveryCategories.HYPO_CLASSIFICATIONS == 0) {
            "Hypo classification recovery is not available in this build"
        }
        val includeJournal = manifest.categories and CloneRecoveryCategories.JOURNAL != 0
        val expectedTypes = linkedSetOf<String>().apply {
            addAll(CloneGlucoseRecoveryRecords.recordTypes)
            if (includeJournal) addAll(CloneJournalRecoveryRecords.recordTypes)
        }
        require(manifest.recordCounts.keys == expectedTypes) {
            "Clone recovery package has invalid record categories"
        }
        val glucoseSession = glucose.newImportSession(manifest.mode, recoverySource)
        val journalSession = if (includeJournal) {
            journal.newImportSession(manifest.mode, recoverySource)
        } else {
            null
        }
        val validator = combinedValidator(includeJournal)
        CloneRecoveryPackageIO.visitValidated(
            file = file,
            manifest = manifest,
            recordValidator = validator,
            transaction = { operation -> database.withTransaction { operation() } },
            beforeVisit = {
                glucoseSession.beforeImport()
                journalSession?.beforeImport()
            },
            afterVisit = {
                glucoseSession.finishImport()
                journalSession?.finishImport()
            },
            visitor = { record ->
                when (record.type) {
                    in CloneGlucoseRecoveryRecords.recordTypes -> glucoseSession.accept(record)
                    in CloneJournalRecoveryRecords.recordTypes ->
                        journalSession?.accept(record)
                            ?: error("Journal record without selected journal category")
                    else -> error("unreachable")
                }
            },
        )
        journalSession?.notifyCommittedImport()
        UiRefreshBus.requestDataRefresh()
    }

    private fun combinedValidator(
        includeJournal: Boolean,
    ): suspend (CloneRecoveryRecord) -> Unit {
        val glucoseValidator = CloneGlucoseRecoveryRecords.orderedValidator()
        val journalValidator = CloneJournalRecoveryRecords.orderedValidator()
        var journalStarted = false
        return { record ->
            when (record.type) {
                in CloneGlucoseRecoveryRecords.recordTypes -> {
                    require(!journalStarted) { "Clone recovery records are out of order" }
                    glucoseValidator(record)
                }
                in CloneJournalRecoveryRecords.recordTypes -> {
                    require(includeJournal) { "Unexpected Clone journal recovery record" }
                    journalStarted = true
                    journalValidator(record)
                }
                else -> throw IllegalArgumentException(
                    "Unsupported Clone recovery record type"
                )
            }
        }
    }
}
