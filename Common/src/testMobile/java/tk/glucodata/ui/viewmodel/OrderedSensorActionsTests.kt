package tk.glucodata.ui.viewmodel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class OrderedSensorActionsTests {
    @Test fun choicesStayInTapOrderBehindABlockedWriteWhileOtherSensorsCanProceed() {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val actions = OrderedSensorActions(scope, dispatcher)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val other = CountDownLatch(1)
            val values = ArrayList<Int>()
            try {
                actions.submit("AiDex") { entered.countDown(); release.await(); values += 8 }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                actions.submit("AiDex") { values += 9 }
                actions.submit("AiDex") { values += 8 }
                actions.submit("AiDex") { values += 0; finished.countDown() }
                actions.submit("Other") { other.countDown() }
                assertTrue(other.await(5, TimeUnit.SECONDS))
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(8, 9, 8, 0), values)
            } finally { release.countDown(); scope.cancel() }
        }
    }

    @Test fun failedChoiceDoesNotDropTheUsersLaterStockChoice() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val actions = OrderedSensorActions(scope, dispatcher)
            val finished = CountDownLatch(1)
            try {
                actions.submit("AiDex") { error("driver rejected first request") }
                actions.submit("AiDex") { finished.countDown() }
                assertTrue(finished.await(5, TimeUnit.SECONDS))
            } finally { scope.cancel() }
        }
    }
}
