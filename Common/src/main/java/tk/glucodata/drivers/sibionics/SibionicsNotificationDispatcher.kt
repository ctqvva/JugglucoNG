package tk.glucodata.drivers.sibionics

/**
 * Keeps expensive packet replay away from Android's Bluetooth callback thread while preserving
 * notification order. A session token prevents notifications queued by a disconnected GATT from
 * leaking into the next connection.
 */
internal class SibionicsNotificationDispatcher(
    private val post: (Runnable) -> Unit,
    private val consume: (ByteArray) -> Unit,
) {
    @Volatile
    private var session: Long = 0L

    @Synchronized
    fun beginSession() {
        session++
    }

    @Synchronized
    fun invalidateSession() {
        session++
    }

    fun dispatch(value: ByteArray) {
        val snapshot = value.copyOf()
        val submittedSession = session
        post(
            Runnable {
                if (submittedSession == session) consume(snapshot)
            },
        )
    }
}
