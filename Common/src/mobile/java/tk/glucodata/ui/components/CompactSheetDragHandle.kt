package tk.glucodata.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CompactSheetDragHandle(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .padding(top = 8.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier.size(width = 36.dp, height = 4.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
        ) {}
    }
}
