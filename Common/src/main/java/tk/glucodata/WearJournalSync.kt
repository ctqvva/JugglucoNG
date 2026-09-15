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
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Entry) return false
            return timestampMs == other.timestampMs && id == other.id && type == other.type &&
                amount == other.amount && title == other.title && presetId == other.presetId &&
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
            MessageSender.sendSyncMessage(MessageSender.JOURNAL_DATA_PATH, disabled)
            return
        }
        MessageSender.sendSyncMessage(MessageSender.JOURNAL_DATA_PATH, payload)
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
        val sent = MessageSender.sendSyncMessage(MessageSender.JOURNAL_REQ_PATH, data)
        Log.i(LOG_ID, "journal requested sent=$sent")
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
        _journal.value = journal
        Log.i(LOG_ID, "journal received: enabled=${journal.enabled} entries=${journal.entries.size}")
        UiRefreshBus.requestStatusRefresh()
    }

    /** Watch: send an entry for the phone to persist. */
    @JvmStatic
    fun sendAdd(timestampMs: Long, type: Int, amount: Float, presetId: Long = 0L): Boolean {
        return sendCommand(CMD_ADD, timestampMs, 0L, type, amount, presetId)
    }

    /** Watch: delete an entry the phone owns. */
    @JvmStatic
    fun sendDelete(id: Long, timestampMs: Long): Boolean {
        return sendCommand(CMD_DELETE, timestampMs, id, TYPE_NOTE, Float.NaN, 0L)
    }

    private fun sendCommand(
        command: Int,
        timestampMs: Long,
        id: Long,
        type: Int,
        amount: Float,
        presetId: Long,
    ): Boolean {
        val data = ByteBuffer.allocate(1 + 1 + 8 + 8 + 1 + 4 + 8)
            .put(VERSION.toByte())
            .put(command.toByte())
            .putLong(timestampMs)
            .putLong(id)
            .put(type.toByte())
            .putFloat(amount)
            .putLong(presetId)
            .array()
        return MessageSender.sendSyncMessage(MessageSender.JOURNAL_CMD_PATH, data)
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

    /**
     * Watch: shows an entry the user just added before the phone has served it
     * back, so the list does not appear to swallow the tap. The next serve
     * replaces it with the phone's own record.
     */
    @JvmStatic
    fun addLocally(entry: Entry) {
        val current = cached()
        cached = current.copy(entries = (listOf(entry) + current.entries).sortedByDescending { it.timestampMs })
        _journal.value = cached
        UiRefreshBus.requestStatusRefresh()
    }

    /** Watch: drops an entry locally after asking the phone to delete it. */
    @JvmStatic
    fun removeLocally(id: Long) {
        val current = cached()
        cached = current.copy(entries = current.entries.filterNot { it.id == id })
        _journal.value = cached
        UiRefreshBus.requestStatusRefresh()
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
        return Journal(enabled, entries.sortedByDescending { it.timestampMs }, presets)
    }

    /** Decodes a watch command; phone side. Returns null when malformed. */
    internal fun decodeCommand(data: ByteArray): Command? {
        if (data.size < 1 + 1 + 8 + 8 + 1 + 4 + 8) return null
        val buffer = ByteBuffer.wrap(data)
        val version = buffer.get().toInt()
        if (version < MIN_VERSION || version > VERSION) return null
        return Command(
            command = buffer.get().toInt(),
            timestampMs = buffer.long,
            id = buffer.long,
            type = buffer.get().toInt(),
            amount = buffer.float,
            presetId = buffer.long,
        )
    }

    internal data class Command(
        val command: Int,
        val timestampMs: Long,
        val id: Long,
        val type: Int,
        val amount: Float,
        val presetId: Long,
    )
}
