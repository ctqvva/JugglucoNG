package tk.glucodata.ui.alerts

import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertType

class BundledAlertSoundsTests {
    private val packageName = "tk.glucodata"

    @Test
    fun everyAlertRetainsItsMeaningInEveryCollection() {
        val expected = mapOf(
            AlertType.LOW to "low", AlertType.HIGH to "high",
            AlertType.AVAILABLE to "notice", AlertType.AMOUNT to "reminder",
            AlertType.LOSS to "signal", AlertType.VERY_LOW to "urgent_low",
            AlertType.VERY_HIGH to "urgent_high", AlertType.PRE_LOW to "falling",
            AlertType.PRE_HIGH to "rising", AlertType.MISSED_READING to "signal",
            AlertType.PERSISTENT_HIGH to "high", AlertType.SENSOR_EXPIRY to "reminder",
            AlertType.FALLING_FAST to "falling", AlertType.RISING_FAST to "rising"
        )
        assertEquals(AlertType.entries.toSet(), expected.keys)
        BundledAlertSounds.styles.forEach { style ->
            val selected = BundledAlertSounds.uri(packageName, style, AlertType.LOW.id)
            expected.forEach { (type, cue) ->
                val uri = BundledAlertSounds.forAlert(selected, packageName, type.id)!!
                assertEquals("android.resource://$packageName/raw/alert_${style.lowercase(Locale.ROOT)}_$cue", uri)
                assertEquals(style, BundledAlertSounds.styleFor(uri, packageName))
            }
        }
    }

    @Test
    fun externalAndDefaultSoundsPassThroughUnchanged() {
        listOf(null, "", "SYSTEM_DEFAULT", "content://media/external/audio/media/42",
            "android.resource://other.app/raw/alert_halo_low",
            "android.resource://tk.glucodata/raw/alert_halo_fake",
            "android.resource://tk.glucodata/raw/alert_halo_low?query=1",
            "android.resource://tk.glucodata/2131820544").forEach { uri ->
            assertNull(BundledAlertSounds.styleFor(uri, packageName))
            assertEquals(uri, BundledAlertSounds.forAlert(uri, packageName, AlertType.HIGH.id))
        }
    }

    @Test
    fun resourceNamesAreIndependentOfDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val uri = BundledAlertSounds.uri(packageName, "Porcelain", AlertType.RISING_FAST.id)
            assertEquals("android.resource://tk.glucodata/raw/alert_porcelain_rising", uri)
            assertEquals("Porcelain", BundledAlertSounds.styleFor(uri, packageName))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun globalApplyRecognizesMatchingFamilyWithDifferentCues() {
        val draft = AlertConfig(AlertType.LOW, customSoundUri = BundledAlertSounds.uri(packageName, "Halo", 0))
        val targets = AlertType.entries.associateWith { type ->
            draft.copy(type = type, customSoundUri = BundledAlertSounds.forAlert(draft.customSoundUri, packageName, type.id))
        }
        assertFalse(shouldEnableApplyToAll(draft, draft, targets, packageName))
        // A low cue accidentally copied onto HIGH must still need correction.
        assertTrue(shouldEnableApplyToAll(draft, draft,
            targets + (AlertType.HIGH to draft.copy(type = AlertType.HIGH)), packageName))
        assertTrue(shouldEnableApplyToAll(draft, draft,
            targets + (AlertType.HIGH to targets.getValue(AlertType.HIGH).copy(
                customSoundUri = BundledAlertSounds.uri(packageName, "Contour", 1))), packageName))
    }

    @Test
    fun everyNamedUriHasARealPcmWaveResource() {
        val raw = listOf(File("src/main/res/raw"), File("Common/src/main/res/raw")).first { it.isDirectory }
        val names = BundledAlertSounds.styles.flatMap { style ->
            AlertType.entries.map { BundledAlertSounds.uri(packageName, style, it.id).substringAfterLast('/') }
        }.toSet()
        assertEquals(27, names.size)
        names.forEach { name ->
            val bytes = File(raw, "$name.wav").readBytes()
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
            assertEquals(1, bytes[20].toInt()) // Uncompressed PCM
            assertEquals(1, bytes[22].toInt()) // Mono
            assertEquals(16, bytes[34].toInt()) // 16-bit
            assertTrue(bytes.size > 48000)
        }
    }
}
