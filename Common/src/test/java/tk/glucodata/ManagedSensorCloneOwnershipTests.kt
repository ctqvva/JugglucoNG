package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sensor this device reads over its own connection must never stay flagged as
 * a Clone, or it loses its own controls and reports the Clone link's health
 * instead of the radio actually feeding it.
 *
 * Clone ownership was originally asserted from the Libre GATT callback alone,
 * so every Kotlin managed driver left the flag in place. These are source checks
 * because [CloneSensorRegistry] reads Android preferences through `Applic.app`
 * and cannot be exercised in a local JVM test; what they pin is the boundary
 * between drivers that hold a sensor and sources that merely relay one.
 */
class ManagedSensorCloneOwnershipTests {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/CloneSensorRegistry.kt").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    private fun source(relative: String): String = File(repoRoot(), relative).readText()

    private fun flattened(relative: String): String =
        source(relative).replace(Regex("\\s+"), " ")

    /** Drivers that hold a sensor over this device's own radio. */
    private val localDrivers = listOf(
        "Common/src/main/java/tk/glucodata/drivers/sibionics/SibionicsBleManager.kt",
        "Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt",
        "Common/src/main/java/tk/glucodata/drivers/anytime/AnytimeBleManager.kt",
        "Common/src/main/java/tk/glucodata/drivers/icanhealth/ICanHealthBleManager.kt",
        "Common/src/main/java/tk/glucodata/drivers/mq/MQBleManager.kt",
        "Common/src/main/java/tk/glucodata/drivers/aidex/native/ble/AiDexBleManager.kt",
    )

    /**
     * Sources that relay someone else's reading. They subclass the same callback
     * but hold no sensor, so claiming ownership here would let a follower steal
     * a Clone record away from the device actually wearing the sensor.
     */
    private val remoteSources = listOf(
        "Common/src/main/java/tk/glucodata/drivers/nightscout/NightscoutFollowerManager.kt",
        "Common/src/main/java/tk/glucodata/drivers/mq/MQFollowerManager.kt",
        "Common/src/main/java/tk/glucodata/drivers/api/ApiGlucoseSourceManager.kt",
    )

    @Test
    fun localOwnershipSeamClearsTheCloneFlag() {
        val callback = flattened("Common/src/main/java/tk/glucodata/SuperGattCallback.java")
        val seam = callback.substring(
            callback.indexOf("protected final void markLocalReadingAccepted"),
        ).substringBefore("}")

        assertTrue(
            "markLocalReadingAccepted must clear the Clone flag for the sensor it accepted",
            seam.contains("CloneSensorRegistry.markLocalSensor(SerialNumber)"),
        )
        assertTrue(
            "a Clone import publishes through this callback too, so only a GATT this " +
                "process connected may claim local ownership",
            seam.contains("if (hasLocallyConnectedGatt())"),
        )
    }

    @Test
    fun cloneOwnershipIsNotAssertedFromTheLibrePathAlone() {
        val callback = flattened("Common/src/main/java/tk/glucodata/SuperGattCallback.java")
        val marks = Regex("CloneSensorRegistry\\.markLocalSensor").findAll(callback).count()

        // One call, inside the shared seam. Extra call sites mean a driver-specific
        // path is asserting ownership again and the seam has stopped being the truth.
        assertTrue("Expected a single markLocalSensor call site, found $marks", marks == 1)
    }

    @Test
    fun everyLocalDriverAssertsOwnershipWhenAReadingLands() {
        for (driver in localDrivers) {
            assertTrue(
                "$driver must call markLocalReadingAccepted so its sensor cannot stay a Clone",
                flattened(driver).contains("markLocalReadingAccepted("),
            )
        }
    }

    @Test
    fun relayedSourcesNeverClaimLocalOwnership() {
        for (relay in remoteSources) {
            val text = flattened(relay)
            assertFalse(
                "$relay relays a remote reading and must not claim local ownership",
                text.contains("markLocalReadingAccepted(") ||
                    text.contains("CloneSensorRegistry.markLocalSensor("),
            )
        }
    }

    /**
     * The Sibionics registry retires a SIBI: native shell that has no Kotlin record,
     * on the theory that only an old build's leftover looks like that. A sensor
     * mirrored over Clone looks exactly like that too -- its record is on the phone
     * wearing it -- and the receiver finished every mirrored Sibionics within seconds.
     */
    @Test
    fun sibionicsOrphanReconciliationSparesCloneRecords() {
        val registry = flattened("Common/src/main/java/tk/glucodata/drivers/sibionics/SibionicsRegistry.kt")
        val reconcile = registry.substring(registry.indexOf("internal fun reconcileOrphanedNativeMirrors"))
        val finish = reconcile.indexOf("finishNativeMirror(nativeId, fullNativeName)")
        val guard = reconcile.indexOf("CloneSensorRegistry.isCloneSensor(nativeId)")

        assertTrue("reconcile must consult the Clone registry", guard >= 0)
        assertTrue("and it must do so before it finishes anything", guard < finish)
    }

    /**
     * The periodic check was gated, but the restore-all sweep and the single-sensor
     * dial went straight to connectDevice, and on the receiving phone that put a
     * mirrored Sibionics into a Libre scan. One gate, asked on every path.
     */
    @Test
    fun everyLocalDialPathAsksWhetherTheSensorIsMirrored() {
        val bt = flattened("Common/src/main/java/tk/glucodata/SensorBluetooth.java")
        // checkandconnect dials through connectToActiveDevice; the other two go
        // straight to the callback.
        val dials = mapOf(
            "checkandconnect" to "connectToActiveDevice(cb, delay)",
            "connectToAllActiveDevices" to "cb.connectDevice(",
            "connectToActiveDevice" to "cb.connectDevice(",
        )
        for ((path, dial) in dials) {
            val body = bt.substring(bt.indexOf("boolean $path("))
            val gateAt = body.indexOf("mirroredOverClone(cb")
            val dialAt = body.indexOf(dial)
            assertTrue("$path must dial somewhere", dialAt > 0)
            assertTrue("$path must ask mirroredOverClone before it dials", gateAt in 0 until dialAt)
        }
    }

    /**
     * Every stream update over Clone announces its sensor to the registry. That
     * announcement used to retire every other Clone record on the receiver, so a
     * sender with two sensors had them retire each other in turn, and the loser
     * came back as a local Libre 2 the receiver tried to dial. A stream update
     * says nothing about which sensor the sender calls primary; it must retire
     * nothing.
     */
    @Test
    fun aStreamUpdateNeverRetiresTheOtherCloneSensors() {
        val registry = flattened("Common/src/main/java/tk/glucodata/CloneSensorRegistry.kt")
        val reconcile = registry.substring(
            registry.indexOf("fun reconcilePrimaryCloneSensor"),
            registry.indexOf("fun transportForSensor"),
        )
        assertFalse("reconcile must not finish native records", reconcile.contains("finishfromSensorptr"))
        assertFalse("reconcile must not retire callbacks", reconcile.contains("retireCloneSensor"))
        assertFalse("reconcile must not rewrite the registry", reconcile.contains("putString(KEY_SENSOR_IDS"))
        // The one thing it still does: adopt the first Clone sensor when none is selected.
        assertTrue(reconcile.contains("if (primaryIsClone) return"))
    }
}
