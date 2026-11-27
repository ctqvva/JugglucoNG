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
/*      Thu Mar 23 21:03:49 CET 2023                                                 */


package tk.glucodata;
import static android.content.Context.RECEIVER_EXPORTED;
import static tk.glucodata.Log.doLog;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import com.eveningoutpost.dexdrip.services.broadcastservice.models.Settings;

public class watchdrip extends BroadcastReceiver {
private static String  LOG_ID="watchdrip";
private static final double MGDL_PER_MMOLL=18.0182;

 static   String  tostring(Bundle bundle) {
        if(bundle==null)
            return "";
        var builder = new StringBuilder();
        builder.append("bundle content\n");
        var keys = bundle.keySet();
        for(var key: keys) {
            builder.append("[" + key + "]<->[" + bundle.get(key) + "]\n");
        }
        return builder.toString();
    } 
        @Override
        public void onReceive(Context context, Intent intent) {
           	{if(doLog) {Log.i(LOG_ID,"onReceive ");};};
		var extras=intent.getExtras();
		if(doLog) Natives.log(tostring(extras));
		var function=extras.getString("FUNCTION","");
		if("cancel_alarm".equals(function)||"snooze_alarm".equals(function)) {
			acknowledgeAlarm(extras,"snooze_alarm".equals(function));
			return;
			}
		if("update_bg_force".equals(function)) {
			String key=extras.getString("PACKAGE",null);
			if(key==null) {
				Log.e(LOG_ID,"no package");
				return;
				}
//			WearInt.settings  = intent.getParcelableExtra("SETTINGS");
			Settings settings= extras.getParcelable("SETTINGS");
			if(settings==null) {
					Log.e(LOG_ID,"settings==null");
					return;
				}
			WearInt.registerClient(key,settings);
			var current=CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout);
			if(current!=null) {
				double mgdl=Applic.unit==1?current.getPrimaryValue()*MGDL_PER_MMOLL:current.getPrimaryValue();
				var newintent=WearInt.mksendglucoseintent(settings,mgdl,current.getRate(),0,current.getTimeMillis());
				newintent.putExtra( "FUNCTION","update_bg_force");
				newintent.setPackage(key);
				Applic.app.sendBroadcast(newintent);
				return;
				}
			var gl=Natives.getlastGlucose();
			if(gl==null)
					return;
			long res=gl[1];
			int glumgL = (int) (res & 0xFFFFFFFFL);
			if (glumgL != 0) {
				int alarm = (int) ((res >> 48) & 0xFFL);
				float rate = SuperGattCallback.packedRate(res);
				var newintent=WearInt.mksendglucoseintent(settings,glumgL/10.0,rate,alarm,  gl[0]*1000L);
				newintent.putExtra( "FUNCTION","update_bg_force");
				newintent.setPackage(key);
				Applic.app.sendBroadcast(newintent);
				}
			}

        	}
/**
 * A registered client (e.g. Gadgetbridge after the watch rejected the alarm call) stops
 * (cancel_alarm) or snoozes (snooze_alarm) the alarm that is ringing, the same way the
 * alarm screen does. Only packages that registered via update_bg_force are accepted.
 */
private static void acknowledgeAlarm(Bundle extras,boolean snooze) {
	String key=extras.getString("PACKAGE",null);
	if(key==null||!WearInt.mapsettings.containsKey(key)) {
		Log.e(LOG_ID,"alarm acknowledgement from unregistered package "+key);
		return;
		}
	final int kind=Notify.resolveAlertKind(-1);
	Log.i(LOG_ID,(snooze?"snooze_alarm":"cancel_alarm")+" from "+key+" kind="+kind);
	if(kind>=0) {
		Integer minutes=null;
		if(snooze) {
			int requested=extras.getInt("SNOOZE_MINUTES",-1);
			minutes=requested>0?requested:tk.glucodata.alerts.AlertActions.defaultSnoozeMinutes(kind,null);
			}
		WearInt.cancelOrigin=key;
		try {
			tk.glucodata.alerts.AlertActions.handle(kind,null,minutes);
		} finally {
			WearInt.cancelOrigin=null;
		}
		tk.glucodata.ui.AlarmActivityAccess.INSTANCE.acknowledge(kind);
		}
	else {
		Notify.stopalarm();
		Notify.cancelAlertNotification();
		}
	WearInt.cancelOrigin=key;
	try {
		WearInt.cancelAlarm(); // other clients; no-op if handle() already sent it
	} finally {
		WearInt.cancelOrigin=null;
	}
	WearInt.sendReply(key,"OK");
	}

static private watchdrip receiver=null;
@SuppressLint("UnspecifiedRegisterReceiverFlag")
static void register() {
	if(receiver==null)
		receiver=new watchdrip();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Applic.app.registerReceiver(receiver, new IntentFilter("com.eveningoutpost.dexdrip.watch.wearintegration.BROADCAST_SERVICE_RECEIVER"),RECEIVER_EXPORTED);
    }
	else
        Applic.app.registerReceiver(receiver, new IntentFilter("com.eveningoutpost.dexdrip.watch.wearintegration.BROADCAST_SERVICE_RECEIVER"));
}
static void unregister() {
	if(receiver!=null) {
        try {
            Applic.app.unregisterReceiver(receiver);
            } 
       catch(Throwable th) {
          Log.stack(LOG_ID,"unregister",th);
            }
        }
	}

public static void set(boolean val) {
	SuperGattCallback.doWearInt=val;
	if(val) {
		WearInt.loadClients();
		register();
		}
	else {
		unregister();
		WearInt.forgetClients();
		}
	}
}
