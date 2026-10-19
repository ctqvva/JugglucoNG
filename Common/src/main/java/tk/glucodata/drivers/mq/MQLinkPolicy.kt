package tk.glucodata.drivers.mq

internal object MQLinkPolicy {
    fun hasRecentActivity(
        nowMs: Long,
        connectedAtMs: Long,
        lastFrameAtMs: Long,
        firstFrameTimeoutMs: Long,
        frameTimeoutMs: Long,
    ): Boolean {
        val hasFrame = lastFrameAtMs >= connectedAtMs && lastFrameAtMs > 0L
        val since = if (hasFrame) lastFrameAtMs else connectedAtMs
        val timeout = if (hasFrame) frameTimeoutMs else firstFrameTimeoutMs
        return since > 0L && nowMs - since < timeout
    }
}
