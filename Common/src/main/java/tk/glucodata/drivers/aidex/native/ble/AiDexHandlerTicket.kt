package tk.glucodata.drivers.aidex.native.ble

import java.util.concurrent.atomic.AtomicInteger

/**
 * One ticket per teardownOnHandler / runOnHandlerAndWait call. The posted runnable acts only
 * if [tryStart] wins; a caller whose wait timed out acts in its place only if [tryCancel] wins.
 * Exactly one of the two can win, so a timed-out caller and the late runnable can never both act.
 * A caller whose [tryCancel] loses knows the block is already running.
 */
internal class AiDexHandlerTicket {
    private val state = AtomicInteger(PENDING)

    fun tryStart(): Boolean = state.compareAndSet(PENDING, STARTED)

    fun tryCancel(): Boolean = state.compareAndSet(PENDING, CANCELLED)

    private companion object {
        const val PENDING = 0
        const val STARTED = 1
        const val CANCELLED = 2
    }
}
