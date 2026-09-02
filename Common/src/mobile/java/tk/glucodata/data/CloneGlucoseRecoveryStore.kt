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
import tk.glucodata.GlucoseReadingSource

internal class CloneGlucoseRecoveryStore(
    private val database: HistoryDatabase,
) {
    private val historyDao = database.historyDao()
    private val uncertaintyDao = database.readingUncertaintyDao()
    private val displayDao = database.readingDisplayDao()

    suspend fun createGlucosePackage(
        file: File,
        direction: CloneRecoveryDirection,
        mode: CloneRecoveryMode,
    ): CloneRecoveryManifest {
        val stats = CloneRecoveryPackageIO.write(
            file = file,
            declaredRecordTypes = CloneGlucoseRecoveryRecords.recordTypes,
            block = ::exportRecords,
        )
        return CloneRecoveryManifest(
            protocolVersion = CloneHistoryRecoveryProtocol.PROTOCOL_VERSION,
            jobId = CloneHistoryRecoveryProtocol.newJobId(),
            direction = direction,
            mode = mode,
            categories = CloneRecoveryCategories.GLUCOSE,
            compressedBytes = stats.compressedBytes,
            uncompressedBytes = stats.uncompressedBytes,
            recordCounts = stats.recordCounts,
            sha256 = stats.sha256,
        ).also(CloneHistoryRecoveryProtocol::validateManifest)
    }

    suspend fun importGlucosePackage(
        file: File,
        manifest: CloneRecoveryManifest,
        recoverySource: String,
    ) {
        require(manifest.categories == CloneRecoveryCategories.GLUCOSE) {
            "Clone glucose recovery package contains unsupported categories"
        }
        require(manifest.recordCounts.keys == CloneGlucoseRecoveryRecords.recordTypes) {
            "Clone glucose recovery package has invalid record categories"
        }
        val session = newImportSession(manifest.mode, recoverySource)
        CloneRecoveryPackageIO.visitValidated(
            file = file,
            manifest = manifest,
            recordValidator = CloneGlucoseRecoveryRecords.orderedValidator(),
            transaction = { operation -> database.withTransaction { operation() } },
            beforeVisit = session::beforeImport,
            afterVisit = session::finishImport,
            visitor = session::accept,
        )
    }

    suspend fun exportRecords(sink: CloneRecoveryPackageIO.RecordSink) {
        exportDeletedReadings(sink)
        exportReadings(sink)
        exportUncertainty(sink)
        exportDisplay(sink)
    }

    fun newImportSession(
        mode: CloneRecoveryMode,
        recoverySource: String,
    ): ImportSession {
        require(GlucoseReadingSource.cloneTransport(recoverySource) != null) {
            "Clone recovery requires a Clone delivery source"
        }
        return ImportSession(mode, recoverySource)
    }

    private suspend fun exportDeletedReadings(sink: CloneRecoveryPackageIO.RecordSink) {
        var afterDeletedAt = 0L
        var afterTimestamp = 0L
        var afterSensorSerial = ""
        while (true) {
            val page = historyDao.getRecoveryDeletedReadingsPage(
                afterDeletedAt = afterDeletedAt,
                afterTimestamp = afterTimestamp,
                afterSensorSerial = afterSensorSerial,
                limit = PAGE_SIZE,
            )
            if (page.isEmpty()) return
            page.forEach { deleted ->
                sink.write(
                    CloneGlucoseRecoveryRecords.DELETED_READING,
                    CloneGlucoseRecoveryRecords.encode(deleted),
                )
            }
            val last = page.last()
            afterDeletedAt = last.deletedAt
            afterTimestamp = last.timestamp
            afterSensorSerial = last.sensorSerial
        }
    }

    private suspend fun exportReadings(sink: CloneRecoveryPackageIO.RecordSink) {
        var afterId = 0L
        while (true) {
            val page = historyDao.getRecoveryReadingsPage(afterId = afterId, limit = PAGE_SIZE)
            if (page.isEmpty()) return
            page.forEach { reading ->
                sink.write(
                    CloneGlucoseRecoveryRecords.READING,
                    CloneGlucoseRecoveryRecords.encode(reading),
                )
            }
            afterId = page.last().id
        }
    }

    private suspend fun exportUncertainty(sink: CloneRecoveryPackageIO.RecordSink) {
        var afterTimestamp = 0L
        var afterSensorSerial = ""
        while (true) {
            val page = uncertaintyDao.getRecoveryPage(
                afterTimestamp = afterTimestamp,
                afterSensorSerial = afterSensorSerial,
                limit = PAGE_SIZE,
            )
            if (page.isEmpty()) return
            page.forEach { uncertainty ->
                sink.write(
                    CloneGlucoseRecoveryRecords.UNCERTAINTY,
                    CloneGlucoseRecoveryRecords.encode(uncertainty),
                )
            }
            val last = page.last()
            afterTimestamp = last.timestamp
            afterSensorSerial = last.sensorSerial
        }
    }

    private suspend fun exportDisplay(sink: CloneRecoveryPackageIO.RecordSink) {
        var afterTimestamp = 0L
        var afterSensorSerial = ""
        while (true) {
            val page = displayDao.getRecoveryPage(
                afterTimestamp = afterTimestamp,
                afterSensorSerial = afterSensorSerial,
                limit = PAGE_SIZE,
            )
            if (page.isEmpty()) return
            page.forEach { display ->
                sink.write(
                    CloneGlucoseRecoveryRecords.DISPLAY,
                    CloneGlucoseRecoveryRecords.encode(display),
                )
            }
            val last = page.last()
            afterTimestamp = last.timestamp
            afterSensorSerial = last.sensorSerial
        }
    }

    inner class ImportSession internal constructor(
        private val mode: CloneRecoveryMode,
        private val recoverySource: String,
    ) {
        private val deletedReadings = ArrayList<DeletedHistoryReading>(PAGE_SIZE)
        private val readings = ArrayList<HistoryReading>(PAGE_SIZE)
        private val uncertainty = ArrayList<ReadingUncertainty>(PAGE_SIZE)
        private val display = ArrayList<ReadingDisplay>(PAGE_SIZE)
        private var lastRecordOrder = -1

        suspend fun beforeImport() {
            if (mode != CloneRecoveryMode.FULL_HISTORY) return
            displayDao.deleteAll()
            uncertaintyDao.deleteAll()
            historyDao.deleteAllReadings()
            historyDao.deleteAllDeletedReadings()
        }

        suspend fun accept(record: CloneRecoveryRecord) {
            val order = recordOrder(record.type)
            require(order >= lastRecordOrder) { "Clone glucose recovery records are out of order" }
            if (lastRecordOrder >= 0 && order != lastRecordOrder) flush()
            lastRecordOrder = order
            when (record.type) {
                CloneGlucoseRecoveryRecords.DELETED_READING -> {
                    deletedReadings += CloneGlucoseRecoveryRecords.decodeDeletedReading(record.payload)
                    if (deletedReadings.size >= PAGE_SIZE) flushDeletedReadings()
                }
                CloneGlucoseRecoveryRecords.READING -> {
                    readings += CloneGlucoseRecoveryRecords.decodeReading(record.payload)
                    if (readings.size >= PAGE_SIZE) flushReadings()
                }
                CloneGlucoseRecoveryRecords.UNCERTAINTY -> {
                    uncertainty += CloneGlucoseRecoveryRecords.decodeUncertainty(record.payload)
                    if (uncertainty.size >= PAGE_SIZE) flushUncertainty()
                }
                CloneGlucoseRecoveryRecords.DISPLAY -> {
                    display += CloneGlucoseRecoveryRecords.decodeDisplay(record.payload)
                    if (display.size >= PAGE_SIZE) flushDisplay()
                }
                else -> error("unreachable")
            }
        }

        suspend fun finishImport() {
            flush()
        }

        private suspend fun flush() {
            flushDeletedReadings()
            flushReadings()
            flushUncertainty()
            flushDisplay()
        }

        private suspend fun flushDeletedReadings() {
            if (deletedReadings.isEmpty()) return
            val existing = if (mode == CloneRecoveryMode.ONLY_MISSING) {
                existingReadingKeysForDeleted(deletedReadings)
            } else emptySet()
            val candidates = CloneGlucoseRecoveryMergePolicy.deletedReadingsToInsert(
                mode = mode,
                rows = deletedReadings,
                existingReadingKeys = existing,
            )
            if (candidates.isNotEmpty()) {
                historyDao.insertDeletedReadingsForRecovery(candidates)
            }
            deletedReadings.clear()
        }

        private suspend fun flushReadings() {
            if (readings.isEmpty()) return
            val candidates = CloneGlucoseRecoveryMergePolicy.readingsToInsert(
                rows = readings,
                deletedReadingKeys = deletedReadingKeys(readings),
                recoverySource = recoverySource,
            )
            if (candidates.isNotEmpty()) historyDao.insertAllIgnoring(candidates)
            readings.clear()
        }

        private suspend fun flushUncertainty() {
            if (uncertainty.isEmpty()) return
            val candidates = CloneGlucoseRecoveryMergePolicy.uncertaintyToInsert(
                rows = uncertainty,
                existingReadingMinuteKeys = existingReadingMinuteKeys(uncertainty),
            )
            if (candidates.isNotEmpty()) uncertaintyDao.insertAllIgnoring(candidates)
            uncertainty.clear()
        }

        private suspend fun flushDisplay() {
            if (display.isEmpty()) return
            val candidates = CloneGlucoseRecoveryMergePolicy.displayToInsert(
                rows = display,
                existingReadingKeys = existingReadingKeysForDisplay(display),
            )
            if (candidates.isNotEmpty()) displayDao.insertAllIgnoring(candidates)
            display.clear()
        }

        private suspend fun existingReadingKeysForDeleted(
            rows: List<DeletedHistoryReading>,
        ): Set<Pair<String, Long>> {
            val existing = mutableSetOf<Pair<String, Long>>()
            rows.groupBy(DeletedHistoryReading::sensorSerial).forEach { (serial, serialRows) ->
                serialRows.map(DeletedHistoryReading::timestamp).distinct().chunked(SQL_IN_LIMIT)
                    .forEach { timestamps ->
                        historyDao.getSensorReadingsAtTimestamps(serial, timestamps)
                            .forEach { reading -> existing += serial to reading.timestamp }
                    }
            }
            return existing
        }

        private suspend fun deletedReadingKeys(
            rows: List<HistoryReading>,
        ): Set<Pair<String, Long>> {
            val deleted = mutableSetOf<Pair<String, Long>>()
            rows.groupBy(HistoryReading::sensorSerial).forEach { (serial, serialRows) ->
                serialRows.map(HistoryReading::timestamp).distinct().chunked(SQL_IN_LIMIT)
                    .forEach { timestamps ->
                        historyDao.getDeletedTimestampsForSensor(serial, timestamps)
                            .forEach { timestamp -> deleted += serial to timestamp }
                    }
            }
            return deleted
        }

        private suspend fun existingReadingMinuteKeys(
            rows: List<ReadingUncertainty>,
        ): Set<Pair<String, Long>> {
            val existing = mutableSetOf<Pair<String, Long>>()
            rows.groupBy(ReadingUncertainty::sensorSerial).forEach { (serial, serialRows) ->
                serialRows.map(ReadingUncertainty::timestamp).distinct().chunked(SQL_IN_LIMIT)
                    .forEach { timestamps ->
                        historyDao.getExistingMinuteTimestampsForSensor(serial, timestamps)
                            .forEach { timestamp -> existing += serial to timestamp }
                    }
            }
            return existing
        }

        private suspend fun existingReadingKeysForDisplay(
            rows: List<ReadingDisplay>,
        ): Set<Pair<String, Long>> {
            val existing = mutableSetOf<Pair<String, Long>>()
            rows.groupBy(ReadingDisplay::sensorSerial).forEach { (serial, serialRows) ->
                serialRows.map(ReadingDisplay::timestamp).distinct().chunked(SQL_IN_LIMIT)
                    .forEach { timestamps ->
                        historyDao.getSensorReadingsAtTimestamps(serial, timestamps)
                            .forEach { reading -> existing += serial to reading.timestamp }
                    }
            }
            return existing
        }
    }

    private fun recordOrder(type: String): Int = when (type) {
        CloneGlucoseRecoveryRecords.DELETED_READING -> 0
        CloneGlucoseRecoveryRecords.READING -> 1
        CloneGlucoseRecoveryRecords.UNCERTAINTY -> 2
        CloneGlucoseRecoveryRecords.DISPLAY -> 3
        else -> throw IllegalArgumentException("Unsupported Clone glucose recovery record type")
    }

    private companion object {
        const val PAGE_SIZE = 500
        const val SQL_IN_LIMIT = 900
    }
}
