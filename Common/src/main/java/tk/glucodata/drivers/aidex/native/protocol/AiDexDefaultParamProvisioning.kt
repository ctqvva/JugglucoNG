// JugglucoNG — AiDex Native Kotlin Driver
// AiDexDefaultParamProvisioning.kt — Compare/apply planning for AiDex default params
//
// Mirrors the grounded official OtaManager default-param pipeline:
//   - normalize the captured 0x31 blob into the packed compare shape
//   - derive the same firmware version key the official app uses
//   - select a candidate from a local catalog provider
//   - validate CRC-8/MAXIM on settingContent
//   - preserve the same no-change slice from the current DP
//   - compare without writing unless an explicit guarded apply is requested

package tk.glucodata.drivers.aidex.native.protocol

import tk.glucodata.drivers.aidex.native.crypto.Crc8Maxim
import java.util.Locale

object AiDexDefaultParamProvisioning {

    enum class CatalogSource {
        SNAPSHOT,
        IMPORTED;

        val label: String
            get() = when (this) {
                SNAPSHOT -> "snapshot"
                IMPORTED -> "imported"
            }
    }

    data class CatalogEntry(
        val settingType: String,
        val version: String,
        val aidexVersion: String,
        val settingVersion: String,
        val settingContent: String,
        val source: CatalogSource = CatalogSource.SNAPSHOT,
    )

    data class CatalogSummary(
        val snapshotEntryCount: Int,
        val importedEntryCount: Int,
        val totalEntryCount: Int,
        val importedUpdatedAtMs: Long,
    ) {
        val hasImportedEntries: Boolean get() = importedEntryCount > 0
    }

    data class CurrentVariant(
        val hex: String,
        val headerSwapApplied: Boolean,
    ) {
        val byteCount: Int get() = hex.length / 2
        val versionHex: String get() = hex.take(8)
    }

    data class Candidate(
        val entry: CatalogEntry,
        val candidateHex: String,
        val candidateBaseHex: String,
        val crcValid: Boolean,
        val localCrcHex: String,
        val onlineCrcHex: String,
    ) {
        val byteCount: Int get() = candidateHex.length / 2
        val versionHex: String get() = candidateHex.take(8)
    }

    data class Comparison(
        val current: CurrentVariant,
        val candidate: Candidate,
        val diffByteCount: Int,
        val exactMatch: Boolean,
        val diffSample: List<ByteDiff>,
    ) {
        val entry: CatalogEntry get() = candidate.entry

        fun diffSummary(): String {
            if (diffSample.isEmpty()) return "none"
            return diffSample.joinToString(separator = ",") { diff ->
                val index = diff.byteIndex
                val currentHex = diff.currentHex ?: "--"
                val candidateHex = diff.candidateHex ?: "--"
                "b$index:$currentHex->$candidateHex"
            }
        }
    }

    data class ByteDiff(
        val byteIndex: Int,
        val currentHex: String?,
        val candidateHex: String?,
    )

    data class Diagnostics(
        val modelName: String?,
        val firmwareVersion: String?,
        val settingType: String?,
        val versionKey: String?,
        val catalog: CatalogSummary,
        val bestComparison: Comparison?,
        val comparisonCount: Int,
    ) {
        val exactMatch: Boolean get() = bestComparison?.exactMatch == true

        fun summaryLine(): String {
            val best = bestComparison ?: return (
                "model=${modelName.orEmpty().ifBlank { "?" }} fw=${firmwareVersion.orEmpty().ifBlank { "?" }} " +
                    "versionKey=${versionKey ?: "?"} catalog=${catalog.totalEntryCount} " +
                    "(snapshot=${catalog.snapshotEntryCount} imported=${catalog.importedEntryCount}) no-match"
            )
            return buildString {
                append("model=${modelName.orEmpty().ifBlank { "?" }} ")
                append("fw=${firmwareVersion.orEmpty().ifBlank { "?" }} ")
                append("versionKey=${versionKey ?: "?"} ")
                append("catalog=${catalog.totalEntryCount} ")
                append("(snapshot=${catalog.snapshotEntryCount} imported=${catalog.importedEntryCount}) ")
                append("${best.entry.source.label}:${best.entry.settingType}@${best.entry.version}/${best.entry.settingVersion} ")
                append("diff=${best.diffByteCount} exact=${best.exactMatch}")
                if (!best.exactMatch) {
                    append(" sample=${best.diffSummary()}")
                }
            }
        }
    }

    data class ApplyChunk(
        val totalWords: Int,
        val startIndex: Int,
        val payload: ByteArray,
    ) {
        val payloadByteCount: Int get() = payload.size
    }

    data class ApplyPlan(
        val diagnostics: Diagnostics,
        val comparison: Comparison,
        val chunks: List<ApplyChunk>,
    ) {
        val totalWords: Int get() = chunks.firstOrNull()?.totalWords ?: 0
        val entry: CatalogEntry get() = comparison.entry

        fun summaryLine(): String {
            return buildString {
                append("${entry.source.label}:${entry.settingType}@${entry.version}/${entry.settingVersion} ")
                append("diff=${comparison.diffByteCount} ")
                if (!comparison.exactMatch) {
                    append("sample=${comparison.diffSummary()} ")
                }
                append("chunks=${chunks.size} totalWords=$totalWords")
            }
        }
    }

    fun catalogSummary(): CatalogSummary {
        val state = AiDexDpCatalogProvider.catalogState()
        return CatalogSummary(
            snapshotEntryCount = state.snapshotEntryCount,
            importedEntryCount = state.importedEntryCount,
            totalEntryCount = state.totalEntryCount,
            importedUpdatedAtMs = state.importedUpdatedAtMs,
        )
    }

    fun normalizeCatalogModelName(modelName: String?): String? {
        if (modelName.isNullOrBlank()) return null
        val normalized = modelName.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")
        return when {
            normalized.contains("GX01S") -> "1034_GX01S"
            normalized.contains("GX02S") -> "1034_GX02S"
            normalized.contains("GX03S") -> "1034_GX03S"
            normalized.contains("GXXXS14") -> "1034_GXXXS_14"
            normalized.contains("GXXXS16") -> "1034_GXXXS_16"
            normalized.contains("GXXXS7") -> "1034_GXXXS_7"
            normalized.startsWith("1034GX01S") -> "1034_GX01S"
            normalized.startsWith("1034GX02S") -> "1034_GX02S"
            normalized.startsWith("1034GX03S") -> "1034_GX03S"
            normalized.startsWith("1034GXXXS14") -> "1034_GXXXS_14"
            normalized.startsWith("1034GXXXS16") -> "1034_GXXXS_16"
            normalized.startsWith("1034GXXXS7") -> "1034_GXXXS_7"
            else -> null
        }
    }

    fun deriveCatalogVersionKey(firmwareVersion: String?): String? {
        if (firmwareVersion.isNullOrBlank()) return null
        val token = firmwareVersion.trim().substringBefore(' ').substringBefore('(')
        val parts = token.split('.').filter { it.isNotBlank() }
        val numeric = parts.isNotEmpty() && parts.all { part -> part.all(Char::isDigit) }
        return when {
            token.isBlank() -> null
            numeric && parts.size >= 4 -> parts.dropLast(1).joinToString(".")
            else -> token
        }
    }

    fun compareKnownCatalog(currentRawHex: String, modelName: String?, firmwareVersion: String? = null): List<Comparison> {
        val settingType = normalizeCatalogModelName(modelName) ?: return emptyList()
        val variants = normalizeCurrentVariants(currentRawHex)
        if (variants.isEmpty()) return emptyList()

        return compareKnownCatalog(
            currentVariants = variants,
            settingType = settingType,
            firmwareVersion = firmwareVersion,
            catalogEntries = AiDexDpCatalogProvider.entries(),
        )
    }

    fun diagnoseCurrentDefaultParam(
        currentRawHex: String,
        modelName: String?,
        firmwareVersion: String? = null,
    ): Diagnostics {
        val catalogSummary = catalogSummary()
        val settingType = normalizeCatalogModelName(modelName)
        val versionKey = deriveCatalogVersionKey(firmwareVersion)
        val comparisons = if (settingType != null) {
            compareKnownCatalog(currentRawHex, modelName, firmwareVersion)
        } else {
            emptyList()
        }
        return Diagnostics(
            modelName = modelName,
            firmwareVersion = firmwareVersion,
            settingType = settingType,
            versionKey = versionKey,
            catalog = catalogSummary,
            bestComparison = comparisons.firstOrNull(),
            comparisonCount = comparisons.size,
        )
    }

    fun planGuardedApply(
        currentRawHex: String,
        modelName: String?,
        firmwareVersion: String? = null,
        maxChunkPayloadBytes: Int,
    ): ApplyPlan? {
        val diagnostics = diagnoseCurrentDefaultParam(
            currentRawHex = currentRawHex,
            modelName = modelName,
            firmwareVersion = firmwareVersion,
        )
        val comparison = diagnostics.bestComparison ?: return null
        if (comparison.exactMatch || !comparison.candidate.crcValid) return null
        if (comparison.current.hex.length != comparison.candidate.candidateHex.length) return null

        val candidateBytes = hexToBytes(comparison.candidate.candidateHex) ?: return null
        if (candidateBytes.isEmpty() || candidateBytes.size % 2 != 0) return null

        val chunkPayloadBytes = normalizedChunkPayloadBytes(maxChunkPayloadBytes)
        if (chunkPayloadBytes <= 0) return null

        val totalWords = candidateBytes.size / 2
        val chunks = ArrayList<ApplyChunk>()
        var offset = 0
        var startIndex = 1
        while (offset < candidateBytes.size) {
            val remaining = candidateBytes.size - offset
            val payloadSize = minOf(chunkPayloadBytes, remaining)
            if (payloadSize <= 0 || payloadSize % 2 != 0) return null
            val payload = candidateBytes.copyOfRange(offset, offset + payloadSize)
            chunks += ApplyChunk(
                totalWords = totalWords,
                startIndex = startIndex,
                payload = payload,
            )
            offset += payloadSize
            startIndex += payloadSize / 2
        }
        if (chunks.isEmpty() || offset != candidateBytes.size) return null

        return ApplyPlan(
            diagnostics = diagnostics,
            comparison = comparison,
            chunks = chunks,
        )
    }

    fun normalizeCurrentVariants(currentRawHex: String): List<CurrentVariant> {
        val upper = currentRawHex.trim().uppercase(Locale.US)
        if (upper.length < 12 || upper.length % 2 != 0 || !upper.all(::isHexChar)) return emptyList()

        val withoutLead = upper.drop(2)
        if (withoutLead.length < 8) return emptyList()

        // Official GET_DEFAULT_PARAM handling compares BleMessage.data as-is
        // (binaryToHex on the packed payload). The earlier "swap header" variant
        // was our local guess and no longer survives the grounded official traces.
        return listOf(CurrentVariant(hex = withoutLead, headerSwapApplied = false))
    }

    private fun compareKnownCatalog(
        currentVariants: List<CurrentVariant>,
        settingType: String,
        firmwareVersion: String?,
        catalogEntries: List<CatalogEntry>,
    ): List<Comparison> {
        return candidateCatalogEntries(catalogEntries, settingType, firmwareVersion).asSequence()
            .mapNotNull { entry ->
                currentVariants.mapNotNull { variant ->
                    val candidate = buildCandidate(variant.hex, entry) ?: return@mapNotNull null
                    val diffBytes = diffByteCount(variant.hex, candidate.candidateHex)
                    val diffSample = diffSample(variant.hex, candidate.candidateHex)
                    Comparison(
                        current = variant,
                        candidate = candidate,
                        diffByteCount = diffBytes,
                        exactMatch = diffBytes == 0 && variant.hex.length == candidate.candidateHex.length,
                        diffSample = diffSample,
                    )
                }.minWithOrNull(
                    compareBy<Comparison> { it.diffByteCount }
                        .thenBy { if (it.current.headerSwapApplied) 1 else 0 }
                )
            }
            .sortedWith(
                compareBy<Comparison> { it.diffByteCount }
                    .thenBy { if (it.current.headerSwapApplied) 1 else 0 }
                    .thenBy { if (it.entry.source == CatalogSource.IMPORTED) 0 else 1 }
                    .thenBy { it.entry.version }
            )
            .toList()
    }

    private fun buildCandidate(oldDpHex: String, entry: CatalogEntry): Candidate? {
        val settingContent = entry.settingContent.trim().uppercase(Locale.US)
        if (settingContent.length < 10 || settingContent.length % 2 != 0 || !settingContent.all(::isHexChar)) {
            return null
        }

        val crcPayloadHex = settingContent.dropLast(2)
        val onlineCrcHex = settingContent.takeLast(2)
        val payloadBytes = hexToBytes(crcPayloadHex) ?: return null
        val localCrcHex = "%02X".format(Crc8Maxim.checksum(payloadBytes))
        val candidateBaseHex = settingContent.dropLast(8)
        val crcValid = localCrcHex.equals(onlineCrcHex, ignoreCase = true)
        if (!crcValid) {
            return Candidate(
                entry = entry,
                candidateHex = candidateBaseHex,
                candidateBaseHex = candidateBaseHex,
                crcValid = false,
                localCrcHex = localCrcHex,
                onlineCrcHex = onlineCrcHex,
            )
        }

        var candidateHex = candidateBaseHex
        if (oldDpHex.length == candidateBaseHex.length && candidateBaseHex.length >= 24) {
            val preserveRange = if (oldDpHex.length == 0xA8) 8..15 else 16..23
            val preserved = oldDpHex.substring(preserveRange.first, preserveRange.last + 1)
            candidateHex = candidateBaseHex.replaceRange(preserveRange.first, preserveRange.last + 1, preserved)
        }

        return Candidate(
            entry = entry,
            candidateHex = candidateHex,
            candidateBaseHex = candidateBaseHex,
            crcValid = true,
            localCrcHex = localCrcHex,
            onlineCrcHex = onlineCrcHex,
        )
    }

    private fun candidateCatalogEntries(
        catalogEntries: List<CatalogEntry>,
        settingType: String,
        firmwareVersion: String?,
    ): List<CatalogEntry> {
        val matches = catalogEntries.filter { it.settingType == settingType && it.aidexVersion == "X" }
        val versionKey = deriveCatalogVersionKey(firmwareVersion) ?: return matches.sortedBy { it.version }
        val exact = matches.filter { it.version == versionKey }
        val remainder = matches.filterNot { it.version == versionKey }
            .sortedWith(
                compareBy<CatalogEntry> { versionPriority(it.version, versionKey) }
                    .thenByDescending { majorMinorMatches(it.version, versionKey) }
                    .thenBy { it.version }
            )
        return exact + remainder
    }

    private fun versionPriority(entryVersion: String, firmwareVersion: String): Int {
        return when {
            entryVersion == firmwareVersion -> 0
            majorMinorMatches(entryVersion, firmwareVersion) -> 1
            else -> 2
        }
    }

    private fun majorMinorMatches(entryVersion: String, firmwareVersion: String): Boolean {
        val left = entryVersion.split('.')
        val right = firmwareVersion.split('.')
        return left.size >= 2 && right.size >= 2 && left[0] == right[0] && left[1] == right[1]
    }

    private fun normalizedChunkPayloadBytes(maxChunkPayloadBytes: Int): Int {
        val capped = maxChunkPayloadBytes.coerceAtLeast(2)
        return if (capped % 2 == 0) capped else capped - 1
    }

    private fun diffByteCount(a: String, b: String): Int {
        val byteCount = minOf(a.length, b.length) / 2
        var diffs = 0
        for (index in 0 until byteCount) {
            val off = index * 2
            if (!a.regionMatches(off, b, off, 2, ignoreCase = true)) {
                diffs++
            }
        }
        diffs += kotlin.math.abs((a.length / 2) - (b.length / 2))
        return diffs
    }

    private fun diffSample(a: String, b: String, maxEntries: Int = 4): List<ByteDiff> {
        val result = ArrayList<ByteDiff>(maxEntries)
        val sharedByteCount = minOf(a.length, b.length) / 2
        for (index in 0 until sharedByteCount) {
            if (result.size >= maxEntries) break
            val off = index * 2
            if (!a.regionMatches(off, b, off, 2, ignoreCase = true)) {
                result += ByteDiff(
                    byteIndex = index,
                    currentHex = a.substring(off, off + 2),
                    candidateHex = b.substring(off, off + 2),
                )
            }
        }
        if (result.size < maxEntries) {
            val aBytes = a.length / 2
            val bBytes = b.length / 2
            val longer = maxOf(aBytes, bBytes)
            for (index in sharedByteCount until longer) {
                if (result.size >= maxEntries) break
                val aOff = index * 2
                val bOff = index * 2
                result += ByteDiff(
                    byteIndex = index,
                    currentHex = if (aOff + 2 <= a.length) a.substring(aOff, aOff + 2) else null,
                    candidateHex = if (bOff + 2 <= b.length) b.substring(bOff, bOff + 2) else null,
                )
            }
        }
        return result
    }

    internal fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || !hex.all(::isHexChar)) return null
        return ByteArray(hex.length / 2) { index ->
            val off = index * 2
            ((hexDigit(hex[off]) shl 4) or hexDigit(hex[off + 1])).toByte()
        }
    }

    private fun isHexChar(ch: Char): Boolean {
        return (ch in '0'..'9') || (ch in 'A'..'F') || (ch in 'a'..'f')
    }

    private fun hexDigit(ch: Char): Int {
        return when (ch) {
            in '0'..'9' -> ch - '0'
            in 'A'..'F' -> ch - 'A' + 10
            in 'a'..'f' -> ch - 'a' + 10
            else -> 0
        }
    }
}
