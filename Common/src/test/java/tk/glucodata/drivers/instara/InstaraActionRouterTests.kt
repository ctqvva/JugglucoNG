package tk.glucodata.drivers.instara

import org.junit.Assert.assertEquals
import org.junit.Test

class InstaraActionRouterTests {

    private class Sink : InstaraPublishSink {
        val live = mutableListOf<InstaraReading>()
        val history = mutableListOf<List<InstaraReading>>()
        override fun live(reading: InstaraReading) { live += reading }
        override fun history(readings: List<InstaraReading>) { history += readings }
    }

    @Test
    fun historyReadingsKeepTheirComputedTimestampsAndAreBatched() {
        val sink = Sink()
        val router = InstaraActionRouter(sink)
        router.route(
            listOf(
                InstaraSession.Action.PublishReading(5.5f, 10, 1_000L, isHistory = true),
                InstaraSession.Action.PublishReading(6.0f, 11, 1_300L, isHistory = true),
            ),
        )
        assertEquals(1, sink.history.size)
        assertEquals(listOf(1_000L, 1_300L), sink.history[0].map { it.timestampMs })
        assertEquals(emptyList<InstaraReading>(), sink.live)
    }

    @Test
    fun liveReadingUsesItsOwnTimestampAndSequence() {
        val sink = Sink()
        InstaraActionRouter(sink).route(
            listOf(InstaraSession.Action.PublishReading(7.0f, 42, 9_999L)),
        )
        assertEquals(1, sink.live.size)
        assertEquals(42, sink.live[0].sequenceId)
        assertEquals(9_999L, sink.live[0].timestampMs)
        assertEquals(emptyList<List<InstaraReading>>(), sink.history)
    }

    @Test
    fun historyDoesNotSuppressLiveDeliveryOfTheSameSequence() {
        val sink = Sink()
        val router = InstaraActionRouter(sink)
        router.route(listOf(InstaraSession.Action.PublishReading(5.5f, 10, 1_000L, isHistory = true)))
        router.route(listOf(InstaraSession.Action.PublishReading(5.5f, 10, 2_000L)))
        assertEquals(1, sink.history[0].size)
        assertEquals(1, sink.live.size)
        assertEquals(2_000L, sink.live[0].timestampMs)
    }

    @Test
    fun duplicateHistoryFramesAreStoredOnlyOnce() {
        val sink = Sink()
        val router = InstaraActionRouter(sink)
        val action = InstaraSession.Action.PublishReading(5.5f, 10, 1_000L, isHistory = true)
        router.route(listOf(action))
        router.route(listOf(action))
        assertEquals(1, sink.history[0].size)
    }
}
