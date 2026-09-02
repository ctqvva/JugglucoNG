package tk.glucodata.data.journal

import android.content.Context
import java.util.UUID
import tk.glucodata.Applic

internal object CloneJournalIdentity {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val ORIGIN_KEY = "clone_journal_origin_v1"

    @Synchronized
    fun originId(context: Context = Applic.app): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(ORIGIN_KEY, null)
            ?.trim()
            ?.takeIf(::isValidOrigin)
            ?.let { return it }
        val generated = UUID.randomUUID().toString()
        require(prefs.edit().putString(ORIGIN_KEY, generated).commit()) {
            "Could not persist Clone journal origin"
        }
        return generated
    }

    fun stableEntryId(
        entry: JournalEntryEntity,
        localOrigin: String,
    ): String {
        require(isValidOrigin(localOrigin)) { "Invalid Clone journal origin" }
        val source = JournalEntrySource.fromStorage(entry.source)
        val existing = entry.sourceRecordId?.trim()?.takeIf(String::isNotEmpty)
        return if (!isCloneJournalExportSource(entry.source) && existing != null) {
            existing
        } else {
            localEntryId(
                origin = localOrigin,
                localId = entry.id,
                type = JournalEntryType.fromStorage(entry.entryType),
            )
        }
    }

    fun localEntryId(
        origin: String,
        localId: Long,
        type: JournalEntryType,
    ): String {
        require(isValidOrigin(origin)) { "Invalid Clone journal origin" }
        require(localId > 0L) { "Invalid Clone journal row identifier" }
        return JournalTreatmentTransfer.sourceRecordIdForBaseId(
            sourcePrefix = "clone:$origin",
            baseId = localBaseId(localId),
            type = type,
        )
    }

    fun localTombstoneBaseId(origin: String, localId: Long): String {
        require(isValidOrigin(origin)) { "Invalid Clone journal origin" }
        require(localId > 0L) { "Invalid Clone journal row identifier" }
        return "clone:$origin:${localBaseId(localId)}"
    }

    fun entryIdsForTombstoneBase(baseId: String): List<String> {
        require(isValidStableBaseId(baseId)) { "Invalid Clone journal tombstone identity" }
        return JournalTreatmentTransfer.sourceRecordIdsForBaseId(
            sourcePrefix = baseId.substringBeforeLast(":journal:"),
            baseId = "journal:${baseId.substringAfterLast(":journal:")}",
        )
    }

    fun localRowId(
        stableEntryId: String,
        localOrigin: String,
        type: JournalEntryType,
    ): Long? {
        if (!isValidOrigin(localOrigin)) return null
        val prefix = "clone:$localOrigin:journal:"
        val suffix = ":${type.storageValue}"
        if (!stableEntryId.startsWith(prefix) || !stableEntryId.endsWith(suffix)) return null
        val idText = stableEntryId.substring(prefix.length, stableEntryId.length - suffix.length)
        return idText.toLongOrNull()?.takeIf { it > 0L && it.toString() == idText }
    }

    private fun localBaseId(localId: Long): String = "journal:$localId"

    private fun isValidStableBaseId(value: String): Boolean {
        val marker = ":journal:"
        val markerIndex = value.lastIndexOf(marker)
        if (markerIndex <= "clone:".length || markerIndex + marker.length >= value.length) return false
        val prefix = value.substring(0, markerIndex)
        if (!prefix.startsWith("clone:")) return false
        val origin = prefix.removePrefix("clone:")
        val id = value.substring(markerIndex + marker.length)
        return isValidOrigin(origin) && id.toLongOrNull()?.let { it > 0L && it.toString() == id } == true
    }

    private fun isValidOrigin(value: String): Boolean =
        value.length in 1..96 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
