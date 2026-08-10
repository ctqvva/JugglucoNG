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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import androidx.annotation.RequiresApi;

import tk.glucodata.drivers.ManagedBluetoothSensorDriver;
import tk.glucodata.drivers.ManagedSensorIdentityRegistry;

import static android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED;
import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_BONDING;
import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE;
import static android.bluetooth.BluetoothProfile.GATT;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.BuildConfig.libreVersion;
import static tk.glucodata.Log.doLog;
//import static tk.glucodata.Log.showScanSettings;
//import static tk.glucodata.Log.showScanfilters;

public class SensorBluetooth {
    public static void setAutoconnect(boolean val) {
        Natives.setAndroid13(val);
        // if(!isWearable)
        SuperGattCallback.autoconnect = val;
    }

    public static SensorBluetooth blueone = null;

    public static void startscan() {
        if (blueone != null)
            blueone.scanStarter(0L);
    }

    private static final String LOG_ID = "SensorBluetooth";
    private static final int scantimeout = 390000;
    private static final int scaninterval = 60000;

    // public Applic Applic.app;
    static private BluetoothAdapter mBluetoothAdapter;
    private BroadcastReceiver mBluetoothAdapterReceiver = null;;
    static private BluetoothManager mBluetoothManager = null;

    @SuppressLint("MissingPermission")
    void enableBluetooth() {
        if (!mBluetoothAdapter.isEnabled()) {
            mBluetoothAdapter.enable();
        }
    }

    static public void reconnectall() {
        Log.i(LOG_ID, "reconnectall");
        final var wasblue = blueone;
        if (wasblue != null) {
            boolean shouldnotscan = true;
            final var now = System.currentTimeMillis();
            for (var cb : wasblue.gattcallbacks) {
                if (SensorOwnershipRuntime.blocksLocalConnection(cb.SerialNumber)) {
                    continue;
                }
                shouldnotscan = (cb.reconnect(now) && shouldnotscan);
            }
            if (!shouldnotscan) {
                if (wasblue.mBluetoothManager != null) {
                    wasblue.stopScan(false);
                    wasblue.scanStarter(0L);
                }
            }
        }
    }

    static void othersworking(SuperGattCallback current, long timmsec) {
        final var gatts = blueone.gattcallbacks;
        if (gatts.size() > 1) {
            for (var g : gatts) {
                if (g != current)
                    g.shouldreconnect(timmsec);
            }
        }
    }

    private boolean connectToAllActiveDevices(long delayMillis) {
        if (doLog) {
            Log.i(LOG_ID, "connectToAllActiveDevices(" + delayMillis + ")");
        }
        ;
        if (!bluetoothIsEnabled()) {
            Applic.Toaster(R.string.enable_bluetooth);
            return false;
        }
        boolean scan = false;
        for (var cb : gattcallbacks)
            if (!cb.connectDevice(delayMillis)) {
                scan = true;
            }
        if (scan) {
            return scanStarter(delayMillis);
        }
        return false;
    }

    public boolean connectToActiveDevice(SuperGattCallback cb, long delayMillis) {
        if (doLog) {
            Log.i(LOG_ID, "connectToActiveDevice(" + cb.SerialNumber + "," + delayMillis + ")");
        }
        ;
        if (!cb.connectDevice(delayMillis) && !mScanning) {
            return scanStarter(delayMillis);
        }
        return false;
    }

    // long unknownfound=0L;
    // String unknownname="";
    private SuperGattCallback getCallback(BluetoothDevice device) {
        return getCallback(device, null, null);
    }

    private SuperGattCallback getCallback(BluetoothDevice device, String advertisedName, ScanResult scanResult) {
        try {
            @SuppressLint("MissingPermission")
            String deviceName = advertisedName;
            if (deviceName == null || deviceName.trim().isEmpty()) {
                deviceName = device.getName();
            }
            {
                if (doLog) {
                    Log.i(LOG_ID, "deviceName=" + deviceName);
                }
                ;
            }
            ;
            String address = device.getAddress();
            for (var cb : gattcallbacks) {
                if (cb.mActiveDeviceAddress != null && address.equals(cb.mActiveDeviceAddress))
                    return cb;
            }

            if (deviceName != null) {
                for (var cb : gattcallbacks) {
                    if (cb.matchDeviceName(deviceName, address)) {
                        cb.mDeviceName = deviceName;
                        return cb;
                    }
                    {
                        if (doLog) {
                            Log.d(LOG_ID, "not: " + cb.SerialNumber);
                        }
                        ;
                    }
                    ;
                }
            } else if (doLog) {
                Log.d(LOG_ID, "Scan returns device without name");
            }

            if (scanResult != null) {
                for (var cb : gattcallbacks) {
                    if (cb.matchScanResult(scanResult)) {
                        return cb;
                    }
                }
            }
            return null;
        } catch (Throwable e) {
            Log.stack(LOG_ID, "getCallback", e);
            if (!Applic.canBluetooth())
                Applic.Toaster(R.string.turn_on_nearby_devices_permission);
            return null;
        }
    }

    // long foundtime=0L;

    @SuppressLint("MissingPermission")
    private boolean checkdevice(BluetoothDevice device) {
        return checkdevice(device, null, null);
    }

    private boolean checkdevice(BluetoothDevice device, String advertisedName, ScanResult scanResult) {
        try {
            SuperGattCallback cb = getCallback(device, advertisedName, scanResult);
            if (cb != null) {
                boolean newdev = true;
                if (cb.foundtime == 0L) {
                    cb.foundtime = System.currentTimeMillis();
                    int state;
                    if (cb.mBluetoothGatt != null && cb.mActiveBluetoothDevice == device
                            && ((state = mBluetoothManager.getConnectionState(device,
                                    GATT)) == BluetoothGatt.STATE_CONNECTED
                                    || state == BluetoothGatt.STATE_CONNECTING)) {
                        newdev = false;
                        {
                            if (doLog) {
                                Log.i(LOG_ID, "old device connected state=" + state);
                            }
                            ;
                        }
                        ;
                    }
                } else {
                    newdev = false;
                    {
                        if (doLog) {
                            Log.i(LOG_ID, "old device connected foundtime=" + cb.foundtime);
                        }
                        ;
                    }
                    ;
                }

                boolean ret = true;
                cb.setDevice(device);
                for (SuperGattCallback one : gattcallbacks) {
                    if (one.mActiveBluetoothDevice == null) {
                        {
                            if (doLog) {
                                Log.i(LOG_ID, one.SerialNumber + " not found");
                            }
                            ;
                        }
                        ;

                        ret = false;
                        break;
                    }
                }
                if (ret)
                    SensorBluetooth.this.stopScan(false);
                if (newdev) {
                    SensorBluetooth.this.connectToActiveDevice(cb, 0);
                }
                return ret;
            }
            {
                if (doLog) {
                    Log.d(LOG_ID, "BLE unknown device");
                }
                ;
            }
            ;
            return false;
        } catch (Throwable e) {
            Log.stack(LOG_ID, "checkdevice", e);
            if (Build.VERSION.SDK_INT > 30 && !Applic.mayscan())
                Applic.Toaster(R.string.turn_on_nearby_devices_permission);
            return true;
        }
    }

    long scantimeouttime = 0L;

    boolean mScanning = false;

    class Scanner21 implements Scanner {
        final private ScanSettings mScanSettings;
        private BluetoothLeScanner mBluetoothLeScanner = null;
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        private final ScanCallback mScanCallback = new ScanCallback() {
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            private synchronized boolean processScanResult(ScanResult scanResult) {
                if (!mScanning) {
                    Log.i(LOG_ID, "!mScanning");
                    return true;
                }
                if (gattcallbacks.size() < 1) {
                    {
                        if (doLog) {
                            Log.w(LOG_ID, "No Sensors to search for");
                        }
                        ;
                    }
                    ;
                    SensorBluetooth.this.stopScan(false);
                    return true;
                }
                String advertisedName = scanResult.getScanRecord() == null
                        ? null
                        : scanResult.getScanRecord().getDeviceName();
                return checkdevice(scanResult.getDevice(), advertisedName, scanResult);
            }
            // private boolean resultbusy=false;

            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public void onScanResult(int callbackType, ScanResult scanResult) {
                if (doLog) {
                    Log.d(LOG_ID, "onScanResult");
                }
                ;
                processScanResult(scanResult);
                String advertisedName = scanResult.getScanRecord() == null
                        ? null
                        : scanResult.getScanRecord().getDeviceName();
                SuperGattCallback cb = getCallback(scanResult.getDevice(), advertisedName, scanResult);
                if (cb != null && !SensorOwnershipRuntime.blocksLocalConnection(cb.SerialNumber)) {
                    cb.onScanResult(scanResult);
                }
            }

            @Override
            public void onBatchScanResults(List<ScanResult> list) {
                if (doLog) {
                    Log.v(LOG_ID, "onBatchScanResults");
                }
                ;
                final var len = list.size();
                for (int i = 0; i < len && !processScanResult(list.get(i)); ++i)
                    ;
            }

            @Override
            public void onScanFailed(int errorCode) {
                if (doLog) {
                    final String[] scanerror = { "SCAN_0",
                            "SCAN_FAILED_ALREADY_STARTED",
                            "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED",
                            "SCAN_FAILED_INTERNAL_ERROR",
                            "SCAN_FAILED_FEATURE_UNSUPPORTED" };
                    {
                        if (doLog) {
                            Log.d(LOG_ID, "BLE SCAN ERROR: scan failed with error code: "
                                    + ((errorCode < scanerror.length) ? scanerror[errorCode] : "") + " " + errorCode);
                        }
                        ;
                    }
                    ;
                }
                if (errorCode != SCAN_FAILED_ALREADY_STARTED) {
                    SensorBluetooth.this.stopScan(false);
                    if (errorCode != SCAN_FAILED_FEATURE_UNSUPPORTED) {
                        SensorBluetooth.this.scanStarter(scaninterval);
                    }
                }
            }
        };
        private static final boolean alwaysfilter = false;

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        Scanner21() {
            ScanSettings.Builder builder = new ScanSettings.Builder();
            builder.setReportDelay(0);
            mScanSettings = builder.build();
            {
                if (doLog) {
                    Log.i(LOG_ID, "Scanner21");
                }
                ;
            }
            ;
        }

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        public boolean init() {
            if (mBluetoothAdapter == null) {
                BluetoothManager mBluetoothManager = (BluetoothManager) Applic.app
                        .getSystemService(Context.BLUETOOTH_SERVICE);
                if (mBluetoothManager != null) {
                    mBluetoothAdapter = mBluetoothManager.getAdapter();
                }
            }
            if (doLog) {
                Log.i(LOG_ID, "Scanner21.init adapter=" + (mBluetoothAdapter != null));
            }
            if (mBluetoothAdapter == null) {
                return false;
            }
            mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
            return (mBluetoothLeScanner != null);
        }

        private int scanTries = 0;

        @SuppressLint("MissingPermission")
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        public boolean start() {
            if (mBluetoothLeScanner != null) {
                if (doLog) {
                    Log.i(LOG_ID, "Scanner21.start ENTRY");
                }
                List<ScanFilter> mScanFilters = new ArrayList<>();
                // Remove the %2 logic to ensure we always scan when requested
                if (true) {
                    if (doLog) {
                        Log.d(LOG_ID, "SCAN: preparing filters for " + gattcallbacks.size() + " devices");
                    }
                    for (var cb : gattcallbacks) {
                        final var service = cb.getService();
                        if (service == null) {
                            if (doLog) {
                                Log.i(LOG_ID, "SCAN: " + cb.SerialNumber + " has no service filter");
                            }
                            mScanFilters = null;
                            break; // If one is null, we scan without filters
                        } else {
                            if (mScanFilters != null) {
                                if (doLog) {
                                    Log.i(LOG_ID, "SCAN: add filter " + service.toString() + " for " + cb.SerialNumber);
                                }
                                ScanFilter.Builder builder2 = new ScanFilter.Builder();
                                builder2.setServiceUuid(new ParcelUuid(service));
                                mScanFilters.add(builder2.build());
                            }
                        }
                    }
                }

                if (doLog) {
                    Log.i(LOG_ID, "SCAN: calling startScan with "
                            + (mScanFilters == null ? "NO FILTERS" : mScanFilters.size() + " filters"));
                }
                try {
                    this.mBluetoothLeScanner.startScan(mScanFilters, mScanSettings, mScanCallback);
                } catch (Throwable e) {
                    Log.stack(LOG_ID, e);
                    if (Build.VERSION.SDK_INT > 30 && !Applic.mayscan())
                        Applic.Toaster(R.string.turn_on_nearby_devices_permission);
                    return false;
                }
                return true;
            }
            return false;
        }

        @SuppressLint("MissingPermission")
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        public void stop() {
            if (mBluetoothLeScanner != null) {
                {
                    if (doLog) {
                        Log.i(LOG_ID, "Scanner21.stop");
                    }
                    ;
                }
                ;
                try {
                    mBluetoothLeScanner.stopScan(mScanCallback);
                } catch (Throwable e) {
                    Log.stack(LOG_ID, e);
                }
            }
        };

    };

    @SuppressWarnings("deprecation")
    class ArchScanner implements Scanner {
        BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {
            @Override
            public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
                checkdevice(device);
                SuperGattCallback cb = getCallback(device);
                if (cb != null) {
                    cb.onScanRecord(scanRecord);
                }
            }
        };

        public boolean init() {
            {
                if (doLog) {
                    Log.i(LOG_ID, "ArchScanner.init");
                }
                ;
            }
            ;
            return true;
        }

        @SuppressLint("MissingPermission")
        public boolean start() {
            if (doLog) {
                Log.d(LOG_ID, "SCAN: starting scan.");
            }
            ;
            switch (gattcallbacks.size()) {
                case 0:
                    Log.e(LOG_ID, "nothing to scan for");
                    return false;
                case 1:
                    final var service = gattcallbacks.get(0).getService();
                    if (service != null) {
                        return SensorBluetooth.mBluetoothAdapter.startLeScan(new UUID[] { service }, mLeScanCallback);
                    }
            }
            return SensorBluetooth.mBluetoothAdapter.startLeScan(mLeScanCallback);
        }

        @SuppressLint("MissingPermission")
        public void stop() {
            SensorBluetooth.mBluetoothAdapter.stopLeScan(mLeScanCallback);
        };

    };

    Scanner scanner = Build.VERSION.SDK_INT >= 21 ? new Scanner21() : new ArchScanner();

    final private Runnable mScanTimeoutRunnable = () -> {
        {
            if (doLog) {
                Log.i(LOG_ID, "Timeout scanning");
            }
            ;
        }
        ;
        scantimeouttime = System.currentTimeMillis();
        SensorBluetooth.this.stopScan(true);
    };

    static boolean bluetoothIsEnabled() {
        if (mBluetoothAdapter != null) {
            return mBluetoothAdapter.isEnabled();
        }
        // Fallback: try to get adapter from system service
        try {
            if (Applic.app != null) {
                android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) Applic.app
                        .getSystemService(Context.BLUETOOTH_SERVICE);
                if (bm != null) {
                    android.bluetooth.BluetoothAdapter adapter = bm.getAdapter();
                    if (adapter != null)
                        return adapter.isEnabled();
                }
            }
        } catch (Throwable t) {
            String msg = t.getMessage();
            Log.e(LOG_ID, "bluetoothIsEnabled fallback failed: " + (msg != null ? msg : t.toString()));
        }
        return false;
    }

    static public void sensorEnded(String str) {
        if (blueone != null)
            blueone.removeDevice(str);
    }

    private boolean scanstart = false;

    /**
     * Whether the managed scan is running or about to run, the same condition scanStarter()
     * refuses a second scan on. Android throttles startScan per app, not per scanner: a driver
     * opening its own scanner while this one is up spends one of the five starts the platform
     * allows in 30s, and the call the platform then fails can be this scan -- the discovery and
     * recovery path for every sensor family.
     */
    static public boolean scanActiveOrPending() {
        final SensorBluetooth blue = blueone;
        return blue != null && (blue.mScanning || blue.scanstart);
    }

    long scantime = 0L;
    final private Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (doLog) {
                    Log.i(LOG_ID, "scanRunnable ENTRY");
                }
                scantime = System.currentTimeMillis();
                if (bluetoothIsEnabled() && gattcallbacks.size() != 0) {
                    if (!scanner.init()) {
                        Log.w(LOG_ID, "Scanner init failed, retrying in 2s...");
                        if (scanOnUI) {
                            Applic.app.getHandler().postDelayed(scanRunnable, 2000);
                        } else {
                            scanFuture = Applic.scheduler.schedule(scanRunnable, 2000, TimeUnit.MILLISECONDS);
                        }
                        return;
                    }
                    if (scanner.start()) {
                        mScanning = true;
                        if (scanOnUI) {
                            Applic.app.getHandler().postDelayed(mScanTimeoutRunnable, scantimeout);
                        } else {
                            timeoutFuture = Applic.scheduler.schedule(mScanTimeoutRunnable, scantimeout,
                                    TimeUnit.MILLISECONDS);
                        }
                        Log.i(LOG_ID, "scanRunnable: Scanner STARTED");
                    } else {
                        scanstart = false;
                        Log.w(LOG_ID, "scanRunnable: Scanner START FAILED");
                        return;
                    }
                } else {
                    scanstart = false;
                    Log.i(LOG_ID, "scanRunnable: nothing to scan (BT off or no callbacks)");
                }
            } catch (Exception e) {
                scanstart = false;
                Log.stack(LOG_ID, "scanRunnable EXCEPTION", e);
            }
        }

    };

    static private final boolean scanOnUI = false;
    ScheduledFuture<?> scanFuture = null, timeoutFuture = null;

    public synchronized boolean scanStarter(long delayMillis) {
        {
            if (doLog) {
                Log.i(LOG_ID, "scanStarter(" + delayMillis + ")");
            }
            ;
        }
        ;
        var main = MainActivity.thisone;
        if (!((main == null && Applic.mayscan()) || (main != null && main.finepermission()))) {
            Applic.Toaster((Build.VERSION.SDK_INT > 30) ? R.string.turn_on_nearby_devices_permission
                    : R.string.turn_on_location_permission);
            return true;
        }

        if (!bluetoothIsEnabled()) {
            Applic.Toaster(R.string.bluetooth_is_turned_off);
            return false;
        }
        if (mScanning || scanstart) {
            if (doLog) {
                Log.i(LOG_ID, "scanStarter skipped, scan already active/pending");
            }
            return false;
        }
        for (SuperGattCallback cb : gattcallbacks) {
            if (cb.mBluetoothGatt == null) {
                if (cb instanceof tk.glucodata.drivers.ManagedBluetoothSensorDriver) {
                    final tk.glucodata.drivers.ManagedBluetoothSensorDriver managed =
                            (tk.glucodata.drivers.ManagedBluetoothSensorDriver) cb;
                    if (!managed.shouldShowSearchingStatusWhenIdle()) {
                        continue;
                    }
                }
                cb.constatstatusstr = "Searching for sensors";
            }
        }
        Applic.updatescreen();
        scanstart = true;

        if (scanOnUI) {
            if (delayMillis > 0)
                Applic.app.getHandler().postDelayed(scanRunnable, delayMillis);
            else
                Applic.app.getHandler().post(scanRunnable);
        } else {
            scanFuture = Applic.scheduler.schedule(scanRunnable, delayMillis, TimeUnit.MILLISECONDS);
        }
        return false;
    }

    long stopscantime = 0L;
    private static final int startincreasedwait = 300000;
    private int increasedwait = startincreasedwait;

    public void stopScan(boolean retry) {
        if (doLog) {
            Log.d(LOG_ID, "Stop scanning " + (retry ? "retry" : "don't retry"));
        }
        ;
        if (scanOnUI) {
            Applic.app.getHandler().removeCallbacks(this.scanRunnable);
            Applic.app.getHandler().removeCallbacks(this.mScanTimeoutRunnable);
        } else {
            if (scanFuture != null) {
                scanFuture.cancel(true);
                scanFuture = null;
            }
            if (timeoutFuture != null) {
                timeoutFuture.cancel(true);
                timeoutFuture = null;
            }
        }
        if (this.mScanning) {
            stopscantime = System.currentTimeMillis();
            this.mScanning = false;
            scanner.stop();
            if (retry) {
                if (bluetoothIsEnabled()) {
                    int waitscan = scaninterval;
                    if (scantime > 0L) {
                        for (SuperGattCallback cb : gattcallbacks) {
                            if (cb.foundtime > scantime && SuperGattCallback.lastfound() > cb.foundtime) {
                                increasedwait *= 2;
                                waitscan = increasedwait;
                            }
                        }
                    }
                    scanStarter(waitscan);
                }
            }
        }
        scanstart = false;
    }

    // static final ArrayList<SuperGattCallback> gattcallbacks = new ArrayList<>();
    public static final ArrayList<SuperGattCallback> gattcallbacks = new ArrayList<>();

    public static ArrayList<SuperGattCallback> mygatts() {
        synchronized (gattcallbacks) {
            return new ArrayList<>(gattcallbacks);
        }
    }

    private static void addSelectionCandidate(List<String> candidates, Set<String> seen, String serial) {
        if (!isValidShortSensorName(serial)) {
            return;
        }
        if (containsMatching(candidates, serial)) {
            return;
        }
        if (seen.add(serial)) {
            candidates.add(serial);
        }
    }

    private static boolean containsMatching(List<String> sensors, String serial) {
        if (!isValidShortSensorName(serial)) {
            return false;
        }
        for (String candidate : sensors) {
            if (SensorIdentity.matches(candidate, serial)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOfMatching(String[] sensors, String serial) {
        if (sensors == null || !isValidShortSensorName(serial)) {
            return -1;
        }
        for (int i = 0; i < sensors.length; i++) {
            if (SensorIdentity.matches(sensors[i], serial)) {
                return i;
            }
        }
        return -1;
    }

    public static String resolvePreferredCurrentSensor() {
        final ArrayList<String> candidates = new ArrayList<>();
        final HashSet<String> seen = new HashSet<>();
        try {
            final String[] activeSensors = Natives.activeSensors();
            if (activeSensors != null) {
                for (String serial : activeSensors) {
                    addSelectionCandidate(candidates, seen, serial);
                }
            }
        } catch (Throwable t) {
            Log.e(LOG_ID, "resolvePreferredCurrentSensor activeSensors failed: " + t.getMessage());
        }
        synchronized (gattcallbacks) {
            for (SuperGattCallback cb : gattcallbacks) {
                addSelectionCandidate(candidates, seen, cb.SerialNumber);
            }
        }
        return SensorIdentity.resolveAvailableMainSensor(
                Natives.lastsensorname(),
                candidates.isEmpty() ? null : candidates.get(0),
                candidates.toArray(new String[0]));
    }

    public static void ensureCurrentSensorSelection() {
        final String current = Natives.lastsensorname();
        if (current != null && !current.isEmpty() && SensorIdentity.hasNativeSensorBacking(current)) {
            return;
        }
        final String resolved = resolvePreferredCurrentSensor();
        if (resolved != null && !resolved.isEmpty()) {
            setCurrentSensorSelection(resolved);
            if (doLog) {
                Log.i(LOG_ID, "ensureCurrentSensorSelection -> " + resolved);
            }
        }
    }

    private void adoptCurrentSensorIfBlank(String serial) {
        if (serial == null || serial.isEmpty()) {
            return;
        }
        final String current = Natives.lastsensorname();
        if ((current == null || current.isEmpty()) && ManagedCurrentSensor.get() == null) {
            setCurrentSensorSelection(serial);
            if (doLog) {
                Log.i(LOG_ID, "adoptCurrentSensorIfBlank -> " + serial);
            }
        }
    }

    public static void setCurrentSensorSelection(String serial) {
        if (serial == null || serial.isEmpty()) {
            ManagedCurrentSensor.clear();
            Natives.setcurrentsensor("");
            return;
        }
        if (SensorIdentity.hasNativeSensorBacking(serial)) {
            // The managed-current slot is only for sensors that cannot be
            // represented by native lastsensorname. Selecting any native-backed
            // sensor must clear a previous managed primary, otherwise
            // SensorIdentity.resolveMainSensor() keeps returning that stale
            // managed id ahead of the native current sensor.
            ManagedCurrentSensor.clear();
            final String nativeSerial = SensorIdentity.resolveNativeSensorName(serial);
            Natives.setcurrentsensor(nativeSerial != null && !nativeSerial.isEmpty() ? nativeSerial : serial);
        } else {
            ManagedCurrentSensor.set(serial);
            final String current = Natives.lastsensorname();
            if (current != null && !current.isEmpty() && !SensorIdentity.hasNativeSensorBacking(current)) {
                Natives.setcurrentsensor("");
            }
        }
    }

    private static void addReplacementCandidate(
            List<String> candidates,
            Set<String> seen,
            String serial,
            String removedSerial) {
        if (!isValidShortSensorName(serial) || SensorIdentity.matches(serial, removedSerial)) {
            return;
        }
        if (seen.add(serial)) {
            candidates.add(serial);
        }
    }

    private static String resolveReplacementSensorSerial(String removedSerial, Iterable<String> preferredCandidates) {
        final ArrayList<String> candidates = new ArrayList<>();
        final HashSet<String> seen = new HashSet<>();
        if (preferredCandidates != null) {
            for (String serial : preferredCandidates) {
                addReplacementCandidate(candidates, seen, serial, removedSerial);
            }
        }
        try {
            final String[] activeSensors = Natives.activeSensors();
            if (activeSensors != null) {
                for (String serial : activeSensors) {
                    addReplacementCandidate(candidates, seen, serial, removedSerial);
                }
            }
        } catch (Throwable t) {
            Log.e(LOG_ID, "resolveReplacementSensorSerial activeSensors failed: " + t.getMessage());
        }

        final SensorBluetooth currentBlue = blueone;
        if (currentBlue != null) {
            for (SuperGattCallback cb : currentBlue.gattcallbacks) {
                addReplacementCandidate(candidates, seen, cb.SerialNumber, removedSerial);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    public static String resolveReplacementSensorSerial(String removedSerial) {
        return resolveReplacementSensorSerial(removedSerial, null);
    }

    private void rehomeCurrentSensorAfterRemoval(String removedSerial) {
        rehomeCurrentSensorAfterRemoval(removedSerial, null);
    }

    private void rehomeCurrentSensorAfterRemoval(String removedSerial, Iterable<String> preferredCandidates) {
        if (removedSerial == null || removedSerial.isEmpty()) {
            return;
        }
        final String current = SensorIdentity.resolveMainSensor();
        if (!SensorIdentity.matches(current, removedSerial)) {
            return;
        }
        final String replacement = resolveReplacementSensorSerial(removedSerial, preferredCandidates);
        setCurrentSensorSelection(replacement != null ? replacement : "");
        if (doLog) {
            Log.i(LOG_ID, "rehomeCurrentSensorAfterRemoval " + removedSerial + " -> "
                    + (replacement != null ? replacement : "<cleared>"));
        }
    }

    private synchronized void removeDevice(String str) {
        // Use SensorIdentity.matches() instead of strict String.equals so that
        // disconnect/forget works regardless of which form of the serial the UI
        // passes in: provisional ICN- alias, 11-char short tail, 16-char
        // canonical, or 24-char vendor-padded form. Strict equality previously
        // logged "didn't remove" whenever any of those forms didn't byte-match
        // the live gattcallbacks SerialNumber, leaving stale gatts in the list.
        for (int i = 0; i < gattcallbacks.size(); i++) {
            var gatt = gattcallbacks.get(i);
            if (callbackMatchesSensorId(gatt, str)) {
                final String removedSerial = gatt.SerialNumber;
                {
                    if (doLog) {
                        Log.i(LOG_ID, "removeDevice " + removedSerial);
                    }
                    ;
                }
                ;
                gatt.free();
                gattcallbacks.remove(i);
                rehomeCurrentSensorAfterRemoval(str);
                if (removedSerial != null && !removedSerial.equals(str)) {
                    rehomeCurrentSensorAfterRemoval(removedSerial);
                }
                Natives.setmaxsensors(gattcallbacks.size());
                removePersistedManagedSensor(str);
                if (removedSerial != null && !removedSerial.equals(str)) {
                    removePersistedManagedSensor(removedSerial);
                }
                for (; i < gattcallbacks.size(); ++i) {
                    gatt = gattcallbacks.get(i);
                    gatt.stopHealth = false;
                }
                return;
            } else {
                gatt.stopHealth = false;
            }
        }
        {
            if (doLog) {
                Log.i(LOG_ID, "removeDevice: didn't remove" + str);
            }
            ;
        }
        ;
    }

    private static void removePersistedManagedSensor(String serial) {
        if (Applic.app == null || serial == null || serial.isEmpty()) {
            return;
        }
        try {
            ManagedSensorIdentityRegistry.INSTANCE.removePersistedSensor(Applic.app, serial);
        } catch (Throwable t) {
            Log.e(LOG_ID, "removePersistedManagedSensor failed: " + t.getMessage());
        }
        // Also drop the ambient managed-current-sensor SharedPrefs slot if it was
        // pointing at this serial. Without this clear, after app restart the
        // dashboard's _currentSerial init resolves through ManagedCurrentSensor
        // → returns the deleted serial → Natives.getSensorStatusByName fires
        // "ERROR: <name> unknown sensor" on every refresh tick (and the same
        // ghost id surfaces in CSV export / "sensor not found" UI fallbacks).
        try {
            ManagedCurrentSensor.clearIfMatches(serial);
        } catch (Throwable t) {
            Log.e(LOG_ID, "ManagedCurrentSensor.clearIfMatches failed: " + t.getMessage());
        }
    }

    private void removeDevices() {
        {
            if (doLog) {
                Log.i(LOG_ID, "removeDevices()");
            }
            ;
        }
        ;
        for (int i = 0; i < gattcallbacks.size(); i++) {
            gattcallbacks.get(i).free();
        }
        gattcallbacks.clear();
        Natives.setmaxsensors(0);
    }

    private void destruct() {
        removeReceivers();
        if (mBluetoothManager != null) {
            stopScan(false);
            removeDevices();
        }
    }

    public static void destructor() {
        var bluetmp = blueone;
        if (bluetmp != null) {
            {
                if (doLog) {
                    Log.i(LOG_ID, "destructor blueone!=null");
                }
                ;
            }
            ;
            bluetmp.destruct();
            blueone = null;
        } else {
            if (doLog) {
                Log.i(LOG_ID, "destructor blueone==null");
            }
            ;
        }
        ;

    }
    // static boolean nullKAuth=false;

    private static ArrayList<String> distinctRuntimeSensorIds(Iterable<String> sensorIds) {
        final ArrayList<String> distinct = new ArrayList<>();
        if (sensorIds == null) {
            return distinct;
        }
        for (String sensorId : sensorIds) {
            if (!isValidShortSensorName(sensorId)) {
                continue;
            }
            if (!containsMatching(distinct, sensorId)) {
                distinct.add(sensorId);
            }
        }
        return distinct;
    }

    private void setDevices(String[] names) {
        names = filterActiveSensorNames(names);
        for (String name : distinctRuntimeSensorIds(names != null ? Arrays.asList(names) : null)) {
            if (name != null) {
                if (!isValidShortSensorName(name)) {
                    if (doLog) {
                        Log.w(LOG_ID, "setDevice skip invalid name " + name);
                    }
                    continue;
                }
                {
                    if (doLog) {
                        Log.i(LOG_ID, "setDevice " + name);
                    }
                    ;
                }
                ;
                if (findGattCallbackIndex(name) >= 0) {
                    continue;
                }
                if (hasPersistedManagedRecord(name) || shouldSuppressGenericManagedShell(name)) {
                    continue;
                }
                long dataptr = Natives.getdataptr(name);
                final SuperGattCallback callback = getGattCallback(name, dataptr);
                if (callback != null) {
                    if (findGattCallbackIndex(callback.SerialNumber != null ? callback.SerialNumber : name) >= 0) {
                        callback.free();
                        continue;
                    }
                    gattcallbacks.add(callback);
                    adoptCurrentSensorIfBlank(name);
                }
                increasedwait = startincreasedwait;
            }
        }
        addPersistedManagedCallbacks();
        Natives.setmaxsensors(gattcallbacks.size());
    }

    synchronized void addPersistedManagedCallbacks() {
        final Context context = Applic.app;
        if (context == null) {
            return;
        }
        for (String sensorId : ManagedSensorIdentityRegistry.INSTANCE.persistedSensorIds(context)) {
            if (findGattCallbackIndex(sensorId) >= 0) {
                continue;
            }
            final long dataptr = resolvePersistedManagedDataptr(sensorId);
            final SuperGattCallback cb = ManagedSensorIdentityRegistry.INSTANCE.createManagedCallback(context, sensorId, dataptr);
            if (cb == null) {
                continue;
            }
            if (findGattCallbackIndex(cb.SerialNumber) >= 0) {
                cb.free();
                continue;
            }
            gattcallbacks.add(cb);
            final boolean canRunWithoutNativeData =
                    cb instanceof ManagedBluetoothSensorDriver
                            && ((ManagedBluetoothSensorDriver) cb).canConnectWithoutDataptr();
            if (dataptr != 0L || canRunWithoutNativeData) {
                adoptCurrentSensorIfBlank(sensorId);
            }
            if (canRunWithoutNativeData) {
                cb.connectDevice(0);
            }
        }
    }

    private boolean hasPersistedManagedRecord(String sensorId) {
        final Context context = Applic.app;
        if (context == null || sensorId == null || sensorId.isEmpty()) {
            return false;
        }
        for (String persisted : ManagedSensorIdentityRegistry.INSTANCE.persistedSensorIds(context)) {
            if (SensorIdentity.matches(persisted, sensorId)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldSuppressGenericManagedShell(String sensorId) {
        if (sensorId == null || sensorId.isEmpty()) {
            return false;
        }
        if (hasPersistedManagedRecord(sensorId)) {
            return false;
        }
        final String managedNativeName = ManagedSensorIdentityRegistry.INSTANCE.resolveManagedNativeSensorName(sensorId);
        return managedNativeName != null && !managedNativeName.isEmpty();
    }

    private long resolvePersistedManagedDataptr(String sensorId) {
        final Context context = Applic.app;
        if (context == null || sensorId == null || sensorId.isEmpty()) {
            return 0L;
        }
        final Long managedDataptr = ManagedSensorIdentityRegistry.INSTANCE.resolveManagedCallbackDataptr(sensorId);
        if (managedDataptr != null) {
            return managedDataptr;
        }
        final String nativeName = ManagedSensorIdentityRegistry.INSTANCE.resolveManagedNativeSensorName(sensorId);
        if (nativeName == null || nativeName.isEmpty()) {
            return 0L;
        }
        return Natives.getdataptr(nativeName);
    }

    // Edit 85: Public accessor for the `stop` (paused) state of a gatt callback.
    // SuperGattCallback.stop is protected, so it's not accessible from Kotlin
    // code in a different package. SensorBluetooth is in the same package, so it
    // can read it and expose it publicly.
    public static boolean isSensorPaused(SuperGattCallback gatt) {
        return gatt != null && gatt.stop;
    }

    // constatstatusstr records the last connection event ("Loss of signal",
    // "Status=N", ...) and is never cleared when the link recovers. A reading
    // processed after that event (charcha[0], set on every successful glucose)
    // proves recovery, so the recorded status is history, not current state.
    // Same-package bridge for the Compose UI, like isSensorPaused.
    public static boolean connectionStatusOutdated(SuperGattCallback gatt) {
        return gatt != null
                && gatt.constatchange[1] != 0L
                && gatt.charcha[0] > gatt.constatchange[1];
    }

    // --- KOTLIN SENSORS (AiDex) SUPPORT ---
    public static void addAiDexSensor(Context context, String name, String address) {
        if (context == null || name == null || name.trim().isEmpty() || address == null || address.trim().isEmpty()) {
            Log.w(LOG_ID, "addAiDexSensor: invalid input name/address");
            return;
        }
        try {
            // Add to persistent storage
            android.content.SharedPreferences prefs = context.getSharedPreferences("tk.glucodata_preferences",
                    Context.MODE_PRIVATE);
            java.util.Set<String> sensors;
            try {
                sensors = prefs.getStringSet("aidex_sensors", new java.util.HashSet<>());
            } catch (ClassCastException cce) {
                Log.stack(LOG_ID, "addAiDexSensor: malformed aidex_sensors pref, resetting", cce);
                prefs.edit().remove("aidex_sensors").apply();
                sensors = new java.util.HashSet<>();
            }
            if (sensors == null) {
                sensors = new java.util.HashSet<>();
            }
            java.util.Set<String> newSensors = new java.util.HashSet<>(sensors);
            newSensors.add(name + "|" + address);
            prefs.edit().putStringSet("aidex_sensors", newSensors).apply();
            // aidex_sensors feeds AiDexManagedSensorIdentityAdapter.resolveCanonicalSensorId.
            SensorIdentity.invalidateCaches();

            // If SensorBluetooth is alive, add it immediately
            if (blueone != null) {
                String serial = name;
                // Check if already added (avoid duplicates)
                for (SuperGattCallback cb : blueone.gattcallbacks) {
                    if (cb.SerialNumber != null && cb.SerialNumber.equals(serial)) {
                        return;
                    }
                }
                long dataptr = Natives.getdataptr(name);
                // Clear finished flag so sensor appears in activeSensors/bluetoothactive
                if (dataptr != 0L) {
                    Natives.unfinishSensor(dataptr);
                }
                // Multi-sensor fix: Do NOT force this sensor as main on reconnect.
                // The user's main sensor selection should be respected. The old code
                // (Edit 85) called setcurrentsensor() here, which caused the main
                // sensor to silently switch to AiDex whenever it reconnected, even
                // if the user had intentionally set another sensor (e.g. Sibionics)
                // as main. The main sensor is now only changed explicitly by the user
                // or when the first-ever sensor is added (via addsensor() in C++).
                SuperGattCallback cb = tk.glucodata.drivers.aidex.AiDexNativeFactory.createBleManager(name, dataptr);
                cb.mActiveDeviceAddress = address;
                blueone.gattcallbacks.add(cb);
                blueone.adoptCurrentSensorIfBlank(serial);
                cb.connectDevice(0);
            }
        } catch (Throwable t) {
            Log.stack(LOG_ID, "addAiDexSensor", t);
        }
    }

    public void startDevices(String[] names) {
        setDevices(names);
        initializeBluetooth();
    }

    public static boolean syncNativeDevicesNoBluetooth() {
        try {
            if (blueone == null) {
                blueone = new tk.glucodata.SensorBluetooth();
            }
            if (blueone.mBluetoothManager != null) {
                blueone.stopScan(false);
            }
            blueone.removeDevices();
            String[] active = Natives.activeSensors();
            if (active != null && active.length > 0) {
                blueone.setDevices(active);
            }
            return true;
        } catch (Throwable t) {
            Log.stack(LOG_ID, "syncNativeDevicesNoBluetooth", t);
            return false;
        }
    }

    public boolean resetDevices() {
        if (!Natives.getusebluetooth()) {
            {
                if (doLog) {
                    Log.d(LOG_ID, "resetDevices !getusebluetooth()");
                }
                ;
            }
            ;
            return false;
        }
        if (mBluetoothManager != null)
            stopScan(false);
        removeDevices();
        setDevices(Natives.activeSensors());
        updateDevicers();
        return initializeBluetooth();
    }

    static <T> int indexOf(final T[] ar, final T el) {
        for (int i = 0; i < ar.length; i++)
            if (el.equals(ar[i]))
                return i;
        return -1;
    }

    private static boolean callbackMatchesSensorId(SuperGattCallback callback, String sensorId) {
        if (callback == null || sensorId == null) {
            return false;
        }
        if (SensorIdentity.matches(sensorId, callback.SerialNumber)) {
            return true;
        }
        if (callback instanceof ManagedBluetoothSensorDriver managed) {
            return managed.matchesManagedSensorId(sensorId);
        }
        return false;
    }

    private static int consumeMatchingDeviceIds(String[] sensors, SuperGattCallback callback) {
        if (sensors == null || callback == null) {
            return 0;
        }
        int consumed = 0;
        for (int i = 0; i < sensors.length; i++) {
            if (sensors[i] != null && callbackMatchesSensorId(callback, sensors[i])) {
                sensors[i] = null;
                consumed++;
            }
        }
        return consumed;
    }

    private static boolean isValidShortSensorName(String name) {
        // Accept any non-null, non-blank name. Sensor name formats vary by vendor:
        //   Libre: 11 alphanumeric chars
        //   AiDex/LinX: "X-" prefix
        //   Sibionics: serial (variable format)
        return SensorIdentity.isUsableSensorId(name);
    }

    private static boolean hasControlCharacter(String name) {
        if (name == null) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static void finishCorruptActiveSensorName(String name) {
        if (!hasControlCharacter(name)) {
            return;
        }
        final String displayName = name.replace("\u001D", "<GS>");
        try {
            final long sensorptr = Natives.str2sensorptr(name);
            if (sensorptr == 0L) {
                clearCurrentCorruptSensorName(name);
                Log.w(LOG_ID, "Ignored corrupt active sensor name " + displayName);
                return;
            }
            Natives.finishfromSensorptr(sensorptr);
            clearCurrentCorruptSensorName(name);
            Log.w(LOG_ID, "Finished corrupt active sensor name " + displayName);
        } catch (Throwable t) {
            Log.e(LOG_ID, "finish corrupt active sensor failed: " + t.getMessage());
        }
    }

    private static void clearCurrentCorruptSensorName(String name) {
        try {
            final String current = Natives.lastsensorname();
            if (Objects.equals(name, current) || !SensorIdentity.isUsableSensorId(current)) {
                setCurrentSensorSelection("");
            }
        } catch (Throwable t) {
            Log.e(LOG_ID, "clear corrupt current sensor failed: " + t.getMessage());
        }
    }

    private static String[] filterActiveSensorNames(String[] names) {
        if (names == null) {
            return null;
        }
        final ArrayList<String> valid = new ArrayList<>(names.length);
        for (String name : names) {
            if (isValidShortSensorName(name)) {
                valid.add(name);
            } else {
                // Control-character names are known malformed native records and can be
                // resolved safely by their full value. A placeholder such as "?" has no
                // identity: never pass it to native lookup/finish, as that could resolve
                // an unrelated sensor.
                if (hasControlCharacter(name)) {
                    finishCorruptActiveSensorName(name);
                } else {
                    clearCurrentCorruptSensorName(name);
                    Log.w(LOG_ID, "Ignored invalid active sensor identity " + name);
                }
            }
        }
        return valid.toArray(new String[0]);
    }

    public void connectNamedDevice(String id, long delayMillis) {
        for (var cb : gattcallbacks) {
            if (callbackMatchesSensorId(cb, id)) {
                if (!cb.connectDevice(delayMillis)) {
                    scanStarter(delayMillis);
                }
                return;
            }
        }
    }

    public boolean connectDevices(long delayMillis) {
        Log.i(LOG_ID, "connectDevices " + delayMillis);
        if (!bluetoothIsEnabled()) {
            Applic.Toaster(R.string.enable_bluetooth);
            return false;
        }
        boolean scan = false;
        for (var cb : gattcallbacks) {
            if (checkandconnect(cb, delayMillis))
                scan = true;
        }
        if (scan) {
            return scanStarter(delayMillis);
        }
        return false;
    }

    boolean updateDevicers() {
        if (!Natives.getusebluetooth()) {
            {
                if (doLog) {
                    Log.d(LOG_ID, "updateDevicers !getusebluetooth()");
                }
                ;
            }
            ;
            destruct();
            blueone = null;
            return false;
        }
        String[] nativeDevs = filterActiveSensorNames(Natives.activeSensors());

        ArrayList<String> candidateDevs = new ArrayList<>();
        if (nativeDevs != null) {
            for (String s : nativeDevs) {
                if (isValidShortSensorName(s)) {
                    candidateDevs.add(s);
                }
            }
        }
        for (String serial : ManagedSensorIdentityRegistry.INSTANCE.persistedSensorIds(Applic.app)) {
            if (isValidShortSensorName(serial)) {
                candidateDevs.add(serial);
            }
        }
        ArrayList<String> allDevs = distinctRuntimeSensorIds(candidateDevs);

        String[] devs = allDevs.toArray(new String[0]);
        ArrayList<Integer> rem = new ArrayList<>();
        int gatnr = gattcallbacks.size();
        if (devs == null) {
            for (int i = 0; i < gatnr; i++) {
                String was = gattcallbacks.get(i).SerialNumber;
                rem.add(i);
            }
            if (rem.size() == 0) {
                return false;
            }
        } else {

            int heb = 0;

            for (int i = 0; i < gatnr; i++) {
                var gatt = gattcallbacks.get(i);
                String was = gatt.SerialNumber;
                int matched = consumeMatchingDeviceIds(devs, gatt);
                if (matched == 0) {
                    rem.add(i);
                } else {
                    gatt.stopHealth = false;
                    heb += matched;
                }
            }
            if (devs.length == heb && rem.size() == 0) {
                return false;
            }
        }
        if (mBluetoothManager != null)
            stopScan(false);
        // rem.sort((x,y)->{return x-y;});
        Collections.sort(rem, (x, y) -> {
            return x - y;
        });

        for (int el = rem.size() - 1; el >= 0; el--) {
            int weg = rem.get(el);
            final String removedSerial = gattcallbacks.get(weg).SerialNumber;
            {
                if (doLog) {
                    Log.i(LOG_ID, "remove " + removedSerial);
                }
                ;
            }
            ;
            gattcallbacks.get(weg).free();
            gattcallbacks.remove(weg);
            rehomeCurrentSensorAfterRemoval(removedSerial, allDevs);
        }
        int index = gattcallbacks.size();
        if (devs != null) {
            for (String dev : devs) {
                if (dev != null) {
                    if (!isValidShortSensorName(dev)) {
                        if (doLog) {
                            Log.w(LOG_ID, "add skip invalid name " + dev);
                        }
                        continue;
                    }
                    {
                        if (doLog) {
                            Log.i(LOG_ID, "add " + dev);
                        }
                        ;
                    }
                    ;
                    final boolean persistedManaged = hasPersistedManagedRecord(dev);
                    final boolean suppressGenericManagedShell = shouldSuppressGenericManagedShell(dev);
                    final long managedDataptr =
                        (persistedManaged || suppressGenericManagedShell) ? resolvePersistedManagedDataptr(dev) : 0L;
                    if (persistedManaged || suppressGenericManagedShell) {
                        final SuperGattCallback managed = ManagedSensorIdentityRegistry.INSTANCE.createManagedCallback(Applic.app, dev, managedDataptr);
                        if (managed != null) {
                            gattcallbacks.add(managed);
                            if (managedDataptr != 0L) {
                                adoptCurrentSensorIfBlank(dev);
                            }
                            increasedwait = startincreasedwait;
                            index++;
                        }
                        continue;
                    }
                    final long dataptr = Natives.getdataptr(dev);
                    if (dataptr != 0L) {
                        gattcallbacks.add(getGattCallback(dev, dataptr));
                        adoptCurrentSensorIfBlank(dev);
                        increasedwait = startincreasedwait;
                        index++;
                    }
                }
            }
        }

        // nullKAuth=false;
        // Natives.setmaxsensors(gattcallbacks.size());
        if (mBluetoothManager == null || mBluetoothAdapter == null) {
            return initializeBluetooth();
        } else {
            addReceivers();
            return connectDevices(0);
        }
        // scanStarter(0);
    }

    public static boolean updateDevices() {
        {
            if (doLog) {
                Log.d(LOG_ID, "updateDevices");
            }
            ;
        }
        ;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
        }
        // blueone is null until BLE init completes; callers include Compose
        // view models built at first frame and the wear handoff receiver, so
        // this must never throw.
        final SensorBluetooth one = blueone;
        if (one == null) {
            Log.i(LOG_ID, "updateDevices before bluetooth init — skipped");
            return false;
        }
        return one.updateDevicers();
    }

    boolean checkandconnect(SuperGattCallback cb, long delay) {
        if (doLog) {
            Log.i(LOG_ID, "checkandconnect(" + cb.SerialNumber + "," + delay + ")");
        }
        ;
        if (SensorOwnershipRuntime.blocksLocalConnection(cb.SerialNumber)) {
            if (doLog) {
                Log.i(LOG_ID, "checkandconnect skipped: ownership released " + cb.SerialNumber);
            }
            return false;
        }
        BluetoothAdapter adapter = mBluetoothAdapter;
        if (adapter == null && mBluetoothManager != null) {
            try {
                adapter = mBluetoothManager.getAdapter();
                mBluetoothAdapter = adapter;
            } catch (Throwable th) {
                Log.stack(LOG_ID, "checkandconnect getAdapter", th);
            }
        }

        if (cb.mActiveDeviceAddress != null) {
            if (BluetoothAdapter.checkBluetoothAddress(cb.mActiveDeviceAddress)) {
                {
                    if (doLog) {
                        Log.i(LOG_ID,
                                cb.SerialNumber + " checkBluetoothAddress(" + cb.mActiveDeviceAddress + ") succeeded");
                    }
                    ;
                }
                ;
                if (adapter != null) {
                    cb.mActiveBluetoothDevice = adapter.getRemoteDevice(cb.mActiveDeviceAddress);
                } else if (doLog) {
                    Log.w(LOG_ID, cb.SerialNumber + " adapter unavailable for " + cb.mActiveDeviceAddress);
                }
                connectToActiveDevice(cb, delay);
                return false;
            }
            if (doLog) {
                Log.i(LOG_ID, cb.SerialNumber + " checkBluetoothAddress(" + cb.mActiveDeviceAddress + ") failed");
            }
            ;
            cb.setDeviceAddress(null);
        }

        var main = MainActivity.thisone;
        if ((main == null && Applic.mayscan()) || (main != null && main.finepermission())) {
            connectToActiveDevice(cb, delay);
            return false;
        }
        return true;
    }

    SuperGattCallback getGattCallback(String name, long dataptr) {
        if (name.startsWith("X-")) {
            return tk.glucodata.drivers.aidex.AiDexNativeFactory.createBleManager(name, dataptr);
        }
        if (libreVersion == 3 || tk.glucodata.BuildConfig.SiBionics == 1 || tk.glucodata.BuildConfig.DexCom == 1) {
            int vers = Natives.getLibreVersion(dataptr);
            if (libreVersion == 3) {
                if (vers == 3) {
                    return new Libre3GattCallback(name, dataptr);
                }
            }
            if (tk.glucodata.BuildConfig.DexCom == 1) {
                if (vers == 0x40) {
                    return new DexGattCallback(name, dataptr);
                }
                if (vers == 0x20) {
                    return new AccuGattCallback(name, dataptr);
                }
            }
            if (tk.glucodata.BuildConfig.SiBionics == 1) {
                if (vers == 0x10) {
                    return new SiGattCallback(name, dataptr);
                }
            }
        }
        return new Libre2GattCallback(name, dataptr);
    }

    private int findGattCallbackIndex(String serial) {
        if (serial == null) {
            return -1;
        }
        for (int i = 0; i < gattcallbacks.size(); i++) {
            SuperGattCallback cb = gattcallbacks.get(i);
            if (callbackMatchesSensorId(cb, serial)) {
                return i;
            }
        }
        return -1;
    }

    private boolean addDevice(String str, long dataptr) {
        {
            if (doLog) {
                Log.d(LOG_ID, "addDevice " + str);
            }
            ;
        }
        ;
        final int existingIndex = findGattCallbackIndex(str);
        if (existingIndex >= 0) {
            SuperGattCallback existing = gattcallbacks.get(existingIndex);
            if (doLog) {
                Log.w(LOG_ID, "addDevice dedupe existing callback for " + str);
            }
            if (dataptr != 0L) {
                existing.dataptr = dataptr;
            }
            return checkandconnect(existing, 0);
        }
        int index = gattcallbacks.size();
        if (dataptr != 0L) {
            SuperGattCallback cb = getGattCallback(str, dataptr);
            // nullKAuth=false;
            gattcallbacks.add(cb);
            adoptCurrentSensorIfBlank(str);
            Natives.setmaxsensors(gattcallbacks.size());
            increasedwait = startincreasedwait;
            if (mBluetoothManager == null) {
                return initializeBluetooth();
            } else {
                addReceivers();
                return checkandconnect(cb, 0);
            }
        } else {
            Log.e(LOG_ID, "dataptr==0L");
        }
        return false;

    }

    private boolean resetDevicer(long streamptr, String name) {
        if (mBluetoothManager != null)
            stopScan(false);
        for (int i = 0; i < gattcallbacks.size(); i++) {
            SuperGattCallback cb = gattcallbacks.get(i);
            if (Natives.sameSensor(streamptr, cb.dataptr)) {
                {
                    if (doLog) {
                        Log.d(LOG_ID, "reset free " + name);
                    }
                    ;
                }
                ;
                cb.resetdataptr();
                return checkandconnect(cb, 0);
            }
        }
        return addDevice(name, streamptr);
    }

    static public boolean resetDeviceOrFree(long ptr, String name) {
        if (blueone != null) {
            return blueone.resetDevicer(ptr, name);
        } else
            Natives.updateUsedSensors();
        Natives.freedataptr(ptr);
        return false;
    }

    private boolean resetDevicer(String str, long[] ptrptr) {
        if (str == null) {
            ptrptr[0] = 0L;
            return false;
        }
        if (mBluetoothManager != null)
            stopScan(false);
        for (int i = 0; i < gattcallbacks.size(); i++) {
            if (str.equals(gattcallbacks.get(i).SerialNumber)) {
                if (doLog) {
                    Log.d(LOG_ID, "reset free " + str);
                }
                ;
                SuperGattCallback cb = gattcallbacks.get(i);
                ptrptr[0] = cb.resetdataptr();
                return checkandconnect(cb, 0);
            }
        }

        {
            if (doLog) {
                Log.d(LOG_ID, "reset add " + str);
            }
            ;
        }
        ;
        final long dataptr = Natives.getdataptr(str);
        ptrptr[0] = dataptr;
        return addDevice(str, dataptr);
    }

    static public boolean resetDevice(String str) {
        long[] ptrptr = { 0L };
        var ret = resetDevicePtr(str, ptrptr);
        SuperGattCallback.glucosealarms.setLossAlarm();
        return ret;
    }

    static private boolean resetDevicePtr(String str, long[] ptrptr) {
        {
            if (doLog) {
                Log.v(LOG_ID, "resetDevice(" + str + ")");
            }
            ;
        }
        ;
        if (!Natives.getusebluetooth()) {
            Natives.updateUsedSensors();
            return false;
        }
        if (blueone == null) {
            blueone = new tk.glucodata.SensorBluetooth();
        }
        return blueone.resetDevicer(str, ptrptr);
    }

    static public void goscan() {
        if (blueone != null) {
            blueone.connectToAllActiveDevices(0);
        }
    }

    public SensorBluetooth() {
        if (doLog) {
            Log.v(LOG_ID, "SensorBluetooth");
        }
        ;
        SuperGattCallback.autoconnect = Natives.getAndroid13();

        // SuperGattCallback.glucosealarms.setLossAlarm();
    }

    static void start(boolean usebluetooth) {
        final var sensors = filterActiveSensorNames(Natives.activeSensors());
        final boolean hasSensors = sensors != null && sensors.length > 0;
        if (hasSensors) {
            // if(!keeprunning.started) Notify.shownovalue();
            SuperGattCallback.glucosealarms.setLossAlarm();
        }
        if (doLog) {
            Log.v(LOG_ID, "SensorBluetooth.start(" + usebluetooth + ")");
        }
        ;
        if (usebluetooth) {
            if (SensorBluetooth.blueone == null) {
                blueone = new tk.glucodata.SensorBluetooth();
                if (blueone != null) {
                    if (hasSensors) {
                        blueone.startDevices(sensors);
                    } else {
                        // No native BT sensors, but managed sensors (e.g. NightscoutFollower) still need initialization.
                        blueone.addPersistedManagedCallbacks();
                    }
                }
            } else {
                if (hasSensors) {
                    blueone.connectDevices(0);
                } else {
                    blueone.addPersistedManagedCallbacks();
                }
            }
        }
    }

    static final boolean keepBluetooth = false;

    private void removeBluetoothStateReceiver() {
        var rec = mBluetoothAdapterReceiver;
        mBluetoothAdapterReceiver = null;
        if (rec != null) {
            try {
                Applic.app.unregisterReceiver(rec);
            } catch (Throwable th) {
                Log.stack(LOG_ID, "removeBluetoothStateReceiver", th);
            }
        }
    }

    private void addBluetoothStateReceiver() {
        if (mBluetoothAdapterReceiver == null) {
            mBluetoothAdapterReceiver = new BroadcastReceiver() {
                // private boolean wasScanning=false;
                @SuppressLint("MissingPermission")
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("android.bluetooth.adapter.action.STATE_CHANGED".equals(intent.getAction())) {
                        int intExtra = intent.getIntExtra("android.bluetooth.adapter.extra.STATE", -1);
                        if (intExtra == BluetoothAdapter.STATE_OFF) {
                            {
                                if (doLog) {
                                    Log.v(LOG_ID, "BLUETOOTH switched OFF");
                                }
                                ;
                            }
                            ;
                            // wasScanning=mScanning;
                            SensorBluetooth.this.stopScan(false);
                            for (var cb : gattcallbacks) {
                                if (cb.constatchange[1] < cb.constatchange[0]) {
                                    cb.constatchange[1] = System.currentTimeMillis();
                                    cb.constatstatusstr = "Bluetooth off"; // "
                                }
                                if (cb instanceof ManagedBluetoothSensorDriver managed) {
                                    // Adapter loss is a temporary transport event. Calling a
                                    // managed callback's virtual close() may terminate its
                                    // HandlerThread/executor and make Bluetooth recovery
                                    // impossible until the whole app is restarted.
                                    cb.closeGattTransport();
                                    try {
                                        managed.onBluetoothAdapterUnavailable();
                                    } catch (Throwable th) {
                                        Log.stack(LOG_ID, cb.SerialNumber + " adapter-off cleanup", th);
                                    }
                                } else {
                                    cb.close();
                                }
                            }
                            if (keepBluetooth)
                                mBluetoothAdapter.enable();
                        } else if (intExtra == BluetoothAdapter.STATE_ON) {
                            {
                                if (doLog) {
                                    Log.v(LOG_ID, "BLUETOOTH switched ON");
                                }
                                ;
                            }
                            ;
                            if (!isWearable) {
                                Applic.app.numdata.startall();
                            }
                            // if(wasScanning) { SensorBluetooth.this.scanStarter(250L); }
                            SensorBluetooth.this.connectToAllActiveDevices(500);
                        }
                    }
                }
            };
            Applic.app.registerReceiver(mBluetoothAdapterReceiver,
                    new IntentFilter("android.bluetooth.adapter.action.STATE_CHANGED"));
        }
    }

    private BroadcastReceiver pairingRequestReceiver = null;

    private void addPairingRequestReceiver() {
        removePairingRequestReceiver();
        try {
            pairingRequestReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    {
                        if (doLog) {
                            Log.i(LOG_ID, "onReceive ACTION_PAIRING_REQUEST");
                        }
                        ;
                    }
                    ;
                }
            };
            Applic.app.registerReceiver(pairingRequestReceiver,
                    new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST));
        } catch (Throwable e) {
            Log.stack(LOG_ID, "registerReceiver ACTION_PAIRING_REQUEST", e);
        }
    }

    private void removePairingRequestReceiver() {
        var rec = pairingRequestReceiver;
        if (rec != null) {
            try {
                Applic.app.unregisterReceiver(rec);
            } catch (Throwable e) {
                Log.stack(LOG_ID, "unregisterReceiver ACTION_PAIRING_REQUEST", e);
            } finally {
                pairingRequestReceiver = null;
            }
        }
    }

    private void addReceivers() {
        addBluetoothStateReceiver();
        addBondStateReceiver();
        if (Build.VERSION.SDK_INT < 26)
            addPairingRequestReceiver();
    }

    private void removeReceivers() {
        removeBluetoothStateReceiver();
        removeBondStateReceiver();
        if (Build.VERSION.SDK_INT < 26)
            removePairingRequestReceiver();
    }

    private BroadcastReceiver bondStateReceiver = null;

    private void addBondStateReceiver() {
        bondStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                final var tmp = blueone;
                if (tmp == null) {
                    {
                        if (doLog) {
                            Log.i(LOG_ID, "Bond Broadcast: no SensorBluetooth");
                        }
                        ;
                    }
                    ;
                    return;
                }
                if (device == null) {
                    Log.e(LOG_ID, "Bond Broadcast: BluetoothDevice.EXTRA_DEVICE ==null");
                    return;
                }
                String address = device.getAddress();
                if (address == null) {
                    Log.e(LOG_ID, "Bond Broadcast: device.getAddress()==null");
                    return;
                }
                final String action = intent.getAction();
                if (action == null) {
                    Log.e(LOG_ID, "Bond Broadcast: action==null");
                    return;
                }
                for (var cb : tmp.gattcallbacks) {
                    if (cb.mActiveDeviceAddress != null) {
                        if (address.equals(cb.mActiveDeviceAddress)) {
                            if (action.equals(ACTION_BOND_STATE_CHANGED)) {
                                final int bondState = intent.getIntExtra(EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                                final int previousBondState = intent
                                        .getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
                                switch (bondState) {
                                    case BOND_BONDING: {
                                        if (doLog) {
                                            Log.i(LOG_ID, "Broadcast: BOND_BONDING " + address);
                                        }
                                        ;
                                    }
                                        ;
                                        break;
                                    case BOND_BONDED: {
                                        if (doLog) {
                                            Log.i(LOG_ID, "Broadcast: BOND_BONDED " + address);
                                        }
                                        ;
                                    }
                                        ;
                                        break;
                                    case BOND_NONE: {
                                        if (doLog) {
                                            Log.i(LOG_ID, "Broadcast: BOND_NONE " + address);
                                        }
                                        ;
                                    }
                                        ;
                                        break;
                                    case BluetoothDevice.ERROR: {
                                        if (doLog) {
                                            Log.i(LOG_ID, "Broadcast: ERROR " + address);
                                        }
                                        ;
                                    }
                                        ;
                                        break;
                                    default: {
                                        if (doLog) {
                                            Log.i(LOG_ID, "Broadcast: " + bondState + " " + address);
                                        }
                                        ;
                                    }
                                        ;
                                }
                            }
                            cb.bonded();
                            return;
                        }
                    }
                }
                {
                    if (doLog) {
                        Log.i(LOG_ID, "Bond Broadcast: no sensor matches address " + address);
                    }
                    ;
                }
                ;
            }
        };
        Applic.app.registerReceiver(bondStateReceiver, new IntentFilter(ACTION_BOND_STATE_CHANGED));
    }

    private void removeBondStateReceiver() {
        final var rec = bondStateReceiver;
        bondStateReceiver = null;
        if (rec != null) {
            try {
                Applic.app.unregisterReceiver(rec);
            } catch (Throwable th) {
                Log.stack(LOG_ID, "removeBondStateReceiver", th);
            }
        }
    }

    private boolean initializeBluetooth() {
        {
            if (doLog) {
                Log.v(LOG_ID, "initializeBluetooth");
            }
            ;
        }
        ;
        if (!Applic.canBluetooth()) {
            Applic.Toaster(R.string.turn_on_nearby_devices_permission);
            Log.e(LOG_ID, "No Blueotooth permission");
            return false;
        }
        // mBluetoothManager = (BluetoothManager)
        // Applic.app.getSystemService("bluetooth");
        mBluetoothManager = (BluetoothManager) Applic.app.getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBluetoothManager == null) {
            {
                if (doLog) {
                    Log.i(LOG_ID, "getSystemService(\"BLUETOOTH_SERVICE\")==null");
                }
                ;
            }
            ;
        } else {
            mBluetoothAdapter = mBluetoothManager.getAdapter();
            if (mBluetoothAdapter == null) {
                if (doLog) {
                    Log.i(LOG_ID, "bluetoothManager.getAdapter()==null");
                }
                ;
            } else {
                if (gattcallbacks.size() != 0) {
                    if (doLog) {
                        Log.i(LOG_ID, "initializeBluetooth gattcallbacks");
                    }
                    ;
                    for (SuperGattCallback cb : gattcallbacks) {
                        if (cb.mActiveDeviceAddress != null) {
                            if (BluetoothAdapter.checkBluetoothAddress(cb.mActiveDeviceAddress)) {
                                Log.i(LOG_ID, "checkBluetoothAddress(" + cb.mActiveDeviceAddress + ") succeeded");
                                cb.mActiveBluetoothDevice = mBluetoothAdapter.getRemoteDevice(cb.mActiveDeviceAddress);
                            } else {
                                Log.i(LOG_ID, "checkBluetoothAddress(" + cb.mActiveDeviceAddress + ") failed");
                                cb.setDeviceAddress(null);
                            }
                        }
                    }
                    addReceivers();
                    return connectToAllActiveDevices(0);
                } else if (doLog) {
                    Log.i(LOG_ID, "initializeBluetooth no gattcallbacks");
                }
                ;
            }
        }

        return false;
    }
}
