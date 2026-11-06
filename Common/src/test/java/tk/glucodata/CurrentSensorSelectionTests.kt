package tk.glucodata

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CurrentSensorSelectionTests {
    private val ottai = "70D07EDCB416"
    private val aidex = "X-222227KUFY"

    /** Native sensor table: AiDex is native-backed, Ottai only has its own mirror. */
    private open inner class FakeNative(var current: String?) : CurrentSensorSelection.Backend {
        val writes = mutableListOf<String>()
        override fun hasNativeBacking(sensorId: String) = sensorId == aidex
        override fun nativeName(sensorId: String): String? = sensorId
        override fun mirrorName(sensorId: String): String? = sensorId.takeIf { it == ottai }
        override fun selectedNative(): String? = current
        override fun setNative(name: String) {
            writes += name
            current = name.ifEmpty { null }
        }
    }

    @Before
    fun setUp() {
        ManagedCurrentSensor.clear()
    }

    @After
    fun tearDown() {
        ManagedCurrentSensor.clear()
    }

    @Test
    fun select_managedSensorWithMirror_pointsNativeCurrentAtTheMirror() {
        val native = FakeNative(current = aidex)
        CurrentSensorSelection.select(ottai, native)
        assertEquals(ottai, ManagedCurrentSensor.get())
        assertEquals(ottai, native.current)
    }

    @Test
    fun select_nativeBackedSensor_clearsManagedSlot() {
        ManagedCurrentSensor.set(ottai)
        val native = FakeNative(current = ottai)
        CurrentSensorSelection.select(aidex, native)
        assertNull(ManagedCurrentSensor.get())
        assertEquals(aidex, native.current)
    }

    @Test
    fun sync_fillsUnsetNativeCurrent() {
        // Upgraded install: startup cleared native current to -1. lastsensorname() would
        // still name Ottai through its last() fallback, which is why the backend reads
        // the real current instead.
        ManagedCurrentSensor.set(ottai)
        val native = FakeNative(current = null)
        CurrentSensorSelection.syncNativeToManaged(ottai, native)
        assertEquals(listOf(ottai), native.writes)
    }

    @Test
    fun sync_movesNativeCurrentOffThePreviousSensor() {
        ManagedCurrentSensor.set(ottai)
        val native = FakeNative(current = aidex)
        CurrentSensorSelection.syncNativeToManaged(ottai, native)
        assertEquals(ottai, native.current)
    }

    @Test
    fun sync_leavesAlreadySelectedMirrorAlone() {
        ManagedCurrentSensor.set(ottai)
        val native = FakeNative(current = ottai)
        CurrentSensorSelection.syncNativeToManaged(ottai, native)
        assertTrue(native.writes.isEmpty())
    }

    @Test
    fun sync_ignoresStaleManagedIdThatIsNotTheResolvedMain() {
        ManagedCurrentSensor.set(ottai)
        val native = FakeNative(current = aidex)
        CurrentSensorSelection.syncNativeToManaged(aidex, native)
        assertTrue(native.writes.isEmpty())
    }

    @Test
    fun selectDuringSync_userSelectionWins() {
        ManagedCurrentSensor.set(ottai)
        val syncInLookup = CountDownLatch(1)
        val releaseSync = CountDownLatch(1)
        val native = object : FakeNative(current = null) {
            override fun mirrorName(sensorId: String): String? {
                if (Thread.currentThread().name == "sync") {
                    syncInLookup.countDown()
                    releaseSync.await(5, TimeUnit.SECONDS)
                }
                return super.mirrorName(sensorId)
            }
        }

        val sync = Thread({ CurrentSensorSelection.syncNativeToManaged(ottai, native) }, "sync")
        sync.start()
        assertTrue(syncInLookup.await(5, TimeUnit.SECONDS))

        // The user picks AiDex while the sync has read Ottai but not yet written it.
        val select = Thread({ CurrentSensorSelection.select(aidex, native) }, "select")
        select.start()
        select.join(300)
        releaseSync.countDown()
        sync.join(5_000)
        select.join(5_000)

        assertNull(ManagedCurrentSensor.get())
        assertEquals(aidex, native.current)
    }
}
