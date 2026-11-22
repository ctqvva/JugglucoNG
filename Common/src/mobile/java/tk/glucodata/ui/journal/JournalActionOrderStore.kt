package tk.glucodata.ui.journal

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tk.glucodata.Applic
import tk.glucodata.data.journal.JournalActionOrder
import tk.glucodata.data.journal.JournalEntryType

/**
 * The add menus' order, shared by the menus on the dashboard and the journal screen and by
 * the Entry types screen that arranges it. Without the app's preferences (a preview, a
 * test), it is the default order and changes last only as long as the process.
 */
object JournalActionOrderStore {
    private val state: MutableStateFlow<List<JournalEntryType>> by lazy {
        MutableStateFlow(JournalActionOrder.decode(prefs()?.getString(JournalActionOrder.KEY, null)))
    }

    val order: StateFlow<List<JournalEntryType>> get() = state.asStateFlow()

    fun set(order: List<JournalEntryType>) {
        prefs()?.edit()?.putString(JournalActionOrder.KEY, JournalActionOrder.encode(order))?.apply()
        state.value = order
    }

    private fun prefs(): SharedPreferences? = runCatching {
        Applic.app?.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
    }.getOrNull()
}
