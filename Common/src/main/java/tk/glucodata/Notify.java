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

import static android.app.Notification.FLAG_ONGOING_EVENT;
import static android.app.Notification.VISIBILITY_PUBLIC;
import static android.content.Context.NOTIFICATION_SERVICE;
import static android.content.Context.VIBRATOR_SERVICE;
import android.media.MediaPlayer;
import static android.graphics.Color.BLACK;
import static android.graphics.Color.WHITE;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
import static java.lang.String.format;
import static tk.glucodata.Applic.DontTalk;
import static tk.glucodata.Applic.TargetSDK;
import static tk.glucodata.Applic.app;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Applic.usedlocale;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Natives.getUSEALARM;
import static tk.glucodata.Natives.getalarmdisturb;
import static tk.glucodata.Natives.getisalarm;
import static tk.glucodata.Natives.setisalarm;
import static tk.glucodata.R.id.arrowandvalue;
import static tk.glucodata.ScanNfcV.vibrates;
import static tk.glucodata.Talker.notifyfocus;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.KeyguardManager;
import android.app.AlarmManager;
import android.view.View;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Bitmap; // Added Import
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.DisplayMetrics;
import android.widget.RemoteViews;

import androidx.annotation.ColorInt;

import java.text.DateFormat;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import tk.glucodata.alerts.AlertDisplayText;
import tk.glucodata.alerts.AlertType;
import tk.glucodata.alerts.SnoozeManager;
import tk.glucodata.alerts.AlertConfig;
import tk.glucodata.alerts.AlertNotificationDismissAction;
import tk.glucodata.alerts.AlertRepository;
import tk.glucodata.alerts.AlertStateTracker;
import tk.glucodata.alerts.QuietWindow;
import tk.glucodata.drivers.ManagedSensorRuntime;
import tk.glucodata.drivers.ManagedSensorStatusPolicy;
import java.util.Collections;
import java.util.List;

public class Notify {
    // ... class start ...
    static {
        makenotification_audio();
    };
    static public final int glucosetimeoutSEC = 30 * 11;
    static public final long glucosetimeout = 1000L * glucosetimeoutSEC;
    static private final int FOREGROUND_GLUCOSE_NOTIFICATION_KIND = -1;
    static private final long INTERACTIVE_NOTIFICATION_REFRESH_DELAY_MS = 750L;
    static private final long DATA_CHANGED_NOTIFICATION_REFRESH_DELAY_MS = 1000L;
    static private final long LOCKED_ALARM_ACTIVITY_DELAY_MS = 0L;
    static private final Handler glucoseRefreshHandler = makeGlucoseRefreshHandler();

    static final private String LOG_ID = "Notify";
    static Notify onenot = null;

    private static Handler makeGlucoseRefreshHandler() {
        HandlerThread thread = new HandlerThread("JugglucoNotifyRefresh", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        return new Handler(thread.getLooper());
    }

    static void init(Context cont) {
        if (onenot == null) {
            onenot = new Notify(cont);

        }
    }

    public static String glucoseformat = null;
    public static String pureglucoseformat = null;
    static public String unitlabel = "mg/dL";

    // public static int unit=0;
    static void mkunitstr(Context cont, int unit) {
        Applic.unit = unit;
        pureglucoseformat = unit == 1 ? "%.1f" : "%.0f";
        if (isWearable) {
            glucoseformat = pureglucoseformat;
        } else {
            unitlabel = unit == 1 ? cont.getString(R.string.mmolL) : cont.getString(R.string.mgdL);
            glucoseformat = unit == 1 ? "%.1f " + unitlabel : "%.0f " + unitlabel;
        }

    }

    @SuppressLint("NewApi")
    Ringtone setring(String uristr, int res) {
        if (uristr == null || uristr.length() == 0) {
            uristr = "android.resource://" + Applic.app.getPackageName() + "/" + res;
        }
        Uri uri = Uri.parse(uristr);
        Ringtone ring = RingtoneManager.getRingtone(Applic.app, uri);
        if (ring == null) {
            {
                if (doLog) {
                    Log.i(LOG_ID, "ring==null default");
                }
                ;
            }
            ;
            uristr = "android.resource://" + Applic.app.getPackageName() + "/" + res;
            uri = Uri.parse(uristr);
            ring = RingtoneManager.getRingtone(Applic.app, uri);
        }
        // NOTE: Do NOT set looping here - the scheduled stop via runstopalarm handles
        // duration.
        // setLooping(true) causes the ringtone to loop forever and prevents proper
        // stopping.
        return ring;
    }

    private static final class AlertSoundHandle {
        private static final long SOUND_FADE_IN_MS = 1_200L;
        private static final int SOUND_FADE_STEPS = 8;
        private static final float SOUND_START_VOLUME = 0.12f;
        // Hard floor: sound must never accidentally go silent even if profile logic has a bug
        private static final float MIN_PLAY_VOLUME = 0.3f;

        private final Ringtone ringtone;
        private final MediaPlayer mediaPlayer;
        private final String title;
        private boolean released = false;
        private int playGeneration = 0;
        private float maxVolume = 1.0f;

        AlertSoundHandle(Ringtone ringtone, MediaPlayer mediaPlayer, String title) {
            this.ringtone = ringtone;
            this.mediaPlayer = mediaPlayer;
            this.title = title;
        }

        boolean isPresent() {
            return ringtone != null || mediaPlayer != null;
        }

        String getTitle() {
            return (title != null && !title.isEmpty()) ? title : "alert sound";
        }

        synchronized void setMaxVolume(float vol) {
            maxVolume = Math.max(MIN_PLAY_VOLUME, Math.min(1.0f, vol));
        }

        private synchronized void setVolume(float volume) {
            if (released) {
                return;
            }
            final float safeVolume = Math.max(0.0f, Math.min(1.0f, volume));
            try {
                if (ringtone != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.setVolume(safeVolume);
                }
                if (mediaPlayer != null) {
                    mediaPlayer.setVolume(safeVolume, safeVolume);
                }
            } catch (Throwable th) {
                Log.stack(LOG_ID, "AlertSoundHandle.setVolume()", th);
            }
        }

        private void scheduleFadeIn(int generation) {
            for (int step = 1; step <= SOUND_FADE_STEPS; step++) {
                final int finalStep = step;
                final long delayMs = (SOUND_FADE_IN_MS * step) / SOUND_FADE_STEPS;
                Applic.scheduler.schedule(() -> {
                    synchronized (AlertSoundHandle.this) {
                        if (released || generation != playGeneration) {
                            return;
                        }
                    }
                    final float progress = (float) finalStep / (float) SOUND_FADE_STEPS;
                    setVolume(SOUND_START_VOLUME + ((maxVolume - SOUND_START_VOLUME) * progress));
                }, delayMs, TimeUnit.MILLISECONDS);
            }
        }

        synchronized void play() {
            if (released) {
                return;
            }
            try {
                final int generation = ++playGeneration;
                setVolume(SOUND_START_VOLUME);
                if (ringtone != null) {
                    ringtone.play();
                    scheduleFadeIn(generation);
                    return;
                }
                if (mediaPlayer != null) {
                    try {
                        mediaPlayer.seekTo(0);
                    } catch (Throwable ignored) {
                    }
                    mediaPlayer.start();
                    scheduleFadeIn(generation);
                }
            } catch (Throwable th) {
                Log.stack(LOG_ID, "AlertSoundHandle.play()", th);
            }
        }

        synchronized void stop() {
            if (released) {
                return;
            }
            released = true;
            playGeneration++;
            if (ringtone != null) {
                try {
                    ringtone.stop();
                } catch (Throwable th) {
                    Log.stack(LOG_ID, "AlertSoundHandle.ringtone.stop()", th);
                }
            }
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    mediaPlayer.reset();
                } catch (Throwable ignored) {
                }
                try {
                    mediaPlayer.release();
                } catch (Throwable th) {
                    Log.stack(LOG_ID, "AlertSoundHandle.mediaPlayer.release()", th);
                }
            }
        }
    }

    private static String defaultAlertSoundUri(int res) {
        return "android.resource://" + Applic.app.getPackageName() + "/" + res;
    }

    private static String resolveSoundTitle(Uri uri, Ringtone ringtone) {
        if (ringtone != null) {
            try {
                final String title = ringtone.getTitle(Applic.app);
                if (title != null && !title.isEmpty()) {
                    return title;
                }
            } catch (Throwable ignored) {
            }
        }
        if (uri != null) {
            final String lastSegment = uri.getLastPathSegment();
            if (lastSegment != null && !lastSegment.isEmpty()) {
                return lastSegment;
            }
        }
        return "alert sound";
    }

    private AlertSoundHandle buildMediaPlayerHandle(Uri uri, boolean useAlarmStream) {
        MediaPlayer player = null;
        android.content.res.AssetFileDescriptor afd = null;
        try {
            player = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= 21) {
                player.setAudioAttributes(useAlarmStream ? ScanNfcV.audioattributes : notification_audio);
            }
            try {
                afd = Applic.app.getContentResolver().openAssetFileDescriptor(uri, "r");
                if (afd != null) {
                    if (afd.getLength() >= 0L) {
                        player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                    } else {
                        player.setDataSource(afd.getFileDescriptor());
                    }
                } else {
                    player.setDataSource(Applic.app, uri);
                }
            } catch (Throwable dataSourceError) {
                if (afd != null) {
                    try {
                        afd.close();
                    } catch (Throwable ignored) {
                    }
                    afd = null;
                }
                player.setDataSource(Applic.app, uri);
            }
            if (afd != null) {
                try {
                    afd.close();
                } catch (Throwable ignored) {
                }
                afd = null;
            }
            player.setLooping(true);
            player.prepare();
            return new AlertSoundHandle(null, player, resolveSoundTitle(uri, null));
        } catch (Throwable th) {
            if (afd != null) {
                try {
                    afd.close();
                } catch (Throwable ignored) {
                }
            }
            if (player != null) {
                try {
                    player.release();
                } catch (Throwable ignored) {
                }
            }
            Log.stack(LOG_ID, "buildMediaPlayerHandle(" + uri + ")", th);
            return null;
        }
    }

    private static String normalizeAlertSoundUri(String uristr, String fallbackUri) {
        if (uristr == null || uristr.length() == 0) {
            return fallbackUri;
        }
        if ("SYSTEM_DEFAULT".equals(uristr)) {
            final Uri systemDefault = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (systemDefault != null) {
                return systemDefault.toString();
            }
        }
        return uristr;
    }

    private boolean shouldUseAlarmAudioStream(int kind, boolean disturb) {
        if (AlertType.Companion.isLegacyOnlyId(kind)) {
            return false;
        }
        return AlertDeliveryPolicy.shouldUseAlarmAudioStream(normalizeDeliveryMode(getDeliveryMode(kind)), disturb);
    }

    private AlertSoundHandle buildAlertSoundHandle(String uristr, int kind, boolean useAlarmStream) {
        final int fallbackRes = defaults[Math.max(0, Math.min(kind, defaults.length - 1))];
        final String fallbackUri = defaultAlertSoundUri(fallbackRes);
        final String requestedUriString = normalizeAlertSoundUri(uristr, fallbackUri);
        final Uri requestedUri = Uri.parse(requestedUriString);

        final AlertSoundHandle mediaHandle = buildMediaPlayerHandle(requestedUri, useAlarmStream);
        if (mediaHandle != null) {
            return mediaHandle;
        }

        Ringtone ringtone = RingtoneManager.getRingtone(Applic.app, requestedUri);
        if (ringtone != null) {
            if (Build.VERSION.SDK_INT >= 21) {
                try {
                    ringtone.setAudioAttributes(useAlarmStream ? ScanNfcV.audioattributes : notification_audio);
                } catch (Throwable e) {
                    Log.stack(LOG_ID, "buildAlertSoundHandle.ringtone", e);
                }
            }
            return new AlertSoundHandle(ringtone, null, resolveSoundTitle(requestedUri, ringtone));
        }

        final Uri fallbackParsedUri = Uri.parse(fallbackUri);
        ringtone = RingtoneManager.getRingtone(Applic.app, fallbackParsedUri);
        if (ringtone != null) {
            if (Build.VERSION.SDK_INT >= 21) {
                try {
                    ringtone.setAudioAttributes(useAlarmStream ? ScanNfcV.audioattributes : notification_audio);
                } catch (Throwable e) {
                    Log.stack(LOG_ID, "buildAlertSoundHandle.fallback", e);
                }
            }
            return new AlertSoundHandle(ringtone, null, resolveSoundTitle(fallbackParsedUri, ringtone));
        }
        return new AlertSoundHandle(null, null, "alert sound");
    }

    static public String alarmtext(int kind) {
        return Applic.getContext().getString(switch (kind) {
            case 0 -> R.string.lowglucoseshort;
            case 1 -> R.string.highglucoseshort;
            case 5 -> R.string.verylowglucose;
            case 6 -> R.string.veryhighglucose;
            case 7 -> R.string.prelowglucose;
            case 8 -> R.string.prehighglucose;
            default -> R.string.nothing;
        });
    }

    // 0 1 2 3 4 5 6 7 8
    // low high avail amount loss very low very high pre low pre high
    static final private int[] defaults = { R.raw.siren, R.raw.classic, R.raw.ghost, R.raw.nudge, R.raw.elves,
            R.raw.verylow, R.raw.veryhigh, R.raw.lowsoon, R.raw.highsoon, R.raw.classic, R.raw.classic,
            R.raw.classic };

    // static AudioAttributes notification_audio=(android.os.Build.VERSION.SDK_INT
    // >= 21)?new
    // AudioAttributes.Builder().setUsage(isWearable?USAGE_UNKNOWN:USAGE_NOTIFICATION)
    // .build():null;
    // static AudioAttributes notification_audio=(android.os.Build.VERSION.SDK_INT
    // >= 21)?new AudioAttributes.Builder().setUsage( USAGE_ASSISTANCE_SONIFICATION)
    // .build():null;
    // static AudioAttributes notification_audio=(android.os.Build.VERSION.SDK_INT
    // >= 21)?new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION)
    // .build():null;

    static AudioAttributes notification_audio;
    // static AudioAttributes notification_audio=(android.os.Build.VERSION.SDK_INT
    // >= 21)?new AudioAttributes.Builder().setUsage(isWearable?
    // USAGE_ASSISTANCE_SONIFICATION: AudioAttributes.USAGE_NOTIFICATION)
    // .build():null;
    static AudioFocusRequest audiofocusrequest;

    static public void makenotification_audio() {
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            int type = Natives.getSoundType();
            Log.i(LOG_ID, "getSoundType()=" + type);
            if (type == 0) {
                type = isWearable ? USAGE_ASSISTANCE_SONIFICATION : AudioAttributes.USAGE_NOTIFICATION;
            }
            notification_audio = new AudioAttributes.Builder().setUsage(type).build();
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                audiofocusrequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(notification_audio).build();
                Log.i(LOG_ID, "audiofocusrequest  has value");
            } else {
                audiofocusrequest = null;
                Log.i(LOG_ID, "audiofocusrequest=null");
            }
        } else {
            notification_audio = null;
        }
    }

    static private AudioManager audioManager = (android.os.Build.VERSION.SDK_INT < 26) ? null
            : (AudioManager) Applic.getContext().getSystemService(Context.AUDIO_SERVICE);
    static private boolean turnfocusoff = false;

    static void doTurnFocusoff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final var wasturnoff = turnfocusoff;
            turnfocusoff = false;
            if (wasturnoff) {
                audioManager.abandonAudioFocusRequest(audiofocusrequest);
            }
        }
    }

    static void doTurnFocuson() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            if (!turnfocusoff) {
                switch (audioManager.requestAudioFocus(audiofocusrequest)) {
                    case AudioManager.AUDIOFOCUS_REQUEST_FAILED:
                        Log.i(LOG_ID, "REQUEST_FAILED");
                        break;

                    case AudioManager.AUDIOFOCUS_REQUEST_GRANTED:
                        turnfocusoff = true;
                        Log.i(LOG_ID, "REQUEST_GRANTED");
                        break;
                    case AudioManager.AUDIOFOCUS_REQUEST_DELAYED:
                        Log.i(LOG_ID, "REQUEST_DELAYED");
                        break;
                }
                ;
            }
        }
    }

    public static Ringtone getring(int kind) {
        return mkrings(Natives.readring(kind), kind);
    }

    Ringtone mkring(String uristr, int kind) {
        // For global alerts, use getalarmdisturb to determine audio stream
        return mkring(uristr, kind, getalarmdisturb(kind));
    }

    Ringtone mkring(String uristr, int kind, boolean disturb) {
        {
            if (doLog) {
                Log.i(LOG_ID, "ringtone " + kind + " " + uristr + " disturb=" + disturb);
            }
            ;
        }
        ;
        var ring = setring(uristr, defaults[kind]);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            try {
                boolean useAlarmStream = (!AlertType.Companion.isLegacyOnlyId(kind) && disturb);
                ring.setAudioAttributes(useAlarmStream ? ScanNfcV.audioattributes : notification_audio);
            } catch (Throwable e) {
                Log.stack(LOG_ID, "mkring", e);
            }

        }
        return ring;
    }

    static public Ringtone mkrings(String uristr, int kind) {
        if (onenot != null)
            return onenot.mkring(uristr, kind);
        return null;
    }

    final static boolean whiteonblack = false;
    @ColorInt
    public static int foregroundcolor = BLACK;
    static public float glucosesize;
    static RemoteGlucose arrowNotify;

    static void mkpaint() {
        if (!isWearable) {
            DisplayMetrics metrics = Applic.app.getResources().getDisplayMetrics();
            {
                if (doLog) {
                    Log.i(LOG_ID, "metrics.density=" + metrics.density + " width=" + metrics.widthPixels + " height="
                            + metrics.heightPixels);
                }
                ;
            }
            ;
            var notwidth = Math.min(metrics.widthPixels, metrics.heightPixels);
            arrowNotify = new RemoteGlucose(glucosesize, notwidth, 0.12f, whiteonblack ? 1 : 0, false);
        }
    }

    Notify(Context cont) {
        showalways = Natives.getshowalways();
        {
            if (doLog) {
                Log.i(LOG_ID, "showalways=" + showalways);
            }
            ;
        }
        ;
        alertseparate = true; // Natives.getSeparate(); // Force true for modern notification channels
        mkunitstr(cont, Natives.getunit());
        notificationManager = (NotificationManager) Applic.app.getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel(Applic.app);
        mkpaint();
    }

    private static final String NUMALARM = "MedicationReminder";
    private static final String GLUCOSEALARM = "glucoseAlarm";
    public static final String CHANNEL_LOW = "LOW";
    public static final String CHANNEL_HIGH = "HIGH";
    public static final String CHANNEL_LOSS = "LOSS";
    public static final String CHANNEL_MISSED_READING = "MISSED_READING";
    public static final String CHANNEL_SENSOR_EXPIRY = "SENSOR_EXPIRY";
    // private static final String LOSSALARM = "LossofSensorAlarm";
    private static String GLUCOSENOTIFICATION = "glucoseNotification";

    private static String alertChannelForKind(int kind) {
        switch (kind) {
            case 0:
                return CHANNEL_LOW;
            case 1:
                return CHANNEL_HIGH;
            case 4:
                return CHANNEL_LOSS;
            case 9:
                return CHANNEL_MISSED_READING;
            case 10:
                return CHANNEL_HIGH;
            case 11:
                return CHANNEL_SENSOR_EXPIRY;
            default:
                return GLUCOSEALARM;
        }
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            // Determine Channel ID (Standard)
            String targetChannelId = "glucoseNotification";
            GLUCOSENOTIFICATION = targetChannelId;

            try {
                // Cleanup experiment channels
                String[] obsolete = { "glucoseNotification_nodot", "glucoseNotification_nobadge" };
                for (String s : obsolete) {
                    if (notificationManager.getNotificationChannel(s) != null) {
                        notificationManager.deleteNotificationChannel(s);
                    }
                }
            } catch (Exception e) {
            }

            String description = context.getString(R.string.numalarm_description);
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(NUMALARM, NUMALARM, importance);
            channel.setSound(null, null);
            channel.setDescription(description);
            channel.setShowBadge(true); // Default behavior
            channel.setLockscreenVisibility(VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);

            description = context.getString(R.string.alarm_description);
            importance = NotificationManager.IMPORTANCE_HIGH;
            channel = new NotificationChannel(GLUCOSEALARM, GLUCOSEALARM, importance);
            channel.setSound(null, null);
            channel.setDescription(description);
            channel.setShowBadge(true);
            channel.setLockscreenVisibility(VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);

            description = context.getString(R.string.notification_description);
            importance = NotificationManager.IMPORTANCE_HIGH; // Default High

            // Standard Channel
            channel = new NotificationChannel(GLUCOSENOTIFICATION, GLUCOSENOTIFICATION, importance);
            channel.setSound(null, null);
            channel.setDescription(description);
            channel.setShowBadge(true);
            channel.setLockscreenVisibility(VISIBILITY_PUBLIC);

            notificationManager.createNotificationChannel(channel);

            // === NEW CHANNELS (Phase 4) ===
            NotificationChannel channelLow = new NotificationChannel(CHANNEL_LOW, "Low Glucose",
                    NotificationManager.IMPORTANCE_HIGH);
            channelLow.setDescription("Alerts when glucose is below target");
            channelLow.setSound(null, null); // App plays sound manually
            channelLow.setShowBadge(false);
            channelLow.setLockscreenVisibility(VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channelLow);

            NotificationChannel channelHigh = new NotificationChannel(CHANNEL_HIGH, "High Glucose",
                    NotificationManager.IMPORTANCE_HIGH);
            channelHigh.setDescription("Alerts when glucose is above target");
            channelHigh.setSound(null, null);
            channelHigh.setShowBadge(false);
            channelHigh.setLockscreenVisibility(VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channelHigh);

            NotificationChannel channelLoss = new NotificationChannel(CHANNEL_LOSS, "Signal Loss",
                    NotificationManager.IMPORTANCE_HIGH);
            channelLoss.setDescription("Alerts when sensor signal is lost");
            channelLoss.setSound(null, null);
            channelLoss.setShowBadge(false);
            channelLoss.setLockscreenVisibility(VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channelLoss);

            NotificationChannel channelMissedReading = new NotificationChannel(
                    CHANNEL_MISSED_READING,
                    context.getString(R.string.alert_missed_reading),
                    NotificationManager.IMPORTANCE_HIGH);
            channelMissedReading.setDescription(context.getString(R.string.missed_reading_channel_description));
            channelMissedReading.setSound(null, null);
            channelMissedReading.setShowBadge(false);
            channelMissedReading.setLockscreenVisibility(VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channelMissedReading);

            NotificationChannel channelSensorExpiry = new NotificationChannel(
                    CHANNEL_SENSOR_EXPIRY,
                    context.getString(R.string.alert_sensor_expiry),
                    NotificationManager.IMPORTANCE_HIGH);
            channelSensorExpiry.setDescription(context.getString(R.string.sensor_expiry_channel_description));
            channelSensorExpiry.setSound(null, null);
            channelSensorExpiry.setShowBadge(false);
            channelSensorExpiry.setLockscreenVisibility(VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channelSensorExpiry);
        }

    }

    // channel.setShowBadge(false);
    // channel.setShowBadge(false);
    boolean lowglucose(notGlucose strgl, float gl, float rate, boolean alarm) {
        String msg = Applic.getContext().getString(R.string.alert_low) + " " + format(usedlocale, glucoseformat, gl);
        return arrowglucosealarm(0, gl, msg, strgl, alertChannelForKind(0), alarm);
    }

    boolean highglucose(notGlucose strgl, float gl, float rate, boolean alarm) {
        String msg = Applic.getContext().getString(R.string.alert_high) + " " + format(usedlocale, glucoseformat, gl);
        return arrowglucosealarm(1, gl, msg, strgl, alertChannelForKind(1), alarm);
    }

    boolean veryhighglucose(notGlucose strgl, float gl, float rate, boolean alarm) {
        String msg = Applic.getContext().getString(R.string.alert_very_high) + " "
                + format(usedlocale, glucoseformat, gl);
        return arrowglucosealarm(6, gl, msg, strgl, alertChannelForKind(6), alarm);
    }

    boolean verylowglucose(notGlucose strgl, float gl, float rate, boolean alarm) {
        String msg = Applic.getContext().getString(R.string.alert_very_low) + " "
                + format(usedlocale, glucoseformat, gl);
        return arrowglucosealarm(5, gl, msg, strgl, alertChannelForKind(5), alarm);
    }

    boolean prehighglucose(notGlucose strgl, float gl, float rate, boolean alarm) {
        String msg = Applic.getContext().getString(R.string.alert_forecast_high) + " "
                + format(usedlocale, glucoseformat, gl);
        return arrowglucosealarm(8, gl, msg, strgl, alertChannelForKind(8), alarm);
    }

    boolean prelowglucose(notGlucose strgl, float gl, float rate, boolean alarm) {
        String msg = Applic.getContext().getString(R.string.alert_forecast_low) + " "
                + format(usedlocale, glucoseformat, gl);
        return arrowglucosealarm(7, gl, msg, strgl, alertChannelForKind(7), alarm);
    }

    public static boolean triggerSupplementalGlucoseAlert(int kind, float glucoseValue, float rate, String message) {
        final Notify notify = onenot;
        if (notify == null || !Float.isFinite(glucoseValue)) {
            return false;
        }

        final notGlucose base = toLegacyGlucose(resolveNotificationCurrentSnapshot());
        final notGlucose snapshot = copyGlucoseSnapshot(base, glucoseValue);
        snapshot.time = System.currentTimeMillis();
        snapshot.value = format(usedlocale, pureglucoseformat, glucoseValue);
        snapshot.rate = rate;
        return notify.arrowglucosealarm(kind, glucoseValue, message, snapshot, alertChannelForKind(kind), true);
    }

    /**
     * Message-only variant for alerts about non-glucose conditions (sensor
     * expiry) when no current reading exists. The alarm carries no glucose
     * figure at all - showing a stale or fabricated value on an alarm screen
     * would be worse than showing none. Runs through arrowglucosealarm like the
     * value-carrying variant, so the first-fire gate and retry sessions apply
     * unchanged; the NaN value keeps the banner display blank and the
     * foreground glucose notification untouched.
     */
    public static boolean triggerSupplementalMessageAlert(int kind, String message) {
        final Notify notify = onenot;
        if (notify == null) {
            return false;
        }
        final notGlucose snapshot = new notGlucose(System.currentTimeMillis(), "", Float.NaN, 0);
        return notify.arrowglucosealarm(kind, Float.NaN, message, snapshot, alertChannelForKind(kind), true);
    }

    private static void sendLegacyWatchAlert(int kind, float glvalue, String message, notGlucose strglucose) {
        if (isWearable) {
            return;
        }
        try {
            if (kind == AlertType.MISSED_READING.getId()) {
                WearInt.missingalarm(System.currentTimeMillis());
                return;
            }
            final String watchMessage = legacyWatchAlertMessage(kind, glvalue, message, strglucose);
            if (watchMessage != null && !watchMessage.isBlank()) {
                WearInt.alarm(watchMessage);
            }
        } catch (Throwable th) {
            Log.stack(LOG_ID, "sendLegacyWatchAlert", th);
        }
    }

    private static String legacyWatchAlertMessage(int kind, float glvalue, String message, notGlucose strglucose) {
        final String value = strglucose != null && strglucose.value != null && !strglucose.value.isBlank()
                ? strglucose.value
                : (Float.isFinite(glvalue) ? format(usedlocale, pureglucoseformat, glvalue) : "");
        switch (kind) {
            case 0:
            case 5:
            case 7:
                return value.isBlank() ? message : "LOW " + value;
            case 1:
            case 6:
            case 8:
            case 10:
                return value.isBlank() ? message : "HIGH " + value;
            default:
                return message != null && !message.isBlank() ? message : value;
        }
    }

    static private final int glucosenotificationid = 81431;
    static private final int glucosealarmid = 81432;
    static boolean alertwatch = false;
    static private boolean showalways = Natives.getshowalways();

    static public String glucosestr(float gl) {
        return format(usedlocale, glucoseformat, gl);
    }

    static public void glucosestatus(boolean val) {
        showalways = val;
        Natives.setshowalways(val);
        if (!val) {
            if (onenot != null)
                onenot.novalue();
        } else {
            showoldglucose();
        }
    }

    boolean hasvalue = false;
    private long lastForegroundGlucoseTimeMs = 0L;
    private float lastForegroundGlucoseValue = Float.NaN;
    private float lastForegroundGlucoseRate = Float.NaN;

    void showglucose(notGlucose strgl, float gl) {
        var message = format(usedlocale, glucoseformat, gl);
        arrowglucosenotification(FOREGROUND_GLUCOSE_NOTIFICATION_KIND, gl, message, strgl, GLUCOSENOTIFICATION,
                true);
    }
    /*
     * void overwriteglucose() {
     * 
     * var strgl=SuperGattCallback.previousglucose;
     * if(strgl==null)
     * return;
     * showglucose(strgl,strgl.gl);
     * }
     */

    public static void showoldglucose() {
        var noti = onenot;
        if (noti == null)
            return;
        final CurrentDisplaySource.Snapshot current = resolveNotificationCurrentSnapshot();
        if (current == null || current.getPrimaryValue() < 2.0f)
            return;
        noti.postForegroundGlucoseNotification(
                FOREGROUND_GLUCOSE_NOTIFICATION_KIND,
                current.getPrimaryValue(),
                format(usedlocale, glucoseformat, current.getPrimaryValue()),
                toLegacyGlucose(current));
    }

    void normalglucose(notGlucose strgl, float gl, float rate, boolean waiting) {
        MainActivity.showmessage = null;
        var act = MainActivity.thisone;
        if (act != null)
            act.cancelglucosedialog();
        final String message = format(usedlocale, glucoseformat, gl);
        {
            if (doLog) {
                Log.i(LOG_ID, "normalglucose waiting=" + waiting);
            }
            ;
        }
        ;
        if (waiting) {
            updateForegroundGlucoseNotification(FOREGROUND_GLUCOSE_NOTIFICATION_KIND, gl, strgl);
        }

        else if (!isWearable) {
            {
                if (doLog) {
                    Log.i(LOG_ID, "arrowglucosenotification  alertwatch=" + alertwatch + " showalways=" + showalways);
                }
                ;
            }
            ;
            if (showalways || alertwatch) {
                arrowglucosenotification(FOREGROUND_GLUCOSE_NOTIFICATION_KIND, gl, message, strgl,
                        GLUCOSENOTIFICATION,
                        !alertwatch);
            } else {
                if (hasvalue) {
                    if (keeprunning.started)
                        novalue();
                    else
                        notificationManager.cancel(glucosenotificationid);
                }
            }
        } else {
            notificationManager.cancel(glucosealarmid);
        }
    }

    NotificationManager notificationManager;

    // private static boolean isalarm=false;
    private static Runnable runstopalarm = null;
    private static ScheduledFuture<?> stopschedule = null;
    private static final long RETRY_RESHOW_GAP_MS = 10_000L;
    private static final long ALERT_EFFECT_START_GAP_MS = 750L;
    private static final Object retrySessionLock = new Object();
    private static final Object alertEffectLock = new Object();
    private static ScheduledFuture<?> retrySessionSchedule = null;
    private static AlertRetrySession activeRetrySession = null;
    private static volatile boolean alarmUiVisible = false;
    private static ScheduledFuture<?> delayedAlertEffectSchedule = null;
    private static long delayedAlertEffectGeneration = 0L;
    private static int delayedAlertEffectPriority = Integer.MIN_VALUE;
    private static AlertSoundHandle delayedAlertSoundHandle = null;
    private static long nextAlertEffectStartAllowedMs = 0L;
    private static long manualEffectBypassUntilMs = 0L;
    // "Vibrate first, audio after N seconds": the scheduled audible-alarm start.
    // Distinct from the anti-overlap debounce above; cancelled on any stop.
    private static ScheduledFuture<?> delayedSoundSchedule = null;
    private static long delayedSoundGeneration = 0L;

    private static final class AlertRetrySession {
        int kind;
        float glucoseValue;
        notGlucose glucoseSnapshot;
        String message;
        String notificationType;
        long lastFireStartedAtMs;
        int retriesUsed;

        AlertRetrySession(int kind, float glucoseValue, notGlucose glucoseSnapshot, String message,
                String notificationType, long lastFireStartedAtMs) {
            this.kind = kind;
            this.glucoseValue = glucoseValue;
            this.glucoseSnapshot = glucoseSnapshot;
            this.message = message;
            this.notificationType = notificationType;
            this.lastFireStartedAtMs = lastFireStartedAtMs;
            this.retriesUsed = 0;
        }

        void updateFrom(int kind, float glucoseValue, notGlucose glucoseSnapshot, String message,
                String notificationType) {
            this.kind = kind;
            this.glucoseValue = glucoseValue;
            this.glucoseSnapshot = glucoseSnapshot;
            this.message = message;
            this.notificationType = notificationType;
        }
    }

    private static final class AlarmLaunchResult {
        final boolean directStarted;
        final boolean queued;

        AlarmLaunchResult(boolean directStarted, boolean queued) {
            this.directStarted = directStarted;
            this.queued = queued;
        }

        boolean startedOrQueued() {
            return directStarted || queued;
        }
    }

    private static notGlucose copyGlucoseSnapshot(notGlucose glucose, float fallbackValue) {
        if (glucose == null) {
            return new notGlucose(System.currentTimeMillis(), String.valueOf(fallbackValue), Float.NaN, 0);
        }
        return new notGlucose(glucose.time, glucose.value, glucose.rate, glucose.sensorgen2);
    }

    private static String alarmDisplayGlucoseValue(float glvalue, notGlucose glucose) {
        final String glucoseFormat = pureglucoseformat != null ? pureglucoseformat : "%.1f";
        return AlertDisplayText.alarmDisplayValue(
                glvalue,
                glucose != null ? glucose.value : null,
                v -> format(usedlocale, glucoseFormat, v));
    }

    private static String resolveNotificationSensorSerial() {
        return NotificationHistorySource.resolveSensorSerial(resolvePrimarySensorName());
    }

    private static String resolveNotificationStatusText(String activeSensorSerial, String fallbackStatus) {
        try {
            final var managedSnapshot = ManagedSensorRuntime.resolveUiSnapshot(activeSensorSerial, activeSensorSerial);
            if (managedSnapshot != null) {
                final String detailed = ManagedSensorStatusPolicy.collapseSummaryStatus(managedSnapshot.getDetailedStatus());
                if (!detailed.isEmpty()) {
                    return detailed;
                }
                final String subtitle = ManagedSensorStatusPolicy.collapseSummaryStatus(managedSnapshot.getSubtitleStatus());
                if (!subtitle.isEmpty()) {
                    return subtitle;
                }
                return "";
            }
        } catch (Throwable th) {
            Log.stack(LOG_ID, "resolveNotificationStatusText", th);
        }
        // Raw GATT codes ("Status=147") are diagnostics for the bluediag
        // screen, not something to surface in the notification.
        if (fallbackStatus == null || fallbackStatus.startsWith("Status="))
            return "";
        return fallbackStatus;
    }

    private static CurrentDisplaySource.Snapshot resolveNotificationCurrentSnapshot() {
        return resolveNotificationCurrentSnapshot(resolveNotificationSensorSerial());
    }

    private static CurrentDisplaySource.Snapshot resolveNotificationCurrentSnapshot(String activeSensorSerial) {
        try {
            return CurrentDisplaySource.resolveCurrent(glucosetimeout, activeSensorSerial);
        } catch (Throwable th) {
            Log.stack(LOG_ID, "resolveNotificationCurrentSnapshot", th);
            return null;
        }
    }

    private static long latestNotificationTimestamp(List<GlucosePoint> historyPoints) {
        if (historyPoints == null || historyPoints.isEmpty()) {
            return 0L;
        }
        final GlucosePoint latest = historyPoints.get(historyPoints.size() - 1);
        return latest != null ? latest.timestamp : 0L;
    }

    private static DisplayDataState.Status resolveNotificationDataState(
            CurrentDisplaySource.Snapshot current,
            List<GlucosePoint> historyPoints,
            String activeSensorSerial) {
        return DisplayDataState.resolve(
                activeSensorSerial != null && !activeSensorSerial.isEmpty(),
                current != null ? current.getTimeMillis() : 0L,
                latestNotificationTimestamp(historyPoints));
    }

    private static notGlucose toLegacyGlucose(CurrentDisplaySource.Snapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new notGlucose(
                snapshot.getTimeMillis(),
                snapshot.getPrimaryStr(),
                snapshot.getRate(),
                snapshot.getSensorGen());
    }

    private static boolean isScreenInteractive() {
        try {
            final PowerManager powerManager = (PowerManager) Applic.app.getSystemService(Context.POWER_SERVICE);
            return powerManager != null && powerManager.isInteractive();
        } catch (Throwable th) {
            Log.stack(LOG_ID, "isScreenInteractive", th);
            return true;
        }
    }

    private final Runnable glucoseRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (!isScreenInteractive()) {
                    return;
                }
                final CurrentDisplaySource.Snapshot current = resolveNotificationCurrentSnapshot();
                if (current == null || current.getPrimaryValue() < 2.0f) {
                    return;
                }
                BatteryTrace.bump("notify.glucose.followup", 20L, "interactive=true");
                postForegroundGlucoseNotification(
                        FOREGROUND_GLUCOSE_NOTIFICATION_KIND,
                        current.getPrimaryValue(),
                        format(usedlocale, glucoseformat, current.getPrimaryValue()),
                        toLegacyGlucose(current));
            } catch (Throwable th) {
                Log.stack(LOG_ID, "glucoseRefreshRunnable", th);
            }
        }
    };

    private final Runnable dataChangedGlucoseRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (!shouldKeepForegroundGlucoseNotification()) {
                    return;
                }
                final CurrentDisplaySource.Snapshot current = resolveNotificationCurrentSnapshot();
                if (current == null || current.getPrimaryValue() < 2.0f) {
                    return;
                }
                if (isSameForegroundGlucose(current)) {
                    return;
                }
                BatteryTrace.bump("notify.glucose.data_changed", 20L, "source=" + current.getSource());
                postForegroundGlucoseNotification(
                        FOREGROUND_GLUCOSE_NOTIFICATION_KIND,
                        current.getPrimaryValue(),
                        format(usedlocale, glucoseformat, current.getPrimaryValue()),
                        toLegacyGlucose(current));
            } catch (Throwable th) {
                Log.stack(LOG_ID, "dataChangedGlucoseRefreshRunnable", th);
            }
        }
    };

    private boolean shouldKeepForegroundGlucoseNotification() {
        return !isWearable && (showalways || alertwatch || hasvalue || keeprunning.started);
    }

    private boolean isSameForegroundGlucose(CurrentDisplaySource.Snapshot current) {
        if (current == null || current.getTimeMillis() <= 0L) {
            return false;
        }
        return lastForegroundGlucoseTimeMs == current.getTimeMillis()
                && Float.compare(lastForegroundGlucoseValue, current.getPrimaryValue()) == 0
                && Float.compare(lastForegroundGlucoseRate, current.getRate()) == 0;
    }

    public static void scheduleDataChangedRefresh() {
        final Notify noti = onenot;
        if (noti == null) {
            return;
        }
        noti.scheduleDataChangedNotificationRefresh();
    }

    private void scheduleDataChangedNotificationRefresh() {
        if (!shouldKeepForegroundGlucoseNotification()) {
            return;
        }
        glucoseRefreshHandler.removeCallbacks(dataChangedGlucoseRefreshRunnable);
        glucoseRefreshHandler.postDelayed(dataChangedGlucoseRefreshRunnable, DATA_CHANGED_NOTIFICATION_REFRESH_DELAY_MS);
    }

    private void scheduleInteractiveNotificationRefresh() {
        if (!isScreenInteractive()) {
            return;
        }
        glucoseRefreshHandler.removeCallbacks(glucoseRefreshRunnable);
        glucoseRefreshHandler.postDelayed(glucoseRefreshRunnable, INTERACTIVE_NOTIFICATION_REFRESH_DELAY_MS);
    }

    private void postForegroundGlucoseNotification(int kind, float glvalue, String message, notGlucose glucose) {
        hasvalue = true;
        glucoseRefreshHandler.removeCallbacks(glucoseRefreshRunnable);
        if (glucose != null && glucose.time > 0L) {
            lastForegroundGlucoseTimeMs = glucose.time;
            lastForegroundGlucoseRate = glucose.rate;
        } else {
            lastForegroundGlucoseTimeMs = 0L;
            lastForegroundGlucoseRate = Float.NaN;
        }
        lastForegroundGlucoseValue = glvalue;
        fornotify(makearrownotification(kind, glvalue, message, glucose, GLUCOSENOTIFICATION, true));
    }

    private static final int MIN_ALERT_DURATION_SECONDS = 1;
    private static final int MAX_ALERT_DURATION_SECONDS = 60;
    private static final int DEFAULT_ALERT_DURATION_SECONDS = 5;

    private static int sanitizeAlarmDurationSeconds(int durationSeconds) {
        if (durationSeconds < MIN_ALERT_DURATION_SECONDS || durationSeconds > MAX_ALERT_DURATION_SECONDS) {
            return DEFAULT_ALERT_DURATION_SECONDS;
        }
        return durationSeconds;
    }

    public static long estimateAlertEffectDurationMs(String soundUri, int kind, boolean sound, boolean flash,
            boolean vibrate, String hapticProfileName, int configuredDurationSeconds) {
        final int sanitizedDurationSeconds = sanitizeAlarmDurationSeconds(configuredDurationSeconds);
        return TimeUnit.SECONDS.toMillis(sanitizedDurationSeconds);
    }

    private static float resolveAlarmRate(float requestedRate, boolean isHigh, boolean isTest) {
        if (Float.isFinite(requestedRate)) {
            return requestedRate;
        }
        try {
            final strGlucose latest = Natives.lastglucose();
            if (latest != null && Float.isFinite(latest.rate)) {
                return latest.rate;
            }
        } catch (Throwable th) {
            if (doLog) {
                Log.i(LOG_ID, "resolveAlarmRate latest lookup failed: " + th);
            }
        }
        if (isTest) {
            return isHigh ? 1.2f : -1.2f;
        }
        return Float.NaN;
    }

    private static void cancelRetryScheduleLocked() {
        if (retrySessionSchedule != null) {
            retrySessionSchedule.cancel(false);
            retrySessionSchedule = null;
        }
    }

    private static void cancelRetrySessionLocked(String reason) {
        if (activeRetrySession != null && doLog) {
            Log.i(LOG_ID, "Cancel retry session kind=" + activeRetrySession.kind + " reason=" + reason);
        }
        if (activeRetrySession != null) {
            cancelQueuedAlarmActivityLaunch(activeRetrySession.kind, null, reason);
        }
        cancelRetryScheduleLocked();
        activeRetrySession = null;
    }

    private static boolean isSameRetryFamily(int firstKind, int secondKind) {
        return firstKind == secondKind
                || (isLowFamilyAlert(firstKind) && isLowFamilyAlert(secondKind))
                || (isHighFamilyAlert(firstKind) && isHighFamilyAlert(secondKind));
    }

    private static long computeRetryDelayMs(AlertRetrySession session, AlertConfig config, long nowMs) {
        final long intervalMs = config.getRetryIntervalMinutes() <= 0
                ? 0L
                : TimeUnit.MINUTES.toMillis(config.getRetryIntervalMinutes());
        if (intervalMs <= 0L) {
            return RETRY_RESHOW_GAP_MS;
        }
        final long dueAtMs = session.lastFireStartedAtMs + intervalMs;
        return Math.max(RETRY_RESHOW_GAP_MS, dueAtMs - nowMs);
    }

    public static int resolveAlertKind(int fallbackKind) {
        synchronized (retrySessionLock) {
            if (activeRetrySession != null) {
                return activeRetrySession.kind;
            }
        }
        return lastalarm >= 0 ? lastalarm : fallbackKind;
    }

    public static void cancelRetrySession(int kind, String reason) {
        synchronized (retrySessionLock) {
            if (activeRetrySession != null && activeRetrySession.kind == kind) {
                cancelRetrySessionLocked(reason);
            }
        }
    }

    public static void cancelCurrentRetrySession(String reason) {
        synchronized (retrySessionLock) {
            cancelRetrySessionLocked(reason);
        }
    }

    public static void cancelAlertNotification() {
        if (onenot != null && onenot.notificationManager != null) {
            onenot.notificationManager.cancel(glucosealarmid);
        }
    }

    private static boolean dismissCustomAlertById(String customAlertId) {
        if (customAlertId == null || customAlertId.isEmpty()) {
            return false;
        }
        return CustomAlertAccess.dismissAlert(customAlertId);
    }

    public static void acknowledgeCurrentAlert() {
        acknowledgeCurrentAlert(null);
    }

    public static void acknowledgeCurrentAlert(String customAlertId) {
        if (dismissCustomAlertById(customAlertId)) {
            cancelCurrentRetrySession("notification-open-custom");
            cancelAlertNotification();
            stopalarm();
            return;
        }
        final int kind = resolveAlertKind(-1);
        boolean productionAction = true;
        if (kind >= 0) {
            final AlertType type = AlertType.Companion.fromId(kind);
            if (type != null) {
                productionAction = AlertStateTracker.INSTANCE.onAlertDismissed(type);
                if (productionAction) {
                    SnoozeManager.INSTANCE.clearSnooze(type);
                }
            }
        }
        if (productionAction) {
            cancelCurrentRetrySession("notification-open");
        }
        cancelAlertNotification();
        stopalarm();
    }

    private static void fireRetrySession(int expectedKind) {
        final AlertRetrySession sessionSnapshot;
        synchronized (retrySessionLock) {
            retrySessionSchedule = null;
            if (activeRetrySession == null || activeRetrySession.kind != expectedKind) {
                return;
            }
            final AlertType alertType = AlertType.Companion.fromId(activeRetrySession.kind);
            if (alertType == null) {
                cancelRetrySessionLocked("unknown-alert-type");
                return;
            }
            final AlertConfig config = AlertRepository.INSTANCE.loadConfig(alertType);
            if (!config.getEnabled() || !config.getRetryEnabled() || !config.isActiveNow()
                    || SnoozeManager.INSTANCE.isSnoozed(alertType)) {
                cancelRetrySessionLocked("retry-disabled-or-snoozed");
                return;
            }
            if (config.getRetryCount() != 0 && activeRetrySession.retriesUsed >= config.getRetryCount()) {
                cancelRetrySessionLocked("retry-limit-reached");
                return;
            }
            activeRetrySession.retriesUsed += 1;
            activeRetrySession.lastFireStartedAtMs = System.currentTimeMillis();
            sessionSnapshot = new AlertRetrySession(
                    activeRetrySession.kind,
                    activeRetrySession.glucoseValue,
                    copyGlucoseSnapshot(activeRetrySession.glucoseSnapshot, activeRetrySession.glucoseValue),
                    activeRetrySession.message,
                    activeRetrySession.notificationType,
                    activeRetrySession.lastFireStartedAtMs);
            sessionSnapshot.retriesUsed = activeRetrySession.retriesUsed;
        }

        if (doLog) {
            Log.i(LOG_ID, "Timed retry firing kind=" + sessionSnapshot.kind + " retry=" + sessionSnapshot.retriesUsed);
        }

        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (onenot != null) {
                onenot.deliverTriggeredAlert(
                        sessionSnapshot.kind,
                        sessionSnapshot.glucoseValue,
                        sessionSnapshot.message,
                        sessionSnapshot.glucoseSnapshot,
                        sessionSnapshot.notificationType);
            }
        });
    }

    private void syncRetrySession(int kind, float glvalue, String message, notGlucose strglucose, String type,
            AlertConfig config, boolean newFire) {
        if (config == null) {
            cancelRetrySession(kind, "missing-config");
            return;
        }
        synchronized (retrySessionLock) {
            if (!config.getRetryEnabled()) {
                if (activeRetrySession != null && isSameRetryFamily(activeRetrySession.kind, kind)) {
                    cancelRetrySessionLocked("retry-disabled-for-current-kind");
                }
                return;
            }
            final notGlucose snapshot = copyGlucoseSnapshot(strglucose, glvalue);
            if (activeRetrySession == null) {
                if (!newFire) {
                    return;
                }
                activeRetrySession = new AlertRetrySession(kind, glvalue, snapshot, message, type,
                        System.currentTimeMillis());
                if (doLog) {
                    Log.i(LOG_ID, "Created retry session kind=" + kind);
                }
                return;
            }
            if (!isSameRetryFamily(activeRetrySession.kind, kind)) {
                if (!newFire) {
                    return;
                }
                cancelRetrySessionLocked("replace-with-new-alert-family");
                activeRetrySession = new AlertRetrySession(kind, glvalue, snapshot, message, type,
                        System.currentTimeMillis());
                if (doLog) {
                    Log.i(LOG_ID, "Replaced retry session with kind=" + kind);
                }
                return;
            }
            final boolean kindChanged = activeRetrySession.kind != kind;
            activeRetrySession.updateFrom(kind, glvalue, snapshot, message, type);
            if (newFire) {
                if (kindChanged) {
                    activeRetrySession.retriesUsed = 0;
                }
                activeRetrySession.lastFireStartedAtMs = System.currentTimeMillis();
                cancelRetryScheduleLocked();
            }
        }
    }

    private static void scheduleRetryAfterStop(int kind) {
        synchronized (retrySessionLock) {
            if (activeRetrySession == null || activeRetrySession.kind != kind) {
                return;
            }
            final AlertType alertType = AlertType.Companion.fromId(activeRetrySession.kind);
            if (alertType == null) {
                cancelRetrySessionLocked("unknown-alert-type");
                return;
            }
            final AlertConfig config = AlertRepository.INSTANCE.loadConfig(alertType);
            if (!config.getEnabled() || !config.getRetryEnabled() || !config.isActiveNow()
                    || SnoozeManager.INSTANCE.isSnoozed(alertType)) {
                cancelRetrySessionLocked("retry-disabled-or-snoozed");
                return;
            }
            if (config.getRetryCount() != 0 && activeRetrySession.retriesUsed >= config.getRetryCount()) {
                cancelRetrySessionLocked("retry-limit-reached");
                return;
            }
            cancelRetryScheduleLocked();
            final long delayMs = computeRetryDelayMs(activeRetrySession, config, System.currentTimeMillis());
            retrySessionSchedule = Applic.scheduler.schedule(() -> fireRetrySession(kind), delayMs,
                    TimeUnit.MILLISECONDS);
            if (doLog) {
                Log.i(LOG_ID, "Scheduled retry kind=" + kind + " delayMs=" + delayMs + " retriesUsed="
                        + activeRetrySession.retriesUsed + " maxRetries=" + config.getRetryCount());
            }
        }
    }

    private static String resolvePrimarySensorName() {
        try {
            final String mainName = SensorIdentity.resolveMainSensor();
            if (mainName != null && !mainName.isEmpty()) {
                return mainName;
            }
            final String[] activeSensors = Natives.activeSensors();
            if (activeSensors != null && activeSensors.length > 0) {
                for (final String sensor : activeSensors) {
                    final String resolved = SensorIdentity.resolveAppSensorId(sensor);
                    if (resolved != null && !resolved.isEmpty()) {
                        return resolved;
                    }
                }
                return activeSensors[0];
            }
        } catch (Throwable th) {
            Log.stack(LOG_ID, "resolvePrimarySensorName", th);
        }
        return null;
    }

    // Package-visible: BroadcastTrendRate resolves the same view mode when
    // computing the outgoing trend.
    static int resolveSensorViewMode(String sensorName) {
        if (sensorName == null || sensorName.isEmpty()) {
            return 0;
        }
        try {
            final var managedSnapshot = ManagedSensorRuntime.resolveUiSnapshot(sensorName, sensorName);
            if (managedSnapshot != null) {
                return managedSnapshot.getViewMode();
            }
        } catch (Throwable ignored) {
        }
        if (!SensorIdentity.hasNativeSensorBacking(sensorName)) {
            return 0;
        }
        try {
            final long[] snapshot = Natives.getSensorUiSnapshot(sensorName);
            if (snapshot != null && snapshot.length >= 2) {
                return (int) snapshot[1];
            }
        } catch (Throwable th) {
            Log.stack(LOG_ID, "resolveSensorViewMode", th);
        }
        return 0;
    }

    private static boolean isAlarmUiForeground() {
        if (alarmUiVisible) {
            return true;
        }
        final MainActivity main = MainActivity.thisone;
        if (main == null || !main.active || main.isFinishing()) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && main.isDestroyed()) {
                return false;
            }
            if (!main.hasWindowFocus()) {
                return false;
            }
            final PowerManager powerManager = (PowerManager) Applic.app.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && !powerManager.isInteractive()) {
                return false;
            }
            final KeyguardManager keyguardManager = (KeyguardManager) Applic.app.getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager != null && keyguardManager.isDeviceLocked()) {
                return false;
            }
        } catch (Throwable th) {
            Log.stack(LOG_ID, "isAlarmUiForeground", th);
            return false;
        }
        return true;
    }

    private static boolean isDeviceInteractiveAndUnlocked() {
        try {
            final PowerManager powerManager = (PowerManager) Applic.app.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && !powerManager.isInteractive()) {
                return false;
            }
            final KeyguardManager keyguardManager = (KeyguardManager) Applic.app.getSystemService(Context.KEYGUARD_SERVICE);
            return keyguardManager == null || !keyguardManager.isDeviceLocked();
        } catch (Throwable th) {
            Log.stack(LOG_ID, "isDeviceInteractiveAndUnlocked", th);
            return false;
        }
    }

    private static boolean shouldTryDirectAlarmActivity() {
        return isAlarmUiForeground() || isDeviceInteractiveAndUnlocked();
    }

    static public void stopalarm() {
        stopalarmnotsend(true);
    }

    public static final String EXTRA_CUSTOM_ALERT_ID = "custom_alert_id";
    public static final String EXTRA_ALERT_DELIVERY_MODE = "alert_delivery_mode";

    public static void setAlarmUiVisible(boolean visible) {
        alarmUiVisible = visible;
    }

    private static int alertEffectPriority(int kind) {
        switch (kind) {
            case 5: // Very low
                return 100;
            case 0: // Low
                return 90;
            case 6: // Very high
                return 85;
            case 4: // Loss
            case 9: // Missed reading
                return 80;
            case 1: // High
                return 70;
            case 10: // Persistent high
                return 60;
            case 7: // Forecast low
            case 8: // Forecast high
                return 50;
            case 11: // Sensor expiry
                return 30;
            default:
                return 40;
        }
    }

    private static void stopSoundHandleQuietly(AlertSoundHandle soundHandle) {
        if (soundHandle == null || !soundHandle.isPresent()) {
            return;
        }
        try {
            soundHandle.stop();
        } catch (Throwable th) {
            Log.stack(LOG_ID, "stopSoundHandleQuietly", th);
        }
    }

    private static void cancelDelayedAlertEffectsLocked(String reason) {
        final boolean hadDelayed = delayedAlertEffectSchedule != null
                || delayedAlertSoundHandle != null
                || delayedSoundSchedule != null;
        if (delayedAlertEffectSchedule != null) {
            delayedAlertEffectSchedule.cancel(false);
            delayedAlertEffectSchedule = null;
        }
        if (delayedAlertSoundHandle != null) {
            stopSoundHandleQuietly(delayedAlertSoundHandle);
            delayedAlertSoundHandle = null;
        }
        // Cancel a pending "vibrate first, audio later" sound so it never fires
        // after a dismiss / snooze / resolution / retry.
        if (delayedSoundSchedule != null) {
            delayedSoundSchedule.cancel(false);
            delayedSoundSchedule = null;
        }
        delayedSoundGeneration++;
        delayedAlertEffectPriority = Integer.MIN_VALUE;
        delayedAlertEffectGeneration++;
        if (hadDelayed && doLog) {
            Log.i(LOG_ID, "Cancelled delayed alert effects: " + reason);
        }
    }

    private static void allowNextAlertEffectsForTest() {
        synchronized (alertEffectLock) {
            manualEffectBypassUntilMs = System.currentTimeMillis() + 5_000L;
            cancelDelayedAlertEffectsLocked("manual-test");
        }
    }

    static public void stopalarmnotsend(boolean send) {
        synchronized (alertEffectLock) {
            cancelDelayedAlertEffectsLocked("stopalarm");
        }
        final int currentAlertKind = resolveAlertKind(-1);
        if (currentAlertKind >= 0) {
            cancelQueuedAlarmActivityLaunch(currentAlertKind, null, "stopalarm");
        }
        final boolean wasAlarm = getisalarm();
        if (doLog) {
            Log.d(LOG_ID, wasAlarm ? "stopalarm is alarm" : "stopalarm not is alarm");
        }
        final var stopper = stopschedule;
        if (stopper != null) {
            stopper.cancel(false);
            stopschedule = null;
        }
        var runner = runstopalarm;
        runstopalarm = null;
        if (runner != null) {
            if (!isWearable) {
                if (send && wasAlarm)
                    Applic.app.numdata.stopalarm();
            }
            runner.run();
        } else if (!wasAlarm && onenot != null) {
            onenot.stopvibratealarm();
        }
    }
    // static int alarmnr=0;

    /**
     * Quiet window: a silenced alarm has stayed active past the breakthrough time.
     * Deliver its effects again; playringhier finds the silenced episode old
     * enough and lets sound and vibration through.
     */
    public static void breakThroughQuietWindow(int kind) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (onenot != null) {
                onenot.mksound(kind);
            }
        });
    }

    public static void playring(Ringtone ring, int duration, boolean sound, boolean flash, boolean vibrate,
            boolean disturb, int kind) {
        if (onenot == null)
            return;
        onenot.playringhier(ring, duration, sound, flash, vibrate, disturb, kind);
    }

    Vibrator vibrator = null;

    private void vibrateWaveform(Vibrator vibrator, long[] timings, int[] amplitudes, int repeatIndex) {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, repeatIndex), ScanNfcV.audioattributes);
        } else {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, repeatIndex), ScanNfcV.vibrationattributes);
        }
    }

    private void vibratealarm(int kind) {
        // Lookup profile from SharedPreferences for global alerts
        vibratealarm(kind, getHapticProfile(kind), DEFAULT_ALERT_DURATION_SECONDS);
    }

    private void vibratealarm(int kind, String hapticProfileName, int durationSeconds) {
        vibratealarm(kind, hapticProfileName, durationSeconds, 0);
    }

    private void vibratealarm(int kind, String hapticProfileName, int durationSeconds, int soundDelayLeadInSeconds) {
        var context = Applic.app;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            vibrator = ((VibratorManager) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)))
                    .getDefaultVibrator();
        } else
            vibrator = (Vibrator) context.getSystemService(VIBRATOR_SERVICE);

        if (vibrator == null) {
            if (doLog) {
                Log.w(LOG_ID, "vibratealarm: vibrator unavailable");
            }
            return;
        }

        if (hapticProfileName == null)
            hapticProfileName = "STRONG";

        float scale = 1.0f;
        boolean ascending = false;

        switch (hapticProfileName.toUpperCase()) {
            case "SOFT":
            case "LOW":
                scale = 0.45f;
                break;
            case "STEADY":
            case "MEDIUM":
                scale = 0.70f;
                break;
            case "ESCALATING":
            case "ASCENDING":
                ascending = true;
                break;
            case "SILENT":
                scale = 0.0f;
                break;
            default:
                scale = 1.0f; // STRONG, HIGH, VIBRATE_ONLY
        }

        if (scale <= 0.01f)
            return; // Silent

        // Define Patterns based on Kind (AlertType ID)
        long[] timings;
        int[] amplitudes;

        if (kind == 0) { // LOW: SOS-like (short-short-long)
            timings = new long[] { 0, 200, 100, 200, 100, 800, 200 };
            amplitudes = new int[] { 0, 255, 0, 255, 0, 255, 0 };
        } else if (kind == 1) { // HIGH: Rapid pulses
            timings = new long[] { 0, 150, 100, 150, 100, 150, 100, 150, 300 };
            amplitudes = new int[] { 0, 255, 0, 255, 0, 255, 0, 255, 0 };
        } else if (kind == 5) { // VERY_LOW: Intense, longer SOS (Urgent)
            timings = new long[] { 0, 300, 100, 300, 100, 300, 100, 1000, 200 };
            amplitudes = new int[] { 0, 255, 0, 255, 0, 255, 0, 255, 0 };
        } else if (kind == 6) { // VERY_HIGH: Double long buzz
            timings = new long[] { 0, 800, 200, 800, 500 };
            amplitudes = new int[] { 0, 255, 0, 255, 0 };
        } else if (kind == 7 || kind == 8) { // PRE_LOW / PRE_HIGH: Gentle wave
            timings = new long[] { 0, 400, 200, 400, 500 };
            amplitudes = new int[] { 0, 128, 0, 128, 0 }; // Gentler amplitude by default
        } else if (kind == 4) { // LOSS: Intermittent
            timings = new long[] { 0, 500, 1000, 500, 1000 };
            amplitudes = new int[] { 0, 200, 0, 200, 0 };
        } else { // DEFAULT (Missed Reading, etc)
            timings = new long[] { 0, 500, 200, 500, 500 };
            amplitudes = new int[] { 0, 200, 0, 200, 0 };
        }

        // Apply Scaling or Ascending Logic
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            for (int i = 0; i < amplitudes.length; i++) {
                if (amplitudes[i] > 0) {
                    if (ascending) {
                        // Simple ramp: later pulses get stronger, start at 30%
                        // Not implemented perfectly for looped, but fine for one-shot sequence
                        float progress = (float) i / amplitudes.length;
                        amplitudes[i] = (int) (amplitudes[i] * (0.3f + 0.7f * progress));
                    } else {
                        amplitudes[i] = (int) (amplitudes[i] * scale);
                    }
                    if (amplitudes[i] > 255)
                        amplitudes[i] = 255;
                    if (amplitudes[i] < 1)
                        amplitudes[i] = 1; // Ensure non-zero if it was meant to be on
                }
            }
            final AlertVibrationPattern finitePattern = AlertVibrationPattern.buildFinite(timings, amplitudes,
                    durationSeconds, soundDelayLeadInSeconds);
            vibrateWaveform(vibrator, finitePattern.timings, finitePattern.amplitudes, -1);
        } else {
            final AlertVibrationPattern finitePattern = AlertVibrationPattern.buildFinite(timings, amplitudes,
                    durationSeconds, soundDelayLeadInSeconds);
            vibrator.vibrate(finitePattern.timings, -1);
        }

        if (doLog) {
            Log.i(LOG_ID, "vibratealarm " + kind + " hapticProfile=" + hapticProfileName
                    + " duration=" + sanitizeAlarmDurationSeconds(durationSeconds)
                    + (soundDelayLeadInSeconds > 0 ? " soundDelayLeadIn=" + soundDelayLeadInSeconds : ""));
        }
    }

    void stopvibratealarm() {
        if (vibrator == null) {
            return;
        }
        try {
            vibrator.cancel();
        } catch (Throwable th) {
            Log.stack(LOG_ID, "stopvibratealarm", th);
        } finally {
            vibrator = null;
        }
    }

    private static int lastalarm = -1;

    static void stoplossalarm() {
        if (lastalarm == 4) {
            lastalarm = -1;
            stopalarm();
        }
    }

    private synchronized void playringhier(Ringtone ring, int duration, boolean sound, boolean flash, boolean vibrate,
            boolean disturb, int kind) {
        playringhier(new AlertSoundHandle(ring, null, resolveSoundTitle(null, ring)),
                duration, sound, flash, vibrate, disturb, kind, null, -1L);
    }

    private synchronized void playringhier(AlertSoundHandle soundHandle, int duration, boolean sound, boolean flash,
            boolean vibrate, boolean disturb, int kind) {
        playringhier(soundHandle, duration, sound, flash, vibrate, disturb, kind, null, -1L);
    }

    private synchronized void playringhier(AlertSoundHandle soundHandle, int duration, boolean sound, boolean flash,
            boolean vibrate, boolean disturb, int kind, String hapticProfile, long effectDurationMs) {
        playringhier(soundHandle, duration, sound, flash, vibrate, disturb, kind, hapticProfile, effectDurationMs,
                false);
    }

    /**
     * {@code customAlert}: a custom alert delivers as kind 0/1 (LOW/HIGH) and shares their
     * channels, but it is not their episode — the quiet window keeps its silenced
     * episode apart, under its own key. Nothing else here reads the flag.
     */
    private synchronized void playringhier(AlertSoundHandle soundHandle, int duration, boolean sound, boolean flash,
            boolean vibrate, boolean disturb, int kind, String hapticProfile, long effectDurationMs,
            boolean customAlert) {
        final int sanitizedDuration = sanitizeAlarmDurationSeconds(duration);
        if (sanitizedDuration != duration) {
            duration = sanitizedDuration;
            if (doLog)
                Log.i(LOG_ID, "Duration reset to default (outside 1-60s)");
        }
        final long sanitizedDurationMs = TimeUnit.SECONDS.toMillis(duration);
        final long stopDelayMs = effectDurationMs > 0L ? Math.min(sanitizedDurationMs, effectDurationMs) : sanitizedDurationMs;
        final long nowMs = System.currentTimeMillis();
        synchronized (alertEffectLock) {
            final boolean manualBypass = manualEffectBypassUntilMs >= nowMs;
            if (manualBypass) {
                manualEffectBypassUntilMs = 0L;
            } else if (nowMs < nextAlertEffectStartAllowedMs) {
                final long delayMs = nextAlertEffectStartAllowedMs - nowMs;
                final int priority = alertEffectPriority(kind);
                if (delayedAlertEffectSchedule != null && priority < delayedAlertEffectPriority) {
                    stopSoundHandleQuietly(soundHandle);
                    if (doLog) {
                        Log.i(LOG_ID, "Dropping delayed alert effects kind=" + kind
                                + " because a higher-priority effect is already queued");
                    }
                    return;
                }
                if (delayedAlertEffectSchedule != null) {
                    delayedAlertEffectSchedule.cancel(false);
                    stopSoundHandleQuietly(delayedAlertSoundHandle);
                }
                delayedAlertEffectPriority = priority;
                delayedAlertSoundHandle = soundHandle;
                final long generation = ++delayedAlertEffectGeneration;
                final Notify target = this;
                final int finalDuration = duration;
                delayedAlertEffectSchedule = Applic.scheduler.schedule(() -> {
                    synchronized (alertEffectLock) {
                        if (generation != delayedAlertEffectGeneration) {
                            return;
                        }
                        delayedAlertEffectSchedule = null;
                        delayedAlertSoundHandle = null;
                        delayedAlertEffectPriority = Integer.MIN_VALUE;
                    }
                    target.playringhier(soundHandle, finalDuration, sound, flash, vibrate, disturb, kind,
                            hapticProfile, effectDurationMs, customAlert);
                }, delayMs, TimeUnit.MILLISECONDS);
                if (doLog) {
                    Log.i(LOG_ID, "Delaying alert effects kind=" + kind + " by " + delayMs + "ms");
                }
                return;
            }
            nextAlertEffectStartAllowedMs = Math.max(nextAlertEffectStartAllowedMs,
                    nowMs + ALERT_EFFECT_START_GAP_MS);
        }

        // Quiet window: a temporary, self-expiring cut of sound (and, in
        // notification-only mode, vibration) decided by AlertDeliveryPolicy. A
        // silenced alarm that stays active past the breakthrough time sounds as if
        // there were no window. Nothing else here changes.
        final long quietNowMs = System.currentTimeMillis();
        final boolean quietWindow = QuietWindow.untilMs(quietNowMs) > 0L;
        boolean quietBreakThrough = false;
        String quietMode = null;
        if (quietWindow) {
            // A custom alert is not LOW/HIGH's episode; it is tracked under its own key.
            final int episodeKind = customAlert ? QuietWindow.customEpisodeKind(kind) : kind;
            final long silencedSinceMs = QuietWindow.noteSilencedDelivery(episodeKind, quietNowMs);
            quietBreakThrough = AlertDeliveryPolicy.quietWindowBreakthroughAppliesTo(kind,
                    QuietWindow.breakthroughScope())
                    && AlertDeliveryPolicy.quietWindowBreaksThrough(silencedSinceMs, quietNowMs,
                            QuietWindow.breakthroughMillis());
            quietMode = QuietWindow.mode();
            if (doLog) {
                Log.i(LOG_ID, "Quiet window: kind=" + kind + " silencedSince=" + silencedSinceMs
                        + (quietBreakThrough ? " -> breaking through" : " -> sound off"));
            }
        }
        final boolean quietSilencesSound = AlertDeliveryPolicy.shouldSilenceSound(quietWindow, kind,
                quietBreakThrough);
        final boolean quietSuppressesVibration = AlertDeliveryPolicy.shouldSuppressVibration(quietWindow,
                quietMode, kind, quietBreakThrough);

        notifyfocus = true;
        doTurnFocuson();
        stopalarm();
        // final int[] curfilter={-1};
        final boolean glucosealarm = kind < 2 || kind > 4;
        if (!DontTalk) {
            // Speech is sound: a quiet window silences it too.
            if (glucosealarm && Natives.speakalarms() && !quietSilencesSound) {
                final CurrentDisplaySource.Snapshot current = resolveNotificationCurrentSnapshot();
                if (current != null) {
                    SuperGattCallback.talker.speak(current.getSpeechPrimaryStr(),
                            disturb ? ScanNfcV.audioattributes : notification_audio);
                }
            }
        }
        final boolean[] doplaysound = { true };
        final boolean hasSoundHandle = soundHandle != null && soundHandle.isPresent();
        if (sound && quietSilencesSound) {
            // Quiet window: no sound, and no DND override for a sound that will not play.
            doplaysound[0] = false;
        } else if (sound) {
            if (!hasSoundHandle) {
                doplaysound[0] = false;
                if (doLog) {
                    Log.w(LOG_ID, "playringhier: no playable sound handle");
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int filt = notificationManager.getCurrentInterruptionFilter();
                if (doLog) {
                    Log.i(LOG_ID, "getCurrentInterruptionFilter()=" + filt + " disturb=" + disturb);
                }

                if (filt != NotificationManager.INTERRUPTION_FILTER_ALL) {
                    // Phone is in some DND mode
                    if (disturb) {
                        // Override DND if we have permission
                        if (notificationManager.isNotificationPolicyAccessGranted()) {
                            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                        }
                    } else {
                        // Don't disturb - skip sound
                        doplaysound[0] = false;
                        if (doLog) {
                            Log.i(LOG_ID, "Skipping sound due to DND and disturb=false");
                        }
                    }
                }
            }
            if (doLog && doplaysound[0] && hasSoundHandle) {
                Log.d(LOG_ID, "play " + soundHandle.getTitle());
            }
        }
        runstopalarm = () -> {
            boolean wasAlarm = getisalarm(); // Check state BEFORE resetting it
            if (wasAlarm) {
                notifyfocus = false;
                lastalarm = -1; // Now safe to reset

                {
                    if (doLog) {
                        Log.d(LOG_ID, "runstopalarm  isalarm");
                    }
                    ;
                }
                ;
                if (sound) {
                    if (hasSoundHandle) {
                        try {
                            if (doLog) {
                                {
                                    if (doLog) {
                                        Log.d(LOG_ID, "stop sound " + soundHandle.getTitle());
                                    }
                                    ;
                                }
                                ;
                            }
                            soundHandle.stop();
                        } catch (Throwable th) {
                            Log.stack(LOG_ID, "soundHandle.stop()", th);
                        }
                    }
                }

                if (!isWearable) {
                    if (flash)
                        Flash.stop();
                }
                if (vibrate) {
                    stopvibratealarm();
                }
                if (!DontTalk) {
                    if (glucosealarm && Natives.speakalarms() && !quietSilencesSound) {
                        final CurrentDisplaySource.Snapshot current = resolveNotificationCurrentSnapshot();
                        if (current != null) {
                            Applic.scheduler.schedule(
                                    () -> SuperGattCallback.talker.speak(current.getSpeechPrimaryStr(),
                                            disturb ? ScanNfcV.audioattributes : notification_audio),
                                    300, TimeUnit.MILLISECONDS);
                        } else
                            doTurnFocusoff();
                    } else
                        doTurnFocusoff();
                    // overwriteglucose();
                } else
                    doTurnFocusoff();

                if (glucosealarm)
                    overwriteglucose(kind);
                setisalarm(false);
                scheduleRetryAfterStop(kind);

            } else {
                if (sound && hasSoundHandle) {
                    soundHandle.stop();
                }
                if (!isWearable && flash) {
                    Flash.stop();
                }
                if (vibrate) {
                    stopvibratealarm();
                }
                if (doLog) {
                    {
                        if (doLog) {
                            Log.d(LOG_ID, "runstopalarm not isalarm " + (hasSoundHandle ? soundHandle.getTitle() : "alert sound"));
                        }
                        ;
                    }
                    ;
                }
            }
        };
        lastalarm = kind;
        setisalarm(true);
        {
            if (doLog) {
                Log.d(LOG_ID, "schedule stop afterMs=" + stopDelayMs);
            }
            ;
        }
        ;

        // MOVED EFFECTS START HERE - SAFER
        final String resolvedHapticProfile = (hapticProfile != null) ? hapticProfile : getHapticProfile(kind);

        // Sound delay ("vibrate first, audio after N seconds"): start vibration
        // and flash immediately; add the audible alarm only after the configured
        // delay. Only when both sound and vibration are on for this alert (else
        // there is either nothing to delay, or a silent gap with no signal).
        // A sound the quiet window silences has nothing to delay.
        final int soundDelaySeconds = (sound && vibrate && !quietSilencesSound) ? getSoundDelaySeconds(kind) : 0;
        final long soundDelayMs = TimeUnit.SECONDS.toMillis(soundDelaySeconds);
        final boolean canPlaySound = sound && doplaysound[0] && hasSoundHandle && soundHandle != null;

        if (!isWearable) {
            if (flash) {
                Flash.start(app, 200L);
            }
        }
        // Quiet window in notification-only mode: no vibration either.
        if (vibrate && !quietSuppressesVibration) {
            // The finite pattern must also span the silent delay phase
            // ("vibrate first"), else it runs out before the sound starts.
            vibratealarm(kind, resolvedHapticProfile, duration, soundDelaySeconds);
        }

        if (canPlaySound) {
            soundHandle.setMaxVolume(soundVolumeForProfile(resolvedHapticProfile));
            if (soundDelayMs > 0L) {
                // Delay only the play() call; the gradual fade-in lives inside
                // play() and carries over unchanged, just starting later.
                final AlertSoundHandle delayedHandle = soundHandle;
                final long soundGeneration;
                synchronized (alertEffectLock) {
                    soundGeneration = ++delayedSoundGeneration;
                    delayedSoundSchedule = Applic.scheduler.schedule(() -> {
                        synchronized (alertEffectLock) {
                            if (soundGeneration != delayedSoundGeneration) {
                                return;
                            }
                            delayedSoundSchedule = null;
                        }
                        // Only sound if still alarming (not dismissed / snoozed /
                        // resolved during the delay); play() is also a no-op once
                        // the handle has been released.
                        if (getisalarm()) {
                            delayedHandle.play();
                        }
                    }, soundDelayMs, TimeUnit.MILLISECONDS);
                }
                if (doLog) {
                    Log.i(LOG_ID, "Sound delayed by " + soundDelaySeconds + "s for kind=" + kind);
                }
            } else {
                soundHandle.play();
            }
        } else if (sound && soundHandle == null && doLog) {
            Log.w(LOG_ID, "playringhier: sound handle is null");
        }

        // Auto-stop counts the sound's play time from when it actually begins, so
        // a delayed sound still plays for its full duration.
        stopschedule = Applic.scheduler.schedule(runstopalarm, stopDelayMs + soundDelayMs, TimeUnit.MILLISECONDS);

    }
    private String getDeliveryMode(int kind) {
        if (AlertType.Companion.isLegacyOnlyId(kind)) {
            return AlertDeliveryPolicy.NOTIFICATION_ONLY;
        }
        try {
            android.content.SharedPreferences prefs = Applic.app.getSharedPreferences("tk.glucodata.alerts",
                    android.content.Context.MODE_PRIVATE);
            String key = "alert_" + kind + "_delivery";
            String mode = prefs.getString(key, AlertDeliveryPolicy.SYSTEM_ALARM);
            if (doLog) {
                Log.d(LOG_ID, "getDeliveryMode kind=" + kind + " key=" + key + " val=" + mode);
            }
            return mode;
        } catch (Exception e) {
            return AlertDeliveryPolicy.SYSTEM_ALARM;
        }
    }

    private static String normalizeDeliveryMode(String deliveryMode) {
        return AlertDeliveryPolicy.normalize(deliveryMode);
    }

    private String getHapticProfile(int kind) {
        try {
            android.content.SharedPreferences prefs = Applic.app.getSharedPreferences("tk.glucodata.alerts",
                    android.content.Context.MODE_PRIVATE);
            if (prefs.contains("alert_" + kind + "_haptic")) {
                return prefs.getString("alert_" + kind + "_haptic", "STRONG");
            }
            final String legacyProfile = prefs.getString("alert_" + kind + "_volume", "HIGH");
            if (legacyProfile == null) {
                return "STRONG";
            }
            switch (legacyProfile.toUpperCase()) {
                case "MEDIUM":
                    return "STEADY";
                case "ASCENDING":
                    return "ESCALATING";
                case "LOW":
                    return "SOFT";
                case "SILENT":
                    return "SILENT";
                case "HIGH":
                case "VIBRATE_ONLY":
                default:
                    return "STRONG";
            }
        } catch (Exception e) {
            return "STRONG";
        }
    }

    // SOFT=0.4, STEADY=0.7, STRONG/ESCALATING=1.0 — matches the old dead getVolumeFromProfile() intent.
    // Never returns below AlertSoundHandle.MIN_PLAY_VOLUME (enforced again in setMaxVolume as a safety net).
    private static float soundVolumeForProfile(String profile) {
        if (profile == null) return 1.0f;
        switch (profile.toUpperCase()) {
            case "SOFT":      case "LOW":      return 0.4f;
            case "STEADY":    case "MEDIUM":   return 0.7f;
            default:                           return 1.0f;
        }
    }

    // Resolved audible-alarm delay for this alert type, in seconds (0 = none).
    // Re-applies the per-type hypo cap via the single source of truth in
    // AlertConfig, independent of how the value was written.
    private int getSoundDelaySeconds(int kind) {
        final AlertType type = AlertType.Companion.fromId(kind);
        if (type == null) {
            return 0;
        }
        try {
            final android.content.SharedPreferences prefs = Applic.app.getSharedPreferences(
                    "tk.glucodata.alerts", android.content.Context.MODE_PRIVATE);
            if (!prefs.getBoolean("alert_" + kind + "_soundDelayEnabled", false)) {
                return 0;
            }
            final int requested = prefs.getInt("alert_" + kind + "_soundDelay", 0);
            return tk.glucodata.alerts.AlertConfigKt.sanitizeSoundDelaySeconds(type, requested);
        } catch (Exception e) {
            return 0;
        }
    }

    void mksound(int kind) {
        String ringUri = null;
        try {
            android.content.SharedPreferences prefs = Applic.app.getSharedPreferences("tk.glucodata.alerts",
                    android.content.Context.MODE_PRIVATE);
            ringUri = prefs.getString("alert_" + kind + "_soundUri", null);
        } catch (Exception e) {
            if (doLog)
                Log.i(LOG_ID, "Error reading custom sound pref: " + e.toString());
        }

        if (ringUri == null || ringUri.isEmpty()) {
            ringUri = Natives.readring(kind);
        }

        // Read settings from Prefs (AlertRepository) to support new Alert Types that
        // Natives doesn't know about
        android.content.SharedPreferences p = Applic.app.getSharedPreferences("tk.glucodata.alerts",
                android.content.Context.MODE_PRIVATE);

        int defDuration = DEFAULT_ALERT_DURATION_SECONDS;

        boolean defSound = (kind <= 8) ? Natives.alarmhassound(kind) : true;
        boolean defFlash = (kind <= 8) ? Natives.alarmhasflash(kind) : true;
        boolean defVibrate = (kind <= 8) ? Natives.alarmhasvibration(kind) : true;

        final int rawDuration = p.getInt("alert_" + kind + "_alarmDur", defDuration);
        final int duration = sanitizeAlarmDurationSeconds(rawDuration);
        final boolean flash = p.getBoolean("alert_" + kind + "_flash", defFlash);
        final boolean sound = p.getBoolean("alert_" + kind + "_sound", defSound);
        final boolean vibration = p.getBoolean("alert_" + kind + "_vibration", defVibrate);

        final boolean dist = isWearable || p.getBoolean("alert_" + kind + "_dnd", getalarmdisturb(kind));
        final boolean useAlarmStream = shouldUseAlarmAudioStream(kind, dist);
        final AlertSoundHandle soundHandle = sound ? buildAlertSoundHandle(ringUri, kind, useAlarmStream) : null;

        // DEBUG LOGGING
        Log.i(LOG_ID, "mksound DEBUG: kind=" + kind + " ring=" + (soundHandle != null ? soundHandle.getTitle() : "NULL")
                + " duration=" + duration + (rawDuration == duration ? "" : " rawDuration=" + rawDuration)
                + " sound=" + sound + " flash=" + flash + " vibration=" + vibration
                + " alarmStream=" + useAlarmStream);

        final String hapticProfileName = getHapticProfile(kind);
        final long effectDurationMs = estimateAlertEffectDurationMs(ringUri, kind, sound, flash, vibration,
                hapticProfileName, duration);
        playringhier(soundHandle, duration, sound, flash, vibration, dist, kind, hapticProfileName, effectDurationMs);
    }

    /**
     * Test an alarm type by triggering the full alarm flow with dummy data.
     */
    private static long lastTestTime = 0;

    public static void testTrigger(int kind) {
        long now = System.currentTimeMillis();
        if (now - lastTestTime < 2000)
            return; // Debounce 2s
        lastTestTime = now;

        // Run on main thread to be safe with UI/Toasts
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            boolean isMmol = tk.glucodata.Applic.unit == 1;
            final AlertType alertType = AlertType.Companion.fromId(kind);
            final AlertConfig config = alertType != null ? AlertRepository.INSTANCE.loadConfig(alertType) : null;
            final CurrentDisplaySource.Snapshot current = resolveNotificationCurrentSnapshot();
            final float currentDisplayValue = current != null ? current.getPrimaryValue() : Float.NaN;
            final float dummyValue = AlertTestValuePolicy.resolve(
                    alertType,
                    config,
                    isMmol,
                    currentDisplayValue);
            final String typeStr = alertChannelForKind(kind);
            final String label = alertType != null
                    ? Applic.app.getString(alertType.getNameResId())
                    : "Test Alert";
            final String message = alertType == AlertType.LOSS
                    ? label
                    : label + " " + glucosestr(dummyValue);

            if (onenot != null) {
                if (alertType != null) {
                    AlertStateTracker.INSTANCE.allowNextTriggerForTest(alertType);
                }
                allowNextAlertEffectsForTest();

                if (kind == 4) {
                    onenot.lossofsignalalarm(kind, R.drawable.loss, message, typeStr, true);
                } else {
                    notGlucose dummyGlucose = new notGlucose(System.currentTimeMillis(), String.valueOf(dummyValue), 0f,
                            0);
                    onenot.arrowglucosealarm(kind, dummyValue, message, dummyGlucose, typeStr, true);
                }
            }
        });
    }

    /**
     * Trigger a Custom Alert. Called from CustomAlertManager when a custom
     * threshold is crossed.
     * Uses the existing alarm flow via playringhier for guaranteed stop.
     */

    public static void triggerCustomAlert(String soundUri, boolean sound, boolean vibrate, boolean flash,
            boolean isHigh, float glucoseValue, String deliveryMode, String hapticProfile,
            int durationSeconds, boolean overrideDnd, String customAlertId, String customAlertName, float rate) {
        triggerCustomAlertInternal(soundUri, sound, vibrate, flash, isHigh, glucoseValue, false, deliveryMode,
                hapticProfile, durationSeconds, overrideDnd, customAlertId, customAlertName, rate);
    }

    public static void testCustomTrigger(String soundUri, boolean sound, boolean vibrate, boolean flash,
            boolean isHigh, String deliveryMode, String hapticProfile, int durationSeconds,
            boolean overrideDnd, String customAlertId, String customAlertName) {
        boolean isMmol = tk.glucodata.Applic.unit == 1;
        float dummyValue = isHigh ? (isMmol ? 12.0f : 216f) : (isMmol ? 3.5f : 63f);
        triggerCustomAlertInternal(soundUri, sound, vibrate, flash, isHigh, dummyValue, true, deliveryMode,
                hapticProfile, durationSeconds, overrideDnd, customAlertId, customAlertName, Float.NaN);
    }

    private static long lastCustomTriggerTime = 0;

    private static void triggerCustomAlertInternal(String soundUri, boolean sound, boolean vibrate, boolean flash,
            boolean isHigh, float glucoseValue, boolean isTest, String deliveryMode,
            String hapticProfile, int durationSeconds, boolean overrideDnd, String customAlertId,
            String customAlertName, float rate) {

        long now = System.currentTimeMillis();
        // Debounce only for test mode to prevent accidental double-clicks
        if (isTest) {
            if (now - lastCustomTriggerTime < 2000)
                return;
            allowNextAlertEffectsForTest();
        }
        lastCustomTriggerTime = now;

        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (onenot != null) {
                int kind = isHigh ? 1 : 0;

                final String defaultName = isTest
                        ? ("Test Custom " + (isHigh ? "High" : "Low"))
                        : (isHigh ? "Custom High" : "Custom Low");
                final String message = (customAlertName != null && !customAlertName.isBlank())
                        ? customAlertName
                        : defaultName;

                String typeStr = alertChannelForKind(kind);
                final float resolvedRate = resolveAlarmRate(rate, isHigh, isTest);
                notGlucose glucoseStr = new notGlucose(System.currentTimeMillis(), String.valueOf(glucoseValue),
                        resolvedRate, 0);

                // Delivery Mode Logic
                String mode = normalizeDeliveryMode(deliveryMode);
                AlarmLaunchResult alarmLaunchResult = new AlarmLaunchResult(false, false);

                // When the phone is idle/locked, background activity starts are not a
                // reliable contract. Queue the AlarmActivity through AlarmManager so
                // Alarm mode still wakes into the alarm screen instead of degrading to a
                // plain heads-up notification.
                if (AlertDeliveryPolicy.shouldAttemptAlarmWindow(mode)) {
                    final String alarmGlucoseValue = alarmDisplayGlucoseValue(glucoseValue, glucoseStr);
                    alarmLaunchResult = launchOrQueueAlarmActivityResult(alarmGlucoseValue, message, glucoseStr.rate,
                            kind, customAlertId, mode);
                    if (!alarmLaunchResult.startedOrQueued() && doLog) {
                        Log.i(LOG_ID, "Custom Alert: AlarmActivity launch failed, using notification fallback");
                    }
                }

                boolean skipBanner = !AlertDeliveryPolicy.shouldPostAlertNotification(
                        mode,
                        alarmLaunchResult.startedOrQueued());

                // 2. Heads-Up Notification (Separate) - This is what standard alerts do!
                // Only if we shouldn't skip the banner (Notification mode, Both mode, or Alarm
                // mode failure)
                if (!skipBanner) {
                    onenot.makeseparatenotification(glucoseValue, message, glucoseStr, typeStr, kind, mode,
                            customAlertId);
                }

                // 3. Sound/Flash/Vibrate - Use standard playringhier (handles repeats, stop,
                // etc.)
                if (sound || flash || vibrate) {
                    String actualUri;
                    if (soundUri == null || soundUri.isEmpty()) {
                        actualUri = Natives.readring(kind);
                    } else {
                        actualUri = soundUri;
                    }

                    final int finalDuration = sanitizeAlarmDurationSeconds(
                            durationSeconds > 0 ? durationSeconds : DEFAULT_ALERT_DURATION_SECONDS);
                    boolean disturb = isWearable || overrideDnd;

                    Log.i(LOG_ID, "Custom Alert DND check: overrideDnd=" + overrideDnd + " isWearable=" + isWearable
                            + " disturb=" + disturb + " haptic=" + hapticProfile);

                    final boolean useAlarmStream = AlertDeliveryPolicy.shouldUseAlarmAudioStream(mode, disturb);
                    AlertSoundHandle soundHandle = onenot.buildAlertSoundHandle(actualUri, kind, useAlarmStream);
                    final long effectDurationMs = estimateAlertEffectDurationMs(actualUri, kind, sound, flash,
                            vibrate, hapticProfile, finalDuration);

                    onenot.playringhier(soundHandle, finalDuration, sound, flash, vibrate, disturb, kind,
                            hapticProfile, effectDurationMs, true);

                    if (doLog)
                        Log.i(LOG_ID, "Custom Alert: sound=" + sound + " flash=" + flash + " vibrate=" + vibrate
                                + " duration=" + finalDuration + " disturb=" + disturb
                                + " alarmStream=" + useAlarmStream + " haptic=" + hapticProfile);
                }

                sendLegacyWatchAlert(kind, glucoseValue, message, glucoseStr);

                // Keep the regular ongoing glucose notification on its normal surface instead of
                // turning it into a second custom-alert card.
                onenot.updateForegroundGlucoseNotification(kind, glucoseValue, glucoseStr);
            }
        });
    }

    private static void setmessage(String message, Boolean cancel) {
        {
            if (doLog) {
                Log.i(LOG_ID, "setmessage " + message + " " + cancel);
            }
            ;
        }
        ;
        if (cancel) {
            MainActivity.showmessage = message;
        } else {
            MainActivity.shownummessage.push(message);
        }
    }

    private static Intent buildAlarmActivityIntent(String glucoseValue, String alarmMessage, float rate, int alertTypeId,
            String customAlertId, String deliveryMode) throws ClassNotFoundException {
        Class<?> alarmClass = Class.forName("tk.glucodata.ui.AlarmActivity");
        Intent alarmIntent = new Intent(Applic.app, alarmClass);
        alarmIntent.putExtra("EXTRA_GLUCOSE_VAL", glucoseValue);
        alarmIntent.putExtra("EXTRA_ALARM_TYPE", "ALARM");
        alarmIntent.putExtra("EXTRA_ALARM_MESSAGE", alarmMessage);
        alarmIntent.putExtra("EXTRA_ALERT_TYPE_ID", alertTypeId);
        alarmIntent.putExtra("EXTRA_RATE", rate);
        if (deliveryMode != null && !deliveryMode.isEmpty()) {
            alarmIntent.putExtra(EXTRA_ALERT_DELIVERY_MODE, deliveryMode);
        }
        if (customAlertId != null && !customAlertId.isEmpty()) {
            alarmIntent.putExtra(EXTRA_CUSTOM_ALERT_ID, customAlertId);
        }
        return alarmIntent;
    }

    private static PendingIntent mkAlarmPendingIntent(String glucoseValue, String alarmMessage, float rate,
            int alertTypeId, String customAlertId, String deliveryMode) throws ClassNotFoundException {
        Intent alarmIntent = buildAlarmActivityIntent(glucoseValue, alarmMessage, rate, alertTypeId, customAlertId,
                deliveryMode);
        alarmIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(Applic.app, alarmPendingRequestCode(100_000, alertTypeId, customAlertId),
                alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT | penmutable);
    }

    private static int alarmPendingRequestCode(int base, int alertTypeId, String customAlertId) {
        final int customHash = customAlertId == null ? 0 : customAlertId.hashCode();
        return base + (alertTypeId * 257) + (customHash & 0x7FFF);
    }

    private static Intent buildAlarmLaunchReceiverIntent(Intent alarmIntent) {
        final Intent launchIntent = new Intent(Applic.app, tk.glucodata.receivers.AlarmLaunchReceiver.class);
        launchIntent.setAction(tk.glucodata.receivers.AlarmLaunchReceiver.ACTION_LAUNCH_ALARM_ACTIVITY);
        if (alarmIntent != null) {
            launchIntent.putExtras(alarmIntent);
        }
        return launchIntent;
    }

    private static boolean queueAlarmActivityLaunch(String glucoseValue, String alarmMessage, float rate,
            int alertTypeId, String customAlertId, String deliveryMode) {
        try {
            final AlarmManager alarmManager = (AlarmManager) Applic.app.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                return false;
            }
            final Intent alarmIntent = buildAlarmActivityIntent(glucoseValue, alarmMessage, rate, alertTypeId,
                    customAlertId, deliveryMode);
            final Intent launchIntent = buildAlarmLaunchReceiverIntent(alarmIntent);
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(Applic.app,
                    alarmPendingRequestCode(200_000, alertTypeId, customAlertId), launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | penmutable);
            final long triggerAtMs = System.currentTimeMillis() + LOCKED_ALARM_ACTIVITY_DELAY_MS;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent);
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent);
                }
            } catch (SecurityException denied) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    final PendingIntent showIntent = mkAlarmPendingIntent(glucoseValue, alarmMessage, rate, alertTypeId,
                            customAlertId, deliveryMode);
                    alarmManager.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAtMs, showIntent),
                            pendingIntent);
                } else {
                    throw denied;
                }
            }
            if (doLog) {
                Log.i(LOG_ID, "Queued AlarmActivity launch kind=" + alertTypeId + " mode=" + deliveryMode);
            }
            return true;
        } catch (Throwable e) {
            if (doLog) {
                Log.e(LOG_ID, "queueAlarmActivityLaunch failed: " + e.toString());
            }
            return false;
        }
    }

    public static void cancelQueuedAlarmActivityLaunch(int alertTypeId, String customAlertId, String reason) {
        if (alertTypeId < 0) {
            return;
        }
        try {
            final AlarmManager alarmManager = (AlarmManager) Applic.app.getSystemService(Context.ALARM_SERVICE);
            final Intent launchIntent = buildAlarmLaunchReceiverIntent(null);
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(Applic.app,
                    alarmPendingRequestCode(200_000, alertTypeId, customAlertId), launchIntent,
                    PendingIntent.FLAG_NO_CREATE | penmutable);
            if (pendingIntent == null) {
                return;
            }
            if (alarmManager != null) {
                alarmManager.cancel(pendingIntent);
            }
            pendingIntent.cancel();
            if (doLog) {
                Log.i(LOG_ID, "Cancelled queued AlarmActivity launch kind=" + alertTypeId
                        + " reason=" + reason);
            }
        } catch (Throwable th) {
            Log.stack(LOG_ID, "cancelQueuedAlarmActivityLaunch", th);
        }
    }

    private static AlarmLaunchResult launchOrQueueAlarmActivityResult(String glucoseValue, String alarmMessage,
            float rate, int alertTypeId, String customAlertId, String deliveryMode) {
        final boolean directStartIsReliable = shouldTryDirectAlarmActivity();
        final boolean directStarted = AlarmLaunchStrategy.shouldAttemptDirectStart()
                && showpopupalarm(glucoseValue, alarmMessage, rate, alertTypeId, customAlertId, deliveryMode);
        if (!AlarmLaunchStrategy.shouldQueueBackup(directStartIsReliable)) {
            return new AlarmLaunchResult(directStarted, false);
        }
        final boolean queued = queueAlarmActivityLaunch(glucoseValue, alarmMessage, rate, alertTypeId,
                customAlertId, deliveryMode);
        return new AlarmLaunchResult(directStarted, queued);
    }

    private static boolean showpopupalarm(String glucoseValue, String alarmMessage, float rate, int alertTypeId) {
        return showpopupalarm(glucoseValue, alarmMessage, rate, alertTypeId, null, null);
    }

    private static boolean showpopupalarm(String glucoseValue, String alarmMessage, float rate, int alertTypeId,
            String customAlertId, String deliveryMode) {
        MainActivity.showmessage = null;
        try {
            Intent alarmIntent = buildAlarmActivityIntent(glucoseValue, alarmMessage, rate, alertTypeId,
                    customAlertId, deliveryMode);
            alarmIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            Applic.app.startActivity(alarmIntent);
            return true;
        } catch (Throwable e) {
            if (doLog)
                Log.e(LOG_ID, "showpopupalarm failed: " + e.toString());
            return false;
        }
    }

    private void soundalarm(int kind, int draw, String message, String type, boolean alarm) {
        if (alarm) {
            {
                if (doLog) {
                    Log.d(LOG_ID, "soundalarm " + kind);
                }
                ;
            }
            ;
            mksound(kind);
        }
        placelargenotification(draw, message, type, !alarm);
    }

    // private int wasdraw=-1;
    private float wasvalue = 0.0f;
    private String wasmessage = null, wastype;
    void overwriteglucose(int kind) {
        // if(wasdraw==-1) return;
        if (wasvalue < 0.1f)
            return;
        var strgl = toLegacyGlucose(resolveNotificationCurrentSnapshot());
        if (strgl == null)
            return;
        arrowglucosenotification(kind, wasvalue, wasmessage, strgl, wastype, true);
        wasvalue = 0.0f;
    }

    private void updateForegroundGlucoseNotification(int kind, float glvalue, notGlucose glucose) {
        final String currentMessage = format(usedlocale, glucoseformat, glvalue);
        postForegroundGlucoseNotification(kind, glvalue, currentMessage, glucose);
        // Live glucose is persisted asynchronously. Repaint once shortly after the
        // direct update so notification formatting/chart/rate can catch the Room write.
        scheduleInteractiveNotificationRefresh();
    }

    private void arrowsoundalarm(int kind, float glvalue, String message, notGlucose sglucose, String type,
            boolean alarm, boolean skipBanner) {
        if (alarm) {
            wasvalue = 0.0f;
            wasmessage = null;
            wastype = null;
            if (!skipBanner) {
                makeseparatenotification(glvalue, message, sglucose, type, kind);
            }
            {
                if (doLog) {
                    Log.d(LOG_ID, "arrowsoundalarm " + kind + " skipBanner=" + skipBanner);
                }
                ;
            }
            ;
            mksound(kind);
        }
        // A message-only alert (no reading available) must not repaint the
        // persistent glucose notification with a non-value.
        if (Float.isFinite(glvalue)) {
            updateForegroundGlucoseNotification(kind, glvalue, sglucose);
        }
    }

    private void deliverTriggeredAlert(int kind, float glvalue, String message, notGlucose strglucose, String type) {
        AlarmLaunchResult alarmLaunchResult = new AlarmLaunchResult(false, false);
        boolean skipBanner = false;

        if (!AlertType.Companion.isLegacyOnlyId(kind)) {
            String deliveryMode = normalizeDeliveryMode(getDeliveryMode(kind));

            boolean forceLaunch = AlertDeliveryPolicy.shouldAttemptAlarmWindow(deliveryMode);

            if (doLog) {
                Log.i(LOG_ID, String.format("Alert Debug: kind=%d deliveryMode=%s forceLaunch=%b", kind,
                        deliveryMode, forceLaunch));
            }

            if (forceLaunch) {
                float rate = (strglucose != null) ? strglucose.rate : Float.NaN;
                final String alarmGlucoseValue = alarmDisplayGlucoseValue(glvalue, strglucose);
                alarmLaunchResult = launchOrQueueAlarmActivityResult(alarmGlucoseValue, message, rate, kind, null,
                        deliveryMode);
                if (doLog)
                    Log.i(LOG_ID, "Alert Debug: alarm window started=" + alarmLaunchResult.startedOrQueued()
                            + " direct=" + alarmLaunchResult.directStarted
                            + " queued=" + alarmLaunchResult.queued);
                if (!alarmLaunchResult.startedOrQueued() && doLog) {
                    Log.i(LOG_ID, "Alert Debug: AlarmActivity launch failed, using notification fallback");
                }
            }

            skipBanner = !AlertDeliveryPolicy.shouldPostAlertNotification(
                    deliveryMode,
                    alarmLaunchResult.startedOrQueued());
            if (doLog)
                Log.i(LOG_ID, "Alert Debug: skipBanner=" + skipBanner);
        }

        arrowsoundalarm(kind, glvalue, message, strglucose, type, true, skipBanner);
        sendLegacyWatchAlert(kind, glvalue, message, strglucose);
    }

    private void lossofsignalalarm(int kind, int draw, String message, String type, boolean alarm) {
        {
            if (doLog) {
                Log.i(LOG_ID, "glucose alarm kind=" + kind + " " + message + " alarm=" + alarm);
            }
            ;
        }
        ;
        if (alarm) {
            if (!AlertType.Companion.isLegacyOnlyId(kind)) {
                String deliveryMode = normalizeDeliveryMode(getDeliveryMode(kind));
                boolean isSystem = AlertDeliveryPolicy.SYSTEM_ALARM.equals(deliveryMode);
                boolean isBoth = AlertDeliveryPolicy.BOTH.equals(deliveryMode);

                if (isSystem || isBoth) {
                    launchOrQueueAlarmActivityResult(message, message, Float.NaN, kind, null, deliveryMode);
                }
            }
        } else {
            final var act = MainActivity.thisone;
            if (act != null) {
                {
                    if (doLog) {
                        Log.i(LOG_ID, "act!=null");
                    }
                    ;
                }
                ;
                act.replaceDialogMessage(message);
            }
            {
                if (doLog) {
                    Log.i(LOG_ID, "act==null");
                }
                ;
            }
            ;
            if (MainActivity.showmessage != null)
                MainActivity.showmessage = message;
        }
        if (!alarm && alertwatch)
            lossofsensornotification(draw, message, GLUCOSENOTIFICATION, false);
        else
            soundalarm(kind, draw, message, type, alarm);
    }

    private boolean arrowglucosealarm(int kind, float glvalue, String message, notGlucose strglucose, String type,
            boolean alarm) {
        {
            if (doLog) {
                Log.i(LOG_ID, "arrowglucosealarm kind=" + kind + " " + message + " alarm=" + alarm);
            }
            ;
        }
        ;

        if (AlertType.Companion.isLegacyOnlyId(kind)) {
            final AlertType hiddenType = AlertType.Companion.fromId(kind);
            if (hiddenType != null) {
                SnoozeManager.INSTANCE.clearSnooze(hiddenType);
                AlertStateTracker.INSTANCE.resetState(hiddenType);
            }
            if (strglucose != null && GLUCOSENOTIFICATION.equals(type)) {
                updateForegroundGlucoseNotification(FOREGROUND_GLUCOSE_NOTIFICATION_KIND, glvalue, strglucose);
            }
            return false;
        }

        boolean incomingAlarm = alarm; // Capture initial state from Native/Caller
        AlertType alertType = null;
        AlertConfig config = null;

        // Resolve AlertType early
        try {
            alertType = AlertType.Companion.fromId(kind);
        } catch (Exception e) {
            Log.e(LOG_ID, "Error resolving AlertType: " + e.toString());
        }

        if (alarm) {

            // First-fire gate. Timed retries are handled by Notify after a successful
            // initial firing, not by subsequent glucose readings.
            try {
                if (alertType != null) {
                    config = AlertRepository.INSTANCE.loadConfig(alertType);

                    if (!AlertStateTracker.INSTANCE.shouldTrigger(alertType, config)) {
                        if (doLog)
                            Log.i(LOG_ID, "Alert suppressed by alert state: kind=" + kind);
                        alarm = false;
                    } else {
                        final boolean productionTrigger = AlertStateTracker.INSTANCE.onAlertTriggered(alertType, config);
                        if (productionTrigger) {
                            syncRetrySession(kind, glvalue, message, strglucose, type, config, true);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(LOG_ID, "Error checking alert state: " + e.toString());
            }

            if (alarm) {
                deliverTriggeredAlert(kind, glvalue, message, strglucose, type);
            }
        } else {
            // Processing for SILENT updates (alarm was false initially, OR
            // suppressed/downgraded above)

            // CRITICAL FIX: If incomingAlarm was false, it means Native logic (or caller)
            // decided
            // the alarm condition is NOT active (or cleared).
            // We must RESET the AlertStateTracker so it doesn't get stuck thinking the
            // episode is
            // still ongoing forever (preventing future triggers).
            if (!incomingAlarm && alertType != null) {
                AlertStateTracker.INSTANCE.resetState(alertType);
                cancelRetrySession(kind, "condition-cleared");
            } else if (incomingAlarm && alertType != null) {
                try {
                    if (config == null) {
                        config = AlertRepository.INSTANCE.loadConfig(alertType);
                    }
                    syncRetrySession(kind, glvalue, message, strglucose, type, config, false);
                } catch (Exception e) {
                    Log.e(LOG_ID, "Error updating retry session: " + e.toString());
                }
            }

            if (incomingAlarm) {
                if (doLog) {
                    Log.i(LOG_ID, "Suppressed alert did not update UI/notification: kind=" + kind);
                }
                return false;
            }

            final var act = MainActivity.thisone;
            if (act != null) {
                {
                    if (doLog) {
                        Log.i(LOG_ID, "act!=null");
                    }
                    ;
                }
                ;
                act.replaceDialogMessage(message);
            }
            if (MainActivity.showmessage != null) {
                {
                    if (doLog) {
                        Log.i(LOG_ID, "MainActivity.showmessage=" + message);
                    }
                    ;
                }
                ;
                MainActivity.showmessage = message;
            }
        }
        if (!alarm) {
            if (alertwatch) {
                {
                    if (doLog) {
                        Log.i(LOG_ID, "arrowglucosealarm alertwatch=" + alertwatch);
                    }
                    ;
                }
                ;
                arrowglucosenotification(kind, glvalue, message, strglucose, GLUCOSENOTIFICATION, false);
            } else {
                updateForegroundGlucoseNotification(kind, glvalue, strglucose);
            }
        }
        return alarm;
    }

    private void canceller() {
        glucoseRefreshHandler.removeCallbacks(glucoseRefreshRunnable);
        notificationManager.cancel(glucosenotificationid);
        notificationManager.cancel(numalarmid);
    }

    static public void cancelmessages() {
        if (onenot != null)
            onenot.canceller();

    }

    static final String fromnotification = "FromNotification";
    final static int forcecloserequest = 10;
    final static int stopalarmrequest = 8;
    // static final String closename= "ForceClose";
    final static int penmutable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE
            : 0;

    private final boolean makeicon = !isWearable && android.os.Build.VERSION.SDK_INT >= 23;
    private final StatusIcon icons = makeicon ? new StatusIcon(Applic.app) : null;

    static int getMaxGlucose(int sensorgen) {
        if (sensorgen == 0x40 || sensorgen == 0x20)
            return 400;
        return 500;
    }

    static private String getglstring(float glvalue, int sensorgen2) {
        var maxglucose = getMaxGlucose(sensorgen2);
        if (Applic.unit == 1) {
            if (glvalue < 2.2f) {
                return "2.2>";
            }
            if (glvalue > (((double) maxglucose) / Applic.mgdLmult)) {
                return "27.8<";
            }
            var glstr = format(util.getlocale(), Notify.pureglucoseformat, glvalue);
            // User requested to KEEP ",0" - Removed trimming logic
            // if (glstr.charAt(glstr.length() - 1) == '0')
            // glstr = glstr.substring(0, glstr.length() - 2);
            return glstr;
        } else {
            int intval = (int) glvalue;
            if (intval < 40)
                return "40>";
            if (intval > maxglucose)
                return "500<";
            return format(util.getlocale(), Notify.pureglucoseformat, glvalue);
        }
    }

    private void setIcon(Notification.Builder GluNotBuilder, float glvalue, int sensorgen2) {
        setIcon(GluNotBuilder, glvalue, sensorgen2, java.util.Collections.emptyList());
    }

    private void setIcon(Notification.Builder GluNotBuilder, float glvalue, int sensorgen2,
            java.util.List<NotificationChartDrawer.ValueItem> peerValues) {
        boolean hideIcon = Applic.app.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
                .getBoolean("notification_hide_status_icon", false);

        if (hideIcon) {
            GluNotBuilder.setSmallIcon(R.drawable.transparent_icon);
            return;
        }

        // A message-only alert has no value; both icon painters would render
        // the literal text "NaN".
        if (!Float.isFinite(glvalue)) {
            GluNotBuilder.setSmallIcon(R.drawable.loss);
            return;
        }

        if (makeicon) {
            final java.util.ArrayList<String> peerTexts = new java.util.ArrayList<>();
            if (peerValues != null) {
                for (NotificationChartDrawer.ValueItem peer : peerValues) {
                    if (peer != null && peer.text != null && !peer.text.isEmpty()) {
                        // The status icon identifies sensors, not each sensor's Auto/Raw
                        // sub-values. Keep only the leading displayed value here.
                        int separator = peer.text.indexOf(" · ");
                        peerTexts.add(separator >= 0 ? peer.text.substring(0, separator) : peer.text);
                    }
                }
            }
            final var icon = icons.getIcon(getglstring(glvalue, sensorgen2), peerTexts);
            GluNotBuilder.setSmallIcon(icon);
        } else {
            var draw = GlucoseDraw.getgludraw(glvalue, sensorgen2);
            GluNotBuilder.setSmallIcon(draw);
        }
    }

    private static void setImageViewBitmapIfPresent(RemoteViews remoteViews, int viewId, Bitmap bitmap) {
        // Android 17 beta crashes while reducing notification images if a
        // RemoteViews image action carries a null bitmap/icon.
        if (bitmap != null) {
            remoteViews.setImageViewBitmap(viewId, bitmap);
        }
    }

    private void makeseparatenotification(float glvalue, String message, notGlucose glucose, String type,
            int alertTypeId) {
        makeseparatenotification(glvalue, message, glucose, type, alertTypeId, null, null);
    }

    private void makeseparatenotification(float glvalue, String message, notGlucose glucose, String type,
            int alertTypeId, String deliveryModeOverride, String customAlertId) {
        if (!isWearable) {
            if (alertseparate) {
                String currentDeliveryMode = deliveryModeOverride != null
                        ? normalizeDeliveryMode(deliveryModeOverride)
                        : normalizeDeliveryMode(getDeliveryMode(alertTypeId));
                final String alarmGlucoseValue = alarmDisplayGlucoseValue(glvalue, glucose);
                // notificationManager.cancel(glucosealarmid); // Performance optimization:
                // Don't cancel, just overwrite
                PendingIntent intent;
                if (AlertDeliveryPolicy.shouldAttemptAlarmWindow(currentDeliveryMode)) {
                    try {
                        intent = mkAlarmPendingIntent(alarmGlucoseValue, message, glucose.rate, alertTypeId,
                                customAlertId, currentDeliveryMode);
                    } catch (Throwable e) {
                        if (doLog) {
                            Log.e(LOG_ID, "alarm content intent setup failed: " + e.toString());
                        }
                        intent = mkpending(customAlertId);
                    }
                } else {
                    intent = mkpending(customAlertId);
                }
                var GluNotBuilder = mkbuilderintent(type, intent, false);
                // Wearables and companion apps often map "dismiss" to the notification's
                // delete intent. The explicit notification buttons keep their fixed
                // behavior; this preference only controls generic swipe/close events.
                Intent swipeDismissIntent = new Intent(Applic.app, tk.glucodata.receivers.AlarmActionReceiver.class);
                AlertNotificationDismissAction deleteAction = AlertRepository.INSTANCE.loadNotificationDismissAction();
                swipeDismissIntent.setAction(deleteAction == AlertNotificationDismissAction.SNOOZE
                        ? tk.glucodata.receivers.AlarmActionReceiver.ACTION_SNOOZE
                        : tk.glucodata.receivers.AlarmActionReceiver.ACTION_DISMISS);
                swipeDismissIntent.putExtra("alert_type_id", alertTypeId);
                if (customAlertId != null && !customAlertId.isEmpty()) {
                    swipeDismissIntent.putExtra(EXTRA_CUSTOM_ALERT_ID, customAlertId);
                }
                PendingIntent swipeDismissPendingIntent = PendingIntent.getBroadcast(Applic.app, 4, swipeDismissIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | penmutable);
                GluNotBuilder.setDeleteIntent(swipeDismissPendingIntent);
                {
                    if (doLog) {
                        Log.i(LOG_ID, "makeseparatenotification " + alarmGlucoseValue);
                    }
                    ;
                }
                ;

                setIcon(GluNotBuilder, glvalue, glucose.sensorgen2);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // final int timeout= Build.VERSION.SDK_INT >= 30? 60*1500:60*3000;
                    final int timeout = 800 * 60;// Build.VERSION.SDK_INT >= 30? 60*1500:60*3000;
                    GluNotBuilder.setTimeoutAfter(timeout);
                }
                GluNotBuilder.setPriority(Notification.PRIORITY_HIGH);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    GluNotBuilder.setCategory(Notification.CATEGORY_ALARM);
                }

                // Fallback path for devices/OEM states where the queued alarm-window
                // launch is denied. Notification-only mode deliberately never gets this.
                if (AlertDeliveryPolicy.shouldAttachFullScreenIntent(currentDeliveryMode)) {
                    // Use Reflection for Intent creation to safe-guard against Missing Class on
                    // Wear
                    try {
                        PendingIntent fullScreenPendingIntent = mkAlarmPendingIntent(alarmGlucoseValue, message,
                                glucose.rate, alertTypeId, customAlertId, currentDeliveryMode);
                        GluNotBuilder.setFullScreenIntent(fullScreenPendingIntent, true);
                    } catch (Throwable e) {
                        if (doLog)
                            Log.e(LOG_ID, "fullScreenIntent setup failed: " + e.toString());
                    }
                }

                // Add Snooze Action
                Intent snoozeIntent = new Intent(Applic.app, tk.glucodata.receivers.AlarmActionReceiver.class);
                snoozeIntent.setAction(tk.glucodata.receivers.AlarmActionReceiver.ACTION_SNOOZE);
                snoozeIntent.putExtra("alert_type_id", alertTypeId);
                if (customAlertId != null && !customAlertId.isEmpty()) {
                    snoozeIntent.putExtra(EXTRA_CUSTOM_ALERT_ID, customAlertId);
                }
                PendingIntent snoozePendingIntent = PendingIntent.getBroadcast(Applic.app, 1, snoozeIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | penmutable);

                // Add Dismiss Action
                Intent dismissIntent = new Intent(Applic.app, tk.glucodata.receivers.AlarmActionReceiver.class);
                dismissIntent.setAction(tk.glucodata.receivers.AlarmActionReceiver.ACTION_DISMISS);
                dismissIntent.putExtra("alert_type_id", alertTypeId);
                if (customAlertId != null && !customAlertId.isEmpty()) {
                    dismissIntent.putExtra(EXTRA_CUSTOM_ALERT_ID, customAlertId);
                }
                PendingIntent dismissPendingIntent = PendingIntent.getBroadcast(Applic.app, 2, dismissIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | penmutable);

                // --- RICH UI START (Minimal: Value + Arrow + Alert Name) ---
                // Fetch Layout Prefs
                android.content.SharedPreferences prefs = Applic.app.getSharedPreferences("tk.glucodata_preferences",
                        Context.MODE_PRIVATE);
                float fontSize = prefs.getFloat("notification_font_size", 1.0f);
                int fontWeight = prefs.getInt("notification_font_weight", 400);
                boolean showArrow = prefs.getBoolean("notification_show_arrow", true);
                float arrowSize = prefs.getFloat("notification_arrow_size", 1.0f);
                boolean isMmol = Applic.unit == 1;

                // Data Prep
                int glucoseColor = NotificationChartDrawer.getGlucoseColor(Applic.app, glvalue, isMmol);

                // Fetch Native Points for Consistent Text Formatting (Raw/Auto)
                long endT = System.currentTimeMillis();
                long recentStartT = endT - 10 * 60 * 1000L;
                final String activeSensorSerial = NotificationHistorySource.resolveSensorSerial(resolvePrimarySensorName());
                java.util.List<GlucosePoint> nativePoints = new java.util.ArrayList<>();
                try {
                    nativePoints = NotificationHistorySource.getDisplayHistory(recentStartT, isMmol, activeSensorSerial);
                } catch (Exception e) {
                }

                // Determine ViewMode for formatting
                int viewMode = 0;
                viewMode = resolveSensorViewMode(resolvePrimarySensorName());

                float displayRate = glucose.rate;
                try {
                    // The alarm arrow must not disagree with the main notification and the
                    // dashboard hero: regress over the same canonical trend list they use,
                    // not a bare 10-minute wall-clock slice.
                    final CurrentDisplaySource.Snapshot alarmSnapshot = resolveNotificationCurrentSnapshot(
                            activeSensorSerial);
                    java.util.List<GlucosePoint> trendPoints = NotificationHistorySource.getDisplayHistory(
                            endT - 2 * DisplayTrendSource.TREND_WINDOW_MS, isMmol, activeSensorSerial);
                    trendPoints = DisplayTrendSource.resolveTrendPoints(trendPoints, alarmSnapshot,
                            activeSensorSerial);
                    displayRate = DisplayTrendSource.resolveArrowRate(trendPoints, alarmSnapshot, viewMode, isMmol,
                            glucose.rate);
                } catch (Throwable t) {
                    // keep original rate if fails
                }

                Bitmap arrowBitmap = showArrow
                        ? NotificationChartDrawer.drawArrow(Applic.app, displayRate, isMmol, glucoseColor, arrowSize)
                        : null;

                CharSequence valueText = formatGlucoseText(glucose.value, glvalue, nativePoints, viewMode,
                        glucose.time, activeSensorSerial);

                // Construct RemoteViews using the same rich alert surface for every mode.
                RemoteViews remoteViews = new RemoteViews(Applic.app.getPackageName(),
                        R.layout.notification_material);
                RemoteViews remoteViewsExpanded = new RemoteViews(Applic.app.getPackageName(),
                        R.layout.notification_material_expanded);
                RemoteViews remoteViewsHeadsUp = new RemoteViews(Applic.app.getPackageName(),
                        R.layout.notification_material_heads_up);

                // "Forecast Low 4.0 mmol/L" -> "Forecast Low"; duration-carrying
                // messages (sensor expiry, missed reading) keep their numbers.
                final String badgeText = AlertDisplayText.notificationBadge(
                        AlertType.Companion.fromId(alertTypeId),
                        customAlertId != null && !customAlertId.isEmpty(),
                        message);
                final String alertMeta = timef.format(glucose.time);
                final String plainValueText = valueText.toString();

                GluNotBuilder.setVisibility(VISIBILITY_PUBLIC);
                GluNotBuilder.setContentTitle(badgeText);
                GluNotBuilder.setContentText(plainValueText);
                GluNotBuilder.setSubText(alertMeta);
                GluNotBuilder.setTicker(badgeText + " " + plainValueText);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Notification.Builder publicBuilder = mkbuilderintent(type, intent, false);
                    setIcon(publicBuilder, glvalue, glucose.sensorgen2);
                    publicBuilder
                            .setVisibility(VISIBILITY_PUBLIC)
                            .setContentTitle(badgeText)
                            .setContentText(plainValueText)
                            .setSubText(alertMeta);
                    GluNotBuilder.setPublicVersion(publicBuilder.build());
                }

                // Font Styling
                // Initialize ssb first!!
                android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(valueText);

                String family = (fontWeight >= 500) ? "google-sans-medium" : "google-sans";
                ssb.setSpan(new android.text.style.TypefaceSpan(family), 0, ssb.length(),
                        android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                if (fontSize != 1.0f)
                    ssb.setSpan(new android.text.style.RelativeSizeSpan(fontSize), 0, ssb.length(),
                            android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);

                // Set Views (Collapsed)
                remoteViews.setTextViewText(R.id.notification_glucose, ssb);
                remoteViews.setTextColor(R.id.notification_glucose, glucoseColor);
                remoteViews.setTextViewTextSize(R.id.notification_glucose, android.util.TypedValue.COMPLEX_UNIT_SP,
                        24 * fontSize);

                remoteViews.setViewVisibility(R.id.notification_status, View.VISIBLE);
                remoteViews.setTextViewText(R.id.notification_status, badgeText);
                remoteViews.setTextColor(R.id.notification_status,
                        ((Applic.app.getResources().getConfiguration().uiMode
                                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                                == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                                        ? 0xB3FFFFFF
                                        : 0x8A000000);
                remoteViews.setViewVisibility(R.id.notification_alert_badge, View.GONE);
                remoteViews.setViewVisibility(R.id.notification_meta, View.GONE);

                if (showArrow && arrowBitmap != null) {
                    remoteViews.setViewVisibility(R.id.notification_arrow, View.VISIBLE);
                    remoteViews.setImageViewBitmap(R.id.notification_arrow, arrowBitmap);
                } else {
                    remoteViews.setViewVisibility(R.id.notification_arrow, View.GONE);
                }

                // Hide Chart & Container
                remoteViews.setViewVisibility(R.id.notification_chart, View.GONE);
                remoteViews.setViewVisibility(R.id.chart_container, View.GONE);

                // Set Views (Expanded)
                remoteViewsExpanded.setTextViewText(R.id.notification_glucose, ssb);
                remoteViewsExpanded.setTextColor(R.id.notification_glucose, glucoseColor);
                remoteViewsExpanded.setTextViewTextSize(R.id.notification_glucose,
                        android.util.TypedValue.COMPLEX_UNIT_SP, 28 * fontSize);

                remoteViewsExpanded.setViewVisibility(R.id.notification_status, View.GONE);
                remoteViewsExpanded.setViewVisibility(R.id.notification_alert_badge, View.VISIBLE);
                remoteViewsExpanded.setTextViewText(R.id.notification_alert_badge, badgeText);
                remoteViewsExpanded.setViewVisibility(R.id.notification_meta, View.VISIBLE);
                remoteViewsExpanded.setTextViewText(R.id.notification_meta, alertMeta);

                if (showArrow && arrowBitmap != null) {
                    remoteViewsExpanded.setViewVisibility(R.id.notification_arrow, View.VISIBLE);
                    remoteViewsExpanded.setImageViewBitmap(R.id.notification_arrow, arrowBitmap);
                } else {
                    remoteViewsExpanded.setViewVisibility(R.id.notification_arrow, View.GONE);
                }

                // Hide Chart in Expanded too
                remoteViewsExpanded.setViewVisibility(R.id.notification_chart, View.GONE);
                remoteViewsExpanded.setViewVisibility(R.id.notification_alert_actions, View.VISIBLE);
                remoteViewsExpanded.setOnClickPendingIntent(R.id.notification_action_snooze, snoozePendingIntent);
                remoteViewsExpanded.setOnClickPendingIntent(R.id.notification_action_dismiss, dismissPendingIntent);

                remoteViewsHeadsUp.setTextViewText(R.id.notification_glucose, ssb);
                remoteViewsHeadsUp.setTextColor(R.id.notification_glucose, glucoseColor);
                remoteViewsHeadsUp.setTextViewTextSize(R.id.notification_glucose,
                        android.util.TypedValue.COMPLEX_UNIT_SP, 24 * fontSize);
                remoteViewsHeadsUp.setViewVisibility(R.id.notification_status, View.GONE);
                remoteViewsHeadsUp.setViewVisibility(R.id.notification_alert_badge, View.VISIBLE);
                remoteViewsHeadsUp.setTextViewText(R.id.notification_alert_badge, badgeText);
                remoteViewsHeadsUp.setViewVisibility(R.id.notification_meta, View.VISIBLE);
                remoteViewsHeadsUp.setTextViewText(R.id.notification_meta, alertMeta);
                remoteViewsHeadsUp.setViewVisibility(R.id.notification_alert_actions, View.VISIBLE);
                remoteViewsHeadsUp.setOnClickPendingIntent(R.id.notification_action_snooze, snoozePendingIntent);
                remoteViewsHeadsUp.setOnClickPendingIntent(R.id.notification_action_dismiss, dismissPendingIntent);

                if (showArrow && arrowBitmap != null) {
                    remoteViewsHeadsUp.setViewVisibility(R.id.notification_arrow, View.VISIBLE);
                    remoteViewsHeadsUp.setImageViewBitmap(R.id.notification_arrow, arrowBitmap);
                } else {
                    remoteViewsHeadsUp.setViewVisibility(R.id.notification_arrow, View.GONE);
                }

                final int badgeBackground = getAlertBadgeBackgroundRes(alertTypeId);
                final int primaryActionBackground = getAlertPrimaryActionBackgroundRes(alertTypeId);
                remoteViewsExpanded.setInt(R.id.notification_alert_badge, "setBackgroundResource", badgeBackground);
                remoteViewsHeadsUp.setInt(R.id.notification_alert_badge, "setBackgroundResource", badgeBackground);
                remoteViewsExpanded.setInt(R.id.notification_action_dismiss, "setBackgroundResource",
                        primaryActionBackground);
                remoteViewsHeadsUp.setInt(R.id.notification_action_dismiss, "setBackgroundResource",
                        primaryActionBackground);

                // Bind to Builder
                if (Build.VERSION.SDK_INT >= 24) {
                    GluNotBuilder.setStyle(new Notification.DecoratedCustomViewStyle());
                    GluNotBuilder.setCustomContentView(remoteViews);
                    GluNotBuilder.setCustomBigContentView(remoteViewsExpanded);
                    GluNotBuilder.setCustomHeadsUpContentView(remoteViewsHeadsUp);
                } else {
                    GluNotBuilder.setContent(remoteViews);
                }
                // --- RICH UI END ---

                Notification notif = GluNotBuilder.build();
                notif.when = glucose.time;
                notificationManager.notify(glucosealarmid, notif);
            }
        }
    }

    private static boolean isLowFamilyAlert(int alertTypeId) {
        return alertTypeId == 0 || alertTypeId == 5 || alertTypeId == 7;
    }

    private static boolean isHighFamilyAlert(int alertTypeId) {
        return alertTypeId == 1 || alertTypeId == 6 || alertTypeId == 8 || alertTypeId == 10;
    }

    private static int getAlertBadgeBackgroundRes(int alertTypeId) {
        if (isLowFamilyAlert(alertTypeId)) {
            return R.drawable.notification_alert_badge_low;
        }
        if (isHighFamilyAlert(alertTypeId)) {
            return R.drawable.notification_alert_badge_high;
        }
        return R.drawable.notification_alert_badge_neutral;
    }

    private static int getAlertPrimaryActionBackgroundRes(int alertTypeId) {
        if (isLowFamilyAlert(alertTypeId)) {
            return R.drawable.notification_alert_action_primary_low;
        }
        if (isHighFamilyAlert(alertTypeId)) {
            return R.drawable.notification_alert_action_primary_high;
        }
        return R.drawable.notification_alert_action_primary_neutral;
    }

    static public boolean alertseparate = false;

    static public PendingIntent mkpending() {
        return mkpending(null);
    }

    static public PendingIntent mkpending(String customAlertId) {
        {
            if (doLog) {
                Log.i(LOG_ID, "mkpending");
            }
            ;
        }
        ;
        Intent notifyIntent = new Intent(Applic.app, MainActivity.class);
        notifyIntent.putExtra(fromnotification, true);
        if (customAlertId != null && !customAlertId.isEmpty()) {
            notifyIntent.putExtra(EXTRA_CUSTOM_ALERT_ID, customAlertId);
        }
        notifyIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        notifyIntent.setAction(Intent.ACTION_MAIN);
        notifyIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(Applic.app, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT | penmutable);
    }

    private Notification.Builder mkbuilderintent(String type, PendingIntent notifyPendingIntent) {
        return mkbuilderintent(type, notifyPendingIntent, true);
    }

    private Notification.Builder mkbuilderintent(String type, PendingIntent notifyPendingIntent,
            boolean groupWithService) {
        Notification.Builder GluNotBuilder;
        if (true) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                GluNotBuilder = new Notification.Builder(Applic.app, type);
            } else {
                GluNotBuilder = new Notification.Builder(Applic.app);
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                GluNotBuilder.setChannelId(type);
        }
        GluNotBuilder.setContentIntent(notifyPendingIntent).setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= 20) {
            GluNotBuilder.setLocalOnly(false);
        }
        if (groupWithService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            GluNotBuilder.setGroup("aa2");
        }
        return GluNotBuilder;
    }

    // Helper to format "Value · Raw" consistently
    // Helper to format "Value · Raw" consistently
    // Helper to format "Value · Raw" consistently
    // Helper to format "Value · Raw" consistently
    // Helper to format "Value · Raw" with HTML styling and comma separator
    // Helper to format "Value · Raw" with HTML styling and comma separator
    private static CharSequence buildFormattedGlucoseText(CurrentDisplaySource.Snapshot resolved, float fallbackValue) {
        if (resolved == null) {
            return format(java.util.Locale.getDefault(), pureglucoseformat, fallbackValue);
        }

        final String valueText = resolved.getPrimaryStr();
        final String secondary = resolved.getSecondaryStr();
        final String tertiary = resolved.getTertiaryStr();
        if (secondary == null) {
            return valueText;
        }

        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder();
        ssb.append(valueText);

        int secStart = ssb.length();
        ssb.append(" · ");
        ssb.append(secondary);
        ssb.setSpan(new android.text.style.ForegroundColorSpan(0xFF888888), secStart, ssb.length(),
                android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        ssb.setSpan(new android.text.style.RelativeSizeSpan(0.85f), secStart, ssb.length(),
                android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);

        if (tertiary != null) {
            int terStart = ssb.length();
            ssb.append(" · ");
            ssb.append(tertiary);
            ssb.setSpan(new android.text.style.ForegroundColorSpan(0xFFAAAAAA), terStart, ssb.length(),
                    android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            ssb.setSpan(new android.text.style.RelativeSizeSpan(0.7f), terStart, ssb.length(),
                    android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }

        return ssb;
    }

    public static CharSequence formatGlucoseText(String value, float glvalue, java.util.List<GlucosePoint> points,
            int viewMode,
            long targetTime,
            String calibrationSensorId) {
        final boolean isMmol = Applic.unit == 1;
        final CurrentDisplaySource.Snapshot resolved = CurrentDisplaySource.resolveFromLive(
                value,
                glvalue,
                Float.NaN,
                CurrentGlucoseSource.normalizeTimeMillis(targetTime),
                calibrationSensorId,
                0,
                0,
                "notification",
                points != null ? points : java.util.Collections.emptyList(),
                viewMode,
                isMmol);
        return buildFormattedGlucoseText(resolved, glvalue);
    }

    // Helper for relative time "1m", "5m", "now"
    private String getRelativeTimeSpanString(Context context, long time) {
        long now = System.currentTimeMillis();
        long diff = now - time;
        if (diff < 60000) {
            return "now";
        } else {
            long mins = diff / 60000;
            return mins + "m";
        }
    }

    // UPDATE METHOD
    public Notification makearrownotification(int draw, float glvalue, String message, notGlucose glucose, String type,
            boolean once) {
        // 1. Determine Arrow
        float rate = glucose.rate;

        // Delta (Current - Previous?) - omitted for now

        // 2. Build Chart
        long endT = System.currentTimeMillis();
        long startT = endT - 3 * 60 * 60 * 1000L;
        boolean isMmol = Applic.unit == 1; // Check user unit preference
        final String activeSensorSerial = NotificationHistorySource.resolveSensorSerial(resolvePrimarySensorName());

        final CurrentDisplaySource.Snapshot resolvedDisplay = GLUCOSENOTIFICATION.equals(type)
                ? resolveNotificationCurrentSnapshot(activeSensorSerial)
                : null;

        java.util.List<GlucosePoint> chartPoints;
        try {
            chartPoints = NotificationHistorySource.getDisplayHistory(startT, isMmol, activeSensorSerial);
        } catch (Exception e) {
            chartPoints = new java.util.ArrayList<>();
        }

        // The canonical trend list: derived from the same rows before the chart's own
        // augmentation, so the arrow regresses over byte-identical points with the
        // dashboard hero and the computed-trend broadcast.
        java.util.List<GlucosePoint> nativePoints = DisplayTrendSource.resolveTrendPoints(chartPoints, resolvedDisplay,
                activeSensorSerial);

        chartPoints = DisplayTrendSource.augmentHistory(chartPoints, resolvedDisplay, activeSensorSerial, startT);

        BatteryTrace.bump(
                "notify.glucose.render",
                20L,
                "interactive=" + isScreenInteractive());

        // Status Logic & ViewMode extraction
        String statusText = "";
        int viewMode = 0; // Default

        if (activeSensorSerial != null && SensorBluetooth.blueone != null) {
            synchronized (SensorBluetooth.gattcallbacks) {
                for (SuperGattCallback cb : SensorBluetooth.gattcallbacks) {
                    if (cb.SerialNumber != null && cb.SerialNumber.equals(activeSensorSerial)) {
                        statusText = cb.constatstatusstr;
                        break;
                    }
                }
            }
        }
        if (viewMode == 0) {
            viewMode = resolveSensorViewMode(activeSensorSerial);
        }

        // Keep the notification arrow aligned with the same 20-minute trend window the
        // dashboard TrendEngine uses, while still appending the live point before Room
        // persistence catches up.
        rate = DisplayTrendSource.resolveArrowRate(nativePoints, resolvedDisplay, viewMode, isMmol, rate);

        // If ViewMode == 3 (Combined), we force appending Raw if available
        boolean isRawMode = (viewMode == 1 || viewMode == 3);
        boolean hasCalibration = NightscoutCalibration.hasCalibrationForViewMode(activeSensorSerial, viewMode);

        final CurrentDisplaySource.Snapshot fallbackDisplay = resolvedDisplay != null
                ? resolvedDisplay
                : CurrentDisplaySource.resolveFromLive(
                        glucose.value,
                        glvalue,
                        Float.NaN,
                        CurrentGlucoseSource.normalizeTimeMillis(glucose.time),
                        activeSensorSerial,
                        0,
                        0,
                        "notification",
                        nativePoints,
                        viewMode,
                        isMmol);

        CharSequence valueText = buildFormattedGlucoseText(fallbackDisplay, glvalue);
        final float displayGlucoseValue = fallbackDisplay != null ? fallbackDisplay.getPrimaryValue() : glvalue;
        // One snapshot resolution per peer per update; reused for the value row
        // and (when enabled) the chart series below.
        final java.util.List<NotificationMultiSensorSource.PeerCurrent> peerCurrents =
                NotificationMultiSensorSource.peerCurrents(glucosetimeout, activeSensorSerial);
        final java.util.List<NotificationChartDrawer.ValueItem> peerValueItems =
                NotificationMultiSensorSource.valueItems(peerCurrents);

        boolean hasSelectedPeerSensors = !peerCurrents.isEmpty();
        int primaryDisplayColor = NotificationChartDrawer.notificationChartPrimaryValueColor(
                Applic.app,
                displayGlucoseValue,
                isMmol,
                activeSensorSerial,
                hasSelectedPeerSensors);
        int secondaryDisplayColor = NotificationChartDrawer.notificationChartSecondaryValueColor(Applic.app);
        int tertiaryDisplayColor = NotificationChartDrawer.notificationChartTertiaryValueColor(Applic.app);

        // ========== READ NOTIFICATION PREFERENCES ==========
        android.content.SharedPreferences prefs = Applic.app
                .getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE);

        float fontSize = prefs.getFloat("notification_font_size", 1.0f);
        int fontFamily = prefs.getInt("notification_font_family", 0); // 0=App, 1=System
        boolean useSystemFont = (fontFamily == 1);
        int fontWeight = prefs.getInt("notification_font_weight", 400);
        boolean showArrow = prefs.getBoolean("notification_show_arrow", true);
        float arrowSize = prefs.getFloat("notification_arrow_size", 1.0f);
        boolean showStatus = prefs.getBoolean("notification_show_status", true);
        boolean showIob = prefs.getBoolean("notification_show_iob", false);
        boolean showCob = prefs.getBoolean("notification_show_cob", false);
        boolean showDelta = prefs.getBoolean("notification_show_delta", false);
        int deltaIntervalMinutes = prefs.getInt("delta_interval_minutes", GlucoseDelta.DEFAULT_INTERVAL_MINUTES);
        boolean iobCobRiskColored = prefs.getBoolean("notification_iob_cob_risk_colored", false);
        boolean arrowForecastColored = prefs.getBoolean("glucose_arrow_forecast_colors_enabled", false);
        boolean showChart = prefs.getBoolean("notification_chart_enabled", true);
        final boolean shadeNight = (Applic.app.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        boolean showChartCollapsed = prefs.getBoolean("notification_chart_collapsed", false);
        boolean showTargetRange = prefs.getBoolean("notification_chart_target_range", true);

        // Optional GDH-style traffic coloring of the value (and arrow): green
        // in target range, yellow up to the alarm bounds, red beyond.
        if (prefs.getBoolean("glucose_value_range_colors_enabled", false)) {
            primaryDisplayColor = GlucoseRangeColors.trafficColorForValue(
                    displayGlucoseValue,
                    Natives.targetlow(), Natives.targethigh(),
                    Natives.alarmverylow(), Natives.alarmveryhigh(),
                    shadeNight, isMmol, primaryDisplayColor);
        }

        // Multi-sensor: arrows render inline next to each value inside the
        // glucose bitmap; the standalone arrow view would otherwise sit after
        // the peer values and look like it belongs to the last peer.
        boolean inlineMultiArrows = showArrow && !peerValueItems.isEmpty();

        // Arrow color: optionally driven by the 30-minute linear forecast
        // (value color says where you are, arrow color where you're heading).
        // Gated on data age: during a reconnect gap the trend window only
        // holds pre-gap points and must not paint a red forecast.
        int arrowColor = primaryDisplayColor;
        if (arrowForecastColored) {
            long latestDataMillis = CurrentGlucoseSource.normalizeTimeMillis(glucose.time);
            if (!nativePoints.isEmpty())
                latestDataMillis = Math.max(latestDataMillis,
                        nativePoints.get(nativePoints.size() - 1).timestamp);
            final long dataAgeMillis = latestDataMillis > 0
                    ? System.currentTimeMillis() - latestDataMillis
                    : Long.MAX_VALUE;
            final float toMgdl = isMmol ? 18.016f : 1.0f;
            final int trendRisk = TrendProjection.classify(
                    displayGlucoseValue * toMgdl,
                    rate,
                    dataAgeMillis,
                    TrendProjection.DEFAULT_HORIZON_MINUTES,
                    Natives.targetlow() * toMgdl, Natives.targethigh() * toMgdl,
                    Natives.alarmverylow() * toMgdl, Natives.alarmveryhigh() * toMgdl);
            if (trendRisk == TrendProjection.OUT)
                arrowColor = GlucoseRangeColors.valueOut(shadeNight);
            else if (trendRisk == TrendProjection.BORDERLINE)
                arrowColor = GlucoseRangeColors.valueBorderline(shadeNight);
        }

        // Render Arrow (Color + Size from Preferences) - still bitmap for colored
        // vector
        Bitmap arrowBitmap = (showArrow && !inlineMultiArrows)
                ? NotificationChartDrawer.drawArrow(Applic.app, rate, isMmol, arrowColor, arrowSize)
                : null;

        // 3a. Construct RemoteViews (Collapsed)
        RemoteViews remoteViews = new RemoteViews(Applic.app.getPackageName(), R.layout.notification_material);

        // Apply System Font Weight Mapping (Pixel-friendly)
        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(valueText);
        String family = "sans-serif";
        boolean isBold = false;

        // Use standard system font logic
        if (fontWeight >= 500) {
            family = "sans-serif-medium";
        } else {
            family = "sans-serif";
        }

        // Apply Font Family
        ssb.setSpan(new android.text.style.TypefaceSpan(family), 0, ssb.length(),
                android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);

        // Apply Bold if needed
        if (isBold) {
            ssb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, ssb.length(),
                    android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }

        // Apply Relative Size (Scale) based on preference
        if (fontSize != 1.0f) {
            ssb.setSpan(new android.text.style.RelativeSizeSpan(fontSize), 0, ssb.length(),
                    android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }

        CharSequence finalText = ssb;

        String sensorStatusText = resolveNotificationStatusText(activeSensorSerial, statusText);

        // The status line carries the journal IOB/eIOB/COB (when enabled) and
        // the sensor status (gated by its own pref). IOB goes first — the
        // status view ellipsizes and must not swallow it.
        if (!showStatus)
            sensorStatusText = "";
        CharSequence newStatusText = sensorStatusText;
        if (showIob || showCob) {
            CharSequence iobLine = JournalIobAccess.notificationLine(prefs, showIob, showCob,
                    iobCobRiskColored, shadeNight, displayGlucoseValue, isMmol);
            if (doLog)
                Log.i(LOG_ID, "notification iobLine=" + iobLine);
            if (iobLine != null)
                newStatusText = (sensorStatusText == null || sensorStatusText.isEmpty())
                        ? iobLine
                        : new android.text.SpannableStringBuilder(iobLine).append(" · ").append(sensorStatusText);
        }
        // The "Δ" readout: measured change over the last ~5 minutes — a raw
        // number to sanity-check the estimated arrow against. Leftmost, right
        // next to the arrow. Walks back to the first point old enough for the
        // window; the tail can hold near-duplicates (persisted vs live
        // timestamp of the same reading), so never take blind indices.
        if (showDelta && nativePoints.size() >= 2) {
            final GlucosePoint newest = nativePoints.get(nativePoints.size() - 1);
            final float newestValue = (isRawMode && newest.rawValue > 0f) ? newest.rawValue : newest.value;
            GlucosePoint previous = null;
            for (int i = nativePoints.size() - 2; i >= 0; i--) {
                final GlucosePoint p = nativePoints.get(i);
                final float value = (isRawMode && p.rawValue > 0f) ? p.rawValue : p.value;
                if (value > 0.1f && newest.timestamp - p.timestamp >= GlucoseDelta.minGapMillis(deltaIntervalMinutes)) {
                    previous = p;
                    break;
                }
            }
            final float previousValue = previous == null ? Float.NaN
                    : (isRawMode && previous.rawValue > 0f) ? previous.rawValue : previous.value;
            final String deltaText = previous == null ? "" : GlucoseDelta.format(
                    GlucoseDelta.delta(newest.timestamp, newestValue, previous.timestamp, previousValue, deltaIntervalMinutes),
                    isMmol);
            if (doLog)
                Log.i(LOG_ID, "notification delta=" + deltaText
                        + " points=" + nativePoints.size()
                        + " gap=" + (previous == null ? -1L : (newest.timestamp - previous.timestamp)));
            if (!deltaText.isEmpty()) {
                newStatusText = (newStatusText == null || newStatusText.length() == 0)
                        ? "Δ " + deltaText
                        : new android.text.SpannableStringBuilder("Δ ").append(deltaText)
                                .append(" · ").append(newStatusText);
            }
        }

        // Apply Style to Status Text too
        // The layout's ?android:attr/textColorSecondary resolves against the app
        // theme, not the notification's, and can render near-invisible on dark
        // shades — pick an explicit night-aware color instead.
        final int statusTextColor = shadeNight ? 0xB3FFFFFF : 0x8A000000;
        CharSequence styledStatus = newStatusText;
        if (newStatusText != null && newStatusText.length() > 0) {
            android.text.SpannableStringBuilder ssbStatus = new android.text.SpannableStringBuilder(newStatusText);
            ssbStatus.setSpan(new android.text.style.TypefaceSpan(family), 0, ssbStatus.length(),
                    android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            if (isBold) {
                ssbStatus.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0,
                        ssbStatus.length(), android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            }
            styledStatus = ssbStatus;
        }

        // Glucose Value - Render as Bitmap to support IBM Plex Font & Locale
        // consistency
        // Collapsed: Base size 24sp (scale 1.0 * fontSize)
        Bitmap valueBitmap = NotificationChartDrawer.drawMultiGlucoseText(Applic.app, valueText.toString(),
                primaryDisplayColor, secondaryDisplayColor, tertiaryDisplayColor, peerValueItems,
                fontSize, fontWeight, useSystemFont,
                inlineMultiArrows ? rate : Float.NaN, isMmol, arrowSize);
        remoteViews.setViewVisibility(R.id.notification_glucose, View.GONE);
        remoteViews.setViewVisibility(R.id.notification_glucose_image, View.VISIBLE);
        remoteViews.setImageViewBitmap(R.id.notification_glucose_image, valueBitmap);

        if (showArrow && arrowBitmap != null) {
            remoteViews.setViewVisibility(R.id.notification_arrow, View.VISIBLE);
            remoteViews.setImageViewBitmap(R.id.notification_arrow, arrowBitmap);
        } else {
            remoteViews.setViewVisibility(R.id.notification_arrow, View.GONE);
        }

        // Status - native TextView
        if (newStatusText != null && newStatusText.length() > 0) {
            remoteViews.setViewVisibility(R.id.notification_status, View.VISIBLE);
            remoteViews.setTextViewText(R.id.notification_status, styledStatus);
            remoteViews.setTextColor(R.id.notification_status, statusTextColor);
        } else {
            remoteViews.setViewVisibility(R.id.notification_status, View.GONE);
        }

        // 3b. Construct RemoteViews (Expanded)
        RemoteViews remoteViewsExpanded = new RemoteViews(Applic.app.getPackageName(),
                R.layout.notification_material_regular_expanded);

        // Glucose Value - Expanded: Size 28sp (scale ~1.17 * fontSize)
        Bitmap valueBitmapExpanded = NotificationChartDrawer.drawMultiGlucoseText(Applic.app, valueText.toString(),
                primaryDisplayColor, secondaryDisplayColor, tertiaryDisplayColor, peerValueItems,
                fontSize * 1.166f, fontWeight, useSystemFont,
                inlineMultiArrows ? rate : Float.NaN, isMmol, arrowSize);
        remoteViewsExpanded.setViewVisibility(R.id.notification_glucose, View.GONE);
        remoteViewsExpanded.setViewVisibility(R.id.notification_glucose_image, View.VISIBLE);
        remoteViewsExpanded.setImageViewBitmap(R.id.notification_glucose_image, valueBitmapExpanded);

        if (showArrow && arrowBitmap != null) {
            remoteViewsExpanded.setViewVisibility(R.id.notification_arrow, View.VISIBLE);
            remoteViewsExpanded.setImageViewBitmap(R.id.notification_arrow, arrowBitmap);
        } else {
            remoteViewsExpanded.setViewVisibility(R.id.notification_arrow, View.GONE);
        }

        // Status for Expanded - native TextView
        if (newStatusText != null && newStatusText.length() > 0) {
            remoteViewsExpanded.setViewVisibility(R.id.notification_status, View.VISIBLE);
            remoteViewsExpanded.setTextViewText(R.id.notification_status, styledStatus);
            remoteViewsExpanded.setTextColor(R.id.notification_status, statusTextColor);
        } else {
            remoteViewsExpanded.setViewVisibility(R.id.notification_status, View.GONE);
        }

        // Set Chart
        Bitmap chartBitmapCollapsed = null;
        Bitmap chartBitmapExpanded = null;

        // Create Safe Context with System Density (Fix for Low DPI/Large Text modes
        // where Applic.app is stale)
        Context safeContext = Applic.app;
        if (showChartCollapsed || showChart) {
            try {
                android.util.DisplayMetrics systemDm = android.content.res.Resources.getSystem().getDisplayMetrics();
                android.content.res.Configuration config = new android.content.res.Configuration(
                        Applic.app.getResources().getConfiguration());
                config.densityDpi = (int) (systemDm.density * 160f);
                safeContext = Applic.app.createConfigurationContext(config);
            } catch (Throwable t) {
                // fallback to app context if creation fails
            }
        }
        final java.util.List<NotificationChartDrawer.PeerSeries> peerChartSeries =
                (showChartCollapsed || showChart)
                        ? NotificationMultiSensorSource.peerSeries(peerCurrents, startT, isMmol)
                        : java.util.Collections.emptyList();

        if (showChartCollapsed) {
            // Collapsed chart: Limit height to 48dp based on SYSTEM density
            float density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
            int collapsedHeight = (int) (48 * density);
            if (collapsedHeight < 48)
                collapsedHeight = 48;

            // Use safeContext and explicit height
            chartBitmapCollapsed = NotificationChartDrawer.drawChartWithPrediction(safeContext, chartPoints, 0, collapsedHeight,
                    isMmol,
                    viewMode, showTargetRange, hasCalibration, true, activeSensorSerial, peerChartSeries);
        }

        if (showChart) {
            // Expanded chart: Use safely resolved density context (default 0 ->
            // 256*density)
            chartBitmapExpanded = NotificationChartDrawer.drawChartWithPrediction(safeContext, chartPoints, 0, 0, isMmol,
                    viewMode, showTargetRange, hasCalibration, false, activeSensorSerial, peerChartSeries);
        }

        if (showChartCollapsed && chartBitmapCollapsed != null) {
            setImageViewBitmapIfPresent(remoteViews, R.id.notification_chart, chartBitmapCollapsed);
            remoteViews.setViewVisibility(R.id.chart_container, View.VISIBLE);
            remoteViews.setViewVisibility(R.id.notification_chart, View.VISIBLE);
        } else {
            remoteViews.setViewVisibility(R.id.chart_container, View.GONE);
            remoteViews.setViewVisibility(R.id.notification_chart, View.GONE);
        }

        if (showChart && chartBitmapExpanded != null) {
            setImageViewBitmapIfPresent(remoteViewsExpanded, R.id.notification_chart, chartBitmapExpanded);
            remoteViewsExpanded.setViewVisibility(R.id.notification_chart, View.VISIBLE);
        } else {
            remoteViewsExpanded.setViewVisibility(R.id.notification_chart, View.GONE);
        }

        // 4. Bind to Builder
        var GluNotBuilder = mkbuilder(type);
        GluNotBuilder.setOnlyAlertOnce(once);

        setIcon(GluNotBuilder, displayGlucoseValue, glucose.sensorgen2, peerValueItems);

        GluNotBuilder.setVisibility(VISIBILITY_PUBLIC);

        if (Build.VERSION.SDK_INT >= 24) {
            GluNotBuilder.setStyle(new Notification.DecoratedCustomViewStyle());
            GluNotBuilder.setCustomContentView(remoteViews);
            GluNotBuilder.setCustomBigContentView(remoteViewsExpanded);
        } else {
            GluNotBuilder.setContent(remoteViews);
        }

        GluNotBuilder.setShowWhen(true);

        // Standard priority logic
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            GluNotBuilder.setTimeoutAfter(glucosetimeout);
        }
        if (isWearable) {
            GluNotBuilder.setAutoCancel(true);
        }
        if (once)
            GluNotBuilder.setPriority(Notification.PRIORITY_DEFAULT);
        else {
            GluNotBuilder.setPriority(Notification.PRIORITY_HIGH);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Use CATEGORY_STATUS for regular updates, ALARM for actual alarms (kind logic
                // omitted here for simplicity or passed via 'once' equivalent?)
                // Actually 'kind' was passed but unused in my snippet?
                // Let's assume standard behavior.
                GluNotBuilder.setCategory(Notification.CATEGORY_ALARM);
            }
        }

        Notification notif = GluNotBuilder.build();
        notif.when = System.currentTimeMillis();

        return notif;
    }

    Notification getforgroundnotification() {
        // Use custom layout even for initial notification to show Graph Grid
        final String message = app
                .getString(SensorBluetooth.blueone != null ? R.string.connectwithsensor : R.string.exchangedata);

        long endT = System.currentTimeMillis();
        long startT = endT - 3 * 60 * 60 * 1000L;
        boolean isMmol = Applic.unit == 1;
        final String activeSensorSerial = NotificationHistorySource.resolveSensorSerial(resolvePrimarySensorName());

        final CurrentDisplaySource.Snapshot current = resolveNotificationCurrentSnapshot(activeSensorSerial);

        java.util.List<GlucosePoint> chartPoints;
        try {
            chartPoints = NotificationHistorySource.getDisplayHistory(startT, isMmol, activeSensorSerial);
        } catch (Exception e) {
            chartPoints = new java.util.ArrayList<>();
        }

        // Same canonical trend list as the update path, the dashboard hero and the broadcast.
        java.util.List<GlucosePoint> nativePoints = DisplayTrendSource.resolveTrendPoints(chartPoints, current,
                activeSensorSerial);

        chartPoints = DisplayTrendSource.augmentHistory(chartPoints, current, activeSensorSerial, startT);

        // Identify ViewMode for Startup
        int viewMode = 0;
        if (activeSensorSerial != null && SensorBluetooth.blueone != null) {
            synchronized (SensorBluetooth.gattcallbacks) {
                for (SuperGattCallback cb : SensorBluetooth.gattcallbacks) {
                    if (cb.SerialNumber != null && cb.SerialNumber.equals(activeSensorSerial)) {
                        break;
                    }
                }
            }
        }
        if (viewMode == 0) {
            try {
                String sensorName = activeSensorSerial;
                if (sensorName == null || sensorName.isEmpty()) {
                    sensorName = resolvePrimarySensorName();
                }
                viewMode = resolveSensorViewMode(sensorName);
            } catch (Throwable t) {
            }
        }

        // Startup Text using the shared current-value resolver.
        CharSequence startupValue = "---";
        if (current != null) {
            startupValue = current.getFullFormatted();
        } else if (!chartPoints.isEmpty()) {
            // Fallback if Natives.lastglucose() is not ready but history is
            // Manual fall back logic if formatGlucoseText can't be used (no string value)
            GlucosePoint latest = chartPoints.get(chartPoints.size() - 1);
            // Also check staleness of history
            long now = System.currentTimeMillis();
            if (Math.abs(now - latest.timestamp) < 15 * 60 * 1000L) {
                String vStr = format(usedlocale, pureglucoseformat, latest.value);

                if (viewMode == 3 && latest.rawValue > 0.1f) {
                    String rStr = format(usedlocale, pureglucoseformat, latest.rawValue);
                    // startupValue = rStr + " · " + vStr;
                    startupValue = rStr + "/ " + vStr;
                } else {
                    startupValue = vStr;
                }
            }
        }
        // Check if chart is enabled
        // Check if chart is enabled
        android.content.SharedPreferences prefs = Applic.app
                .getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE);
        boolean showChart = prefs.getBoolean("notification_chart_enabled", true);
        float fontSize = prefs.getFloat("notification_font_size", 1.0f);
        int fontWeight = prefs.getInt("notification_font_weight", 400);
        int fontFamily = prefs.getInt("notification_font_family", 0); // 0=App, 1=System

        Bitmap chartBitmapCollapsed = null;
        Bitmap chartBitmapExpanded = null;

        if (showChart) {
            // Create Safe Context for Startup Notification too
            Context safeContext = Applic.app;
            try {
                android.util.DisplayMetrics systemDm = android.content.res.Resources.getSystem().getDisplayMetrics();
                android.content.res.Configuration config = new android.content.res.Configuration(
                        Applic.app.getResources().getConfiguration());
                config.densityDpi = (int) (systemDm.density * 160f);
                safeContext = Applic.app.createConfigurationContext(config);
            } catch (Throwable t) {
            }

            float density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
            int collapsedHeight = (int) (48 * density);
            if (collapsedHeight < 48)
                collapsedHeight = 48;

            // Collapsed: Compact Mode = TRUE, Height 48dp
            chartBitmapCollapsed = NotificationChartDrawer.drawChartWithPrediction(safeContext, chartPoints, 0, collapsedHeight,
                    isMmol,
                    viewMode, true, false, true, activeSensorSerial);

            // Expanded: Compact Mode = FALSE, Height 256dp (via 0)
            chartBitmapExpanded = NotificationChartDrawer.drawChartWithPrediction(safeContext, chartPoints, 0, 0, isMmol,
                    viewMode, true, false, false, activeSensorSerial);
        }

        Bitmap arrowBitmap;

        // Semantic Color Logic for Startup
        float colorVal = 0f;
        if (current != null) {
            colorVal = current.getPrimaryValue();
        } else if (!chartPoints.isEmpty()) {
            colorVal = chartPoints.get(chartPoints.size() - 1).value;
        }
        int glucoseColor = NotificationChartDrawer.getGlucoseColor(Applic.app, colorVal, isMmol);

        float startupRate = DisplayTrendSource.resolveArrowRate(nativePoints, current, viewMode, isMmol, 0f);

        arrowBitmap = NotificationChartDrawer.drawArrow(Applic.app, startupRate, isMmol,
                glucoseColor);

        // Use native TextView for startup value
        RemoteViews remoteViews = new RemoteViews(Applic.app.getPackageName(), R.layout.notification_material);

        // Apply Font Logic
        // Hoisted variables
        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(startupValue);
        CharSequence finalText = ssb;
        String family = "sans-serif";
        boolean isBold = false;

        if (fontFamily == 0) { // App Font (IBM Plex) - Render as Bitmap
            remoteViews.setViewVisibility(R.id.notification_glucose, View.GONE);
            remoteViews.setViewVisibility(R.id.notification_glucose_image, View.VISIBLE);

            try {
                // Measure text using TextPaint to support Spans
                android.text.TextPaint textPaint = new android.text.TextPaint();
                textPaint.setAntiAlias(true);
                textPaint.setColor(glucoseColor);
                textPaint.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                        24 * fontSize, Applic.app.getResources().getDisplayMetrics()));

                android.graphics.Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(Applic.app,
                        R.font.ibm_plex_sans_var);
                textPaint.setTypeface(tf);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    textPaint.setFontVariationSettings("'wght' " + fontWeight + ", 'wdth' 100");
                }

                int desiredWidth = (int) Math.ceil(android.text.Layout.getDesiredWidth(startupValue, textPaint));
                // Add padding
                int width = desiredWidth + 4;
                if (width <= 0)
                    width = 100;

                // Create StaticLayout
                // Use deprecated constructor for broad compatibility (or logic check)
                // For simplicity in this file, standard deprecated constructor works well on
                // Android.
                android.text.StaticLayout layout = new android.text.StaticLayout(startupValue, textPaint, width,
                        android.text.Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);

                int height = layout.getHeight() + 4;
                if (height <= 0)
                    height = 50;

                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(width, height,
                        android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);

                // Draw layout
                canvas.translate(2, 2); // padding
                layout.draw(canvas);

                remoteViews.setImageViewBitmap(R.id.notification_glucose_image, bmp);

            } catch (Exception e) {
                // Fallback to text view if bitmap creation fails
                remoteViews.setViewVisibility(R.id.notification_glucose, View.VISIBLE);
                remoteViews.setViewVisibility(R.id.notification_glucose_image, View.GONE);
                remoteViews.setTextViewText(R.id.notification_glucose, startupValue);
            }

        } else { // System Font (Google Sans / Roboto) - Use TextView
            remoteViews.setViewVisibility(R.id.notification_glucose, View.VISIBLE);
            remoteViews.setViewVisibility(R.id.notification_glucose_image, View.GONE);

            remoteViews.setViewVisibility(R.id.notification_glucose_image, View.GONE);

            // ssb already initialized above

            if (fontWeight >= 500) {
                family = "google-sans-medium";
            } else {
                family = "google-sans";
            }
            // If system defaults to sans-serif because google-sans missing, it handles it.

            ssb.setSpan(new android.text.style.TypefaceSpan(family), 0, ssb.length(),
                    android.text.Spannable.SPAN_INCLUSIVE_INCLUSIVE);

            if (fontSize != 1.0f) {
                ssb.setSpan(new android.text.style.RelativeSizeSpan(fontSize), 0, ssb.length(),
                        android.text.Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            }

            remoteViews.setTextViewText(R.id.notification_glucose, ssb);
            remoteViews.setTextColor(R.id.notification_glucose, glucoseColor);
            remoteViews.setTextViewTextSize(R.id.notification_glucose, android.util.TypedValue.COMPLEX_UNIT_SP,
                    24 * fontSize);

            // Do not call setFontVariationSettings via RemoteViews: some OEMs (Huawei/EMUI)
            // reject this method and crash with RemoteServiceException (bad notification).

            finalText = ssb;
        }

        remoteViews.setImageViewBitmap(R.id.notification_arrow, arrowBitmap);

        if (showChart && chartBitmapCollapsed != null) {
            remoteViews.setViewVisibility(R.id.chart_container, View.VISIBLE);
            remoteViews.setImageViewBitmap(R.id.notification_chart, chartBitmapCollapsed);
            remoteViews.setViewVisibility(R.id.notification_chart, View.VISIBLE);
        } else {
            remoteViews.setViewVisibility(R.id.chart_container, View.GONE);
            remoteViews.setViewVisibility(R.id.notification_chart, View.GONE);
        }

        // Apply Style to Status Text (message)
        CharSequence styledMessage = message;
        if (message != null && !message.isEmpty()) {
            android.text.SpannableStringBuilder ssbMsg = new android.text.SpannableStringBuilder(message);
            ssbMsg.setSpan(new android.text.style.TypefaceSpan(family), 0, ssbMsg.length(),
                    android.text.Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            if (isBold) {
                ssbMsg.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0,
                        ssbMsg.length(), android.text.Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            }
            styledMessage = ssbMsg;
        }

        // Same explicit night-aware color as the live notification — the
        // layout's theme attribute renders near-invisible on dark shades.
        final boolean startupShadeNight = (Applic.app.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        final int startupStatusColor = startupShadeNight ? 0xB3FFFFFF : 0x8A000000;
        remoteViews.setViewVisibility(R.id.notification_status, View.VISIBLE);
        remoteViews.setTextViewText(R.id.notification_status, styledMessage);
        remoteViews.setTextColor(R.id.notification_status, startupStatusColor);

        RemoteViews remoteViewsExpanded = new RemoteViews(Applic.app.getPackageName(),
                R.layout.notification_material_regular_expanded);
        remoteViewsExpanded.setTextViewText(R.id.notification_glucose, finalText);
        remoteViewsExpanded.setTextColor(R.id.notification_glucose, glucoseColor);
        // Apply size and weight to expanded startup notification
        remoteViewsExpanded.setTextViewTextSize(R.id.notification_glucose, android.util.TypedValue.COMPLEX_UNIT_SP,
                28 * fontSize);
        // Keep expanded RemoteViews free of font-variation method calls for OEM compatibility.

        remoteViewsExpanded.setImageViewBitmap(R.id.notification_arrow, arrowBitmap);

        if (showChart && chartBitmapExpanded != null) {
            remoteViewsExpanded.setImageViewBitmap(R.id.notification_chart, chartBitmapExpanded);
            remoteViewsExpanded.setViewVisibility(R.id.notification_chart, View.VISIBLE);
        } else {
            remoteViewsExpanded.setViewVisibility(R.id.notification_chart, View.GONE);
        }
        remoteViewsExpanded.setViewVisibility(R.id.notification_status, View.VISIBLE);
        remoteViewsExpanded.setTextViewText(R.id.notification_status, styledMessage);
        remoteViewsExpanded.setTextColor(R.id.notification_status, startupStatusColor);

        var GluNotBuilder = mkbuilder(GLUCOSENOTIFICATION);
        if (Build.VERSION.SDK_INT >= 24) {
            GluNotBuilder.setStyle(new Notification.DecoratedCustomViewStyle());
            GluNotBuilder.setCustomContentView(remoteViews);
            GluNotBuilder.setCustomBigContentView(remoteViewsExpanded);
        } else {
            GluNotBuilder.setContent(remoteViews);
        }
        GluNotBuilder.setSmallIcon(R.drawable.novalue).setOnlyAlertOnce(true).setContentTitle(message)
                .setShowWhen(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            GluNotBuilder.setVisibility(VISIBILITY_PUBLIC);
            GluNotBuilder.setCategory(Notification.CATEGORY_SERVICE);
        }
        GluNotBuilder.setOngoing(true);
        Notification not = GluNotBuilder.build();
        not.flags |= FLAG_ONGOING_EVENT;
        return not;
    }

    private Notification.Builder mkbuilder(String type) {
        var build = mkbuilderintent(type, mkpending());
        build.setDeleteIntent(DeleteReceiver.getDeleteIntent());
        return build;
    }

    // static final private boolean alertseperate=true;

    void fornotify(Notification notif) {
        {
            if (doLog) {
                Log.i(LOG_ID, "fornotify ");
            }
            // Notify AOD/widgets of update immediately. The shared data-refresh
            // path also emits this, so the helper rate-limits duplicate sends.
            GlucoseUpdateBroadcaster.send(Applic.app);

            ;
        }
        ;
        if (isWearable) {
            WearOngoingActivity.updateStatus(Applic.app, glucosenotificationid);
            notificationManager.notify(glucosealarmid, notif);
        } else {
            if (keeprunning.theservice != null) {
                keeprunning.theservice.startForeground(glucosenotificationid, notif);
            } else {
                notificationManager.notify(glucosenotificationid, notif);
            }
        }
    }
    // static final long glucosetimeout=1000*60*3;

    /*
     * @SuppressWarnings("deprecation")
     * void oldnotification(long time) {
     * String message= Applic.app.getString(R.string.nonewvalue)+
     * timef.format(time);
     * {if(doLog) {Log.i(LOG_ID,"oldnotification "+message);};};
     * var GluNotBuilder=mkbuilder(GLUCOSENOTIFICATION);
     * if (Build.VERSION.SDK_INT < 31) {
     * GluNotBuilder.setStyle(new Notification.DecoratedCustomViewStyle());
     * }
     * GluNotBuilder.setContentTitle(message).setSmallIcon(R.drawable.novalue).
     * setPriority(Notification.PRIORITY_DEFAULT);
     * if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
     * GluNotBuilder.setVisibility(VISIBILITY_PUBLIC);
     * }
     * if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
     * GluNotBuilder.setTimeoutAfter(glucosetimeout);
     * }
     * // RemoteViews remoteViews= new
     * RemoteViews(Applic.app.getPackageName(),R.layout.smallnotification);
     * // GluNotBuilder.setContent(remoteViews);
     * Notification notif= GluNotBuilder.build();
     * fornotify(notif);
     * {if(doLog) {Log.i(LOG_ID,"end oldnotification");};};
     * }
     */
    void oldnotification(long time) {
        final String tformat = timef.format(time);
        String message = Applic.getContext().getString(R.string.nonewvalue) + tformat;
        placelargenotification(R.drawable.novalue, message, GLUCOSENOTIFICATION, true);
    }

    @SuppressWarnings("deprecation")
    private Notification makenotification(int draw, String message, String type, boolean once) {
        var GluNotBuilder = mkbuilder(type);

        if (TargetSDK < 31 || Build.VERSION.SDK_INT < 31) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                GluNotBuilder.setStyle(new Notification.DecoratedCustomViewStyle());
            }
        }
        {
            if (doLog) {
                Log.i(LOG_ID, "makenotification " + message);
            }
            ;
        }
        ;

        GluNotBuilder.setSmallIcon(draw).setOnlyAlertOnce(once).setContentTitle(message).setShowWhen(true);

        if (!isWearable) {
            RemoteViews remoteViews = new RemoteViews(app.getPackageName(), R.layout.text);
            remoteViews.setTextColor(R.id.content, foregroundcolor);
            remoteViews.setTextViewText(R.id.content, message);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                GluNotBuilder.setCustomContentView(remoteViews);
            } else
                GluNotBuilder.setContent(remoteViews);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            GluNotBuilder.setVisibility(VISIBILITY_PUBLIC);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            GluNotBuilder.setTimeoutAfter(glucosetimeout);
        }
        if (isWearable) {
            GluNotBuilder.setAutoCancel(true);
        }
        if (once)
            GluNotBuilder.setPriority(Notification.PRIORITY_DEFAULT);
        else {
            // GluNotBuilder.setPriority(Notification.PRIORITY_HIGH);
            GluNotBuilder.setPriority(Notification.PRIORITY_MAX);
            // GluNotBuilder.setDefaults(Notification.DEFAULT_VIBRATE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                GluNotBuilder.setCategory(Notification.CATEGORY_ALARM);
            }
        }

        {
            if (doLog) {
                Log.i(LOG_ID, (once ? "" : "not ") + "only once");
            }
            ;
        }
        ;

        Notification notif = GluNotBuilder.build();
        notif.when = System.currentTimeMillis();
        return notif;

    }

    static public void shownovalue() {
        init(Applic.app);
        onenot.novalue();
    }

    private void novalue() {
        {
            if (doLog) {
                Log.i(LOG_ID, "novalue");
            }
            ;
        }
        ;

        fornotify(getforgroundnotification());
        // notificationManager.notify(glucosenotificationid,getforgroundnotification());
    }
    public void foregroundno(Service service) {
        Notification not = getforgroundnotification();
        if (isWearable) {
            not = WearOngoingActivity.attach(service, not, glucosenotificationid);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            service.startForeground(glucosenotificationid, not,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            service.startForeground(glucosenotificationid, not);
        }
        {
            if (doLog) {
                Log.i(LOG_ID, "startforeground");
            }
            ;
        }
        ;
    }

    static public void foregroundnot(Service service) {
        // Application app=service.getApplication();
        init(service);
        onenot.foregroundno(service);
    }

    public void placelargenotification(int draw, String message, String type, boolean once) {
        hasvalue = true;
        fornotify(makenotification(draw, message, type, once));

    }

    static int testtimes = 1;
    /*
     * static void testnot() {
     * float gl=11.4f;
     * var timmsec= System.currentTimeMillis()-1000;
     * float rate=(float)(1.6*Math.pow(-1,testtimes));
     * --testtimes;
     * boolean waiting=false;
     * var sglucose=new notGlucose(timmsec,
     * format(Applic.usedlocale,Notify.pureglucoseformat, gl) , rate);
     * // Notify.onenot.normalglucose(sglucose,gl, rate,waiting);
     * // var dr=GlucoseDraw.getgludraw(gl);
     * Notify.onenot.makearrownotification(2,gl,"message",sglucose,
     * GLUCOSENOTIFICATION ,false);
     * }
     * 
     * static void test2() {
     * float gl=7.8f;
     * float rate=0.0f;
     * SuperGattCallback.dowithglucose("Serialnumber", (int)(gl*18f), gl,rate,
     * 0,System.currentTimeMillis()) ;
     * }
     */

    public void arrowplacelargenotification(int kind, float glvalue, String message, notGlucose glucose, String type,
            boolean once) {
        hasvalue = true;
        fornotify(makearrownotification(kind, glvalue, message, glucose, type, once));
        if (once && GLUCOSENOTIFICATION.equals(type)) {
            scheduleInteractiveNotificationRefresh();
        }

    }

    public void lossofsensornotification(int draw, String message, String type, boolean once) {
        {
            if (doLog) {
                Log.i(LOG_ID, "notify " + message);
            }
            ;
        }
        ;
        fornotify(makenotification(draw, message, type, once));
    }

    public void arrowglucosenotification(int kind, float glvalue, String message, notGlucose glucose, String type,
            boolean once) {
        {
            if (doLog) {
                Log.i(LOG_ID, "notify " + message);
            }
            ;
        }
        ;
        fornotify(makearrownotification(kind, glvalue, message, glucose, type, once));
        if (once && GLUCOSENOTIFICATION.equals(type)) {
            scheduleInteractiveNotificationRefresh();
        }
    }

    final private int numalarmid = 81432;

    static DateFormat timef = DateFormat.getTimeInstance(DateFormat.SHORT);

    public static void mkDateformat() {
        timef = DateFormat.getTimeInstance(DateFormat.SHORT);
    };

    Notification.Builder NumNotBuilder = null;

    @SuppressWarnings("deprecation")
    public void notifyer(int draw, String message, String type, int notid) {
        if (doLog)
            Log.d(LOG_ID, "notifyer called: type=" + type + " id=" + notid);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NumNotBuilder = new Notification.Builder(Applic.app, type);
        } else
            NumNotBuilder = new Notification.Builder(Applic.app);

        // notificationManager.cancel(glucosenotificationid);

        NumNotBuilder.setAutoCancel(true).setContentIntent(mkpending())
                .setDeleteIntent(DeleteReceiver.getDeleteIntent()).setContentTitle(message);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            NumNotBuilder.setVisibility(VISIBILITY_PUBLIC);
            NumNotBuilder.setCategory(Notification.CATEGORY_ALARM);
        }
        var timemess = timef.format(System.currentTimeMillis()) + ": " + message;

        if (!isWearable) {
            RemoteViews NumRemoteViewss = new RemoteViews(Applic.app.getPackageName(), R.layout.numalarm);
            NumRemoteViewss.setInt(R.id.text, "setBackgroundColor", WHITE);
            NumRemoteViewss.setTextColor(R.id.text, BLACK);
            NumRemoteViewss.setTextViewText(R.id.text, timemess);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NumNotBuilder.setCustomContentView(NumRemoteViewss);
            } else
                NumNotBuilder.setContent(NumRemoteViewss);
        }

        NumNotBuilder.setSmallIcon(draw).setPriority(Notification.PRIORITY_MAX);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NumNotBuilder.setTimeoutAfter(1000L * 60 * 60 * 2);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            NumNotBuilder.setGroup("aa1");
        }
        try {
            notificationManager.notify(notid, NumNotBuilder.build());
            if (doLog)
                Log.d(LOG_ID, "notifyer: notification posted successfully to " + type);
        } catch (Exception e) {
            Log.e(LOG_ID, "notifyer: failed to post notification: " + e.toString());
        }
    }

    public void amountalarm(String message) {
        try {
            mksound(3);
            notifyer(R.drawable.numalarm, message, NUMALARM, numalarmid);
        } catch (Throwable e) {
            Log.stack(LOG_ID, e);
        }
    }

    // final private int lossalarmid=77332;
    public void lossalarm(long time) {
        {
            if (doLog) {
                Log.i(LOG_ID, "lossalarm");
            }
            ;
        }
        ;
        final String tformat = timef.format(time);
        final String message = "***  " + Applic.getContext().getString(R.string.nonewvalue) + tformat + " ***";

        // oldfloatmessage(tformat, true) ;
        lossofsignalalarm(4, R.drawable.loss, message, alertChannelForKind(4), true);
    }

}
