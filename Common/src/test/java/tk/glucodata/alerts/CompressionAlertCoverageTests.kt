package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionAlertCoverageTests {
    @Test
    fun defaultsCoverTheLowDetectorAndBothEarlyWarningDirections() {
        assertEquals(
            setOf(
                AlertType.LOW,
                AlertType.PRE_LOW,
                AlertType.FALLING_FAST,
                AlertType.PRE_HIGH,
                AlertType.RISING_FAST
            ),
            CompressionAlertCoverage.decode(CompressionAlertCoverage.defaultMask)
        )
        assertFalse(AlertType.VERY_LOW in CompressionAlertCoverage.defaultTypes)
        assertFalse(AlertType.HIGH in CompressionAlertCoverage.defaultTypes)
        assertFalse(AlertType.VERY_HIGH in CompressionAlertCoverage.defaultTypes)
    }

    @Test
    fun everyEligibleSelectionRoundTrips() {
        val all = setOf(
            AlertType.LOW,
            AlertType.VERY_LOW,
            AlertType.PRE_LOW,
            AlertType.FALLING_FAST,
            AlertType.HIGH,
            AlertType.VERY_HIGH,
            AlertType.PRE_HIGH,
            AlertType.RISING_FAST
        )
        assertEquals(all, CompressionAlertCoverage.eligibleTypes.toSet())
        assertEquals(all, CompressionAlertCoverage.decode(CompressionAlertCoverage.encode(all)))
        assertEquals(emptySet<AlertType>(), CompressionAlertCoverage.decode(0))
    }

    @Test
    fun unknownAndNonGlucoseAlarmBitsAreIgnored() {
        val stored = CompressionAlertCoverage.defaultMask or (1 shl AlertType.LOSS.id)
        assertEquals(CompressionAlertCoverage.defaultTypes, CompressionAlertCoverage.decode(stored))
        assertFalse(AlertType.LOSS in CompressionAlertCoverage.eligibleTypes)
    }

    @Test
    fun userCanSelectVeryLowOrClearEveryAlarm() {
        val withVeryLow = CompressionAlertCoverage.updated(
            CompressionAlertCoverage.defaultTypes,
            AlertType.VERY_LOW,
            covered = true
        )
        assertTrue(AlertType.VERY_LOW in withVeryLow)

        val empty = CompressionAlertCoverage.eligibleTypes.fold(withVeryLow) { selected, type ->
            CompressionAlertCoverage.updated(selected, type, covered = false)
        }
        assertTrue(empty.isEmpty())
    }
}
