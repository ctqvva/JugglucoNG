package tk.glucodata.drivers.medtrum

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.Applic

class MedtrumSensorLifeTest {

    private val hour = 3_600_000L

    @Test
    fun startIsNowMinusTheElapsedSecondsTheVendorAppCountsFrom() {
        // Captured: elapsed=38629s; the vendor app showed 5 h 56 min elapsed 4 h 47 min earlier.
        val now = 1_792_263_327_000L
        assertEquals(now - 38_629_000L, MedtrumSensorLife.startFromElapsed(now, 38_629L))
    }

    @Test
    fun theEndIsFourteenDaysAfterTheStart() {
        val start = 1_000_000_000_000L
        assertEquals(start + 14 * 24 * hour, MedtrumSensorLife.endFromStart(start))
    }

    @Test
    fun remainingTimeMatchesTheVendorCountdown() {
        // The vendor app showed 13 d 18 h 4 min left at 5 h 56 min elapsed: 330 whole hours.
        val now = 2_000_000_000_000L
        val start = now - (5 * hour + 56 * 60_000L)
        assertEquals(330, MedtrumSensorLife.remainingHours(start, now))
        assertEquals(5, MedtrumSensorLife.ageHours(start, now))
    }

    @Test
    fun anUnknownStartHasNoDatesAndNoAge() {
        assertEquals(0L, MedtrumSensorLife.startFromElapsed(1_000L, 0L))
        assertEquals(0L, MedtrumSensorLife.endFromStart(0L))
        assertEquals(-1, MedtrumSensorLife.ageHours(0L, 1_000L))
        assertEquals(-1, MedtrumSensorLife.remainingHours(0L, 1_000L))
    }

    @Test
    fun anExpiredPatchHasZeroHoursLeft() {
        val start = 1_000_000_000_000L
        assertEquals(0, MedtrumSensorLife.remainingHours(start, start + 15 * 24 * hour))
    }

    @Test
    fun jitterDoesNotRewriteTheStoredStartButARealMoveDoes() {
        val stored = 1_000_000_000_000L
        assertTrue(MedtrumSensorLife.shouldReplaceStart(0L, stored))
        assertFalse(MedtrumSensorLife.shouldReplaceStart(stored, stored + 5_000L))
        assertFalse(MedtrumSensorLife.shouldReplaceStart(stored, stored - 90_000L))
        assertTrue(MedtrumSensorLife.shouldReplaceStart(stored, stored + 3 * 60_000L))
        assertFalse(MedtrumSensorLife.shouldReplaceStart(stored, 0L))
    }
}

class MedtrumSerialAliasTest {

    @Test
    fun theNativeShortNameOfTheCapturedSerialIsAnAlias() {
        assertTrue(MedtrumSerialAlias.isShortForm("95446", "2329895446"))
    }

    @Test
    fun onlyAnEndingOfTheSerialCounts() {
        assertFalse(MedtrumSerialAlias.isShortForm("23298", "2329895446"))
        assertFalse(MedtrumSerialAlias.isShortForm("95447", "2329895446"))
    }

    @Test
    fun aLongerNumericEndingIsAnotherPatchNotAnAlias() {
        // Both are valid serials. Matching any numeric ending would merge them, and replacing one
        // patch with the other would keep the old driver.
        assertFalse(MedtrumSerialAlias.isShortForm("329895446", "2329895446"))
        assertFalse(MedtrumSerialAlias.isShortForm("895446", "2329895446"))
        assertFalse(MedtrumIdentityAdapterProbe.matches("329895446", "2329895446"))
    }

    @Test
    fun theSerialItselfAndTooShortOrNonNumericNamesAreNotAliases() {
        assertFalse(MedtrumSerialAlias.isShortForm("2329895446", "2329895446"))
        assertFalse(MedtrumSerialAlias.isShortForm("5446", "2329895446"))
        assertFalse(MedtrumSerialAlias.isShortForm("3MH01M0MWYR", "2329895446"))
        assertFalse(MedtrumSerialAlias.isShortForm(null, "2329895446"))
        assertFalse(MedtrumSerialAlias.isShortForm("95446", null))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MedtrumTestApplication::class, shadows = [MedtrumTestNatives::class])
class MedtrumIdentityAliasTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Applic.app = context as Applic
        MedtrumPersistence.clear(context)
        MedtrumPersistence.setSensorId(context, "2329895446")
    }

    @Test
    fun theShortNativeNameIsClaimedSoNoLibreCallbackIsBuiltForIt() {
        assertTrue(MedtrumIdentityAdapter.hasPersistedManagedRecord("95446"))
        assertTrue(MedtrumIdentityAdapter.hasPersistedManagedRecord("2329895446"))
        assertFalse(MedtrumIdentityAdapter.hasPersistedManagedRecord("3MH01M0MWYR"))
    }

    @Test
    fun claimingTheAliasCreatesNoCallbackOfItsOwn() {
        assertNull(MedtrumIdentityAdapter.createManagedCallback(context, "95446", 0L))
    }

    @Test
    fun callbackIdMatchingSeesTheAliasAsTheSamePatch() {
        assertTrue(MedtrumIdentityAdapter.matchesCallbackId("95446", "2329895446"))
        assertTrue(MedtrumIdentityAdapter.matchesCallbackId("2329895446", "95446"))
        assertFalse(MedtrumIdentityAdapter.matchesCallbackId("3MH01M0MWYR", "2329895446"))
    }

    @Test
    fun aDriverReportsItsDatesOnceReadStateHasToldItWhenThePatchStarted() {
        val start = System.currentTimeMillis() - 38_629_000L
        MedtrumPersistence.setSensorStartMs(context, start)
        val driver = MedtrumBleManager("2329895446", 0L)
        try {
            assertEquals(start, driver.getStartTimeMs())
            assertEquals(start + MedtrumSensorLife.LIFETIME_MS, driver.getExpectedEndMs())
            val snapshot = driver.getManagedUiSnapshot("2329895446")!!
            assertEquals(start, snapshot.startTimeMs)
            assertEquals(start + MedtrumSensorLife.LIFETIME_MS, snapshot.expectedEndMs)
            assertEquals(10, snapshot.sensorAgeHours)
            assertFalse(snapshot.isSensorExpired)
        } finally {
            driver.terminateManagedSensor(wipeData = true)
        }
    }

    @Test
    fun aDriverWithoutAKnownStartHasNoDates() {
        val driver = MedtrumBleManager("2329895446", 0L)
        try {
            assertEquals(0L, driver.getStartTimeMs())
            assertEquals(0L, driver.getManagedUiSnapshot("2329895446")!!.expectedEndMs)
        } finally {
            driver.terminateManagedSensor(wipeData = true)
        }
    }
}

/** The adapter's id matching, without a driver. */
private object MedtrumIdentityAdapterProbe {
    fun matches(a: String, b: String) = MedtrumIdentityAdapter.matchesCallbackId(a, b)
}

class MedtrumSensorSessionTest {

    private val start = 1_000_000_000_000L
    private val keyA = MedtrumSensorSession.key(7, 1_000L)
    private val keyB = MedtrumSensorSession.key(8, 2_000L)

    @Test
    fun aNewInsertionWithARestartedElapsedTimeIsANewSensor() {
        assertTrue(MedtrumSensorSession.isNewSession(keyA, keyB, start, start + 5 * 24 * 3_600_000L))
    }

    @Test
    fun theSameInsertionIsTheSameSensor() {
        assertFalse(MedtrumSensorSession.isNewSession(keyA, keyA, start, start + 5 * 24 * 3_600_000L))
    }

    @Test
    fun aChangedKeyAloneDoesNotArchiveCalibrations() {
        // A drifting counter would otherwise drop the user's calibration.
        assertFalse(MedtrumSensorSession.isNewSession(keyA, keyB, start, start + 30_000L))
        assertFalse(MedtrumSensorSession.isNewSession(keyA, keyB, start, start - 5 * 24 * 3_600_000L))
    }

    @Test
    fun theFirstSightOfASensorIsNotAReplacement() {
        assertFalse(MedtrumSensorSession.isNewSession("", keyB, 0L, start))
        assertFalse(MedtrumSensorSession.isNewSession(keyA, keyB, 0L, start))
    }

    @Test
    fun theArchiveIdMatchesNoLiveSensor() {
        assertEquals("2329895446@1000", MedtrumSensorSession.archiveId("2329895446", 1_000L))
        assertFalse(MedtrumSerialAlias.isShortForm(MedtrumSensorSession.archiveId("2329895446", 1_000L), "2329895446"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MedtrumTestApplication::class, shadows = [MedtrumTestNatives::class])
class MedtrumCalibrationArchiveTest {
    private val serial = "2329895446"
    private lateinit var context: Context
    private lateinit var driver: MedtrumBleManager
    private val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    private val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()

    private val provider = object : tk.glucodata.CalibrationProvider {
        override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?) = false
        override fun getCalibratedValue(
            value: Float, timestamp: Long, isRawMode: Boolean, emitDiagnostics: Boolean, sensorId: String?,
        ) = value

        override fun archiveSensorCalibrationsBlocking(sensorId: String, archiveId: String, cutoffMs: Long): Boolean {
            calls.add(sensorId to archiveId)
            return results.poll() ?: true
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Applic.app = context as Applic
        MedtrumPersistence.clear(context)
        calls.clear()
        results.clear()
        tk.glucodata.CalibrationAccess.register(provider)
        MedtrumPersistence.setSensorId(context, serial)
        MedtrumPersistence.setSessionKey(context, MedtrumSensorSession.key(7, 1_000L))
        MedtrumPersistence.setSensorStartMs(context, OLD_START)
        driver = MedtrumBleManager(serial, 0L)
    }

    @org.junit.After
    fun tearDown() {
        driver.terminateManagedSensor(wipeData = true)
        tk.glucodata.CalibrationAccess.unregisterForTests()
    }

    @Test
    fun aNewSensorFilesTheOldCalibrationsAndClearsTheRequest() {
        noteState(sensorId = 8, insertTime = 2_000L)

        awaitCalls(1)
        assertEquals(serial to "$serial@$OLD_START", calls.first())
        awaitPendingCleared()
    }

    @Test
    fun aFailedMoveStaysPendingAndAnyLaterReadStateRetriesIt() {
        results.add(false)
        noteState(sensorId = 8, insertTime = 2_000L)
        awaitCalls(1)
        assertEquals("a move that did not happen must stay requested", "$serial@$OLD_START", MedtrumPersistence.getPendingArchive(context))

        // The session key is already stored, so only the persisted request can ask again.
        driver.softDisconnect()
        // The first attempt may still be unwinding on the archive thread, in which case this
        // readState finds it running and the next one retries, as on a real link.
        await {
            noteState(sensorId = 8, insertTime = 2_000L)
            calls.size >= 2
        }
        awaitPendingCleared()
    }

    @Test
    fun theRequestSurvivesADisconnectBeforeItRuns() {
        MedtrumPersistence.setPendingArchive(context, "$serial@$OLD_START")

        driver.softDisconnect()
        noteState(sensorId = 7, insertTime = 1_000L)

        awaitCalls(1)
        assertEquals("$serial@$OLD_START", calls.first().second)
    }

    @Test
    fun withoutACalibrationEngineTheRequestIsDroppedWithoutACall() {
        tk.glucodata.CalibrationAccess.unregisterForTests()
        MedtrumPersistence.setPendingArchive(context, "$serial@$OLD_START")

        noteState(sensorId = 7, insertTime = 1_000L)

        assertEquals("", MedtrumPersistence.getPendingArchive(context))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun theSameSensorNeverArchivesAnything() {
        noteState(sensorId = 7, insertTime = 1_000L)

        Thread.sleep(200)
        assertTrue(calls.isEmpty())
        assertEquals("", MedtrumPersistence.getPendingArchive(context))
    }

    private fun noteState(sensorId: Int, insertTime: Long) {
        // A new sensor reports an elapsed time that restarted; the same one keeps counting.
        val elapsed = if (insertTime == 1_000L) 5L * 24 * 3600 else 3_600L
        val state = MedtrumProtocol.StateRecord(
            version = 1, phase = MedtrumProtocol.Phase.MEASURING, status = 0, sensorId = sensorId,
            sequence = 10, signal = 1_500, calibrationFactor = 13_476,
            runningTime = insertTime + elapsed, insertTime = insertTime,
            nextCalibrationSequence = 0, batteryMillivolts = 2_000,
        )
        MedtrumBleManager::class.java.getDeclaredMethod("noteSensorSession", MedtrumProtocol.StateRecord::class.java)
            .apply { isAccessible = true }
            .invoke(driver, state)
    }

    private fun awaitCalls(n: Int) = await { calls.size >= n }

    private fun awaitPendingCleared() = await { MedtrumPersistence.getPendingArchive(context).isEmpty() }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("condition not reached in time; calls=$calls", condition())
    }

    private companion object {
        val OLD_START = System.currentTimeMillis() - 10L * 24 * 3_600_000L
    }
}
