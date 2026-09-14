package tk.glucodata.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps a [HistoryTimestampIndex] in step with the readings table.
 *
 * Room says only "the table changed", not what changed, so this works out
 * the difference itself from a four-number fingerprint of the table:
 *
 *  - a higher `MAX(id)` with the row count still matching the index after
 *    the new rows are added is an append (a REPLACE of an existing minute
 *    lands here too: new id, same timestamp, count unchanged) — the ordinary
 *    once-a-minute case, and it costs the new rows only;
 *  - a count or oldest-timestamp that no longer matches is a rewrite — a
 *    deletion, a re-sync, an import replacing buckets — and the index is
 *    rebuilt from the timestamps, per sensor, in pages;
 *  - a retag (`UPDATE ... SET sensorSerial`) changes neither count nor ids,
 *    so the writers that do it call [invalidate] — the fingerprint is the
 *    safety net under the hooks, not the other way round.
 *
 * Readers take the index through [withIndex], which holds the lock for the
 * duration of their merge, so a refresh never runs under a reader.
 *
 * One per database: the repository is instantiated freely, the index is not.
 */
class HistoryTimestampIndexTracker(private val dao: HistoryDao) {
    private val mutex = Mutex()
    private val index = HistoryTimestampIndex()
    private var lastMaxId = 0L
    private var stale = true

    private val _rebuilds = MutableStateFlow(0L)

    /** Bumped on every rebuild — a consumer caching per-timestamp results derived from the store drops them on a change here. */
    val rebuilds: StateFlow<Long> = _rebuilds

    @Volatile
    var isReady: Boolean = false
        private set

    /** Marks the index stale so the next [refresh] rebuilds it. For writers that change rows in place. */
    fun invalidate() {
        stale = true
    }

    /** Brings the index up to date and runs [block] against it under the lock. */
    suspend fun <T> withIndex(block: (HistoryCoverage) -> T): T = mutex.withLock {
        refreshLocked()
        block(index)
    }

    /** The store's extents as the index knows them; null while nothing has been indexed. */
    suspend fun extents(): TimelineExtents? = mutex.withLock {
        refreshLocked()
        val earliest = index.earliest ?: return@withLock null
        val latest = index.latest ?: return@withLock null
        TimelineExtents(earliest, latest, index.readingCount)
    }

    private suspend fun refreshLocked() {
        val fingerprint = dao.getTableFingerprint()
        val maxId = fingerprint.maxId ?: 0L
        if (!isReady || stale) {
            rebuild(fingerprint)
            return
        }
        if (maxId > lastMaxId) {
            dao.getIndexRowsAfter(lastMaxId).forEach { index.add(it.sensorSerial, it.timestamp) }
            lastMaxId = maxId
        }
        if (!agreesWith(fingerprint)) {
            // A write can land between the fingerprint and the delta; that is
            // not a rewrite, so look again before paying for one.
            val again = dao.getTableFingerprint()
            val againMaxId = again.maxId ?: 0L
            if (againMaxId > lastMaxId) {
                dao.getIndexRowsAfter(lastMaxId).forEach { index.add(it.sensorSerial, it.timestamp) }
                lastMaxId = againMaxId
            }
            if (!agreesWith(again)) rebuild(again)
        }
    }

    private fun agreesWith(fingerprint: HistoryTableFingerprint): Boolean =
        index.readingCount == fingerprint.rowCount &&
            index.earliest == fingerprint.minTimestamp &&
            index.latest == fingerprint.maxTimestamp

    private suspend fun rebuild(fingerprint: HistoryTableFingerprint) {
        index.clear()
        for (serial in dao.getAllSensorSerials()) {
            val timestamps = ArrayList<Long>()
            var after = Long.MIN_VALUE
            while (true) {
                val page = dao.getTimestampsForSensorPage(serial, after, REBUILD_PAGE)
                if (page.isEmpty()) break
                timestamps.addAll(page)
                after = page.last()
                if (page.size < REBUILD_PAGE) break
            }
            index.replaceSensor(serial, timestamps.toLongArray())
        }
        lastMaxId = fingerprint.maxId ?: 0L
        stale = false
        isReady = true
        _rebuilds.value = _rebuilds.value + 1
    }

    private companion object {
        const val REBUILD_PAGE = 50_000
    }
}

/** The whole store in three numbers, for the parts of the UI that used to read the whole list to learn them. */
data class TimelineExtents(
    val earliestMs: Long,
    val latestMs: Long,
    val readingCount: Int,
)
