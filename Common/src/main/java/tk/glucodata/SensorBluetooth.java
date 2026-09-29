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
    // A low-latency scan listens all the time. This caps each start of one, not the total: when it
    // expires the scan starts again and the mode is chosen anew, so full duty lasts as long as some
    // driver keeps asking, plus up to one cap after it stops (a running scan is not switched down
    // early, see scanStarter). The total bound is the driver's: its wantsLowLatencyScan() must turn
    // false by itself when its wait ends, as Ottai's time-gated wantsLowLatencyActivationScan does.
    private static final int lowlatencyscantimeout = 120000;

    // public Applic Applic.app;
    static private BluetoothAdapter mBluetoothAdapter;
    private BroadcastReceiver mBluetoothAdapterReceiver = null;;
    static private BluetoothManager mBluetoothManager = null;

    // legacy adapter API: required at minSdk 26
    @SuppressWarnings("deprecation")
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
        for (var cb : gattcallbacks) {
            if (mirroredOverClone(cb, "connectToAllActiveDevices")) {
                continue;
            }
            if (!cb.connectDevice(delayMillis)) {
                scan = true;
            }
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
        if (mirroredOverClone(cb, "connectToActiveDevice")) {
            return false;
        }
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
                Applic.missingScanPermission();
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
                Applic.missingScanPermission();
            return true;
        }
    }

    long scantimeouttime = 0L;

    boolean mScanning = false;
    // The mode the running scan was started with; settings cannot change under a running scan.
    volatile boolean scanLowLatency = false;

    /** Whether a sensor asks for a low-latency scan now; see SuperGattCallback.wantsLowLatencyScan. */
    private static boolean lowLatencyScanWanted() {
        // Walked without the list's lock, as start() and scanStarter already walk it: this runs under
        // scanStarter's lock, often for a driver holding its own monitor, so locking here (or
        // mygatts()) would order that monitor before the list's lock, and any path that takes a
        // driver's monitor under the list's lock would deadlock against it (the Clone paths and
        // updateDevicers used to; GattMonitorOrderTests now forbids it).
        // A concurrent add or remove can then throw mid-walk; the default mode is always a safe
        // answer, and the next start asks again.
        try {
            boolean wanted = false;
            for (SuperGattCallback cb : gattcallbacks) {
                // start() scans without any filter once one sensor has no service; at full duty that
                // would hand every advertisement nearby to the main thread, so it keeps the default.
                if (cb.getService() == null)
                    return false;
                if (cb.wantsLowLatencyScan())
                    wanted = true;
            }
            return wanted;
        } catch (RuntimeException e) {
            Log.stack(LOG_ID, "lowLatencyScanWanted", e);
            return false;
        }
    }

    class Scanner21 implements Scanner {
        final private ScanSettings mScanSettings;
        // Full duty, for the short waits a driver asks for through wantsLowLatencyScan().
        final private ScanSettings mLowLatencyScanSettings;
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
            mLowLatencyScanSettings = new ScanSettings.Builder()
                    .setReportDelay(0)
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
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

                // From the filters this start built, not from a second walk alone: a callback added
                // or removed between the two walks must not put an unfiltered scan at full duty.
                final boolean lowLatency = mScanFilters != null && lowLatencyScanWanted();
                if (doLog) {
                    Log.i(LOG_ID, "SCAN: calling startScan with "
                            + (mScanFilters == null ? "NO FILTERS" : mScanFilters.size() + " filters")
                            + " mode=" + (lowLatency ? "LOW_LATENCY" : "default"));
                }
                try {
                    this.mBluetoothLeScanner.startScan(mScanFilters,
                            lowLatency ? mLowLatencyScanSettings : mScanSettings, mScanCallback);
                } catch (Throwable e) {
                    Log.stack(LOG_ID, e);
                    if (Build.VERSION.SDK_INT > 30 && !Applic.mayscan())
                        Applic.missingScanPermission();
                    return false;
                }
                scanLowLatency = lowLatency;
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
        // Under scanStarter's lock, and only for a scan still running: scanStarter can stop a default
        // scan to restart it at low latency while this timeout is already running (cancel does not
        // stop a running task), and stopScan(true) here would then cancel that restart and leave no
        // scan at all. A scan already stopped is not this timeout's; whoever stopped it decides what
        // follows. checkdevice stops without this lock, so that race is only narrowed.
        synchronized (SensorBluetooth.this) {
            if (!mScanning)
                return;
            if (scanLowLatency) {
                // The low-latency cap, not a search that came up empty: start again at once and let
                // the sensors choose the mode anew, instead of the retry wait below.
                SensorBluetooth.this.stopScan(false);
                if (bluetoothIsEnabled())
                    SensorBluetooth.this.scanStarter(0L);
                return;
            }
            SensorBluetooth.this.stopScan(true);
        }
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
                        // Published with its timeout under scanStarter's lock: a restart there in
                        // between would cancel a timeout not yet stored, and the one stored after it
                        // would end some later scan early. Not the other order: stopScan cancels the
                        // timeout even while mScanning is still false, which would leave this scan
                        // with none. Results arriving while this waits for the lock are dropped
                        // (processScanResult, !mScanning); the next advertisement is heard.
                        synchronized (SensorBluetooth.this) {
                            mScanning = true;
                            final long timeout = scanLowLatency ? lowlatencyscantimeout : scantimeout;
                            if (scanOnUI) {
                                Applic.app.getHandler().postDelayed(mScanTimeoutRunnable, timeout);
                            } else {
                                timeoutFuture = Applic.scheduler.schedule(mScanTimeoutRunnable, timeout,
                                        TimeUnit.MILLISECONDS);
                            }
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
            // Without an Activity the permission cannot be asked for here; note
            // it so the next resume does, instead of only toasting forever.
            Applic.missingScanPermission();
            return true;
        }

        if (!bluetoothIsEnabled()) {
            Applic.Toaster(R.string.bluetooth_is_turned_off);
            return false;
        }
        if (mScanning || scanstart) {
            // A running scan keeps the settings it started with, so a sensor that has just begun
            // asking for a low-latency scan is only served by a restart: one of Android's five
            // startScan calls per 30 s, at once, because the caller's delay was never meant to stop
            // a running scan. Only up: going back down is left to the low-latency cap
            // (lowlatencyscantimeout), so a driver re-armed in a loop cannot spend a restart per
            // flip. A result the stopped scan already queued is dropped (processScanResult,
            // !mScanning); the sensor's next advertisement reaches the new scan.
            if (mScanning && !scanLowLatency && lowLatencyScanWanted()) {
                if (doLog) {
                    Log.i(LOG_ID, "scanStarter restarts scan at low latency");
                }
                stopScan(false);
                delayMillis = 0L;
            } else {
                if (doLog) {
                    Log.i(LOG_ID, "scanStarter skipped, scan already active/pending");
                }
                return false;
            }
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
            scanLowLatency = false;
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

    /**
     * True when another live AiDex callback already holds [address], including leftover
     * MAC-fallback rows. Broadcast-only name rebind must not attach a second manager to that radio.
     */
    public static boolean aidexLiveAddressOccupiedByOtherSerial(String serial, String address) {
        if (serial == null || address == null || address.isEmpty()) {
            return false;
        }
        synchronized (gattcallbacks) {
            for (SuperGattCallback cb : gattcallbacks) {
                if (cb == null || !(cb instanceof tk.glucodata.drivers.aidex.AiDexDriver)) {
                    continue;
                }
                if (!address.equalsIgnoreCase(cb.mActiveDeviceAddress)) {
                    continue;
                }
                if (SensorIdentity.matches(cb.SerialNumber, serial)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /** Exact id match: no alias or registry resolution, so ids of two drivers never conflate. */
    private static boolean containsExactly(List<String> ids, String want) {
        if (ids == null || want == null) {
            return false;
        }
        final String trimmed = want.trim();
        for (String id : ids) {
            if (id != null && id.trim().equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        return false;
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
            // A snapshot: re-homing runs outside the list's lock, where a concurrent add or remove
            // would throw out of a live walk.
            for (SuperGattCallback cb : mygatts()) {
                if (cb == null) continue;
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
                final boolean claimed;
                synchronized (gattcallbacks) {
                    claimed = gattcallbacks.remove(gatt);
                }
                if (claimed) {
                    gatt.free();
                }
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
        // Taken off the list under its lock, freed after it, as every other remover does: free()
        // takes the callback's monitor, and resetDevicer relies on a removed callback being off
        // the list before it is freed. Drained until the list stays empty, so a callback added
        // while a batch is being freed (a free can wait seconds for a driver's handler) is freed
        // too, as the old in-place loop did; bounded, in case something keeps adding.
        for (int pass = 0; pass < 8; pass++) {
            final ArrayList<SuperGattCallback> removed;
            synchronized (gattcallbacks) {
                if (gattcallbacks.isEmpty()) break;
                removed = new ArrayList<>(gattcallbacks);
                gattcallbacks.clear();
            }
            for (SuperGattCallback cb : removed) {
                try {
                    cb.free();
                } catch (Throwable t) {
                    Log.stack(LOG_ID, "removeDevices free", t);
                }
            }
        }
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
                if (isAiDexMacFallbackLeftover(Applic.app, name)) {
                    Log.i(LOG_ID, "setDevices: dropping leftover " + name);
                    dropAiDexLeftoverPersistAndNative(Applic.app, name);
                    continue;
                }
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
                    synchronized (gattcallbacks) {
                        gattcallbacks.add(callback);
                    }
                    adoptCurrentSensorIfBlank(name);
                }
                increasedwait = startincreasedwait;
            }
        }
        addPersistedManagedCallbacks();
        Natives.setmaxsensors(gattcallbacks.size());
    }

    /**
     * Materialize drivers for managed sensors that were persisted after Bluetooth already
     * started. {@link #addPersistedManagedCallbacks()} otherwise runs only from start()/
     * startDevices(), so a sensor that arrives mid-run — a watch receiving a handoff — has a
     * stored record and no driver: nothing connects, nothing falls back to broadcast, and the
     * claim just times out.
     */
    public static void ensurePersistedManagedCallbacks() {
        final SensorBluetooth one = blueone;
        if (one == null) {
            return;
        }
        try {
            one.addPersistedManagedCallbacks();
        } catch (Throwable th) {
            Log.stack(LOG_ID, "ensurePersistedManagedCallbacks", th);
        }
    }

    /**
     * Serializes a whole pass against another one. Not the instance monitor: the leftover drop
     * below frees native data and is deliberately kept outside it.
     */
    private static final Object persistedManagedLock = new Object();

    void addPersistedManagedCallbacks() {
        synchronized (persistedManagedLock) {
        final Context context = Applic.app;
        if (context == null) {
            return;
        }
        final java.util.ArrayList<String> leftovers = new java.util.ArrayList<>();
        synchronized (this) {
            for (String sensorId : ManagedSensorIdentityRegistry.INSTANCE.persistedSensorIds(context)) {
                if (isAiDexMacFallbackLeftover(context, sensorId)) {
                    leftovers.add(sensorId);
                }
            }
        }
        for (String leftover : leftovers) {
            Log.i(LOG_ID, "addPersistedManagedCallbacks: dropping leftover " + leftover);
            dropAiDexLeftoverPersistAndNative(context, leftover);
        }
        synchronized (this) {
        for (String sensorId : ManagedSensorIdentityRegistry.INSTANCE.persistedSensorIds(context)) {
            if (isAiDexMacFallbackLeftover(context, sensorId)) {
                continue;
            }
            if (findGattCallbackIndex(sensorId) >= 0) {
                continue;
            }
            final long dataptr = resolvePersistedManagedDataptr(sensorId);
            final SuperGattCallback cb = ManagedSensorIdentityRegistry.INSTANCE.createManagedCallback(context, sensorId, dataptr);
            if (cb == null) {
                continue;
            }
            if (findGattCallbackIndex(cb.SerialNumber) >= 0) {
                // The persisted id and the driver's own serial can spell the same sensor
                // differently, so this is reachable even though the id was checked above.
                // The duplicate shares the live callback's dataptr — freeing it would
                // release that sensor's native data out from under it.
                cb.discard();
                continue;
            }
            synchronized (gattcallbacks) {
                gattcallbacks.add(cb);
            }
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

    /** Wall-clock time of the last connection failure recorded for the sensor. */
    public static long connectionStatusChangedAt(SuperGattCallback gatt) {
        return gatt == null ? 0L : gatt.constatchange[1];
    }

    /** Keep mirrored sensor records visible without claiming their physical transmitter. */
    public static void blockLocalCloneConnection(String sensorId) {
        if (sensorId == null || sensorId.isEmpty()) return;
        // Matched under the list's lock, paused after it: setPause and closeGattTransport take the
        // callback's monitor, and code holding that monitor reaches mygatts(), so taking the two
        // locks in this order could deadlock (as removeDevice does, the list's lock is let go first).
        final ArrayList<SuperGattCallback> matched = new ArrayList<>();
        synchronized (gattcallbacks) {
            for (SuperGattCallback callback : gattcallbacks) {
                if (SensorIdentity.matches(callback.SerialNumber, sensorId)) {
                    matched.add(callback);
                }
            }
        }
        for (SuperGattCallback callback : matched) {
            callback.setPause(true);
            callback.closeGattTransport();
        }
    }

    public static void retireCloneSensor(String sensorId) {
        if (sensorId == null || sensorId.isEmpty()) return;
        // Removed under the list's lock, freed after it, as removeDevice does: free() takes the
        // callback's monitor (see blockLocalCloneConnection).
        final ArrayList<SuperGattCallback> retired = new ArrayList<>();
        synchronized (gattcallbacks) {
            for (int index = gattcallbacks.size() - 1; index >= 0; index--) {
                SuperGattCallback callback = gattcallbacks.get(index);
                if (SensorIdentity.matches(callback.SerialNumber, sensorId)) {
                    retired.add(callback);
                    gattcallbacks.remove(index);
                }
            }
        }
        for (SuperGattCallback callback : retired) {
            callback.free();
        }
        Natives.setmaxsensors(gattcallbacks.size());
        final String current = SensorIdentity.resolveMainSensor();
        if (SensorIdentity.matches(current, sensorId)) {
            final String replacement = resolveReplacementSensorSerial(sensorId);
            setCurrentSensorSelection(replacement != null ? replacement : "");
        }
    }

    // --- KOTLIN SENSORS (AiDex) SUPPORT ---
    public static boolean addAiDexSensor(Context context, String name, String address) {
        if (context == null || name == null || name.trim().isEmpty() || address == null || address.trim().isEmpty()) {
            Log.w(LOG_ID, "addAiDexSensor: invalid input name/address");
            return false;
        }
        if (tk.glucodata.drivers.aidex.AiDexScanIdentity.INSTANCE.isMacFallbackSerial(name, address)) {
            Log.w(LOG_ID, "addAiDexSensor: refusing MAC-fallback serial " + name);
            return false;
        }
        if (blueone == null) {
            Log.w(LOG_ID, "addAiDexSensor: bluetooth not started — not persisting " + name);
            return false;
        }
        try {
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

            String serial = name.trim();
            SuperGattCallback existing = null;
            java.util.ArrayList<SuperGattCallback> leftoverOwners = new java.util.ArrayList<>();
            SuperGattCallback realAddressOwner = null;
            if (blueone != null) {
                synchronized (blueone.gattcallbacks) {
                    for (SuperGattCallback cb : blueone.gattcallbacks) {
                        if (cb.SerialNumber != null && cb.SerialNumber.equals(serial)) {
                            existing = cb;
                        } else if (cb instanceof tk.glucodata.drivers.aidex.AiDexDriver
                                && address.equalsIgnoreCase(cb.mActiveDeviceAddress)) {
                            // Row first, like every shared call site: a live address alone can be
                            // moved by a spoofed advert and must not make a real sensor a leftover.
                            if (isAiDexMacFallbackLeftover(context, cb.SerialNumber)) {
                                leftoverOwners.add(cb);
                            } else {
                                realAddressOwner = cb;
                            }
                        }
                    }
                }
            }
            if (realAddressOwner != null) {
                Log.w(LOG_ID, "addAiDexSensor: address " + address
                        + " already bound to " + realAddressOwner.SerialNumber);
                teardownLeftoverAiDexOwners(context, leftoverOwners);
                return false;
            }
            for (String entry : sensors) {
                tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.PersistedEntry parsed =
                        tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE
                                .parsePersistedEntry(entry);
                if (tk.glucodata.drivers.aidex.AiDexScanIdentity.INSTANCE
                        .persistAddressOccupiedByOtherRealSerial(
                                parsed.getSerial(), parsed.getAddress(), serial, address)) {
                    Log.w(LOG_ID, "addAiDexSensor: address " + address
                            + " already persisted to " + parsed.getSerial());
                    teardownLeftoverAiDexOwners(context, leftoverOwners);
                    return false;
                }
            }
            if (tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE.aliasBelongsToOtherDriver(
                    serial,
                    ManagedSensorIdentityRegistry.INSTANCE.getAll(),
                    adapter -> adapter.persistedSensorIds(context))) {
                Log.w(LOG_ID, "addAiDexSensor: " + serial + " would take over another driver's sensor");
                teardownLeftoverAiDexOwners(context, leftoverOwners);
                return false;
            }
            String persistedAddress = tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE
                    .persistedAddress(context, serial);
            if (persistedAddress != null && !persistedAddress.equalsIgnoreCase(address)) {
                Log.w(LOG_ID, "addAiDexSensor: refusing to retarget existing " + serial
                        + " from " + persistedAddress + " to " + address);
                return false;
            }
            boolean delayConnectAfterLeftover = !leftoverOwners.isEmpty();
            if (delayConnectAfterLeftover) {
                for (SuperGattCallback leftover : leftoverOwners) {
                    Log.i(LOG_ID, "addAiDexSensor: retargeting leftover MAC serial "
                            + leftover.SerialNumber + " -> " + serial);
                }
                teardownLeftoverAiDexOwners(context, leftoverOwners);
            }

            java.util.Set<String> newSensors = new java.util.HashSet<>();
            for (String entry : sensors) {
                tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.PersistedEntry parsed =
                        tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE
                                .parsePersistedEntry(entry);
                if (tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE
                        .matchesCallbackId(parsed.getSerial(), serial)) {
                    continue;
                }
                boolean leftoverTornDown = false;
                for (SuperGattCallback leftover : leftoverOwners) {
                    if (tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE
                            .matchesCallbackId(parsed.getSerial(), leftover.SerialNumber)) {
                        leftoverTornDown = true;
                        break;
                    }
                }
                if (leftoverTornDown) {
                    dropAiDexLeftoverPersistAndNative(context, parsed.getSerial());
                    continue;
                }
                if (tk.glucodata.drivers.aidex.AiDexScanIdentity.INSTANCE
                        .isMacFallbackSerial(parsed.getSerial(), parsed.getAddress())
                        && address.equalsIgnoreCase(parsed.getAddress())) {
                    dropAiDexLeftoverPersistAndNative(context, parsed.getSerial());
                    continue;
                }
                newSensors.add(entry);
            }
            newSensors.add(serial + "|" + address);
            prefs.edit().putStringSet("aidex_sensors", newSensors).commit();
            SensorIdentity.invalidateCaches();

            if (blueone != null) {
                if (existing != null) {
                    boolean applied = tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE
                            .applyPersistedBleAddress(existing, address);
                    if (applied) {
                        existing.connectDevice(delayConnectAfterLeftover ? 250 : 0);
                    } else {
                        Log.w(LOG_ID, "addAiDexSensor: could not resolve " + address + " for existing " + serial);
                    }
                    return true;
                }
                long dataptr = Natives.getdataptr(name);
                if (dataptr != 0L) {
                    Natives.unfinishSensor(dataptr);
                }
                SuperGattCallback cb = tk.glucodata.drivers.aidex.AiDexNativeFactory.createBleManager(name, dataptr);
                boolean applied = tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE
                        .applyPersistedBleAddress(cb, address);
                synchronized (blueone.gattcallbacks) {
                    blueone.gattcallbacks.add(cb);
                }
                blueone.adoptCurrentSensorIfBlank(serial);
                if (applied) {
                    cb.connectDevice(delayConnectAfterLeftover ? 250 : 0);
                } else {
                    Log.w(LOG_ID, "addAiDexSensor: persisted " + serial
                            + " without a resolvable device for " + address);
                }
            }
            return true;
        } catch (Throwable t) {
            Log.stack(LOG_ID, "addAiDexSensor", t);
            return false;
        }
    }

    public static boolean isExistingAiDexSetup(Context context, String serial) {
        if (serial == null || serial.trim().isEmpty()) {
            return false;
        }
        String want = serial.trim();
        if (blueone != null) {
            synchronized (blueone.gattcallbacks) {
                for (SuperGattCallback cb : blueone.gattcallbacks) {
                    if (cb instanceof tk.glucodata.drivers.aidex.AiDexDriver
                            && SensorIdentity.matches(cb.SerialNumber, want)) {
                        return true;
                    }
                }
            }
        }
        if (context != null) {
            for (String persisted : tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE
                    .persistedSensorIds(context)) {
                if (SensorIdentity.matches(persisted, want)
                        || tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE
                        .matchesCallbackId(persisted, want)) {
                    return true;
                }
            }
        }
        return isNativeActiveSensor(want);
    }

    private static boolean isNativeActiveSensor(String serial) {
        if (serial == null || serial.trim().isEmpty()) {
            return false;
        }
        try {
            String[] active = Natives.activeSensors();
            if (active == null) {
                return false;
            }
            for (String sensorId : active) {
                if (SensorIdentity.matches(sensorId, serial)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void teardownLeftoverAiDexOwners(Context context,
            java.util.List<SuperGattCallback> leftovers) {
        if (leftovers == null || leftovers.isEmpty()) {
            return;
        }
        for (SuperGattCallback leftover : leftovers) {
            teardownLeftoverAiDex(context, leftover);
            if (blueone != null) {
                synchronized (blueone.gattcallbacks) {
                    blueone.gattcallbacks.remove(leftover);
                }
            }
        }
    }

    // Never removes the Android bond: a sensor torn down here may still hold its side of it, and
    // removing ours strands it with keys no phone has. The user's own Forget/Unpair does that.
    private static void teardownLeftoverAiDex(Context context, SuperGattCallback leftover) {
        if (leftover == null) {
            return;
        }
        try {
            if (leftover instanceof tk.glucodata.drivers.aidex.AiDexDriver) {
                ((tk.glucodata.drivers.aidex.AiDexDriver) leftover).forgetVendor(false);
            } else {
                leftover.finishSensor();
                leftover.close();
            }
        } catch (Throwable t) {
            Log.stack(LOG_ID, "teardownLeftoverAiDex forgetVendor", t);
        }
        tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE
                .removePersistedSensor(context, leftover.SerialNumber);
    }

    private static void dropAiDexLeftoverPersistAndNative(Context context, String leftoverSerial) {
        dropAiDexLeftoverPersistAndNative(context, leftoverSerial, true);
    }

    private static void dropAiDexLeftoverPersistAndNative(
            Context context, String leftoverSerial, boolean freeMatchingLiveCallback) {
        if (leftoverSerial == null || leftoverSerial.trim().isEmpty()) {
            return;
        }
        try {
            if (isNativeActiveSensor(leftoverSerial)) {
                long dataptr = Natives.getdataptr(leftoverSerial);
                if (dataptr != 0L) {
                    try {
                        Natives.finishSensor(dataptr);
                    } finally {
                        Natives.freedataptr(dataptr);
                    }
                }
            }
        } catch (Throwable t) {
            Log.stack(LOG_ID, "dropAiDexLeftoverPersistAndNative finishSensor", t);
        }
        tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE
                .removePersistedSensor(context, leftoverSerial);
        final SensorBluetooth one = blueone;
        if (!freeMatchingLiveCallback || one == null) {
            return;
        }
        SuperGattCallback live = null;
        synchronized (gattcallbacks) {
            for (SuperGattCallback cb : gattcallbacks) {
                if (cb instanceof tk.glucodata.drivers.aidex.AiDexDriver
                        && SensorIdentity.matches(cb.SerialNumber, leftoverSerial)) {
                    live = cb;
                    break;
                }
            }
            // Claim it under the lock so a concurrent drop cannot free the same callback twice.
            if (live != null) {
                gattcallbacks.remove(live);
            }
        }
        if (live != null) {
            try {
                live.free();
            } catch (Throwable t) {
                Log.stack(LOG_ID, "dropAiDexLeftoverPersistAndNative free", t);
            }
            one.rehomeCurrentSensorAfterRemoval(leftoverSerial);
        }
    }

    /**
     * An AiDex id left over from the old MAC-as-serial fallback: its persisted row, or the live
     * AiDex callback carrying it, has a serial that spells its own BLE address. The id shape alone
     * decides nothing — other drivers' bare 12-hex MACs (Ottai, Anytime, MQ) and real AiDex
     * `<letter>-<12 hex>` serials must never be dropped as leftovers.
     */
    private static boolean isAiDexMacFallbackLeftover(Context context, String sensorId) {
        if (sensorId == null || sensorId.trim().isEmpty()) {
            return false;
        }
        // The stored row is the sensor's own record, so it outranks a live address that a rebind
        // may have rewritten; the live callback is asked only when the row proves nothing.
        final Boolean persisted = context == null ? null
                : tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE
                        .persistedLeftoverVerdict(context, sensorId);
        final String want = sensorId.trim();
        return tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE.leftoverVerdict(persisted, () -> {
            synchronized (gattcallbacks) {
                for (SuperGattCallback cb : gattcallbacks) {
                    if (cb instanceof tk.glucodata.drivers.aidex.AiDexDriver
                            && want.equalsIgnoreCase(cb.SerialNumber)
                            && tk.glucodata.drivers.aidex.AiDexScanIdentity.INSTANCE
                                    .isMacFallbackSerial(cb.SerialNumber, cb.mActiveDeviceAddress)) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    public static void rollbackUnpairedAiDexSensor(Context context, String serial) {
        if (context == null || serial == null || serial.trim().isEmpty()) {
            return;
        }
        try {
            if (blueone == null) {
                Log.i(LOG_ID, "rollbackUnpairedAiDexSensor: blueone unset — keeping persist for "
                        + serial);
                return;
            }
            SuperGattCallback victim = null;
            boolean paired = false;
            synchronized (blueone.gattcallbacks) {
                for (SuperGattCallback cb : blueone.gattcallbacks) {
                    if (!(cb instanceof tk.glucodata.drivers.aidex.AiDexDriver)) {
                        continue;
                    }
                    if (!SensorIdentity.matches(cb.SerialNumber, serial)) {
                        continue;
                    }
                    tk.glucodata.drivers.aidex.AiDexDriver driver = (tk.glucodata.drivers.aidex.AiDexDriver) cb;
                    if (!tk.glucodata.drivers.aidex.AiDexSetupPolicy.mayRollBack(
                            driver.isVendorPaired(), driver.hasCompletedHandshake())) {
                        paired = true;
                        break;
                    }
                    victim = cb;
                    break;
                }
                if (victim != null) {
                    blueone.gattcallbacks.remove(victim);
                }
            }
            if (paired) {
                Log.i(LOG_ID, "rollbackUnpairedAiDexSensor: keeping paired " + serial);
                return;
            }
            if (victim != null) {
                // The Android bond stays. A setup abandoned after SMP finished leaves the sensor
                // bonded on its own side; removing ours strands it with keys no phone holds. Kept,
                // the next setup of this sensor pairs over the existing bond, with no new prompt.
                teardownLeftoverAiDex(context, victim);
            }
            tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE
                    .removePersistedSensor(context, serial);
        } catch (Throwable t) {
            Log.stack(LOG_ID, "rollbackUnpairedAiDexSensor", t);
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
        // Clone disable can retire managed callbacks from the UI thread while a
        // native sync asks us to rebuild this same roster. Take the native and
        // managed snapshots under the same monitor as comparison and mutation;
        // otherwise a pre-disable snapshot can re-add a callback after retirement.
        // Removed callbacks are only claimed here and freed after the lock: free() takes the
        // callback's monitor, and code holding that monitor reaches mygatts() (a connect checking
        // CloneSensorRegistry), so freeing under this lock could deadlock.
        final ArrayList<SuperGattCallback> claimedVictims = new ArrayList<>();
        final ArrayList<String> removedSerials = new ArrayList<>();
        final ArrayList<String> removedLeftovers = new ArrayList<>();
        final ArrayList<String> lateLeftovers = new ArrayList<>();
        ArrayList<String> allDevs = null;
        try {
        synchronized (gattcallbacks) {
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
            allDevs = distinctRuntimeSensorIds(candidateDevs);

            String[] devs = allDevs.toArray(new String[0]);
            // Snapshot: another thread may add or remove callbacks while this pass runs, and an index
            // captured here would then free the wrong sensor.
            final ArrayList<SuperGattCallback> live = mygatts();
            ArrayList<SuperGattCallback> rem = new ArrayList<>();
            final ArrayList<String> droppedLeftovers = new ArrayList<>();
            final ArrayList<SuperGattCallback> leftoverVictims = new ArrayList<>();
            int gatnr = live.size();
            if (devs == null) {
                rem.addAll(live);
                if (rem.size() == 0) {
                    return false;
                }
            } else {

                int heb = 0;

                for (int i = 0; i < gatnr; i++) {
                    var gatt = live.get(i);
                    String was = gatt.SerialNumber;
                    if (isAiDexMacFallbackLeftover(Applic.app, was)) {
                        // Decided once, here: a concurrent drop can erase the row and the callback before
                        // the removal loop reaches this victim, and a second look would then miss it.
                        rem.add(gatt);
                        leftoverVictims.add(gatt);
                        if (was != null) {
                            droppedLeftovers.add(was);
                        }
                        continue;
                    }
                    int matched = consumeMatchingDeviceIds(devs, gatt);
                    if (matched == 0) {
                        rem.add(gatt);
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

            for (int el = rem.size() - 1; el >= 0; el--) {
                final SuperGattCallback victim = rem.get(el);
                final String removedSerial = victim.SerialNumber;
                final boolean leftover = leftoverVictims.contains(victim);
                {
                    if (doLog) {
                        Log.i(LOG_ID, "remove " + removedSerial);
                    }
                    ;
                }
                ;
                // Claim it before freeing: a concurrent remover must not free the same callback twice.
                final boolean claimed;
                synchronized (gattcallbacks) {
                    claimed = gattcallbacks.remove(victim);
                }
                if (claimed) {
                    claimedVictims.add(victim);
                }
                if (leftover) {
                    removedLeftovers.add(removedSerial);
                }
                removedSerials.add(removedSerial);
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
                        if (containsExactly(droppedLeftovers, dev)) {
                            continue;
                        }
                        if (isAiDexMacFallbackLeftover(Applic.app, dev)) {
                            Log.i(LOG_ID, "updateDevicers: dropping leftover " + dev);
                            lateLeftovers.add(dev);
                            continue;
                        }
                        final boolean persistedManaged = hasPersistedManagedRecord(dev);
                        if (!persistedManaged && !isNativeActiveSensor(dev)) {
                            // Gone since this pass listed it: another thread dropped or removed it.
                            continue;
                        }
                        final boolean suppressGenericManagedShell = shouldSuppressGenericManagedShell(dev);
                        final long managedDataptr =
                            (persistedManaged || suppressGenericManagedShell) ? resolvePersistedManagedDataptr(dev) : 0L;
                        if (persistedManaged || suppressGenericManagedShell) {
                            final SuperGattCallback managed = ManagedSensorIdentityRegistry.INSTANCE.createManagedCallback(Applic.app, dev, managedDataptr);
                            if (managed != null) {
                                synchronized (gattcallbacks) {
                                    gattcallbacks.add(managed);
                                }
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
                            final SuperGattCallback generic = getGattCallback(dev, dataptr);
                            synchronized (gattcallbacks) {
                                gattcallbacks.add(generic);
                            }
                            adoptCurrentSensorIfBlank(dev);
                            increasedwait = startincreasedwait;
                            index++;
                        }
                    }
                }
            }
        }
        } finally {
            // Out of the list's lock, in the old order: free, drop the leftover's rows, re-home.
            // In a finally, so a throw in the add loop cannot strand callbacks already removed.
            for (SuperGattCallback victim : claimedVictims) {
                try {
                    victim.free();
                } catch (Throwable t) {
                    Log.stack(LOG_ID, "updateDevicers free", t);
                }
            }
            // Each step guarded on its own: one failure must neither hide the add loop's exception
            // nor skip the steps after it.
            for (String serial : removedLeftovers) {
                try {
                    dropAiDexLeftoverPersistAndNative(Applic.app, serial, false);
                } catch (Throwable t) {
                    Log.stack(LOG_ID, "updateDevicers drop removed " + serial, t);
                }
            }
            for (String serial : removedSerials) {
                try {
                    rehomeCurrentSensorAfterRemoval(serial, allDevs);
                } catch (Throwable t) {
                    Log.stack(LOG_ID, "updateDevicers rehome " + serial, t);
                }
            }
            for (String dev : lateLeftovers) {
                try {
                    dropAiDexLeftoverPersistAndNative(Applic.app, dev);
                } catch (Throwable t) {
                    Log.stack(LOG_ID, "updateDevicers drop late " + dev, t);
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

    /**
     * A sensor whose readings are arriving over Clone belongs to the sending
     * device. Dialling it here puts two phones on one transmitter, and the
     * loser of that race is whichever one was actually wearing it. The claim
     * lasts only while the mirror keeps delivering, so unplugging the sender
     * still lets this device pick the sensor up, and turning Clone off hands
     * it back immediately. Every path that dials a callback asks this, not only
     * the periodic check: the restore-all sweep went straight to connectDevice
     * and put a mirrored Sibionics into a Libre scan on the receiving phone.
     */
    private static boolean mirroredOverClone(SuperGattCallback cb, String where) {
        if (CloneSensorRegistry.isReceptionEnabled()
                && CloneSensorRegistry.isMirrorDelivering(cb.SerialNumber)) {
            if (doLog) {
                Log.i(LOG_ID, where + " skipped: mirrored over Clone " + cb.SerialNumber);
            }
            return true;
        }
        return false;
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
        if (mirroredOverClone(cb, "checkandconnect")) {
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
            synchronized (gattcallbacks) {
                gattcallbacks.add(cb);
            }
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

    private static boolean isListed(SuperGattCallback callback) {
        // An array copy, not an iterator: some registries add to the list without its lock.
        synchronized (gattcallbacks) {
            for (Object cb : gattcallbacks.toArray()) {
                if (cb == callback) return true;
            }
            return false;
        }
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
                cb.sensorstartmsec = Natives.getSensorStartmsec(cb.dataptr);
                cb.setPause(false);
                // Found without the list's lock: a concurrent remover takes cb off the list before
                // it frees it, and free() sets stop only once. If that happened, the unpause above
                // must not outlive the free, or a callback no stop path can reach would reconnect.
                if (!isListed(cb)) {
                    cb.setPause(true);
                }
                // NFC created a separate stream wrapper for the same native record.
                Natives.freedataptr(streamptr);
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
                // legacy adapter API: required at minSdk 26
                @SuppressWarnings("deprecation")
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
                // legacy Intent extra API: required at minSdk 26
                @SuppressWarnings("deprecation")
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
            Applic.missingScanPermission();
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
