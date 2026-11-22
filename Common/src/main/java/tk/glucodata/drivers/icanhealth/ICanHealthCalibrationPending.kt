package tk.glucodata.drivers.icanhealth

/**
 * Tracks a single outstanding finger-calibration write.
 *
 * The sensor's calibration acknowledgement carries only a status byte, so it cannot be matched to a
 * request by sequence. Instead each submission gets a monotonic id; a late ack, a write failure, a
 * timeout and a teardown are all bound to that id. The pending result settles only after both the
 * sensor ack and the GATT write callback have arrived.
 */
internal class ICanHealthCalibrationPending {
    data class Settlement(
        val code: Int,
        val writeSucceeded: Boolean,
        val result: ICanHealthCeCalibrationResult,
    )

    private var nextId = 0L
    private var currentId = -1L
    private var currentResult: ICanHealthCeCalibrationResult? = null
    private var ackCode: Int? = null
    private var writeSucceeded: Boolean? = null

    fun hasPending(): Boolean = currentResult != null

    fun begin(result: ICanHealthCeCalibrationResult): Long? {
        if (hasPending()) return null
        currentId = ++nextId
        currentResult = result
        ackCode = null
        writeSucceeded = null
        return currentId
    }

    fun isCurrent(id: Long): Boolean = hasPending() && currentId == id

    fun onWriteResult(id: Long, status: Int): Settlement? {
        if (!isCurrent(id)) return null
        writeSucceeded = status == 0
        return settle()
    }

    fun onAck(id: Long, code: Int): Settlement? {
        if (!isCurrent(id)) return null
        ackCode = code
        return settle()
    }

    fun cancel(id: Long) {
        if (isCurrent(id)) clear()
    }

    fun clear() {
        currentResult = null
        currentId = -1L
        ackCode = null
        writeSucceeded = null
    }

    private fun settle(): Settlement? {
        val result = currentResult ?: return null
        val code = ackCode ?: return null
        val succeeded = writeSucceeded ?: return null
        clear()
        return Settlement(code, succeeded, result)
    }
}
