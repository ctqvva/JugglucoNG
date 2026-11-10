package tk.glucodata.data.journal

/**
 * Which journal types the main screen draws. Hiding a type is purely visual: the chart
 * markers and the reading-row chips drop it, while IOB, COB, the prediction and the dose
 * hints keep working from every entry, so a hidden bolus still counts.
 *
 * Stored as the storage values of the hidden types, so a new type shows by default.
 */
object JournalDashboardVisibility {
    const val HIDDEN_TYPES_KEY = "dashboard_journal_hidden_types"

    fun decode(stored: Set<String>?): Set<JournalEntryType> =
        stored.orEmpty().mapNotNullTo(mutableSetOf()) { value ->
            JournalEntryType.entries.firstOrNull { it.storageValue == value }
        }

    fun encode(hidden: Set<JournalEntryType>): Set<String> =
        hidden.mapTo(mutableSetOf()) { it.storageValue }

    fun toggled(hidden: Set<JournalEntryType>, type: JournalEntryType, visible: Boolean): Set<JournalEntryType> =
        if (visible) hidden - type else hidden + type

    fun visible(entries: List<JournalEntry>, hidden: Set<JournalEntryType>): List<JournalEntry> =
        if (hidden.isEmpty()) entries else entries.filterNot { it.type in hidden }
}
