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
    fun aConfigurationThatCannotFollowRetiresNothing() {
        // Switching Follow off has its own path; a blank URL is a screen mid-edit.
        val off = FakeRoster(running = listOf(oldId), main = oldId)
        assertFalse(
            NightscoutFollowerRegistry.retireSupersededFollowers(
                config("https://new.example.com", enabled = false), off
            )
        )
        val blank = FakeRoster(running = listOf(oldId), main = oldId)
        assertFalse(NightscoutFollowerRegistry.retireSupersededFollowers(config(""), blank))
        assertTrue(off.calls.isEmpty() && blank.calls.isEmpty())
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
