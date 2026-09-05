package tk.glucodata.ui.journal

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntrySource

/**
 * How a journal entry's origin is shown: a small icon on the row, plain words in the detail.
 *
 * A dose read off a pen over NFC and one typed from memory make the same entry with the same
 * insulin and the same units; the origin is what says whether the number came from a device.
 * The icon alone only carries that for NFC, which people know from paying and ticketing, so
 * every source also has a label, and the row's icon uses it as its content description.
 */
internal data class JournalSourcePresentation(
    val icon: ImageVector,
    @StringRes val labelRes: Int,
)

/**
 * Null for [JournalEntrySource.MANUAL]: typing an entry in is the normal case, and marking
 * every row would drown the marks that mean something.
 */
internal fun JournalEntrySource.presentation(): JournalSourcePresentation? = when (this) {
    JournalEntrySource.MANUAL -> null
    JournalEntrySource.PEN -> JournalSourcePresentation(Icons.Default.Nfc, R.string.journal_source_pen)
    JournalEntrySource.NIGHTSCOUT -> JournalSourcePresentation(Icons.Default.CloudDownload, R.string.journal_source_nightscout)
    JournalEntrySource.AAPS -> JournalSourcePresentation(Icons.Default.CloudDownload, R.string.journal_source_aaps)
    JournalEntrySource.API -> JournalSourcePresentation(Icons.Default.CloudDownload, R.string.journal_source_api)
    JournalEntrySource.HEALTH_CONNECT -> JournalSourcePresentation(Icons.Default.HealthAndSafety, R.string.journal_source_health_connect)
    JournalEntrySource.METER -> JournalSourcePresentation(Icons.Default.WaterDrop, R.string.journal_source_meter)
}
