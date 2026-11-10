package tk.glucodata;

import androidx.annotation.Keep;

/**
 * Bridge between the native LibreView writer and the journal's LibreView entries.
 *
 * The LibreView payload is assembled in {@code net/libreview/libreview.cpp} and
 * {@code newlibre3.cpp}, whose food/insulin/generic arrays were fed only by the legacy
 * native {@code Numdata} store — a store the Compose journal never writes, so nothing a
 * user recorded ever reached LibreView. This object's own static methods are a JNI
 * contract ({@code journalentries.cpp} finds them by name), so they keep their names;
 * inside, they delegate to the registered {@link LibreviewJournalEntriesBridge} instead
 * of resolving the mobile-only journal by name (plan P1/Q1). The wearable build (which
 * has no journal database compiled in) registers nothing and contributes nothing.
 *
 * <p>The native side calls {@link #prepare(boolean)} while sizing its buffer, the three
 * accessors while filling it, and exactly one of {@link #commit()} / {@link #discard()}
 * once it knows whether LibreView accepted the document.
 */
@Keep
public final class LibreviewJournal {
    private LibreviewJournal() {}

    /** Renders what is pending and returns its size in bytes, or 0 when there is nothing. */
    @Keep
    public static int prepare(boolean libre3) {
        return LibreviewJournalEntriesAccess.prepare(libre3);
    }

    @Keep
    public static String foodEntries() {
        return LibreviewJournalEntriesAccess.foodEntries();
    }

    @Keep
    public static String insulinEntries() {
        return LibreviewJournalEntriesAccess.insulinEntries();
    }

    @Keep
    public static String noteEntries() {
        return LibreviewJournalEntriesAccess.noteEntries();
    }
    /** Marks the prepared entries delivered. Only ever called after a successful upload. */
    @Keep
    public static void commit() {
        LibreviewJournalEntriesAccess.commit();
    }

    /** Drops the prepared entries so the next pass rebuilds them. */
    @Keep
    public static void discard() {
        LibreviewJournalEntriesAccess.discard();
    }
}
