package tk.glucodata.drivers.nightscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Changing the followed server changes the sensor id (it is a hash of the URL). The old
 * follower must be retired, and only the main role it held may move to the new one.
 */
class NightscoutFollowerReplacementTests {

    private val oldId = NightscoutFollowerRegistry.deriveSensorId("https://old.example.com")
    private val newId = NightscoutFollowerRegistry.deriveSensorId("https://new.example.com")

    private fun config(url: String, enabled: Boolean = true) =
        NightscoutFollowerRegistry.Config(enabled = enabled, url = url, secret = "")

    /** Records what the registry asked for, in order. */
    private class FakeRoster(
        running: List<String>,
        private var main: String?,
        /** What the main role goes to when nothing is configured to take it. */
        private val replacement: String? = null,
    ) : NightscoutFollowerRegistry.FollowerRoster {
        val running = running.toMutableList()
        val calls = mutableListOf<String>()

        override fun runningFollowers(): List<String> = running.toList()

        // Like the real thing, a retired sensor no longer reports the role: that is why the
        // registry has to ask before retiring.
        override fun holdsMain(serial: String): Boolean = main.equals(serial, ignoreCase = true)

        override fun retire(serial: String) {
            calls += "retire $serial"
            running.removeAll { it.equals(serial, ignoreCase = true) }
            if (main.equals(serial, ignoreCase = true)) main = null
        }

        override fun add(sensorId: String) {
            calls += "add $sensorId"
            if (running.none { it.equals(sensorId, ignoreCase = true) }) running += sensorId
        }

        override fun selectMain(sensorId: String) {
            calls += "select $sensorId"
            main = sensorId
        }

        override fun selectReplacement(retired: String) {
            calls += "replace $retired"
            main = replacement
        }

        override fun refreshDisplays() {
            calls += "refresh"
        }

        fun mainSensor() = main
    }

    @Test
    fun urlChangeRetiresTheOldFollowerAndHandsItsMainRoleOver() {
        val roster = FakeRoster(running = listOf(oldId), main = oldId)

        val changed = NightscoutFollowerRegistry.retireSupersededFollowers(
            config("https://new.example.com"), roster
        )

        assertTrue(changed)
        assertEquals(listOf("retire $oldId", "add $newId", "select $newId", "refresh"), roster.calls)
        assertEquals(newId, roster.mainSensor())
        assertEquals(listOf(newId), roster.running)
    }

    @Test
    fun mainRoleIsReadBeforeTheOldFollowerIsRetired() {
        // The fake clears the role when the holder is retired, as the real selection does.
        // Reading it afterwards would find nobody and leave the widget on a dead sensor.
        val roster = FakeRoster(running = listOf(oldId), main = oldId)

        NightscoutFollowerRegistry.retireSupersededFollowers(config("https://new.example.com"), roster)

        assertEquals(newId, roster.mainSensor())
    }

    @Test
    fun aPhysicalSensorPickedAsMainIsNotStolenByTheReplacement() {
        val roster = FakeRoster(running = listOf(oldId), main = "X-22222GX3H")

        NightscoutFollowerRegistry.retireSupersededFollowers(config("https://new.example.com"), roster)

        assertEquals(listOf("retire $oldId", "refresh"), roster.calls)
        assertEquals("X-22222GX3H", roster.mainSensor())
    }

    @Test
    fun theFollowerThatIsStillConfiguredIsLeftAlone() {
        val roster = FakeRoster(running = listOf(newId), main = newId)

        val changed = NightscoutFollowerRegistry.retireSupersededFollowers(
            config("https://new.example.com"), roster
        )

        assertFalse(changed)
        assertTrue(roster.calls.isEmpty())
    }

    @Test
    fun theIdIsComparedWithoutRegardToCase() {
        val roster = FakeRoster(running = listOf(newId.lowercase()), main = newId)

        assertFalse(
            NightscoutFollowerRegistry.retireSupersededFollowers(config("https://new.example.com"), roster)
        )
    }

    @Test
    fun switchingFollowOffRetiresNothingHere() {
        // Switching Follow off has its own path, which also ends the callback properly.
        val off = FakeRoster(running = listOf(oldId), main = oldId)

        assertFalse(
            NightscoutFollowerRegistry.retireSupersededFollowers(
                config("https://new.example.com", enabled = false), off
            )
        )
        assertFalse(
            NightscoutFollowerRegistry.retireSupersededFollowers(config("", enabled = false), off)
        )
        assertTrue(off.calls.isEmpty())
    }

    @Test
    fun clearingTheUrlWithFollowOnRetiresTheFollowerAndRehomesItsMainRole() {
        // The settings screen saves exactly this when the field is emptied: Follow still on,
        // no server. The old follower went on polling its old one, and stayed main.
        val roster = FakeRoster(running = listOf(oldId), main = oldId, replacement = "X-22222GX3H")

        val changed = NightscoutFollowerRegistry.retireSupersededFollowers(config(""), roster)

        assertTrue(changed)
        assertEquals(listOf("retire $oldId", "replace $oldId", "refresh"), roster.calls)
        assertEquals("X-22222GX3H", roster.mainSensor())
        assertTrue(roster.running.isEmpty())
    }

    @Test
    fun clearingTheUrlLeavesAPhysicalSensorAsMain() {
        val roster = FakeRoster(running = listOf(oldId), main = "X-22222GX3H", replacement = "other")

        NightscoutFollowerRegistry.retireSupersededFollowers(config(""), roster)

        assertEquals(listOf("retire $oldId", "refresh"), roster.calls)
        assertEquals("X-22222GX3H", roster.mainSensor())
    }

    @Test
    fun aBlankUrlWithNoFollowerRunningDoesNothing() {
        val roster = FakeRoster(running = emptyList(), main = null)

        assertFalse(NightscoutFollowerRegistry.retireSupersededFollowers(config(""), roster))
        assertTrue(roster.calls.isEmpty())
    }

    @Test
    fun everySupersededFollowerGoesNotJustTheFirst() {
        // Two earlier URLs that were saved without ever connecting leave two behind.
        val older = NightscoutFollowerRegistry.deriveSensorId("https://older.example.com")
        val roster = FakeRoster(running = listOf(older, oldId), main = oldId)

        NightscoutFollowerRegistry.retireSupersededFollowers(config("https://new.example.com"), roster)

        assertEquals(
            listOf("retire $older", "retire $oldId", "add $newId", "select $newId", "refresh"),
            roster.calls
        )
    }

    @Test
    fun noRunningFollowerMeansNothingToDo() {
        val roster = FakeRoster(running = emptyList(), main = null)

        assertFalse(
            NightscoutFollowerRegistry.retireSupersededFollowers(config("https://new.example.com"), roster)
        )
        assertTrue(roster.calls.isEmpty())
    }

    // ---------- orphans left by the bug, found at start-up ----------

    private val ranHere: (String) -> Boolean = { it.equals(oldId, ignoreCase = true) }

    @Test
    fun aFollowerThisPhoneRanForAnotherUrlIsAnOrphan() {
        val orphans = NightscoutFollowerRegistry.orphanedFollowerIds(
            candidates = listOf(oldId, newId, null),
            config = config("https://new.example.com"),
            ranHere = ranHere,
        )
        assertEquals(listOf(oldId), orphans)
    }

    @Test
    fun aMirroredFollowerFromAnotherPhoneIsNotAnOrphan() {
        // A clone receiver holds sensors it never ran; they must keep their role and shell.
        val orphans = NightscoutFollowerRegistry.orphanedFollowerIds(
            candidates = listOf("NSF-0123456789AB"),
            config = config("https://new.example.com"),
            ranHere = ranHere,
        )
        assertTrue(orphans.isEmpty())
    }

    @Test
    fun onlyFollowerIdsCanBeOrphans() {
        val orphans = NightscoutFollowerRegistry.orphanedFollowerIds(
            candidates = listOf("X-22222GX3H", "LMF-FEF5BF47035B"),
            config = config("https://new.example.com"),
            ranHere = { true },
        )
        assertTrue(orphans.isEmpty())
    }

    @Test
    fun withNoUrlConfiguredNothingIsAnOrphan() {
        val orphans = NightscoutFollowerRegistry.orphanedFollowerIds(
            candidates = listOf(oldId),
            config = config(""),
            ranHere = { true },
        )
        assertTrue(orphans.isEmpty())
    }

    // Native storage names a sensor without the first five characters of its id, so what
    // activeSensors() and currentsensorname() return never starts with the prefix.
    private val nativeNames = mapOf(oldId.drop(5) to oldId, "0123456789A" to "NSF-0123456789AB")

    @Test
    fun nativeShortNamesAreResolvedToTheFullIdBeforeTheyAreJudged() {
        val orphans = NightscoutFollowerRegistry.orphanedFollowerIds(
            candidates = listOf(oldId.drop(5)),
            config = config("https://new.example.com"),
            ranHere = ranHere,
            fullName = nativeNames::get,
        )
        assertEquals(listOf(oldId), orphans)
    }

    @Test
    fun aShortNameThatResolvesToAMirroredFollowerIsStillNotAnOrphan() {
        val orphans = NightscoutFollowerRegistry.orphanedFollowerIds(
            candidates = listOf("0123456789A"),
            config = config("https://new.example.com"),
            ranHere = ranHere,
            fullName = nativeNames::get,
        )
        assertTrue(orphans.isEmpty())
    }

    @Test
    fun aShortNameThatResolvesToAnotherSensorIsNotAnOrphan() {
        val orphans = NightscoutFollowerRegistry.orphanedFollowerIds(
            candidates = listOf("22222GX3H", "unknown"),
            config = config("https://new.example.com"),
            ranHere = { true },
            fullName = mapOf("22222GX3H" to "X-22222GX3H")::get,
        )
        assertTrue(orphans.isEmpty())
    }

    @Test
    fun theFullIdAndItsShortNameAreOneOrphan() {
        val orphans = NightscoutFollowerRegistry.orphanedFollowerIds(
            candidates = listOf(oldId, oldId.drop(5)),
            config = config("https://new.example.com"),
            ranHere = ranHere,
            fullName = nativeNames::get,
        )
        assertEquals(listOf(oldId), orphans)
    }

    @Test
    fun duplicatesAreReportedOnce() {
        val orphans = NightscoutFollowerRegistry.orphanedFollowerIds(
            candidates = listOf(oldId, " $oldId "),
            config = config("https://new.example.com"),
            ranHere = { true },
        )
        assertEquals(listOf(oldId), orphans)
    }
}
