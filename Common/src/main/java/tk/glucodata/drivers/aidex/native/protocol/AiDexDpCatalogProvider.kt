package tk.glucodata.drivers.aidex.native.protocol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import tk.glucodata.Applic
import tk.glucodata.Log
import java.util.Locale

internal object AiDexDpCatalogProvider {

    private const val TAG = "AiDexDpCatalogProvider"
    private const val PREFS_NAME = "AiDexDpCatalogPrefs"
    private const val KEY_IMPORTED_JSON = "importedCatalogJson"
    private const val KEY_IMPORTED_AT_MS = "importedCatalogAtMs"

    data class CatalogState(
        val snapshotEntryCount: Int,
        val importedEntryCount: Int,
        val totalEntryCount: Int,
        val importedUpdatedAtMs: Long,
    ) {
        val hasImportedEntries: Boolean get() = importedEntryCount > 0

        fun summaryLine(): String {
            return "catalog=$totalEntryCount snapshot=$snapshotEntryCount imported=$importedEntryCount importedAt=$importedUpdatedAtMs"
        }
    }

    data class ImportResult(
        val importedEntries: List<AiDexDefaultParamProvisioning.CatalogEntry>,
        val replacedKeys: Int,
        val catalogState: CatalogState,
    ) {
        val importedCount: Int get() = importedEntries.size

        fun summaryLine(): String {
            return "imported=$importedCount replaced=$replacedKeys ${catalogState.summaryLine()}"
        }
    }

    interface CatalogStorage {
        fun loadImportedCatalogJson(): String?
        fun saveImportedCatalogJson(json: String?)
        fun loadImportedAtMs(): Long
        fun saveImportedAtMs(timestampMs: Long)
    }

    class InMemoryStorage(
        var catalogJson: String? = null,
        var importedAtMs: Long = 0L,
    ) : CatalogStorage {
        override fun loadImportedCatalogJson(): String? = catalogJson

        override fun saveImportedCatalogJson(json: String?) {
            catalogJson = json
        }

        override fun loadImportedAtMs(): Long = importedAtMs

        override fun saveImportedAtMs(timestampMs: Long) {
            importedAtMs = timestampMs
        }
    }

    private object AndroidStorage : CatalogStorage {
        private fun prefs() = Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun loadImportedCatalogJson(): String? = prefs().getString(KEY_IMPORTED_JSON, null)

        override fun saveImportedCatalogJson(json: String?) {
            prefs().edit().putString(KEY_IMPORTED_JSON, json).apply()
        }

        override fun loadImportedAtMs(): Long = prefs().getLong(KEY_IMPORTED_AT_MS, 0L)

        override fun saveImportedAtMs(timestampMs: Long) {
            prefs().edit().putLong(KEY_IMPORTED_AT_MS, timestampMs).apply()
        }
    }

    @Volatile
    private var storageOverride: CatalogStorage? = null

    @Volatile
    private var importedEntriesCache: List<AiDexDefaultParamProvisioning.CatalogEntry>? = null

    @Volatile
    private var importedUpdatedAtCache: Long? = null

    private fun activeStorage(): CatalogStorage? {
        storageOverride?.let { return it }
        return if (Applic.app != null) AndroidStorage else null
    }

    internal fun setStorageForTests(storage: CatalogStorage?) {
        storageOverride = storage
        importedEntriesCache = null
        importedUpdatedAtCache = null
    }

    fun snapshotEntries(): List<AiDexDefaultParamProvisioning.CatalogEntry> = AiDexOfficialDpCatalogSnapshot.entries

    fun importedEntries(): List<AiDexDefaultParamProvisioning.CatalogEntry> {
        importedEntriesCache?.let { return it }
        val storage = activeStorage() ?: return emptyList()
        val json = storage.loadImportedCatalogJson().orEmpty()
        if (json.isBlank()) {
            importedEntriesCache = emptyList()
            importedUpdatedAtCache = storage.loadImportedAtMs()
            return emptyList()
        }
        val parsed = try {
            parseEntries(json)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse imported DP catalog: ${t.message}")
            emptyList()
        }
        importedEntriesCache = parsed
        importedUpdatedAtCache = storage.loadImportedAtMs()
        return parsed
    }

    fun entries(): List<AiDexDefaultParamProvisioning.CatalogEntry> {
        val merged = LinkedHashMap<String, AiDexDefaultParamProvisioning.CatalogEntry>()
        snapshotEntries().forEach { entry ->
            merged[catalogKey(entry)] = entry
        }
        importedEntries().forEach { entry ->
            merged[catalogKey(entry)] = entry
        }
        return merged.values.toList()
    }

    fun catalogState(): CatalogState {
        val snapshotCount = snapshotEntries().size
        val imported = importedEntries()
        val total = entries().size
        val importedUpdatedAt = importedUpdatedAtCache ?: activeStorage()?.loadImportedAtMs() ?: 0L
        return CatalogState(
            snapshotEntryCount = snapshotCount,
            importedEntryCount = imported.size,
            totalEntryCount = total,
            importedUpdatedAtMs = importedUpdatedAt,
        )
    }

    fun importCatalogJson(json: String): ImportResult {
        val parsed = parseEntries(json)
        val snapshotKeys = snapshotEntries().mapTo(LinkedHashSet()) { catalogKey(it) }
        val importedKeys = parsed.map { catalogKey(it) }
        val replacedKeys = importedKeys.count { it in snapshotKeys }
        persistImportedEntries(parsed)
        val state = catalogState()
        Log.i(TAG, "Imported AiDex DP catalog: imported=${parsed.size} replaced=$replacedKeys ${state.summaryLine()}")
        return ImportResult(
            importedEntries = parsed,
            replacedKeys = replacedKeys,
            catalogState = state,
        )
    }

    fun clearImportedCatalog(): CatalogState {
        persistImportedEntries(emptyList())
        return catalogState()
    }

    private fun persistImportedEntries(entries: List<AiDexDefaultParamProvisioning.CatalogEntry>) {
        val storage = activeStorage()
        val json = if (entries.isEmpty()) null else serializeEntries(entries)
        val importedAtMs = if (entries.isEmpty()) 0L else System.currentTimeMillis()
        storage?.saveImportedCatalogJson(json)
        storage?.saveImportedAtMs(importedAtMs)
        importedEntriesCache = entries
        importedUpdatedAtCache = importedAtMs
    }

    private fun parseEntries(json: String): List<AiDexDefaultParamProvisioning.CatalogEntry> {
        val trimmed = json.trim()
        require(trimmed.isNotBlank()) { "empty DP catalog JSON" }
        val root = JSONTokener(trimmed).nextValue()
        val collected = LinkedHashMap<String, AiDexDefaultParamProvisioning.CatalogEntry>()
        collectEntries(root, collected)
        require(collected.isNotEmpty()) { "no DP entries found in JSON" }
        return collected.values.toList()
    }

    private fun collectEntries(
        node: Any?,
        out: LinkedHashMap<String, AiDexDefaultParamProvisioning.CatalogEntry>,
    ) {
        when (node) {
            is JSONObject -> {
                val entry = node.toCatalogEntryOrNull()
                if (entry != null) {
                    out[catalogKey(entry)] = entry
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        collectEntries(node.opt(keys.next()), out)
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    collectEntries(node.opt(index), out)
                }
            }
        }
    }

    private fun JSONObject.toCatalogEntryOrNull(): AiDexDefaultParamProvisioning.CatalogEntry? {
        val settingType = optString("settingType").trim()
        val version = optString("version").trim()
        val settingContent = optString("settingContent").trim()
            .replace(Regex("\\s+"), "")
            .uppercase(Locale.US)
        if (settingType.isBlank() || version.isBlank() || settingContent.isBlank()) return null
        if (settingContent.length % 2 != 0 || !settingContent.all(::isHexChar)) return null

        val aidexVersion = optString("aidexVersion", "X").trim().ifBlank { "X" }
        val settingVersion = optString("settingVersion").trim()
        return AiDexDefaultParamProvisioning.CatalogEntry(
            settingType = settingType,
            version = version,
            aidexVersion = aidexVersion,
            settingVersion = settingVersion,
            settingContent = settingContent,
            source = AiDexDefaultParamProvisioning.CatalogSource.IMPORTED,
        )
    }

    private fun serializeEntries(entries: List<AiDexDefaultParamProvisioning.CatalogEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("settingType", entry.settingType)
                    put("version", entry.version)
                    put("aidexVersion", entry.aidexVersion)
                    put("settingVersion", entry.settingVersion)
                    put("settingContent", entry.settingContent)
                }
            )
        }
        return array.toString()
    }

    private fun catalogKey(entry: AiDexDefaultParamProvisioning.CatalogEntry): String {
        return "${entry.settingType}|${entry.aidexVersion}|${entry.version}"
    }

    private fun isHexChar(ch: Char): Boolean {
        return (ch in '0'..'9') || (ch in 'A'..'F') || (ch in 'a'..'f')
    }
}
