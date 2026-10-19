package tk.glucodata.drivers.sibionics

internal object SibionicsStartupRecovery {
    /** Only replay the exact input range represented by the persisted cursor. */
    fun sourcesForCheckpoint(
        samples: List<SibionicsSourceSample>,
        nextIndex: Int,
        variant: SibionicsConstants.Variant,
    ): List<SibionicsSourceSample>? {
        if (nextIndex <= 1) return null
        val sources = samples.takeWhile { it.index < nextIndex }
        if (!SibionicsAlgorithmRebuilder.isContiguousFromSensorStart(sources) ||
            sources.last().index != nextIndex - 1 ||
            sources.any { it.variantId != variant.ordinal }
        ) return null
        return sources
    }
}
