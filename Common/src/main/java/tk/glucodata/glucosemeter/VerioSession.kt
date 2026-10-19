package tk.glucodata.glucosemeter

import androidx.annotation.Keep
import tk.glucodata.Log
import tk.glucodata.drivers.aidex.native.crypto.Crc16CcittFalse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * OneTouch Verio Flex (LifeScan) glucose meter protocol, ported from xDrip.
 *
 * The meter is not GDM compliant: it has no glucose service at all, just a
 * vendor service with one write and one notify characteristic. The exchange
 * is strictly one command at a time and every answer has to be acked with a
 * single 0x81 byte before the next command goes out, so commands are queued
 * here and drained one per write callback.
 *
 * A command is 0x01 followed by a framed payload, and the frame is
 * `0x02 | size | 0x00 | 0x03 | payload | 0x03 | crc16`, where size counts the
 * whole frame. The CRC covers the frame without the leading 0x01 and is
 * appended little endian.
 */
@Keep
class VerioSession {

    private enum class Stage { IDLE, TIME, T_COUNTER, R_COUNTER, RECORDS, DONE }

    /** A reading the meter sent us; storing it is the caller's job. */
    data class Reading(val record: Int, val timestampMillis: Long, val mgdlTenths: Int)

    private val outbox = ArrayDeque<ByteArray>()
    private val pendingReadings = ArrayList<Reading>()
    private var stage = Stage.IDLE
    private var begun = false
    private var meterTimeOffset = 0L
    private var haveMeterTime = false
    private var highestRecordNumber = -1
    private var numberOfRecords = -1
    private var requestedRecord = -1
    private var nextRecordToRequest = -1
    private var recordsRequested = 0
    /** Lowest record index the walk may still ask for. */
    private var walkFloor = 0

    fun reset() {
        outbox.clear()
        pendingReadings.clear()
        stage = Stage.IDLE
        begun = false
        meterTimeOffset = 0L
        haveMeterTime = false
        highestRecordNumber = -1
        numberOfRecords = -1
        requestedRecord = -1
        nextRecordToRequest = -1
        recordsRequested = 0
        walkFloor = 0
    }

    fun begin() {
        reset()
        begun = true
        stage = Stage.TIME
        queue(command(0x20, 0x02)) // meter time first, every reading needs it
    }

    /** Next command to write, or null when there is nothing to send. */
    fun take(): ByteArray? = outbox.pollFirst()

    /**
     * Puts a command back after a failed write. Without this the command is
     * lost: writeCharacteristic returning false means no write callback comes,
     * so nothing would ever ask for it again.
     */
    fun putBack(command: ByteArray) {
        outbox.addFirst(command)
    }

    /** Readings received since the last call. */
    fun takeReadings(): List<Reading> {
        if (pendingReadings.isEmpty()) return emptyList()
        val copy = pendingReadings.toList()
        pendingReadings.clear()
        return copy
    }

    private fun queue(value: ByteArray?) {
        if (value != null) outbox.addLast(value)
    }

    /**
     * @param alreadyHave highest record the caller has stored, the walk stops
     *   there. Passed in so this stays free of storage and testable on the jvm.
     */
    @Synchronized
    fun onNotification(message: ByteArray?, alreadyHave: Int) {
        if (!begun) {
            Log.e(TAG, "notification outside a session, ignored")
            return
        }
        if (message == null || message.isEmpty()) {
            Log.e(TAG, "empty notification")
            return
        }
        // a single 0x81 is the meter acknowledging one of our commands. It says
        // nothing about which answer is coming, so the stage only moves on the
        // data packet itself.
        if (message.size == 1) {
            if ((message[0].toInt() and 0x81) == 0x81) {
                Log.i(TAG, "ack")
            } else {
                Log.e(TAG, String.format(Locale.US, "unexpected byte 0x%02X", message[0]))
            }
            return
        }
        if (message.size <= 5) {
            reject(String.format(Locale.US, "unexpected message size %d", message.size))
            return
        }
        // a bad header is logged, not rejected on its own: the crc below decides
        if (message[0] != HEADER || message[1] != FRAME_START) {
            Log.e(TAG, String.format(Locale.US, "invalid header %02X %02X", message[0], message[1]))
        }
        if (message[2].toInt() != message.size - 1) {
            reject(String.format(
                Locale.US, "length field %02X but message is %d bytes", message[2], message.size))
            return
        }
        // Same slice xDrip checks with crc16ccitt(bytes, true, true): the crc
        // covers the frame without the leading 0x01 and without its own two
        // bytes. validateResponse() already drops the trailing two, so this only
        // has to drop the header. All four packets a real OneTouch sent pass it.
        if (!Crc16CcittFalse.validateResponse(message.copyOfRange(1, message.size))) {
            reject(String.format(Locale.US, "crc mismatch on a %d byte packet", message.size))
            return
        }
        val messageType = message[5]
        if (messageType != 0x06.toByte()) {
            reportError(messageType, message)
            // still ack and stop: the request that was rejected has no answer to
            // wait for and no write is outstanding to drive the next step
            reject(null)
            return
        }
        handleData(message, alreadyHave)
        // every data packet has to be acked before anything else goes out
        queue(byteArrayOf(0x81.toByte()))
        queue(advance())
    }

    /**
     * Ends the exchange. The meter is waiting on an ack, so send one even when we
     * are about to walk away - dropping it silently leaves it waiting and nothing
     * outstanding to drive the next step, which is how the session used to hang.
     * The next connection resumes from the stored position, so at worst this one
     * answer is lost.
     */
    private fun reject(reason: String?) {
        if (reason != null) Log.e(TAG, reason)
        queue(byteArrayOf(0x81.toByte()))
        stage = Stage.DONE
    }

    private fun handleData(message: ByteArray, alreadyHave: Int) {
        val result = message.copyOfRange(6, message.size - 3)
        val data = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        // the stage says what we asked for, which is more reliable than guessing
        // from the payload length
        when (stage) {
            Stage.TIME -> {
                if (result.size != 4) {
                    Log.e(TAG, String.format(Locale.US, "meter time expected 4 bytes, got %d", result.size))
                    return
                }
                val meterTime = (data.getInt(0).toLong() + TIME_OFFSET) * 1000L
                meterTimeOffset = System.currentTimeMillis() - meterTime
                haveMeterTime = true
                Log.i(TAG, String.format(Locale.US, "meter time received, offset=%dms", meterTimeOffset))
            }
            Stage.T_COUNTER -> {
                if (result.size != 4) {
                    Log.e(TAG, String.format(Locale.US, "record counter expected 4 bytes, got %d", result.size))
                    return
                }
                highestRecordNumber = data.getInt(0)
                Log.i(TAG, String.format(Locale.US, "record counter=%d", highestRecordNumber))
            }
            Stage.R_COUNTER -> {
                if (result.size != 2) {
                    Log.e(TAG, String.format(Locale.US, "record count expected 2 bytes, got %d", result.size))
                    return
                }
                numberOfRecords = data.getShort(0).toInt() and 0xFFFF
                Log.i(TAG, String.format(Locale.US, "number of records=%d", numberOfRecords))
                startRecordRequests(alreadyHave)
            }
            Stage.RECORDS -> handleReading(data)
            else -> Log.e(TAG, String.format(Locale.US, "data packet of %d bytes outside a request", result.size))
        }
    }

    private fun handleReading(data: ByteBuffer) {
        val record = requestedRecord
        // the walk has to move on whatever the record says, otherwise one bad
        // reading is requested forever
        try {
            readReading(data, record)
        } finally {
            advanceRecord(record)
        }
    }

    private fun readReading(data: ByteBuffer, record: Int) {
        if (record <= 0) {
            Log.e(TAG, "reading we did not ask for")
            return
        }
        if (data.remaining() < 11) {
            Log.e(TAG, String.format(Locale.US, "record %d is only %d bytes", record, data.remaining()))
            return
        }
        // xDrip sums two ints at offsets 6 and 10 here, but the buffer is only
        // 11 bytes so its second read overruns; use the two trailing bytes.
        val marker = (data.get(6).toInt() and 0xFF) + (data.get(10).toInt() and 0xFF)
        if (marker != 0) {
            Log.e(TAG, String.format(Locale.US, "record %d has non-zero marker %d", record, marker))
        }
        if (!haveMeterTime) {
            Log.e(TAG, "no meter time yet, cannot place the reading in time")
            return
        }
        // ByteBuffer.short/int are relative reads, these offsets are from the
        // start of the payload
        val mgdl = data.getShort(4).toInt() and 0xFFFF
        if (mgdl < MIN_PLAUSIBLE_MGDL || mgdl > MAX_PLAUSIBLE_MGDL) {
            Log.e(TAG, String.format(Locale.US, "implausible value %d mg/dl ignored", mgdl))
            return
        }
        val timestamp = ((data.getInt(0).toLong() and 0xFFFFFFFFL) + TIME_OFFSET) * 1000L + meterTimeOffset
        pendingReadings.add(Reading(record, timestamp, mgdl * 10))
        Log.i(TAG, String.format(Locale.US, "reading %d = %d mg/dl at %d", record, mgdl, timestamp))
    }

    private fun advanceRecord(record: Int) {
        // unconditional: if this could be skipped the walk would sit on the same
        // index forever, because nothing else moves it
        nextRecordToRequest = record - 1
        recordsRequested++
    }

    private fun startRecordRequests(alreadyHave: Int) {
        if (highestRecordNumber < 0 || numberOfRecords < 0) {
            Log.e(TAG, "counters missing, cannot request records")
            nextRecordToRequest = 0
            return
        }
        if (numberOfRecords == 0) {
            Log.i(TAG, "no readings on the meter")
            nextRecordToRequest = 0
            return
        }
        // walkFloor is exclusive: we ask for a record while it is above it. The
        // floor is one below the oldest record the meter still keeps, so that
        // oldest one is included, and never below 0 because record indices
        // start at 1. Folding FIRST_RECORD into this same maximum would be
        // wrong - a 1 there would be treated as exclusive and skip record 1,
        // which is exactly the reading on a meter that has not rolled over its
        // history yet, the normal case on first pairing.
        val oldest = maxOf(highestRecordNumber - numberOfRecords, 0)
        walkFloor = maxOf(oldest, alreadyHave)
        nextRecordToRequest = highestRecordNumber
        recordsRequested = 0
        Log.i(TAG, String.format(
            Locale.US, "%d records, highest=%d, walking down to %d, have %d",
            numberOfRecords, highestRecordNumber, walkFloor, alreadyHave))
    }

    /** Moves to the next request and returns the command that asks for it. */
    private fun advance(): ByteArray? {
        stage = when (stage) {
            Stage.TIME -> Stage.T_COUNTER
            Stage.T_COUNTER -> Stage.R_COUNTER
            Stage.R_COUNTER -> Stage.RECORDS
            else -> stage
        }
        return when (stage) {
            Stage.T_COUNTER -> command(0x0a, 0x02, 0x06) // record counter
            Stage.R_COUNTER -> command(0x27, 0x00) // number of records
            Stage.RECORDS -> {
                if (nextRecordToRequest <= walkFloor || recordsRequested >= MAX_BACKFILL_RECORDS) {
                    stage = Stage.DONE
                    Log.i(TAG, "nothing newer on the meter")
                    null
                } else {
                    requestedRecord = nextRecordToRequest
                    command(0xb3, requestedRecord and 0xff, (requestedRecord shr 8) and 0xff)
                }
            }
            else -> null
        }
    }

    private fun reportError(messageType: Byte, message: ByteArray) {
        val code = if (message.size > 6) message[6].toInt() and 0xFF else 0
        when (messageType) {
            0x07.toByte() -> Log.e(TAG, String.format(Locale.US, "command not allowed (%02X), check the pairing", code))
            0x08.toByte() -> Log.e(TAG, String.format(Locale.US, "command not supported (%02X)", code))
            0x09.toByte() -> Log.e(TAG, String.format(Locale.US, "command not understood (%02X)", code))
            else -> Log.e(TAG, String.format(Locale.US, "unknown message type %02X", messageType))
        }
    }

    companion object {
        private const val TAG = "VerioSession"

        @JvmField
        val SERVICE_UUID: UUID = UUID.fromString("af9df7a1-e595-11e3-96b4-0002a5d5c51b")

        private const val DATA_DELIMITER: Byte = 0x03
        private const val HEADER: Byte = 0x01
        private const val FRAME_START: Byte = 0x02
        /** Meter timestamps count seconds since this epoch offset. */
        private const val TIME_OFFSET = 946684799L
        private const val MAX_BACKFILL_RECORDS = 15
        private const val MIN_PLAUSIBLE_MGDL = 20
        private const val MAX_PLAUSIBLE_MGDL = 700

        /** Frames a payload the way the meter expects it. */
        fun command(vararg payload: Int): ByteArray {
            val frameSize = payload.size + 7
            val frame = ByteArray(frameSize)
            frame[0] = FRAME_START
            frame[1] = frameSize.toByte()
            frame[2] = 0x00
            frame[3] = DATA_DELIMITER
            for (i in payload.indices) {
                frame[4 + i] = payload[i].toByte()
            }
            frame[4 + payload.size] = DATA_DELIMITER
            // the crc covers the frame without the two trailing crc bytes
            val crc = Crc16CcittFalse.checksum(frame.copyOfRange(0, frameSize - 2))
            frame[5 + payload.size] = (crc and 0xFF).toByte()
            frame[6 + payload.size] = ((crc shr 8) and 0xFF).toByte()
            val transmission = ByteArray(frame.size + 1)
            transmission[0] = HEADER
            System.arraycopy(frame, 0, transmission, 1, frame.size)
            return transmission
        }
    }
}
