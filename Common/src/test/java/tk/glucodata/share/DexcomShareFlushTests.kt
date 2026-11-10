package tk.glucodata.share

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.share.DexcomShareClient.HttpResult

/**
 * The flush loop with a fake transport and a fake clock, so the retry rules — which are the part
 * that can lock a user's Dexcom account — are pinned without Android or a network.
 */
class DexcomShareFlushTests {

    private val minute = 60_000L
    private var now = 1_760_000_000_000L

    private val config = DexcomShareSettings.Config(
        enabled = true,
        account = "someone@example.com",
        password = "secret",
        region = DexcomShareRegion.OUS,
        serial = "SM00000000"
    )

    private class RecordingSink : DexcomShareStatusSink {
        val successes = mutableListOf<Pair<Int, Long>>()
        val failures = mutableListOf<Pair<Int, String>>()
        var authBlockedCalls = 0
        var persisted = 0

        override fun recordSuccess(count: Int, newestMs: Long) {
            successes += count to newestMs
        }

        override fun recordFailure(code: Int, message: String) {
            failures += code to message
        }

        override fun recordAuthBlocked() {
            authBlockedCalls++
        }

        override fun persistQueue() {
            persisted++
        }
    }

    private class ScriptedTransport : DexcomShareClient.Transport {
        val calls = mutableListOf<Pair<String, String?>>()
        val uploadSizes = mutableListOf<Int>()
        var loginCode = 200
        var loginBody = "\"session-1\""
        var uploadReply: Pair<Int, String> = 200 to ""
        val uploadReplies = ArrayDeque<Pair<Int, String>>()

        override fun post(url: String, jsonBody: String?): HttpResult {
            calls += url to jsonBody
            return when {
                url.contains("AuthenticatePublisherAccount") ->
                    HttpResult(200, "\"1d2c3b4a-0000-4000-8000-00000000abcd\"")
                url.contains("LoginPublisherAccountById") -> HttpResult(loginCode, loginBody)
                url.contains("CheckMonitoredReceiverAssignmentStatus") ->
                    HttpResult(200, "\"AssignedToYou\"")
                url.contains("PostReceiverEgvRecords") -> {
                    uploadSizes += JSONObject(jsonBody!!).getJSONArray("Egvs").length()
                    val (code, body) = uploadReplies.removeFirstOrNull() ?: uploadReply
                    HttpResult(code, body)
                }
                else -> HttpResult(200, "")
            }
        }

        val loginCalls: Int get() = calls.count { it.first.contains("LoginPublisherAccountById") }

        val uploadCalls: Int get() = uploadSizes.size
    }

    private fun queueWith(times: List<Long>): DexcomShareQueue {
        val queue = DexcomShareQueue()
        times.forEach { queue.offerLive("s1", ShareEgv(it, 120, 4), it) }
        return queue
    }

    private fun flush(
        queue: DexcomShareQueue,
        transport: ScriptedTransport,
        sink: RecordingSink
    ) = DexcomShareFlush(queue, sink, { transport }, { now })

    @Test
    fun anExpiredSessionIsReloggedOnceAndTheChunkSucceeds() {
        val transport = ScriptedTransport().apply {
            uploadReplies += 500 to """{"Code":"SessionIdNotFound","Message":"gone"}"""
            uploadReply = 200 to ""
        }
        val queue = queueWith(listOf(now))
        val sink = RecordingSink()

        assertEquals(null, flush(queue, transport, sink).flush(config))

        assertEquals(2, transport.loginCalls)
        assertEquals(listOf(1, 1), transport.uploadSizes)
        assertEquals(emptyList<ShareEgv>(), queue.pending(now))
        assertEquals(listOf(1 to now), sink.successes)
    }

    @Test
    fun badCredentialsBlockTheAccountAndNoFurtherCallIsMade() {
        val transport = ScriptedTransport().apply {
            loginCode = 500
            loginBody = """{"Code":"AccountPasswordInvalid","Message":"Password is invalid"}"""
        }
        val queue = queueWith(listOf(now))
        val sink = RecordingSink()
        val engine = flush(queue, transport, sink)

        assertEquals(null, engine.flush(config))
        assertTrue(engine.authBlocked)
        assertEquals(1, sink.authBlockedCalls)
        assertEquals(0, transport.uploadCalls)

        val callsAfterFirst = transport.calls.size
        assertEquals(null, engine.flush(config))
        assertEquals(callsAfterFirst, transport.calls.size)
        assertEquals(listOf(now), queue.pending(now).map { it.timeMs })
    }

    @Test
    fun aLockoutIsHandledLikeBadCredentials() {
        val transport = ScriptedTransport().apply {
            loginCode = 500
            loginBody = """{"Code":"SSO_AuthenticateMaxAttemptsExceeed","Message":"locked"}"""
        }
        val engine = flush(queueWith(listOf(now)), transport, RecordingSink())

        engine.flush(config)

        assertTrue(engine.authBlocked)
        assertEquals(0, transport.uploadCalls)
    }

    @Test
    fun aTransientFailureKeepsTheQueueAndTheBackoffDoublesUpToThirtyMinutes() {
        val transport = ScriptedTransport().apply { uploadReply = 503 to "" }
        val queue = queueWith(listOf(now))
        val engine = flush(queue, transport, RecordingSink())
        val start = now

        val delays = (0 until 7).map {
            val delay = engine.flush(config)
            now += delay ?: 0L
            delay
        }

        assertEquals(
            listOf(60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_800_000L, 1_800_000L),
            delays,
        )
        assertEquals(listOf(start), queue.pending(now).map { it.timeMs })
        // The session survives a transient failure, so the login is not repeated.
        assertEquals(1, transport.loginCalls)
    }

    @Test
    fun aBackoffDelaysTheNextAttempt() {
        val transport = ScriptedTransport().apply { uploadReply = 503 to "" }
        val queue = queueWith(listOf(now))
        val engine = flush(queue, transport, RecordingSink())

        assertEquals(60_000L, engine.flush(config))
        assertEquals(60_000L, engine.flush(config))
        assertEquals(1, transport.uploadCalls)

        // Once the backoff has elapsed the next attempt goes out; failing again doubles it.
        now += 60_000L
        assertEquals(120_000L, engine.flush(config))
        assertEquals(2, transport.uploadCalls)
    }

    @Test
    fun aRejectedChunkIsDroppedAndTheRestStillGoesOut() {
        val times = (0 until 130).map { now - (129 - it) * 5 * minute }
        val queue = queueWith(times)
        val transport = ScriptedTransport().apply {
            uploadReplies += 400 to """{"Code":"BadRequest","Message":"nope"}"""
            uploadReply = 200 to ""
        }
        val sink = RecordingSink()

        assertEquals(null, flush(queue, transport, sink).flush(config))

        assertEquals(listOf(60, 60, 10), transport.uploadSizes)
        assertEquals(emptyList<ShareEgv>(), queue.pending(now))
        assertEquals(listOf(400 to "nope"), sink.failures)
        assertEquals(2, sink.successes.size)
    }

    @Test
    fun oneHundredAndThirtyReadingsGoOutInThreeRequests() {
        val times = (0 until 130).map { now - (129 - it) * 5 * minute }
        val queue = queueWith(times)
        val transport = ScriptedTransport()
        val sink = RecordingSink()

        assertEquals(null, flush(queue, transport, sink).flush(config))

        assertEquals(listOf(60, 60, 10), transport.uploadSizes)
        assertEquals(3, sink.successes.size)
        assertEquals(emptyList<ShareEgv>(), queue.pending(now))
        assertEquals(1, transport.loginCalls)
    }

    @Test
    fun anUnrecognisedServerErrorReloginsOnlyOncePerFlush() {
        val transport = ScriptedTransport().apply { uploadReply = 500 to "" }
        val queue = queueWith(listOf(now))
        val sink = RecordingSink()
        val engine = flush(queue, transport, sink)

        assertEquals(60_000L, engine.flush(config))

        assertEquals(2, transport.loginCalls)
        assertEquals(listOf(1, 1), transport.uploadSizes)
        assertEquals(listOf(now), queue.pending(now).map { it.timeMs })
        assertEquals(listOf(500 to ""), sink.failures)
        assertFalse(engine.authBlocked)
    }

    @Test
    fun aTransientLoginFailureBacksOffWithoutBlockingTheAccount() {
        val transport = ScriptedTransport().apply {
            loginCode = 503
            loginBody = "Service unavailable"
        }
        val queue = queueWith(listOf(now))
        val engine = flush(queue, transport, RecordingSink())

        assertEquals(60_000L, engine.flush(config))

        assertFalse(engine.authBlocked)
        assertEquals(0, transport.uploadCalls)
        assertEquals(listOf(now), queue.pending(now).map { it.timeMs })
    }

    @Test
    fun loginErrorsDexcomKeepsAnsweringBlockTheAccountAfterTheLimit() {
        // A 500 with no code Dexcom documents: it could be a password problem under a new name.
        val transport = ScriptedTransport().apply {
            loginCode = 500
            loginBody = ""
        }
        val sink = RecordingSink()
        val engine = flush(queueWith(listOf(now)), transport, sink)

        repeat(DexcomShareFlush.MAX_FAILED_LOGINS) {
            engine.flush(config)?.let { now += it }
        }

        assertTrue(engine.authBlocked)
        assertEquals(1, sink.authBlockedCalls)
        assertEquals(DexcomShareFlush.MAX_FAILED_LOGINS, transport.loginCalls)

        now += DexcomShareFlush.MAX_BACKOFF_MS
        assertEquals(null, engine.flush(config))
        assertEquals(DexcomShareFlush.MAX_FAILED_LOGINS, transport.loginCalls)
    }

    @Test
    fun transportFailuresDoNotCountTowardTheLoginLimit() {
        val transport = ScriptedTransport().apply {
            loginCode = DexcomShareClient.TRANSPORT_FAILURE_CODE
            loginBody = "timeout"
        }
        val engine = flush(queueWith(listOf(now)), transport, RecordingSink())

        repeat(DexcomShareFlush.MAX_FAILED_LOGINS * 2) {
            engine.flush(config)?.let { now += it }
        }

        assertFalse(engine.authBlocked)
        assertEquals(DexcomShareFlush.MAX_FAILED_LOGINS * 2, transport.loginCalls)
    }
}
