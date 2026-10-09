package tk.glucodata.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun morphingNavigationItemColors() = ShortNavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColorTopIconPosition = MaterialTheme.colorScheme.onSurface,
    selectedTextColorStartIconPosition = MaterialTheme.colorScheme.onSurface,
    selectedIndicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
internal fun MorphingNavigationContainer(
    radii: NavigationCornerRadii,
    content: @Composable () -> Unit
) {
    val animationSpec = trendCornerAnimationSpec()
    val topStart by animateDpAsState(radii.topStart.dp, animationSpec, label = "NavigationTopStart")
    val topEnd by animateDpAsState(radii.topEnd.dp, animationSpec, label = "NavigationTopEnd")
    val bottomEnd by animateDpAsState(radii.bottomEnd.dp, animationSpec, label = "NavigationBottomEnd")
    val bottomStart by animateDpAsState(radii.bottomStart.dp, animationSpec, label = "NavigationBottomStart")

    Box(
        Modifier
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 8.dp)
            .padding(top = 8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            // The gesture/button inset belongs inside this panel, as in the reference.
            // Item bounds stay fixed while only the outline changes.
            ShortNavigationBar(
                modifier = Modifier.padding(top = 8.dp),
                containerColor = Color.Transparent,
                arrangement = ShortNavigationBarArrangement.EqualWeight,
                content = content
            )
        }
    }
}
