package tk.glucodata.drivers.ottai

/** Serial-owner gate: wait for the last MTU callback, then discover once per connection. */
internal class OttaiFirmwareDiscovery(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val discover: () -> Unit,
) {
    private var generation = 0
    private var started = false
    private var closed = false

    fun connected(mtuRequested: Boolean) = arm(if (mtuRequested) 2_500L else 300L)
    fun mtuChanged() = arm(1_250L)

    private fun arm(delayMs: Long) {
        if (started || closed) return
        val owner = ++generation
        schedule(delayMs) {
            if (!closed && !started && owner == generation) {
                started = true
                discover()
            }
        }
    }

    fun close() {
        closed = true
        generation++
    }
}
