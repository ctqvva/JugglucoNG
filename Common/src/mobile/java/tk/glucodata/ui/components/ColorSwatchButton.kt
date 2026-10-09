package tk.glucodata.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ColorSwatchButton(
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = containerColor
        ),
        shapes = IconButtonDefaults.shapes()
    ) {
        Surface(
            modifier = Modifier.size(22.dp),
            shape = CircleShape,
            color = color
        ) {}
    }
}
