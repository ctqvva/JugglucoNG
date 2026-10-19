package tk.glucodata

object LiveContinuityPolicy {
    data class ContinuitySyncDecision(
        val shouldRequestContinuitySync: Boolean,
        val previousBucket: Long,
        val currentBucket: Long,
        val missingBuckets: Int,
    )

    @JvmStatic
    fun readingBucket(readingTimeMs: Long, bucketSizeMs: Long): Long {
        if (readingTimeMs <= 0L || bucketSizeMs <= 0L) {
            return -1L
        }
        val halfBucket = bucketSizeMs / 2L
        return Math.floorDiv(readingTimeMs + halfBucket, bucketSizeMs)
    }

    @JvmStatic
    fun decideContinuitySync(
        previousReadingMs: Long,
        currentReadingMs: Long,
        bucketSizeMs: Long,
        maxMissingBuckets: Int,
    ): ContinuitySyncDecision {
        val previousBucket = readingBucket(previousReadingMs, bucketSizeMs)
        val currentBucket = readingBucket(currentReadingMs, bucketSizeMs)
        if (previousBucket < 0L || currentBucket <= previousBucket || maxMissingBuckets <= 0) {
            return ContinuitySyncDecision(
                shouldRequestContinuitySync = false,
                previousBucket = previousBucket,
                currentBucket = currentBucket,
                missingBuckets = 0,
            )
        }

        val missingBucketsLong = (currentBucket - previousBucket - 1L).coerceAtLeast(0L)
        val missingBuckets = missingBucketsLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        return ContinuitySyncDecision(
            shouldRequestContinuitySync = missingBuckets in 1..maxMissingBuckets,
            previousBucket = previousBucket,
            currentBucket = currentBucket,
            missingBuckets = missingBuckets,
        )
    }
}
