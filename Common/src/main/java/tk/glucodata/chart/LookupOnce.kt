package tk.glucodata.chart

/**
 * Asks once per key and keeps the answer, null included.
 *
 * `getOrPut` asks again whenever the kept value is null, and for a chart "no calibration" is the
 * usual answer: the lookup meant to run once per sensor ran for every point of the timeline.
 */
internal class LookupOnce<K, V : Any>(private val lookup: (K) -> V?) {
    private val known = HashMap<K, V?>()

    operator fun get(key: K): V? =
        if (known.containsKey(key)) known[key] else lookup(key).also { known[key] = it }
}
