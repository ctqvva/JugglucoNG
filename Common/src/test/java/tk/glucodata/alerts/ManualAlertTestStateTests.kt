package tk.glucodata.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualAlertTestStateTests {

    @Test
    fun testActionIsConsumedWithoutBecomingProductionState() {
        val state = ManualAlertTestState<AlertType>()

        state.arm(AlertType.HIGH)
        assertTrue(state.consumeBypassAndActivate(AlertType.HIGH))
        assertTrue(state.consumeAction(AlertType.HIGH))
        assertFalse(state.consumeAction(AlertType.HIGH))
    }

    @Test
    fun realEvaluationSupersedesUnacknowledgedTestSurface() {
        val state = ManualAlertTestState<AlertType>()

        state.arm(AlertType.HIGH)
        assertTrue(state.consumeBypassAndActivate(AlertType.HIGH))
        state.supersede(AlertType.HIGH)

        assertFalse(state.isActive(AlertType.HIGH))
        assertFalse(state.consumeAction(AlertType.HIGH))
    }

    @Test
    fun clearingProductionEpisodeDoesNotLoseActiveTestAction() {
        val state = ManualAlertTestState<AlertType>()

        state.arm(AlertType.HIGH)
        assertTrue(state.consumeBypassAndActivate(AlertType.HIGH))
        state.clearPending(AlertType.HIGH)

        assertTrue(state.consumeAction(AlertType.HIGH))
    }
}
