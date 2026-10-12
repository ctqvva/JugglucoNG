package tk.glucodata.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The leading icon of a settings row or card: a 40dp tile with 12dp corners, washed with 12%
 * of its tint, holding a 24dp glyph.
 *
 * Screens used to build this by hand and ended up with three tiles: this one, a 44dp one on
 * the readiness, journal and calibration screens, and a 40dp circle on the alert screens.
 * Next to each other they read as different kinds of thing. [IconTileDefaults.TextInset] is
 * the reason the size is fixed: 16dp of row padding + 40 + 12 puts every row's text on the
 * same 68dp line.
 */
@Composable
fun IconTile(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    containerColor: Color = tint.copy(alpha = IconTileDefaults.ContainerAlpha),
    contentDescription: String? = null,
) {
    Surface(
        modifier = modifier.size(IconTileDefaults.Size),
        shape = IconTileDefaults.Shape,
        color = containerColor,
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(IconTileDefaults.GlyphSize),
            )
        }
    }
}

object IconTileDefaults {
    val Size = 40.dp
    val Shape = RoundedCornerShape(12.dp)
    val GlyphSize = 24.dp
    const val ContainerAlpha = 0.12f

    /** Gap between the tile and the text beside it. */
    val Gap = 12.dp

    /** Where a row's text starts, measured from the card edge: 16 + [Size] + [Gap]. */
    val TextInset = 68.dp

    /**
     * Tile wash for a row that can be off: the tint's wash while on, a neutral container while
     * off. Pair it with an `onSurfaceVariant` glyph when off, so the whole tile goes quiet.
     */
    @Composable
    fun toggleContainerColor(tint: Color, active: Boolean): Color =
        if (active) tint.copy(alpha = ContainerAlpha) else MaterialTheme.colorScheme.surfaceContainerHighest
}
