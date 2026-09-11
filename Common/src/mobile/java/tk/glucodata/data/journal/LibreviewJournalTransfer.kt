package tk.glucodata.data.journal

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Renders Journal entries as LibreView measurement-log entries.
 *
 * LibreView's payload is assembled in native code (`net/libreview/libreview.cpp` for the
 * Libre 2 document, `newlibre3.cpp` for the Libre 3 one) and its food/insulin/generic
 * arrays used to be filled only from the legacy `Numdata` store, which the Compose journal
 * never writes. This builds the same entry shapes from the Room journal instead, so what
 * the user records reaches LibreView the way it already reaches Nightscout.
 *
 * The two documents differ only in how timestamps are spelled: the Libre 3 flavour omits
 * the milliseconds the Libre 2 flavour writes. Everything else here mirrors `writeentry`
 * in `net/libreview/librenumbers.hpp`.
 */
object LibreviewJournalTransfer {

    /** Record-type tags, the low byte of a recordNumber. Mirrors `RecordType` in librenumbers.hpp. */
    private const val TYPE_RAPID = 1
    private const val TYPE_LONG = 3
    private const val TYPE_FOOD = 4
    private const val TYPE_NOTE = 16

    /**
     * Journal record numbers start past this.
     *
     * A recordNumber is `id << 8 | type`, and the legacy Numdata writer builds its half of
     * the same namespace from a counter that starts at 1. Room row ids also start at 1, so
     * without an offset a user who has both would send two different entries under one
     * record number and LibreView would keep whichever arrived last.
     */
    private const val RECORD_NUMBER_BASE = 1_000_000L

    /** Rendered entries, grouped by the array each belongs to. */
    data class Entries(
        val food: List<String> = emptyList(),
        val insulin: List<String> = emptyList(),
        val generic: List<String> = emptyList()
    ) {
        val isEmpty: Boolean get() = food.isEmpty() && insulin.isEmpty() && generic.isEmpty()
    }

    /**
     * Whether this entry has anything LibreView can represent.
     *
     * Fingersticks are deliberately absent: LibreView carries them in `bloodGlucoseEntries`,
     * a channel this payload does not populate, and folding them into free-text notes would
     * present a measurement as a comment.
     */
    fun isSendable(entry: JournalEntryEntity): Boolean = when (JournalEntryType.fromStorage(entry.entryType)) {
        JournalEntryType.CARBS -> positive(entry.amount) != null
        JournalEntryType.INSULIN -> positive(entry.amount) != null
        JournalEntryType.NOTE, JournalEntryType.ACTIVITY -> noteText(entry).isNotEmpty()
        else -> false
    }

    /**
     * Entries that arrived from another system are not ours to forward: re-uploading them
     * would bounce Nightscout's and AAPS's own records back out through a second cloud.
     */
    fun isExternalMirrorSource(source: String): Boolean =
        source == JournalEntrySource.AAPS.storageValue ||
            source == JournalEntrySource.NIGHTSCOUT.storageValue ||
            source == JournalEntrySource.API.storageValue

    fun build(
        entries: List<JournalEntryEntity>,
        presets: Map<Long, JournalInsulinPresetEntity>,
        libre3: Boolean,
        zone: ZoneId = ZoneId.systemDefault()
    ): Entries {
        val food = ArrayList<String>()
        val insulin = ArrayList<String>()
        val generic = ArrayList<String>()
        for (entry in entries) {
            if (entry.timestamp <= 0L) continue
            if (isExternalMirrorSource(entry.source)) continue
            when (JournalEntryType.fromStorage(entry.entryType)) {
                JournalEntryType.CARBS -> foodEntry(entry, libre3, zone)?.let(food::add)
                JournalEntryType.INSULIN ->
                    insulinEntry(entry, presets[entry.insulinPresetId ?: -1L], libre3, zone)?.let(insulin::add)
                JournalEntryType.NOTE, JournalEntryType.ACTIVITY ->
                    noteEntry(entry, libre3, zone)?.let(generic::add)
                else -> Unit
            }
        }
        return Entries(food, insulin, generic)
    }

    fun recordNumber(entryId: Long, typeTag: Int): Long = ((RECORD_NUMBER_BASE + entryId) shl 8) or typeTag.toLong()

    private fun foodEntry(entry: JournalEntryEntity, libre3: Boolean, zone: ZoneId): String? {
        val grams = positive(entry.amount) ?: return null
        return entryJson(
            valueKey = "foodType",
            valueName = mealType(grams, entry.timestamp, zone),
            unitKey = "gramsCarbs",
            unitValue = format(grams, decimals = 0),
            text = null,
            recordNumber = recordNumber(entry.id, TYPE_FOOD),
            timestamp = entry.timestamp,
            libre3 = libre3,
            zone = zone
        )
    }

    private fun insulinEntry(
        entry: JournalEntryEntity,
        preset: JournalInsulinPresetEntity?,
        libre3: Boolean,
        zone: ZoneId
    ): String? {
        val units = positive(entry.amount) ?: return null
        // Same classification Nightscout uploads use: a preset that deliberately stays out
        // of IOB is the basal one. An unknown preset counts as rapid rather than being
        // dropped, so an entry whose preset was deleted still reaches LibreView.
        val isLong = preset?.let { !it.countsTowardIob } ?: false
        return entryJson(
            valueKey = "insulinType",
            valueName = if (isLong) "LongActing" else "RapidActing",
            unitKey = "units",
            unitValue = format(units, decimals = 1),
            text = null,
            recordNumber = recordNumber(entry.id, if (isLong) TYPE_LONG else TYPE_RAPID),
            timestamp = entry.timestamp,
            libre3 = libre3,
            zone = zone
        )
    }

    private fun noteEntry(entry: JournalEntryEntity, libre3: Boolean, zone: ZoneId): String? {
        val text = noteText(entry).takeIf { it.isNotEmpty() } ?: return null
        return entryJson(
            valueKey = "type",
            valueName = "com.abbottdiabetescare.informatics.customnote",
            unitKey = null,
            unitValue = null,
            text = text,
            recordNumber = recordNumber(entry.id, TYPE_NOTE),
            timestamp = entry.timestamp,
            libre3 = libre3,
            zone = zone
        )
    }

    /**
     * One measurement-log entry.
     *
     * A note carries its wording in `extendedProperties.text` and no value field at all,
     * which is the shape `writeentry` produces when it is handed zero units.
     */
    private fun entryJson(
        valueKey: String,
        valueName: String,
        unitKey: String?,
        unitValue: String?,
        text: String?,
        recordNumber: Long,
        timestamp: Long,
        libre3: Boolean,
        zone: ZoneId
    ): String {
        val sb = StringBuilder(220)
        sb.append('{').append('"').append(valueKey).append("\":\"").append(escape(valueName)).append('"')
        if (unitKey != null && unitValue != null) {
            sb.append(",\"").append(unitKey).append("\":").append(unitValue)
        }
        sb.append(",\"extendedProperties\":{\"factoryTimestamp\":\"")
            .append(factoryTimestamp(timestamp, libre3))
            .append('"')
        if (text != null) {
            sb.append(",\"text\":\"").append(escape(text)).append('"')
        }
        sb.append(",\"linkedGlucoseRecordNumber\":\"0\"}")
        sb.append(",\"recordNumber\":").append(recordNumber)
        sb.append(",\"timestamp\":\"").append(localTimestamp(timestamp, libre3)).append('"')
        sb.append('}')
        return sb.toString()
    }

    /**
     * Meal naming, copied from `getmealtype`: only a portion big enough to be a meal gets
     * named after the time of day, and anything outside normal meal hours stays a snack.
     */
    internal fun mealType(grams: Float, timestamp: Long, zone: ZoneId): String {
        if (grams <= 50f) return "Snack"
        val hour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), zone).hour
        return when {
            hour <= 3 -> "Snack"
            hour < 11 -> "Breakfast"
            hour < 16 -> "Lunch"
            hour < 22 -> "Dinner"
            else -> "Snack"
        }
    }

    private fun noteText(entry: JournalEntryEntity): String {
        val title = entry.title.trim()
        val note = entry.note?.trim().orEmpty()
        return when {
            title.isEmpty() -> note
            note.isEmpty() || note == title -> title
            else -> "$title: $note"
        }
    }

    /** LibreView records to the minute, so the seconds are dropped rather than rounded. */
    private fun minuteOf(timestamp: Long, zone: ZoneId): ZonedDateTime =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), zone).truncatedTo(ChronoUnit.MINUTES)

    internal fun factoryTimestamp(timestamp: Long, libre3: Boolean): String {
        val utc = minuteOf(timestamp, ZoneId.of("UTC"))
        return String.format(
            Locale.US,
            if (libre3) "%04d-%02d-%02dT%02d:%02d:00Z" else "%04d-%02d-%02dT%02d:%02d:00.000Z",
            utc.year, utc.monthValue, utc.dayOfMonth, utc.hour, utc.minute
        )
    }

    internal fun localTimestamp(timestamp: Long, libre3: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = minuteOf(timestamp, zone)
        val offsetMinutes = local.offset.totalSeconds / 60
        val sign = if (offsetMinutes < 0) '-' else '+'
        val absMinutes = kotlin.math.abs(offsetMinutes)
        return String.format(
            Locale.US,
            if (libre3) "%04d-%02d-%02dT%02d:%02d:00%c%02d:%02d" else "%04d-%02d-%02dT%02d:%02d:00.000%c%02d:%02d",
            local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute,
            sign, absMinutes / 60, absMinutes % 60
        )
    }

    private fun format(value: Float, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    private fun positive(amount: Float?): Float? =
        amount?.takeIf { it.isFinite() && it > 0f }

    /**
     * Escapes to pure ASCII, not just to valid JSON.
     *
     * The rendered entries cross into the native writer as a Java string, and JNI hands
     * those over in modified UTF-8, which spells anything outside the BMP as a surrogate
     * pair rather than as the four UTF-8 bytes an HTTP body needs. One emoji in a note
     * would therefore have corrupted the whole LibreView document — glucose data included.
     * Escaping every non-ASCII character sidesteps that, and makes the byte count the
     * native buffer is sized from exactly the character count.
     */
    private fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' || ch.code > 0x7E -> sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
