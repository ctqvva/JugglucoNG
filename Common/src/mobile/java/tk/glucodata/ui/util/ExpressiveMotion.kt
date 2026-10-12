package tk.glucodata.ui.util

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * The Material 3 Expressive motion scheme, as plain springs.
 *
 * Expressive motion splits into two channels, and the split is the point. The *spatial*
 * channel — position, size, scale, corner radius — is under-damped, so movement carries a
 * little momentum past its target. The *effects* channel — alpha, colour — is critically
 * damped, so a fade never wobbles. Running alpha on a bouncy spring is what makes
 * hand-rolled "expressive" motion look cheap, and running position on a stiff linear
 * tween is what makes it look mechanical.
 *
 * Pick by how far the thing travels, not by how important it is: [fastSpatial] for a
 * small element moving a short distance, [defaultSpatial] for most container changes,
 * [slowSpatial] for something crossing a large part of the screen.
 *
 * The values follow `MotionScheme.expressive()`, which the theme installs for the stock
 * components. These copies are for hand-built animations that take a plain spring spec outside
 * composition, where `MaterialTheme.motionScheme` cannot be read.
 */
object ExpressiveMotion {
    fun <T> fastSpatial(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)

    fun <T> defaultSpatial(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

    fun <T> slowSpatial(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)

    fun <T> fastEffects(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 3800f)

    fun <T> defaultEffects(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)

    fun <T> slowEffects(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 800f)
}
