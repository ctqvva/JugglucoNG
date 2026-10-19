package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthLiveSemanticsTests {

    // ---- classifier ----

    @Test
    fun rawComparison_allowsOneQuantumOfNoiseOnly() {
        assertTrue(ICanHealthLiveSemanticsClassifier.isMatch(545, 545))
        assertTrue(ICanHealthLiveSemanticsClassifier.isMatch(545, 546))
        assertTrue(ICanHealthLiveSemanticsClassifier.isMatch(545, 544))
        assertFalse(ICanHealthLiveSemanticsClassifier.isMatch(545, 547))
        // The captured global iCGM-t3 pairs, e.g. 1755 live vs 597 history at 5.97 mmol/L.
        assertFalse(ICanHealthLiveSemanticsClassifier.isMatch(1755, 597))
    }

    @Test
    fun gate_requiresThreeDecisiveObservationsOfOneKind() {
        assertEquals(ICanHealthLiveSemantics.UNKNOWN, classify(0, 0))
        assertEquals(ICanHealthLiveSemantics.UNKNOWN, classify(2, 0))
        assertEquals(ICanHealthLiveSemantics.UNKNOWN, classify(1, 1))
        assertEquals(ICanHealthLiveSemantics.UNKNOWN, classify(3, 1))
        assertEquals(ICanHealthLiveSemantics.UNKNOWN, classify(1, 3))
        assertEquals(ICanHealthLiveSemantics.UNKNOWN, classify(3, 3))
        assertEquals(ICanHealthLiveSemantics.VERIFIED_GLUCOSE, classify(3, 0))
        assertEquals(ICanHealthLiveSemantics.TRIGGER_ONLY, classify(0, 3))
        // More evidence of the same kind must not flip the verdict back.
        assertEquals(ICanHealthLiveSemantics.VERIFIED_GLUCOSE, classify(7, 0))
        assertEquals(ICanHealthLiveSemantics.TRIGGER_ONLY, classify(0, 7))
    }

    @Test
    fun evidence_accumulatesUntilTheGateOpens() {
        var evidence = ICanHealthLiveSemanticsEvidence()
        assertEquals(ICanHealthLiveSemantics.UNKNOWN, evidence.verdict)

        evidence = evidence.record(isMatch = true)
        evidence = evidence.record(isMatch = true)
        assertEquals(ICanHealthLiveSemantics.UNKNOWN, evidence.verdict)

        evidence = evidence.record(isMatch = true)
        assertEquals(ICanHealthLiveSemantics.VERIFIED_GLUCOSE, evidence.verdict)

        // A contradicting observation closes the gate again rather than being ignored.
        evidence = evidence.record(isMatch = false)
        assertEquals(ICanHealthLiveSemantics.UNKNOWN, evidence.verdict)
    }

    /**
     * The persisted state is the count pair, so a reload (constructing the data class from the
     * two stored ints) must reproduce the same verdict without any in-memory history.
     */
    @Test
    fun persistedCounts_reproduceTheVerdictAfterReload() {
        val reloadedVerified = ICanHealthLiveSemanticsEvidence(matches = 3, mismatches = 0)
        val reloadedTrigger = ICanHealthLiveSemanticsEvidence(matches = 0, mismatches = 3)
        val reloadedUnknown = ICanHealthLiveSemanticsEvidence(matches = 2, mismatches = 1)

        assertEquals(ICanHealthLiveSemantics.VERIFIED_GLUCOSE, reloadedVerified.verdict)
        assertEquals(ICanHealthLiveSemantics.TRIGGER_ONLY, reloadedTrigger.verdict)
        assertEquals(ICanHealthLiveSemantics.UNKNOWN, reloadedUnknown.verdict)
    }

    // ---- pairing ----

    @Test
    fun pairing_reportsComparisonWhenHistoryArrivesSecond() {
        val pairing = ICanHealthLiveSemanticsPairing()
        assertNull(pairing.observeLive(837, 545))

        val comparison = pairing.observeHistory(837, 545)!!
        assertEquals(837, comparison.sequenceNumber)
        assertEquals(545, comparison.liveRaw)
        assertEquals(545, comparison.historyRaw)
    }

    @Test
    fun pairing_reportsComparisonWhenHistoryArrivesFirst() {
        val pairing = ICanHealthLiveSemanticsPairing()
        assertNull(pairing.observeHistory(837, 545))

        val comparison = pairing.observeLive(837, 545)!!
        assertEquals(545, comparison.liveRaw)
    }

    @Test
    fun pairing_ignoresUnrelatedSequencesAndNegatives() {
        val pairing = ICanHealthLiveSemanticsPairing()
        assertNull(pairing.observeLive(100, 545))
        assertNull(pairing.observeHistory(103, 597))
        assertNull(pairing.observeLive(-1, 545))
        assertNull(pairing.observeHistory(-1, 597))
    }

    @Test
    fun pairing_countsEachSequenceOnceEvenWhenBothLanesRedeliver() {
        val pairing = ICanHealthLiveSemanticsPairing()
        assertNull(pairing.observeLive(837, 545))
        assertEquals(837, pairing.observeHistory(837, 545)!!.sequenceNumber)

        // A sensor can redeliver the same frame; that must not fabricate a second observation.
        assertNull(pairing.observeLive(837, 545))
        assertNull(pairing.observeHistory(837, 545))
    }

    @Test
    fun pairing_forgetsTheOldestPendingSequenceBeyondTheWindow() {
        val pairing = ICanHealthLiveSemanticsPairing(windowSize = 2)
        assertNull(pairing.observeLive(1, 100))
        assertNull(pairing.observeLive(2, 200))
        assertNull(pairing.observeLive(3, 300)) // evicts sequence 1's live observation

        // The evicted live value is gone, so its history cannot pair with anything.
        assertNull(pairing.observeHistory(1, 100))
        // The newer sequences survived and still pair.
        assertEquals(3, pairing.observeHistory(3, 300)!!.sequenceNumber)
    }

    // ---- publish policy ----

    @Test
    fun policy_verifiedSensorPublishesEvenWithTheExperimentalFlag() {
        assertEquals(
            ICanHealthLivePublishMode.PUBLISH_LIVE,
            ICanHealthLivePublishPolicy.modeFor(
                semantics = ICanHealthLiveSemantics.VERIFIED_GLUCOSE,
                suppressUnverifiedLiveGlucose = true,
            )
        )
    }

    @Test
    fun policy_triggerOnlyNeverPublishesTheLiveValue() {
        assertEquals(
            ICanHealthLivePublishMode.HISTORY_TRIGGER,
            ICanHealthLivePublishPolicy.modeFor(
                semantics = ICanHealthLiveSemantics.TRIGGER_ONLY,
                suppressUnverifiedLiveGlucose = false,
            )
        )
        assertEquals(
            ICanHealthLivePublishMode.HISTORY_TRIGGER,
            ICanHealthLivePublishPolicy.modeFor(
                semantics = ICanHealthLiveSemantics.TRIGGER_ONLY,
                suppressUnverifiedLiveGlucose = true,
            )
        )
    }

    @Test
    fun policy_unknownKeepsCurrentBehaviourUntilTheFlagIsFlipped() {
        assertEquals(
            ICanHealthLivePublishMode.PUBLISH_LIVE,
            ICanHealthLivePublishPolicy.modeFor(
                semantics = ICanHealthLiveSemantics.UNKNOWN,
                suppressUnverifiedLiveGlucose = false,
            )
        )
        assertEquals(
            ICanHealthLivePublishMode.HISTORY_TRIGGER,
            ICanHealthLivePublishPolicy.modeFor(
                semantics = ICanHealthLiveSemantics.UNKNOWN,
                suppressUnverifiedLiveGlucose = true,
            )
        )
    }

    @Test
    fun gate_withDefaultFlagsUnknownPublishesWhileTriggerOnlySuppresses() {
        assertFalse(ICanHealthExperimental.SUPPRESS_UNVERIFIED_LIVE_GLUCOSE)
        assertTrue(
            ICanHealthLivePublishPolicy.shouldPublishLive(
                semantics = ICanHealthLiveSemantics.UNKNOWN,
                suppressUnverifiedLiveGlucose = ICanHealthExperimental.SUPPRESS_UNVERIFIED_LIVE_GLUCOSE,
            )
        )
        assertFalse(
            ICanHealthLivePublishPolicy.shouldPublishLive(
                semantics = ICanHealthLiveSemantics.TRIGGER_ONLY,
                suppressUnverifiedLiveGlucose = ICanHealthExperimental.SUPPRESS_UNVERIFIED_LIVE_GLUCOSE,
            )
        )
    }

    private fun classify(matches: Int, mismatches: Int) =
        ICanHealthLiveSemanticsClassifier.classify(matches, mismatches)
}
