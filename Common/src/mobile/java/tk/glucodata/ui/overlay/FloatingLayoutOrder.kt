package tk.glucodata.ui.overlay

/**
 * What the floating glucose shows, in the order the user arranged it (#981).
 *
 * [CUTOUT] is not drawn: in Dynamic Island mode it is where the camera sits, so the items
 * before it go on its left (or above a side cutout) and the rest after it. The free-floating
 * pill has no camera and ignores it.
 */
enum class FloatingLayoutItem {
    VALUE,
    SECONDARY,
    DELTA,
    ARROW,
    CUTOUT,
}

/** The items on each side of the camera, in order. */
data class FloatingLayoutSides(
    val before: List<FloatingLayoutItem>,
    val after: List<FloatingLayoutItem>,
)

object FloatingLayoutOrder {
    /** The layout every version before #981 drew: reading and secondary over delta, camera, arrow. */
    val DEFAULT: List<FloatingLayoutItem> = listOf(
        FloatingLayoutItem.VALUE,
        FloatingLayoutItem.SECONDARY,
        FloatingLayoutItem.DELTA,
        FloatingLayoutItem.CUTOUT,
        FloatingLayoutItem.ARROW,
    )

    fun serialize(order: List<FloatingLayoutItem>): String = order.joinToString(",") { it.name }

    /**
     * The stored order, repaired: unknown or repeated names are dropped and a missing item
     * goes back where [DEFAULT] has it relative to the item before it, so a value written by
     * a later version with fewer or more items still lays out.
     */
    fun parse(stored: String?): List<FloatingLayoutItem> {
        if (stored.isNullOrBlank()) return DEFAULT
        val seen = LinkedHashSet<FloatingLayoutItem>()
        stored.split(',').forEach { name ->
            FloatingLayoutItem.entries.firstOrNull { it.name == name.trim() }?.let(seen::add)
        }
        val result = seen.toMutableList()
        DEFAULT.forEachIndexed { index, item ->
            if (item in result) return@forEachIndexed
            val anchor = DEFAULT.subList(0, index).lastOrNull { it in result }
            result.add(if (anchor == null) 0 else result.indexOf(anchor) + 1, item)
        }
        return result
    }

    /** [order] with the item at [from] moved to [to], both indices into [order]. */
    fun moved(order: List<FloatingLayoutItem>, from: Int, to: Int): List<FloatingLayoutItem> {
        if (from !in order.indices || to !in order.indices || from == to) return order
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * The rows the settings list shows: every item, or without [FloatingLayoutItem.CUTOUT]
     * while Dynamic Island mode is off, since the pill has no camera to arrange around.
     */
    fun visible(order: List<FloatingLayoutItem>, island: Boolean): List<FloatingLayoutItem> =
        if (island) order else order.filter { it != FloatingLayoutItem.CUTOUT }

    /**
     * [moved] for indices into [visible]. With the camera row hidden it stays right after the
     * item it followed, so the items the user did not move keep their side of the camera for
     * when island mode comes back on; the moved item goes wherever it landed.
     */
    fun movedVisible(order: List<FloatingLayoutItem>, island: Boolean, from: Int, to: Int): List<FloatingLayoutItem> {
        if (island) return moved(order, from, to)
        val shown = visible(order, island)
        if (from !in shown.indices || to !in shown.indices || from == to) return order
        val rearranged = moved(shown, from, to).toMutableList()
        val cutoutAt = order.indexOf(FloatingLayoutItem.CUTOUT)
        if (cutoutAt < 0) return rearranged
        val movedItem = shown[from]
        val before = order.subList(0, cutoutAt).lastOrNull { it != movedItem }
        rearranged.add(if (before == null) 0 else rearranged.indexOf(before) + 1, FloatingLayoutItem.CUTOUT)
        return rearranged
    }

    /** The drawn items on each side of the camera. */
    fun sides(order: List<FloatingLayoutItem>): FloatingLayoutSides {
        val cutoutAt = order.indexOf(FloatingLayoutItem.CUTOUT)
        val drawn = { items: List<FloatingLayoutItem> -> items.filter { it != FloatingLayoutItem.CUTOUT } }
        return if (cutoutAt < 0) {
            FloatingLayoutSides(drawn(order), emptyList())
        } else {
            FloatingLayoutSides(drawn(order.subList(0, cutoutAt)), drawn(order.subList(cutoutAt + 1, order.size)))
        }
    }
}

/**
 * One drawn piece of a side. The reading and a secondary value right beside it make one
 * block, as they always have. The delta has no block of its own while something shares its
 * side: it goes under the block before it, or the one after it when it leads the side.
 */
internal sealed interface FloatingLayoutBlock {
    /** Whether the delta sits with this block. */
    val withDelta: Boolean

    /** The reading, with the secondary value [secondaryFirst] or after it when [withSecondary]. */
    data class Reading(
        val withSecondary: Boolean,
        val secondaryFirst: Boolean,
        override val withDelta: Boolean = false,
    ) : FloatingLayoutBlock

    data class Secondary(override val withDelta: Boolean = false) : FloatingLayoutBlock
    data class Arrow(override val withDelta: Boolean = false) : FloatingLayoutBlock

    /** The delta alone on its side. */
    data object Delta : FloatingLayoutBlock {
        override val withDelta: Boolean get() = false
    }
}

/**
 * The blocks one side draws from [items], leaving out what is not shown: a secondary value
 * when [showSecondary] is false, the arrow when [showArrow] is false, the delta when
 * [showDelta] is false.
 */
internal fun floatingLayoutBlocks(
    items: List<FloatingLayoutItem>,
    showSecondary: Boolean,
    showArrow: Boolean,
    showDelta: Boolean,
): List<FloatingLayoutBlock> {
    val shown = items.filter {
        when (it) {
            FloatingLayoutItem.SECONDARY -> showSecondary
            FloatingLayoutItem.ARROW -> showArrow
            FloatingLayoutItem.DELTA -> showDelta
            FloatingLayoutItem.VALUE -> true
            FloatingLayoutItem.CUTOUT -> false
        }
    }
    val valueAt = shown.indexOf(FloatingLayoutItem.VALUE)
    val secondaryAt = shown.indexOf(FloatingLayoutItem.SECONDARY)
    val joined = valueAt >= 0 && secondaryAt >= 0 && kotlin.math.abs(valueAt - secondaryAt) == 1
    val blocks = shown.mapNotNull { item ->
        when (item) {
            FloatingLayoutItem.VALUE -> FloatingLayoutBlock.Reading(withSecondary = joined, secondaryFirst = joined && secondaryAt < valueAt)
            FloatingLayoutItem.SECONDARY -> if (joined) null else FloatingLayoutBlock.Secondary()
            FloatingLayoutItem.ARROW -> FloatingLayoutBlock.Arrow()
            FloatingLayoutItem.DELTA, FloatingLayoutItem.CUTOUT -> null
        }
    }.toMutableList()
    val deltaAt = shown.indexOf(FloatingLayoutItem.DELTA)
    if (deltaAt < 0) return blocks
    if (blocks.isEmpty()) return listOf(FloatingLayoutBlock.Delta)
    // Blocks drawn from the items before the delta; it goes under the last of them.
    val blocksBefore = shown.subList(0, deltaAt).count {
        it == FloatingLayoutItem.VALUE || it == FloatingLayoutItem.ARROW ||
            (it == FloatingLayoutItem.SECONDARY && !joined)
    }
    val host = if (blocksBefore > 0) blocksBefore - 1 else 0
    blocks[host] = when (val block = blocks[host]) {
        is FloatingLayoutBlock.Reading -> block.copy(withDelta = true)
        is FloatingLayoutBlock.Secondary -> block.copy(withDelta = true)
        is FloatingLayoutBlock.Arrow -> block.copy(withDelta = true)
        FloatingLayoutBlock.Delta -> block
    }
    return blocks
}
