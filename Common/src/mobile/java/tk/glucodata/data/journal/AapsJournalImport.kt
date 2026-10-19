package tk.glucodata.data.journal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import tk.glucodata.Log

object AapsJournalImport {
    private const val TAG = "AapsJournalImport"
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val SOURCE_PREFIX = "aaps"

    const val PREF_KEY = "journal_aaps_import_enabled"
    const val ACTION_NEW_TREATMENT = "info.nightscout.client.NEW_TREATMENT"
    const val ACTION_CHANGED_TREATMENT = "info.nightscout.client.CHANGED_TREATMENT"
    const val ACTION_REMOVED_TREATMENT = "info.nightscout.client.REMOVED_TREATMENT"
    const val ACTION_NEW_FOOD = "info.nightscout.client.NEW_FOOD"

    private val supportedActions = setOf(
        ACTION_NEW_TREATMENT,
        ACTION_CHANGED_TREATMENT,
        ACTION_REMOVED_TREATMENT,
        ACTION_NEW_FOOD
    )

    private val treatmentExtraKeys = listOf("treatments", "treatment", "data")

    data class ImportResult(
        val importedEntries: Int,
        val deletedEntries: Int,
        val skippedTreatments: Int,
        val treatmentObjects: Int
    )

    fun isEnabled(context: Context): Boolean {
        return context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_KEY, enabled)
            .apply()
    }

    suspend fun handleIntent(context: Context, intent: Intent): ImportResult {
        val action = intent.action ?: return emptyResult()
        if (action !in supportedActions) return emptyResult()

        val treatments = extractTreatmentObjects(intent.extras)
        if (treatments.isEmpty()) return emptyResult()

        val repository = JournalRepository()
        repository.deleteAapsSenderOnlyNotesOnce()
        if (action == ACTION_REMOVED_TREATMENT) {
            val sourceIds = treatments.flatMap { treatment ->
                JournalTreatmentTransfer.sourceRecordIdsForTreatment(treatment, SOURCE_PREFIX)
            }
            repository.deleteEntriesBySourceRecordIds(sourceIds)
            return ImportResult(
                importedEntries = 0,
                deletedEntries = sourceIds.distinct().size,
                skippedTreatments = 0,
                treatmentObjects = treatments.size
            )
        }

        repository.ensureDefaultInsulinPresets()
        val insulinPresets = repository.getInsulinPresetsSnapshot()

        var imported = 0
        var deleted = 0
        var skipped = 0
        for (treatment in treatments) {
            val parsed = JournalTreatmentTransfer.parseTreatment(
                context = context,
                treatment = treatment,
                source = JournalEntrySource.AAPS,
                sourcePrefix = SOURCE_PREFIX,
                insulinPresets = insulinPresets
            )
            if (parsed == null) {
                skipped++
                continue
            }
            if (parsed.deleteOnly) {
                repository.deleteEntriesBySourceRecordIds(parsed.candidateSourceRecordIds)
                deleted += parsed.candidateSourceRecordIds.size
                continue
            }
            for (input in parsed.inputs) {
                repository.upsertEntry(input)
                imported++
            }
            if (action == ACTION_CHANGED_TREATMENT) {
                val importedSourceIds = parsed.inputs.mapNotNull { it.sourceRecordId }.toSet()
                val staleSourceIds = parsed.candidateSourceRecordIds.filterNot { it in importedSourceIds }
                repository.deleteEntriesBySourceRecordIds(staleSourceIds)
                deleted += staleSourceIds.size
            }
        }
        return ImportResult(
            importedEntries = imported,
            deletedEntries = deleted,
            skippedTreatments = skipped,
            treatmentObjects = treatments.size
        )
    }

    @Suppress("DEPRECATION")
    fun describeExtras(intent: Intent): String {
        val extras = intent.extras ?: return "none"
        val keys = extras.keySet().sorted()
        if (keys.isEmpty()) return "empty"
        val summary = keys.joinToString(prefix = "[", postfix = "]") { key ->
            val value = runCatching { extras.get(key) }.getOrNull()
            "$key:${describeValue(value)}"
        }
        return summary.take(400)
    }

    private fun emptyResult(): ImportResult =
        ImportResult(
            importedEntries = 0,
            deletedEntries = 0,
            skippedTreatments = 0,
            treatmentObjects = 0
        )

    @Suppress("DEPRECATION")
    private fun extractTreatmentObjects(extras: Bundle?): List<JSONObject> {
        if (extras == null) return emptyList()
        val result = ArrayList<JSONObject>()
        for (key in treatmentExtraKeys) {
            appendJsonValue(extras.get(key), result)
        }
        if (result.isEmpty()) {
            for (key in extras.keySet()) {
                if (treatmentExtraKeys.any { it.equals(key, ignoreCase = true) }) {
                    appendJsonValue(extras.get(key), result)
                }
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun appendJsonValue(value: Any?, out: MutableList<JSONObject>) {
        when (value) {
            null -> return
            is Bundle -> {
                for (key in value.keySet()) appendJsonValue(value.get(key), out)
            }
            is JSONObject -> out.add(value)
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    value.optJSONObject(index)?.let(out::add)
                }
            }
            is String -> appendJsonString(value, out)
            is ByteArray -> appendJsonString(value.toString(Charsets.UTF_8), out)
            is Iterable<*> -> value.forEach { appendJsonValue(it, out) }
            is Array<*> -> value.forEach { appendJsonValue(it, out) }
            else -> appendJsonString(value.toString(), out)
        }
    }

    private fun appendJsonString(raw: String, out: MutableList<JSONObject>) {
        val text = raw.trim()
        if (text.isEmpty()) return
        try {
            when {
                text.startsWith("[") -> appendJsonValue(JSONArray(text), out)
                text.startsWith("{") -> {
                    val root = JSONObject(text)
                    val nested = root.optNestedTreatmentPayload()
                    if (nested != null) {
                        appendJsonValue(nested, out)
                    } else {
                        out.add(root)
                    }
                }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Ignoring malformed AAPS treatment payload: ${e.message}")
        }
    }

    private fun JSONObject.optNestedTreatmentPayload(): Any? {
        for (key in treatmentExtraKeys) {
            if (has(key) && !isNull(key)) return opt(key)
        }
        return null
    }

    private fun describeValue(value: Any?): String =
        when (value) {
            null -> "null"
            is Bundle -> "Bundle(${value.keySet().size})"
            is JSONObject -> "JSONObject(${value.length()})"
            is JSONArray -> "JSONArray(${value.length()})"
            is String -> "String(${value.length})"
            is ByteArray -> "ByteArray(${value.size})"
            is Collection<*> -> "Collection(${value.size})"
            is Array<*> -> "Array(${value.size})"
            else -> value::class.java.simpleName
        }
}
