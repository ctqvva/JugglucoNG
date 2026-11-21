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
/*      Thu Mar 23 21:03:54 CET 2023                                                 */



package tk.glucodata;

import static android.graphics.Color.WHITE;

import android.content.Intent;
import android.os.Bundle;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static tk.glucodata.Natives.getxDripTrendName;
import static tk.glucodata.Notify.glucosetimeout;

import com.eveningoutpost.dexdrip.services.broadcastservice.models.GraphLine;
import com.eveningoutpost.dexdrip.services.broadcastservice.models.Settings;

public class  WearInt  {
private static final String LOG_ID="WearInt";
private static final String ACTION_WATCH_COMMUNICATION_SENDER = "com.eveningoutpost.dexdrip.watch.wearintegration.BROADCAST_SERVICE_SENDER";
static private final long oldvalue=glucosetimeout;
// Written by the watchdrip receiver (main thread), read by senders on other threads.
static final Map<String, Settings> mapsettings=new ConcurrentHashMap<>();

// Registered clients are saved so that a restart doesn't lose them: an alarm that goes
// off before a client registers again would otherwise never reach the watch.
private static final String CLIENTS_PREFS="watchdrip_clients";

static void registerClient(String pack,Settings settings) {
    final boolean isNew=!mapsettings.containsKey(pack);
    mapsettings.put(pack,settings);
    try {
        Applic.app.getSharedPreferences(CLIENTS_PREFS,0).edit().putString(pack,settings.serialize()).apply();
        if(isNew) Log.i(LOG_ID,"saved watchdrip client "+pack);
    } catch(Throwable th) {
        Log.stack(LOG_ID,"registerClient",th);
    }
}

static void loadClients() {
    try {
        for(var el:Applic.app.getSharedPreferences(CLIENTS_PREFS,0).getAll().entrySet()) {
            if(!(el.getValue() instanceof String)) continue;
            final Settings settings=Settings.deserialize((String)el.getValue());
            if(settings!=null) mapsettings.putIfAbsent(el.getKey(),settings);
        }
        Log.i(LOG_ID,"loaded watchdrip clients "+mapsettings.keySet());
    } catch(Throwable th) {
        Log.stack(LOG_ID,"loadClients",th);
    }
}

static void forgetClients() {
    mapsettings.clear();
    try {
        Applic.app.getSharedPreferences(CLIENTS_PREFS,0).edit().clear().apply();
    } catch(Throwable th) {
        Log.stack(LOG_ID,"forgetClients",th);
    }
}

// Type of the last alarm sent to clients; null when none is ringing there.
private static volatile String alarmSentType=null;
// The client whose own request is stopping the alarm: it already knows.
static volatile String cancelOrigin=null;

/** Tell clients the alarm was stopped or snoozed on the phone, if one was sent to them. */
public static void cancelAlarm() {
    final String type=alarmSentType;
    if(type==null) return;
    alarmSentType=null;
    final String except=cancelOrigin;
    Intent intent = new Intent(ACTION_WATCH_COMMUNICATION_SENDER);
    intent.putExtra("FUNCTION","cancel_alarm");
    intent.putExtra("type",type);
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    for(final var el:mapsettings.keySet()) {
        if(el.equals(except)) continue;
        intent.setPackage(el);
        Applic.app.sendBroadcast(intent);
        Log.i(LOG_ID,"cancel_alarm sent to "+el);
    }
}
/*
private static int getBatteryLevel() { //From xDrip

        final Intent batteryIntent = Applic.app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        try {
            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level == -1 || scale == -1) {
                Log.e(LOG_ID,"level="+level+" scale="+scale);
                return 50;
            }
            int perc= (int) (((float) level / (float) scale) * 100.0f);
            {if(doLog) {Log.i(LOG_ID,"BatteryLevel="+perc);};};
            return perc;
        } catch (Throwable error) {
                        String mess=error!=null?error.getMessage():null;
                        Log.e(LOG_ID,"getBatteryLevel "+mess);

            return 50;
                }
        } 

*/
static void alarm(String value ) {
    Intent intent = new Intent(ACTION_WATCH_COMMUNICATION_SENDER);
    Bundle bundle=new Bundle();
    bundle.putString("FUNCTION","alarm");
    bundle.putString("type","BG_ALERT_TYPE");
    bundle.putString("message",value);
    if(!mapsettings.isEmpty()) alarmSentType="BG_ALERT_TYPE";
    intent.putExtras(bundle);
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    for(final var el:mapsettings.keySet()) {
        intent.setPackage(el);
        Applic.app.sendBroadcast(intent);
    }
    /*
    mapsettings.forEach((pack, settings) -> {
    	intent.setPackage(pack);
    	Applic.app.sendBroadcast(intent);
    	}); */

    }

/** xDrip+-compatible reply to a client's command. */
static void sendReply(String pack,String code) {
    Intent intent = new Intent(ACTION_WATCH_COMMUNICATION_SENDER);
    intent.putExtra("FUNCTION","reply_msg");
    intent.putExtra("REPLY_CODE",code);
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    intent.setPackage(pack);
    Applic.app.sendBroadcast(intent);
    }

static void missingalarm(long timmsec)  {
    Intent intent = new Intent(ACTION_WATCH_COMMUNICATION_SENDER);
    Bundle bundle=new Bundle();
    bundle.putString("FUNCTION","alarm");
    bundle.putString("type","bg_missed_alerts");
    if(!mapsettings.isEmpty()) alarmSentType="bg_missed_alerts";
    SimpleDateFormat df = new SimpleDateFormat("HH:mm", Locale.US);
    String time = df.format(new Date(timmsec));
    bundle.putString("message","BG Readings Missed  (@"+time+")");
    intent.putExtras(bundle);
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    for(final var el:mapsettings.keySet()) {
        intent.setPackage(el);
        Applic.app.sendBroadcast(intent);
    }
    /*
    mapsettings.forEach((pack, settings) -> {
    	intent.setPackage(pack);
    	Applic.app.sendBroadcast(intent);
    	}); */

        }

static Intent mksendglucoseintent(Settings settings,double mgdl,float rate, int alarm, long timmsec)  {
    return mksendglucoseintent(settings, mgdl, rate, getxDripTrendName(rate), alarm, timmsec);
}

private static Intent mksendglucoseintent(Settings settings,double mgdl,float rate, String trendName, int alarm, long timmsec)  {
        Intent intent = new Intent(ACTION_WATCH_COMMUNICATION_SENDER);
    Bundle bundle=new Bundle();
//    bundle.putInt("phoneBattery",getBatteryLevel()); //Who needs that?
        bundle.putDouble("bg.valueMgdl", mgdl);
        bundle.putDouble("bg.deltaValueMgdl", (double)rate*5.0);
        bundle.putString("bg.deltaName",trendName);
        bundle.putLong("bg.timeStamp", timmsec);
    final var now=System.currentTimeMillis();
        bundle.putBoolean("bg.isStale", (now-timmsec)>oldvalue);
        bundle.putBoolean("doMgdl", Applic.unit!=1);
    boolean ishigh=false,islow=false;  
    if((alarm&4)==4) {
    	if((alarm&1)==1)
    		islow=true;	
    	else
    		ishigh=true;	
    	}
        bundle.putBoolean("bg.isHigh", ishigh);
        bundle.putBoolean("bg.isLow", islow);
//    bundle.putString("pumpJSON","{\"reservoir\":-1,\"bolusiob\":-1,\"battery\":-1}");
    bundle.putString("pumpJSON","{}");
//    bundle.putLong("treatment.timeStamp",timmsec);
        if(settings.isDisplayGraph()) addgraph(settings,bundle,now);
    intent.putExtras(bundle);
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    return intent;
    }
private static final int FUZZER = 30000;
static private void addgraph(Settings settings,Bundle bundle,long now) {
    long graphStartOffset = settings.getGraphStart();
    if(graphStartOffset == 0) {
        graphStartOffset = 60*60*1000 * 2;
    	}
//    long end = now +settings.getGraphEnd(); //much too large with small periods
    long end = now + graphStartOffset/15;
    long start = now - graphStartOffset;
    bundle.putInt("fuzzer", FUZZER);
    bundle.putLong("start", start);
    bundle.putLong("end", end);
    float targetlow= Natives.targetlow();
    float targethigh= Natives.targethigh();
    bundle.putDouble("lowMark", targetlow); 
    bundle.putDouble("highMark", targethigh);

    float startfuz=start/FUZZER;
    float endfuz=end/FUZZER;
    final int highcolor=-17613;
    final int lowcolor=-3995383;
    var lowline=new GraphLine(lowcolor);
    lowline.add(startfuz,targetlow);
    lowline.add(endfuz,targetlow);
    bundle.putParcelable("graph.lowLine", lowline);
    var highline=new GraphLine(highcolor);
    highline.add(startfuz,targethigh);
    highline.add(endfuz,targethigh);
    bundle.putParcelable("graph.highLine", highline);
    var low=new GraphLine(lowcolor);
//    int inrangecolor=-13388314;
    int inrangecolor=WHITE;
    var inrange=new GraphLine(inrangecolor);
    var high=new GraphLine(highcolor);
    final CurrentDisplaySource.Snapshot current = resolveCurrentForGraph();
    final String sensorId = current != null ? current.getSensorId() : NotificationHistorySource.resolveSensorSerial(null);
    final int viewMode = current != null ? current.getViewMode() : 0;
    final boolean rawPrimary = viewMode == 1 || viewMode == 3;
    final boolean displayCalibratedGraph = shouldUseDisplayHistoryGraph(rawPrimary, sensorId);
    boolean nativeGraph = !displayCalibratedGraph && Natives.mkWearIntgraph(start,end,low,inrange,high);
    if((displayCalibratedGraph || !nativeGraph || !hasGraphPoints(low,inrange,high))
            && populateGraphFromDisplayHistory(start,end,low,inrange,high,targetlow,targethigh,current,sensorId,rawPrimary)) {
        if(Log.doLog) {
            Log.i(LOG_ID,"Using display-history fallback for watch graph");
        }
    }
    if(displayCalibratedGraph && !hasGraphPoints(low,inrange,high)) {
        nativeGraph = Natives.mkWearIntgraph(start,end,low,inrange,high);
    }
    if(!nativeGraph && !hasGraphPoints(low,inrange,high)) {
    	Log.e(LOG_ID,"No native or fallback points for watch graph");
    	}

    bundle.putParcelable("graph.low", low);
    bundle.putParcelable("graph.inRange", inrange);
    bundle.putParcelable("graph.high", high);
//    bundle.putParcelable("graph.iob", new GraphLine()); //ihhhnsulin on board
//    bundle.putParcelable("graph.treatment", new GraphLine()); //treatmentValues

//    bundle.putParcelable("graph.predictedBg", new GraphLine());
//    bundle.putParcelable("graph.cob", new GraphLine());  //cobValues
//    bundle.putParcelable("graph.polyBg", new GraphLine());  //poly predict ;
            }

private static boolean hasGraphPoints(GraphLine low, GraphLine inrange, GraphLine high) {
    return !low.values.isEmpty() || !inrange.values.isEmpty() || !high.values.isEmpty();
}

private static boolean populateGraphFromDisplayHistory(
        long start,
        long end,
        GraphLine low,
        GraphLine inrange,
        GraphLine high,
        float targetlow,
        float targethigh,
        CurrentDisplaySource.Snapshot current,
        String sensorId,
        boolean rawPrimary) {
    final var points = NotificationHistorySource.getDisplayHistory(start, Applic.unit == 1, sensorId);
    boolean added = false;
    for (final var point : points) {
        if (point == null || point.timestamp < start || point.timestamp > end) {
            continue;
        }
        final float value = resolveFallbackGraphValue(point, rawPrimary, sensorId);
        if (!Float.isFinite(value) || value <= 0f) {
            continue;
        }
        addGraphPoint(low, inrange, high, point.timestamp, value, targetlow, targethigh);
        added = true;
    }
    if (!added && current != null) {
        final long currentTime = current.getTimeMillis();
        final float currentValue = current.getPrimaryValue();
        if (currentTime >= start && currentTime <= end && Float.isFinite(currentValue) && currentValue > 0f) {
            addGraphPoint(low, inrange, high, currentTime, currentValue, targetlow, targethigh);
            added = true;
        }
    }
    return added;
}

private static CurrentDisplaySource.Snapshot resolveCurrentForGraph() {
    try {
        return CurrentDisplaySource.resolveCurrent(glucosetimeout);
    } catch (Throwable th) {
        Log.stack(LOG_ID, "resolveCurrent for graph", th);
        return null;
    }
}

private static boolean shouldUseDisplayHistoryGraph(boolean rawPrimary, String sensorId) {
    return !CalibrationAccess.shouldOverwriteSensorValues()
            && CalibrationAccess.hasActiveCalibration(rawPrimary, sensorId);
}

private static float resolveFallbackGraphValue(GlucosePoint point, boolean rawPrimary, String sensorId) {
    final float primary = rawPrimary ? point.rawValue : point.value;
    if (Float.isFinite(primary) && primary > 0f) {
        return calibrateGraphValue(primary, point.timestamp, rawPrimary, sensorId);
    }
    final float fallback = rawPrimary ? point.value : point.rawValue;
    return (Float.isFinite(fallback) && fallback > 0f)
            ? calibrateGraphValue(fallback, point.timestamp, !rawPrimary, sensorId)
            : Float.NaN;
}

private static float calibrateGraphValue(float value, long timestampMillis, boolean rawMode, String sensorId) {
    if (!Float.isFinite(value) || value <= 0f) {
        return Float.NaN;
    }
    if (CalibrationAccess.shouldOverwriteSensorValues()
            || !CalibrationAccess.hasActiveCalibration(rawMode, sensorId)) {
        return value;
    }
    final float calibrated = CalibrationAccess.getCalibratedValue(value, timestampMillis, rawMode, false, sensorId);
    return (Float.isFinite(calibrated) && calibrated > 0f) ? calibrated : value;
}

private static void addGraphPoint(
        GraphLine low,
        GraphLine inrange,
        GraphLine high,
        long timestampMillis,
        float value,
        float targetlow,
        float targethigh) {
    final GraphLine line = value < targetlow ? low : (value > targethigh ? high : inrange);
    line.add((float)(timestampMillis/(double)FUZZER), value);
}

static void sendglucose(int mgdl,float rate, int alarm, long timmsec)  {
//    mapsettings.forEach((pack, settings) -> {

    for(final var el:mapsettings.entrySet()) {
    	var intent=mksendglucoseintent(el.getValue(),mgdl,rate,alarm, timmsec);
    	intent.putExtra( "FUNCTION","update_bg");
    	intent.setPackage(el.getKey());
    	Applic.app.sendBroadcast(intent);
    	};
    }

static void sendglucose(ExchangeGlucosePayload payload, int alarm)  {
    for(final var el:mapsettings.entrySet()) {
        var intent=mksendglucoseintent(el.getValue(),payload.primaryMgdl,payload.trendRate,payload.trendName,alarm, payload.timeMillis);
        intent.putExtra( "FUNCTION","update_bg");
        intent.setPackage(el.getKey());
        Applic.app.sendBroadcast(intent);
        };
}
}
