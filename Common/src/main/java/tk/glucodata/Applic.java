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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.graphics.Color.BLACK;
import static android.graphics.Color.RED;
import static android.graphics.Color.BLUE;
import static android.graphics.Color.WHITE;
import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE;
import static android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS;
import static android.view.View.INVISIBLE;
//import java.text.DateFormat;
import static java.lang.String.format;
import static java.util.Locale.US;
import static tk.glucodata.GlucoseCurve.STEPBACK;
import static tk.glucodata.GlucoseCurve.smallfontsize;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.MessageSender.initwearos;
import static tk.glucodata.Natives.hasData;
import static tk.glucodata.SuperGattCallback.endtalk;
import static tk.glucodata.util.getlocale;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import tk.glucodata.nums.AllData;
import tk.glucodata.nums.numio;
import tk.glucodata.settings.Broadcasts;
import java.util.concurrent.Executors;
//import static tk.glucodata.MessageSender.messagesender;

public class Applic extends Application implements androidx.work.Configuration.Provider {
    static final boolean ALLGALAXY = true;
    static final boolean hasNotChinese = true;
    public static final boolean scrollbar = true;
    public static final boolean horiScrollbar = true;
    // static final float mgdLmult= doLog?18.0182f:18.0f;
    // static final float mgdLmult= BuildConfig.DEBUG?18.0182f:18.0f;
    static final float mgdLmult = 18.0f;
    // public static tk.glucodata.MessageSender messagesender=null;
    static boolean Nativesloaded = false;
    public static boolean hour24 = true;
    static public final int TargetSDK = BuildConfig.targetSDK;
    static public final boolean isWearable = BuildConfig.isWear == 1;
    // static final boolean DontTalk=isWearable;
    static public final boolean DontTalk = false;
    static public final boolean useZXing = BuildConfig.noZXing == 0;
    // static public final boolean includeLib= isWearable;
    static public final boolean includeLib = true;
    static public final boolean isRelease = BuildConfig.isRelease == 1;
    // static public final boolean isRelease= !BuildConfig.DEBUG;
    static final String JUGGLUCOIDENT = isWearable ? "juggluco" : "jugglucowatch";
    public static final Locale usedlocale = US;
    static boolean setremoveviews = false;
    // final public static boolean usemeal=Natives.usemeal();
    // boolean usebluetooth=true;
    final private static String LOG_ID = "Applic";

    // ArrayList<String> labels = null;
    public ArrayList<String> getlabels() {
        return Natives.getLabels();
    }

    public tk.glucodata.nums.AllData numdata = null;
    public GlucoseCurve curve = null;
    public static int unit = 0;

    public void redraw() {
        var tmpcurve = curve;
        if (tmpcurve != null)
            tmpcurve.requestRender();
    }

    static private Handler mHandler;
    private static long uiThreadId;

    public static Handler getHandler() {
        return mHandler;
    }

    static public MainActivity getActivity() {
        return MainActivity.thisone;
    }

    static public Context getContext() {
        Context cont = getActivity();
        if (cont != null)
            return cont;
        return app;
    }

    static public void Toaster(String mess) {
        RunOnUiThread(() -> {
            Applic.argToaster(app, mess, Toast.LENGTH_SHORT);
        });
    }

    static public void Toaster(int res) {
        RunOnUiThread(() -> {
            Applic.argToaster(app, res, Toast.LENGTH_SHORT);
        });
    }

    static public void argToaster(Context context, int res, int duration) {
        argToaster(context, context.getString(res), duration);
    }

    static public void argToaster(Context context, String message, int duration) {
        Toast.makeText(context, message, duration).show();
        if (!DontTalk) {
            if (initproccalled && Natives.speakmessages())
                speak(message);
        }
    }

    static public void RunOnUiThread(Runnable action) {
        if (Thread.currentThread().getId() != uiThreadId) {
            mHandler.post(action);
        } else {
            action.run();
        }
    }

    static public void postDelayed(Runnable action, long mmsecs) {
        Applic.getHandler().postDelayed(action, mmsecs);
    }

    /*
     * static public void RunOnUiThread(Runnable action) {
     * app.RunOnUiThreader(action);
     * }
     */
    public static Applic app;
    private final IntentFilter mintimefilter;

    @MainThread
    public Applic() {
        super();
        app = this;
        android.util.Log.i(LOG_ID, "start tk.glucodata");
        mintimefilter = new IntentFilter();
        mintimefilter.addAction(Intent.ACTION_TIME_TICK);
        mHandler = new Handler(Looper.getMainLooper());
        uiThreadId = Thread.currentThread().getId();
        if (!isWearable) {
            numdata = new AllData();
        }
    }



    void setnotify(boolean on) {
        Notify.alertwatch = on;
        {
            if (doLog) {
                Log.i(LOG_ID, "setnotify=" + on);
            }
            ;
        }
        ;
    }

    public static boolean isWatchNotifyEnabled() {
        return Notify.alertwatch;
    }

    public static void setWatchNotifyEnabled(boolean on) {
        if (app != null) {
            app.setnotify(on);
        } else {
            Notify.alertwatch = on;
        }
    }

    public void setunit(int unit) {
        if (Applic.unit != unit)
            SuperGattCallback.previousglucosevalue = 0.0f;
        Natives.setunit(unit);
        Notify.mkunitstr(app, unit);
    }

    public void sendlabels() {
        if (!isWearable) {
            if (Natives.gethasgarmin())
                numdata.sendlabels();
        }
    }

    void setcurve(GlucoseCurve curve) {
        this.curve = curve;
        if (setremoveviews)
            curve.removeviews();
    }

    // void savestate() { if(blue!=null) blue.savestate(); }
    public static String curlang = null;

    private static boolean hasSystemtimeformat = true;

    public static boolean systemtimeformat() {
        // return DateFormat.is24HourFormat(app)==hour24;
        return hasSystemtimeformat;
    }
    // private static boolean was24=true;

    private static void setlanguage() {
        var lang = getlocale().getLanguage();
        {
            if (doLog) {
                Log.i(LOG_ID, "Applic.setlangauge=" + lang + " cur=" + curlang);
            }
            ;
        }
        ;
        if (!lang.equals(curlang)) {
            curlang = lang;
            if (!DontTalk) {
                if (Talker.istalking())
                    SuperGattCallback.newtalker(null);
            }
        } else {
            return;
        }
        Natives.setlocale(lang);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (Nativesloaded) {
            needsnatives();

            // var new24 = DateFormat.is24HourFormat(this);
            hasSystemtimeformat = DateFormat.is24HourFormat(app) == hour24;
            Notify.timef = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT);
            {
                if (doLog) {
                    Log.i(LOG_ID, "Applic.onConfigurationChanged " + (Applic.hour24 ? " 24uur" : " 12uur"));
                }
                ;
            }
            ;
            // was24=new24;
            setlanguage();
        }
    }

    static private void setjavahour24(boolean val) {
        Applic.hour24 = val;
        Notify.mkDateformat();
        hasSystemtimeformat = DateFormat.is24HourFormat(app) == hour24;
        if (!isWearable) {
            var main = MainActivity.thisone;
            if (main != null) {
                var tmpcurve = main.curve;
                if (tmpcurve != null) {
                    if (tmpcurve.search != null)
                        tmpcurve.clearsearch(null);
                }
            }
        }
    }

    static public void sethour24(boolean val) {
        Natives.sethour24(val);
        setjavahour24(val);
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        if (!isWearable) {
            if (numdata != null) {
                numdata.onCleared();
            }
        }
    }

    static final String[] foregroundscanpermissions = ((Build.VERSION.SDK_INT >= 31)
            ? (new String[] { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT })
            : new String[] { Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION });

    static final String[] scanpermissions = ((Build.VERSION.SDK_INT >= 31)
            ? foregroundscanpermissions
            : ((Build.VERSION.SDK_INT >= 29)
                    ? new String[] { Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION }
                    : foregroundscanpermissions));

    static String[] hasPermissions(Context context, String[] perms) {
        var over = new ArrayList<String>();
        {
            if (doLog) {
                Log.i(LOG_ID, "hasPermissions: ");
            }
            ;
        }
        ;
        for (var p : perms) {
            if (ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED) {
                over.add(p);
                Log.i(LOG_ID, p + " not granted");
            } else {
                Log.i(LOG_ID, p + " granted");
            }
        }
        var uit = new String[over.size()];
        {
            if (doLog) {
                Log.i(LOG_ID, "no perm=" + over.size());
            }
            ;
        }
        ;
        return over.toArray(uit);
    }

    static String[] noPermissions(Context context) {
        String[] noperms = hasPermissions(context, scanpermissions);
        if (doLog) {
            {
                if (doLog) {
                    Log.i(LOG_ID, "nopermissions:");
                }
                ;
            }
            ;
            for (var el : noperms) {
                {
                    if (doLog) {
                        Log.i(LOG_ID, el);
                    }
                    ;
                }
                ;
            }
        }
        return noperms;
    }

    static boolean mayscan() {
        if (Build.VERSION.SDK_INT >= 23) {
            return Applic.hasPermissions(app, scanpermissions).length == 0;
        }
        return true;
    }

    static boolean hasForegroundScanPermissions(Context context) {
        return hasPermissions(context, foregroundscanpermissions).length == 0;
    }

    static boolean needsBackgroundScanPermission() {
        return Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT < 31;
    }

    static boolean hasBackgroundScanPermission(Context context) {
        if (!needsBackgroundScanPermission()) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * On the watch Bluetooth is nearly always switched on by something that has
     * no Activity: the phone's /bluetooth message, a sensor handoff, or
     * WearSensorClaim re-arming direct mode while the process starts. Those
     * paths cannot show the runtime dialog, so the scan only ever failed with a
     * toast and the permission had to be granted by hand in the system app
     * settings. Remember that it is still needed, and ask as soon as
     * MainActivity is up again.
     */
    static volatile boolean scanPermissionPending = false;

    private static long scanPermissionToastMillis = 0L;
    private static final long SCANPERMISSION_TOAST_INTERVAL = 60000L;

    /**
     * Asks for the scan (NEARBY DEVICES / location) permission when an Activity
     * is available, and otherwise marks it pending for the next resume.
     *
     * @return true when the permission is already there or the dialog could be
     *         shown.
     */
    static boolean requestScanPermission() {
        if (Build.VERSION.SDK_INT < 23 || mayscan()) {
            scanPermissionPending = false;
            return true;
        }
        var main = MainActivity.thisone;
        if (main == null) {
            scanPermissionPending = true;
            return false;
        }
        return main.finepermission();
    }

    /**
     * A scan needed the permission and did not have it: ask for it if we can,
     * and keep the toast from repeating on every retry.
     */
    static void missingScanPermission() {
        requestScanPermission();
        final long now = android.os.SystemClock.elapsedRealtime();
        if (scanPermissionToastMillis != 0L && now - scanPermissionToastMillis < SCANPERMISSION_TOAST_INTERVAL) {
            return;
        }
        scanPermissionToastMillis = now;
        Toaster((Build.VERSION.SDK_INT > 30) ? R.string.turn_on_nearby_devices_permission
                : R.string.turn_on_location_permission);
    }

    static boolean canBluetooth() {
        if (Build.VERSION.SDK_INT > 30) {
            return Applic.hasPermissions(app, scanpermissions).length == 0;
        }
        return true;
    }

    @Keep
    static int bluePermission() {
        if (Build.VERSION.SDK_INT > 23) {
            if (Applic.hasPermissions(app, scanpermissions).length == 0)
                return 2;
            if (Build.VERSION.SDK_INT > 30) {
                return 0;
            }
            return 1;
        }
        return 2;
    }

    public static void updateservice(Context context, boolean usebluetooth) {
        final int backupHosts = Natives.activeBackupHostNr();
        final boolean keepAliveRequired = usebluetooth || backupHosts > 0;
        if (doLog) {
            Log.i(LOG_ID, "updateservice decision bluetooth=" + usebluetooth
                    + " backupHosts=" + backupHosts
                    + " nightscoutEnabled=" + Natives.getuseuploader()
                    + " keepAlive=" + keepAliveRequired);
        }
        if (keepAliveRequired) {
            if (keeprunning.start(context)) {
                if (doLog) {
                    Log.i(LOG_ID, "updateservice: keeprunning started");
                }
                ;
            } else {
                if (doLog) {
                    Log.i(LOG_ID, "updateservice keeprunning not started=" + keeprunning.started);
                }
                ;
            }
            ;

        } else
            keeprunning.stop();
    }

    private static void dontusebluetooth() {
        {
            if (doLog) {
                Log.i(LOG_ID, "dontusebluetooth()");
            }
            ;
        }
        ;
        SensorBluetooth.destructor();
        if (Natives.activeBackupHostNr() <= 0) {
            keeprunning.stop();
        }
    }

    boolean usingbluetooth = false;

    boolean canusebluetooth() {
        return usingbluetooth && Natives.getusebluetooth();
    }

    static void explicit(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                final PowerManager pm = (PowerManager) context.getSystemService(Activity.POWER_SERVICE);
                var name = context.getPackageName();
                if (pm.isIgnoringBatteryOptimizations(name))
                    return;
                Intent intent = new Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + name));
                context.startActivity(intent);
            } catch (Throwable e) {
                Log.stack(LOG_ID, "explicit", e);
            }
        }
    }

    void initbluetooth(boolean usebluetooth, Context context, boolean frommain) {
        usingbluetooth = usebluetooth;
        if (doLog) {
            Log.i(LOG_ID, "initbluetooth " + usebluetooth);
        }
        ;
        if (!isWearable) {
            if (Natives.getusegarmin() && numdata.devices == null)
                numdata.initIQ(context);
        }

        if (!keeprunning.started) {
            if (usebluetooth || Natives.activeBackupHostNr() > 0) {
                if (keeprunning.start(context)) {
                    if (doLog) {
                        Log.i(LOG_ID, "keeprunning started");
                    }
                    ;
                } else {
                    if (doLog) {
                        Log.i(LOG_ID, "keeprunning not started=" + keeprunning.started);
                    }
                    ;
                }
                ;
                if (frommain)
                    ((MainActivity) context).askNotify();
            }
        }
        SensorBluetooth.start(usebluetooth);

        if (!isWearable) {
            BluetoothGlucoseMeter.startDevices();
        }
    }

    static boolean possiblybluetooth(Context context) {
        {
            if (doLog) {
                Log.i(LOG_ID, "possiblybluetooth");
            }
            ;
        }
        ;
        // boolean useblue=Natives.getusebluetooth()&& hasPermissions(context,
        // Applic.scanpermissions).length==0;
        final boolean useblue = Natives.getusebluetooth();
        ((Applic) context.getApplicationContext()).initbluetooth(useblue, context, false);
        return useblue;
    }

    public static boolean hasip() {
        String[] ips = Backup.gethostnames();
        return ips.length > 0 && ips[ips.length - 1] != null;
    }

    // @RequiresApi(api = Build.VERSION_CODES.M)
    private static boolean hasonAvailable = false;
    private static final Set<Long> availableInternetNetworks = ConcurrentHashMap.newKeySet();
    private static final NetworkHandoverPolicy networkHandoverPolicy = new NetworkHandoverPolicy();
    private static final Object networkTransitionLock = new Object();
    private static ScheduledFuture<?> pendingNetworkAbsence;
    private static long networkAbsenceGeneration;
    private static final long NETWORK_ABSENCE_GRACE_MILLIS = 1_000L;

    private static long networkId(Network network) {
        return network.getNetworkHandle();
    }

    private static void cancelPendingNetworkAbsence() {
        synchronized (networkTransitionLock) {
            ++networkAbsenceGeneration;
            if (pendingNetworkAbsence != null) {
                pendingNetworkAbsence.cancel(false);
                pendingNetworkAbsence = null;
            }
        }
    }

    private static void applyNetworkAction(NetworkHandoverPolicy.Action action) {
        switch (action) {
            case PRESENT:
                cancelPendingNetworkAbsence();
                Natives.networkpresent();
                Applic.wakemirrors();
                break;
            case HANDOVER:
                cancelPendingNetworkAbsence();
                Natives.networkhandover();
                Applic.wakemirrors();
                break;
            case DEFER_ABSENCE:
                synchronized (networkTransitionLock) {
                    if (pendingNetworkAbsence != null) {
                        pendingNetworkAbsence.cancel(false);
                    }
                    final long generation = ++networkAbsenceGeneration;
                    pendingNetworkAbsence = scheduler.schedule(() -> {
                        synchronized (networkTransitionLock) {
                            if (generation != networkAbsenceGeneration) {
                                return;
                            }
                            pendingNetworkAbsence = null;
                        }
                        final long defaultNetwork = networkHandoverPolicy.currentDefaultNetwork();
                        final boolean defaultStillAvailable = defaultNetwork != 0L
                                && availableInternetNetworks.contains(defaultNetwork);
                        applyNetworkAction(networkHandoverPolicy.afterAbsenceGrace(
                                defaultStillAvailable));
                    }, NETWORK_ABSENCE_GRACE_MILLIS, TimeUnit.MILLISECONDS);
                }
                break;
            case ABSENT:
                cancelPendingNetworkAbsence();
                Natives.networkabsent();
                break;
            case NONE:
                break;
        }
    }
    /*
     * public static void sendsettings() {
     * {if(doLog) {Log.i(LOG_ID,"sendsettings");};};
     * if(!MessageSender.cansend()) {
     * {if(doLog) {Log.i(LOG_ID,"!cansend()");};};
     * return;
     * }
     * var sender=tk.glucodata.MessageSender.getMessageSender();
     * if(sender!=null) {
     * sender.sendsettings();
     * }
     * }
     */

    static private boolean MessageReceiverEnabled() {
        if (!isWearable) {
            try {
                Application app = Applic.app;
                PackageManager manage = app.getPackageManager();
                ComponentName scan = new ComponentName(app, "tk.glucodata.MessageReceiver");
                return manage.getComponentEnabledSetting(scan) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            } catch (Throwable e) {

                Log.stack(LOG_ID, e);
            }
        }
        return false;
    }

    static private void updateWearMessageReceiverComponent() {
        if (Applic.app == null) {
            return;
        }
        if (isWearable) {
            return;
        }
        try {
            final boolean gmsAvailable = GoogleServices.isPlayServicesAvailable(Applic.app);
            final PackageManager pm = Applic.app.getPackageManager();
            final ComponentName receiver = new ComponentName(Applic.app, "tk.glucodata.MessageReceiver");
            final int current = pm.getComponentEnabledSetting(receiver);
            // Keep user choice for Wear transport. Only force-disable when GMS is unavailable.
            // This prevents auto-enabling Wear transport on fresh installs.
            if (!gmsAvailable && current != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(
                        receiver,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                );
                Log.i(LOG_ID, "MessageReceiver component forced DISABLED (Google Play Services unavailable)");
            }
        } catch (Throwable th) {
            Log.stack(LOG_ID, "updateWearMessageReceiverComponent", th);
        }
    }

    static public boolean useWearos() {
        if (!GoogleServices.isPlayServicesAvailable(Applic.app))
            return false;
        if (isWearable)
            return true;
        else
            return MessageReceiverEnabled();
    }

    private void initialize() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            connectivityManager.registerNetworkCallback(
                    (new NetworkRequest.Builder())
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build(),
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            hasonAvailable = true;
                            availableInternetNetworks.add(networkId(network));
                            {
                                if (doLog) {
                                    Log.i(LOG_ID, "network: onAvailable(" + network + ")");
                                }
                                ;
                            }
                            ;
                            tk.glucodata.drivers.nightscout.NightscoutFollowerRegistry.INSTANCE
                                    .recoverOnNetworkAvailable(Applic.this);
                            final boolean wearos = useWearos();
                            if (wearos) {
                                Natives.networkpresent();
                                MessageSender.reinit();
                                MessageSender.sendnetinfo();
                                Applic.scheduler.schedule(() -> {
                                    resetWearOS();
                                }, 20, TimeUnit.SECONDS);
                                Applic.wakemirrors();
                            }
                        }

                        @Override
                        public void onUnavailable() {
                            {
                                if (doLog) {
                                    Log.i(LOG_ID, "network: onUnavailable()");
                                }
                                ;
                            }
                            ;
                        }

                        @Override
                        public void onLosing(Network network, int maxMsToLive) {
                            {
                                if (doLog) {
                                    Log.i(LOG_ID, "network: OnLosing(" + network + "," + maxMsToLive + ")");
                                }
                                ;
                            }
                            ;
                        }

                        @Override
                        public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                            var down = networkCapabilities.getLinkDownstreamBandwidthKbps();
                            var up = networkCapabilities.getLinkUpstreamBandwidthKbps();
                            var wifi = networkCapabilities.hasTransport(TRANSPORT_WIFI);
                            var blue = networkCapabilities.hasTransport(TRANSPORT_BLUETOOTH);
                            var validated = networkCapabilities.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                            boolean aware = false;
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                aware = networkCapabilities.hasTransport(TRANSPORT_WIFI_AWARE);
                            }
                            {
                                if (doLog) {
                                    Log.i(LOG_ID,
                                            "network: onCapabilitiesChanged(" + network + "downstream " + down
                                                    + " Kbps up=" + up + " Kbps " + (wifi ? "" : "no ") + " wifi, "
                                                    + (blue ? "" : "no ") + " bluetooth, " + (aware ? "" : " no ")
                                                    + "WIFI aware, " + (validated ? "" : "not ") + "validated)");
                                }
                                ;
                            }
                            ;
                        }

                        @Override
                        public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                            {
                                if (doLog) {
                                    Log.i(LOG_ID, "network: onLinkPropertiesChanged(" + network
                                            + ", LinkProperties linkProperties) ");
                                }
                                ;
                            }
                            ;
                        }

                        @Override
                        public void onLost(Network network) {
                            final long lostNetwork = networkId(network);
                            availableInternetNetworks.remove(lostNetwork);
                            {
                                if (doLog) {
                                    Log.i(LOG_ID, "onLost(" + network + ")");
                                }
                                ;
                            }
                            ;
                            applyNetworkAction(networkHandoverPolicy.onInternetNetworkLost(
                                    lostNetwork));
                            final boolean wearos = useWearos();
                            if (wearos) {
                                MessageSender.reinit();
                            }
                            // if(hasonAvailable)
                            if (wearos) {
                                Applic.wakemirrors();
                                Applic.scheduler.schedule(() -> {
                                    resetWearOS();
                                }, 20, TimeUnit.SECONDS);
                            }
                        }
                    });

            connectivityManager.registerDefaultNetworkCallback(
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            final long availableNetwork = networkId(network);
                            if (doLog) {
                                Log.i(LOG_ID, "default network: onAvailable(" + network + ")");
                            }
                            applyNetworkAction(networkHandoverPolicy.onDefaultAvailable(
                                    availableNetwork));
                        }

                        @Override
                        public void onLost(Network network) {
                            if (doLog) {
                                Log.i(LOG_ID, "default network: onLost(" + network + ")");
                            }
                            applyNetworkAction(networkHandoverPolicy.onDefaultLost(
                                    networkId(network)));
                        }
                    });

            if (hasip())
                Natives.networkpresent();
        } else
            Natives.networkpresent();
    }

    public static int initscreenwidth = -1;

    static private final BroadcastReceiver minTimeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Applic app = (Applic) context.getApplicationContext();
            app.initproc();
            app.domintime();
        }
    };

    void domintime() {
        {
            if (doLog) {
                Log.i(LOG_ID, "TICK");
            }
            ;
        }
        ;
        if (curve != null) {
            if ((curve.render.stepresult & STEPBACK) == 0) {
                {
                    if (doLog) {
                        Log.i(LOG_ID, "requestRender()");
                    }
                    ;
                }
                ;
                curve.requestRender();
                if (!isWearable) {
                    numdata.sendmessages();
                }
            }
        }
    }

    static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    void setmintime() {
        try {
            registerReceiver(minTimeReceiver, mintimefilter);
        } catch (Throwable th) {
            Log.stack(LOG_ID, "registerReceiver", th);
        }
    }

    void cancelmintime() {
        try {
            unregisterReceiver(minTimeReceiver);
        } catch (Throwable th) {
            Log.stack(LOG_ID, "cancelmintime", th);
        }
    }

    /*
     * void startstrictmode() {
     * if(BuildConfig.isRelease!=1) {
     * StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
     * .detectAll().penaltyLog().build());
     * StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectAll()
     * .penaltyLog() .build());
     * }
     * }
     */
    static boolean initproccalled = false;
    static boolean initStarted = false;
    /*
     * public static void initwearos(Context app) {
     * {if(doLog) {Log.i(LOG_ID,"before new MessageSender");};};
     * messagesender=new MessageSender(app);
     * {if(doLog) {Log.i(LOG_ID,"before sendnetinfo");};};
     * MessageSender.sendnetinfo();
     * }
     */
    static boolean dataAtStart = false;

    boolean initproc() {
        if (!initproccalled) {
            // setlibrary() restores and starts saved ICE hosts. Seed their
            // immutable connection settings before any host is constructed.
            CloneIceNetworkConfigStore.prepareForNativeStartup(this);
            if (!numio.setlibrary(this))
                return false;
            // ICE connections can be created by startup services before the
            // settings screen exists, so restore user-selected endpoints as
            // soon as the native library and persisted backup hosts are ready.
            CloneIceNetworkConfigStore.initialize(this);
            // Native backup hosts are restored before the Java Wear transport.
            // Honour the persisted companion switch immediately, otherwise a
            // disabled Wear host reconnects during every process start.
            MessageSender.syncNativeWearTransportState(useWearos());
            // First thing after the natives are up, so Log reaches trace.log: whatever killed the
            // previous process is otherwise lost. Both apps died inside the 2026-08-01 jamming
            // storm and 767s of the downtime is unattributed purely because no log line says why.
            ProcessExitReasons.logPrevious(this);
            if (isWearable) {
                if (Natives.getWifi())
                    UseWifi.usewifi();
            }
            if (tk.glucodata.Applic.useWearos()) {
                initwearos(this);
            }
            needsnatives();
            {
                if (doLog) {
                    Log.i("Applic", "initproc width=" + initscreenwidth);
                }
                ;
            }
            ;
            libre3init.init();
            SuperGattCallback.initAlarmTalk();
            // A poll alarm may be the component that starts this process. Restore cloud-only
            // callbacks before registering network listeners so the receiver and onAvailable
            // can repair polling without waiting for MainActivity to initialize Bluetooth.
            tk.glucodata.drivers.nightscout.NightscoutFollowerRegistry.INSTANCE
                    .restoreConfiguredFollower(this);
            initialize();

            // Load the glucose colour palette + per-band overrides before any
            // drawing happens, so the notification chart (which runs outside
            // Compose) renders with the user's choice too.
            GlucoseRangeColors.initFromPrefs(this);

            // Driver-owned one-time handoff. This must run after native storage initialization
            // and before SensorBluetooth creates callbacks for the active native sensor list.
            tk.glucodata.drivers.sibionics.SibionicsLegacyMigration.migrateActiveSensors(this);

            // Preserve an explicit non-active selection across startup. Historical sensors
            // are still valid dashboard targets even if they are not in activeSensors().
            // Virtual/cloud-only sensors must not stay in native lastsensorname: native
            // status/history paths will try to open non-existent sensor files and spam
            // getSensorData last()<0. Migrate those ids into the managed current slot.
            try {
                String currentSensor = Natives.lastsensorname();
                if (currentSensor != null && !currentSensor.isEmpty()
                        && !tk.glucodata.SensorIdentity.hasNativeSensorBacking(currentSensor)) {
                    tk.glucodata.ManagedCurrentSensor.set(currentSensor);
                    Natives.setcurrentsensor("");
                    Log.i("Applic", "Moved virtual current sensor '" + currentSensor + "' out of native lastsensorname");
                    currentSensor = "";
                }
                String[] active = Natives.activeSensors();
                if ((currentSensor == null || currentSensor.isEmpty())
                        && tk.glucodata.ManagedCurrentSensor.get() == null
                        && active != null && active.length > 0) {
                    Natives.setcurrentsensor(active[0]);
                    Log.i("Applic", "Seeded empty lastsensorname from first active sensor '" + active[0] + "'");
                }
            } catch (Throwable t) {
                Log.i("Applic", "startup sensor initialization failed: " + t.getMessage());
            }

            // Check if FloatingService should be started (mobile-only class, so no compile-time reference)
            if (!isWearable && getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
                    .getBoolean("floating_glucose_enabled", false)) {
                Intent intent = new Intent().setClassName(this, "tk.glucodata.service.FloatingGlucoseService");
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            }

            NumAlarm.handlealarm(this);
            Maintenance.setMaintenancealarm(this);
            // A quiet window is checked against the wall clock on every start: an
            // expired one ends now, a running one re-arms its expiry alarm. A process
            // kill must never stretch it.
            tk.glucodata.alerts.QuietWindow.syncOnStart(this);
            initbroadcasts();
            initproccalled = true;
            if (isWearable && !(dataAtStart = hasData())) {
                final ScheduledFuture<?>[] askstarthandle = { null };
                askstarthandle[0] = scheduler.scheduleWithFixedDelay(() -> {
                    if (initStarted) {
                        if (askstarthandle[0] != null)
                            askstarthandle[0].cancel(false);
                        return;
                    } else
                        MessageSender.sendaskforstart();
                }, 4, 30, TimeUnit.SECONDS);
            } else if (useWearos()) {
                MessageSender.sendnetinfo();
            }
            Specific.start(this);
            SensorOwnershipRuntime.start();
            if (isWearable) {
                tk.glucodata.glucosecomplication.GlucoseValue.updateall();
                // Direct sensor mode has to survive a restart of the watch app,
                // otherwise a handoff silently stops scanning.
                WearSensorClaim.restoreOnStart();
                // Same for automatic switching: the setting survives, the
                // scanning it enables would not.
                AutoSensorSwitch.restoreOnStart();
            }
        }
        return true;
    }

    public static void setbluetooth(Context activity, boolean on) {
        {
            if (doLog) {
                Log.i(LOG_ID, "setbluetooth(Context activity," + on + ")");
            }
            ;
        }
        ;
        Natives.setusebluetooth(on);
        if (on) {
            // The caller is often a message handler or a restart path, not the
            // Activity, so this is the only moment where we know Bluetooth is
            // wanted and can still ask for the permission it needs.
            requestScanPermission();
            Applic.app.initbluetooth(on, activity, activity instanceof MainActivity);
        } else {
            Applic.dontusebluetooth();
        }
        app.redraw();
    }

    public static int stopprogram = 0;

    @NonNull
    @Override
    public androidx.work.Configuration getWorkManagerConfiguration() {
        return new androidx.work.Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Before anything else: Application.onCreate precedes every component
        // (boot receiver, restarted service, activity), so this is the only
        // place that guarantees the trend/alert bridges exist before the first
        // reading is evaluated. Waiting for Specific.start() at the end of
        // initproc() left a post-reboot window where forecast alerts fired on
        // the degraded two-point slope.
        Specific.registerBridges();
        enlargeCursorWindow();
        updateWearMessageReceiverComponent();
        if (DiskSpace.check(this)) {
            initproc();
        } else {
            android.util.Log.e(LOG_ID, "Stop program");
            stopprogram = 1;
        }
    }

    /**
     * Raise the global {@link android.database.CursorWindow} size (default 2 MB).
     *
     * The glucose history Room table is read in a few places with an unbounded
     * {@code SELECT * FROM history_readings} that returns the whole table as a
     * single list. {@code SELECT *} fetches all 8 physical columns (including the
     * dead customValue/customRate columns left over from an old migration), so a
     * row is ~330-350 bytes and the 2 MB window overflows at ~6000 readings,
     * crashing with "Couldn't read row N, col 0 from CursorWindow" — most visibly
     * right after importing history/settings, which pushes the table over that
     * threshold. Bumping the ceiling to 16 MB (ashmem is committed lazily, so this
     * does not reserve 16 MB up front) covers ~48k readings. Best-effort: the
     * field is hidden, so any failure is logged and ignored.
     */
    private static void enlargeCursorWindow() {
        try {
            java.lang.reflect.Field field =
                    android.database.CursorWindow.class.getDeclaredField("sCursorWindowSize");
            field.setAccessible(true);
            field.setInt(null, 16 * 1024 * 1024);
        } catch (Throwable t) {
            android.util.Log.w(LOG_ID, "Could not enlarge CursorWindow size", t);
        }
    }

    // static public int backgroundcolor= BLACK;
    static public int backgroundcolor = isWearable ? BLACK : RED;

    void setbackgroundcolor(Context context) {
        if (!isWearable) {
            if (Build.VERSION.SDK_INT >= 21) {
                TypedValue typedValue = new TypedValue();
                if (context.getTheme().resolveAttribute(android.R.attr.windowBackground, typedValue, true)
                        && typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT)
                    backgroundcolor = typedValue.data;
            }
        }
    }

    // static private Runnable updater=null;
    static private ArrayList<Runnable> updaters = new ArrayList<Runnable>();

    public static void setscreenupdater(Runnable up) {
        updaters.add(up);
        // updater=up;
    }

    public static void removescreenupdater(Runnable up) {
        updaters.remove(up);
        // updater=up;
    }

    static void updatescreen() {
        if (app.curve != null)
            app.curve.requestRender();
        for (var el : updaters)
            el.run();
        updaters.clear();
    }

    static float headfontsize;
    static float mediumfontsize;
    static public float largefontsize;
    static float menufontsize;

    boolean needsnatives() {
        {
            if (doLog) {
                Log.i(LOG_ID, "needsnatives");
            }
            ;
        }
        ;
        final var res = getResources();
        var metrics = GlucoseCurve.metrics = res.getDisplayMetrics();
        MainActivity.screenheight = metrics.heightPixels;
        MainActivity.screenwidth = metrics.widthPixels;
        {
            if (doLog) {
                Log.i(LOG_ID, "heightPixels=" + GlucoseCurve.metrics.heightPixels + " widthPixels="
                        + GlucoseCurve.metrics.widthPixels);
            }
            ;
        }
        ;
        var newinitscreenwidth = Math.max(GlucoseCurve.metrics.heightPixels, GlucoseCurve.metrics.widthPixels);
        boolean ret;
        menufontsize = res.getDimension(R.dimen.abc_text_size_menu_material);
        final double screensize = (newinitscreenwidth / menufontsize);
        final boolean smallsize = screensize < 34.0;
        if (smallsize != NumberView.smallScreen) {
            NumberView.smallScreen = smallsize;
            if (curve != null) {
                curve.removeviews();
            }
            ret = true;
        } else
            ret = false;
        initscreenwidth = newinitscreenwidth;
        {
            if (doLog) {
                Log.i(LOG_ID, "initscreenwidth=" + newinitscreenwidth);
            }
            ;
        }
        ;
        {
            if (doLog) {
                Log.i(LOG_ID, "menufontsize=" + menufontsize);
            }
            ;
        }
        ;
        {
            if (doLog) {
                Log.i(LOG_ID, "screensize=" + screensize);
            }
            ;
        }
        ;
        headfontsize = res.getDimension(R.dimen.abc_text_size_display_4_material);
        Notify.glucosesize = headfontsize * .35f;
        smallfontsize = res.getDimension(R.dimen.abc_text_size_small_material);
        largefontsize = res.getDimension(R.dimen.abc_text_size_large_material);
        mediumfontsize = res.getDimension(R.dimen.abc_text_size_medium_material);
        // Natives.setfontsize(smallfontsize, menufontsize,
        // GlucoseCurve.metrics.density, headfontsize);
        Notify.mkpaint();
        return ret;
    }

    /*
     * @Keep
     * static void toCalendar(String name) {
     * MainActivity.tocalendarapp=true;
     * MainActivity.calendarsensor=name;
     * }
     */
    @Keep
    static boolean bluetoothEnabled() {
        return SensorBluetooth.bluetoothIsEnabled();
    }

    static final boolean usewakelock = true;

    /**
     * Safety net on the ingest wakelock. Handling one reading is sub-second work; this only
     * exists so a crash between acquire and release can never pin the CPU awake indefinitely.
     */
    static final long GLUCOSE_WAKELOCK_TIMEOUT_MS = 60_000L;

    @Keep
    static void doglucose(String SerialNumber, int mgdl, float gl, float rate, int alarm, long timmsec,
            boolean wasblueoff, long sensorstartmsec, long sensorptr, int sensorgen) {
        if (doLog) {
            Log.i(LOG_ID,
                    "doglucose " + SerialNumber + " " + mgdl + " " + gl + " " + rate + " " + alarm + " " + timmsec + " "
                            + wasblueoff + " " + sensorstartmsec + " sensorptr=" + format("%x", sensorptr) + " "
                            + sensorgen);
        }
        ;

        var wakelock = usewakelock ? (((PowerManager) app.getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Juggluco::Applic")) : null;
        if (wakelock != null) {
            wakelock.setReferenceCounted(false);
            wakelock.acquire(GLUCOSE_WAKELOCK_TIMEOUT_MS);
        }
        try {
            if (!wasblueoff) {
                Applic.dontusebluetooth();
            }
            SuperGattCallback.dowithglucose(SerialNumber, mgdl, gl, rate, alarm, timmsec, sensorstartmsec,
                    Notify.glucosetimeout, sensorgen);
            if (!isWearable) {
                if (sensorptr != 0L) {
                    {
                        if (doLog) {
                            Log.i(LOG_ID, "sensorptr=" + format("%x", sensorptr));
                        }
                        ;
                    }
                    ;
                    if (Build.VERSION.SDK_INT >= 28) {
                        HealthConnection.Companion.writeAll(sensorptr, SerialNumber);
                    }
                }
            }
        } finally {
            releasewakelock(wakelock);
        }
    }

    /** Release a wakelock without letting a release failure escape over the caller's own work. */
    static void releasewakelock(PowerManager.WakeLock wakelock) {
        if (wakelock == null)
            return;
        try {
            if (wakelock.isHeld())
                wakelock.release();
        } catch (Throwable th) {
            Log.stack(LOG_ID, "wakelock.release", th);
        }
    }

    @Keep
    static boolean updateDevices() { // Rename to reset
        final boolean useBluetooth = Natives.getusebluetooth();
        boolean ok = false;

        if (useBluetooth) {
            var blue = tk.glucodata.SensorBluetooth.blueone;
            if (blue == null) {
                tk.glucodata.SensorBluetooth.start(true);
                blue = tk.glucodata.SensorBluetooth.blueone;
            }
            if (blue != null) {
                if (blue.updateDevicers()) {
                    var main = MainActivity.thisone;
                    if (main != null)
                        main.finepermission();
                }
                tk.glucodata.SensorBluetooth.ensureCurrentSensorSelection();
                ok = true;
            } else {
                Log.e(LOG_ID, "tk.glucodata.SensorBluetooth.blueone==null");
            }
        } else {
            ok = tk.glucodata.SensorBluetooth.syncNativeDevicesNoBluetooth();
            tk.glucodata.SensorBluetooth.ensureCurrentSensorSelection();
        }

        updatescreen();
        Notify.showoldglucose();
        return ok;
    }
    /*
     * @Keep
     * static boolean updateDevices() { //Rename to reset
     * if(tk.glucodata.SensorBluetooth.blueone!=null) {
     * if(tk.glucodata.SensorBluetooth.blueone.resetDevices()) {
     * var main=MainActivity.thisone;
     * if(main!=null)
     * main.finepermission();
     * }
     * return true;
     * }
     * Log.e(LOG_ID,"tk.glucodata.SensorBluetooth.blueone==null");
     * return false;
     * }
     */
    /*
     * @Override
     * protected void attachBaseContext(Context base) {
     * super.attachBaseContext(base);
     * MultiDex.install(this);
     * }
     */

    public static void wakemirrors() {
        {
            if (doLog) {
                Log.i(LOG_ID, "wakemirrors");
            }
            ;
        }
        ;
        if (useWearos()) {
            MessageSender.sendwake();
        }
        Natives.wakebackup();
    }

    @Keep
    static public void resetWearOS() {
        if (useWearos()) {
            MessageSender.reinit();
        }
        wakemirrors();
    }

    private static void initbroadcasts() {
        if (isWearable) {
            Specific.setclose(!Natives.getdontuseclose());
        }

        Floating.init();
        final var initversion = Natives.getinitVersion();
        if (initversion < 37) {
            if (initversion < 36) {
                if (initversion < 29) {
                    if (initversion < 22) {
                        if (initversion < 14) {
                            if (initversion < 13) {
                                Broadcasts.updateall();
                            }
                            if (Notify.arrowNotify != null)
                                Natives.setfloatingFontsize((int) Notify.glucosesize);
                            Natives.setfloatingbackground(WHITE);
                            Natives.setfloatingforeground(BLACK);
                        }
                    }
                    sethour24(DateFormat.is24HourFormat(app));
                }
            }
            if (hasLegacyDefaultChartRange()) {
                Natives.setGraphRange(0.0f, Natives.getunit() == 1 ? 13.0f : 234.0f);
            }
            Natives.setinitVersion(37);
        }

        setjavahour24(Natives.gethour24());
        XInfuus.setlibrenames();
        JugglucoSend.setreceivers();
        SendLikexDrip.setreceivers();
    }

    private static boolean hasLegacyDefaultChartRange() {
        final var unit = Natives.getunit();
        final var graphLow = Natives.graphlow();
        final var graphHigh = Natives.graphhigh();
        if (unit == 1) {
            return isClose(graphLow, 3.0f, 0.05f) && isClose(graphHigh, 12.0f, 0.05f);
        }
        return isClose(graphLow, 54.0f, 0.5f) && isClose(graphHigh, 216.0f, 0.5f);
    }

    private static boolean isClose(float actual, float expected, float tolerance) {
        return Math.abs(actual - expected) <= tolerance;
    }

    static public boolean talkbackrunning = false;

    static void talkbackon(Context cont) {
        if (!DontTalk) {
            SuperGattCallback.newtalker(cont);
            talkbackrunning = true;

            Natives.settouchtalk(true);
            Natives.setspeakmessages(true);
            Natives.setspeakalarms(true);
        }

    }

    static void talkbackoff() {
        if (!DontTalk) {
            talkbackrunning = false;
        }
    }

    @Keep
    static public void speak(String message) {
        if (!DontTalk) {
            var talker = SuperGattCallback.talker;
            if (talker == null) {
                SuperGattCallback.newtalker(null);
                talker = SuperGattCallback.talker;
            }
            talker.speak(message);
        }
    }

    static public boolean getHeartRate() {
        return initproccalled && Natives.getheartrate();
    }

    /*
     * @Keep
     * static void changedProfile() {
     * SuperGattCallback.init();
     * }
     */
    @Keep
    static void setinittext(String str) {
        RunOnUiThread(() -> {
            Specific.settext(str);
        });
    }

    @Keep
    static void rminitlayout() {
        RunOnUiThread(() -> {
            Specific.rmlayout();
        });
    }

    @Keep
    static void toGarmin(int base) {
        if (!isWearable) {
            app.numdata.changedback(base);
        }
    }

    @Keep
    static void Garmindeletelast(int base, int pos, int end) {
        if (!isWearable) {
            app.numdata.deletelast(base, pos, end);
            app.numdata.changedback(base);
        }
    }

    static public void startMain() {
        final var act = MainActivity.thisone;
        final var intent = new Intent(Applic.app, MainActivity.class);
        intent.putExtra(Notify.fromnotification, true);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setAction(Intent.ACTION_MAIN);
        if (act != null) {
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            {
                if (doLog) {
                    Log.i(LOG_ID, "startActivityIfNeeded( new Intent(Applic.app,MainActivity)). ");
                }
                ;
            }
            ;
            act.startActivityIfNeeded(intent, 0);
        } else {
            final var service = keeprunning.theservice;
            if (service != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                {
                    if (doLog) {
                        Log.i(LOG_ID, "startActivity MainActivity.thisone==null");
                    }
                    ;
                }
                ;
                service.startActivity(intent);
            } else {
                if (doLog) {
                    Log.i(LOG_ID, "keeprunning.theservice==null");
                }
                ;
            }
        }
    }

    @Keep
    static boolean switchbluetooth(String name, byte[] netinfo, boolean watchBluetooth) {
        if (!isWearable) {
            if (netinfo != null) {
                var sender = tk.glucodata.MessageSender.getMessageSender();
                if (sender != null) {
                    sender.sendnetinfo(name, netinfo);
                    sender.sendbluetooth(name, watchBluetooth);
                    var main = MainActivity.thisone;
                    boolean here = !watchBluetooth;
                    Applic.setbluetooth(main == null ? Applic.app : main, here);
                    return true;
                }
            }
        }
        return false;
    }



}
