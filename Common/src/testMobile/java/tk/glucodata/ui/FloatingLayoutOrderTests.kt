package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.ui.overlay.FloatingLayoutBlock
import tk.glucodata.ui.overlay.FloatingLayoutItem.ARROW
import tk.glucodata.ui.overlay.FloatingLayoutItem.CUTOUT
import tk.glucodata.ui.overlay.FloatingLayoutItem.DELTA
import tk.glucodata.ui.overlay.FloatingLayoutItem.SECONDARY
import tk.glucodata.ui.overlay.FloatingLayoutItem.VALUE
import tk.glucodata.ui.overlay.FloatingLayoutOrder
import tk.glucodata.ui.overlay.FloatingLayoutSides
import tk.glucodata.ui.overlay.floatingLayoutBlocks

/** The floating glucose's arranged order (#981): storage, moves and what each side draws. */
class FloatingLayoutOrderTests {

    @Test
    fun noStoredOrderIsTheLayoutBefore981() {
        assertEquals(listOf(VALUE, SECONDARY, DELTA, CUTOUT, ARROW), FloatingLayoutOrder.parse(null))
        assertEquals(FloatingLayoutOrder.DEFAULT, FloatingLayoutOrder.parse(""))
        assertEquals(
            FloatingLayoutSides(before = listOf(VALUE, SECONDARY, DELTA), after = listOf(ARROW)),
            FloatingLayoutOrder.sides(FloatingLayoutOrder.DEFAULT)
        )
    }

    @Test
    fun storedOrderRoundTrips() {
        val inverted = listOf(ARROW, DELTA, CUTOUT, VALUE, SECONDARY)
        assertEquals(inverted, FloatingLayoutOrder.parse(FloatingLayoutOrder.serialize(inverted)))
    }

    @Test
    fun damagedOrderIsRepaired() {
        // Unknown and repeated names dropped; missing ones back beside their default neighbour.
        assertEquals(listOf(ARROW, VALUE, SECONDARY, DELTA, CUTOUT), FloatingLayoutOrder.parse("ARROW,VALUE,BOGUS,ARROW"))
        assertEquals(listOf(VALUE, SECONDARY, DELTA, CUTOUT, ARROW), FloatingLayoutOrder.parse("CUTOUT,ARROW"))
    }

    @Test
    fun orderStoredBeforeTheDeltaRowKeepsItUnderTheReading() {
        assertEquals(listOf(ARROW, CUTOUT, VALUE, SECONDARY, DELTA), FloatingLayoutOrder.parse("ARROW,CUTOUT,VALUE,SECONDARY"))
        assertEquals(listOf(CUTOUT, VALUE, SECONDARY, DELTA, ARROW), FloatingLayoutOrder.parse("CUTOUT,VALUE,SECONDARY,ARROW"))
    }

    @Test
    fun islandOrderMovesTheCameraToo() {
        // Dragging the arrow above the camera puts it on the left (#981's inverted layout).
        assertEquals(
            listOf(VALUE, SECONDARY, DELTA, ARROW, CUTOUT),
            FloatingLayoutOrder.movedVisible(FloatingLayoutOrder.DEFAULT, island = true, from = 4, to = 3)
        )
    }

    @Test
    fun pillOrderKeepsTheHiddenCameraWhereItWas() {
        // The pill list is VALUE, SECONDARY, DELTA, ARROW. The camera stays after the delta,
        // so moving the arrow to the front puts only the arrow on the left in island mode.
        assertEquals(listOf(VALUE, SECONDARY, DELTA, ARROW), FloatingLayoutOrder.visible(FloatingLayoutOrder.DEFAULT, island = false))
        assertEquals(
            listOf(ARROW, VALUE, SECONDARY, DELTA, CUTOUT),
            FloatingLayoutOrder.movedVisible(FloatingLayoutOrder.DEFAULT, island = false, from = 3, to = 0)
        )
        // Moving the delta itself: the secondary value it left behind keeps the camera after it.
        assertEquals(
            listOf(VALUE, SECONDARY, CUTOUT, ARROW, DELTA),
            FloatingLayoutOrder.movedVisible(FloatingLayoutOrder.DEFAULT, island = false, from = 2, to = 3)
        )
    }

    @Test
    fun outOfRangeMovesChangeNothing() {
        assertEquals(FloatingLayoutOrder.DEFAULT, FloatingLayoutOrder.moved(FloatingLayoutOrder.DEFAULT, 0, 9))
        assertEquals(FloatingLayoutOrder.DEFAULT, FloatingLayoutOrder.movedVisible(FloatingLayoutOrder.DEFAULT, false, 4, 0))
    }

    @Test
    fun aSideCanBeEmpty() {
        assertEquals(
            FloatingLayoutSides(before = emptyList(), after = listOf(ARROW, VALUE, SECONDARY)),
            FloatingLayoutOrder.sides(listOf(CUTOUT, ARROW, VALUE, SECONDARY))
        )
    }

    private fun blocks(
        items: List<tk.glucodata.ui.overlay.FloatingLayoutItem>,
        secondary: Boolean = true,
        arrow: Boolean = true,
        delta: Boolean = true,
    ) = floatingLayoutBlocks(items, showSecondary = secondary, showArrow = arrow, showDelta = delta)

    @Test
    fun readingAndAdjacentSecondaryShareOneBlock() {
        assertEquals(
            listOf(FloatingLayoutBlock.Reading(withSecondary = true, secondaryFirst = false), FloatingLayoutBlock.Arrow()),
            blocks(listOf(VALUE, SECONDARY, CUTOUT, ARROW))
        )
        assertEquals(
            listOf(FloatingLayoutBlock.Reading(withSecondary = true, secondaryFirst = true)),
            blocks(listOf(SECONDARY, VALUE))
        )
    }

    @Test
    fun separatedSecondaryIsItsOwnBlock() {
        assertEquals(
            listOf(FloatingLayoutBlock.Reading(false, false), FloatingLayoutBlock.Arrow(), FloatingLayoutBlock.Secondary()),
            blocks(listOf(VALUE, ARROW, SECONDARY))
        )
        // A hidden arrow between them leaves them side by side again.
        assertEquals(
            listOf(FloatingLayoutBlock.Reading(withSecondary = true, secondaryFirst = false)),
            blocks(listOf(VALUE, ARROW, SECONDARY), arrow = false)
        )
    }

    @Test
    fun deltaGoesUnderTheBlockBeforeIt() {
        // The default: under the reading and its secondary value, as before #981.
        assertEquals(
            listOf(FloatingLayoutBlock.Reading(withSecondary = true, secondaryFirst = false, withDelta = true)),
            blocks(listOf(VALUE, SECONDARY, DELTA))
        )
        assertEquals(
            listOf(FloatingLayoutBlock.Reading(true, false), FloatingLayoutBlock.Arrow(withDelta = true)),
            blocks(listOf(VALUE, SECONDARY, ARROW, DELTA))
        )
        // Between the reading and the secondary value it parts them and goes under the reading.
        assertEquals(
            listOf(FloatingLayoutBlock.Reading(false, false, withDelta = true), FloatingLayoutBlock.Secondary()),
            blocks(listOf(VALUE, DELTA, SECONDARY))
        )
    }

    @Test
    fun deltaLeadingItsSideGoesUnderTheBlockAfterIt() {
        assertEquals(
            listOf(FloatingLayoutBlock.Arrow(withDelta = true)),
            blocks(FloatingLayoutOrder.sides(listOf(DELTA, ARROW, CUTOUT, VALUE, SECONDARY)).before)
        )
    }

    @Test
    fun deltaAloneOnItsSideIsItsOwnBlock() {
        assertEquals(listOf(FloatingLayoutBlock.Delta), blocks(listOf(DELTA)))
        // A hidden arrow leaves it alone too.
        assertEquals(listOf(FloatingLayoutBlock.Delta), blocks(listOf(ARROW, DELTA), arrow = false))
    }

    @Test
    fun hiddenItemsDrawNothing() {
        assertEquals(
            listOf(FloatingLayoutBlock.Reading(false, false)),
            blocks(FloatingLayoutOrder.DEFAULT, secondary = false, arrow = false, delta = false)
        )
    }
}
