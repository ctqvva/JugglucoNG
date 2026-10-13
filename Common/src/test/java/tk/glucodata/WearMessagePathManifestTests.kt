package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
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
 *
 * Only these two files are read, because only these two are build inputs: one source set
 * gets one manifest, and `sourceSets` in `Common/build.gradle` declares no per-variant one.
 * Seventeen `AndroidManifest.xml.*` snapshots from the initial import (`.debug`, `.release`,
 * `.33`, ...) were deleted in #496; they looked like per-variant overlays and still listed
 * `/settings`, but no manifest merger ever read them.
 */
class WearMessagePathManifestTests {

    private val moduleRoot = File("").absoluteFile.let { working ->
        // The test runs from the module directory; walk up if it does not.
        generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "src/main/java/tk/glucodata/MessageSender.kt").exists() }
            ?: working
    }

    private fun source(relative: String) = File(moduleRoot, relative)

    /**
     * The paths, from the one place they now live.
     *
     * This used to parse `const val ..._PATH = "..."` out of MessageSender with a regex. It does
     * not any more, because those constants are gone: the strings moved into WearMessagePath, and
     * leaving the test reading a source pattern that no longer exists would have made it pass
     * vacuously -- a test that finds nothing to check is worse than no test.
     */
    private fun declaredPaths(): Map<String, String> =
        WearMessagePath.entries.associate { it.name to it.wire }

    private fun handledPaths(): List<String> {
        val declared = declaredPaths()
        val text = source("src/main/java/tk/glucodata/MessageReceiver.kt").readText()
        return Regex("""WearMessagePath\.(\w+)\s*->""")
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

    /**
     * The other direction: a prefix that names no path in the type.
     *
     * The coverage check above cannot see this, because a prefix matching nothing is not a
     * path that fails to arrive. It is what dropping a path looks like when only the enum and
     * the receiver are edited: the filter keeps delivering, and the receiver answers `null ->`
     * with a log line, so the message is not dropped -- it is delivered and refused, which is
     * a wider filter than the protocol has.
     */
    private fun assertNoDeadPrefix(manifest: String) {
        val prefixes = receiverPrefixes(manifest)
        val dead = prefixes.filterNot { prefix ->
            WearMessagePath.entries.any { path ->
                path.wire == prefix || path.wire.startsWith("$prefix/")
            }
        }
        assertTrue(
            "$manifest delivers ${dead.sorted()}, which is no path in WearMessagePath — " +
                "delete the prefix with the path",
            dead.isEmpty(),
        )
    }

    @Test
    fun everyHandledPathReachesTheWatch() = assertCovers("src/wear/AndroidManifest.xml")

    @Test
    fun everyHandledPathReachesThePhone() = assertCovers("src/mobile/AndroidManifest.xml")

    @Test
    fun theWatchFilterDeclaresNoPathThatIsNotInTheType() =
        assertNoDeadPrefix("src/wear/AndroidManifest.xml")

    @Test
    fun thePhoneFilterDeclaresNoPathThatIsNotInTheType() =
        assertNoDeadPrefix("src/mobile/AndroidManifest.xml")

    /**
     * The typed path set, the declared constants and the receiver's dispatch must be the same
     * set, today and after the constants are removed.
     *
     * This test is the reason the type can be introduced before anything uses it. The rest of
     * this class reads the paths by regex out of two source files, so it would stop being a guard
     * the moment the constants go away -- the `when` would then be reading a type that nothing
     * cross-checks, and a wrong `wire` string would be green in a debug build and wrong on a
     * device. So the comparison is anchored on whichever side still exists: this runs now, against
     * the constants, and it runs again once the receiver dispatches on the type.
     */
    @Test
    fun theTypedPathsMatchTheReceiverDispatch() {
        val typed = WearMessagePath.entries.map { it.wire }
        assertEquals(
            "every path in the type must be dispatched in MessageReceiver; the when is on the " +
                "type now, so the compiler enforces coverage of the enum, and this enforces " +
                "that every enum entry means a path the receiver acts on",
            typed.toSet(),
            handledPaths().toSet(),
        )
        assertEquals(
            "the receiver must not dispatch a path that is not in the type",
            handledPaths().toSet().intersect(typed.toSet()),
            handledPaths().toSet(),
        )
        assertEquals(
            "no duplicate wire strings, or fromWire cannot resolve an entry",
            typed.size,
            typed.toSet().size,
        )
    }

    /**
     * The wire strings are the protocol, and they are frozen.
     *
     * Every other check here compares the type with this build's own receiver and manifests, so
     * a typo in a `wire` string would move sender, receiver and the check together and stay
     * green -- while a watch or phone still running the previous build stops understanding that
     * message. The two sides of a pair are updated separately, so the strings are pinned
     * literally, copied from the `_PATH` constants they replaced (#488). Changing one is a
     * protocol change and belongs with a `WearProtocol` version bump, not in a rename.
     *
     * `SETTINGS` to `/settings` was the one entry removed rather than frozen (#491): no NG build
     * ever sent that path -- `sendsettings()` was commented out in the first commit and the
     * settings bytes travel inside `/start` -- and the Wearable layer only pairs apps with the
     * same package, so an upstream Juggluco cannot send it to `tk.glucodata.ng` either. Nothing
     * ever spoke it, so nothing can be broken by dropping it.
     */
    @Test
    fun theWireStringsAreTheOnesOlderBuildsSpeak() {
        val frozen = mapOf(
            "ALERT_ACK" to "/alertack",
            "ASKFORSTART" to "/askforstart",
            "BLUETOOTH" to "/bluetooth",
            "CALIBRATE" to "/calibrate",
            "DATA" to "/data",
            "DEFAULTS" to "/defaults",
            "DISPLAY_PREFS" to "/displayprefs",
            "DISPLAY_PREFS_MAINSENSOR" to "/displayprefs/mainsensor",
            "DISPLAY_PREFS_REQ" to "/displayprefs/req",
            "GLUCOSE_COLORS" to "/glucosecolors",
            "MESSAGES" to "/messages",
            "NETINFO" to "/netinfo",
            "PROTOCOL" to "/protocol",
            "SENSOR_CLAIM_STATUS" to "/sensorclaimstatus",
            "SENSOR_HANDOFF" to "/sensorhandoff",
            "START" to "/start",
            "SYNC2_CAL" to "/sync2/cal",
            "SYNC2_CALCMD" to "/sync2/calcmd",
            "SYNC2_CHUNK" to "/sync2/chunk",
            "SYNC2_JOURNAL_CMD" to "/sync2/journal/cmd",
            "SYNC2_JOURNAL_DATA" to "/sync2/journal",
            "SYNC2_JOURNAL_REQ" to "/sync2/journal/req",
            "SYNC2_OWN" to "/sync2/own",
            "SYNC2_REMOVE" to "/sync2/remove",
            "SYNC2_REQ" to "/sync2/req",
            "TOGGLES" to "/toggles",
            "TOGGLES_REQ" to "/toggles/req",
            "TOGGLES_SET" to "/toggles/set",
            "WAKE" to "/wake",
            "WAKESTREAM" to "/wakestream",
        )
        assertEquals(frozen, WearMessagePath.entries.associate { it.name to it.wire })
    }

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
