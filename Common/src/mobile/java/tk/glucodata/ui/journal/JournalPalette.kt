package tk.glucodata.ui.journal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import tk.glucodata.data.journal.JournalEntryType

fun journalTypeColor(type: JournalEntryType): Color = when (type) {
    JournalEntryType.INSULIN -> Color(0xFF4F78A8)
    JournalEntryType.CARBS -> Color(0xFF5F8B5D)
    JournalEntryType.FINGERSTICK -> Color(0xFF9D5A54)
    JournalEntryType.ACTIVITY -> Color(0xFFA66D39)
    JournalEntryType.NOTE -> Color(0xFF79639A)
}

fun journalTypeSelectedContainerColor(type: JournalEntryType, surfaceColor: Color): Color {
    return lerp(surfaceColor, journalTypeColor(type), 0.24f)
}

fun journalTypeSubtleContainerColor(type: JournalEntryType, surfaceColor: Color): Color {
    return lerp(surfaceColor, journalTypeColor(type), 0.08f)
}

@Composable
fun journalTypeSelectedContainerColor(type: JournalEntryType): Color {
    return journalTypeSelectedContainerColor(type, MaterialTheme.colorScheme.surfaceContainerHigh)
}

@Composable
fun journalTypeSubtleContainerColor(type: JournalEntryType): Color {
    return journalTypeSubtleContainerColor(type, MaterialTheme.colorScheme.surfaceContainerHigh)
}
