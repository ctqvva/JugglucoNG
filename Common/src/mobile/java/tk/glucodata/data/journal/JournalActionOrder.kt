package tk.glucodata.data.journal

/**
 * The order of the journal's add menus, top to bottom: the + button's menu and the chart's
 * "add at this time" menu. The last type sits nearest the + button, under the thumb.
 *
 * Stored as the types' storage values, comma-separated. Unknown values are dropped, and a type
 * the stored order lacks (a new one) goes back to its default place, so nothing ever falls out
 * of the menus.
 */
object JournalActionOrder {
    const val KEY = "journal_action_order"

    /** Only the types a person adds by hand: temp basals come from AAPS. */
    val DEFAULT: List<JournalEntryType> = listOf(
        JournalEntryType.NOTE,
        JournalEntryType.ACTIVITY,
        JournalEntryType.FINGERSTICK,
        JournalEntryType.CARBS,
        JournalEntryType.INSULIN
    )

    fun decode(stored: String?): List<JournalEntryType> {
        val order = stored.orEmpty()
            .split(',')
            .mapNotNull { value -> DEFAULT.firstOrNull { it.storageValue == value.trim() } }
            .distinct()
            .toMutableList()
        DEFAULT.forEachIndexed { index, type ->
            if (type !in order) order.add(index.coerceAtMost(order.size), type)
        }
        return order
    }

    /**
     * The entry sheet's type row, left to right: the menus read from the + button up, so the
     * type nearest the button leads. By default Insulin, Food, Glucose, Activity, Note.
     */
    fun sheetOrder(menuOrder: List<JournalEntryType>): List<JournalEntryType> = menuOrder.asReversed()

    fun encode(order: List<JournalEntryType>): String = order.joinToString(",") { it.storageValue }

    /** [order] with the type at [from] taken out and put back at [to]; out-of-range is a no-op. */
    fun moved(order: List<JournalEntryType>, from: Int, to: Int): List<JournalEntryType> {
        if (from !in order.indices || to !in order.indices || from == to) return order
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }
}
