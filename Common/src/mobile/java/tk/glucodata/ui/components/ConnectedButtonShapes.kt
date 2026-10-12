package tk.glucodata.ui.components

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Shapes for two or more buttons that sit edge to edge as one control (Reconnect | Disconnect,
 * Reset | Undo, a split button's halves): full pill ends on the outside, small corners where
 * the halves meet. This is the M3 Expressive connected-button silhouette, and it keeps the
 * pair's outer ends the same shape as every standalone button on the screen. Pressed, the
 * outer ends squeeze to 8dp as a standalone button's do; pass both through
 * `ButtonDefaults.shapes(shape, pressedShape)`.
 */
object ConnectedButtonShapes {
    private val Outer = CornerSize(50)
    private val Inner = CornerSize(4.dp)
    private val OuterPressed = CornerSize(8.dp)

    val Leading = RoundedCornerShape(topStart = Outer, bottomStart = Outer, topEnd = Inner, bottomEnd = Inner)
    val Middle = RoundedCornerShape(Inner)
    val Trailing = RoundedCornerShape(topStart = Inner, bottomStart = Inner, topEnd = Outer, bottomEnd = Outer)

    val LeadingPressed = RoundedCornerShape(topStart = OuterPressed, bottomStart = OuterPressed, topEnd = Inner, bottomEnd = Inner)
    val TrailingPressed = RoundedCornerShape(topStart = Inner, bottomStart = Inner, topEnd = OuterPressed, bottomEnd = OuterPressed)
}
