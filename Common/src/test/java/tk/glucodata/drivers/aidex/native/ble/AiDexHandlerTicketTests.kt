package tk.glucodata.drivers.aidex.native.ble

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDexHandlerTicketTests {

    @Test
    fun startThenCancel_theStartWins() {
        val ticket = AiDexHandlerTicket()
        assertTrue(ticket.tryStart())
        assertFalse("a timed-out caller must not act once the block has started", ticket.tryCancel())
    }

    @Test
    fun cancelThenStart_theCancelWins() {
        val ticket = AiDexHandlerTicket()
        assertTrue(ticket.tryCancel())
        assertFalse("the late runnable must not act once the caller has taken over", ticket.tryStart())
    }

    @Test
    fun eachSideWinsAtMostOnce() {
        val started = AiDexHandlerTicket()
        assertTrue(started.tryStart())
        assertFalse(started.tryStart())

        val cancelled = AiDexHandlerTicket()
        assertTrue(cancelled.tryCancel())
        assertFalse(cancelled.tryCancel())
    }

    @Test
    fun startRacingCancel_exactlyOneWinsOnEveryTicket() {
        val (starts, cancels) = race({ it.tryStart() }, { it.tryCancel() })
        for (i in 0 until ROUNDS) {
            assertTrue("ticket $i: start=${starts[i]} cancel=${cancels[i]}", starts[i] != cancels[i])
        }
    }

    @Test
    fun startRacingStart_exactlyOneWinsOnEveryTicket() {
        val (first, second) = race({ it.tryStart() }, { it.tryStart() })
        for (i in 0 until ROUNDS) {
            assertTrue("ticket $i: first=${first[i]} second=${second[i]}", first[i] != second[i])
        }
    }

    /** Two threads hit the same ticket together, released by a barrier, once per ticket. */
    private fun race(
        a: (AiDexHandlerTicket) -> Boolean,
        b: (AiDexHandlerTicket) -> Boolean,
    ): Pair<BooleanArray, BooleanArray> {
        val tickets = Array(ROUNDS) { AiDexHandlerTicket() }
        val barrier = CyclicBarrier(2)
        val failure = AtomicReference<Throwable?>(null)
        val winsA = BooleanArray(ROUNDS)
        val winsB = BooleanArray(ROUNDS)
        fun contender(action: (AiDexHandlerTicket) -> Boolean, wins: BooleanArray) = Thread {
            try {
                for (i in 0 until ROUNDS) {
                    // A timeout breaks the barrier, which releases the other thread too.
                    barrier.await(10, TimeUnit.SECONDS)
                    wins[i] = action(tickets[i])
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        val threads = listOf(contender(a, winsA), contender(b, winsB))
        threads.forEach { it.start() }
        // join() publishes the array writes to this thread.
        threads.forEach { it.join(60_000L) }
        threads.forEach { assertFalse("contender still running", it.isAlive) }
        assertNull("race failed: ${failure.get()}", failure.get())
        return winsA to winsB
    }

    private companion object {
        const val ROUNDS = 10_000
    }
}
