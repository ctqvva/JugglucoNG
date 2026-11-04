// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from upstream Juggluco 11.5.6.
package tk.glucodata;

import androidx.annotation.Keep;

/** One consistent snapshot of the native Clarity uploader, populated by JNI. Times are UTC seconds. */
@Keep
public final class ClarityStatus {
    public boolean available, enabled, libre3History, numbers;
    /** Tokens of an interrupted sign-in are saved; finishing it resumes that sign-in. */
    public boolean loginPending;
    public long since, lastSuccess, acknowledgedCount, acknowledgedLast;
    public long pendingCount, pendingFirst, pendingLast;
    public String account = "", status = "";
}
