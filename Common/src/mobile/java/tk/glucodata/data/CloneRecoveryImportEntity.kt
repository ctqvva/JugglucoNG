package tk.glucodata.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import tk.glucodata.CloneRecoveryImportLedger
import tk.glucodata.CloneRecoveryManifest

@Entity(tableName = "clone_recovery_imports")
internal data class CloneRecoveryImportEntity(
    @PrimaryKey val jobId: String,
    val sha256: String,
)

/** A crash after Room commits cannot replay a destructive replacement. */
internal suspend fun HistoryDatabase.withRecoveryTransaction(
    manifest: CloneRecoveryManifest,
    operation: suspend () -> Unit,
) = CloneRecoveryImportLedger.importOnce(
    manifest = manifest,
    transaction = { block -> withTransaction { block() } },
    readDigest = {
        openHelper.writableDatabase.query(
            "SELECT sha256 FROM clone_recovery_imports WHERE jobId = ?", arrayOf(manifest.jobId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    },
    writeDigest = {
        openHelper.writableDatabase.execSQL(
            "INSERT INTO clone_recovery_imports(jobId, sha256) VALUES (?, ?)",
            arrayOf(manifest.jobId, manifest.sha256),
        )
    },
    importRecords = operation,
)
