package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.ui.overlay.FloatingLayoutBlock
import tk.glucodata.ui.overlay.FloatingLayoutItem.ARROW
import tk.glucodata.ui.overlay.FloatingLayoutItem.CUTOUT
import tk.glucodata.ui.overlay.FloatingLayoutItem.SECONDARY
import tk.glucodata.ui.overlay.FloatingLayoutItem.VALUE
import tk.glucodata.ui.overlay.FloatingLayoutOrder
import tk.glucodata.ui.overlay.FloatingLayoutSides
import tk.glucodata.ui.overlay.floatingLayoutBlocks

/** The floating glucose's arranged order (#981): storage, moves and what each side draws. */
class FloatingLayoutOrderTests {

    @Test
    fun noStoredOrderIsTheLayoutBefore981() {
        assertEquals(listOf(VALUE, SECONDARY, CUTOUT, ARROW), FloatingLayoutOrder.parse(null))
        assertEquals(FloatingLayoutOrder.DEFAULT, FloatingLayoutOrder.parse(""))
        assertEquals(
            FloatingLayoutSides(before = listOf(VALUE, SECONDARY), after = listOf(ARROW)),
            FloatingLayoutOrder.sides(FloatingLayoutOrder.DEFAULT)
        )
    }

    @Test
    fun storedOrderRoundTrips() {
        val inverted = listOf(ARROW, CUTOUT, VALUE, SECONDARY)
        assertEquals(inverted, FloatingLayoutOrder.parse(FloatingLayoutOrder.serialize(inverted)))
    }

    @Test
    fun damagedOrderIsRepaired() {
        // Unknown and repeated names dropped; missing ones back beside their default neighbour.
        assertEquals(listOf(ARROW, VALUE, SECONDARY, CUTOUT), FloatingLayoutOrder.parse("ARROW,VALUE,BOGUS,ARROW"))
        assertEquals(listOf(VALUE, SECONDARY, CUTOUT, ARROW), FloatingLayoutOrder.parse("CUTOUT,ARROW"))
    }

    @Test
    fun islandOrderMovesTheCameraToo() {
        // Dragging the arrow above the camera puts it on the left (#981's inverted layout).
        assertEquals(
            listOf(VALUE, SECONDARY, ARROW, CUTOUT),
            FloatingLayoutOrder.movedVisible(FloatingLayoutOrder.DEFAULT, island = true, from = 3, to = 2)
        )
    }

    @Test
    fun pillOrderKeepsTheHiddenCameraWhereItWas() {
        // The pill list is VALUE, SECONDARY, ARROW. The camera stays after the secondary
        // value, so moving the arrow to the front puts only the arrow on the left in island mode.
        assertEquals(listOf(VALUE, SECONDARY, ARROW), FloatingLayoutOrder.visible(FloatingLayoutOrder.DEFAULT, island = false))
        assertEquals(
            listOf(ARROW, VALUE, SECONDARY, CUTOUT),
            FloatingLayoutOrder.movedVisible(FloatingLayoutOrder.DEFAULT, island = false, from = 2, to = 0)
        )
        // Moving the reading itself: the secondary value it left behind keeps the camera after it.
        assertEquals(
            listOf(SECONDARY, CUTOUT, ARROW, VALUE),
            FloatingLayoutOrder.movedVisible(FloatingLayoutOrder.DEFAULT, island = false, from = 0, to = 2)
        )
    }

    @Test
    fun outOfRangeMovesChangeNothing() {
        assertEquals(FloatingLayoutOrder.DEFAULT, FloatingLayoutOrder.moved(FloatingLayoutOrder.DEFAULT, 0, 9))
        assertEquals(FloatingLayoutOrder.DEFAULT, FloatingLayoutOrder.movedVisible(FloatingLayoutOrder.DEFAULT, false, 3, 0))
    }

    @Test
    fun aSideCanBeEmpty() {
        assertEquals(
            FloatingLayoutSides(before = emptyList(), after = listOf(ARROW, VALUE, SECONDARY)),
            FloatingLayoutOrder.sides(listOf(CUTOUT, ARROW, VALUE, SECONDARY))
        )
    }

    @Test
    fun readingAndAdjacentSecondaryShareOneBlock() {
        assertEquals(
            listOf(FloatingLayoutBlock.Reading(withSecondary = true, secondaryFirst = false), FloatingLayoutBlock.Arrow),
            floatingLayoutBlocks(listOf(VALUE, SECONDARY, CUTOUT, ARROW), showSecondary = true, showArrow = true)
        )
        assertEquals(
            listOf(FloatingLayoutBlock.Reading(withSecondary = true, secondaryFirst = true)),
            floatingLayoutBlocks(listOf(SECONDARY, VALUE), showSecondary = true, showArrow = true)
        )
    }

    @Test
    fun separatedSecondaryIsItsOwnBlock() {
        assertEquals(
            listOf(FloatingLayoutBlock.Reading(false, false), FloatingLayoutBlock.Arrow, FloatingLayoutBlock.Secondary),
            floatingLayoutBlocks(listOf(VALUE, ARROW, SECONDARY), showSecondary = true, showArrow = true)
        )
        // A hidden arrow between them leaves them side by side again.
        assertEquals(
            listOf(FloatingLayoutBlock.Reading(withSecondary = true, secondaryFirst = false)),
            floatingLayoutBlocks(listOf(VALUE, ARROW, SECONDARY), showSecondary = true, showArrow = false)
        )
    }

    @Test
    fun hiddenItemsDrawNothing() {
        assertEquals(
            listOf(FloatingLayoutBlock.Reading(false, false)),
            floatingLayoutBlocks(FloatingLayoutOrder.DEFAULT, showSecondary = false, showArrow = false)
        )
    }
}
