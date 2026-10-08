package tk.glucodata.drivers.aidex.native.ble

import java.util.UUID

/** Shares the transport replacement monitor; never waits for the handler while holding it. */
internal class AiDexGattCallbacks<G : Any>(
    private val lock: Any,
    private val currentGatt: () -> G?,
    private val post: (() -> Unit) -> Unit,
    private val operationCompleted: () -> Unit,
) {
    enum class Kind { READ, WRITE }

    private class Operation<G>(val gatt: G, val characteristic: UUID, val kind: Kind)
    private var active: Operation<G>? = null
    private var timedOutGatt: G? = null

    fun runIfCurrent(gatt: G, action: () -> Unit): Boolean = synchronized(lock) {
        if (currentGatt() !== gatt) return@synchronized false
        action()
        true
    }

    fun canStartOperation(gatt: G): Boolean = synchronized(lock) {
        currentGatt() === gatt && timedOutGatt !== gatt
    }

    fun beginOperation(gatt: G, characteristic: UUID, kind: Kind): Boolean = synchronized(lock) {
        if (!canStartOperation(gatt)) return@synchronized false
        active = Operation(gatt, characteristic, kind)
        true
    }

    /** Android callbacks have no request ID. After timeout, this link cannot safely be reused. */
    fun timeoutOperation(): Boolean = synchronized(lock) {
        val expected = active ?: return@synchronized false
        if (currentGatt() !== expected.gatt) return@synchronized false
        timedOutGatt = expected.gatt
        active = null
        true
    }

    fun clearOperation() = synchronized(lock) {
        active = null
    }

    fun completeOperation(gatt: G, characteristic: UUID, kind: Kind) {
        val expected = synchronized(lock) {
            active?.takeIf {
                canStartOperation(gatt) && it.gatt === gatt &&
                    it.characteristic == characteristic && it.kind == kind
            }
        } ?: return

        // A reset can remove pending messages before this post happens. Check both the link
        // and this dispatch's identity again when the handler actually consumes the callback.
        post {
            runIfCurrent(gatt) {
                if (active === expected) {
                    active = null
                    operationCompleted()
                }
            }
        }
    }
}
