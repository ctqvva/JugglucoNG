/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
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
/*                                                                                   */
/*      Sun Apr 16 20:56:20 CEST 2023                                                 */


package tk.glucodata.NovoPen;

import static tk.glucodata.Log.doLog;
import static tk.glucodata.Log.showbytes;
import static tk.glucodata.ScanNfcV.failure;
import static tk.glucodata.ScanNfcV.getvibrator;
import static tk.glucodata.ScanNfcV.startvibration;

import android.content.Context;
import android.nfc.Tag;

import tk.glucodata.NovoPen.opennov.OpContext;
import tk.glucodata.NovoPen.opennov.OpenNov;

import tk.glucodata.Applic;
import tk.glucodata.InsulinPenManager;
import tk.glucodata.Log;
import tk.glucodata.MainActivity;
import tk.glucodata.R;

/**
 * Reads a NovoPen 6 / NovoPen Echo Plus over NFC and hands the doses to
 * {@link InsulinPenManager}, which puts the confirmed ones in the journal.
 */
public class Scan {
    static final private String LOG_ID = "Scan";

    static public void onTag(MainActivity act, Tag tag) {
        onTag(act, tag, false);
    }

    /**
     * The one read path. {@code unattended} is the background receiver's case: the app
     * is not in front, so the result goes to the journal and a notification rather than
     * to the review sheet.
     */
    static public void onTag(Context act, Tag tag, boolean unattended) {
        // Every ISO-DEP tag the phone sees while Juggluco is in front lands here. Without
        // pen support switched on, that tag is somebody's bank card and none of our business.
        if (!InsulinPenManager.isEnabled()) {
            {if(doLog){Log.i(LOG_ID, "IsoDep tag ignored: insulin pen support is off");};}
            return;
        }
        {if(doLog){showbytes("onTag", tag.getId());};}
        var vibrator = getvibrator(act);
        startvibration(vibrator);
        var openNov = new OpenNov();
        var op = openNov.processTag(tag);
        vibrator.cancel();

        if (!openNov.spokeProtocol()) {
            // Not a pen at all: it never answered the pen application select.
            {if(doLog){Log.i(LOG_ID, "IsoDep tag is not an insulin pen");};}
            return;
        }
        if (op == null) {
            // The pen answered, so it is in range and understood — the read just did not
            // last long enough. Telling the reader to hold still beats "try again".
            Log.e(LOG_ID, "processTag failed");
            failure(vibrator);
            Applic.Toaster(act.getString(R.string.insulin_pen_read_cut_short));
            return;
        } else if (op.specification == null) {
            Log.e(LOG_ID, "op.specification==null");
        } else if (op.specification.getSerial() == null) {
            Log.e(LOG_ID, "pen serial missing");
        } else if (op.doses == null) {
            Log.e(LOG_ID, "op.doses==null");
        } else {
            if (unattended) {
                InsulinPenManager.onScannedUnattended(op.specification.getSerial(), op.doses);
            } else {
                InsulinPenManager.onScanned(op.specification.getSerial(), op.doses);
            }
            return;
        }

        failure(vibrator);
        Applic.Toaster(act.getString(R.string.penfailed));
    }
}
