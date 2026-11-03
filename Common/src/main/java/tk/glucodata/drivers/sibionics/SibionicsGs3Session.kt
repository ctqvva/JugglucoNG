package tk.glucodata.drivers.sibionics

import tk.glucodata.drivers.sibionics.SibionicsGs3Protocol.FrameCipher
import tk.glucodata.drivers.sibionics.SibionicsGs3Protocol.Message

/**
 * One connection's GS3 handshake and streaming state, with no Android dependency.
 *
 * ```
 * V3: IDLE -> WAITING_AUTH -> WAITING_BIND_IN -> WAITING_SYSINFO15 -> STREAMING
 * V2: IDLE -> WAITING_AUTH -> WAITING_ID_BIND -> WAITING_SYSINFO1 -> WAITING_SYSINFO7
 *          -> WAITING_ACTIVATE -> WAITING_TIME -> STREAMING
 * ```
 * FAILED is terminal for the connection; a new connection starts a new session.
 *
 * Auth falls back in the vendor's order: V3 under REGISTERED_V3, the same V3 frame in
 * PLAIN, then V2 under RC4. Each step is taken when the sensor's reply is not the expected
 * ACK, when the reply cannot be decoded, or when no reply arrives ([onStepTimeout]).
 */
internal class SibionicsGs3Session(
    private val mac: ByteArray,
    initialCursor: Int,
    private val nowMs: () -> Long,
) {
    enum class State {
        IDLE,
        WAITING_AUTH,
        WAITING_BIND_IN,
        WAITING_SYSINFO15,
        WAITING_ID_BIND,
        WAITING_SYSINFO1,
        WAITING_SYSINFO7,
        WAITING_ACTIVATE,
        WAITING_TIME,
        STREAMING,
        FAILED,
    }

    enum class AuthAttempt { V3_REGISTERED, V3_PLAIN, V2_RC4 }

    sealed class Action {
        /**
         * @param sensitive the frame carries the session key (auth); neither [plain] nor
         *   [wire] may be logged — in PLAIN the wire bytes are the key itself.
         */
        data class Send(
            val label: String,
            val plain: ByteArray,
            val wire: ByteArray,
            val sensitive: Boolean = false,
        ) : Action()
        data class Page(val page: Message.GlucosePage) : Action()
        data class Failed(val reason: String) : Action()
        data class Note(val text: String) : Action()
    }

    init {
        require(mac.size == 6) { "identity MAC must be 6 bytes" }
    }

    var state: State = State.IDLE
        private set
    var cipher: FrameCipher = FrameCipher.REGISTERED_V3
        private set

    /** True on the V3 branch (`g` in the vendor driver); false once the V2 fallback is taken. */
    var isV3: Boolean = true
        private set
    var authAttempt: AuthAttempt? = null
        private set

    /** First record index of the newest page seen; the next request starts after it. */
    var cursor: Int = initialCursor.coerceIn(0, 0xFFFF)
        private set
    var lastReplyCipher: FrameCipher? = null
        private set

    /**
     * Frames emitted so far. Changes only when a request actually goes out, so it tells a real
     * new attempt (an IdBind retry, the next auth fallback) apart from a notification that was
     * merely ignored.
     */
    var sendCount: Int = 0
        private set

    private var idBindSeq = 1
    private var fragment: ByteArray? = null
    private var fragmentAtMs = 0L

    val isAuthenticated: Boolean
        get() = state != State.IDLE && state != State.WAITING_AUTH && state != State.FAILED

    fun start(): List<Action> {
        check(state == State.IDLE) { "session already started" }
        return beginAuth(AuthAttempt.V3_REGISTERED)
    }

    fun onNotification(raw: ByteArray): List<Action> {
        if (state == State.IDLE || state == State.FAILED) return listOf(Action.Note("notification in $state ignored"))
        if (raw.isEmpty()) return emptyList()
        val decoded = reassemble(raw) ?: return if (fragment != null) {
            listOf(Action.Note("holding ${fragment!!.size}-byte fragment"))
        } else if (state == State.WAITING_AUTH) {
            authNotAccepted("undecodable reply ${SibionicsGs3Protocol.toHex(raw)}")
        } else {
            listOf(Action.Note("undecodable ${SibionicsGs3Protocol.toHex(raw)}"))
        }
        lastReplyCipher = decoded.cipher
        return onMessage(SibionicsGs3Protocol.parse(decoded.plain))
    }

    internal fun onMessage(message: Message): List<Action> =
        if (state == State.WAITING_AUTH) onAuthReply(message) else onAuthenticated(message)

    /** The step deadline passed with no usable reply. */
    fun onStepTimeout(): List<Action> = when (state) {
        State.WAITING_AUTH -> authNotAccepted("no reply")
        State.IDLE, State.STREAMING, State.FAILED -> emptyList()
        else -> fail("no reply in $state")
    }

    /** The next page request, for the stale-data timer; null outside STREAMING. */
    fun requestNextPage(): Action.Send? {
        if (state != State.STREAMING) return null
        return getDataRequest()
    }

    // ---- auth ----

    private fun beginAuth(attempt: AuthAttempt): List<Action> {
        authAttempt = attempt
        state = State.WAITING_AUTH
        fragment = null
        return when (attempt) {
            AuthAttempt.V3_REGISTERED -> {
                isV3 = true
                cipher = FrameCipher.REGISTERED_V3
                listOf(send("auth-v3", SibionicsGs3Protocol.buildAuthV3(mac), sensitive = true))
            }
            AuthAttempt.V3_PLAIN -> {
                isV3 = true
                cipher = FrameCipher.PLAIN
                listOf(send("auth-v3-plain", SibionicsGs3Protocol.buildAuthV3(mac), sensitive = true))
            }
            AuthAttempt.V2_RC4 -> {
                isV3 = false
                cipher = FrameCipher.RC4
                listOf(send("auth-v2", SibionicsGs3Protocol.buildAuthV2(mac), sensitive = true))
            }
        }
    }

    private fun authNotAccepted(reason: String): List<Action> {
        val next = when (authAttempt) {
            AuthAttempt.V3_REGISTERED -> AuthAttempt.V3_PLAIN
            AuthAttempt.V3_PLAIN -> AuthAttempt.V2_RC4
            AuthAttempt.V2_RC4, null -> return fail("auth not accepted ($reason)")
        }
        return listOf(Action.Note("auth ${authAttempt?.name} not accepted ($reason); trying ${next.name}")) +
            beginAuth(next)
    }

    private fun onAuthReply(message: Message): List<Action> {
        val expected = SibionicsGs3Protocol.replyType(
            if (isV3) SibionicsGs3Protocol.CMD_AUTH_V3 else SibionicsGs3Protocol.CMD_AUTH_V2,
        )
        return when {
            message is Message.Ack && message.replyType == expected -> {
                val accepted = Action.Note(
                    "auth ${authAttempt?.name} accepted result=${message.result} arg=${message.arg} " +
                        "reply cipher=${lastReplyCipher?.name}",
                )
                if (isV3) {
                    state = State.WAITING_BIND_IN
                    listOf(accepted, send("bind-in", SibionicsGs3Protocol.buildBindIn(nowSeconds())))
                } else {
                    idBindSeq = 1
                    state = State.WAITING_ID_BIND
                    listOf(accepted, send("id-bind seq=1", SibionicsGs3Protocol.buildIdBind(1)))
                }
            }
            // Data or system info proves the frame decodes, not that this auth was accepted.
            message is Message.GlucosePage || message is Message.SysInfo ->
                listOf(Action.Note("ignoring $message before auth"))
            else -> authNotAccepted("unexpected reply $message")
        }
    }

    // ---- after auth ----

    private fun onAuthenticated(message: Message): List<Action> {
        if (message is Message.GlucosePage) return onPage(message)
        when (state) {
            State.WAITING_BIND_IN -> if (message.isAckFor(SibionicsGs3Protocol.CMD_BIND_IN) ||
                message.isAckFor(SibionicsGs3Protocol.CMD_BIND_IN_ALT)
            ) {
                state = State.WAITING_SYSINFO15
                return listOf(send("sysinfo-15", SibionicsGs3Protocol.buildSysInfo(SibionicsGs3Protocol.SYSINFO_V3_READY)))
            }

            State.WAITING_SYSINFO15 -> if (message.isSysInfo(SibionicsGs3Protocol.SYSINFO_V3_READY)) {
                state = State.STREAMING
                return listOf(getDataRequest())
            }

            State.WAITING_ID_BIND -> if (message is Message.Ack &&
                message.isAckFor(SibionicsGs3Protocol.CMD_ID_BIND)
            ) {
                return onIdBindAck(message)
            }

            State.WAITING_SYSINFO1 -> if (message.isSysInfo(SibionicsGs3Protocol.SYSINFO_V2_FIRST)) {
                state = State.WAITING_SYSINFO7
                return listOf(send("sysinfo-7", SibionicsGs3Protocol.buildSysInfo(SibionicsGs3Protocol.SYSINFO_V2_SECOND)))
            }

            State.WAITING_SYSINFO7 -> if (message.isSysInfo(SibionicsGs3Protocol.SYSINFO_V2_SECOND)) {
                state = State.WAITING_ACTIVATE
                return listOf(send("activate", SibionicsGs3Protocol.buildActivate(nowSeconds())))
            }

            State.WAITING_ACTIVATE -> if (message.isAckFor(SibionicsGs3Protocol.CMD_ACTIVATE)) {
                state = State.WAITING_TIME
                return listOf(send("time", SibionicsGs3Protocol.buildTime(nowSeconds())))
            }

            State.WAITING_TIME -> if (message.isAckFor(SibionicsGs3Protocol.CMD_TIME)) {
                state = State.STREAMING
                return listOf(getDataRequest())
            }

            State.STREAMING -> if (message is Message.Ack && (
                    message.isAckFor(SibionicsGs3Protocol.CMD_GET_DATA_V3) ||
                        message.isAckFor(SibionicsGs3Protocol.CMD_GET_DATA_V2)
                    )
            ) {
                return listOf(Action.Note("get-data accepted result=${message.result} arg=${message.arg}"))
            }

            else -> Unit
        }
        return listOf(Action.Note("ignoring $message in $state"))
    }

    private fun onIdBindAck(ack: Message.Ack): List<Action> = when (ack.result) {
        SibionicsGs3Protocol.ID_BIND_RESULT_WRONG_ACCOUNT ->
            fail("sensor is bound to another account (IdBind result 2)")
        SibionicsGs3Protocol.ID_BIND_RESULT_OK -> {
            state = State.WAITING_SYSINFO1
            listOf(send("sysinfo-1", SibionicsGs3Protocol.buildSysInfo(SibionicsGs3Protocol.SYSINFO_V2_FIRST)))
        }
        else -> if (idBindSeq == 1) {
            idBindSeq = 2
            listOf(
                Action.Note("IdBind result=${ack.result}; retrying with seq 2"),
                send("id-bind seq=2", SibionicsGs3Protocol.buildIdBind(2)),
            )
        } else {
            fail("IdBind rejected result=${ack.result} arg=${ack.arg}")
        }
    }

    private fun onPage(page: Message.GlucosePage): List<Action> {
        if (page.records.isNotEmpty()) cursor = maxOf(cursor, page.startIndex.coerceIn(0, 0xFFFF))
        return listOf(Action.Page(page))
    }

    private fun getDataRequest(): Action.Send {
        val start = SibionicsGs3Protocol.requestStartIndex(cursor)
        return if (isV3) {
            send("get-data-v3 idx=$start", SibionicsGs3Protocol.buildGetDataV3(start))
        } else {
            send("get-data-v2 idx=$start", SibionicsGs3Protocol.buildGetDataV2(start))
        }
    }

    // ---- framing ----

    /**
     * A notification normally carries one whole frame. When the link is still on the default
     * 23-byte ATT MTU a longer frame (any glucose page, the 38-byte V3 auth) arrives split
     * over full 20-byte notifications. The ciphers are stream ciphers, so the concatenated
     * fragments decrypt like the original buffer.
     */
    private fun reassemble(raw: ByteArray): SibionicsGs3Protocol.Decoded? {
        val now = nowMs()
        val pending = fragment?.takeIf { now - fragmentAtMs <= FRAGMENT_WINDOW_MS }
        fragment = null
        if (pending != null) {
            val combined = pending + raw
            SibionicsGs3Protocol.decodeIncoming(combined, cipher, mac)?.let { return it }
            if (combined.size < MAX_FRAME_SIZE && SibionicsGs3Protocol.couldBeFramePrefix(combined, cipher, mac)) {
                fragment = combined
                fragmentAtMs = now
                return null
            }
        }
        SibionicsGs3Protocol.decodeIncoming(raw, cipher, mac)?.let { return it }
        // Only a notification that fills a default-MTU payload can be the head of a longer
        // frame; a shorter one that does not decode is simply not a frame.
        if (raw.size >= DEFAULT_MTU_PAYLOAD && SibionicsGs3Protocol.couldBeFramePrefix(raw, cipher, mac)) {
            fragment = raw.copyOf()
            fragmentAtMs = now
        }
        return null
    }

    private fun send(label: String, plain: ByteArray, sensitive: Boolean = false): Action.Send {
        sendCount++
        return Action.Send(
            label = label,
            plain = plain,
            wire = SibionicsGs3Protocol.encodeOutgoing(cipher, mac, plain) ?: plain,
            sensitive = sensitive,
        )
    }

    private fun fail(reason: String): List<Action> {
        state = State.FAILED
        fragment = null
        return listOf(Action.Failed(reason))
    }

    private fun nowSeconds(): Long = nowMs() / 1000L

    private fun Message.isAckFor(requestCmd: Int): Boolean =
        this is Message.Ack && replyType == SibionicsGs3Protocol.replyType(requestCmd)

    private fun Message.isSysInfo(subtype: Int): Boolean =
        this is Message.SysInfo && this.subtype == subtype

    companion object {
        private const val DEFAULT_MTU_PAYLOAD = 20
        private const val MAX_FRAME_SIZE = 256
        private const val FRAGMENT_WINDOW_MS = 1_000L
    }
}
