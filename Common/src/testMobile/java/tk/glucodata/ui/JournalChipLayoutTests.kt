package tk.glucodata.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalChipLayoutTests {

    private fun box(left: Float, top: Float, width: Float = 100f, height: Float = 26f) =
        JournalChipBox(left, top, left + width, top + height)

    private fun spread(boxes: List<JournalChipBox>, members: IntArray, maxX: Float = 1000f, minTop: Float = 8f, maxTop: Float = 400f) =
        JournalChipLayout.spread(
            boxes = boxes,
            members = members,
            minX = 0f,
            maxX = maxX,
            minTop = minTop,
            maxTop = maxTop,
            gapPx = 4f,
            ringStepPx = 4f,
            maxRadiusPx = 200f
        ).shifts

    private fun moved(boxes: List<JournalChipBox>, members: IntArray, shifts: Array<FloatArray>): List<JournalChipBox> {
        val result = boxes.toMutableList()
        members.forEachIndexed { slot, index ->
            val box = boxes[index]
            val (dx, dy) = shifts[slot].let { it[0] to it[1] }
            result[index] = JournalChipBox(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
        }
        return result
    }

    private fun overlapping(a: JournalChipBox, b: JournalChipBox) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    @Test
    fun theFrontChipStaysAndTheOneBehindMovesTheShortestWay() {
        // Chip 1 is the pile's front, so it keeps its place.
        val boxes = listOf(box(0f, 200f), box(5f, 188f))
        val members = intArrayOf(1, 0)

        val shifts = spread(boxes, members)

        assertArrayEquals(floatArrayOf(0f, 0f), shifts[0], 0.001f)
        val result = moved(boxes, members, shifts)
        assertTrue(!overlapping(result[0], result[1]))
        // Straight down is the shortest way clear: 188 + 26 + 4 = 218 is 18px below 200.
        assertEquals(0f, shifts[1][0], 0.5f)
        assertEquals(20f, shifts[1][1], 0.5f)
    }

    @Test
    fun theFrontStaysEvenWhenAnEdgeOutsideThePileTouchesIt() {
        // Chip 2, outside the pile, shingles 2px onto the front's top edge.
        val boxes = listOf(box(0f, 200f), box(5f, 206f), box(0f, 176f))

        val shifts = spread(boxes, intArrayOf(0, 1))

        assertArrayEquals(floatArrayOf(0f, 0f), shifts[0], 0.001f)
    }

    @Test
    fun aChipMovesSidewaysWhenUpAndDownAreBlocked() {
        // Chip 0 hides under chip 1; chips 2 and 3 sit just above and below, outside the group,
        // and the chart ends right past them, so neither way up nor down has room.
        val boxes = listOf(box(0f, 200f), box(10f, 196f), box(0f, 160f), box(0f, 240f))

        val shifts = spread(boxes, intArrayOf(1, 0), minTop = 150f, maxTop = 236f)

        val result = moved(boxes, intArrayOf(1, 0), shifts)
        for (i in result.indices) for (j in result.indices) {
            if (i < j) assertTrue("chips $i and $j overlap", !overlapping(result[i], result[j]))
        }
        assertTrue(shifts[1][0] != 0f)
    }

    @Test
    fun spreadNeverLandsOnChipsOutsideTheGroup() {
        val boxes = listOf(box(0f, 200f), box(5f, 190f), box(10f, 180f), box(0f, 226f), box(120f, 200f))
        val members = intArrayOf(2, 1, 0)

        val result = moved(boxes, members, spread(boxes, members))

        for (i in result.indices) for (j in result.indices) {
            if (i < j && (i in members || j in members)) {
                assertTrue("chips $i and $j overlap", !overlapping(result[i], result[j]))
            }
        }
    }

    @Test
    fun spreadStaysOnTheChart() {
        val boxes = listOf(box(0f, 10f), box(2f, 8f))
        val members = intArrayOf(0, 1)

        val result = moved(boxes, members, spread(boxes, members, maxX = 300f, minTop = 8f, maxTop = 300f))

        result.forEach { box ->
            assertTrue(box.left >= 0f && box.right <= 300f && box.top >= 8f && box.top <= 300f)
        }
    }

    // --- Placement ---

    private val spec = JournalChipLayout.Spec(
        chipHeight = 26f,
        sideOffset = 10f,
        rowStep = 30f,
        nudgeStep = 12f,
        gap = 4f,
        minX = 0f,
        maxX = 400f,
        minTop = 8f,
        maxTop = 300f
    )

    private fun request(
        anchorX: Float,
        baseTop: Float = 200f,
        width: Float = 80f,
        previous: JournalChipSlot? = null,
        stackKey: Any? = null,
        foldInto: Int? = null
    ) = JournalChipRequest(anchorX, baseTop, width, previous, stackKey, foldInto)

    private val stacking = spec.copy(peek = 5f, repeatReach = 100f)

    private fun assertNoOverlaps(placements: List<JournalChipPlacement>) {
        for (i in placements.indices) for (j in placements.indices) {
            if (i < j) assertTrue("chips $i and $j overlap", !overlapping(placements[i].box, placements[j].box))
        }
    }

    @Test
    fun aChipWithRoomSitsWhereItAlwaysDid() {
        val placed = JournalChipLayout.place(listOf(request(50f)), spec).single()

        assertEquals(JournalChipSlot.Preferred, placed.slot)
        assertEquals(JournalChipBox(60f, 200f, 140f, 226f), placed.box)
    }

    @Test
    fun twoEntriesCloseTogetherSitBackToBackAroundThemInsteadOfStacking() {
        // The second entry's own right-hand spot is taken, but its left side is free.
        val placements = JournalChipLayout.place(listOf(request(100f), request(110f)), spec)

        assertEquals(JournalChipSlot.Preferred, placements[0].slot)
        assertEquals(JournalChipSlot(side = -1, lift = 0, nudge = 0), placements[1].slot)
        assertNoOverlaps(placements)
    }

    @Test
    fun aCrowdUsesRoomSidewaysAndAboveWithoutOverlapping() {
        // Six doses a minute apart, as a loop posts them, at 1h zoom on a 400px chart.
        val placements = JournalChipLayout.place((0 until 6).map { request(150f + it * 7f) }, spec)

        assertNoOverlaps(placements)
        assertTrue(placements.none { it.crowded })
        // Some of them use the room beside the entries rather than all climbing.
        assertTrue(placements.any { it.slot.lift == 0 && it.slot != JournalChipSlot.Preferred })
    }

    @Test
    fun aChipNearTheRightEdgeHangsToTheLeftOfItsEntry() {
        val placed = JournalChipLayout.place(listOf(request(370f)), spec).single()

        assertEquals(-1, placed.slot.side)
        assertTrue(placed.box.right <= 400f)
    }

    @Test
    fun aChipKeepsItsSpotWhileItStaysFree() {
        // At x 330 the right side would just fit, but last frame the chip hung left; it stays.
        val kept = JournalChipSlot(side = -1, lift = 0, nudge = 0)

        val placed = JournalChipLayout.place(listOf(request(300f, previous = kept)), spec).single()

        assertEquals(kept, placed.slot)
    }

    @Test
    fun aChipWhoseEntryHasLeftTheScreenRidesOffWithTheChart() {
        // Its entry is 30px past the left edge; the chip stays beside it instead of being
        // pushed back onto the screen.
        val placed = JournalChipLayout.place(listOf(request(-30f)), spec).single()

        assertEquals(JournalChipSlot.Preferred, placed.slot)
    }

    @Test
    fun withNoRoomLeftAChipOverlapsAndIsMarkedCrowded() {
        val tight = spec.copy(maxX = 120f, minTop = 190f, maxTop = 210f, maxNudge = 0)

        val placements = JournalChipLayout.place(listOf(request(10f), request(12f), request(14f)), tight)

        assertTrue(placements.any { it.crowded })
    }

    @Test
    fun aChipScrollingInDoesNotPushAChipAlreadyOnScreen() {
        // The later chip had the earlier chip's spot last frame; the earlier one is new.
        val onScreen = request(110f, previous = JournalChipSlot.Preferred)
        val scrollingIn = request(100f)

        val placements = JournalChipLayout.place(listOf(scrollingIn, onScreen), spec)

        assertEquals(JournalChipSlot.Preferred, placements[1].slot)
        assertNoOverlaps(placements)
    }

    // --- Piles ---

    @Test
    fun repeatsTuckBehindTheirTwinInsteadOfClimbing() {
        // A loop's 0,2 U doses five minutes apart, with no room for a row above.
        val tight = stacking.copy(minTop = 190f, maxTop = 210f)
        val placements = JournalChipLayout.place((0 until 3).map { request(100f + it * 20f, stackKey = "0,2") }, tight)

        assertTrue(placements.none { it.crowded })
        assertTrue(placements.all { it.front == 0 })
        assertEquals(listOf(0, 1, 2), placements.map { it.depth })
        assertTrue(placements.all { it.box == placements[0].box })
    }

    @Test
    fun chipsBehindAFrontPeekPartWayTowardWhereTheySpread() {
        // A front with three chips behind it and room all round: the first two peek out part of
        // the way to where the pile spreads them, and the third stays wholly behind.
        val boxes = List(4) { box(100f, 200f) }
        val pile = intArrayOf(0, 1, 2, 3)
        fun spreadOf(members: IntArray) = JournalChipLayout.spread(boxes, members, 0f, 1000f, 8f, 400f, 4f, 4f, 200f)
        val shifts = spreadOf(pile).shifts

        val peeks = JournalChipLayout.peeks(boxes, listOf(pile), visibleLayers = 2, peek = 12f, spreadOf = ::spreadOf)

        assertEquals(null, peeks[0])
        assertEquals(null, peeks[3])
        for (layer in 1..2) {
            val (dx, dy) = shifts[layer].let { it[0] to it[1] }
            val length = kotlin.math.hypot(dx, dy)
            val part = minOf(0.4f, layer * 12f / length)
            assertEquals(dx * part, peeks[layer]!![0], 0.01f)
            assertEquals(dy * part, peeks[layer]!![1], 0.01f)
        }
        // The first straight up; the second straight down, the nearest room left.
        assertTrue(peeks[1]!![0] == 0f && peeks[1]!![1] < 0f)
        assertTrue(peeks[2]!![0] == 0f && peeks[2]!![1] > 0f)
    }

    @Test
    fun aSpreadKeepsLastTimesSpotWhileItIsClear() {
        // Straight up is nearest, but last time the chip went down, and down is still clear.
        val boxes = listOf(box(100f, 200f), box(100f, 200f))

        val kept = JournalChipLayout.spread(boxes, intArrayOf(0, 1), 0f, 1000f, 8f, 400f, 4f, 4f, 200f, preferred = arrayOf(null, floatArrayOf(0f, 40f)))
        assertArrayEquals(floatArrayOf(0f, 40f), kept.shifts[1], 0.001f)

        // Once a chip sits there, it looks again.
        val blocked = JournalChipLayout.spread(boxes + box(100f, 240f), intArrayOf(0, 1), 0f, 1000f, 8f, 400f, 4f, 4f, 200f, preferred = arrayOf(null, floatArrayOf(0f, 40f)))
        assertTrue(blocked.shifts[1][1] < 0f)
    }

    @Test
    fun aFoldedChipStaysWhollyBehind() {
        val boxes = List(3) { box(100f, 200f) }
        fun spreadOf(members: IntArray) = JournalChipLayout.spread(boxes, members, 0f, 1000f, 8f, 400f, 4f, 4f, 200f)

        // Chip 1 is folded; chip 2, behind it, peeks in its place.
        val peeks = JournalChipLayout.peeks(boxes, listOf(intArrayOf(0, 1, 2)), visibleLayers = 1, peek = 12f, mayPeek = { it != 1 }, spreadOf = ::spreadOf)

        assertEquals(null, peeks[1])
        assertTrue(peeks[2] != null)
    }

    @Test
    fun aPeekNeverCoversAChipOutsideItsPile() {
        // The pile spreads up and to the left, but a chip outside it sits right above the front,
        // where a full peek would cover it and half a peek does not.
        val boxes = listOf(box(100f, 200f), box(100f, 200f), box(100f, 160f, width = 40f))
        val spread = JournalChipSpread(arrayOf(floatArrayOf(0f, 0f), floatArrayOf(-60f, -40f)), BooleanArray(2))

        val peeks = JournalChipLayout.peeks(boxes, listOf(intArrayOf(0, 1)), visibleLayers = 2, peek = 100f) { spread }

        val (dx, dy) = peeks[1]!!.let { it[0] to it[1] }
        val moved = JournalChipBox(boxes[1].left + dx, boxes[1].top + dy, boxes[1].right + dx, boxes[1].bottom + dy)
        assertTrue(!overlapping(moved, boxes[2]))
        assertEquals(-12f, dx, 0.01f)
    }

    @Test
    fun aChipWithNowhereToSpreadDoesNotPeek() {
        val boxes = listOf(box(0f, 8f), box(2f, 8f))
        fun spreadOf(members: IntArray) = JournalChipLayout.spread(boxes, members, 0f, 102f, 8f, 8f, 4f, 4f, 200f)

        val peeks = JournalChipLayout.peeks(boxes, listOf(intArrayOf(0, 1)), visibleLayers = 2, peek = 12f, spreadOf = ::spreadOf)

        assertEquals(null, peeks[1])
    }

    @Test
    fun aUniqueValueGetsAFreeSpotBeforeRepeatsDo() {
        // The 3 U bolus comes last in time but claims its own spot first; the two 0,2 U
        // doses either side of it make do by stacking.
        val tight = stacking.copy(minTop = 190f, maxTop = 210f, maxNudge = 1)
        val placements = JournalChipLayout.place(
            listOf(
                request(100f, stackKey = "0,2"),
                request(110f, stackKey = "0,2"),
                request(105f, stackKey = "3")
            ),
            tight
        )

        assertTrue(!placements[2].crowded)
        assertEquals(0, placements[2].depth)
        assertEquals(2, placements[2].front)
    }

    @Test
    fun differentValuesNeverStackWhileThereIsRoom() {
        val placements = JournalChipLayout.place(
            listOf(request(100f, stackKey = "0,2"), request(105f, stackKey = "0,3")),
            stacking
        )

        assertNoOverlaps(placements)
    }

    @Test
    fun aPileOfRepeatsHoldsOnlyAFew() {
        val roomy = stacking.copy(maxStack = 2)
        val placements = JournalChipLayout.place((0 until 3).map { request(100f + it * 10f, stackKey = "0,2") }, roomy)

        assertTrue(placements.groupBy { it.front }.values.all { it.size <= 2 })
    }

    @Test
    fun aChipWithNoFreeSpotJoinsThePileInItsWay() {
        val tight = stacking.copy(maxX = 120f, minTop = 190f, maxTop = 210f, maxNudge = 0)

        val placements = JournalChipLayout.place(listOf(request(10f, stackKey = "a"), request(12f, stackKey = "b"), request(14f, stackKey = "c")), tight)

        assertTrue(placements.drop(1).all { it.crowded && it.tuck != null && it.front == 0 })
        assertTrue(placements.all { it.box.left == placements[0].box.left })
        assertEquals(listOf(listOf(0, 1, 2)), JournalChipLayout.piles(placements, 12f, 8f).map { it.toList() })
    }

    @Test
    fun aCrowdedChipNeverPilesWithAnotherKind() {
        // No free spot for the carbs chip, and the only pile in its way is insulin.
        val tight = stacking.copy(maxX = 120f, minTop = 190f, maxTop = 210f, maxNudge = 0)

        val placements = JournalChipLayout.place(
            listOf(
                request(10f, stackKey = "a").copy(kind = "insulin"),
                request(12f, stackKey = "b").copy(kind = "insulin"),
                request(14f, stackKey = "c").copy(kind = "carbs")
            ),
            tight
        )

        assertEquals(0, placements[1].front)
        assertEquals(2, placements[2].front)
        assertTrue(placements[2].tuck == null)
    }

    @Test
    fun aCrowdedChipOfAnotherKindCoversAsLittleAsItCan() {
        // Two rows of room, one insulin chip already in each; the carbs chip can't pile with
        // them, so it takes the spot where it covers least of them.
        val tight = stacking.copy(maxX = 200f, minTop = 170f, maxTop = 200f, maxNudge = 0)
        val insulin = listOf(request(10f, baseTop = 200f).copy(kind = "insulin"), request(10f, baseTop = 170f).copy(kind = "insulin"))
        val carbs = request(60f, baseTop = 200f, width = 60f).copy(kind = "carbs")

        val placements = JournalChipLayout.place(insulin + carbs, tight)

        val placed = placements[2]
        assertTrue(placed.crowded && placed.tuck == null)
        val covered = placements.take(2).sumOf { other ->
            val across = minOf(placed.box.right, other.box.right) - maxOf(placed.box.left, other.box.left)
            val down = minOf(placed.box.bottom, other.box.bottom) - maxOf(placed.box.top, other.box.top)
            if (across > 0f && down > 0f) (across * down).toDouble() else 0.0
        }
        // Hanging right of its entry at 70..130 it covers only 20px of the 20..100 chips.
        assertTrue("covered $covered", covered <= 30.0 * 26.0)
    }

    @Test
    fun pilesHoldStillFromFrameToFrame() {
        // Scrolling in from the right edge, 7px a frame, where a front has to give up its spot
        // as its entry arrives: every chip still keeps its pile, front and layer.
        val tight = stacking.copy(minTop = 190f, maxTop = 210f)
        val anchors = listOf(370f, 385f, 400f, 378f)
        val keys = listOf("0,2", "0,2", "0,2", "5 g")
        var slots = arrayOfNulls<JournalChipSlot>(anchors.size)
        var tucks = arrayOfNulls<JournalChipTuck>(anchors.size)
        var first: List<Pair<Int, Int>>? = null
        for (frame in 0 until 15) {
            val placements = JournalChipLayout.place(
                anchors.indices.map { i ->
                    request(anchors[i] - frame * 7f, stackKey = keys[i], previous = slots[i]).copy(previousTuck = tucks[i])
                },
                tight
            )
            val roles = placements.map { it.front to it.depth }
            if (first == null) first = roles else assertEquals("frame $frame", first, roles)
            slots = Array(anchors.size) { placements[it].slot.takeIf { _ -> placements[it].tuck == null } }
            tucks = Array(anchors.size) { placements[it].tuck }
        }
    }

    @Test
    fun aFoldedChipHidesBehindTheChipItFoldsInto() {
        val placements = JournalChipLayout.place(listOf(request(100f), request(100.5f, foldInto = 0)), stacking)

        assertTrue(placements[1].folded)
        assertEquals(0, placements[1].front)
        assertEquals(placements[0].box, placements[1].box)
        assertEquals(listOf(listOf(0, 1)), JournalChipLayout.piles(placements, 12f, 8f).map { it.toList() })
    }

    @Test
    fun aHiddenTwinFormsAPileWithItsFront() {
        val tight = stacking.copy(minTop = 190f, maxTop = 210f)
        val placements = JournalChipLayout.place(listOf(request(100f, stackKey = "0,2"), request(110f, stackKey = "0,2")), tight)

        val piles = JournalChipLayout.piles(placements, 12f, 8f)

        assertEquals(listOf(listOf(0, 1)), piles.map { it.toList() })
    }

    @Test
    fun aMemberWithNowhereToGoIsMarkedStuck() {
        // The chart is exactly one chip tall and wide, so the chip behind has nowhere to go.
        val boxes = listOf(box(0f, 8f), box(2f, 8f))

        val spread = JournalChipLayout.spread(boxes, intArrayOf(0, 1), 0f, 102f, 8f, 8f, 4f, 4f, 200f)

        assertEquals(listOf(false, true), spread.stuck.toList())
        assertEquals(1, spread.stuckCount)
    }

    @Test
    fun onAFullChartALaterChipStillFindsFreeRoom() {
        // Forty chips crowd one spot, well past the point where overlaps stop being weighed;
        // a chip far from them still gets its own free spot.
        val tight = spec.copy(minTop = 190f, maxTop = 210f)
        val crowd = (0 until 40).map { request(10f) }

        val placements = JournalChipLayout.place(crowd + request(300f, width = 60f), tight)

        val last = placements.last()
        assertTrue(!last.crowded)
        assertTrue(placements.dropLast(1).none { overlapping(it.box, last.box) })
    }

    @Test
    fun onAFullChartCrowdedChipsStayOnTheChart() {
        val tight = spec.copy(minTop = 190f, maxTop = 210f)

        val placements = JournalChipLayout.place((0 until 60).map { request(380f) }, tight)

        assertTrue(placements.all { it.box.left >= 0f && it.box.right <= 400f })
    }

    @Test
    fun aChipPastAFullPileWithNoFreeSpotStillJoinsIt() {
        val tight = stacking.copy(maxX = 200f, minTop = 190f, maxTop = 210f, maxNudge = 0, maxStack = 4)

        val placements = JournalChipLayout.place((0 until 5).map { request(10f + it, stackKey = "0,2") }, tight)

        assertTrue(placements.all { it.front == 0 })
        assertEquals(listOf(listOf(0, 1, 2, 3, 4)), JournalChipLayout.piles(placements, 12f, 8f).map { it.toList() })
    }

    @Test
    fun aChipArrivingFromTheRightLeavesARightHandSpotItHadKept() {
        // Its entry is past the right edge, and last frame it hung to the right of it.
        val placed = JournalChipLayout.place(listOf(request(420f, previous = JournalChipSlot.Preferred)), spec).single()

        assertEquals(-1, placed.slot.side)
    }
}
