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

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.Keep;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import tk.glucodata.drivers.ManagedBluetoothSensorDriver;

import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_BONDING;
import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_BALANCED;
import static android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
import static android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
import static java.util.Objects.isNull;
import static tk.glucodata.Applic.DontTalk;
import static tk.glucodata.Applic.app;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Applic.mgdLmult;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Log.showbytes;
import static tk.glucodata.Natives.thresholdchange;
import static tk.glucodata.SensorBluetooth.blueone;

public abstract class SuperGattCallback extends BluetoothGattCallback {
    volatile protected boolean stop = false;
    public static boolean doWearInt = false;
    public static boolean doGadgetbridge = false;
    private static final String LOG_ID = "SuperGattCallback";
    static final private int use_priority = CONNECTION_PRIORITY_HIGH;
    static boolean autoconnect = false;
    String mDeviceName = null;

    protected static final UUID mCharacteristicConfigDescriptor = UUID
            .fromString("00002902-0000-1000-8000-00805f9b34fb");

    protected static final UUID mCharacteristicUUID_BLELogin = UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb");
    protected static final UUID mCharacteristicUUID_CompositeRawData = UUID
            .fromString("0000f002-0000-1000-8000-00805f9b34fb");
    public static final UUID LIBRE3_DATA_SERVICE = UUID.fromString("089810cc-ef89-11e9-81b4-2a2ae2dbcce4");
    public static final UUID SIG_SERVICE_DEVICE_INFO = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    public static final UUID LIBRE3_SECURITY_SERVICE = UUID.fromString("0898203a-ef89-11e9-81b4-2a2ae2dbcce4");
    public static final UUID LIBRE3_DEBUG_SERVICE = UUID.fromString("08982400-ef89-11e9-81b4-2a2ae2dbcce4");
    public static final UUID LIBRE3_CHAR_BLE_LOGIN = UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb");
    public static final UUID LIBRE3_CHAR_PATCH_CONTROL = UUID.fromString("08981338-ef89-11e9-81b4-2a2ae2dbcce4");
    public static final UUID LIBRE3_CHAR_PATCH_STATUS = UUID.fromString("08981482-ef89-11e9-81b4-2a2ae2dbcce4");
    public static final UUID LIBRE3_CHAR_EVENT_LOG = UUID.fromString("08981bee-ef89-11e9-81b4-2a2ae2dbcce4");
    public static final UUID LIBRE3_CHAR_GLUCOSE_DATA = UUID.fromString("0898177a-ef89-11e9-81b4-2a2ae2dbcce4");
    public static final UUID LIBRE3_CHAR_HISTORIC_DATA = UUID.fromString("0898195a-ef89-11e9-81b4-2a2ae2dbcce4");
    public static final UUID LIBRE3_CHAR_CLINICAL_DATA = UUID.fromString("08981ab8-ef89-11e9-81b4-2a2ae2dbcce4");
    public static final UUID LIBRE3_CHAR_FACTORY_DATA = UUID.fromString("08981d24-ef89-11e9-81b4-2a2ae2dbcce4");
    public static final UUID LIBRE3_SEC_CHAR_COMMAND_RESPONSE = UUID.fromString("08982198-ef89-11e9-81b4-2a2ae2dbcce4");
    public static final UUID LIBRE3_SEC_CHAR_CHALLENGE_DATA = UUID.fromString("089822ce-ef89-11e9-81b4-2a2ae2dbcce4");
    public static final UUID LIBRE3_SEC_CHAR_CERT_DATA = UUID.fromString("089823fa-ef89-11e9-81b4-2a2ae2dbcce4");
    // private final UUID mCharacteristicUUID_ManufacturerNameString =
    // UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb");
    // private final UUID mCharacteristicUUID_SerialNumberString =
    // UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb");
    // private final UUID mSIGDeviceInfoServiceUUID =
    // UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    public final long starttime = System.currentTimeMillis();
    public long connectTime = 0L;
    public String SerialNumber;
    public String mActiveDeviceAddress;
    public long dataptr = 0L;
    public BluetoothDevice mActiveBluetoothDevice;
    long foundtime = 0L;
    protected BluetoothGatt mBluetoothGatt;
    private volatile BluetoothGatt locallyConnectedGatt;
    private volatile boolean connectPending = false;
    private volatile ScheduledFuture<?> pendingConnectFuture = null;
    /** connectGatt() timestamp of the attempt now in flight, cleared by the first callback the
     * stack delivers for it. Zero means "already reported", so a live connection pays one
     * volatile read per callback and nothing else. */
    private volatile long connectGattAtMs = 0L;
    boolean superseded = false;
    public final int sensorgen;
    public int readrssi = 9999;
    protected long sensorstartmsec;

    protected SuperGattCallback(String SerialNumber, long dataptr, int gen) {
        this.SerialNumber = SerialNumber;
        this.dataptr = dataptr;
        mActiveDeviceAddress = Natives.getDeviceAddress(dataptr, true);
        sensorstartmsec = Natives.getSensorStartmsec(dataptr);
        sensorgen = gen;
        {
            if (doLog) {
                Log.i(LOG_ID, "new SuperGattCallback " + SerialNumber + " "
                        + ((mActiveDeviceAddress != null) ? mActiveDeviceAddress : "null"));
            }
            ;
        }
        ;
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
            locallyConnectedGatt = gatt;
        } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
                && locallyConnectedGatt == gatt) {
            locallyConnectedGatt = null;
            WearSensorClaim.onLocalGattDisconnected(SerialNumber);
        }
    }

    /**
     * True only when this process received STATE_CONNECTED for the callback's
     * current Android GATT object. Persisted/synced driver state cannot set it.
     */
    public final boolean hasLocallyConnectedGatt() {
        final BluetoothGatt current = mBluetoothGatt;
        return current != null && locallyConnectedGatt == current;
    }

    /** Mark a live reading accepted by this local BLE callback. */
    protected final void markLocalReadingAccepted(long sampleTimeMs) {
        WearSensorClaim.onLocalReadingAccepted(SerialNumber, sampleTimeMs);
        SensorOwnershipRuntime.noteLocalReading(SerialNumber, sampleTimeMs);
    }

    public void disconnect() {
        clearPendingConnect();
        final var thegatt = mBluetoothGatt;
        if (thegatt != null) {
            {
                if (doLog) {
                    Log.i(LOG_ID, "Disconnect");
                }
                ;
            }
            ;
            thegatt.disconnect();
        } else {
            {
                if (doLog) {
                    Log.i(LOG_ID, "Disconnect mBluetoothGatt==null");
                }
                ;
            }
            ;
        }
    }

    public void setPause(boolean pause) {
        this.stop = pause;
        if (pause) {
            clearPendingConnect();
        }
        if (doLog)
            Log.i(LOG_ID, "setPause " + pause);
    }

    public boolean reconnect(long now) {
        final var old = now - showtime + 20;
        if (charcha[1] < old && connectTime < (now - 60 * 1000)) {
            try {
                if (doLog) {
                    Log.i(LOG_ID, "reconnect " + SerialNumber);
                }
                ;
                constatstatusstr = "Loss of signal";
                constatchange[1] = now;
                final var thegatt = mBluetoothGatt;
                if (thegatt != null) {
                    thegatt.disconnect();
                }
            } catch (Throwable th) {
                Log.stack(LOG_ID, "reconnect", th);
            } finally {
                return connectDevice(0);
            }
        }
        return true;
    }

    void setConStatus(int status) {
        constatstatusstr = "Status=" + status;
    }

    void shouldreconnect(long now) {
        final var old = now - showtime + 20;
        if (starttime < old && charcha[0] < old && connectTime < (now - 60 * 1000))
            reconnect(old);
    }

    long[] constatchange = { 0L, 0L };
    public String constatstatusstr = "";
    public String handshake = "";
    long[] wrotepass = { 0L, 0L };
    long[] charcha = { 0L, 0L };

    static final long thefuture = 0x7FFFFFFFFFFFFFFFL;
    // static long oldtime = thefuture;

    long showtime = Notify.glucosetimeout;
    static long lastfoundL = 0L;

    private static final Object realtimeReadingOrderLock = new Object();
    private static String realtimeReadingSensorId = null;
    private static long realtimeReadingTimeMs = 0L;

    static long lastfound() {
        return lastfoundL;
    }

    private static boolean acceptRealtimeReading(String sensorId, long readingTimeMs) {
        synchronized (realtimeReadingOrderLock) {
            final boolean sameInMemorySensor = realtimeReadingSensorId != null
                    && SensorIdentity.matches(sensorId, realtimeReadingSensorId);
            final long inMemoryHighWaterMs = sameInMemorySensor ? realtimeReadingTimeMs : 0L;

            long nativeHighWaterMs = 0L;
            try {
                final String nativeMainSensor = Natives.lastsensorname();
                if (nativeMainSensor != null && SensorIdentity.matches(sensorId, nativeMainSensor)) {
                    nativeHighWaterMs = Natives.lastglucosetime();
                }
            } catch (Throwable error) {
                Log.stack(LOG_ID, "acceptRealtimeReading", error);
            }

            if (!RealtimeReadingPolicy.shouldDispatch(readingTimeMs, inMemoryHighWaterMs, nativeHighWaterMs)) {
                if (doLog) {
                    Log.w(LOG_ID, "Ignoring stale realtime callback sensor=" + sensorId
                            + " time=" + readingTimeMs
                            + " inMemoryHighWater=" + inMemoryHighWaterMs
                            + " nativeHighWater=" + nativeHighWaterMs);
                }
                return false;
            }

            realtimeReadingSensorId = sensorId;
            realtimeReadingTimeMs = Math.max(readingTimeMs, inMemoryHighWaterMs);
            return true;
        }
    }

    private static long[] nextalarm = new long[10];

    static public void writealarmsuspension(int kind, short wa) {
        short prevsus = Natives.readalarmsuspension(kind);
        if (prevsus != wa) {
            Natives.writealarmsuspension(kind, wa);
            int versch = wa - prevsus;
            nextalarm[kind] += versch * 60;
        }
    }

    // New methods to handle scan data
    public void onScanResult(android.bluetooth.le.ScanResult result) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.bluetooth.le.ScanRecord record = result.getScanRecord();
            if (record != null) {
                byte[] bytes = record.getBytes();
                if (bytes != null)
                    onScanRecord(bytes);
            }
        }
    }

    /** Optional metadata match for advertisements without a usable local name. */
    public boolean matchScanResult(ScanResult result) {
        return false;
    }

    public void onScanRecord(byte[] scanRecord) {
        // Default empty implementation
    }

    // Optional pre-replay sync hook for drivers that can actively request
    // latest pending sensor data (Sibionics FF30 path).
    public boolean requestLatestDataForReplay() {
        return false;
    }

    protected boolean allowConnectWithoutDataptr() {
        if (this instanceof ManagedBluetoothSensorDriver managed) {
            try {
                return managed.canConnectWithoutDataptr();
            } catch (Throwable th) {
                Log.stack(LOG_ID, SerialNumber + " allowConnectWithoutDataptr", th);
            }
        }
        return false;
    }

    static final int mininterval = 55;
    static long nexttime = 0L; // secs
    public static tk.glucodata.GlucoseAlarms glucosealarms = null;
    public static notGlucose previousglucose = null;
    static float previousglucosevalue = 0.0f;
    public static String previousglucosesensorid = null;
    private static final ConcurrentHashMap<String, Long> lastCollapsedExchangeTimeMs = new ConcurrentHashMap<>();

    private static boolean shouldEmitExchangeUpdate(String sensorId, long payloadTimeMs, boolean collapseChunks) {
        if (!collapseChunks || payloadTimeMs <= 0L) {
            return true;
        }
        final String key = (sensorId != null && !sensorId.isEmpty()) ? sensorId : "<unknown>";
        final Long previous = lastCollapsedExchangeTimeMs.put(key, payloadTimeMs);
        return previous == null || previous.longValue() != payloadTimeMs;
    }

    static public void initAlarmTalk() {
        if (glucosealarms == null)
            glucosealarms = new tk.glucodata.GlucoseAlarms(Applic.app);
        if (!DontTalk) {
            Talker.getvalues();
            if (Talker.shouldtalk())
                newtalker(null);
        }
    }

    static Talker talker;
    static boolean dotalk = false;

    static void newtalker(Context context) {
        if (!DontTalk) {
            if (talker != null)
                talker.destruct();
            talker = new Talker(context);
        }
    }

    static void endtalk() {
        if (!DontTalk) {
            dotalk = false;
            if (talker != null) {
                talker.destruct();
                talker = null;
            }
        }
    }

    private static boolean isStandardGlucoseAlertCode(int alarm) {
        return alarm == 4 || alarm == 5 || alarm == 6 || alarm == 7 || alarm == 16 || alarm == 17 || alarm == 18
                || alarm == 19;
    }

    public static void processExternalCurrentReading(String sensorSerial, float glucoseValue, float rate,
            long timmsec, int sensorgen) {
        if (!Float.isFinite(glucoseValue) || glucoseValue <= 0f || timmsec <= 0L) {
            return;
        }
        if (glucosealarms == null) {
            glucosealarms = new tk.glucodata.GlucoseAlarms(Applic.app);
        }
        final String resolvedSensorSerial = (sensorSerial != null && !sensorSerial.isEmpty())
                ? sensorSerial
                : Natives.lastsensorname();
        final int mgdlValue = Math.round(glucoseValue * (Applic.unit == 1 ? mgdLmult : 1.0f));
        dowithglucose(resolvedSensorSerial, mgdlValue, glucoseValue, rate, 0, timmsec,
                0L, Notify.glucosetimeout, sensorgen);
    }

    private static long[] loadRecentSensorHistory(String sensorSerial, long startTimeSec) {
        long[] history = null;
        if (sensorSerial != null && !sensorSerial.isEmpty()
                && !SensorIdentity.shouldUseNativeHistorySync(sensorSerial)) {
            return null;
        }
        if (sensorSerial != null && !sensorSerial.isEmpty()) {
            try {
                history = Natives.getGlucoseHistoryForSensor(sensorSerial, startTimeSec);
            } catch (Throwable ignored) {
                history = null;
            }
            if ((history == null || history.length == 0) && sensorSerial.startsWith("X-") && sensorSerial.length() > 2) {
                try {
                    history = Natives.getGlucoseHistoryForSensor(sensorSerial.substring(2), startTimeSec);
                } catch (Throwable ignored) {
                    history = null;
                }
            }
        }
        if (history == null || history.length == 0) {
            final String current = SensorIdentity.resolveMainSensor();
            if (current != null && !current.isEmpty() && !SensorIdentity.shouldUseNativeHistorySync(current)) {
                return null;
            }
            try {
                history = Natives.getGlucoseHistory(startTimeSec);
            } catch (Throwable ignored) {
                history = null;
            }
        }
        return history;
    }

    private static final class SensorHistoryMatch {
        final long timestampMs;
        final float rawMgdl;

        SensorHistoryMatch(long timestampMs, float rawMgdl) {
            this.timestampMs = timestampMs;
            this.rawMgdl = rawMgdl;
        }
    }

    private static final long LIVE_HISTORY_MATCH_WINDOW_SEC = 60L;

    private static SensorHistoryMatch findSensorHistoryMatchNear(String sensorSerial, long timeSec) {
        long[] history = loadRecentSensorHistory(sensorSerial, timeSec - (LIVE_HISTORY_MATCH_WINDOW_SEC + 30L));
        if (history == null) {
            return null;
        }
        SensorHistoryMatch bestMatch = null;
        long bestDelta = Long.MAX_VALUE;
        for (int i = 0; i < history.length; i += 3) {
            if (i + 2 >= history.length)
                break;
            long hTime = history[i];
            long delta = Math.abs(hTime - timeSec);
            if (delta <= LIVE_HISTORY_MATCH_WINDOW_SEC && delta < bestDelta) {
                long rawVal = history[i + 2];
                float rawMgdl = rawVal > 0 ? (float) rawVal / 10.0f : Float.NaN;
                bestMatch = new SensorHistoryMatch(hTime * 1000L, rawMgdl);
                bestDelta = delta;
            }
        }
        return bestMatch;
    }

    private static float findRawMgdlNear(String sensorSerial, long timeSec) {
        final SensorHistoryMatch match = findSensorHistoryMatchNear(sensorSerial, timeSec);
        return match != null ? match.rawMgdl : Float.NaN;
    }

    private static boolean shouldUseRawAsPrimary(int viewMode) {
        // Mode 2 is Auto+Raw, not Raw-primary. Live publishing must only
        // substitute the primary value when raw is actually the primary lane.
        return viewMode == 1 || viewMode == 3;
    }

    private static boolean shouldStoreRawLane(int viewMode) {
        return viewMode == 1 || viewMode == 2 || viewMode == 3;
    }

    private static final long ROOM_MINUTE_BUCKET_MS = 60_000L;

    private static void storeLiveReadingInRoom(String sensorSerial, long timmsec, float autoMgdl, float rawMgdl,
            float rate) {
        if (sensorSerial == null || sensorSerial.isEmpty() || timmsec <= 0L) {
            return;
        }
        if ((!Float.isFinite(autoMgdl) || autoMgdl <= 0f) && (!Float.isFinite(rawMgdl) || rawMgdl <= 0f)) {
            return;
        }
        final SensorHistoryMatch match = findSensorHistoryMatchNear(sensorSerial, timmsec / 1000L);
        long storedTimestamp = match != null && match.timestampMs > 0L
                ? match.timestampMs
                : (timmsec / 1000L) * 1000L;
        if (match == null || match.timestampMs <= 0L) {
            final long latestRoomTimestamp = HistorySyncAccess.getLatestTimestampForSensor(sensorSerial);
            if (latestRoomTimestamp > 0L
                    && (latestRoomTimestamp / ROOM_MINUTE_BUCKET_MS) == (storedTimestamp / ROOM_MINUTE_BUCKET_MS)) {
                storedTimestamp = latestRoomTimestamp;
            }
        }
        final float storedAuto = (Float.isFinite(autoMgdl) && autoMgdl > 0f) ? autoMgdl : 0f;
        final float matchedRaw = match != null ? match.rawMgdl : Float.NaN;
        final float storedRaw = (Float.isFinite(rawMgdl) && rawMgdl > 0f)
                ? rawMgdl
                : ((Float.isFinite(matchedRaw) && matchedRaw > 0f) ? matchedRaw : 0f);
        HistorySyncAccess.storeCurrentReadingAsync(storedTimestamp, storedAuto, storedRaw, rate, sensorSerial);
    }

    private static void syncLegacyRoomHistoryAfterLive(String sensorSerial, long timmsec) {
        if (sensorSerial == null || sensorSerial.isEmpty() || timmsec <= 0L) {
            return;
        }
        HistorySyncAccess.syncRecentSensorFromNative(sensorSerial, timmsec);
    }

    static void dowithglucose(String SerialNumber, int mgdl, float gl, float rate, int alarm, long timmsec,
            long sensorstartmsec, long showtime, int sensorgen) {

        if (gl == 0.0)
            return;
        if (glucosealarms == null) {
            Log.e(LOG_ID, "glucosealarms==null");
            return;
        }

        // Multi-sensor fix: Check if this sensor is the user-selected main sensor.
        // Non-main sensors still store data (already done before this call), but should
        // NOT trigger notifications, alarms, broadcasts, or exchange data — those should
        // only reflect the main sensor's values to avoid confusing switching behavior.
        boolean isMainSensor = true;
        String resolvedMainName = null;
        try {
            resolvedMainName = SensorIdentity.resolveLiveMainSensor(SerialNumber);
            if (resolvedMainName != null && !resolvedMainName.isEmpty() && SerialNumber != null) {
                isMainSensor = SensorIdentity.matches(SerialNumber, resolvedMainName);
            }
        } catch (Throwable t) {
            // If we can't determine main sensor, default to allowing (safety)
            isMainSensor = true;
        }
        if (!isMainSensor) {
            if (doLog) {
                // Reuse the resolution above. Re-calling resolveLiveMainSensor here
                // just to build the message doubled a per-reading JNI sweep
                // (Natives.activeSensors + mygatts) for every non-main reading.
                Log.i(LOG_ID, "Multi-sensor: Skipping notifications/broadcasts for non-main sensor "
                        + SerialNumber + " (liveMain=" + resolvedMainName
                        + ", selectedMain=" + Natives.lastsensorname() + ")");
            }
            // Still update the screen so charts/history reflect all sensors
            Applic.updatescreen();
            UiRefreshBus.requestDataRefresh();
            return;
        }

        // History/replay rows are already persisted before this method. They must not
        // move notifications, glucose alerts, or the signal-loss deadline backwards.
        if (!acceptRealtimeReading(SerialNumber, timmsec)) {
            Applic.updatescreen();
            UiRefreshBus.requestDataRefresh();
            return;
        }

        glucosealarms.setagealarm(timmsec, showtime);
        final var alertEvaluation = tk.glucodata.alerts.AlertRuntimeManager.INSTANCE.onNewReading(SerialNumber,
                gl, rate, timmsec, sensorgen);
        final boolean runtimeHandledStandardGlucoseAlert = alertEvaluation.getStandardGlucoseAlertHandled();
        final boolean glucoseAlertStarted = alertEvaluation.getStandardGlucoseAlertStarted();
        final long tim = timmsec / 1000L;
        boolean waiting = false;
        var sglucose = new notGlucose(timmsec, String.format(Applic.usedlocale, Notify.pureglucoseformat, gl), rate,
                sensorgen);
        previousglucose = sglucose;
        previousglucosevalue = gl;
        previousglucosesensorid = SerialNumber;
        Applic.app.sendBroadcast(new Intent("tk.glucodata.action.AOD_IMMEDIATE_REFRESH"));
        final var fview = Floating.floatview;
        // MainActivity.showmessage=null;
        final boolean alarmSpeechStarted = glucoseAlertStarted && !DontTalk && Natives.speakalarms();
        if (alarmSpeechStarted)
            Talker.nexttime = 0L;
        if (fview != null)
            fview.postInvalidate();

        try {

            if (runtimeHandledStandardGlucoseAlert) {
                if (isStandardGlucoseAlertCode(alarm)) {
                    if (doLog) {
                        Log.i(LOG_ID, "Ignoring native standard glucose alarm code " + alarm
                                + "; AlertRuntimeManager owns glucose alert decisions");
                    }
                    alarm = 0;
                }
            } else if (isStandardGlucoseAlertCode(alarm)) {
                if (doLog) {
                    Log.i(LOG_ID, "Ignoring native standard glucose alarm code " + alarm
                            + "; AlertRuntimeManager owns glucose alert decisions");
                }
                alarm = 0;
                Notify.onenot.normalglucose(sglucose, gl, rate, false);
            } else {
                switch (alarm) {
                    case 3:
                        waiting = true;
                    default:
                        Notify.onenot.normalglucose(sglucose, gl, rate, waiting);
                }
            }
            ;
        } catch (Throwable e) {
            Log.stack(LOG_ID, SerialNumber, e);
        }
        CustomAlertAccess.checkAndTrigger(Applic.app, gl, rate, timmsec, SerialNumber, sensorgen);
        {
            if (doLog) {
                Log.v(LOG_ID, SerialNumber + " " + tim + " glucose=" + gl + " " + rate);
            }
            ;
        }
        ;

        Applic.updatescreen();
        UiRefreshBus.requestDataRefresh();

        if (!DontTalk) {
            if (dotalk && !alarmSpeechStarted) {
                // Speak the calibrated display value (same source as the display,
                // notifications, and alarm speech) rather than the raw native value.
                final CurrentDisplaySource.Snapshot speakcurrent =
                        CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout);
                talker.selspeak(speakcurrent != null ? speakcurrent.getSpeechPrimaryStr() : sglucose.value);
            }
        }
        if (isWearable) {
            tk.glucodata.glucosecomplication.GlucoseValue.updateall();
        }

        emitExchangeOutputs(SerialNumber, gl, rate, alarm, timmsec, sensorstartmsec, tim, sensorgen,
                sglucose.value, true);
    }

    /**
     * Every outbound use of a reading: broadcasts, the outbound API, Nightscout
     * and LibreView through numdata, the widget, and the xDrip / Gadgetbridge /
     * WearInt targets.
     *
     * Split out of {@link #dowithglucose} so a reading that arrived from the
     * watch can travel exactly the same way. When the watch holds the sensor the
     * phone's BLE callback never runs, so none of this fired and every exchange
     * target silently stopped for as long as the watch owned the sensor.
     *
     * Deliberately excludes alarms, notifications and speech: those are wired
     * into dowithglucose and stay there, so mirroring a reading cannot produce a
     * second alarm for one the other device has already handled.
     *
     * @param pushToPeer false for a reading that came from the peer, so it is
     *                   not immediately sent back to the device it came from.
     */
    static void emitExchangeOutputs(String SerialNumber, float gl, float rate, int alarm, long timmsec,
            long sensorstartmsec, long tim, int sensorgen, String primaryText, boolean pushToPeer) {
        final Applic app = Applic.app;
        if (app == null)
            return;
        final boolean shouldBroadcastMinuteUpdate = tim > nexttime;
        final boolean outboundApiEnabled = OutboundApiSettings.isEnabled(app);
        final boolean shouldResolveExchangePayload =
                Natives.getJugglucobroadcast()
                || outboundApiEnabled
                || (shouldBroadcastMinuteUpdate && (
                        Natives.getlibrelinkused()
                        || Natives.getxbroadcast()
                        || doWearInt
                        || doGadgetbridge));
        final ExchangeGlucosePayload exchangePayload = shouldResolveExchangePayload
                ? ExchangeGlucosePayload.resolve(SerialNumber, gl, rate, timmsec, sensorgen, primaryText)
                : null;
        final boolean collapseExchangeUpdates = DataSmoothing.shouldCollapseExchangeOutputs(app);
        final boolean shouldEmitExchangeUpdate =
                exchangePayload != null
                && shouldEmitExchangeUpdate(exchangePayload.getSensorId(), exchangePayload.getTimeMillis(), collapseExchangeUpdates);

        if (Natives.getJugglucobroadcast() && shouldEmitExchangeUpdate)
            JugglucoSend.broadcastglucose(SerialNumber, exchangePayload, alarm);
        if (outboundApiEnabled && shouldEmitExchangeUpdate)
            OutboundApi.enqueueGlucose(
                    exchangePayload.getSensorId(),
                    exchangePayload.getPrimaryText(),
                    exchangePayload.getPrimaryDisplayValue(),
                    exchangePayload.getPrimaryMgdl(),
                    exchangePayload.getRate(),
                    exchangePayload.getTimeMillis(),
                    exchangePayload.getSensorGen(),
                    exchangePayload.getAutoValue(),
                    exchangePayload.getAutoMgdl(),
                    exchangePayload.getRawValue(),
                    exchangePayload.getTrendIndex(),
                    exchangePayload.getTrendName(),
                    exchangePayload.getTrendRate(),
                    alarm);
        // Readings travel to whichever device is not holding the sensor, so the
        // watch pushes too when it is the one reading. A reading that arrived
        // from the peer is not sent straight back.
        if (pushToPeer)
            WearSync2.pushTail();
        if (!isWearable) {
            app.numdata.sendglucose(SerialNumber, tim, gl, thresholdchange(rate), alarm | 0x10);
            GlucoseWidget.update();
            // Keep the webserver's /pebble IOB in step with the journal,
            // independent of whether any broadcast target is configured.
            JournalIobAccess.pushWatchserver(System.currentTimeMillis());
        }
        if (shouldBroadcastMinuteUpdate) {
            nexttime = tim + mininterval;
            if (!isWearable) {
                if (Natives.getlibrelinkused() && shouldEmitExchangeUpdate)
                    XInfuus.sendGlucoseBroadcast(exchangePayload.getSensorId(), exchangePayload.getPrimaryMgdl(), exchangePayload.getRate(), exchangePayload.getTimeMillis(), sensorstartmsec);
                // SendNSClient.broadcastglucose(mgdl, rate, timmsec);
            }
            if (Natives.getxbroadcast() && shouldEmitExchangeUpdate)
                SendLikexDrip.broadcastglucose(exchangePayload, sensorstartmsec);
            if (!isWearable) {
                if (doWearInt && shouldEmitExchangeUpdate)
                    tk.glucodata.WearInt.sendglucose(exchangePayload, alarm);

                if (doGadgetbridge && shouldEmitExchangeUpdate)
                    Gadgetbridge.sendglucose(exchangePayload);
            }
        }

    }

    boolean stopHealth = false;

    private boolean dohealth(SuperGattCallback one) {
        if (!isWearable) {
            var blue = blueone;
            if (blue == null)
                return true; // false?
            final var gatts = blue.gattcallbacks;
            boolean other = gatts.size() > 1;
            if (!other) {
                return true; // TODO stopHealth=false
            }
            if (stopHealth)
                return false;
            for (var el : gatts) {
                if (el != one)
                    el.stopHealth = true;
            }
            return true;
        } else {
            return false;
        }
    }

    protected void handleGlucoseResult(long res, long timmsec) {
        handleGlucoseResultInternal(res, timmsec, 0, Float.NaN);
    }

    protected void handleGlucoseResult(long res, long timmsec, float preferredRawMgdl) {
        handleGlucoseResultInternal(res, timmsec, 0, preferredRawMgdl);
    }

    private void handleGlucoseResultInternal(long res, long timmsec, int retryCount, float preferredRawMgdl) {
        // int glumgdl = (int) (res & 0xFFFFFFFFL);
        int glumgL = (int) (res & 0xFFFFFFFFL);
        int alarm = (int) ((res >> 48) & 0xFFL);
        short ratein = (short) ((res >> 32) & 0xFFFFL);
        float rate = ratein / 1000.0f;
        final boolean liveRoomStorage = this instanceof ManagedBluetoothSensorDriver managed
                && managed.managesLiveRoomStorage()
                && SerialNumber != null
                && !SerialNumber.isEmpty();
        final boolean shouldApplyGenericLiveCalibration = !liveRoomStorage;

        // Check viewMode early - RAW modes may have data even when calibrated glucose is 0
        int viewMode = Natives.getViewMode(dataptr);
        boolean isRawMode = (viewMode == 1 || viewMode == 3);
        boolean shouldUseRawAsPrimary = shouldUseRawAsPrimary(viewMode);
        boolean hasPreferredRawLane = Float.isFinite(preferredRawMgdl) && preferredRawMgdl > 0f;
        boolean shouldStoreRawLane = hasPreferredRawLane || shouldStoreRawLane(viewMode);

        // In RAW mode with zero calibrated glucose, try to get raw from history
        // This handles warmup period where algorithm returns 0 but raw data exists
        if (glumgL == 0 && isRawMode) {
            long timeSec = timmsec / 1000L;
            final float rawMgdl = (Float.isFinite(preferredRawMgdl) && preferredRawMgdl > 0f)
                    ? preferredRawMgdl
                    : findRawMgdlNear(SerialNumber, timeSec);
            if (Float.isFinite(rawMgdl) && rawMgdl > 0f) {
                // Found raw value - use it even though calibrated is 0
                int mgdlToUse = (int) Math.round(rawMgdl);
                float glucoseToUse = rawMgdl;
                if (Applic.unit == 1) {
                    glucoseToUse = glucoseToUse / (float) mgdLmult;
                }
                if (liveRoomStorage) {
                    storeLiveReadingInRoom(SerialNumber, timmsec, 0f, rawMgdl, rate);
                } else {
                    syncLegacyRoomHistoryAfterLive(SerialNumber, timmsec);
                }
                if (shouldApplyGenericLiveCalibration) {
                    glucoseToUse = CalibrationAccess.getCalibratedValue(glucoseToUse, timmsec, true);
                    mgdlToUse = (int) Math.round(glucoseToUse * (Applic.unit == 1 ? mgdLmult : 1.0f));
                }

                if (doLog) {
                    Log.i(LOG_ID, "RAW mode during warmup: using raw=" + glucoseToUse + " mgdl=" + mgdlToUse);
                }

                markLocalReadingAccepted(timmsec);
                dowithglucose(SerialNumber, mgdlToUse, glucoseToUse, rate, alarm, timmsec, sensorstartmsec, showtime, sensorgen);
                charcha[0] = timmsec;

                if (!isWearable && Natives.gethealthConnect() && Build.VERSION.SDK_INT >= 28) {
                    if (dohealth(this)) {
                        final long sensorptr = Natives.getsensorptr(dataptr);
                        HealthConnection.Companion.writeAll(sensorptr, SerialNumber);
                    }
                }
                SensorBluetooth.othersworking(this, timmsec);
                return;
            }
            // No raw found yet - retry if possible
            if (retryCount < 3) {
                if (doLog) {
                    Log.i(LOG_ID, "RAW mode: no raw value found, retrying (" + (retryCount + 1) + "/3)...");
                }
                Applic.scheduler.schedule(
                        () -> handleGlucoseResultInternal(res, timmsec, retryCount + 1, preferredRawMgdl),
                        200,
                        TimeUnit.MILLISECONDS);
                return;
            }
        }

        if (glumgL != 0) {
            if (doLog) {
                Log.i(LOG_ID, SerialNumber + " alarm=" + alarm);
            }
            ;

            final float gl = Applic.unit == 1 ? glumgL / (mgdLmult * 10.0f) : glumgL / 10.0f;

            // LOGIC TO USE RAW VALUE IF VIEWMODE SPECIES SO
            float glucoseToUse = gl;
            int mgdlToUse = (int) Math.round(glumgL / 10.0f);
            final float autoMgdl = glumgL / 10.0f;
            float rawMgdl = Float.NaN;

            if (shouldStoreRawLane) {
                long timeSec = timmsec / 1000L;
                rawMgdl = (Float.isFinite(preferredRawMgdl) && preferredRawMgdl > 0f)
                        ? preferredRawMgdl
                        : findRawMgdlNear(SerialNumber, timeSec);
                boolean found = Float.isFinite(rawMgdl) && rawMgdl > 0f;
                if (shouldUseRawAsPrimary && found) {
                    mgdlToUse = (int) Math.round(rawMgdl);
                    float glVal = rawMgdl;
                    if (Applic.unit == 1) {
                        glVal = glVal / (float) mgdLmult;
                    }
                    glucoseToUse = glVal;
                    if (doLog) {
                        Log.i(LOG_ID, "Using RAW value: " + glucoseToUse + " (mgdl: " + mgdlToUse + ")");
                    }
                }
                if (shouldUseRawAsPrimary && !found && retryCount < 3) {
                    if (doLog) {
                        Log.i(LOG_ID, "History lookup failed, retrying (" + (retryCount + 1) + "/3)...");
                    }
                    Applic.scheduler.schedule(() -> handleGlucoseResultInternal(res, timmsec, retryCount + 1, preferredRawMgdl), 200,
                            TimeUnit.MILLISECONDS);
                    return;
                }
            }

            if (liveRoomStorage) {
                storeLiveReadingInRoom(SerialNumber, timmsec, autoMgdl, rawMgdl, rate);
            } else {
                syncLegacyRoomHistoryAfterLive(SerialNumber, timmsec);
            }

            if (shouldApplyGenericLiveCalibration) {
                glucoseToUse = CalibrationAccess.getCalibratedValue(glucoseToUse, timmsec, isRawMode);
                mgdlToUse = (int) Math.round(glucoseToUse * (Applic.unit == 1 ? mgdLmult : 1.0f));
            }

            markLocalReadingAccepted(timmsec);
            dowithglucose(SerialNumber, mgdlToUse, glucoseToUse, rate, alarm, timmsec, sensorstartmsec, showtime,
                    sensorgen);

            charcha[0] = timmsec;

            if (!isWearable) {
                if (Natives.gethealthConnect()) {
                    if (Build.VERSION.SDK_INT >= 28) {
                        if (dohealth(this)) {
                            final long sensorptr = Natives.getsensorptr(dataptr);// TODO: set sensorptr in
                                                                                 // SuperGattCallback?
                            HealthConnection.Companion.writeAll(sensorptr, SerialNumber);
                        }
                    }
                }
            }
            SensorBluetooth.othersworking(this, timmsec);
        } else {
            {
                if (doLog) {
                    Log.i(LOG_ID, SerialNumber + " onCharacteristicChanged: Glucose failed");
                }
                ;
            }
            ;
            charcha[1] = timmsec;
        }
    }

    public void searchforDeviceAddress() {
        {
            if (doLog) {
                Log.i(LOG_ID, SerialNumber + " searchforDeviceAddress()");
            }
            ;
        }
        ;
        // setDeviceAddress(null);
        foundtime = 0L;
        mActiveDeviceAddress = null;
    }

    String getinfo() {
        if (dataptr != 0L)
            return Natives.getsensortext(dataptr);
        return "";
    }

    public long resetdataptr() {
        if (constatchange[1] < constatchange[0]) {
            constatchange[1] = System.currentTimeMillis();
            constatstatusstr = "resetdataptr";
        }
        Natives.freedataptr(dataptr);
        close();
        dataptr = Natives.getdataptr(SerialNumber);
        mActiveDeviceAddress = Natives.getDeviceAddress(dataptr, true);
        return dataptr;
    }

    public void setDevice(BluetoothDevice device) {

        mActiveBluetoothDevice = device;
        if (device != null) {
            String address = device.getAddress();
            {
                if (doLog) {
                    Log.i(LOG_ID, SerialNumber + " setDevice(" + address + ")");
                }
                ;
            }
            ;
            setDeviceAddress(address);
        } else {
            {
                if (doLog) {
                    Log.i(LOG_ID, SerialNumber + " setDevice(null)");
                }
                ;
            }
            ;
            setDeviceAddress(null);
        }
    }

    public void setDeviceAddress(String address) {
        {
            if (doLog) {
                Log.i(LOG_ID, SerialNumber + " " + "setDeviceAddress(" + address + ")");
            }
            ;
        }
        ;
        mActiveDeviceAddress = address;
        Natives.setDeviceAddress(dataptr, address);
    }

    void free() {
        stop = true;
        {
            if (doLog) {
                Log.i(LOG_ID, "free " + SerialNumber);
            }
            ;
        }
        ;
        close();
        Natives.freedataptr(dataptr);
        dataptr = 0L;
        // sensorbluetooth=null;
    }

    public boolean streamingEnabled() {// TODO: libre3?
        return Natives.askstreamingEnabled(dataptr);
    }

    public void finishSensor() {
        Natives.finishSensor(dataptr);
    }

    public void close() {
        closeGattTransport();
    }

    /** Close only the current Android GATT transport, without invoking a
     * managed driver's terminal close override. */
    public final void closeGattTransport() {
        clearPendingConnect();
        {
            if (doLog) {
                Log.i(LOG_ID, "close " + SerialNumber);
            }
            ;
        }
        ;
        var tmpgatt = mBluetoothGatt;
        if (tmpgatt != null) {
            if (locallyConnectedGatt == tmpgatt) {
                locallyConnectedGatt = null;
                WearSensorClaim.onLocalGattDisconnected(SerialNumber);
            }
            try {
                tmpgatt.disconnect();
                tmpgatt.close();
            } catch (Throwable se) {
                var mess = se.getMessage();
                mess = mess == null ? "" : mess;
                String uit = ((Build.VERSION.SDK_INT > 30)
                        ? Applic.getContext().getString(R.string.turn_on_nearby_devices_permission)
                        : mess);
                Applic.Toaster(uit);
                Log.stack(LOG_ID, SerialNumber + " " + "BluetoothGatt.close()", se);
            } finally {
                mBluetoothGatt = null;
            }
        } else {
            {
                if (doLog) {
                    Log.i(LOG_ID, "close mBluetoothGatt==null");
                }
                ;
            }
            ;
        }

    }

    /**
     * Connect mode for this sensor's GATT connections.
     * <p>
     * The backing field is one application-wide static that the {@link SensorBluetooth}
     * constructor overwrites from {@code Natives.getAndroid13()}, so until this hook existed a
     * driver had no say at all: every one of the 103 connect attempts logged during the
     * 2026-08-01 jamming storm (52 + 51, two Ottai sensors) used autoConnect=true and not one
     * used false. Whether a direct connect would do better on any particular peripheral is still
     * unmeasured — see {@link #noteFirstGattCallback} for the number that would decide it — so
     * every driver deliberately keeps inheriting the user's setting here.
     */
    protected boolean useAutoConnect() {
        return autoconnect;
    }

    private void armConnectCallbackLatency() {
        connectGattAtMs = System.currentTimeMillis();
    }

    /**
     * Time from connectGatt() to the first callback the stack delivered for that attempt, and the
     * mode it was asked for. Comparing autoConnect=true against false needs this number and the
     * app has never recorded it: the 2026-08-01 logs show how long an outage lasted but not how
     * long a connect took to produce its first sign of life, which is the part the two modes
     * differ in.
     * <p>
     * Subclasses have to call this from their own onConnectionStateChange — that is in practice
     * the first callback of an attempt, and no driver except AiDex chains to super, so the base
     * class cannot funnel it. They call it first, above their own staleness guards, so the gatt
     * is taken here instead: a late callback from a retired GATT belongs to an earlier attempt,
     * and letting it consume the timestamp would publish its age against the attempt in flight —
     * corrupting exactly the number this exists to collect, in the drivers with the most
     * retired-GATT churn. A null mBluetoothGatt is the window between connectGatt() returning and
     * the driver storing it, and that attempt IS the one being timed.
     */
    protected final void noteFirstGattCallback(String which, BluetoothGatt gatt) {
        final long started = connectGattAtMs;
        if (started == 0L)
            return;
        final BluetoothGatt current = mBluetoothGatt;
        if (current != null && gatt != null && gatt != current)
            return;
        connectGattAtMs = 0L;
        if (doLog) {
            Log.i(LOG_ID, SerialNumber + " first GATT callback " + which + " "
                    + (System.currentTimeMillis() - started) + "ms after connectGatt autoConnect="
                    + useAutoConnect());
        }
    }

    private Runnable getConnectDevice() {
        var cb = this;
        if (cb.mBluetoothGatt != null) {
            // FIX: mBluetoothGatt != null does NOT mean "connected" - it means
            // "we have a GATT object reference". After disconnect(), mBluetoothGatt
            // stays non-null even though the connection is dead. Force close any
            // stale reference to allow reconnection (fixes Sibionics 1 CN reconnect bug).
            if (doLog)
                Log.d(LOG_ID, SerialNumber + " getConnectDevice: clearing stale mBluetoothGatt");
            closeGattTransport();
        }
        if (cb.mActiveDeviceAddress == null || cb.mActiveBluetoothDevice == null) {
            {
                if (doLog) {
                    Log.i(LOG_ID, SerialNumber + " " + "cb.mActiveBluetoothDevice == null");
                }
            }
            if (blueone != null) {
                blueone.scanStarter(0);
            }
            foundtime = 0L;
            return null;
        }
        return () -> {
            markConnectRunnableStarted();
            try {
            {
                if (doLog) {
                    Log.i(LOG_ID, "getConnectDevice Runnable " + SerialNumber);
                }
                ;
            }
            ;
            if (stop || SensorOwnershipRuntime.blocksLocalConnection(SerialNumber)
                    || (dataptr == 0L && !allowConnectWithoutDataptr())) {
                if (doLog) {
                    Log.i(LOG_ID, SerialNumber + " getConnectDevice: cancelled (stop=" + stop
                            + ", ownershipReleased=" + SensorOwnershipRuntime.blocksLocalConnection(SerialNumber)
                            + ", dataptr=" + dataptr + ")");
                }
                return;
            }
            var device = cb.mActiveBluetoothDevice;
            var sensorbluetooth = blueone;
            if (sensorbluetooth == null) {
                Log.e(LOG_ID, SerialNumber + " " + "sensorbluetooth==null");
                return;
            }
            if (!sensorbluetooth.bluetoothIsEnabled()) {
                Log.e(LOG_ID, SerialNumber + " " + "!sensorbluetooth.bluetoothIsEnabled()");
                return;
            }
            if (device == null) {
                Log.e(LOG_ID, SerialNumber + " " + "device==null");
                return;
            }

            if (cb.mBluetoothGatt != null) {
                {
                    if (doLog) {
                        Log.d(LOG_ID, SerialNumber + " cb.mBluetoothGatt!=null");
                    }
                    ;
                }
                ;
                return;
            }
            var devname = device.getName();
            if (devname != null)
                mDeviceName = devname;
            if (doLog) {
                {
                    if (doLog) {
                        Log.d(LOG_ID, SerialNumber + " Try connection to " + device.getAddress() + " " + devname
                                + " autoconnect=" + cb.useAutoConnect());
                    }
                    ;
                }
                ;
            }
            try {
                cb.armConnectCallbackLatency();
                if (isWearable) {
                    cb.mBluetoothGatt = device.connectGatt(Applic.app, cb.useAutoConnect(), cb,
                            BluetoothDevice.TRANSPORT_LE);
                    cb.setGattOptions(cb.mBluetoothGatt);
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        cb.mBluetoothGatt = device.connectGatt(Applic.app, cb.useAutoConnect(), cb,
                                BluetoothDevice.TRANSPORT_LE);
                    } else {
                        cb.mBluetoothGatt = device.connectGatt(Applic.app, cb.useAutoConnect(), cb);
                    }
                }

                setpriority(cb.mBluetoothGatt);
                {
                    if (doLog) {
                        Log.i(LOG_ID, SerialNumber + " after connectGatt =" + cb.mBluetoothGatt);
                    }
                    ;
                }
                ;
                // cb.mBluetoothGatt.connect();
                connectTime = System.currentTimeMillis();
            } catch (SecurityException se) {
                var mess = se.getMessage();
                mess = mess == null ? "" : mess;
                String uit = ((Build.VERSION.SDK_INT > 30)
                        ? Applic.getContext().getString(R.string.turn_on_nearby_devices_permission)
                        : mess);
                Applic.Toaster(uit);

                Log.stack(LOG_ID, SerialNumber + " " + "connectGatt", se);
            } catch (Throwable e) {
                Log.stack(LOG_ID, SerialNumber + " " + "connectGatt", e);

            }
            } finally {
                clearPendingConnect();
            }
        };
    }

    private synchronized void markConnectRunnableStarted() {
        pendingConnectFuture = null;
    }

    private synchronized void clearPendingConnect() {
        connectPending = false;
        if (pendingConnectFuture != null) {
            pendingConnectFuture.cancel(false);
            pendingConnectFuture = null;
        }
    }

    public synchronized boolean connectDevice(long delayMillis) {
        if (doLog) {
            Log.i(LOG_ID, "connectDevice(" + delayMillis + ") " + SerialNumber);
        }
        ;
        if (stop || SensorOwnershipRuntime.blocksLocalConnection(SerialNumber)
                || (dataptr == 0L && !allowConnectWithoutDataptr())) {
            return false;
        }
        if (connectPending) {
            if (doLog) {
                Log.i(LOG_ID, "connectDevice skipped, connect already pending for " + SerialNumber);
            }
            return true;
        }
        Runnable connect = getConnectDevice();
        if (connect == null)
            return false;
        connectPending = true;
        pendingConnectFuture = Applic.scheduler.schedule(connect, delayMillis, TimeUnit.MILLISECONDS);
        /*
         * if(delayMillis>0)
         * Applic.app.getHandler().postDelayed(connect, delayMillis);
         * else
         * Applic.app.getHandler().post(connect);
         */
        return true;
    }

    private boolean used_priority = false;

    @SuppressLint("MissingPermission")
    void setpriority(BluetoothGatt bluegatt) {
        if (bluegatt != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (Natives.getpriority()) {
                    bluegatt.requestConnectionPriority(use_priority);
                    {
                        if (doLog) {
                            Log.i(LOG_ID, "requestConnectionPriority HIGH");
                        }
                        ;
                    }
                    ;
                    used_priority = true;
                } else {
                    if (used_priority) {
                        bluegatt.requestConnectionPriority(CONNECTION_PRIORITY_BALANCED);
                        {
                            if (doLog) {
                                Log.i(LOG_ID, "requestConnectionPriority LOW");
                            }
                            ;
                        }
                        ;
                        used_priority = false;
                    }
                }
            }
        } else {
            Log.e(LOG_ID, SerialNumber + " " + "setpriority BluetoothGatt==null");
        }
    }

    /**
     * Hidden framework callback (BluetoothGattCallback#onConnectionUpdated, present since
     * Android O but absent from the SDK stubs — hence no @Override). The stack invokes it
     * after every LL connection-parameter update, including ones the peripheral requested,
     * so it is the only signal that a sensor renegotiated away from the fast interval the
     * app asked for. Signature must stay exactly this for runtime dispatch to bind.
     *
     * No compiled call site reaches it, so R8 deleted it outright from every release build ever
     * shipped -- the framework called it 210 times into app1 and 207 into app2 during the
     * 2026-08-01 storm and not one "conn params" line was written. @Keep plus the matching rule
     * in proguard-rules.my; both, because the rule can drift and the annotation cannot.
     */
    @Keep
    public void onConnectionUpdated(BluetoothGatt gatt, int interval, int latency, int timeout, int status) {
        noteFirstGattCallback("onConnectionUpdated", gatt);
        onConnectionParamsUpdated(gatt, interval, latency, timeout, status);
    }

    /** SDK-visible relay of the hidden onConnectionUpdated; drivers override this one. */
    public void onConnectionParamsUpdated(BluetoothGatt gatt, int interval, int latency, int timeout, int status) {
    }

    boolean disableNoCheck(BluetoothGatt gatt, BluetoothGattCharacteristic ch) {
        gatt.setCharacteristicNotification(ch, false);
        BluetoothGattDescriptor descriptor = ch.getDescriptor(mCharacteristicConfigDescriptor);
        if (!descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
            Log.e(LOG_ID, SerialNumber + " " + "descriptor.setValue())  failed");
            return false;
        }
        return gatt.writeDescriptor(descriptor);
    }

    boolean disablenotification(BluetoothGatt gatt, BluetoothGattCharacteristic ch) {
        if (isNull(gatt)) {
            return false;
        }
        if (isNull(ch))
            return false;
        try {
            return disableNoCheck(gatt, ch);
        } catch (Throwable th) {
            Log.stack(LOG_ID, "disablenotification", th);
            return false;
        }
    }

    protected final boolean enableNotification(BluetoothGatt bluetoothGatt1,
            BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        return enableNotificationnote(SerialNumber, bluetoothGatt1, bluetoothGattCharacteristic);
    }

    protected final boolean enableIndication(BluetoothGatt bluetoothGatt1,
            BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        return enableIndicationnote(SerialNumber, bluetoothGatt1, bluetoothGattCharacteristic);
    }

    static boolean enableNotificationnote(String note, BluetoothGatt bluetoothGatt1,
            BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        return enableGattDescriptornote(note, bluetoothGatt1, bluetoothGattCharacteristic, ENABLE_NOTIFICATION_VALUE);
    }

    static boolean enableIndicationnote(String note, BluetoothGatt bluetoothGatt1,
            BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        return enableGattDescriptornote(note, bluetoothGatt1, bluetoothGattCharacteristic, ENABLE_INDICATION_VALUE);
    }

    protected boolean enableGattDescriptor(BluetoothGatt bluetoothGatt1,
            BluetoothGattCharacteristic bluetoothGattCharacteristic, byte[] type) {
        return enableGattDescriptornote(SerialNumber, bluetoothGatt1, bluetoothGattCharacteristic, type);
    }

    @SuppressLint("MissingPermission")
    static boolean enableGattDescriptornote(String note, BluetoothGatt bluetoothGatt1,
            BluetoothGattCharacteristic bluetoothGattCharacteristic, byte[] type) {
        try {
            BluetoothGattDescriptor descriptor = bluetoothGattCharacteristic
                    .getDescriptor(mCharacteristicConfigDescriptor);
            if (!descriptor.setValue(type)) {
                Log.e(LOG_ID, note + " " + "descriptor.setValue())  failed");
                return false;
            }
            final int originalWriteType = bluetoothGattCharacteristic.getWriteType();
            bluetoothGattCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            var success = bluetoothGatt1.writeDescriptor(descriptor);
            bluetoothGattCharacteristic.setWriteType(originalWriteType);
            if (!success) {
                Log.e(LOG_ID, note + " " + "bluetoothGatt1.writeDescriptor(descriptor))  failed");
                return success;
            }
            if (doLog) {
                showbytes(LOG_ID + " " + note + " " + "enableNotification ", type);
            }
            ;
            if (!bluetoothGatt1.setCharacteristicNotification(bluetoothGattCharacteristic, type[0] != 0)) {
                Log.e(LOG_ID, note + " " + "setCharacteristicNotification("
                        + bluetoothGattCharacteristic.getUuid().toString() + ",true) failed");
                return false;
            }
            return success;
        } catch (Throwable th) {
            Log.stack(LOG_ID, "enableGattDescriptor", th);
            return false;
        }
    }

    protected final boolean asknotification(BluetoothGattCharacteristic charac) {
        return enableNotification(mBluetoothGatt, charac);
    }

    public boolean matchDeviceName(String deviceName, String address) {
        return false;
    }

    public UUID getService() {
        return null;
    }

    public void bonded() {
    }

    public String mygetDeviceName() {
        if (mDeviceName != null)
            return mDeviceName;
        final var device = mActiveBluetoothDevice;
        if (device != null) {
            var name = device.getName();
            if (name != null)
                return name;
        }
        if (mActiveDeviceAddress != null)
            return mActiveDeviceAddress;
        return "?";
    }

    public void setGattOptions(BluetoothGatt gatt) {
        {
            if (doLog) {
                Log.i(LOG_ID, "setGattOptions(BluetoothGatt gatt) empty");
            }
            ;
        }
        ;
    }

    @Override
    public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
        noteFirstGattCallback("onPhyUpdate", gatt);
        {
            if (doLog) {
                Log.i(LOG_ID, "onPhyUpdate txPhy=" + txPhy + " rxPhy=" + rxPhy + " status=" + status);
            }
            ;
        }
        ;
    }

    /**
     * Relay for {@link BluetoothGatt#readRemoteRssi()}. Five drivers declared their own copy of
     * this callback and the base class had none, so a driver that did not was simply unable to see
     * a signal level: the whole 2026-08-01 jamming storm was logged without a single RSSI value in
     * it, which is why "jammed" and "out of range" are still indistinguishable in that dataset.
     * {@link #readrssi} is what the sensor screens read, so it is filled here for every driver
     * rather than once per driver; the drivers that already override this keep their own handling.
     */
    @Override
    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
        noteFirstGattCallback("onReadRemoteRssi", gatt);
        if (status == GATT_SUCCESS) {
            readrssi = rssi;
        }
        {
            if (doLog) {
                Log.i(LOG_ID, SerialNumber + " onReadRemoteRssi rssi=" + rssi + " status=" + status);
            }
            ;
        }
        ;
    }

    static public String bondString(int bonded) {
        return switch (bonded) {
            case BOND_NONE -> "BOND_NONE";
            case BOND_BONDING -> "BOND_BONDING";
            case BOND_BONDED -> "BOND_BONDED";
            case BluetoothDevice.ERROR -> "BOND ERROR";
            default -> "BOND Unknown";
        };
    }
}
