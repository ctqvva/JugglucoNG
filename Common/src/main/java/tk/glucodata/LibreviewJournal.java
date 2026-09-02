package tk.glucodata;

import androidx.annotation.Keep;

import java.lang.reflect.Method;

/**
 * Bridge between the native LibreView writer and the journal's LibreView entries.
 *
 * The LibreView payload is assembled in {@code net/libreview/libreview.cpp} and
 * {@code newlibre3.cpp}, whose food/insulin/generic arrays were fed only by the legacy
 * native {@code Numdata} store — a store the Compose journal never writes, so nothing a
 * user recorded ever reached LibreView. This resolves the mobile-only journal by name, the
 * same way {@link NightPost#uploadJournalTreatments(boolean)} does, so the wearable build
 * (which has no journal database compiled in) simply contributes nothing.
 *
 * <p>The native side calls {@link #prepare(boolean)} while sizing its buffer, the three
 * accessors while filling it, and exactly one of {@link #commit()} / {@link #discard()}
 * once it knows whether LibreView accepted the document.
 */
@Keep
public final class LibreviewJournal {
    private static final String LOG_ID = "LibreviewJournal";
    private static final String ENTRIES_CLASS = "tk.glucodata.data.journal.LibreviewJournalEntries";

    private LibreviewJournal() {}

    private static volatile Class<?> entriesClass;
    private static volatile boolean unavailable;

    private static Class<?> entries() {
        if (unavailable) {
            return null;
        }
        Class<?> cached = entriesClass;
        if (cached != null) {
            return cached;
        }
        try {
            cached = Class.forName(ENTRIES_CLASS);
            entriesClass = cached;
            return cached;
        } catch (ClassNotFoundException nfe) {
            // Wearable build: no journal database. Nothing to contribute, and nothing wrong.
            unavailable = true;
            return null;
        } catch (Throwable th) {
            unavailable = true;
            Log.e(LOG_ID, "journal entries unavailable:\n" + Log.stackline(th));
            return null;
        }
    }

    /** Renders what is pending and returns its size in bytes, or 0 when there is nothing. */
    @Keep
    public static int prepare(boolean libre3) {
        final Class<?> cl = entries();
        if (cl == null) {
            return 0;
        }
        try {
            Method method = cl.getMethod("prepare", boolean.class);
            Object result = method.invoke(null, libre3);
            return result instanceof Integer ? (Integer) result : 0;
        } catch (Throwable th) {
            Log.e(LOG_ID, "prepare failed:\n" + Log.stackline(th));
            return 0;
        }
    }

    @Keep
    public static String foodEntries() {
        return fragment("foodEntries");
    }

    @Keep
    public static String insulinEntries() {
        return fragment("insulinEntries");
    }

    @Keep
    public static String noteEntries() {
        return fragment("noteEntries");
    }

    /** Marks the prepared entries delivered. Only ever called after a successful upload. */
    @Keep
    public static void commit() {
        call("commit");
    }

    /** Drops the prepared entries so the next pass rebuilds them. */
    @Keep
    public static void discard() {
        call("discard");
    }

    private static String fragment(String name) {
        final Class<?> cl = entries();
        if (cl == null) {
            return "";
        }
        try {
            Object result = cl.getMethod(name).invoke(null);
            return result instanceof String ? (String) result : "";
        } catch (Throwable th) {
            Log.e(LOG_ID, name + " failed:\n" + Log.stackline(th));
            return "";
        }
    }

    private static void call(String name) {
        final Class<?> cl = entries();
        if (cl == null) {
            return;
        }
        try {
            cl.getMethod(name).invoke(null);
        } catch (Throwable th) {
            Log.e(LOG_ID, name + " failed:\n" + Log.stackline(th));
        }
    }
}
