package tk.glucodata

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins how each payload family treats a version it does not expect
 * (direction.md §6 Q2: "define what happens with an unknown message").
 *
 * There are three version carriers in the protocol and they do not agree:
 *
 * - the text payloads ([WearPrefsSync], [WearToggleSync], [GlucoseColorSync]) carry a
 *   `v:<n>` line and go through [WearProtocol.accepts], so a payload with no version
 *   (an older build) and an older version are both applied, and only a *newer* one is
 *   refused;
 * - the sensor-ownership message carries its own version byte and accepts that one
 *   value only, so an older or absent version is dropped;
 * - the handoff payload is JSON with a `version` key, read the same way, and a payload
 *   without the key reads as version 0 and is dropped.
 *
 * The ownership rules are pinned here because they are the surprising ones: the binary
 * families are not backward compatible, so a version bump breaks the pair silently in
 * both directions instead of degrading. Relaxing that is a behaviour change on which
 * device reads a sensor, so it is a maintainer decision (plan §8.3), not a refactor to
 * slip in here.
 *
 * The JSON family is deliberately not covered: under plain JVM unit tests
 * `returnDefaultValues = true` makes every `org.json` call answer 0/false, so a
 * rejection case would pass for the wrong reason.
 */
class WearPayloadVersionPolicyTests {
    private val serial = "SIBI:0123456789ABCDEF"

    /** The layout [SensorOwnershipRuntime] writes: version, owns, len, serial, cursor. */
    private fun ownershipPayload(
        version: Int,
        owns: Boolean = true,
        sensor: String = serial,
        cursor: Long = 1234L,
    ): ByteArray {
        val serialBytes = sensor.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(3 + serialBytes.size + 8)
            .put(version.toByte())
            .put(if (owns) 1 else 0)
            .put(serialBytes.size.toByte())
            .put(serialBytes)
            .putLong(cursor)
            .array()
    }

    @Test
    fun theOwnershipPayloadAtItsOwnVersionIsRead() {
        assertEquals(
            Triple(serial, true, 1234L),
            SensorOwnershipRuntime.decode(ownershipPayload(1)),
        )
    }

    @Test
    fun anOwnershipPayloadFromANewerBuildIsDropped() {
        assertNull(SensorOwnershipRuntime.decode(ownershipPayload(2)))
    }

    @Test
    fun anOwnershipPayloadFromAnOlderBuildIsAlsoDropped() {
        // The counterpart of WearProtocol.accepts(null), which does accept a payload
        // with no version. The binary family does not, and that difference is the
        // thing a future version bump has to reckon with.
        assertNull(SensorOwnershipRuntime.decode(ownershipPayload(0)))
    }

    @Test
    fun aTruncatedOwnershipPayloadIsDropped() {
        assertNull(SensorOwnershipRuntime.decode(ownershipPayload(1).copyOfRange(0, 6)))
    }

    @Test
    fun aTextPayloadWithNoVersionIsStillAccepted() {
        // The same situation, the other policy: a legacy text payload keeps working.
        assertEquals(true, WearProtocol.accepts(WearProtocol.declaredVersion("i:showscan=1\n")))
        assertEquals(false, WearProtocol.accepts(WearProtocol.declaredVersion("v:99\ni:showscan=1\n")))
    }
}
