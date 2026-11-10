package tk.glucodata.drivers.aidex

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.aidex.native.ble.AiDexGattCallbacks
import tk.glucodata.drivers.aidex.native.ble.AiDexGattCallbacks.Kind

class AiDexGattCallbacksTests {
    // Two transports to the same device may compare equal; only object identity is safe.
    private data class Link(val address: String = "sensor")
    private val characteristic = UUID.fromString("0000f002-0000-1000-8000-00805f9b34fb")

    private class Fixture {
        val lock = Any()
        var current: Link? = Link()
        val tasks = ArrayDeque<() -> Unit>()
        var beforePost: () -> Unit = {}
        var completions = 0
        var watchdogArmed = true
        val callbacks = AiDexGattCallbacks(
            lock = lock,
            currentGatt = { current },
            post = { task -> beforePost(); tasks.add(task) },
            operationCompleted = { completions++; watchdogArmed = false },
        )

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst()()
        }
    }

    @Test
    fun aRetiredDisconnectCannotCloseTheReplacementOrResetItsState() {
        val f = Fixture()
        val retired = f.current!!
        val replacement = Link()
        f.current = replacement
        var resets = 0

        val accepted = f.callbacks.runIfCurrent(retired) { resets++; f.current = null }

        assertFalse(accepted)
        assertTrue(f.current === replacement)
        assertEquals(0, resets)
    }

    @Test
    fun aCurrentDisconnectRunsUnderTheTransportReplacementMonitor() {
        val f = Fixture()
        val accepted = f.callbacks.runIfCurrent(f.current!!) {
            assertTrue(Thread.holdsLock(f.lock))
            f.current = null
        }

        assertTrue(accepted)
        assertEquals(null, f.current)
    }

    @Test
    fun aRetiredReadOrWriteDoesNotReleaseTheReplacementOperation() {
        for (kind in Kind.entries) {
            val f = Fixture()
            val retired = f.current!!
            f.current = Link()
            f.callbacks.beginOperation(f.current!!, characteristic, kind)

            f.callbacks.completeOperation(retired, characteristic, kind)
            f.drain()

            assertEquals(0, f.completions)
            assertTrue(f.watchdogArmed)
        }
    }

    @Test
    fun replacementBetweenCallbackAndHandlerExecutionPreservesTheNewOperation() {
        for (kind in Kind.entries) {
            val f = Fixture()
            val retired = f.current!!
            f.callbacks.beginOperation(retired, characteristic, kind)
            f.callbacks.completeOperation(retired, characteristic, kind)
            f.current = Link()
            f.callbacks.beginOperation(f.current!!, characteristic, kind)

            f.drain()

            assertEquals(0, f.completions)
            assertTrue(f.watchdogArmed)
        }
    }

    @Test
    fun aResetBeforeTheOldCallbackIsPostedCannotResurrectItsCompletion() {
        val f = Fixture()
        f.callbacks.beginOperation(f.current!!, characteristic, Kind.READ)
        f.beforePost = {
            // removeCallbacksAndMessages cannot remove a callback not posted yet.
            f.tasks.clear()
            f.callbacks.clearOperation()
            f.callbacks.beginOperation(f.current!!, characteristic, Kind.READ)
        }

        f.callbacks.completeOperation(f.current!!, characteristic, Kind.READ)
        f.drain()

        assertEquals(0, f.completions)
        assertTrue(f.watchdogArmed)
    }

    @Test
    fun aDuplicateCompletionCannotReleaseTheNextDispatchOnTheSameLink() {
        val f = Fixture()
        val gatt = f.current!!
        f.callbacks.beginOperation(gatt, characteristic, Kind.WRITE)
        f.callbacks.completeOperation(gatt, characteristic, Kind.WRITE)
        f.callbacks.completeOperation(gatt, characteristic, Kind.WRITE)

        f.tasks.removeFirst()()
        f.callbacks.beginOperation(gatt, characteristic, Kind.WRITE)
        f.watchdogArmed = true
        f.drain()

        assertEquals(1, f.completions)
        assertTrue(f.watchdogArmed)
    }

    @Test
    fun anUnrelatedCallbackCannotCompleteARead() {
        val f = Fixture()
        val gatt = f.current!!
        f.callbacks.beginOperation(gatt, characteristic, Kind.READ)

        f.callbacks.completeOperation(gatt, characteristic, Kind.WRITE)
        f.callbacks.completeOperation(gatt, UUID.randomUUID(), Kind.READ)
        f.drain()

        assertEquals(0, f.completions)
        assertTrue(f.watchdogArmed)
        f.callbacks.completeOperation(gatt, characteristic, Kind.READ)
        f.drain()
        assertEquals(1, f.completions)
        assertFalse(f.watchdogArmed)
    }

    @Test
    fun aTimedOutLinkCannotRetryOrAdvanceForALateReadOrWriteCallback() {
        for (kind in Kind.entries) {
            val f = Fixture()
            val gatt = f.current!!
            assertTrue(f.callbacks.beginOperation(gatt, characteristic, kind))

            assertTrue(f.callbacks.timeoutOperation())
            f.callbacks.clearOperation() // Connection cleanup must not make this link reusable.
            assertFalse(f.callbacks.canStartOperation(gatt))
            assertFalse(f.callbacks.beginOperation(gatt, characteristic, kind)) // Same-command retry.
            assertFalse(f.callbacks.beginOperation(gatt, UUID.randomUUID(), kind)) // Next command.
            f.callbacks.completeOperation(gatt, characteristic, kind) // Original, late callback.
            f.drain()

            assertEquals(0, f.completions)
            assertTrue(f.watchdogArmed)
        }
    }

    @Test
    fun timeoutInvalidatesAPostedCompletionAndOnlyAFreshTransportCanResume() {
        for (kind in Kind.entries) {
            val f = Fixture()
            val retired = f.current!!
            f.callbacks.beginOperation(retired, characteristic, kind)
            f.callbacks.completeOperation(retired, characteristic, kind)
            assertTrue(f.callbacks.timeoutOperation())

            f.current = Link()
            assertTrue(f.callbacks.beginOperation(f.current!!, characteristic, kind))
            f.callbacks.completeOperation(retired, characteristic, kind)
            f.drain()
            assertEquals(0, f.completions)
            assertTrue(f.watchdogArmed)

            f.callbacks.completeOperation(f.current!!, characteristic, kind)
            f.drain()
            assertEquals(1, f.completions)
            assertFalse(f.watchdogArmed)
        }
    }
}
