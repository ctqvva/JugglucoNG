/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */

package tk.glucodata;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

// Builds the IOB devicestatus document for Nightscout and decides when one is
// due. Nightscout's IOB plugin (lib/plugins/iob.js) reads openaps.iob first
// and ignores devicestatus entries older than 30 minutes, so the cadence has
// to stay well inside that window while values are moving. The standard
// openaps field carries classic IOB: consumers (AAPS followers, caregivers,
// loops) read it as insulin still on board, and eIOB is systematically
// smaller, so publishing eIOB there would understate active insulin. eIOB and
// COB travel in a separate "jugglucong" object instead; Nightscout stores
// unknown devicestatus fields and serves them back over its API.
// openaps.iob.activity is deliberately absent: OpenAPS defines it as insulin
// activity per minute, which is not a value the journal computes.
public class NightscoutIobDeviceStatus {
    // Values are moving: upload at most every 5 minutes. Idle at zero: fall
    // back to the same slow cadence as the battery devicestatus.
    static final long FAST_INTERVAL_MILLIS = 5L * 60L * 1000L;
    static final long SLOW_INTERVAL_MILLIS = 15L * 60L * 1000L;
    // The ancillary upload path fetches its own token when the cache is empty,
    // but at most this often, so a refusing server is asked once per interval
    // instead of on every devicestatus attempt.
    static final long TOKEN_RETRY_MILLIS = FAST_INTERVAL_MILLIS;
    // Below half of the 0.01-unit display quantum IOB counts as zero.
    private static final float VALUE_QUANTUM = 0.01f;

    private NightscoutIobDeviceStatus() {
    }

    // True once enough time has passed for an upload to even be considered —
    // callers use this as a cheap gate before computing the journal snapshot.
    static boolean fastIntervalElapsed(long nowMillis, long lastUploadMillis) {
        return lastUploadMillis <= 0L || nowMillis - lastUploadMillis >= FAST_INTERVAL_MILLIS;
    }

    // True when the ancillary path may spend a network round trip on a token
    // request. A clock that moved backwards must not block requests forever.
    static boolean tokenRetryDue(long nowMillis, long lastAttemptMillis) {
        return lastAttemptMillis <= 0L
                || nowMillis < lastAttemptMillis
                || nowMillis - lastAttemptMillis >= TOKEN_RETRY_MILLIS;
    }

    // Decides whether a devicestatus is due. Fast cadence while insulin is on
    // board or any value moved since the last upload; slow cadence once IOB
    // has settled at zero so an idle night does not spam the endpoint.
    static boolean shouldUpload(
            long nowMillis,
            long lastUploadMillis,
            float iob,
            float eiob,
            float cob,
            float lastIob,
            float lastEiob,
            float lastCob
    ) {
        if (lastUploadMillis <= 0L)
            return true;
        final long elapsed = nowMillis - lastUploadMillis;
        if (elapsed < FAST_INTERVAL_MILLIS)
            return false;
        final boolean changed = differs(iob, lastIob) || differs(eiob, lastEiob) || differs(cob, lastCob);
        final boolean insulinOnBoard = Float.isFinite(iob) && Math.abs(iob) >= VALUE_QUANTUM / 2f;
        if (insulinOnBoard || changed)
            return true;
        return elapsed >= SLOW_INTERVAL_MILLIS;
    }

    private static boolean differs(float value, float last) {
        if (!Float.isFinite(value) || !Float.isFinite(last))
            return Float.isFinite(value) != Float.isFinite(last);
        return Math.round(value / VALUE_QUANTUM) != Math.round(last / VALUE_QUANTUM);
    }

    // The devicestatus JSON array, or null when there is no classic IOB to
    // report — without openaps.iob.iob the document would carry nothing
    // Nightscout's IOB plugin can use. eIOB and COB are included only when
    // the journal has data of that kind. All values are insulin units and
    // grams; glucose units play no role here.
    static String buildDocument(long nowMillis, float iob, float eiob, float cob) {
        if (!Float.isFinite(iob))
            return null;
        final String timestamp = isoTimestamp(nowMillis);
        final StringBuilder out = new StringBuilder(224);
        out.append("[{\"device\":\"JugglucoNG\",\"created_at\":\"").append(timestamp)
                .append("\",\"openaps\":{\"iob\":{\"iob\":").append(formatUnits(iob))
                .append(",\"timestamp\":\"").append(timestamp)
                .append("\"}},\"jugglucong\":{\"iob\":").append(formatUnits(iob));
        if (Float.isFinite(eiob))
            out.append(",\"eiob\":").append(formatUnits(eiob));
        if (Float.isFinite(cob))
            out.append(",\"cob\":").append(formatUnits(cob));
        out.append("}}]");
        return out.toString();
    }

    // Same shape the native battery devicestatus emits (addNightscoutDateStringGMT).
    static String isoTimestamp(long millis) {
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date(millis));
    }

    private static String formatUnits(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
