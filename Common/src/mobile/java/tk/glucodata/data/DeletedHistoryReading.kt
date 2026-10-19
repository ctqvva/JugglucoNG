package tk.glucodata.data

import androidx.room.Entity
import androidx.room.Index

/**
 * Tombstone for a manually deleted reading.
 * Keeps history sync from restoring rows the user explicitly removed.
 */
@Entity(
    tableName = "history_deleted_readings",
    primaryKeys = ["timestamp", "sensorSerial"],
    indices = [Index(value = ["sensorSerial"])]
)
data class DeletedHistoryReading(
    val timestamp: Long,
    val sensorSerial: String,
    val deletedAt: Long
)
