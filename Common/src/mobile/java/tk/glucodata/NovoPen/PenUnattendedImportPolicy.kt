package tk.glucodata.NovoPen

/**
 * What a pen read does when nobody is looking at a review sheet: the receiver
 * activity imports with the app in the background, and there is no chance to untick
 * a dose. So the mode is "everything new", with two consequences that live here, as
 * plain logic, so they can be tested without a pen or a phone.
 */
object PenUnattendedImportPolicy {
    private const val ISO_DEP = "android.nfc.tech.IsoDep"

    sealed class Plan {
        /** Nothing to write and nothing to ask about. */
        data object NothingNew : Plan()

        /** Write these, in the pen's insulin; air shots are already out. */
        data class Import(val doses: List<PenDose>) : Plan()

        /**
         * The pen has no insulin chosen yet, so the import has nothing to name the
         * doses with: hand the whole fresh list to the review sheet as a foreground
         * scan would, air shots included, since the sheet shows those pre-unticked.
         */
        data class Review(val doses: List<PenDose>) : Plan()
    }

    /**
     * [fresh] is what a foreground scan would have offered: inside the review window
     * and not in the journal. Air shots are dropped rather than pre-unticked: nobody
     * is there to untick, and a priming dose in the journal inflates IOB.
     */
    fun plan(fresh: List<PenDose>, hasPreset: Boolean): Plan {
        if (fresh.isEmpty()) return Plan.NothingNew
        if (!hasPreset) return Plan.Review(fresh)
        val therapy = fresh.filterNot(PenDose::priming)
        return if (therapy.isEmpty()) Plan.NothingNew else Plan.Import(therapy)
    }

    /**
     * The pen/sensor split, the rule MainActivity uses for a foreground tap: pens are
     * ISO-DEP, the sensors are NfcV. MainActivity looks at the first listed technology;
     * this accepts ISO-DEP anywhere in the list, so it takes everything that rule takes
     * whatever order the system reports. Anything else is not ours to import.
     */
    fun isPenTag(techList: Array<String>?): Boolean =
        techList?.any { it == ISO_DEP } == true
}
