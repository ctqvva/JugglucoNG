package tk.glucodata.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardChartExpansionGestureGateTests {

    @Test
    fun inheritedFlingMomentumNeverExpandsTheChart() {
        val gate = DashboardChartExpansionGestureGate()
        gate.onGestureStarted(startedAtTop = true, chartExpanded = false)
        gate.onDirectScrollDelta(deltaY = 20f)

        assertFalse(gate.allowsExpansion(directUserInput = false))
    }

    @Test
    fun dragStartedBelowTopCannotHandOffIntoChartExpansion() {
        val gate = DashboardChartExpansionGestureGate()
        gate.onGestureStarted(startedAtTop = false, chartExpanded = false)
        gate.onDirectScrollDelta(deltaY = 20f)

        assertFalse(gate.allowsExpansion(directUserInput = true))
    }

    @Test
    fun freshPullDownStartedAtTopCanExpandTheChart() {
        val gate = DashboardChartExpansionGestureGate()
        gate.onGestureStarted(startedAtTop = true, chartExpanded = false)
        gate.onDirectScrollDelta(deltaY = 20f)

        assertTrue(gate.allowsExpansion(directUserInput = true))
    }

    @Test
    fun upwardListScrollCannotReverseIntoExpansionWithoutANewGesture() {
        val gate = DashboardChartExpansionGestureGate()
        gate.onGestureStarted(startedAtTop = true, chartExpanded = false)
        gate.onDirectScrollDelta(deltaY = -20f)
        gate.onDirectScrollDelta(deltaY = 20f)

        assertFalse(gate.allowsExpansion(directUserInput = true))
    }

    @Test
    fun existingChartDragCanCollapseAndExpandWithinOneGesture() {
        val gate = DashboardChartExpansionGestureGate()
        gate.onGestureStarted(startedAtTop = true, chartExpanded = true)
        gate.onDirectScrollDelta(deltaY = -20f)

        assertTrue(gate.allowsExpansion(directUserInput = true))
    }

    @Test
    fun liftingTheFingerRequiresANewExplicitGesture() {
        val gate = DashboardChartExpansionGestureGate()
        gate.onGestureStarted(startedAtTop = true, chartExpanded = false)
        gate.onDirectScrollDelta(deltaY = 20f)
        gate.onGestureEnded()

        assertFalse(gate.allowsExpansion(directUserInput = true))
    }
}
