package tk.glucodata

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A WearableListenerService is only delivered the path prefixes its manifest
 * filter names. An unlisted path is not an error anywhere — the message is
 * simply dropped, on both devices, in silence.
 *
 * That cost three features before it was spotted: the colour scheme, the
 * mirrored display preferences and the toggle state were all implemented,
 * wired and tested, and none of them ever arrived. The watch's sensor-claim
 * status had been going nowhere for longer still.
 *
 * So: every path [MessageReceiver] handles must be covered by a prefix in both
 * manifests. Requiring both rather than reasoning about which side handles what
 * keeps this check honest — a listed path the device never receives costs
 * nothing, and the receiver already ignores anything meant for the other side.
 */
class WearMessagePathManifestTests {

    private val moduleRoot = File("").absoluteFile.let { working ->
        // The test runs from the module directory; walk up if it does not.
        generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "src/main/java/tk/glucodata/MessageSender.kt").exists() }
            ?: working
    }

    private fun source(relative: String) = File(moduleRoot, relative)

    private fun declaredPaths(): Map<String, String> {
        val text = source("src/main/java/tk/glucodata/MessageSender.kt").readText()
        return Regex("""const val (\w*PATH)\s*=\s*"([^"]+)"""")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun handledPaths(): List<String> {
        val declared = declaredPaths()
        val text = source("src/main/java/tk/glucodata/MessageReceiver.kt").readText()
        return Regex("""MessageSender\.(\w*PATH)\s*->""")
            .findAll(text)
            .mapNotNull { declared[it.groupValues[1]] }
            .distinct()
            .toList()
    }

    /** The prefixes the MessageReceiver filter in [manifest] actually declares. */
    private fun receiverPrefixes(manifest: String): List<String> {
        val text = source(manifest).readText()
        val serviceAt = text.indexOf(".MessageReceiver")
        assertTrue("$manifest declares no MessageReceiver", serviceAt >= 0)
        val filterStart = text.indexOf("<intent-filter>", serviceAt)
        val filterEnd = text.indexOf("</intent-filter>", filterStart)
        assertTrue("$manifest has no intent-filter for MessageReceiver", filterStart in 0 until filterEnd)
        return Regex("""pathPrefix="([^"]+)"""")
            .findAll(text.substring(filterStart, filterEnd))
            .map { it.groupValues[1] }
            .toList()
    }

    private fun assertCovers(manifest: String) {
        val prefixes = receiverPrefixes(manifest)
        val missing = handledPaths().filterNot { path -> prefixes.any { path.startsWith(it) } }
        assertTrue(
            "$manifest does not deliver ${missing.sorted()} to MessageReceiver — " +
                "those messages are dropped without any error",
            missing.isEmpty(),
        )
    }

    @Test
    fun everyHandledPathReachesTheWatch() = assertCovers("src/wear/AndroidManifest.xml")

    @Test
    fun everyHandledPathReachesThePhone() = assertCovers("src/mobile/AndroidManifest.xml")

    @Test
    fun thePathsThisTestReadsAreActuallyThere() {
        // Guards the regexes: if the declarations move or change shape, the two
        // tests above would pass by finding nothing to check.
        val handled = handledPaths()
        assertTrue("no handled paths parsed from MessageReceiver", handled.size > 10)
        assertTrue("expected the sync2 chunk path", handled.contains("/sync2/chunk"))
        assertTrue("expected the display-prefs path", handled.contains("/displayprefs"))
    }
}
