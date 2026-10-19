// JugglucoNG — iCanHealth (Sinocare iCan i3/i6/i7) Driver
// ICanHealthLiveSemantics.kt — what the live 0x2AA7 value field means on a given sensor.
//
// Verified evidence behind the three-way split (notes §10.8):
// - iCGM-t6: the live raw value equals the history record at the same sequence exactly across
//   the whole captured series (raw 545 == 545) — the live field is glucose.
// - global iCGM-t3: it does not (1755 vs 5.97 mmol/L over 8 samples, no monotone or affine
//   relation, not a CRC) — the live packet is only a "one new record is available" trigger.
// - CN iCGM-t3: a user report says the field looked like plausible glucose, but no packet-level
//   evidence exists yet, so an unanswered serial starts at UNKNOWN.

package tk.glucodata.drivers.icanhealth

import kotlin.math.abs

/** What the live 0x2AA7 value field means on a given sensor. */
enum class ICanHealthLiveSemantics {
    /** Not enough same-sequence evidence yet; the driver keeps its historical behaviour. */
    UNKNOWN,

    /** The live raw value matches the history record at the same sequence. */
    VERIFIED_GLUCOSE,

    /** The live raw value carries no glucose; the packet means "a new record is available". */
    TRIGGER_ONLY,
}

/**
 * Accumulated per-sensor evidence from comparing live and history raw values at the same sequence.
 *
 * The counts, not the verdict, are what gets persisted: the verdict is re-derived from them, so a
 * stored answer cannot go stale if the threshold rule ever changes.
 */
internal data class ICanHealthLiveSemanticsEvidence(
    val matches: Int = 0,
    val mismatches: Int = 0,
) {
    val verdict: ICanHealthLiveSemantics
        get() = ICanHealthLiveSemanticsClassifier.classify(matches, mismatches)

    fun record(isMatch: Boolean): ICanHealthLiveSemanticsEvidence =
        if (isMatch) copy(matches = matches + 1) else copy(mismatches = mismatches + 1)
}

/**
 * The gate rule: three decisive observations of one kind and none of the other.
 *
 * Deliberately conservative in both directions — any mixed evidence stays UNKNOWN, which keeps
 * the driver on its current behaviour.
 */
internal object ICanHealthLiveSemanticsClassifier {

    const val REQUIRED_DECISIVE_OBSERVATIONS = 3

    /** The raw field is hundredths of mmol/L; one unit of quantisation is measurement noise. */
    const val RAW_MATCH_TOLERANCE = 1

    fun isMatch(liveRaw: Int, historyRaw: Int): Boolean =
        abs(liveRaw - historyRaw) <= RAW_MATCH_TOLERANCE

    fun classify(matches: Int, mismatches: Int): ICanHealthLiveSemantics = when {
        matches >= REQUIRED_DECISIVE_OBSERVATIONS && mismatches == 0 ->
            ICanHealthLiveSemantics.VERIFIED_GLUCOSE

        mismatches >= REQUIRED_DECISIVE_OBSERVATIONS && matches == 0 ->
            ICanHealthLiveSemantics.TRIGGER_ONLY

        else -> ICanHealthLiveSemantics.UNKNOWN
    }
}

/**
 * Pairs live and history raw values per sequence, whichever arrives first, and reports the
 * comparison once both sides are in.
 *
 * One comparison per sequence, ever: the sensors redeliver both lanes, and letting a redelivery
 * count again would let a looping packet fabricate a three-in-a-row verdict. The window bounds
 * memory on a long-running connection.
 */
internal class ICanHealthLiveSemanticsPairing(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
) {

    internal data class Comparison(
        val sequenceNumber: Int,
        val liveRaw: Int,
        val historyRaw: Int,
    )

    private class Sides {
        var liveRaw: Int? = null
        var historyRaw: Int? = null
    }

    private val pending = LinkedHashMap<Int, Sides>()
    private val compared = LinkedHashSet<Int>()

    fun observeLive(sequenceNumber: Int, liveRaw: Int): Comparison? =
        observe(sequenceNumber, liveRaw = liveRaw, historyRaw = null)

    fun observeHistory(sequenceNumber: Int, historyRaw: Int): Comparison? =
        observe(sequenceNumber, liveRaw = null, historyRaw = historyRaw)

    private fun observe(sequenceNumber: Int, liveRaw: Int?, historyRaw: Int?): Comparison? {
        if (sequenceNumber < 0 || sequenceNumber in compared) {
            return null
        }
        val sides = pending.getOrPut(sequenceNumber) { Sides() }
        if (liveRaw != null) sides.liveRaw = liveRaw
        if (historyRaw != null) sides.historyRaw = historyRaw
        val live = sides.liveRaw
        val history = sides.historyRaw
        if (live == null || history == null) {
            trimPending()
            return null
        }
        pending.remove(sequenceNumber)
        compared.add(sequenceNumber)
        while (compared.size > windowSize) {
            compared.remove(compared.first())
        }
        return Comparison(sequenceNumber, live, history)
    }

    private fun trimPending() {
        // Evict by sequence, not insertion order: a re-observed old sequence must not push a
        // newer one out of the window it was kept for.
        while (pending.size > windowSize) {
            val oldest = pending.keys.minOrNull() ?: return
            pending.remove(oldest)
        }
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 24
    }
}

/** How the live publish path must treat the value carried by a live frame. */
internal enum class ICanHealthLivePublishMode {
    /** Forward the decoded live value as glucose, exactly as the driver always did. */
    PUBLISH_LIVE,

    /** Treat the frame only as "a new record is available" and let the history lane deliver it. */
    HISTORY_TRIGGER,
}

internal object ICanHealthLivePublishPolicy {

    fun modeFor(
        semantics: ICanHealthLiveSemantics,
        suppressUnverifiedLiveGlucose: Boolean,
    ): ICanHealthLivePublishMode = when (semantics) {
        ICanHealthLiveSemantics.VERIFIED_GLUCOSE -> ICanHealthLivePublishMode.PUBLISH_LIVE
        ICanHealthLiveSemantics.TRIGGER_ONLY -> ICanHealthLivePublishMode.HISTORY_TRIGGER
        ICanHealthLiveSemantics.UNKNOWN ->
            if (suppressUnverifiedLiveGlucose) {
                ICanHealthLivePublishMode.HISTORY_TRIGGER
            } else {
                ICanHealthLivePublishMode.PUBLISH_LIVE
            }
    }

    /** Boolean form of [modeFor] for call sites that only need the gate. */
    fun shouldPublishLive(
        semantics: ICanHealthLiveSemantics,
        suppressUnverifiedLiveGlucose: Boolean,
    ): Boolean =
        modeFor(semantics, suppressUnverifiedLiveGlucose) == ICanHealthLivePublishMode.PUBLISH_LIVE
}
