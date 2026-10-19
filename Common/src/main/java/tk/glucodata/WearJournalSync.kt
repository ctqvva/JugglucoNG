package tk.glucodata

import android.content.Context
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Journal over the Data Layer.
 *
 * The journal lives in Room on the phone only, so the watch keeps a cache of
 * what the phone last served and relays new entries back for the phone to
 * persist. Payloads are encoded on the phone side of the bridge so the reflective
 * surface into the mobile source set stays two methods wide.
 *
 * Wire format, current version, big-endian:
 *
 *     served:  u8 version, u8 enabled, u16 entryCount,
 *              entryCount × { i64 timestampMs, i64 id, u8 type, f32 amount,
 *                             u8 titleLen, titleLen × utf8, i64 presetId,
 *                             u8 curveCount,
 *                             curveCount × { u16 minute, f32 activity } },
 *              u16 presetCount,
 *              presetCount × { i64 id, f32 units, u8 nameLen, nameLen × utf8 }
 *
 *     command: u8 version, u8 command, i64 timestampMs, i64 id, u8 type,
 *              f32 amount, i64 presetId
 */
object WearJournalSync {
    private const val LOG_ID = "WearJournalSync"
    /**
     * 2 adds the preset id each insulin entry was dosed with, and each preset's
     * activity curve, so the watch can model insulin on board rather than
     * forecasting as though a dose never happened. A v1 payload still decodes;
     * its entries simply carry no preset, and prediction treats them as
     * unmodelled.
     *
     * 3 adds the immutable resolved curve to each entry. This keeps watch
     * prediction stable when a preset or body weight changes after a dose.
     */
    const val VERSION = 3
    private const val MIN_VERSION = 1

    /**
     * Longest entry identity accepted on the wire, in UTF-8 bytes.
     *
     * The identity is a de-duplication hint, not content, so an over-long or malformed one is
     * ignored rather than refusing the command: losing the de-duplication costs a duplicate on a
     * retry, losing the command loses the entry (#502).
     */
    const val MAX_IDENTITY_BYTES = 64

    const val CMD_ADD = 1
    const val CMD_DELETE = 2

    /** Matches the ordinals of the phone's JournalEntryType. */
    const val TYPE_INSULIN = 0
    const val TYPE_CARBS = 1
    const val TYPE_FINGERSTICK = 2
    const val TYPE_ACTIVITY = 3
    const val TYPE_NOTE = 4

    private const val HISTORY_MS = 24L * 60L * 60L * 1000L
    private const val PREFS = "wear_journal_cache"
    private const val KEY_PAYLOAD = "payload"

    data class Entry(
        val timestampMs: Long,
        val id: Long,
        val type: Int,
        val amount: Float,
        val title: String,
        /** 0 when unknown, which is every entry from a v1 payload. */
        val presetId: Long = 0L,
        /** Resolved per-dose curve; empty for payloads older than v3. */
        val curveMinutes: IntArray = IntArray(0),
        val curveActivity: FloatArray = FloatArray(0),
        /**
         * The id this entry has where it was made, echoed back by the phone. Empty for an entry
         * the phone originated, and for payloads from a build that does not send it: a watch
         * re-sending an add needs this to recognise its own entry instead of adding a second one.
         */
        val entryIdentity: String = "",
        /** True while the entry is only on the watch, waiting to be confirmed by a serve. */
        val pending: Boolean = false,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Entry) return false
            return timestampMs == other.timestampMs && id == other.id && type == other.type &&
                amount == other.amount && title == other.title && presetId == other.presetId &&
                entryIdentity == other.entryIdentity && pending == other.pending &&
                curveMinutes.contentEquals(other.curveMinutes) &&
                curveActivity.contentEquals(other.curveActivity)
        }

        override fun hashCode(): Int = id.hashCode() * 31 + timestampMs.hashCode()
    }

    data class Preset(
        val id: Long,
        val units: Float,
        val name: String,
        /** Activity curve as (minute, activity) pairs; empty from a v1 payload. */
        val curveMinutes: IntArray = IntArray(0),
        val curveActivity: FloatArray = FloatArray(0),
    ) {
        // Arrays compare by identity, and a decode allocates fresh ones every
        // time, so a generated equals would call every payload a change.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Preset) return false
            return id == other.id && units == other.units && name == other.name &&
                curveMinutes.contentEquals(other.curveMinutes) &&
                curveActivity.contentEquals(other.curveActivity)
        }

        override fun hashCode(): Int = id.hashCode() * 31 + name.hashCode()
    }

    data class Journal(
        val enabled: Boolean = false,
        val entries: List<Entry> = emptyList(),
        val presets: List<Preset> = emptyList(),
        /**
         * Whether the phone sent the appended identity block, which is what a phone does from the
         * build that de-duplicates a watch entry (#511). It replaces a version gate: bumping
         * `WearProtocol` for this would make a new phone's text payloads unreadable to a watch that
         * has not been updated, and the two APKs are updated separately.
         */
        val identityEcho: Boolean = false,
    )

    // ---------------------------------------------------------------- phone

    /** Phone: the watch asked for the journal. */
    @JvmStatic
    fun onRequest(fromMs: Long) {
        if (Applic.isWearable) return
        val payload = JournalAccess.serveEntries(if (fromMs > 0L) fromMs else System.currentTimeMillis() - HISTORY_MS)
        Log.i(LOG_ID, "journal request from watch: payload=${payload?.size ?: -1} bytes")
        if (payload == null) {
            // No journal in this variant, or it is switched off. Say so, so the
            // watch hides the feature instead of showing an empty list.
            val disabled = ByteBuffer.allocate(6)
                .put(VERSION.toByte())
                .put(0)
                .putShort(0)
                .putShort(0)
                .array()
            MessageSender.sendSyncMessage(WearMessagePath.SYNC2_JOURNAL_DATA, disabled)
            return
        }
        MessageSender.sendSyncMessage(WearMessagePath.SYNC2_JOURNAL_DATA, payload)
    }

    /** Phone: the watch added or removed an entry. */
    @JvmStatic
    fun onCommand(data: ByteArray?) {
        if (Applic.isWearable || data == null || data.size < 2) return
        if (!JournalAccess.applyCommand(data)) {
            Log.w(LOG_ID, "journal command rejected")
            return
        }
        onRequest(0L)
    }

    /** Phone: push the journal after it changed locally. */
    @JvmStatic
    fun onJournalChanged() {
        if (Applic.isWearable) return
        if (!MessageSender.outgoingAllowed()) return
        onRequest(0L)
    }

    // ---------------------------------------------------------------- watch

    /** Watch: ask the phone for the journal. */
    @JvmStatic
    fun requestSync() {
        if (!Applic.isWearable) return
        val data = ByteBuffer.allocate(9)
            .put(VERSION.toByte())
            .putLong(System.currentTimeMillis() - HISTORY_MS)
            .array()
        val sent = MessageSender.sendSyncMessage(WearMessagePath.SYNC2_JOURNAL_REQ, data)
        // Opening the screen is also the moment to push whatever the watch has been holding.
        flushPending()
        Log.i(LOG_ID, "journal requested sent=$sent pending=${outbox().size}")
    }

    /** Watch: the phone served the journal. */
    @JvmStatic
    fun onServed(data: ByteArray?) {
        if (!Applic.isWearable || data == null || data.isEmpty()) return
        val version = data[0].toInt()
        if (version < MIN_VERSION || version > VERSION) {
            Log.w(LOG_ID, "ignoring journal payload version=$version")
            return
        }
        val journal = runCatching { decode(data) }.getOrNull() ?: return
        store(data)
        cached = journal
        // What a serve proves, out of the queue, before what is left goes out again: a serve is
        // also the only proof available, so the two belong together.
        reconcile(journal)
        flushPending()
        // Publish, do not assign: the served journal is what the phone holds, and what the screen
        // shows is that plus whatever is still only on the watch.
        publish()
        Log.i(
            LOG_ID,
            "journal received: enabled=${journal.enabled} entries=${journal.entries.size} " +
                "identityEcho=${journal.identityEcho} pending=${outbox().size}",
        )
        UiRefreshBus.requestStatusRefresh()
    }

    // ---- the outbox ----------------------------------------------------------------------------------
    //
    // An entry the watch makes when the phone is out of reach used to be announced as saved and then
    // vanish: the send went nowhere, the local cache was the only copy, and the next serve replaced
    // it (#502). The outbox is that copy. It is persisted with the served journal, it is what the
    // screen draws pending entries from, and an item leaves it only when a serve proves the phone has
    // the entry.

    /**
     * One command the phone has not confirmed.
     *
     * [identity] is the key: it is also the id the phone stores the row under, so a re-send is the
     * same row rather than a second one (#511). A delete carries one too, as a storage key only --
     * it is already idempotent, because it names the row the phone owns.
     */
    internal data class Pending(
        val identity: String,
        val command: Int,
        val timestampMs: Long,
        val entryId: Long,
        val type: Int,
        val amount: Float,
        val presetId: Long,
        /** True once this build has handed it to the transport, whatever became of it. */
        val sentOnce: Boolean = false,
        /**
         * What the watch showed for this entry, kept so a pending one reads the same as the
         * phone's own. Never sent: the phone derives its own title from the type and the amount.
         */
        val title: String = "",
    )

    private const val KEY_OUTBOX = "outbox"
    private const val KEY_OUTBOX_SEQ = "outbox_seq"
    private const val KEY_OUTBOX_IDENTITY = "outbox_identity"
    private const val FIELD_SEPARATOR = "\u001F"

    /** One line per item, so the store is a string and the codec is a pure function. */
    internal fun encodeOutbox(items: List<Pending>): String = items.joinToString("\n") { item ->
        listOf(
            item.identity,
            item.command.toString(),
            item.timestampMs.toString(),
            item.entryId.toString(),
            item.type.toString(),
            item.amount.toString(),
            item.presetId.toString(),
            item.sentOnce.toString(),
            item.title,
        ).joinToString(FIELD_SEPARATOR)
    }

    internal fun decodeOutbox(stored: String?): List<Pending> = stored
        ?.lineSequence()
        ?.filter { it.isNotBlank() }
        ?.mapNotNull { line -> decodeOutboxItem(line) }
        ?.toList()
        .orEmpty()

    /** One unreadable line drops that item, never the rest of the queue. */
    private fun decodeOutboxItem(line: String): Pending? {
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size < 9) return null
        val command = fields[1].toIntOrNull() ?: return null
        if (command != CMD_ADD && command != CMD_DELETE) return null
        return Pending(
            identity = fields[0].takeIf { it.isNotEmpty() } ?: return null,
            command = command,
            timestampMs = fields[2].toLongOrNull() ?: return null,
            entryId = fields[3].toLongOrNull() ?: return null,
            type = fields[4].toIntOrNull() ?: return null,
            amount = fields[5].toFloatOrNull() ?: return null,
            presetId = fields[6].toLongOrNull() ?: return null,
            sentOnce = fields[7].toBooleanStrictOrNull() ?: false,
            title = fields[8],
        )
    }

    /**
     * Whether an item that has already been sent may go out again.
     *
     * A delete always may: it names the row the phone owns, so repeating it changes nothing. An add
     * may only against a phone that echoes identities, i.e. one that de-duplicates (#511) -- against
     * an older phone the second send is a second row, and a duplicated entry is the worse of the two
     * ways to be wrong.
     */
    internal fun mayRepeat(item: Pending, identityEcho: Boolean): Boolean =
        item.command == CMD_DELETE || identityEcho

    /**
     * Whether a serve proves the phone has this item.
     *
     * An add is only ever proven by its own identity, and only by a phone that echoes them: a serve
     * that merely lists an equal timestamp and amount is the phone's own entry and says nothing
     * about ours, so the item stays and the entry keeps its pending marker. A delete is proven by
     * the absence of the row, which any phone can confirm.
     */
    internal fun confirmedBy(served: Journal, item: Pending): Boolean = when (item.command) {
        CMD_ADD -> served.identityEcho && served.entries.any { it.entryIdentity == item.identity }
        CMD_DELETE -> served.entries.none { it.id == item.entryId }
        else -> false
    }

    private fun outboxPrefs() = Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var outboxCache: List<Pending>? = null

    /**
     * Every read-modify-write of the outbox, and the identity counter, happens under this lock.
     * An add arrives on the screen's IO thread and a serve on the message thread; without it a
     * serve that read the queue before an add was stored writes its older copy back, and the new
     * entry is gone -- the #502 loss again, one level down. Same for two adds drawing one counter.
     */
    private val outboxLock = Any()

    private fun outbox(): List<Pending> {
        outboxCache?.let { return it }
        val items = decodeOutbox(runCatching { outboxPrefs()?.getString(KEY_OUTBOX, null) }.getOrNull())
        outboxCache = items
        return items
    }

    private fun storeOutbox(items: List<Pending>) {
        outboxCache = items
        runCatching {
            outboxPrefs()?.edit()?.putString(KEY_OUTBOX, encodeOutbox(items))?.apply()
        }
        publish()
    }

    /** A new identity: this install's prefix and a counter that only ever goes up. */
    private fun nextIdentity(): String = synchronized(outboxLock) { nextIdentityLocked() }

    private fun nextIdentityLocked(): String {
        val prefs = runCatching { outboxPrefs() }.getOrNull()
        val prefix = runCatching {
            prefs?.getString(KEY_OUTBOX_IDENTITY, null)
                ?: "wear:${java.util.UUID.randomUUID().toString().take(8)}"
        }.getOrNull()?.also {
            if (it != prefs?.getString(KEY_OUTBOX_IDENTITY, null)) {
                runCatching { prefs?.edit()?.putString(KEY_OUTBOX_IDENTITY, it)?.apply() }
            }
        } ?: return "wear:${java.util.UUID.randomUUID()}"
        val seq = runCatching { prefs?.getInt(KEY_OUTBOX_SEQ, 0) ?: 0 }.getOrDefault(0) + 1
        // commit, not apply: a counter that did not reach disk before the process died is handed
        // out again, and a reused identity makes the phone overwrite the earlier entry.
        runCatching { prefs?.edit()?.putInt(KEY_OUTBOX_SEQ, seq)?.commit() }
        return "$prefix:$seq"
    }

    /**
     * What the screen shows: the phone's entries, plus the ones only the watch has, marked pending.
     */
    private fun publish() {
        val served = cached ?: cached()
        val stillPending = outbox().filter { it.command == CMD_ADD }
        val pendingEntries = stillPending.map { item ->
            Entry(
                timestampMs = item.timestampMs,
                id = 0L,
                type = item.type,
                amount = item.amount,
                title = item.title,
                presetId = item.presetId,
                entryIdentity = item.identity,
                pending = true,
            )
        }
        val confirmed = served.entries.map { it.entryIdentity }.toSet()
        // A queued delete hides its row straight away, because the serve that proves it has not
        // arrived yet. It reappears if that serve still lists it.
        val deleting = stillPending.map { it.entryId }.toSet() +
            outbox().filter { it.command == CMD_DELETE }.map { it.entryId }
        val merged = (
            served.entries.filterNot { it.id in deleting && it.id > 0L } +
                pendingEntries.filterNot { it.entryIdentity in confirmed }
            ).sortedByDescending { it.timestampMs }
        _journal.value = served.copy(entries = merged)
    }

    /**
     * Sends what the phone has not confirmed, as far as it is safe to.
     *
     * An item that has never been sent goes out every time. An add that has been sent goes out again
     * **only** when the last serve carried the identity block, i.e. the phone is one that
     * de-duplicates (#511): against a phone that does not, a second add is a second row, and a lost
     * entry is better than a duplicated one. A delete is idempotent either way -- it names the row
     * the phone owns -- so it is always safe to repeat.
     */
    @JvmStatic
    fun flushPending() {
        synchronized(outboxLock) { flushPendingLocked() }
    }

    private fun flushPendingLocked() {
        val items = outbox()
        if (items.isEmpty()) return
        val mayRepeat = cached?.identityEcho == true
        var changed = false
        val sent = items.map { item ->
            val repeat = item.sentOnce
            if (repeat && !mayRepeat(item, mayRepeat)) return@map item
            changed = true
            sendCommand(
                command = item.command,
                timestampMs = item.timestampMs,
                id = item.entryId,
                type = item.type,
                amount = item.amount,
                presetId = item.presetId,
                entryIdentity = item.identity,
            )
            item.copy(sentOnce = true)
        }
        if (changed) storeOutbox(sent)
    }

    /**
     * Drops what a serve proves the phone already has, then sends what is left.
     *
     * An add leaves the outbox only when the served journal carries its identity, so a phone too old
     * to echo leaves the entry marked pending instead of silently losing it. A delete leaves as soon
     * as a serve does not contain the row, which any phone can confirm.
     */
    private fun reconcile(served: Journal) = synchronized(outboxLock) {
        val kept = outbox().filterNot { confirmedBy(served, it) }
        if (kept.size != outbox().size) storeOutbox(kept)
    }

    /**
     * Watch: record an entry for the phone to persist.
     *
     * Returns whether it is **saved**, which now means persisted in the outbox rather than handed
     * to a transport. The screen closed on this answer and the entry used to vanish when there was
     * no phone to send it to (#502); delivery is a separate concern the outbox owns. [title] is what
     * the watch shows for it while it is pending, and is never sent: the phone derives its own.
     */
    @JvmStatic
    @JvmOverloads
    fun sendAdd(
        timestampMs: Long,
        type: Int,
        amount: Float,
        presetId: Long = 0L,
        title: String = "",
    ): Boolean {
        if (outboxPrefs() == null) return false
        val item = Pending(
            identity = nextIdentity(),
            command = CMD_ADD,
            timestampMs = timestampMs,
            entryId = 0L,
            type = type,
            amount = amount,
            presetId = presetId,
            title = title,
        )
        synchronized(outboxLock) { storeOutbox(outbox() + item) }
        flushPending()
        return true
    }

    /** Watch: delete an entry the phone owns. Idempotent, so repeating it is safe. */
    @JvmStatic
    fun sendDelete(id: Long, timestampMs: Long): Boolean {
        if (outboxPrefs() == null) return false
        val item = Pending(
            identity = nextIdentity(),
            command = CMD_DELETE,
            timestampMs = timestampMs,
            entryId = id,
            type = TYPE_NOTE,
            amount = Float.NaN,
            presetId = 0L,
        )
        synchronized(outboxLock) { storeOutbox(outbox() + item) }
        flushPending()
        return true
    }

    private fun sendCommand(
        command: Int,
        timestampMs: Long,
        id: Long,
        type: Int,
        amount: Float,
        presetId: Long,
        entryIdentity: String? = null,
    ): Boolean {
        val identity = entryIdentity.orEmpty().takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8)
        // Appended, so a phone that predates it reads the fixed fields and ignores the rest: the
        // command still applies, it just cannot de-duplicate a repeat (#502).
        val buffer = ByteBuffer.allocate(1 + 1 + 8 + 8 + 1 + 4 + 8 + if (identity != null) 2 + identity.size else 0)
            .put(VERSION.toByte())
            .put(command.toByte())
            .putLong(timestampMs)
            .putLong(id)
            .put(type.toByte())
            .putFloat(amount)
            .putLong(presetId)
        if (identity != null) {
            buffer.putShort(identity.size.toShort()).put(identity)
        }
        return MessageSender.sendSyncMessage(WearMessagePath.SYNC2_JOURNAL_CMD, buffer.array())
    }

    @Volatile private var cached: Journal? = null

    private val _journal = kotlinx.coroutines.flow.MutableStateFlow<Journal?>(null)

    /**
     * Watch: the journal as it stands, updated when the phone serves a new one.
     *
     * The screen used to poll the cache ten times at 600 ms and then give up,
     * so a serve that arrived a moment late never appeared at all and the list
     * stayed on whatever was cached from the last run.
     */
    @JvmStatic
    val journal: kotlinx.coroutines.flow.StateFlow<Journal> by lazy {
        _journal.value = cached()
        @Suppress("UNCHECKED_CAST")
        (_journal as kotlinx.coroutines.flow.StateFlow<Journal>)
    }

    /** Watch: last journal the phone served, restored across app starts. */
    @JvmStatic
    fun cached(): Journal {
        cached?.let { return it }
        val stored = runCatching {
            Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.getString(KEY_PAYLOAD, null)
                ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        }.getOrNull()
        val journal = stored?.let { runCatching { decode(it) }.getOrNull() } ?: Journal()
        cached = journal
        _journal.value = journal
        return journal
    }


    private fun store(payload: ByteArray) {
        runCatching {
            Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(KEY_PAYLOAD, android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP))
                ?.apply()
        }
    }

    // --------------------------------------------------------------- codec

    internal fun decode(data: ByteArray): Journal {
        val buffer = ByteBuffer.wrap(data)
        // Kept free of logging so it stays a pure codec, testable on the JVM.
        val version = buffer.get().toInt()
        if (version < MIN_VERSION || version > VERSION) return Journal()
        val enabled = buffer.get().toInt() != 0
        val entryCount = buffer.short.toInt() and 0xFFFF
        val entries = ArrayList<Entry>(entryCount)
        repeat(entryCount) {
            if (buffer.remaining() < 8 + 8 + 1 + 4 + 1) return@repeat
            val timestamp = buffer.long
            val id = buffer.long
            val type = buffer.get().toInt()
            val amount = buffer.float
            val titleLen = buffer.get().toInt() and 0xFF
            if (buffer.remaining() < titleLen) return@repeat
            val title = ByteArray(titleLen).also { buffer.get(it) }.toString(StandardCharsets.UTF_8)
            val presetId = if (version >= 2 && buffer.remaining() >= 8) buffer.long else 0L
            var minutes = IntArray(0)
            var activity = FloatArray(0)
            if (version >= 3 && buffer.remaining() >= 1) {
                val curveCount = buffer.get().toInt() and 0xFF
                if (buffer.remaining() >= curveCount * 6) {
                    minutes = IntArray(curveCount)
                    activity = FloatArray(curveCount)
                    for (index in 0 until curveCount) {
                        minutes[index] = buffer.short.toInt() and 0xFFFF
                        activity[index] = buffer.float
                    }
                }
            }
            entries.add(Entry(timestamp, id, type, amount, title, presetId, minutes, activity))
        }
        val presets = ArrayList<Preset>()
        if (buffer.remaining() >= 2) {
            val presetCount = buffer.short.toInt() and 0xFFFF
            repeat(presetCount) {
                if (buffer.remaining() < 8 + 4 + 1) return@repeat
                val id = buffer.long
                val units = buffer.float
                val nameLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < nameLen) return@repeat
                val name = ByteArray(nameLen).also { buffer.get(it) }.toString(StandardCharsets.UTF_8)
                var minutes = IntArray(0)
                var activity = FloatArray(0)
                if (version >= 2 && buffer.remaining() >= 1) {
                    val curveCount = buffer.get().toInt() and 0xFF
                    if (buffer.remaining() >= curveCount * 6) {
                        minutes = IntArray(curveCount)
                        activity = FloatArray(curveCount)
                        for (index in 0 until curveCount) {
                            minutes[index] = buffer.short.toInt() and 0xFFFF
                            activity[index] = buffer.float
                        }
                    }
                }
                presets.add(Preset(id, units, name, minutes, activity))
            }
        }
        // The identity block is appended after the presets, one optional entry per served entry in
        // payload order, so a build that predates it stops reading before it and the entries it
        // already has are unaffected.
        val identityBlockStart = buffer.position()
        val identities = readServedIdentities(buffer, entries.size)
        val withIdentity = entries.mapIndexed { index, entry ->
            entry.copy(entryIdentity = identities.getOrElse(index) { "" })
        }
        return Journal(
            enabled,
            withIdentity.sortedByDescending { it.timestampMs },
            presets,
            identityEcho = buffer.position() > identityBlockStart,
        )
    }

    /** The appended `[u16 count][u8 length][utf-8]` identities, empty for an older payload. */
    private fun readServedIdentities(buffer: ByteBuffer, entryCount: Int): List<String> {
        if (buffer.remaining() < 2) return emptyList()
        val count = buffer.short.toInt() and 0xFFFF
        if (count > entryCount) return emptyList()
        val identities = ArrayList<String>(count)
        repeat(count) {
            // Length 0 is a phone-originated entry, which has no identity of its own -- not a
            // malformed field. And one field this build will not take must not throw away the
            // identities read before it, so each check ends the block instead of throwing it.
            if (buffer.remaining() < 1) return identities
            val length = buffer.get().toInt() and 0xFF
            if (length > MAX_IDENTITY_BYTES || buffer.remaining() < length) return identities
            val identity = ByteArray(length).also { buffer.get(it) }.toString(StandardCharsets.UTF_8)
            if (identity.any { it.isISOControl() }) return identities
            identities.add(identity)
        }
        return identities
    }

    /** Decodes a watch command; phone side. Returns null when malformed. */
    internal fun decodeCommand(data: ByteArray): Command? {
        if (data.size < 1 + 1 + 8 + 8 + 1 + 4 + 8) return null
        val buffer = ByteBuffer.wrap(data)
        val version = buffer.get().toInt()
        if (version < MIN_VERSION || version > VERSION) return null
        val command = Command(
            command = buffer.get().toInt(),
            timestampMs = buffer.long,
            id = buffer.long,
            type = buffer.get().toInt(),
            amount = buffer.float,
            presetId = buffer.long,
        )
        return command.copy(entryIdentity = readIdentity(buffer))
    }

    /**
     * The trailing `<u16 length><utf-8>` identity, or "" when the frame has none or carries one
     * this build will not take. Never fails the command: see [MAX_IDENTITY_BYTES].
     */
    private fun readIdentity(buffer: ByteBuffer): String {
        if (buffer.remaining() < 2) return ""
        val length = buffer.short.toInt() and 0xFFFF
        if (length <= 0 || length > MAX_IDENTITY_BYTES || buffer.remaining() < length) return ""
        val identity = ByteArray(length).also { buffer.get(it) }.toString(StandardCharsets.UTF_8)
        return if (identity.any { it.isISOControl() }) "" else identity
    }

    internal data class Command(
        val command: Int,
        val timestampMs: Long,
        val id: Long,
        val type: Int,
        val amount: Float,
        val presetId: Long,
        /** The id the watch gave this entry; the phone stores it and echoes it back. */
        val entryIdentity: String = "",
    )
}
