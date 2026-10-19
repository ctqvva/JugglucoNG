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
/*      Fri Jan 27 15:31:05 CET 2023                                                 */


package tk.glucodata;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import static tk.glucodata.Log.doLog;

public class JugglucoSend   {
    public static final String ACTION = "glucodata.Minute";
    private static final String SERIAL = "glucodata.Minute.SerialNumber";
    private static final String MGDL = "glucodata.Minute.mgdl";
	private static final String GLUCOSECUSTOM = "glucodata.Minute.glucose";
	private static final String RATE = "glucodata.Minute.Rate";
	private static final String TREND = "glucodata.Minute.Trend";
	private static final String TREND_NAME = "glucodata.Minute.TrendName";
    private static final String ALARM = "glucodata.Minute.Alarm";
    private static final String TIME = "glucodata.Minute.Time";
    // GDH-standard extras: classic remaining-action IOB and COB, plus the
    // computation timestamp GDH uses for its obsolescence check.
    private static final String IOB = "glucodata.Minute.IOB";
    private static final String COB = "glucodata.Minute.COB";
    private static final String IOBCOBTIME = "gdh.IOB_COB_time";
    // NG extension: activity-based "effective" IOB (ignored by current GDH).
    private static final String EIOB = "glucodata.Minute.eIOB";
private static final String LOG_ID="JugglucoSend";

private static Bundle mkGlucosebundle(String SerialNumber, ExchangeGlucosePayload payload, int alarm, float[] iobcob, long iobComputedAt) {
      Bundle extras = new Bundle();
        extras.putString(SERIAL,SerialNumber);
	extras.putInt(MGDL,payload.primaryMgdl);
	extras.putFloat(GLUCOSECUSTOM,(float)payload.primaryDisplayValue);
        extras.putFloat(RATE,payload.rate);
        extras.putInt(TREND,payload.trendIndex);
        extras.putString(TREND_NAME,payload.trendName);
        extras.putInt(ALARM,alarm);
        extras.putLong(TIME,payload.timeMillis);
	if(iobcob!=null&&iobcob.length>=3) {
		boolean any=false;
		if(!Float.isNaN(iobcob[0])) { extras.putFloat(IOB,iobcob[0]); any=true; }
		if(!Float.isNaN(iobcob[1])) { extras.putFloat(EIOB,iobcob[1]); any=true; }
		if(!Float.isNaN(iobcob[2])) { extras.putFloat(COB,iobcob[2]); any=true; }
		if(any) extras.putLong(IOBCOBTIME,iobComputedAt);
		}
	return extras;
	  }

private static String[] names=null;
public static  void setreceivers() {
	names=Natives.glucodataRecepters();
	}
private static volatile String lastSerial=null;
private static volatile ExchangeGlucosePayload lastPayload=null;
private static volatile int lastAlarm=0;

// A journal edit between readings: repeat the last glucose intent so the
// fresh IOB/COB extras go out right away instead of at the next reading.
// Receivers treat the unchanged glucose time as a duplicate and only take
// the insulin data (GlucoDataHandler wants 45s between readings but has a
// separate path for IOB/COB-only intents).
public static void rebroadcastIob() {
	final ExchangeGlucosePayload payload=lastPayload;
	if(payload==null)
		return;
	broadcastglucose(lastSerial,payload,lastAlarm);
	}
static void broadcastglucose(String SerialNumber, ExchangeGlucosePayload payload, int alarm) {
	if(names==null)
		return;
	lastSerial=SerialNumber;
	lastPayload=payload;
	lastAlarm=alarm;
	{if(doLog) {Log.i(LOG_ID,"broadcastglucose "+payload.primaryDisplayValue+" rate="+payload.rate);};};
        final Context context=Applic.app;
        Intent intent = new Intent(ACTION);
	final long iobComputedAt=System.currentTimeMillis();
	intent.putExtras(mkGlucosebundle(SerialNumber, payload, alarm, JournalIobAccess.snapshot(iobComputedAt), iobComputedAt));
	intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
	for(var name:names) {
		if(name!=null) {
		      {if(doLog) {Log.i(LOG_ID,name);};};
	      	      intent.setPackage(name);
		      context.sendBroadcast(intent);
		      }
	   	}
	}
	/*
static void broadcastglucose(String SerialNumber, int mgdl, float gl, float rate, int alarm, long timmsec) {
	{if(doLog) {Log.i(LOG_ID,"broadcastglucose "+gl+" rate="+rate);};};
        final Context context=Applic.app;
        Intent intent = new Intent(ACTION);
	intent.putExtras(mkGlucosebundle(SerialNumber, mgdl, gl, rate, alarm,timmsec));
	intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
   	var receivers = context.getPackageManager().queryBroadcastReceivers(intent, 0);
	   for(var resolveInfo : receivers) {
	   	String name=resolveInfo.activityInfo.packageName;
		if(name!=null) {
			{if(doLog) {Log.i(LOG_ID,name);};};
	      		intent.setPackage(name);
		      context.sendBroadcast(intent);
		      }
	   	}
	}*/
	
}
