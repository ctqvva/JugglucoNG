package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class WearPrefsDeliveryTests {
    @Test
    fun aLostBroadcastAndLostFirstReplyDoNotSuppressTheNextRequest() {
        val delivered = mutableListOf<String>()
        var reachable = false
        val delivery = WearPrefsDelivery(
            encode = { "hide:B".toByteArray() },
            send = { node, payload ->
                if (reachable) delivered += "$node:${payload.decodeToString()}"
            },
        )

        delivery.push() // No watch in reach.
        delivery.pushTo("watch") // Request arrived, but the reply was lost.
        reachable = true
        delivery.pushTo("watch")

        assertEquals(listOf("watch:hide:B"), delivered)
    }

    @Test
    fun replyingToOneWatchDoesNotSuppressAnIdenticalReplyToAnother() {
        val targets = mutableListOf<String?>()
        val delivery = WearPrefsDelivery({ byteArrayOf(1) }) { node, _ -> targets += node }

        delivery.pushTo("watch-a")
        delivery.pushTo("watch-b")
        delivery.pushTo("watch-a")

        assertEquals(listOf("watch-a", "watch-b", "watch-a"), targets)
    }

    @Test
    fun aRequestAfterAnEditEncodesTheCurrentSnapshot() {
        var selected = "A,B"
        val received = mutableListOf<String>()
        val delivery = WearPrefsDelivery({ selected.toByteArray() }) { _, payload ->
            received += payload.decodeToString()
        }

        delivery.pushTo("watch")
        selected = "A"
        delivery.pushTo("watch")

        assertEquals(listOf("A,B", "A"), received)
    }

    @Test
    fun anUnavailableSnapshotOrMissingTargetSendsNothing() {
        var sends = 0
        val delivery = WearPrefsDelivery({ byteArrayOf() }) { _, _ -> sends++ }

        delivery.push()
        delivery.pushTo("watch")
        delivery.pushTo(null)

        assertEquals(0, sends)
    }
}
