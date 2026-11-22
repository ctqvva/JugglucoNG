package tk.glucodata.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The button at the end of a device card's header (pause, resume, hand back): as tall as the
 * vendor badge, as wide as the link pill under it. The 48dp touch target comes from the icon
 * button's own minimum interactive size.
 */
object DeviceHeaderButtonDefaults {
    val Width = 48.dp
    val Height = 40.dp
    val Shape = RoundedCornerShape(16.dp)
}
