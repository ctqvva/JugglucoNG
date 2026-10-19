package tk.glucodata.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun TabIcon(
    isSelected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    description: String,
    isDashboard: Boolean = false,
    isStatistics: Boolean = false
) {
    val dashboardTilt = remember { Animatable(0f) }
    val dashboardLift = remember { Animatable(0f) }
    val dashboardScale = remember { Animatable(1f) }
    val statsTilt = remember { Animatable(0f) }
    val statsLift = remember { Animatable(0f) }
    val statsAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0.96f else 0.84f,
        animationSpec = tween(220),
        label = "StatsIconAlpha"
    )

    LaunchedEffect(isDashboard, isSelected) {
        if (!isDashboard) {
            dashboardTilt.snapTo(0f)
            dashboardLift.snapTo(0f)
            dashboardScale.snapTo(1f)
            return@LaunchedEffect
        }

        if (isSelected) {
            dashboardTilt.snapTo(-22f)
            dashboardLift.snapTo(13f)
            dashboardScale.snapTo(1.12f)
            launch {
                dashboardTilt.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.34f,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                dashboardLift.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                dashboardScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.56f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        } else {
            dashboardTilt.animateTo(0f, animationSpec = tween(120))
            dashboardLift.animateTo(0f, animationSpec = tween(120))
            dashboardScale.animateTo(1f, animationSpec = tween(140))
        }
    }

    LaunchedEffect(isStatistics, isSelected) {
        if (!isStatistics) {
            statsTilt.snapTo(0f)
            statsLift.snapTo(0f)
            return@LaunchedEffect
        }

        if (isSelected) {
            statsTilt.snapTo(-8f)
            statsLift.snapTo(4f)
            launch {
                statsTilt.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                statsLift.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        } else {
            statsTilt.animateTo(0f, animationSpec = tween(130))
            statsLift.animateTo(0f, animationSpec = tween(130))
        }
    }

    AnimatedContent(
        targetState = isSelected,
        transitionSpec = {
            if (targetState) {
                if (isDashboard) {
                    // Dashboard: stronger, playful motion without icon scale expansion.
                    (slideInVertically(
                        initialOffsetY = { it + (it / 3) },
                        animationSpec = spring(
                            dampingRatio = 0.58f,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) + scaleIn(
                        initialScale = 0.82f,
                        animationSpec = tween(230)
                    ) + fadeIn(animationSpec = tween(190)))
                        .togetherWith(
                            slideOutVertically(
                                targetOffsetY = { -it / 2 },
                                animationSpec = tween(160)
                            ) + scaleOut(
                                targetScale = 1.08f,
                                animationSpec = tween(160)
                            ) + fadeOut(animationSpec = tween(130))
                        )
                } else {
                    if (isStatistics) {
                        // Statistics: slightly livelier but still calmer than Dashboard.
                        (slideInVertically(
                            initialOffsetY = { it / 3 },
                            animationSpec = spring(
                                dampingRatio = 0.8f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) + fadeIn(animationSpec = tween(190)))
                            .togetherWith(
                                slideOutVertically(
                                    targetOffsetY = { -it / 6 },
                                    animationSpec = tween(140)
                                ) + fadeOut(animationSpec = tween(130))
                            )
                    } else {
                        // Selected: Filled icon "pops" in (Scale + Fade)
                        (scaleIn(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)))
                            .togetherWith(fadeOut(animationSpec = tween(200)))
                    }
                }
            } else {
                if (isDashboard) {
                    (slideInVertically(
                        initialOffsetY = { -it / 4 },
                        animationSpec = tween(170)
                    ) + fadeIn(animationSpec = tween(180)))
                        .togetherWith(
                            slideOutVertically(
                                targetOffsetY = { it / 3 },
                                animationSpec = tween(160)
                            ) + fadeOut(animationSpec = tween(150))
                        )
                } else {
                    if (isStatistics) {
                        (slideInVertically(
                            initialOffsetY = { -it / 5 },
                            animationSpec = tween(170)
                        ) + fadeIn(animationSpec = tween(180)))
                            .togetherWith(
                                slideOutVertically(
                                    targetOffsetY = { it / 4 },
                                    animationSpec = tween(150)
                                ) + fadeOut(animationSpec = tween(140))
                            )
                    } else {
                        // Deselected: Outline icon fades back in normal
                        fadeIn(animationSpec = tween(200))
                            .togetherWith(scaleOut(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)))
                    }
                }
            }
        },
        label = "TabIconAnimation"
    ) { selected ->
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = description,
            modifier = if (isDashboard) {
                Modifier.graphicsLayer {
                    rotationZ = dashboardTilt.value
                    translationY = -dashboardLift.value
                    scaleX = dashboardScale.value
                    scaleY = dashboardScale.value
                }.size(24.dp)
            } else if (isStatistics) {
                Modifier
                    .graphicsLayer {
                        rotationZ = statsTilt.value
                        translationY = -statsLift.value
                    }
                    .size(24.dp)
                    .alpha(statsAlpha)
            } else {
                Modifier
            }
        )
    }
}
