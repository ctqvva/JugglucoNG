package tk.glucodata.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import tk.glucodata.GlucoseReadingSource

/**
 * Room entity for storing glucose readings independently from C++ sensor data.
 * Values are always stored in mg/dL and converted on display based on user preference.
 * This database persists through "wipe sensor data" operations.
 *
 * Multi-sensor: Each reading is tagged with the sensor serial that produced it.
 * The composite unique index on (timestamp, sensorSerial) allows the same timestamp
 * from different sensors to coexist without conflict.
 */
@Entity(
    tableName = "history_readings",
    indices = [Index(value = ["timestamp", "sensorSerial"], unique = true),
               Index(value = ["sensorSerial"]),
               Index(value = ["sensorSerial", "timestamp"])]
)
data class HistoryReading(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,          // Epoch milliseconds
    val sensorSerial: String,     // Sensor short name (e.g. "X-ABCDEF123456", "GS1Sb-XXX")
    val value: Float,             // Calibrated/auto glucose value (mg/dL)
    val rawValue: Float,          // Raw sensor value (mg/dL)
    val rate: Float?,             // Rate of change (nullable - may not always be available)
    val source: String = GlucoseReadingSource.SENSOR,
    /** First time this logical row reached Room; preserved across overlap rewrites. */
    val firstStoredAt: Long = System.currentTimeMillis(),
)
