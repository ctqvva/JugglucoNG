package tk.glucodata.ui

/** A chip's label pill on the chart, in pixels. */
internal data class JournalChipBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Where one chip sits relative to its entry: which side, how far lifted or dropped, how far pushed out. */
internal data class JournalChipSlot(val side: Int, val lift: Int, val nudge: Int) {
    companion object {
        val Preferred = JournalChipSlot(side = 1, lift = 0, nudge = 0)
    }
}

/** A chip tucked into the pile behind the chip at [front], an index into this frame's requests. */
internal data class JournalChipTuck(val front: Int)

/**
 * One chip to place: its entry's x, the top it would like, its width, and where it sat last
 * frame, either a [previous] spot of its own or a [previousTuck] in a pile. Chips with the same
 * [stackKey], such as a loop's repeated doses of one size, may tuck behind one another. A chip
 * with [foldInto] set is too close to that earlier chip to tell apart on screen, so it hides
 * wholly behind it rather than taking a spot of its own. Chips of different [kind]s, such as
 * insulin and carbs, never pile together.
 */
internal data class JournalChipRequest(
    val anchorX: Float,
    val baseTop: Float,
    val width: Float,
    val previous: JournalChipSlot?,
    val stackKey: Any? = null,
    val foldInto: Int? = null,
    val previousTuck: JournalChipTuck? = null,
    val kind: Any? = null
)

/** How far each member of an opened pile moves, as `dx` and `dy` pairs, and which found no clear spot. */
internal class JournalChipSpread(val shifts: Array<FloatArray>, val stuck: BooleanArray) {
    val stuckCount: Int get() = stuck.count { it }
}

/**
 * The spot a chip was given. [front] is the chip whose pile it belongs to, its own index when it
 * leads; [depth] is how many layers down it sits, 0 being on top. A chip tucked in a pile has [tuck]
 * set. [crowded] chips had no free spot; [folded] ones hide wholly behind the chip they fold into.
 */
internal data class JournalChipPlacement(
    val slot: JournalChipSlot,
    val box: JournalChipBox,
    val crowded: Boolean,
    val front: Int,
    val depth: Int,
    val folded: Boolean = false,
    val tuck: JournalChipTuck? = null
)

/**
 * Lays journal chips out on the chart so they use the room around them instead of piling up.
 *
 * Each chip prefers to hang to the right of its entry at its own height. When that spot is
 * taken, it tries the other side of its entry, lifting or dropping by whole rows, and
 * nudging further out sideways, and takes the cheapest spot that is free. A chip keeps last
 * frame's spot while it stays free and on screen, so panning never reshuffles the chips;
 * the chart forgets those spots when the zoom settles, so the layout then tidies up.
 *
 * Chips whose value appears only once nearby claim the free room first. A chip repeating a
 * nearby value may instead tuck into a pile behind it rather than climbing the chart, a few at
 * most; a chip with no free spot at all joins the pile of the nearest chip in its way. Each chip
 * remembers which pile it joined, so fronts and layers hold still while panning. Where the chips
 * behind a front rest, peeking out toward where they spread, is [peeks]' to say.
 */
internal object JournalChipLayout {

    /** Geometry and limits for [place], in pixels. */
    internal data class Spec(
        val chipHeight: Float,
        val sideOffset: Float,
        val rowStep: Float,
        val nudgeStep: Float,
        val gap: Float,
        val minX: Float,
        val maxX: Float,
        val minTop: Float,
        val maxTop: Float,
        val maxLift: Int = 10,
        val maxDrop: Int = 3,
        val maxNudge: Int = 4,
        /** How far the first chip behind a front peeks out at most; the next one, twice that. */
        val peek: Float = 0f,
        /** How close two chips with the same value must be for either to count as a repeat. */
        val repeatReach: Float = 0f,
        /** The most chips a pile of repeats holds, its front included. */
        val maxStack: Int = 4,
        /** How many chips behind its front a pile shows peeking out; deeper ones hide wholly. */
        val visibleLayers: Int = 2
    )

    // Costs, in rows of movement: a lift is the yardstick, a drop costs a little more, the
    // other side of the entry a little less, and a nudge sideways costs by distance. Any
    // part of a chip hanging off the screen costs heavily. A chip pushed off its last spot
    // still leans toward it, should that spot come back into reach.
    private const val LIFT_COST = 1f
    private const val DROP_COST = 1.3f
    private const val OTHER_SIDE_COST = 0.8f
    private const val NUDGE_COST_PER_ROW = 1.2f
    private const val OFF_SCREEN_COST_PER_ROW = 3f
    private const val KEEP_DISCOUNT = 1f
    // Tucking behind a twin close by is cheaper than climbing a row or crossing to the other
    // side, so repeats share room instead of taking more of the chart.
    private const val TUCK_COST = 0.4f
    private const val KEEP_SLACK = 1f

    /**
     * Places [requests], which come in time order. Chips keeping last frame's spot or pile go
     * first, then chips that had to leave theirs, then chips new to the chart, so nothing on
     * screen is pushed about by a chip scrolling in. Within each pass, chips whose value is
     * unique nearby go before repeats, and earlier entries before later ones. [obstacles], such
     * as the entries' own dots, are kept clear like placed chips. The result lines up with
     * [requests].
     */
    fun place(
        requests: List<JournalChipRequest>,
        spec: Spec,
        obstacles: List<JournalChipBox> = emptyList()
    ): List<JournalChipPlacement> {
        val layout = Layout(requests, spec)
        obstacles.forEach { layout.grid.add(it, OBSTACLE) }
        val slots = slotsFor(spec)
        val folded = requests.indices.filter { requests[it].foldInto != null }.toHashSet()
        // First every chip that can keep last frame's spot does, outright, while that spot is
        // still free and on screen, or while its entry has yet to scroll in from the right, so
        // it slides in steadily rather than hunting for a spot each frame. Then every chip that
        // can rejoin last frame's pile does, in the same layer order. Only the chips that must
        // move look for a new spot, so one chip's move can never push another about.
        requests.forEachIndexed { index, request ->
            if (index in folded || request.previousTuck != null) return@forEachIndexed
            val previous = request.previous ?: return@forEachIndexed
            val box = boxFor(request, previous, spec) ?: return@forEachIndexed
            if (leavesChart(request, box, spec)) return@forEachIndexed
            // A pixel of slack, so chips placed exactly a gap apart are not torn apart by the
            // rounding of a pan.
            if (!layout.isFree(box, spec.gap - KEEP_SLACK)) return@forEachIndexed
            layout.commit(index, previous, box)
        }
        requests.forEachIndexed { index, request ->
            if (index in folded) return@forEachIndexed
            val tuck = request.previousTuck ?: return@forEachIndexed
            if (!layout.mayJoin(index, tuck.front)) return@forEachIndexed
            val box = layout.pileBoxFor(index, tuck.front) ?: return@forEachIndexed
            layout.commitTuck(index, tuck.front, box)
        }
        val unplaced = requests.indices.filter { it !in folded && layout.placements[it] == null }
        val (fresh, displaced) = unplaced.partition { requests[it].previous == null && requests[it].previousTuck == null }
        // Chips that had a spot of their own go before chips that were in a pile, so a pile
        // whose front had to move finds it already settled and follows it.
        val (displacedTucks, displacedSpots) = displaced.partition { requests[it].previousTuck != null }
        val order = displacedSpots.sortedBy { layout.repeats[it] } + displacedTucks + fresh.sortedBy { layout.repeats[it] }
        order.forEach { index ->
            val request = requests[index]
            var bestSlot: JournalChipSlot? = null
            var bestBox: JournalChipBox? = null
            var bestFront = -1
            var bestCost = Float.POSITIVE_INFINITY
            // A pile to tuck into: last frame's, or a twin's close by.
            fun considerPile(front: Int, discount: Float) {
                if (!layout.mayJoin(index, front) || !layout.withinTuckReach(index, front)) return
                val cost = layout.tuckCost(index, front) - discount
                if (cost >= bestCost) return
                val box = layout.pileBoxFor(index, front) ?: return
                bestSlot = null
                bestBox = box
                bestFront = front
                bestCost = cost
            }
            // Last frame's pile, wherever its front has gone, even into another pile.
            request.previousTuck?.let { tuck ->
                val lead = layout.placements[tuck.front]
                considerPile(if (lead != null && lead.tuck != null) lead.front else tuck.front, KEEP_DISCOUNT)
            }
            if (layout.repeats[index]) layout.twinFrontsOf(index).forEach { considerPile(it, 0f) }
            fun consider(slot: JournalChipSlot) {
                val box = boxFor(request, slot, spec) ?: return
                if (leavesChart(request, box, spec)) return
                val cost = costOf(request, slot, box, spec)
                if (cost >= bestCost || !layout.isFree(box)) return
                bestSlot = slot
                bestBox = box
                bestFront = -1
                bestCost = cost
            }
            // Last frame's spot first: it is usually still free and still the cheapest.
            request.previous?.let(::consider)
            // Then the rest, nearest first. Going off screen only adds cost, so once a spot's
            // base cost alone is no better than the best found, nothing further can be.
            for ((slot, baseCost) in slots) {
                if (baseCost >= bestCost) break
                if (slot != request.previous) consider(slot)
            }
            val box = bestBox
            val slot = bestSlot
            when {
                box != null && slot != null -> layout.commit(index, slot, box)
                box != null -> layout.commitTuck(index, bestFront, box)
                else -> crowdedPlacement(index, request, slots, spec, layout)
            }
        }
        layout.orderPiles()
        // A folded chip hides wholly behind the chip it folds into, one layer under that pile.
        folded.sorted().forEach { index ->
            val host = layout.placements[requests[index].foldInto!!] ?: return@forEach
            val front = host.front
            layout.placements[index] = JournalChipPlacement(
                slot = host.slot,
                box = host.box,
                crowded = false,
                front = front,
                depth = layout.pileSizes[front],
                folded = true
            )
            layout.pileSizes[front]++
        }
        return layout.placements.map { it!! }
    }

    private const val OBSTACLE = -1

    /** The placements made so far, and where they sit. */
    private class Layout(val requests: List<JournalChipRequest>, val spec: Spec) {
        val grid = BoxGrid(columnWidth = (spec.rowStep * 2f).coerceAtLeast(1f))
        val placements = arrayOfNulls<JournalChipPlacement>(requests.size)
        val pileSizes = IntArray(requests.size)
        val repeats = repeatsOf(requests, spec.repeatReach)
        private val frontsByKey = HashMap<Any, MutableList<Int>>()

        /** Whether [box] keeps [gap] from every chip and obstacle placed so far. */
        fun isFree(box: JournalChipBox, gap: Float = spec.gap): Boolean {
            grid.forEachNear(box, spec.gap) { _, other -> if (within(box, other, gap)) return false }
            return true
        }

        /**
         * Whether chip [index] may join the pile led by [front]: the front must lead its own
         * pile, of the same kind. By choice, a repeat joins only a pile of its own value with room;
         * a chip with no free spot at all, [crowded], may join any pile of its kind however full,
         * so it is always counted.
         */
        fun mayJoin(index: Int, front: Int, crowded: Boolean = false): Boolean {
            val lead = placements.getOrNull(front) ?: return false
            if (lead.front != front || lead.folded) return false
            if (requests[index].kind != requests[front].kind) return false
            if (crowded) return true
            val key = requests[index].stackKey
            return key == null || key != requests[front].stackKey || pileSizes[front] < spec.maxStack
        }

        // Fronts with the same value whose entries sit close enough for chip [index] to join.
        fun twinFrontsOf(index: Int): List<Int> {
            val key = requests[index].stackKey ?: return emptyList()
            val anchorX = requests[index].anchorX
            return frontsByKey[key].orEmpty().filter { kotlin.math.abs(requests[it].anchorX - anchorX) <= spec.repeatReach }
        }

        // Whether [front]'s pile sits close enough to where chip [index] would hang on its own
        // to tuck into, so no chip joins a pile far from its entry.
        fun withinTuckReach(index: Int, front: Int): Boolean {
            val request = requests[index]
            val pile = placements[front]!!.box
            val ownLeft = request.anchorX + spec.sideOffset
            val ownRight = request.anchorX - spec.sideOffset
            val reach = spec.repeatReach / 2f
            return pile.left <= ownLeft + reach && pile.right >= ownRight - reach
        }

        // What joining [front]'s pile costs chip [index], in the same rows as a spot: the tuck
        // itself, plus how far the pile sits from where the chip would hang on its own, sideways
        // at half a nudge's rate, so a twin a little way along still stacks rather than spreads.
        fun tuckCost(index: Int, front: Int): Float {
            val request = requests[index]
            val pile = placements[front]!!.box
            val ownLeft = request.anchorX + spec.sideOffset
            val ownTop = request.baseTop.coerceIn(spec.minTop, maxOf(spec.maxTop, request.baseTop))
            val across = kotlin.math.abs(pile.left - ownLeft) / spec.rowStep * NUDGE_COST_PER_ROW * 0.5f
            val rows = (pile.top - ownTop) / spec.rowStep
            val down = if (rows >= 0f) rows * DROP_COST else -rows * LIFT_COST
            return TUCK_COST + across + down
        }

        /**
         * Where chip [index] would sit in [front]'s pile: right behind the front, lined up with
         * its edge nearest the entry, or null if that would take it off the chart while its
         * entry is on screen.
         */
        fun pileBoxFor(index: Int, front: Int): JournalChipBox? {
            val box = behind(front, requests[index].width)
            return box.takeUnless { leavesChart(requests[index], it, spec) }
        }

        private fun behind(front: Int, width: Float): JournalChipBox {
            val lead = placements[front]!!
            val left = if (lead.slot.side < 0) lead.box.right - width else lead.box.left
            return JournalChipBox(left, lead.box.top, left + width, lead.box.bottom)
        }

        /**
         * Puts every pile's layers in time order, earliest nearest the front, whatever order
         * its chips joined in, so two chips never trade layers from one frame to the next.
         */
        fun orderPiles() {
            val members = placements.indices.filter { placements[it]?.tuck != null }.groupBy { placements[it]!!.front }
            members.forEach { (front, pile) ->
                if (pile.size < 2) return@forEach
                val depths = pile.map { placements[it]!!.depth }.sorted()
                pile.sorted().forEachIndexed { position, index ->
                    placements[index] = placements[index]!!.copy(box = behind(front, requests[index].width), depth = depths[position])
                }
            }
        }

        fun commit(index: Int, slot: JournalChipSlot, box: JournalChipBox, crowded: Boolean = false) {
            placements[index] = JournalChipPlacement(slot, box, crowded, front = index, depth = 0)
            pileSizes[index] = 1
            grid.add(box, index)
            requests[index].stackKey?.let { frontsByKey.getOrPut(it) { ArrayList() }.add(index) }
        }

        fun commitTuck(index: Int, front: Int, box: JournalChipBox, crowded: Boolean = false) {
            placements[index] = JournalChipPlacement(
                slot = placements[front]!!.slot,
                box = box,
                crowded = crowded,
                front = front,
                depth = pileSizes[front],
                tuck = JournalChipTuck(front)
            )
            pileSizes[front]++
            // A chip wholly behind its front takes no room the front doesn't; leaving it out of
            // the grid keeps dense columns short.
            val lead = placements[front]!!.box
            if (box.left < lead.left || box.right > lead.right) grid.add(box, index)
        }
    }

    // A chip repeats when another with the same value sits within [reach] of it.
    private fun repeatsOf(requests: List<JournalChipRequest>, reach: Float): BooleanArray {
        val repeats = BooleanArray(requests.size)
        requests.indices
            .filter { requests[it].stackKey != null }
            .groupBy { requests[it].stackKey }
            .values
            .forEach { same ->
                val byX = same.sortedBy { requests[it].anchorX }
                for (i in 1 until byX.size) {
                    if (requests[byX[i]].anchorX - requests[byX[i - 1]].anchorX <= reach) {
                        repeats[byX[i]] = true
                        repeats[byX[i - 1]] = true
                    }
                }
            }
        return repeats
    }

    private fun within(a: JournalChipBox, b: JournalChipBox, gap: Float): Boolean =
        a.left - gap < b.right && b.left < a.right + gap && a.top - gap < b.bottom && b.top < a.bottom + gap

    // With no free spot, the chip joins a pile of its kind: of the nearest spots, and last
    // frame's, it takes the cheapest one that a chip of its kind already covers, and tucks
    // behind that chip's front. Failing that, it joins the nearest pile of its kind in reach of
    // its entry. Only when no pile will have it does it simply overlap where it would hang.
    private fun crowdedPlacement(
        index: Int,
        request: JournalChipRequest,
        slots: List<Pair<JournalChipSlot, Float>>,
        spec: Spec,
        layout: Layout
    ) {
        val candidates = ArrayList<JournalChipSlot>(CROWDED_CANDIDATES + 1)
        request.previous?.let(candidates::add)
        for ((slot, _) in slots) {
            if (candidates.size > CROWDED_CANDIDATES) break
            if (slot != request.previous && boxFor(request, slot, spec) != null) candidates.add(slot)
        }
        var bestFront = -1
        var bestPile: JournalChipBox? = null
        var bestCost = Float.POSITIVE_INFINITY
        for (slot in candidates) {
            val box = boxFor(request, slot, spec) ?: continue
            if (leavesChart(request, box, spec)) continue
            val cost = costOf(request, slot, box, spec)
            if (cost >= bestCost) continue
            var front = -1
            layout.grid.forEachNear(box, 0f) { owner, other ->
                if (front < 0 && owner != OBSTACLE && within(box, other, 0f)) {
                    val lead = layout.placements[owner]!!.front
                    if (layout.mayJoin(index, lead, crowded = true) && layout.withinTuckReach(index, lead)) front = lead
                }
            }
            if (front < 0) continue
            val pile = layout.pileBoxFor(index, front) ?: continue
            bestFront = front
            bestPile = pile
            bestCost = cost
        }
        if (bestPile == null) {
            val ownX = request.anchorX + spec.sideOffset
            val reach = spec.repeatReach / 2f + spec.sideOffset * 2f
            val near = JournalChipBox(ownX - reach - request.width, 0f, ownX + reach, 0f)
            var bestDistance = Float.POSITIVE_INFINITY
            layout.grid.forEachNear(near, 0f) { owner, _ ->
                if (owner == OBSTACLE) return@forEachNear
                val front = layout.placements[owner]!!.front
                val distance = kotlin.math.abs(layout.placements[front]!!.box.left - ownX)
                if (distance >= bestDistance || !layout.mayJoin(index, front, crowded = true) || !layout.withinTuckReach(index, front)) return@forEachNear
                val pile = layout.pileBoxFor(index, front) ?: return@forEachNear
                bestDistance = distance
                bestFront = front
                bestPile = pile
            }
        }
        val pile = bestPile
        if (pile != null) {
            layout.commitTuck(index, bestFront, pile, crowded = true)
            return
        }
        // With no pile of its kind in reach, the chip takes the spot near its entry that covers
        // the least of other chips, so both labels stay as readable as the room allows; failing
        // any spot on the chart, it hangs on whichever side of its entry leaves the chart least.
        var bestSlot: JournalChipSlot? = null
        var bestBox: JournalChipBox? = null
        var leastCovered = Float.POSITIVE_INFINITY
        for ((slot, _) in slots) {
            val box = boxFor(request, slot, spec) ?: continue
            if (leavesChart(request, box, spec)) continue
            var covered = 0f
            // A wide chip spans several columns of the grid; count each one once.
            val counted = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<JournalChipBox, Boolean>())
            layout.grid.forEachNear(box, 0f) { _, other ->
                if (!counted.add(other)) return@forEachNear
                val across = minOf(box.right, other.right) - maxOf(box.left, other.left)
                val down = minOf(box.bottom, other.bottom) - maxOf(box.top, other.top)
                if (across > 0f && down > 0f) covered += across * down
            }
            // Ties go to the nearer, cheaper spot, as slots come cheapest first.
            if (covered < leastCovered) {
                leastCovered = covered
                bestSlot = slot
                bestBox = box
            }
        }
        val slot = bestSlot ?: listOf(JournalChipSlot.Preferred, JournalChipSlot(side = -1, lift = 0, nudge = 0))
            .minBy { offScreenOf(request, boxFor(request, it, spec, clamp = true)!!, spec) }
        layout.commit(index, slot, bestBox ?: boxFor(request, slot, spec, clamp = true)!!, crowded = true)
    }

    private const val CROWDED_CANDIDATES = 24

    // Every spot a chip may take, with its base cost, cheapest first.
    private fun slotsFor(spec: Spec): List<Pair<JournalChipSlot, Float>> {
        val slots = ArrayList<Pair<JournalChipSlot, Float>>()
        for (side in intArrayOf(1, -1)) {
            for (lift in -spec.maxDrop..spec.maxLift) {
                for (nudge in 0..spec.maxNudge) {
                    val slot = JournalChipSlot(side, lift, nudge)
                    slots.add(slot to baseCostOf(slot, spec))
                }
            }
        }
        // Stable, so equal costs keep this order: right before left, lifts before drops.
        return slots.sortedBy { it.second }
    }

    private fun boxFor(request: JournalChipRequest, slot: JournalChipSlot, spec: Spec, clamp: Boolean = false): JournalChipBox? {
        // A chip that wants to sit below the usual floor (insulin, under its curve) may.
        val maxTop = maxOf(spec.maxTop, request.baseTop)
        val base = request.baseTop.coerceIn(spec.minTop, maxTop)
        var top = base - slot.lift * spec.rowStep
        if (top < spec.minTop || top > maxTop) {
            if (!clamp) return null
            top = top.coerceIn(spec.minTop, maxTop)
        }
        val push = spec.sideOffset + slot.nudge * spec.nudgeStep
        val left = if (slot.side > 0) request.anchorX + push else request.anchorX - push - request.width
        return JournalChipBox(left, top, left + request.width, top + spec.chipHeight)
    }

    private fun baseCostOf(slot: JournalChipSlot, spec: Spec): Float {
        var cost = if (slot.lift >= 0) slot.lift * LIFT_COST else -slot.lift * DROP_COST
        if (slot.side < 0) cost += OTHER_SIDE_COST
        cost += slot.nudge * spec.nudgeStep / spec.rowStep * NUDGE_COST_PER_ROW
        return cost
    }

    private fun costOf(request: JournalChipRequest, slot: JournalChipSlot, box: JournalChipBox, spec: Spec): Float {
        var cost = baseCostOf(slot, spec)
        cost += offScreenOf(request, box, spec) / spec.rowStep * OFF_SCREEN_COST_PER_ROW
        if (slot == request.previous) cost -= KEEP_DISCOUNT
        return cost
    }

    // A chip whose entry is on screen stays wholly on the chart; one whose entry has left may
    // hang off with it. One whose entry has yet to arrive from the right may not hang to the
    // right of it, since that spot would leave the chart the moment the entry arrives; it waits
    // to the left instead, already where it will stay.
    private fun leavesChart(request: JournalChipRequest, box: JournalChipBox, spec: Spec): Boolean = when {
        request.anchorX < spec.minX -> false
        request.anchorX > spec.maxX -> box.left > request.anchorX
        else -> offScreenOf(request, box, spec) > 0f
    }

    // How far a chip hangs off the screen, as far as it matters. Past the right edge always
    // counts, so a chip scrolling in from the right arrives already hung to the left of its
    // entry instead of flipping over once its entry appears. Past the left edge only counts
    // while the entry is on screen: a chip whose entry has left rides off with it rather
    // than being pinned to the edge.
    private fun offScreenOf(request: JournalChipRequest, box: JournalChipBox, spec: Spec): Float {
        var offScreen = (box.right - spec.maxX).coerceAtLeast(0f)
        if (request.anchorX >= spec.minX) offScreen += (spec.minX - box.left).coerceAtLeast(0f)
        return offScreen
    }

    /** Placed boxes bucketed into columns, so a candidate is only checked against its neighbours. */
    private class BoxGrid(private val columnWidth: Float) {
        val boxes = ArrayList<JournalChipBox>()
        val owners = ArrayList<Int>()
        private val columns = HashMap<Int, MutableList<Int>>()

        fun columnsOf(left: Float, right: Float): IntRange =
            kotlin.math.floor(left / columnWidth).toInt()..kotlin.math.floor(right / columnWidth).toInt()

        fun add(box: JournalChipBox, owner: Int) {
            val entry = boxes.size
            boxes.add(box)
            owners.add(owner)
            for (column in columnsOf(box.left, box.right)) columns.getOrPut(column) { ArrayList() }.add(entry)
        }

        /** Calls [action] for every placed box in the columns within [gap] of [box]; a wide one may come more than once. */
        inline fun forEachNear(box: JournalChipBox, gap: Float, action: (owner: Int, other: JournalChipBox) -> Unit) {
            for (column in columnsOf(box.left - gap, box.right + gap)) {
                val entries = columnEntries(column) ?: continue
                for (i in entries.indices) {
                    val entry = entries[i]
                    action(owners[entry], boxes[entry])
                }
            }
        }

        fun columnEntries(column: Int): List<Int>? = columns[column]
    }

    /**
     * The piles in which some chip's label is hidden: each starts with its front, then the
     * chips hidden behind it, top layer first. A chip tucked in a pile or folded away is hidden by
     * design; any other member counts only when a chip above it in its pile covers more than
     * [minOverlapX] across and [minOverlapY] down.
     */
    fun piles(placements: List<JournalChipPlacement>, minOverlapX: Float, minOverlapY: Float): List<IntArray> {
        val members = placements.indices.groupBy { placements[it].front }
        val piles = ArrayList<IntArray>()
        members.forEach { (front, pile) ->
            if (pile.size < 2) return@forEach
            val hidden = pile.filter { index ->
                if (index == front) return@filter false
                val placement = placements[index]
                placement.folded || placement.tuck != null || pile.any { other ->
                    val above = placements[other]
                    other != index && above.depth < placement.depth &&
                        minOf(above.box.right, placement.box.right) - maxOf(above.box.left, placement.box.left) > minOverlapX &&
                        minOf(above.box.bottom, placement.box.bottom) - maxOf(above.box.top, placement.box.top) > minOverlapY
                }
            }
            if (hidden.isEmpty()) return@forEach
            piles.add((listOf(front) + hidden.sortedWith(compareBy({ placements[it].depth }, { it }))).toIntArray())
        }
        return piles
    }

    /**
     * Where the chips behind each front of [piles] rest while their pile is closed, as `dx` and
     * `dy` from their placed box: part of the way toward the spot [spreadOf] moves them to when
     * the pile opens, so each peeks out the way it will go. The first [visibleLayers] chips behind
     * a front that [mayPeek] peek, the first at most [peek] past it and the next twice that. A
     * peek never covers a chip outside its pile, by half as much where the full peek would;
     * deeper chips, and any with nowhere to spread, stay wholly behind. The result lines up
     * with [boxes].
     */
    fun peeks(
        boxes: List<JournalChipBox>,
        piles: List<IntArray>,
        visibleLayers: Int,
        peek: Float,
        mayPeek: (Int) -> Boolean = { true },
        spreadOf: (IntArray) -> JournalChipSpread
    ): Array<FloatArray?> {
        val peeks = arrayOfNulls<FloatArray>(boxes.size)
        piles.forEach { pile ->
            // A spread places its members in order, so the first few land just where they do
            // when the whole pile opens; chips that may not peek come after them in a pile.
            val peeking = pile.drop(1).filter(mayPeek).take(visibleLayers)
            val shown = (listOf(pile[0]) + peeking).toIntArray()
            if (shown.size < 2) return@forEach
            val spread = spreadOf(shown)
            val inPile = pile.toHashSet()
            val outside = boxes.indices.filter { it !in inPile }
            for (layer in 1 until shown.size) {
                if (spread.stuck[layer]) continue
                val dx = spread.shifts[layer][0]
                val dy = spread.shifts[layer][1]
                val length = kotlin.math.hypot(dx, dy)
                if (length <= 0f) continue
                val box = boxes[shown[layer]]
                val full = minOf(PEEK_PART, layer * peek / length)
                val part = listOf(full, full / 2f).firstOrNull { part ->
                    val moved = JournalChipBox(box.left + dx * part, box.top + dy * part, box.right + dx * part, box.bottom + dy * part)
                    outside.none { within(moved, boxes[it], 0f) && !within(box, boxes[it], 0f) }
                } ?: continue
                peeks[shown[layer]] = floatArrayOf(dx * part, dy * part)
            }
        }
        return peeks
    }

    // How much of the way toward its spread spot a chip behind a front rests, where that is near.
    private const val PEEK_PART = 0.4f

    /**
     * Offsets that move each of [members] the shortest way, in any direction, to a spot clear
     * of every other chip on the chart.
     *
     * Members are placed in the order given, front first. A chip stays put when no member
     * placed before it is in the way; chips outside the group were already there, so staying
     * makes nothing worse. Otherwise it takes its [preferred] offset, where it went last time,
     * while that spot is still clear, so a pile spreads the same way from frame to frame.
     * Failing that, it tries rings of candidate spots around its own position,
     * nearest first, [ringStepPx] apart out to [maxRadiusPx], and takes the first that keeps
     * [gapPx] from every chip already placed and every chip outside the group, and stays inside
     * [minX]..[maxX] × [minTop]..[maxTop]. A chip with no such spot in reach stays where it is
     * and is marked stuck, so the chart can say so and offer a closer zoom. The result lines up
     * with [members].
     */
    fun spread(
        boxes: List<JournalChipBox>,
        members: IntArray,
        minX: Float,
        maxX: Float,
        minTop: Float,
        maxTop: Float,
        gapPx: Float,
        ringStepPx: Float,
        maxRadiusPx: Float,
        preferred: Array<FloatArray?>? = null
    ): JournalChipSpread {
        val shifts = Array(members.size) { FloatArray(2) }
        val stuck = BooleanArray(members.size)
        val memberSet = members.toHashSet()
        val placed = ArrayList<JournalChipBox>(boxes.size)
        boxes.indices.filter { it !in memberSet }.mapTo(placed) { boxes[it] }
        val placedMembers = ArrayList<JournalChipBox>(members.size)
        val candidates = candidateOffsets(ringStepPx, maxRadiusPx)
        members.indices.forEach { slot ->
            val box = boxes[members[slot]]
            val width = box.right - box.left
            val height = box.bottom - box.top
            if (placedMembers.none { within(box, it, gapPx) }) {
                placedMembers.add(box)
                placed.add(box)
                return@forEach
            }
            // Only chips within reach of a candidate spot can block it.
            val reach = maxRadiusPx + gapPx
            val nearby = placed.filter { other ->
                other.right > box.left - reach && other.left < box.right + reach &&
                    other.bottom > box.top - reach && other.top < box.bottom + reach
            }
            fun clear(dx: Float, dy: Float): Boolean {
                val left = box.left + dx
                val top = box.top + dy
                return left >= minX && left + width <= maxX && top >= minTop && top <= maxTop &&
                    nearby.none { other ->
                        left < other.right + gapPx && other.left < left + width + gapPx &&
                            top < other.bottom + gapPx && other.top < top + height + gapPx
                    }
            }
            val kept = preferred?.getOrNull(slot)?.takeIf { (dx, dy) ->
                (dx != 0f || dy != 0f) && kotlin.math.hypot(dx, dy) <= maxRadiusPx && clear(dx, dy)
            }
            val spot = kept?.let { it[0] to it[1] } ?: candidates.firstOrNull { (dx, dy) -> clear(dx, dy) }
            if (spot == null) stuck[slot] = true
            val (dx, dy) = spot ?: (0f to 0f)
            shifts[slot][0] = dx
            shifts[slot][1] = dy
            val moved = JournalChipBox(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
            placedMembers.add(moved)
            placed.add(moved)
        }
        return JournalChipSpread(shifts, stuck)
    }

    private const val RING_DIRECTIONS = 24

    // Directions around a ring, closest to vertical first and up before down: at equal
    // distance a chip stays over its own entry, and only goes sideways when it has to.
    private val RING_ANGLES: List<Double> = (0 until RING_DIRECTIONS)
        .map { step -> step * 2 * Math.PI / RING_DIRECTIONS }
        .sortedWith(compareBy<Double>({ kotlin.math.abs(kotlin.math.cos(it)).roundTo(6) }, { -kotlin.math.sin(it).roundTo(6) }))

    private fun Double.roundTo(places: Int): Double {
        val scale = Math.pow(10.0, places.toDouble())
        return Math.round(this * scale) / scale
    }

    // Rings of spots around a chip, nearest first.
    private fun candidateOffsets(ringStepPx: Float, maxRadiusPx: Float): List<Pair<Float, Float>> {
        val offsets = ArrayList<Pair<Float, Float>>()
        offsets.add(0f to 0f)
        if (ringStepPx <= 0f) return offsets
        var radius = ringStepPx
        while (radius <= maxRadiusPx) {
            RING_ANGLES.forEach { angle ->
                // Screen y grows downward, so a negative sine is up. Rounded to a hundredth of
                // a pixel, so straight down is exactly 0 across and passes the left-edge check.
                offsets.add((radius * kotlin.math.cos(angle)).roundTo(2).toFloat() to (-radius * kotlin.math.sin(angle)).roundTo(2).toFloat())
            }
            radius += ringStepPx
        }
        return offsets
    }
}
