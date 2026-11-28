package tk.glucodata.drivers.libre3

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.VirtualGlucoseSensorBridge.Reading

/** Hand-over from the removed proprietary Libre 3 driver, and minute vs. five-minute merging. */
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

    @Test fun zeroTimeTakeoverUsesAnExistingActivationOrWaitsForTheFirstLifeCount() {
        val noReportedTime = Libre3Nfc.ActivationResult.Success("AA:BB:CC:DD:EE:FF", ByteArray(4), 1_800_000_000, false)
        val a8 = Libre3Nfc.CMD_SWITCH_RECEIVER
        val a0 = Libre3Nfc.CMD_ACTIVATE
        assertEquals(1_799_000_000_000L, Libre3ManagedNfc.pairingStartTimeMs(a8, noReportedTime, 1_799_000_000_000L) { error("already known") })
        assertEquals(1_799_000_000_000L, Libre3ManagedNfc.pairingStartTimeMs(a8, noReportedTime, null) { 1_799_000_000_000L })
        assertEquals(0L, Libre3ManagedNfc.pairingStartTimeMs(a8, noReportedTime, null) { 0L })
        assertEquals(1_800_000_000_000L, Libre3ManagedNfc.pairingStartTimeMs(a0, noReportedTime, null) { error("new activation") })
    }

    @Test fun takeoverRetiresTheExactLegacyCallbackAfterAddingTheSuccessor() {
        val nfc = src("Common/src/main/java/tk/glucodata/drivers/libre3/Libre3ManagedNfc.kt")
        val add = nfc.substringAfter("fun addSensor(").substringBefore("internal fun isLegacyCallbackName")
        assertTrue(add.indexOf("SensorBluetooth.gattcallbacks.add(callback)") < add.lastIndexOf("finishLegacyHandover(legacy, handover, id)"))
        assertTrue(nfc.contains("SensorBluetooth.retireCallback(cb)"))
        assertFalse(nfc.contains("SensorBluetooth.sensorEnded("))
    }

    @Test fun libre3HasNoProprietaryPathLeft() {
        val scan = src("Common/src/main/java/tk/glucodata/ScanNfcV.java")
        val libre3 = scan.substringAfter("static private int[] libre3scan(").substringBefore("static public synchronized void scan(")
        assertTrue(libre3.contains("Libre3ManagedNfc.scanForUi(tag)"))
        // A Libre 3 tag never waits for an imported vendor library.
        assertTrue(scan.contains("!tk.glucodata.drivers.libre3.Libre3ManagedNfc.isLibre3Tag(tag) && !Natives.gethaslibrary()"))
        for (gone in listOf("Common/src/libre3", "Common/src/main/cpp/libre3/loadlibs.cpp", "Common/src/main/cpp/libre3/bluetooth.cpp")) {
            assertFalse(gone, File(root(), gone).exists())
        }
        val inputs = src("scripts/dist/build-inputs.json")
        val active = inputs.substringBefore("\"removedFiles\"")
        for (lib in listOf("liblibre3extension.so", "libcrl_dp.so", "libinit.so")) {
            assertFalse(lib, active.contains(lib))
            assertTrue(lib, inputs.substringAfter("\"removedFiles\"").contains(lib))
        }
        // The roster builds the Kotlin driver where the proprietary one used to start.
        val blue = src("Common/src/main/java/tk/glucodata/SensorBluetooth.java")
        assertTrue(blue.contains("Libre3Registry.adoptLegacySensor(Applic.app, name, dataptr)"))
    }

    @Test fun legacySerialComesFromTheNativeName() {
        assertEquals("0RRC989AQ", Libre3Registry.legacySerial("E07A-XX0RRC989AQ", "XX0RRC989AQ"))
        assertEquals("0RRC989AQ", Libre3Registry.legacySerial("XX0RRC989AQ", "XX0RRC989AQ"))
        assertEquals("0RRC989AQ", Libre3Registry.legacySerial("odd", "ab0RRC989AQ"))
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
