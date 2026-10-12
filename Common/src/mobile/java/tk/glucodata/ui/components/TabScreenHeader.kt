package tk.glucodata.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tk.glucodata.ui.util.rememberAdaptiveWindowMetrics

/**
 * Geometry shared by the top-level tabs (Statistics, Sensors, Settings). They have no top
 * app bar — the title scrolls with the page — so this object is what keeps the three pages
 * starting and ending in the same place.
 */
object TabScreenDefaults {
    /** Screen gutter; cards and section labels start here. */
    val Gutter: Dp = 16.dp

    /** Clearance under the last item, so it can scroll clear of the screen edge. */
    val BottomPadding: Dp = 32.dp

    /** Bottom clearance on a tab with a FAB: the 56dp FAB, its 16dp margin, then [BottomPadding]. */
    val BottomPaddingWithFab: Dp = 104.dp

    fun contentPadding(hasFab: Boolean = false) = PaddingValues(
        start = Gutter,
        end = Gutter,
        top = Gutter,
        bottom = if (hasFab) BottomPaddingWithFab else BottomPadding,
    )
}

/**
 * The large title at the top of a tab, with optional trailing actions (Statistics' share).
 *
 * The title sits [TabScreenDefaults.Gutter] inside the gutter — on the text inset of a card's
 * content rather than on the card's edge, which is where all three tabs have always put it.
 * Compact windows step down from `displaySmall` to `headlineMedium` so the title does not eat
 * a short screen.
 */
@Composable
fun TabScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val compact = rememberAdaptiveWindowMetrics().isCompact
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = TabScreenDefaults.Gutter, bottom = if (compact) 16.dp else 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = if (compact) {
                MaterialTheme.typography.headlineMedium
            } else {
                MaterialTheme.typography.displaySmall
            },
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        actions()
    }
}
