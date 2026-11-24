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
/*      Fri Jan 27 15:30:31 CET 2023                                                 */


package tk.glucodata;
import android.nfc.Tag;
import android.nfc.tech.NfcV;

import tk.glucodata.drivers.libre2.Libre2ManagedDriver;

/**
 * Libre 2 Gen2 NFC commands. The authentication, the encrypted FRAM read and enable streaming
 * are done by the Kotlin driver in {@code drivers/libre2}; no vendor library is involved.
 */
class Gen2 {
static private final String LOG_ID="Gen2";

static byte[] getuspatch(NfcV nfc, byte[] uid, int start, int len) {
	return Libre2ManagedDriver.gen2ReadFram(nfc, uid, start, len);
	}

static final int getversion(byte[] info) {
 	return Natives.getinfogen(info);
	}

static boolean activate(final Tag tag) {
	try {
		byte[] uid=tag.getId();
		final NfcV nfc = NfcV.get(tag);
		nfc.connect();
		boolean res=Libre2ManagedDriver.gen2Activate(nfc, uid);
		nfc.close();
		return res;
		}
	catch (Throwable error) {
		String mess=error!=null?error.getMessage():null;
		if(mess==null) {
			mess="error";
			}
		Log.stack(LOG_ID ,mess,error);
		}
	return false;
	}

static boolean gen2enablestreaming(final Tag tag, final byte[] info, final boolean newSensor) {
	try {
		byte[] uid=tag.getId();
		final NfcV nfc = NfcV.get(tag);
		nfc.connect();
		boolean res=Libre2ManagedDriver.gen2EnableStreaming(nfc, uid, info, newSensor);
		nfc.close();
		return res;
		}
	catch (Throwable error) {
		String mess=error!=null?error.getMessage():null;
		if(mess==null) {
			mess="error";
			}
		Log.stack(LOG_ID ,mess,error);
		}
	return false;
	}
}
