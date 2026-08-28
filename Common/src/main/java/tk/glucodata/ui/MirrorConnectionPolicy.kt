package tk.glucodata.ui

internal enum class QuickPairKind { LOCAL, HYBRID }

internal data class MirrorConnectionSnapshot(
    val index: Int,
    val label: String?,
    val isIce: Boolean,
    val iceSide: Boolean,
    val isWearOs: Boolean,
    val sendsData: Boolean,
    val receivesData: Boolean,
    val isDeactivated: Boolean,
    val isPending: Boolean
)

internal fun reusableQuickPairIndex(
    connections: List<MirrorConnectionSnapshot>,
    kind: QuickPairKind
): Int? = connections.asReversed().firstOrNull { connection ->
    !connection.isWearOs &&
        connection.isPending &&
        connection.sendsData &&
        !connection.receivesData &&
        when (kind) {
            QuickPairKind.HYBRID -> connection.isIce && !connection.iceSide
            QuickPairKind.LOCAL -> !connection.isIce && connection.label?.startsWith("auto") == true
        }
}?.index

internal fun cloneConnectionIndices(
    connections: List<MirrorConnectionSnapshot>
): List<Int> = connections.filterNot { it.isWearOs }.map { it.index }

internal fun isCloneEnabled(
    connections: List<MirrorConnectionSnapshot>
): Boolean = connections.any { !it.isWearOs && !it.isDeactivated }

internal fun shouldDeleteAnnouncementSender(
    connection: MirrorConnectionSnapshot?,
    ownedByAnnouncement: Boolean
): Boolean = ownedByAnnouncement &&
    connection != null &&
    reusableQuickPairIndex(listOf(connection), QuickPairKind.LOCAL) == connection.index

internal fun mirrorDisplayPort(isIce: Boolean, rawPort: String?): String? =
    rawPort.takeUnless { isIce }
