package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #715: a Clone receiver could not pick up a sensor the sender stopped reading,
 * because every mirrored record was barred from local Bluetooth for as long as
 * it carried the Clone flag. Taking over moves the flag aside per sensor and
 * handing back restores it with the route it had.
 */
class CloneTakeOverPolicyTests {
    private val libreAliases = setOf("3MH00ABCDEF", "00ABCDEF")
    private fun matchesLibre(key: String) = key in libreAliases

    @Test
    fun takingOverClearsEveryAliasOfTheCloneFlagAndKeepsOthers() {
        val before = CloneTakeOverPolicy.Entries(
            clone = mapOf(
                "3MH00ABCDEF" to CloneTransport.TURN,
                "00ABCDEF" to CloneTransport.TURN,
                "X-OTHER" to CloneTransport.LOCAL_ICE,
            ),
            takenOver = emptyMap(),
        )

        val after = CloneTakeOverPolicy.takeOver(before, libreAliases, ::matchesLibre)

        assertEquals(mapOf("X-OTHER" to CloneTransport.LOCAL_ICE), after.clone)
        assertEquals(libreAliases.associateWith { CloneTransport.TURN }, after.takenOver)
        assertTrue(CloneTakeOverPolicy.isTakenOver(after.takenOver, ::matchesLibre))
        assertFalse(CloneTakeOverPolicy.isTakenOver(after.takenOver) { it == "X-OTHER" })
    }

    @Test
    fun handingBackRestoresTheRouteTheSensorArrivedBy() {
        val takenOver = CloneTakeOverPolicy.takeOver(
            CloneTakeOverPolicy.Entries(
                clone = mapOf("3MH00ABCDEF" to CloneTransport.LOCAL_ICE),
                takenOver = emptyMap(),
            ),
            libreAliases,
            ::matchesLibre,
        )

        val back = CloneTakeOverPolicy.handBack(takenOver, libreAliases, ::matchesLibre)

        assertEquals(libreAliases.associateWith { CloneTransport.LOCAL_ICE }, back.clone)
        assertTrue(back.takenOver.isEmpty())
        assertFalse(CloneTakeOverPolicy.isTakenOver(back.takenOver, ::matchesLibre))
    }

    @Test
    fun aSensorNeverFlaggedStillTakesOverWithAnUnknownRoute() {
        val after = CloneTakeOverPolicy.takeOver(
            CloneTakeOverPolicy.Entries(clone = emptyMap(), takenOver = emptyMap()),
            libreAliases,
            ::matchesLibre,
        )

        assertEquals(libreAliases.associateWith { CloneTransport.UNKNOWN }, after.takenOver)
        assertTrue(after.clone.isEmpty())
    }
}
