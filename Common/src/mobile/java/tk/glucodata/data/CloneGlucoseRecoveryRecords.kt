package tk.glucodata.data

import org.json.JSONObject
import tk.glucodata.CloneRecoveryRecord

internal object CloneGlucoseRecoveryRecords {
    const val DELETED_READING = "glucose_deleted"
    const val READING = "glucose_reading"
    const val UNCERTAINTY = "glucose_uncertainty"
    const val DISPLAY = "glucose_display"

    val recordTypes: Set<String> = linkedSetOf(
        DELETED_READING,
        READING,
        UNCERTAINTY,
        DISPLAY,
    )

    fun encode(reading: HistoryReading): JSONObject = JSONObject()
        .put("timestamp", reading.timestamp)
        .put("sensorSerial", reading.sensorSerial)
        .put("valueMgDl", reading.value.toDouble())
        .put("rawValueMgDl", reading.rawValue.toDouble())
        .put("rate", reading.rate?.toDouble() ?: JSONObject.NULL)
        .put("source", reading.source)
        .put("firstStoredAt", reading.firstStoredAt)

    fun encode(deleted: DeletedHistoryReading): JSONObject = JSONObject()
        .put("timestamp", deleted.timestamp)
        .put("sensorSerial", deleted.sensorSerial)
        .put("deletedAt", deleted.deletedAt)

    fun encode(uncertainty: ReadingUncertainty): JSONObject = JSONObject()
        .put("timestamp", uncertainty.timestamp)
        .put("sensorSerial", uncertainty.sensorSerial)
        .put("lowerMgdl", uncertainty.lowerMgdl.toDouble())
        .put("upperMgdl", uncertainty.upperMgdl.toDouble())
        .put("intervalMass", uncertainty.intervalMass.toDouble())
        .put("confidence", uncertainty.confidence?.toDouble() ?: JSONObject.NULL)
        .put(
            "artifactProbability",
            uncertainty.artifactProbability?.toDouble() ?: JSONObject.NULL,
        )

    fun encode(display: ReadingDisplay): JSONObject = JSONObject()
        .put("timestamp", display.timestamp)
        .put("sensorSerial", display.sensorSerial)
        .put("displayMgdl", display.displayMgdl.toDouble())
        .put("viewMode", display.viewMode)
        .put("calibrationFingerprint", display.calibrationFingerprint)
        .put("recordedAt", display.recordedAt)

    fun decodeReading(payload: JSONObject): HistoryReading {
        payload.requireExactKeys(
            "timestamp",
            "sensorSerial",
            "valueMgDl",
            "rawValueMgDl",
            "rate",
            "source",
            "firstStoredAt",
        )
        val value = payload.requireFiniteFloat("valueMgDl")
        val rawValue = payload.requireFiniteFloat("rawValueMgDl")
        require(value >= 0f && rawValue >= 0f && (value > 0f || rawValue > 0f)) {
            "Invalid Clone recovery glucose value"
        }
        return HistoryReading(
            timestamp = payload.requirePositiveLong("timestamp"),
            sensorSerial = payload.requireBoundedString("sensorSerial", MAX_SENSOR_SERIAL_LENGTH),
            value = value,
            rawValue = rawValue,
            rate = payload.requireNullableFiniteFloat("rate"),
            source = payload.requireBoundedString("source", MAX_SOURCE_LENGTH),
            firstStoredAt = payload.requirePositiveLong("firstStoredAt"),
        )
    }

    fun decodeDeletedReading(payload: JSONObject): DeletedHistoryReading {
        payload.requireExactKeys("timestamp", "sensorSerial", "deletedAt")
        return DeletedHistoryReading(
            timestamp = payload.requirePositiveLong("timestamp"),
            sensorSerial = payload.requireBoundedString("sensorSerial", MAX_SENSOR_SERIAL_LENGTH),
            deletedAt = payload.requirePositiveLong("deletedAt"),
        )
    }

    fun decodeUncertainty(payload: JSONObject): ReadingUncertainty {
        payload.requireExactKeys(
            "timestamp",
            "sensorSerial",
            "lowerMgdl",
            "upperMgdl",
            "intervalMass",
            "confidence",
            "artifactProbability",
        )
        val row = ReadingUncertainty(
            timestamp = payload.requirePositiveLong("timestamp"),
            sensorSerial = payload.requireBoundedString("sensorSerial", MAX_SENSOR_SERIAL_LENGTH),
            lowerMgdl = payload.requireFiniteFloat("lowerMgdl"),
            upperMgdl = payload.requireFiniteFloat("upperMgdl"),
            intervalMass = payload.requireFiniteFloat("intervalMass"),
            confidence = payload.requireNullableFiniteFloat("confidence"),
            artifactProbability = payload.requireNullableFiniteFloat("artifactProbability"),
        )
        require(row.isUsable) { "Invalid Clone recovery glucose uncertainty" }
        require(row.intervalMass > 0f && row.intervalMass <= 1f) {
            "Invalid Clone recovery uncertainty interval mass"
        }
        require(row.confidence == null || row.confidence in 0f..1f) {
            "Invalid Clone recovery uncertainty confidence"
        }
        require(row.artifactProbability == null || row.artifactProbability in 0f..1f) {
            "Invalid Clone recovery artifact probability"
        }
        return row
    }

    fun decodeDisplay(payload: JSONObject): ReadingDisplay {
        payload.requireExactKeys(
            "timestamp",
            "sensorSerial",
            "displayMgdl",
            "viewMode",
            "calibrationFingerprint",
            "recordedAt",
        )
        val row = ReadingDisplay(
            timestamp = payload.requirePositiveLong("timestamp"),
            sensorSerial = payload.requireBoundedString("sensorSerial", MAX_SENSOR_SERIAL_LENGTH),
            displayMgdl = payload.requireFiniteFloat("displayMgdl"),
            viewMode = payload.requireInt("viewMode"),
            calibrationFingerprint = payload.requireLong("calibrationFingerprint"),
            recordedAt = payload.requirePositiveLong("recordedAt"),
        )
        require(row.isUsable) { "Invalid Clone recovery glucose display value" }
        require(row.viewMode in 0..3) { "Invalid Clone recovery glucose display mode" }
        return row
    }

    fun orderedValidator(): suspend (CloneRecoveryRecord) -> Unit {
        var lastOrder = -1
        return { record ->
            val order = when (record.type) {
                DELETED_READING -> {
                    decodeDeletedReading(record.payload)
                    0
                }
                READING -> {
                    decodeReading(record.payload)
                    1
                }
                UNCERTAINTY -> {
                    decodeUncertainty(record.payload)
                    2
                }
                DISPLAY -> {
                    decodeDisplay(record.payload)
                    3
                }
                else -> throw IllegalArgumentException(
                    "Unsupported Clone glucose recovery record type",
                )
            }
            require(order >= lastOrder) { "Clone glucose recovery records are out of order" }
            lastOrder = order
        }
    }

    private fun JSONObject.requireExactKeys(vararg expected: String) {
        val expectedSet = expected.toSet()
        require(length() == expectedSet.size) { "Invalid Clone glucose recovery record fields" }
        val names = keys()
        while (names.hasNext()) {
            require(names.next() in expectedSet) { "Invalid Clone glucose recovery record fields" }
        }
        expectedSet.forEach { name ->
            require(has(name)) { "Missing Clone glucose recovery field" }
        }
    }

    private fun JSONObject.requireBoundedString(name: String, maximumLength: Int): String {
        require(has(name) && !isNull(name)) { "Missing Clone glucose recovery $name" }
        val value = get(name) as? String
            ?: throw IllegalArgumentException("Invalid Clone glucose recovery $name")
        require(value.isNotBlank() && value.length <= maximumLength && value.none(Char::isISOControl)) {
            "Invalid Clone glucose recovery $name"
        }
        return value
    }

    private fun JSONObject.requirePositiveLong(name: String): Long =
        requireLong(name).also { value ->
            require(value > 0L) { "Invalid Clone glucose recovery $name" }
        }

    private fun JSONObject.requireLong(name: String): Long {
        require(has(name) && !isNull(name)) { "Missing Clone glucose recovery $name" }
        val number = get(name) as? Number
            ?: throw IllegalArgumentException("Invalid Clone glucose recovery $name")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) {
            "Invalid Clone glucose recovery $name"
        }
        return value
    }

    private fun JSONObject.requireInt(name: String): Int {
        val value = requireLong(name)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "Invalid Clone glucose recovery $name"
        }
        return value.toInt()
    }

    private fun JSONObject.requireFiniteFloat(name: String): Float {
        require(has(name) && !isNull(name)) { "Missing Clone glucose recovery $name" }
        val number = get(name) as? Number
            ?: throw IllegalArgumentException("Invalid Clone glucose recovery $name")
        return number.toFloat().also { value ->
            require(value.isFinite()) { "Invalid Clone glucose recovery $name" }
        }
    }

    private fun JSONObject.requireNullableFiniteFloat(name: String): Float? {
        require(has(name)) { "Missing Clone glucose recovery $name" }
        if (isNull(name)) return null
        return requireFiniteFloat(name)
    }

    private const val MAX_SENSOR_SERIAL_LENGTH = 256
    private const val MAX_SOURCE_LENGTH = 64
}
