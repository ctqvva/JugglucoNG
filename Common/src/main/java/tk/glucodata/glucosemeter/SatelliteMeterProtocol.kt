package tk.glucodata.glucosemeter

import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToInt

/** ELTA Satellite (Сателлит+) Nordic UART glucose-meter protocol. */
object SatelliteMeterProtocol {
    @JvmField
    val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    @JvmField
    val RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    @JvmField
    val TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    const val MAX_BACKFILL_RECORDS = 10
    private const val EMPTY_SLOT = "rd000000000000000000"
    private const val CAPILLARY_TO_PLASMA = 1.12
    private const val MGDL_PER_MMOLL = 18.0182
    private val rawPinPattern = Regex("^\\d{3}$")
    private val printedCodePattern = Regex("^[DE]\\d{7,}$", RegexOption.IGNORE_CASE)
    private val recordPattern = Regex("^rd(\\d{12})(\\d{3})(\\d{3})$", RegexOption.IGNORE_CASE)

    data class Reading(val timestampMillis: Long, val mgdlTenths: Int)

    @JvmStatic
    fun decode(value: ByteArray?): String =
        value?.toString(StandardCharsets.US_ASCII)?.trim().orEmpty()

    @JvmStatic
    fun resolvePin(input: String?): String? {
        val value = input?.trim().orEmpty()
        if (rawPinPattern.matches(value)) return value
        if (!printedCodePattern.matches(value)) return null
        val digits = value.substring(1).takeLast(6).toLong()
        val scrambled = (digits * 599_681_139L + 123L) and 0xFFFF_FFFFL
        return String.format(Locale.US, "%03d", scrambled % 1_000L)
    }

    @JvmStatic
    fun pinCommand(pin: String): ByteArray = ascii("pin.$pin")

    @JvmStatic
    fun setTimeCommand(epochMillis: Long): ByteArray {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            timeInMillis = epochMillis
        }
        return ascii(
            String.format(
                Locale.US,
                "settime.%02d%02d%02d%02d%02d%02d",
                calendar.get(Calendar.YEAR) % 100,
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.SECOND),
            )
        )
    }

    @JvmStatic
    fun recordCommand(index: Int): ByteArray =
        ascii(String.format(Locale.US, "rd.%03d", index.coerceIn(0, 999)))

    @JvmStatic
    fun isPinAccepted(response: String): Boolean = response.equals("pin.ok", ignoreCase = true)

    @JvmStatic
    fun isEmptySlot(response: String): Boolean = response.equals(EMPTY_SLOT, ignoreCase = true)

    @JvmStatic
    fun parseRecord(response: String): Reading? {
        val match = recordPattern.matchEntire(response) ?: return null
        val timestamp = parseUtcTimestamp(match.groupValues[1]) ?: return null
        val glucoseRaw = match.groupValues[3].toIntOrNull() ?: return null
        val mgdlTenths = (glucoseRaw * CAPILLARY_TO_PLASMA * MGDL_PER_MMOLL).roundToInt()
        if (mgdlTenths !in 200..7_000) return null
        return Reading(timestampMillis = timestamp, mgdlTenths = mgdlTenths)
    }

    private fun parseUtcTimestamp(token: String): Long? = runCatching {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            isLenient = false
            clear()
            set(
                2000 + token.substring(0, 2).toInt(),
                token.substring(2, 4).toInt() - 1,
                token.substring(4, 6).toInt(),
                token.substring(6, 8).toInt(),
                token.substring(8, 10).toInt(),
                token.substring(10, 12).toInt(),
            )
        }
        calendar.timeInMillis
    }.getOrNull()

    private fun ascii(value: String): ByteArray = value.toByteArray(StandardCharsets.US_ASCII)
}

data class SatelliteSessionUpdate(
    val command: ByteArray? = null,
    val readings: List<SatelliteMeterProtocol.Reading> = emptyList(),
    val complete: Boolean = false,
    val error: String? = null,
)

/** One-command-at-a-time Satellite authentication, clock sync and bounded backfill. */
class SatelliteMeterSession(
    private val pin: String,
    private val nowMillis: Long,
) {
    private enum class Stage { READY, AWAITING_PIN, AWAITING_TIME_ACK, AWAITING_RECORD, COMPLETE }

    private var stage = Stage.READY
    private var recordIndex = 0
    private val readings = mutableListOf<SatelliteMeterProtocol.Reading>()

    @Synchronized
    fun notificationsEnabled(): SatelliteSessionUpdate {
        if (stage != Stage.READY) return fail("Satellite session already started")
        stage = Stage.AWAITING_PIN
        return SatelliteSessionUpdate(command = SatelliteMeterProtocol.pinCommand(pin))
    }

    @Synchronized
    fun onNotification(value: ByteArray?): SatelliteSessionUpdate {
        val response = SatelliteMeterProtocol.decode(value)
        return when (stage) {
            Stage.AWAITING_PIN -> {
                if (!SatelliteMeterProtocol.isPinAccepted(response)) return fail("Satellite PIN rejected")
                stage = Stage.AWAITING_TIME_ACK
                SatelliteSessionUpdate(command = SatelliteMeterProtocol.setTimeCommand(nowMillis))
            }
            Stage.AWAITING_TIME_ACK -> {
                stage = Stage.AWAITING_RECORD
                recordIndex = 0
                SatelliteSessionUpdate(command = SatelliteMeterProtocol.recordCommand(recordIndex))
            }
            Stage.AWAITING_RECORD -> handleRecord(response)
            Stage.READY -> fail("Satellite notification before authentication")
            Stage.COMPLETE -> SatelliteSessionUpdate(complete = true)
        }
    }

    private fun handleRecord(response: String): SatelliteSessionUpdate {
        if (SatelliteMeterProtocol.isEmptySlot(response)) return finish()
        val reading = SatelliteMeterProtocol.parseRecord(response)
            ?: return fail("Malformed Satellite record")
        if (reading.timestampMillis > nowMillis + 24L * 60L * 60L * 1_000L) {
            return fail("Satellite record timestamp is in the future")
        }
        readings += reading
        recordIndex++
        return if (recordIndex >= SatelliteMeterProtocol.MAX_BACKFILL_RECORDS) {
            finish()
        } else {
            SatelliteSessionUpdate(command = SatelliteMeterProtocol.recordCommand(recordIndex))
        }
    }

    private fun finish(): SatelliteSessionUpdate {
        stage = Stage.COMPLETE
        return SatelliteSessionUpdate(readings = readings.sortedBy { it.timestampMillis }, complete = true)
    }

    private fun fail(message: String): SatelliteSessionUpdate {
        stage = Stage.COMPLETE
        return SatelliteSessionUpdate(
            readings = readings.sortedBy { it.timestampMillis },
            complete = true,
            error = message,
        )
    }
}
