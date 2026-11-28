package tk.glucodata.ui.util

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import tk.glucodata.ui.withoutEnlargement

/**
 * Sizes a one-line ellipsized label at the first of [sizes] (largest first) that shows it whole,
 * or the last. Unlike [TextAutoSize.StepBased], whose steps from the minimum can miss the maximum,
 * it draws a label that fits at exactly the size it was given.
 */
data class LargestFittingSize(private val sizes: List<TextUnit>) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(
        constraints: Constraints,
        text: AnnotatedString
    ): TextUnit = sizes.firstOrNull { size ->
        val layout = performLayout(constraints, text, size)
        layout.lineCount > 0 && !layout.isLineEllipsized(layout.lineCount - 1)
    } ?: sizes.last()
}

/**
 * The sizes a label may take, largest first: [largest], then half points down through [default]
 * to [smallest], both as they are at the default text size. The steps between are the same at
 * every text size, so a larger one never finds a smaller size for the same room.
 */
fun labelSizes(largest: TextUnit, default: TextUnit, smallest: TextUnit, density: Density): List<TextUnit> {
    val largestPx = with(density) { largest.toPx() }
    fun isBelowLargest(size: TextUnit) = with(density) { size.toPx() } < largestPx
    val above = generateSequence(default.value) { it + 0.5f }
        .map { it.sp.withoutEnlargement(density) }
        .takeWhile(::isBelowLargest)
        .toList()
    val below = (generateSequence(default.value) { it - 0.5f }.takeWhile { it > smallest.value } + smallest.value)
        .map { it.sp.withoutEnlargement(density) }
        .filter(::isBelowLargest)
        .toList()
    return (listOf(largest) + above.asReversed() + below).distinct()
}
