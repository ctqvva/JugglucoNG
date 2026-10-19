// JugglucoNG — iCanHealth (Sinocare iCan i3/i6/i7) Driver
// ICanHealthExperimental.kt — driver-local switches for behaviour whose evidence is incomplete.

package tk.glucodata.drivers.icanhealth

/**
 * Plain mutable toggles, no settings UI: they exist so a field tester can flip one and reproduce
 * a hypothesis from a report without waiting for a build, and so the choice is greppable.
 */
object ICanHealthExperimental {

    /**
     * Treat live frames of a sensor whose [ICanHealthLiveSemantics] is still UNKNOWN as if they
     * were TRIGGER_ONLY: publish nothing from the live value and let the history lane deliver
     * the reading.
     *
     * Default false, because on the CN iCGM-t3 the live field *looked* like plausible glucose in
     * the one user report we have — suppressing it could take a sensor that works today and make
     * it silent. With this off, an unanswered serial keeps publishing the live value while the
     * classifier collects the same-sequence evidence that settles the question.
     */
    @Volatile
    @JvmField
    var SUPPRESS_UNVERIFIED_LIVE_GLUCOSE: Boolean = false
}
