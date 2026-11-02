package tk.glucodata.drivers.icanhealth

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ICanHealthProvisioningPolicyTests {
    private val policy = ICanHealthProvisioningPolicy
    private val box = "ABC0000005900"

    @Test fun installedSensorsStayOutsideProvisioningForEveryIdentityShape() {
        val identities = listOf("", "ABC00000", "ABC000000", "726022F50005", box)
        for (marker in listOf(0, 500, 590, 65534)) {
            identities.forEach { serial ->
                assertEquals(ICanHealthProvisioningPolicy.Eligibility.INSTALLED_TABLE, policy.eligibility(marker, serial))
            }
        }
    }

    @Test fun legacyConnectionIdentityDoesNotBecomeAnInventedFactorySerial() {
        for (serial in listOf("", "ABC00000", "ABC000000", "726022F50005", "ABC000000XX00")) {
            assertNull(policy.factoryCode(serial))
            assertEquals(ICanHealthProvisioningPolicy.Eligibility.INVALID_FACTORY_SERIAL, policy.eligibility(0xffff, serial))
        }
        val shortened = ICanHealthConstants.normalizeOnboardingDeviceSn("ABC0000005900ACTIVATION")
        assertTrue(shortened.length in 8..9)
        assertEquals(ICanHealthProvisioningPolicy.Eligibility.INVALID_FACTORY_SERIAL, policy.eligibility(0xffff, shortened))
        assertEquals(ICanHealthProvisioningPolicy.Eligibility.FRESH, policy.eligibility(0xffff, box))
        assertEquals(ICanHealthProvisioningPolicy.Eligibility.UNKNOWN_SENSOR, policy.eligibility(null, box))
    }

    @Test fun everyDecimalFactoryCodeQuantizesExactly() {
        for (n in 0..99) {
            val serial = "ABC000000%02d00".format(n)
            assertEquals(n * 10, (policy.factoryCode(serial)!! * 1000f).toInt())
        }
    }

    @Test fun realBoxLabelsGiveTheirFactoryScale() {
        // Pins only what we write. 84F01FF13594A read back F6 0x024E (590) and the vendor SDK log
        // shows x13 = 0.59, but that sensor's readings still ran about twice too high, so this
        // does not show the rule yields correct glucose. The other labels are unconfirmed.
        val labels = mapOf(
            "84F01FF13594A" to 590,
            "937001AEB546A" to 540,
            "92602F429575A" to 570,
            "926030609535A" to 530,
            "77E0067C2643A" to 640,
            "7BF00099C663A" to 660,
            "80201D2BE443A" to 440,
        )
        for ((serial, f6) in labels) {
            assertEquals(serial, f6, (policy.factoryCode(serial)!! * 1000f).toInt())
            assertEquals(ICanHealthProvisioningPolicy.Eligibility.FRESH, policy.eligibility(0xffff, serial))
        }
    }

    @Test fun genericRowsCannotEnterThroughResponseCacheFileOrBuilder() {
        for (code in listOf(0f, .4f, .59f, .99f)) {
            val table = policy.defaultTable.toMutableMap().apply {
                put("x13", code)
                put("x16", 99f) // Not transmitted: changing it cannot make the wire table genuine.
            }
            val json = JSONObject(table as Map<*, *>).toString()
            assertNotNull(ICanHealthCloudCodeProtocol.parseCodeFloats(json))
            assertTrue(policy.isGenericTable(table))
            assertNull(ICanHealthCloudCodeProtocol.parseProvisioningCode(json))
            assertNull(ICanHealthCloudCode.parseTableFileContent(json))
            assertNull(ICanHealthCloudCode.parseTableFileContent(JSONObject().put("code", 200)
                .put("data", JSONObject().put("code", json)).toString()))
            assertNull(ICanHealthCorrectCode.buildCorrectCodeChunks(table, byteArrayOf(1, 2, 3, 4), box))
        }
    }

    @Test fun genuineNumericRowsSurviveAllInputGuards() {
        val table = policy.defaultTable.toMutableMap().apply { put("x8", .5f) }
        val json = JSONObject(table as Map<*, *>).toString()
        assertFalse(policy.isGenericTable(table))
        assertNotNull(ICanHealthCloudCodeProtocol.parseProvisioningCode(json))
        assertNotNull(ICanHealthCloudCode.parseTableFileContent(json))
        assertNotNull(ICanHealthCorrectCode.buildCorrectCodeChunks(table, byteArrayOf(1, 2, 3, 4), box))
    }

    @Test fun canceledOrSupersededWorkersCannotCommitOrRestartRetries() {
        val ownership = ICanHealthCloudFetchOwnership()
        val first = ownership.begin(box)
        assertSame(first, ownership.begin(box))
        ownership.cancel(box) // A positive F6 or sensor removal cancels this lookup.
        var commits = 0
        assertFalse(ownership.commitIfCurrent(first) { commits++; true })
        assertFalse(ownership.current(first))
        val next = ownership.begin(box)
        assertFalse(ownership.current(first))
        assertFalse(ownership.commitIfCurrent(first) { commits++; true })
        assertTrue(ownership.commitIfCurrent(next) { commits++; true })
        assertEquals(1, commits)
    }

    @Test fun repairWritesAreBoundedAndOnlyFollowASilentInstalledSensor() {
        val installed = 0x0190
        // No table, or an unknown marker: the normal FRESH path owns those sensors.
        assertEquals(
            ICanHealthProvisioningPolicy.RepairDecision.NONE,
            policy.repairDecision(0xffff, sensorSilentMs = 60 * 60_000L, sinceLastWriteMs = Long.MAX_VALUE, attempts = 0),
        )
        assertEquals(
            ICanHealthProvisioningPolicy.RepairDecision.NONE,
            policy.repairDecision(null, sensorSilentMs = 60 * 60_000L, sinceLastWriteMs = Long.MAX_VALUE, attempts = 0),
        )
        // Still producing, or written moments ago: leave it alone.
        assertEquals(
            ICanHealthProvisioningPolicy.RepairDecision.NONE,
            policy.repairDecision(installed, sensorSilentMs = 5 * 60_000L, sinceLastWriteMs = 60 * 60_000L, attempts = 0),
        )
        assertEquals(
            ICanHealthProvisioningPolicy.RepairDecision.NONE,
            policy.repairDecision(installed, sensorSilentMs = 60 * 60_000L, sinceLastWriteMs = 60_000L, attempts = 0),
        )
        // Silent well past the cooldown with budget left: rewrite.
        assertEquals(
            ICanHealthProvisioningPolicy.RepairDecision.RETRY,
            policy.repairDecision(installed, sensorSilentMs = 15 * 60_000L, sinceLastWriteMs = 30 * 60_000L, attempts = 1),
        )
        // Budget exhausted: stop, and say so once.
        assertEquals(
            ICanHealthProvisioningPolicy.RepairDecision.EXHAUSTED,
            policy.repairDecision(
                installed,
                sensorSilentMs = 60 * 60_000L,
                sinceLastWriteMs = 60 * 60_000L,
                attempts = ICanHealthProvisioningPolicy.MAX_REPAIR_ATTEMPTS,
            ),
        )
        assertEquals(
            ICanHealthProvisioningPolicy.RepairDecision.EXHAUSTED,
            policy.repairDecision(installed, sensorSilentMs = 60 * 60_000L, sinceLastWriteMs = 60 * 60_000L, attempts = 9),
        )
    }

    @Test fun repairWritesAfterTheFirstOneDoNotNeedAPendingFetch() {
        // A stalled sensor whose first write landed: the follow-up copy still needs a real table,
        // and the per-SN cache is the only source allowed here.
        val installed = 0x0190
        assertEquals(
            ICanHealthProvisioningPolicy.RepairDecision.RETRY,
            policy.repairDecision(
                installedMarker = installed,
                sensorSilentMs = ICanHealthProvisioningPolicy.REPAIR_STALL_MS,
                sinceLastWriteMs = ICanHealthProvisioningPolicy.REPAIR_COOLDOWN_MS,
                attempts = 0,
            ),
        )
    }

    @Test fun negativeF2RepliesAreRecognizedImmediately() {
        assertEquals(1, ICanHealthParser.parseCorrectCodeResult(byteArrayOf(0x1c, 0xf2.toByte(), 1)))
        assertEquals(2, ICanHealthParser.parseCorrectCodeResult(byteArrayOf(0x1c, 0xf2.toByte(), 2)))
        assertEquals(255, ICanHealthParser.parseCorrectCodeResult(byteArrayOf(0x1c, 0xf2.toByte(), -1)))
        assertNull(ICanHealthParser.parseCorrectCodeResult(byteArrayOf(0x1c, 0xf2.toByte())))
        assertNull(ICanHealthParser.parseCorrectCodeResult(byteArrayOf(0x1c, 0xf6.toByte(), 1)))
    }
}
