package tk.glucodata

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.HistoryReading
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

/** The Room lane stays automatic even when the native mirror holds the final primary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NativeHistoryTestApplication::class,
    shadows = [NativeMirrorCalibrationInputs::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class RoomMirrorBackfillCalibrationTests {
    private lateinit var database: HistoryDatabase
    private var previousUnit = 0

    @Before
    fun setUp() {
        previousUnit = Applic.unit
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), HistoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        CalibrationAccess.register(object : CalibrationProvider {
            override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?) = true
            override fun getCalibratedValue(value: Float, timestamp: Long, isRawMode: Boolean,
                emitDiagnostics: Boolean, sensorId: String?) =
                value + if (Applic.unit == 1) 15f / 18.0182f else 15f
        })
    }

    @After
    fun tearDown() {
        database.close()
        CalibrationAccess.unregisterForTests()
        Applic.unit = previousUnit
    }

    @Test
    fun automaticRoomLaneReachesXdripAndShareWithExactlyOneCorrection() = runBlocking {
        val incoming = VirtualGlucoseSensorBridge.Reading(
            timestampMs = 1_760_000_000_000L,
            glucoseMgdl = 130f, autoMgdl = 115f, calibratedMgdl = 130f,
        )
        assertEquals(130f, incoming.primaryGlucoseMgdl, 0f)
        val dao = database.historyDao()
        for (id in listOf("API-123456789ABC", "NSF-123456789ABC", "MQF-123456789ABC")) {
            dao.insert(HistoryReading(
                timestamp = incoming.timestampMs, sensorSerial = id,
                value = incoming.storageGlucoseMgdl, rawValue = 90f, rate = null,
            ))
            val stored = dao.getReadingsSinceForSensor(id, 0L).single()
            assertEquals(115f, stored.value, 0f)
            val rows = listOf(XdripBackfill.Row(stored.timestamp, stored.value, stored.rawValue, stored.rate))
            for (unit in 0..1) {
                Applic.unit = unit
                for (mode in 0..3) {
                    val expected = if (mode == 1 || mode == 3) 105 else 130
                    assertEquals(expected.toDouble(),
                        XdripBackfill.candidates(id, rows, mode, 0).single().mgdl, 0.001)
                    // Dexcom Share uses this same projection without the xDrip tracker.
                    assertEquals(expected,
                        XdripBackfill.displayedRows(id, rows, mode, 0, null).single().mgdl)
                }
            }
        }
    }
}
