package tk.glucodata.data.journal

import org.json.JSONObject

/**
 * Nightscout v3 locks eventType, so changing a dose between a bolus and a basal note
 * creates a new document. The dated part is the follower's identity for the dose.
 * The suffix orders replacements even when a refused delete leaves an old copy visible.
 */
internal data class NightscoutInsulinIdentity(
    val baseId: String,
    val basalNote: Boolean,
    val revision: Long
) {
    companion object {
        private val pattern = Regex("(jng-j-[0-9a-f]+-[0-9a-f]+)(?:-(basal|bolus)(?:-([0-9a-f]+))?)?")

        fun parse(identifier: String?): NightscoutInsulinIdentity? {
            val match = identifier?.let(pattern::matchEntire) ?: return null
            val (baseId, form, version) = match.destructured
            val revision = if (version.isNotEmpty()) {
                version.toLongOrNull(radix = 16)?.takeIf { it in 2 until Long.MAX_VALUE } ?: return null
            } else if (form == "basal") {
                1L
            } else {
                0L
            }
            return NightscoutInsulinIdentity(baseId, form == "basal", revision)
        }

        fun fromTreatment(treatment: JSONObject): NightscoutInsulinIdentity? {
            val id = treatment.optString("identifier").takeUnless { it.isBlank() || it == "null" }
                ?: treatment.optString("_id")
            val identity = parse(id) ?: return null
            if (!treatment.optBoolean("isValid", true)) return identity
            if (treatment.optString("app") != "JugglucoNG" ||
                treatment.optString("type") != JournalEntryType.INSULIN.storageValue
            ) return null
            val event = if (identity.basalNote) "Note" else "Correction Bolus"
            return identity.takeIf { treatment.optString("eventType") == event }
        }

        fun forUpload(baseId: String, previousId: String?, basalNote: Boolean): String {
            val previous = parse(previousId)
            if (previous == null || previous.baseId != baseId) {
                return if (basalNote) "$baseId-basal" else baseId
            }
            if (previous.basalNote == basalNote) return checkNotNull(previousId)
            val revision = maxOf(previous.revision + 1, 2L)
            val form = if (basalNote) "basal" else "bolus"
            return "$baseId-$form-${revision.toString(16)}"
        }
    }
}
