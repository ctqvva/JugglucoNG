package tk.glucodata.ui.alerts

import java.io.File
import java.util.Locale
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertType

class BundledAlertSoundsTests {
    private val packageName = "tk.glucodata"

    @Test
    fun xdripStyleIsDirectlyAboveJuggluco() {
        assertEquals(listOf("xDrip-style", "Juggluco"), BundledAlertSounds.styles.take(2))
        assertEquals("android.resource://$packageName/raw/alert_xdrip_urgent_low",
            BundledAlertSounds.uri(packageName, "xDrip-style", AlertType.VERY_LOW.id))
    }

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
                assertEquals("android.resource://$packageName/raw/alert_${BundledAlertSounds.resourceStyle(style)}_$cue", uri)
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
        BundledAlertSounds.styles.forEach { style ->
            val draft = AlertConfig(AlertType.LOW, customSoundUri = BundledAlertSounds.uri(packageName, style, 0))
            val targets = AlertType.entries.associateWith { type ->
                draft.copy(type = type, customSoundUri = BundledAlertSounds.forAlert(draft.customSoundUri, packageName, type.id))
            }
            assertFalse(shouldEnableApplyToAll(draft, draft, targets, packageName))
            // A low cue accidentally copied onto HIGH must still need correction.
            assertTrue(shouldEnableApplyToAll(draft, draft,
                targets + (AlertType.HIGH to draft.copy(type = AlertType.HIGH)), packageName))
            val differentStyle = if (style == "Contour") "Halo" else "Contour"
            assertTrue(shouldEnableApplyToAll(draft, draft,
                targets + (AlertType.HIGH to targets.getValue(AlertType.HIGH).copy(
                    customSoundUri = BundledAlertSounds.uri(packageName, differentStyle, 1))), packageName))
        }
    }

    private fun rawDir() =
        listOf(File("src/main/res/raw"), File("Common/src/main/res/raw")).first { it.isDirectory }

    private fun toolsFile(name: String) =
        listOf(File("../tools/alert-sounds/$name"),
            File("tools/alert-sounds/$name")).first { it.isFile }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun committedAssetsMatchManifestHashes() {
        val raw = rawDir()
        val manifest = JSONObject(toolsFile("asset-manifest.json").readText())
        val assets = manifest.getJSONObject("assets")
        assertEquals(63, assets.length())
        assets.keys().forEach { name ->
            val entry = assets.getJSONObject(name)
            val file = File(raw, entry.getString("file"))
            assertTrue("$name: ${entry.getString("file")} missing", file.isFile)
            assertTrue("$name: suspiciously small", file.length() > 10000)
            assertEquals(name, entry.getString("sha256"), sha256(file.readBytes()))
        }
        val legacy = manifest.getJSONObject("legacy")
        assertEquals(9, legacy.length())
        legacy.keys().forEach { name ->
            val entry = legacy.getJSONObject(name)
            val file = File(raw, entry.getString("file"))
            assertTrue("$name: legacy ${entry.getString("file")} missing", file.isFile)
            assertEquals(name, entry.getString("sha256"), sha256(file.readBytes()))
        }
    }

    @Test
    fun manifestFramesMatchReferences() {
        val manifest = JSONObject(toolsFile("asset-manifest.json").readText())
        val assets = manifest.getJSONObject("assets")
        val original = JSONObject(toolsFile("original-reference.json").readText())
        var durationMatched = 0
        assets.keys().forEach { name ->
            val entry = assets.getJSONObject(name)
            // Internal consistency: frames agree with the recorded duration.
            assertEquals(name, Math.round(entry.getDouble("seconds") * 48000).toInt(),
                entry.getInt("frames"))
            // The duration-matched collections keep the exact reference frames.
            val cue = name.substringAfter("alert_").substringAfter('_')
            if (name.startsWith("alert_timber_") || name.startsWith("alert_ember_") ||
                name.startsWith("alert_juggluco_")) {
                assertEquals(name, original.getJSONObject(cue).getInt("frames"),
                    entry.getInt("frames"))
                durationMatched++
            }
        }
        assertEquals(27, durationMatched)
    }

    @Test
    fun jugglucoRemastersPlayModernWavsWhileLegacyChoicesStillReadAsJuggluco() {
        val raw = listOf(File("src/main/res/raw"), File("Common/src/main/res/raw")).first { it.isDirectory }
        val referenceFile = listOf(File("../tools/alert-sounds/original-reference.json"),
            File("tools/alert-sounds/original-reference.json")).first { it.isFile }
        val refs = JSONObject(referenceFile.readText())
        AlertType.entries.forEach { type ->
            val cue = BundledAlertSounds.cueFor(type.id)
            val uri = BundledAlertSounds.uri(packageName, "Juggluco", type.id)
            assertEquals("android.resource://$packageName/raw/alert_juggluco_$cue", uri)
            assertEquals("Juggluco", BundledAlertSounds.styleFor(uri, packageName))
            // A saved legacy MP3/OGG choice still reads as Juggluco and remaps to
            // the matching remaster when applied across alerts.
            val legacy = "android.resource://$packageName/raw/" +
                refs.getJSONObject(cue).getString("source").substringBeforeLast('.')
            assertEquals("Juggluco", BundledAlertSounds.styleFor(legacy, packageName))
            assertEquals(uri, BundledAlertSounds.forAlert(legacy, packageName, type.id))
        }
        // The legacy sources themselves are untouched.
        refs.keys().forEach { cue ->
            val ref = refs.getJSONObject(cue)
            val hash = MessageDigest.getInstance("SHA-256").digest(File(raw, ref.getString("source")).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(ref.getString("sha256"), hash)
        }
    }

    @Test
    fun everyNamedUriHasACommittedManifestAsset() {
        val manifest = JSONObject(toolsFile("asset-manifest.json").readText())
        val assets = manifest.getJSONObject("assets")
        val names = BundledAlertSounds.styles.flatMap { style ->
            AlertType.entries.map { BundledAlertSounds.uri(packageName, style, it.id).substringAfterLast('/') }
        }.toSet()
        assertEquals(63, names.size)
        names.forEach { name ->
            assertTrue("$name has no manifest entry", assets.has(name))
            val entry = assets.getJSONObject(name)
            // Bundled sounds resolve by resource name: the committed file keeps
            // that stem with its container extension.
            assertEquals("$name.m4a", entry.getString("file"))
            assertTrue("$name missing from res/raw", File(rawDir(), entry.getString("file")).isFile)
        }
        assertEquals(63, assets.length())
    }
}
