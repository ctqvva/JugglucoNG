package tk.glucodata.drivers.libre3

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.VirtualGlucoseSensorBridge.Reading

/** Hand-over between the legacy and Kotlin Libre 3 drivers, and minute vs. five-minute merging. */
class Libre3HandoverTest {
    private fun root(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "Common/src/main/cpp/g.cpp").isFile }

    private fun src(path: String) = File(root(), path).readText()

    @Test fun legacyCallbackIsFoundByItsShortNativeName() {
        val serial = "0D1234ABC"
        assertEquals("XX0D1234ABC", Libre3IdentityAdapter.legacyShortName(serial))
        val noResolve: (String) -> String? = { null }
        // Running legacy callbacks carry getSensorName(): the last 11 characters.
        assertTrue(Libre3Registry.isLegacyCallbackName("XX0D1234ABC", serial, noResolve))
        assertTrue(Libre3Registry.isLegacyCallbackName("E07A-XX0D1234ABC", serial, noResolve))
        assertTrue(Libre3Registry.isLegacyCallbackName("alias", serial) { "E07A-XX0D1234ABC" })
        assertFalse(Libre3Registry.isLegacyCallbackName("XX0D1234ABD", serial, noResolve))
        // The managed sensor's own id is never mistaken for the legacy one.
        assertFalse(Libre3Registry.isLegacyCallbackName(serial, serial, noResolve))
    }

    @Test fun takeoverRetiresTheExactLegacyCallbackAfterAddingTheSuccessor() {
        val nfc = src("Common/src/main/java/tk/glucodata/drivers/libre3/Libre3ManagedNfc.kt")
        val add = nfc.substringAfter("fun addSensor(").substringBefore("internal fun isLegacyCallbackName")
        assertTrue(add.indexOf("SensorBluetooth.gattcallbacks.add(callback)") < add.indexOf("retireLegacyCallbacks(legacy, id)\n        if (SensorBluetooth.blueone === blue)"))
        assertTrue(nfc.contains("SensorBluetooth.retireCallback(cb, successor)"))
        assertFalse(nfc.contains("SensorBluetooth.sensorEnded("))
    }

    @Test fun legacyScanRetiresTheKotlinDriverBeforeAddingItsOwn() {
        val scan = src("Common/src/main/java/tk/glucodata/ScanNfcV.java")
        val retire = scan.indexOf("Libre3Registry.retireManagedForLegacyScan(main, name)")
        assertTrue(retire > 0 && retire < scan.indexOf("SensorBluetooth.resetDeviceOrFree(streamptr, name)"))
        val nfc = src("Common/src/main/java/tk/glucodata/drivers/libre3/Libre3ManagedNfc.kt")
        val body = nfc.substringAfter("fun retireManagedForLegacyScan(")
        // Terminates the link, drops the pairing so a restart does not rebuild it, retires the object.
        assertTrue(body.contains("terminateManagedSensor(wipeData = false)"))
        assertTrue(body.contains("Libre3Persistence.removeSensor(context, serial)"))
        assertTrue(body.contains("SensorBluetooth.retireCallback(it, name)"))
    }

    private fun r(t: Long, v: Float) = Reading(timestampMs = t, glucoseMgdl = v)

    @Test fun minuteReplacesItsOwnFiveMinuteFillIn() {
        val filler = 1_000_000L
        val plan = Libre3BackfillMerge.plan(listOf(r(filler + 2_000, 120f)), longArrayOf(filler), setOf(filler), 50_000L)
        assertEquals(listOf(r(filler, 120f)), plan.replacing) // re-stamped onto the fill-in's minute
        assertTrue(plan.regular.isEmpty())
        assertEquals(setOf(filler), plan.consumedFillers)
    }

    @Test fun existingClinicalOrLiveReadingsArePreserved() {
        val live = 2_000_000L
        val filler = 3_000_000L
        val plan = Libre3BackfillMerge.plan(
            listOf(r(live + 1_000, 100f), r(filler + 1_000, 110f), r(4_000_000L, 90f)),
            longArrayOf(live, filler, filler + 30_000), // a non-filler sits next to the filler too
            setOf(filler),
            50_000L,
        )
        assertTrue(plan.replacing.isEmpty())
        assertEquals(3, plan.regular.size) // left to the near-duplicate filter, which keeps the stored rows
    }

    @Test fun fillInSetRoundTripsAndIsCapped() {
        assertEquals(setOf(1L, 2L), Libre3BackfillMerge.decode(Libre3BackfillMerge.encode(listOf(2L, 1L))))
        assertEquals(setOf(9L, 10L), Libre3BackfillMerge.decode(Libre3BackfillMerge.encode((1L..10L).toList(), cap = 2)))
        assertTrue(Libre3BackfillMerge.decode(null).isEmpty())
    }
}
