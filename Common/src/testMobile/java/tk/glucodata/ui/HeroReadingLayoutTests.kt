package tk.glucodata.ui

import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test

class HeroReadingLayoutTests {
    private class Scope(override val layoutDirection: LayoutDirection = LayoutDirection.Ltr) : MeasureScope {
        override val density = 1f
        override val fontScale = 1f
    }

    private class Item(val naturalWidth: Int, val naturalHeight: Int, val baseline: Int) : Measurable {
        override val parentData: Any? = null
        var measuredWith: Constraints? = null
        var position = IntOffset.Zero
        var measuredWidth = 0
        override fun minIntrinsicWidth(height: Int) = naturalWidth
        override fun maxIntrinsicWidth(height: Int) = naturalWidth
        override fun minIntrinsicHeight(width: Int) = naturalHeight
        override fun maxIntrinsicHeight(width: Int) = naturalHeight
        override fun measure(constraints: Constraints): Placeable {
            measuredWith = constraints
            measuredWidth = naturalWidth.coerceAtMost(constraints.maxWidth)
            return object : Placeable() {
                init { measuredSize = IntSize(this@Item.measuredWidth, naturalHeight) }
                override fun get(alignmentLine: AlignmentLine) = if (alignmentLine == FirstBaseline) baseline else AlignmentLine.Unspecified
                override fun placeAt(position: IntOffset, zIndex: Float, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
                    this@Item.position = position
                }
            }
        }
    }

    private fun items(scale: Int = 1) = listOf(
        Item(100 * scale, 60 * scale, 45 * scale),
        Item(8 * scale, 30 * scale, 22 * scale),
        Item(40 * scale, 28 * scale, 20 * scale),
        Item(24 * scale, 16 * scale, 12 * scale)
    )

    @Test fun optionalDeltaCannotChangeMeasuredOrIntrinsicHeight() {
        for (scale in listOf(1, 2, 3)) for (showDelta in listOf(false, true)) {
            val children = items(scale).take(if (showDelta) 4 else 3)
            val policy = HeroPairMeasurePolicy(showDelta)
            with(policy) {
                val scope = Scope()
                assertEquals(60 * scale, scope.minIntrinsicHeight(children, 200 * scale))
                assertEquals(60 * scale, scope.maxIntrinsicHeight(children, 200 * scale))
                assertEquals(60 * scale, scope.measure(children, Constraints(maxWidth = 200 * scale)).height)
            }
        }
    }

    @Test fun deltaSitsBelowDotAndSecondaryAtTheSingleValueGap() {
        for (direction in LayoutDirection.entries) {
            val children = items()
            val result = with(HeroPairMeasurePolicy(true)) { Scope(direction).measure(children, Constraints(maxWidth = 200)) }
            result.placeChildren()
            val (primary, dot, second, delta) = children
            assertEquals(dot.position.y + dot.baseline, second.position.y + second.baseline)
            assertTrue(delta.position.y >= dot.position.y + dot.naturalHeight)
            assertTrue(delta.position.y >= second.position.y + second.naturalHeight)
            assertTrue(delta.position.y + delta.naturalHeight <= primary.naturalHeight)
            val distance = if (direction == LayoutDirection.Ltr) delta.position.x - primary.naturalWidth
                else primary.position.x - (delta.position.x + delta.measuredWidth)
            assertEquals(16, distance)
        }
    }

    @Test fun enablingDeltaDoesNotMoveTheDotOrSecondarySideways() {
        for (direction in LayoutDirection.entries) {
            val withoutDelta = items().take(3)
            val withDelta = items()
            val withoutResult = with(HeroPairMeasurePolicy(false)) { Scope(direction).measure(withoutDelta, Constraints(maxWidth = 200)) }
            val withResult = with(HeroPairMeasurePolicy(true)) { Scope(direction).measure(withDelta, Constraints(maxWidth = 200)) }
            withoutResult.placeChildren()
            withResult.placeChildren()
            assertEquals(withoutResult.width, withResult.width)
            assertEquals(withoutDelta[1].position.x, withDelta[1].position.x)
            assertEquals(withoutDelta[2].position.x, withDelta[2].position.x)
            for (children in listOf(withoutDelta, withDelta)) {
                val (primary, dot, secondary) = children
                val mainToDot = if (direction == LayoutDirection.Ltr) dot.position.x - primary.measuredWidth
                    else primary.position.x - (dot.position.x + dot.measuredWidth)
                val dotToSecondary = if (direction == LayoutDirection.Ltr) secondary.position.x - (dot.position.x + dot.measuredWidth)
                    else dot.position.x - (secondary.position.x + secondary.measuredWidth)
                assertEquals(8, mainToDot)
                assertEquals(4, dotToSecondary)
            }
        }
    }

    @Test fun supportingTextNeverTakesWidthAwayFromTheMainReading() {
        val children = items()
        with(HeroPairMeasurePolicy(true)) { Scope().measure(children, Constraints(maxWidth = 120)) }
        assertEquals(120, children[0].measuredWith!!.maxWidth)
        assertEquals(100, children[0].measuredWidth)
    }
}
