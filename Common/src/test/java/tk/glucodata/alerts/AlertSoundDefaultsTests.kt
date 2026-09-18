package tk.glucodata.alerts

import org.junit.Assert.*
import org.junit.Test

class AlertSoundDefaultsTests {
    @Test fun defaultsCoverEveryAlertAndUnknownIdsWithoutArrayIndexing() {
        val cues = listOf("low", "high", "notice", "reminder", "signal", "urgent_low",
            "urgent_high", "falling", "rising", "signal", "high", "reminder", "falling", "rising", "notice")
        assertEquals(AlertType.entries.size, cues.size)
        cues.forEachIndexed { id, cue ->
            val expected = "android.resource://example.app/raw/alert_ember_$cue"
            assertEquals(expected, AlertSoundDefaults.uri("example.app", id))
            assertEquals(expected, AlertSoundDefaults.resolve(null, "example.app", id))
            assertEquals(expected, AlertSoundDefaults.resolve("", "example.app", id))
        }
        listOf(-1, 15, Int.MAX_VALUE).forEach {
            assertEquals("notice", AlertSoundDefaults.cueFor(it))
            assertFalse(AlertSoundDefaults.hasNativeSlot(it))
        }
    }

    @Test fun explicitSelectionsAreNeverMigrated() {
        listOf("SYSTEM_DEFAULT", "content://media/external/audio/media/42",
            "android.resource://example.app/raw/siren",
            "android.resource://example.app/raw/alert_juggluco_low",
            "android.resource://example.app/raw/alert_halo_low").forEach {
            assertEquals(it, AlertSoundDefaults.resolve(it, "example.app", 0))
        }
    }

    @Test fun preferenceOnlyAlertsCannotReadBeyondNativeSoundStorage() {
        (0..9).forEach { assertTrue(AlertSoundDefaults.hasNativeSlot(it)) }
        (10..14).forEach { assertFalse(AlertSoundDefaults.hasNativeSlot(it)) }
    }
}
