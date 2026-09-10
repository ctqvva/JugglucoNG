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
/*      Sun Mar 10 11:40:55 CET 2024                                                 */


package tk.glucodata;

import static android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.media.AudioAttributes.USAGE_UNKNOWN;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.DontTalk;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Natives.getInvertColors;
import static tk.glucodata.Natives.getVoicePitch;
import static tk.glucodata.Natives.getVoiceSeparation;
import static tk.glucodata.Natives.getVoiceSpeed;
import static tk.glucodata.Natives.getVoiceTalker;
import static tk.glucodata.Natives.lastglucose;
import static tk.glucodata.Natives.settouchtalk;
import static tk.glucodata.Notify.notification_audio;
import static tk.glucodata.NumberView.avoidSpinnerDropdownFocus;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.getGenSpin;
import static tk.glucodata.settings.Settings.getProfileSpinner;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.settings.Settings.scheduleProfiles;
import static tk.glucodata.settings.Settings.str2float;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;
import static tk.glucodata.util.getlocale;
import static tk.glucodata.Notify.doTurnFocuson;
import static tk.glucodata.Notify.doTurnFocusoff;
import static tk.glucodata.Notify.audiofocusrequest;
import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;

public class Talker {
static public final String LOG_ID="Talker";
    private TextToSpeech engine;
    volatile boolean engineReady = false;

static    private float curpitch=1.0f;
static  private float curspeed=1.0f;
// volatile: written on the UI thread by the settings dialog, read on the BLE callback thread
// by selspeak(). A non-volatile long has neither atomicity (JLS 17.7) nor visibility.
static private volatile long cursep=50*1000L;
static private int voicepos=-1;
static private String playstring=null;
static private Spinner spinner=null;
//static final private int minandroid=24; //21
static final private int minandroid=21; //21

static void getvalues() {
if(!DontTalk) {
    float speed=getVoiceSpeed( );
    if(speed!=0.0f) {
        voicepos=getVoiceTalker( );
        cursep=getVoiceSeparation( )*1000L;
        curspeed=speed;
        curpitch=getVoicePitch( );
        SuperGattCallback.dotalk= Natives.getVoiceActive();
        }
        }
    }

static final private ArrayList<Voice> voiceChoice=new ArrayList<>();
static final private ArrayList<Runnable> voiceListeners=new ArrayList<>();

private static void notifyVoiceListeners() {
    final ArrayList<Runnable> listeners;
    synchronized(voiceListeners) {
        listeners=new ArrayList<>(voiceListeners);
        }
    if(listeners.isEmpty())
        return;
    Applic.RunOnUiThread(() -> {
        for(var listener:listeners) {
            try {
                listener.run();
                }
            catch(Throwable th) {
                Log.stack(LOG_ID,"voiceListener",th);
                }
            }
        });
    }

public static void addVoiceOptionsListener(Runnable listener) {
    if(listener==null)
        return;
    synchronized(voiceListeners) {
        if(!voiceListeners.contains(listener))
            voiceListeners.add(listener);
        }
    }

public static void removeVoiceOptionsListener(Runnable listener) {
    if(listener==null)
        return;
    synchronized(voiceListeners) {
        voiceListeners.remove(listener);
        }
    }

static String getFriendlyVoiceName(Voice voice) {
    String systemName = voice.getName();
    if (systemName.contains("-x-")) {
        String id = systemName.split("-x-")[1].split("-")[0];
        if (id.startsWith("msm")) return "Device Voice";
        switch (id) {
            case "iob": return "Voice A";
            case "iog": return "Voice B";
            case "iol": return "Voice C";
            case "iom": return "Voice D";
            case "sfg": return "Voice E";
            case "tpc": return "Voice F";
            case "tpd": return "Voice G";
            case "tpf": return "Voice H";
            default:    return "Voice (" + id.toUpperCase() + ")";
        }
    }
    return systemName;
}

private static volatile Runnable previewDoneCallback;

public static void setPreviewDoneListener(Runnable r) {
    previewDoneCallback = r;
}

public static void previewVoice(int index) {
    if (DontTalk) return;
    var talk = SuperGattCallback.talker;
    if (talk == null || !talk.engineReady) return;
    Voice voice;
    synchronized (voiceChoice) {
        if (index < 0 || index >= voiceChoice.size()) return;
        voice = voiceChoice.get(index);
    }
    talk.setvalues();
    talk.engine.setVoice(voice);
    talk.speakPreview();
}

private void speakPreview() {
    if (DontTalk) return;
    try {
        if (android.os.Build.VERSION.SDK_INT >= minandroid) {
            engine.setAudioAttributes(mediaAudio);
            engine.speak("This is a voice preview.", TextToSpeech.QUEUE_FLUSH, null, "voice_preview");
            engine.setAudioAttributes(notification_audio);
        } else {
            engine.speak("This is a voice preview.", TextToSpeech.QUEUE_FLUSH, null);
        }
    } catch (Throwable th) {
        Log.stack(LOG_ID, "speakPreview failed", th);
    }
}

public static void stopPreview() {
    var talk = SuperGattCallback.talker;
    if (talk != null && talk.engine != null) {
        talk.engine.stop();
        talk.setvoice();
    }
    var cb = previewDoneCallback;
    if (cb != null) Applic.RunOnUiThread(cb);
}

public static ArrayList<String> getVoiceNames() {
    ArrayList<String> names=new ArrayList<>();
    synchronized(voiceChoice) {
        for(var voice:voiceChoice) {
            names.add(getFriendlyVoiceName(voice));
            }
        }
    return names;
    }

public static int getSelectedVoiceIndex() {
    getvalues();
    return voicepos;
    }

public static int getSeparationSeconds() {
    getvalues();
    return (int)(cursep/1000L);
    }

public static float getSelectedSpeed() {
    getvalues();
    return curspeed;
    }

public static float getSelectedPitch() {
    getvalues();
    return curpitch;
    }

public static void ensureComposeTalker(Context context) {
    if(!DontTalk&&SuperGattCallback.talker==null)
        SuperGattCallback.newtalker(context);
    }

public static void applyComposeSettings(Context context, boolean speakGlucose, boolean touchTalk, boolean speakMessages, boolean speakAlarms, boolean mediaSound, boolean overrideSilent, float speed, float pitch, int separationSeconds, int selectedVoice) {
    if(DontTalk)
        return;
    curspeed=speed;
    curpitch=pitch;
    cursep=Math.max(1,separationSeconds)*1000L;
    nexttime=System.currentTimeMillis()+cursep;
    if(selectedVoice>=0)
        voicepos=selectedVoice;

    if(overrideSilent) {
        Natives.setSoundType(AudioAttributes.USAGE_ALARM);
        }
    else if(mediaSound) {
        Natives.setSoundType(AudioAttributes.USAGE_MEDIA);
        }
    else {
        Natives.setSoundType(isWearable ? USAGE_ASSISTANCE_SONIFICATION : AudioAttributes.USAGE_NOTIFICATION);
        }
    Notify.makenotification_audio();

    SuperGattCallback.dotalk = speakGlucose;
    settouchtalk(touchTalk);
    Natives.setspeakmessages(speakMessages);
    Natives.setspeakalarms(speakAlarms);
    Natives.saveVoice(curspeed,curpitch,(int)(cursep/1000L),voicepos,SuperGattCallback.dotalk);

    final boolean hasAny=speakGlucose||touchTalk||speakMessages||speakAlarms;
    if(hasAny&&SuperGattCallback.talker==null)
        SuperGattCallback.newtalker(context);
    var talk=SuperGattCallback.talker;
    if(talk!=null) {
        talk.setvalues();
        talk.setvoice();
        }
    }

public static void finishComposeSession() {
    if(!DontTalk&&!shouldtalk())
        SuperGattCallback.endtalk();
    }

public static void selectProfile(Context context,int profile) {
    if(DontTalk)
        return;
    if(profile==Natives.getProfile())
        return;
    Natives.setProfile(profile);
    SuperGattCallback.initAlarmTalk();
    ensureComposeTalker(context);
    notifyVoiceListeners();
    }

static final AudioAttributes mediaAudio = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build();

public static void testCurrentValue(Context context) {
    if(DontTalk)
        return;
    var current = CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout);
    var say=current!=null?current.getSpeechPrimaryStr():context.getString(R.string.tts_missed_readings);
    var talk=SuperGattCallback.talker;
    if(talk!=null && talk.engineReady) {
        talk.setvalues();
        talk.setvoice();
        // Use media stream so test is audible regardless of notification mute
        if(android.os.Build.VERSION.SDK_INT >= minandroid)
            talk.speak(say, mediaAudio);
        else
            talk.speak(say);
        return;
        }
    // Engine not ready yet — queue for after init
    playstring=say;
    if(talk==null)
        SuperGattCallback.newtalker(context);
    }

void setvalues() {
if(!DontTalk) {
   var gine=engine;
    if(gine!=null) {
        var loc=getlocale();
        gine.setLanguage(loc);
        gine.setPitch( curpitch);
        gine.setSpeechRate( curspeed);
        }
       }
    }
void setvoice() {
if(!DontTalk) {
    if(engine==null)
        return;
    if(voicepos>=0&& voicepos<voiceChoice.size()) {
        var vo= voiceChoice.get(voicepos);
        engine.setVoice(voiceChoice.get(voicepos));
        {if(doLog) {Log.i(LOG_ID,"after setVoice "+vo.getName());};};
        }
    else {
        {if(doLog) {Log.i(LOG_ID,"setVoice out of range");};};
        voicepos=0;
        }
      }
    }
void destruct() {
if(!DontTalk) {
    engineReady = false;
    if(engine!=null) {
        engine.shutdown();
        engine=null;
        }
    synchronized(voiceChoice) {
        voiceChoice.clear();
        }
    }
    }

 Talker(Context cont) {
if(!DontTalk) {

//    var context=cont==null?Applic.getContext():cont;
    var context=Applic.app;
     engine=new TextToSpeech(context, new TextToSpeech.OnInitListener() {
       @Override
      public void onInit(int status) {
        
       try {
         var gine=engine;
         if(gine==null) {
            Log.e(LOG_ID,"engine==null");
            return;
            }
         if(status ==TextToSpeech.SUCCESS) {
            engineReady = true;
            setvalues();
            if (android.os.Build.VERSION.SDK_INT >= minandroid) {
                Set<Voice> voices=gine.getVoices();
                if(voices==null) {
                    {if(doLog) {Log.i(LOG_ID,"voices==null");};};
                    }
                else {
                    var loc=getlocale();
                    var lang=loc.getLanguage();
                    {if(doLog) {Log.i(LOG_ID,"lang="+lang);};};

                    var filtered=new ArrayList<Voice>();
                    for(var voice:voices) {
                        if(!lang.equals(voice.getLocale().getLanguage())) continue;
                        // Skip network-only voices — not reliably available offline
                        if(voice.isNetworkConnectionRequired()) continue;
                        // Skip voices that are listed but not actually installed
                        if(voice.getFeatures().contains(android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                        // Skip base locale placeholder — not a selectable voice
                        if(voice.getName().endsWith("-language")) continue;
                        filtered.add(voice);
                        }
                    filtered.sort(Comparator.comparing(Voice::getName));
                    synchronized(voiceChoice) {
                        voiceChoice.clear();
                        voiceChoice.addAll(filtered);
                        }
                    var spin=spinner;
                    if(spin!=null) {
                        {if(doLog) {Log.i(LOG_ID,"Talker spinner!=null");};};
                          Applic.RunOnUiThread(() -> {
                            spin.setAdapter(new RangeAdapter<Voice>(voiceChoice, Applic.app, voice -> {
                                    return getFriendlyVoiceName(voice);
                                    }));
                            if(voicepos>=0&&voicepos<voiceChoice.size())
                                spin.setSelection(voicepos);
                            });
                        }
                    }
                setvoice();
                notifyVoiceListeners();
            }
             if(playstring!=null) {
                 speak(playstring);
                 playstring=null;
                 }
           }
         else {
             Log.e(LOG_ID,"status = TextToSpeech.ERROR ");
             }
         {if(doLog) {Log.i(LOG_ID,"after onInit");};};
          }
       catch(Throwable th) {
         Log.stack(LOG_ID,"Talker.onInit",th);
         }
            }
          });
    engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {

 //  AudioFocusRequest audiofocusrequest = (android.os.Build.VERSION.SDK_INT <26)?null:new AudioFocusRequest.Builder( AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes( notification_audio ).build();
        @Override
        public void onDone(String utteranceId) {
            if(doLog) {Log.i(LOG_ID,"onDone "+utteranceId);};
            if(!notifyfocus)
                doTurnFocusoff();
            if ("voice_preview".equals(utteranceId)) {
                var cb = previewDoneCallback;
                if (cb != null) Applic.RunOnUiThread(cb);
            }
        }

        @Override
        public void onError(String utteranceId) {
            if(doLog) {Log.i(LOG_ID,"onError "+utteranceId);};
            if(!notifyfocus)
                doTurnFocusoff();
            if ("voice_preview".equals(utteranceId)) {
                var cb = previewDoneCallback;
                if (cb != null) Applic.RunOnUiThread(cb);
            }
            }
        @Override
        public void onStart(String utteranceId) {
            if(doLog) {Log.i(LOG_ID,"onStart "+utteranceId);};
            if(!notifyfocus)
                doTurnFocuson() ;
            }

    });
    if(doLog) {Log.i(LOG_ID,"after new TextToSpeech");};
    if(android.os.Build.VERSION.SDK_INT >= minandroid)
        engine.setAudioAttributes(notification_audio);
        }
   }

// Fraction of the stream's max volume that is the minimum allowed for TTS output.
// Prevents silence when the user (or system) drives the stream to zero.
private static final float MIN_TTS_VOLUME_FRACTION = 0.25f;

private static int usageToStreamType(int usage) {
    switch (usage) {
        case AudioAttributes.USAGE_ALARM:  return AudioManager.STREAM_ALARM;
        case AudioAttributes.USAGE_MEDIA:  return AudioManager.STREAM_MUSIC;
        default:                           return AudioManager.STREAM_NOTIFICATION;
    }
}

private static void ensureMinStreamVolume() {
    try {
        AudioManager am = (AudioManager) Applic.app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        final int stream = usageToStreamType(Natives.getSoundType());
        final int maxVol = am.getStreamMaxVolume(stream);
        final int minVol = Math.max(1, Math.round(maxVol * MIN_TTS_VOLUME_FRACTION));
        if (am.getStreamVolume(stream) < minVol) {
            am.setStreamVolume(stream, minVol, 0);
        }
    } catch (Throwable th) {
        Log.stack(LOG_ID, "ensureMinStreamVolume", th);
    }
}

/**
 * Hand an utterance to the engine.
 *
 * @return true only when the engine accepted it, i.e. when an
 *         {@link UtteranceProgressListener} callback is guaranteed to follow. Callers that
 *         ration announcements must not charge the separation interval for an utterance that
 *         never reached the engine (see {@link #selspeak}), and callers holding transient audio
 *         focus must release it themselves on false.
 */
public boolean speak(String message) {
    if(!DontTalk) {
        try {
            ensureMinStreamVolume();
            int speakResult=(android.os.Build.VERSION.SDK_INT >= 21)
                    ? engine.speak(message, TextToSpeech.QUEUE_FLUSH, null,message)
                    : engine.speak(message, TextToSpeech.QUEUE_FLUSH, null);
            if(speakResult==TextToSpeech.SUCCESS) {
                consecutiveSpeakFailures=0;
                if(doLog) {Log.i(LOG_ID,"success speak "+message);}
                return true;
                }
             else {
                consecutiveSpeakFailures++;
                Log.e(LOG_ID,"failed speak "+message+" consecutiveFailures="+consecutiveSpeakFailures);
                }
            }
        catch(Throwable th) {
            // A dead/unbound TTS engine can fail by throwing (e.g. a dead Binder) rather than
            // returning a non-SUCCESS result - count it the same way, or needsReinit() never
            // trips for that failure mode.
            consecutiveSpeakFailures++;
            Log.stack(LOG_ID,"speak failed",th);
            }
        }
    return false;
    }

// Tracks real engine health, as opposed to istalking() which only checks that
// SuperGattCallback.talker is non-null. A talker object surviving a TTS-service disconnect
// (e.g. com.google.android.tts auto-updating overnight, which unbinds the TextToSpeech
// connection permanently until reconstructed) still passes istalking(), so nothing ever healed
// it. Observed in the field: "TextToSpeech: Disconnected from TTS engine" once after a
// com.google.android.tts package REPLACE, then 172 consecutive "failed speak"/"not bound to TTS
// engine" results with zero recovery over 4+ days.
private volatile int consecutiveSpeakFailures=0;
private static final int REINIT_FAILURE_THRESHOLD=2;
/** How long selspeak() defers after an utterance the engine would not take. Short enough that
 *  the next reading retries, so a failed attempt costs one reading rather than one whole
 *  user-configured separation interval. */
private static final long FAILED_SPEAK_RETRY_MS=30_000L;

/** True once repeated real speak failures show the engine is dead and must be reconstructed. */
public boolean needsReinit() {
    return consecutiveSpeakFailures>=REINIT_FAILURE_THRESHOLD;
    }
static boolean notifyfocus=false;
//private static final AudioAttributes notification_audio = (new AudioAttributes.Builder()) .setLegacyStreamType(TextToSpeech.Engine.DEFAULT_STREAM) .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH) .build(); 
//private static final AudioAttributes notification_audio =(android.os.Build.VERSION.SDK_INT >= 21)?new AudioAttributes.Builder().setUsage(isWearable? USAGE_ASSISTANCE_SONIFICATION:USAGE_NOTIFICATION ) .build():null;
//private static final AudioAttributes notification_audio = notification_audio;
/**
 * Speak with a one-shot audio-attribute override, restoring {@link Notify#notification_audio}
 * afterwards. This is the overload the alarm path uses.
 *
 * <p>Reads {@code engine} into a local: {@link #destruct} nulls it from another thread, so the
 * two setAudioAttributes() calls bracketing the utterance could each dereference null while an
 * alarm was being announced. The single-argument {@link #speak(String)} swallows that in its own
 * try/catch; this overload did not, so the throw escaped onto the caller's thread - for
 * {@link Notify} that is a scheduler thread mid-alarm.
 *
 * @return true only when the engine accepted the utterance. See {@link #speak(String)}.
 */
public boolean speak(String message, AudioAttributes attr) {
    if(DontTalk)
        return false;
    final TextToSpeech gine=engine;
    if(gine==null) {
        Log.e(LOG_ID,"speak(message,attr): engine already shut down, dropping \""+message+"\"");
        return false;
        }
    final boolean override=android.os.Build.VERSION.SDK_INT >= minandroid && attr!=notification_audio;
    boolean spoken=false;
    try {
        if(override) {
            gine.setAudioAttributes(attr);
            }
        spoken=speak(message);
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"speak(message,attr)",th);
        }
    finally {
        // Restore in a finally: leaving the override in place would apply the alarm's
        // attributes to every subsequent routine announcement.
        if(override) {
            try {
                gine.setAudioAttributes(notification_audio);
                }
            catch(Throwable th) {
                Log.stack(LOG_ID,"speak(message,attr) restore",th);
                }
            }
         }
    return spoken;
    }
// volatile: written and read from the announce path and the settings/UI thread alike.
volatile static long nexttime=0L;
void selspeak(String message) {
    if(!DontTalk) {
        var now=System.currentTimeMillis();
        if(now>nexttime && SpeakSchedule.INSTANCE.isWithinSchedule(Applic.app)) {
            // Claim the slot before speaking, so two readings arriving back to back cannot both
            // announce - then hand nearly all of it back if the engine refused the utterance.
            // Charging a failed attempt the full separation interval made the engine-health
            // check's detection latency scale with a user setting, which is backwards:
            // consecutiveSpeakFailures counts announcement *attempts*, and attempts only happen
            // once per cursep, so REINIT_FAILURE_THRESHOLD=2 meant a dead engine went unnoticed
            // for 2 x cursep - 10 minutes at a 300s separation, 33 minutes at 999s, with every
            // further failure costing another interval of silence. Retrying on the next reading
            // instead makes detection ~2 minutes regardless of how the user set the interval.
            nexttime=now+cursep;
            if(!speak(message)) {
                nexttime=now+Math.min(cursep,FAILED_SPEAK_RETRY_MS);
                }
            }
          }
    }
static final private double base2=Math.log(2);
static final private double multiplyer=10000.0/base2;
private static int ratio2progress(float ratio) {
    if(DontTalk)
        return 0;
     else {
        if(ratio<0.18)
            return 0;
        return (int)Math.round(Math.log(ratio)*multiplyer)+25000;
          }
    }
private static float progress2ratio(int progress) {
    if(DontTalk)
        return 0;
     else {
        return (float)Math.exp((double)(progress-25000)/multiplyer);
        }
    }

private static View[] slider(MainActivity context,float init) {
    if(!DontTalk) {
        var speed=new SeekBar(context);
    //    speed.setMin(-25000);
        speed.setMax(50000);
        speed.setProgress(ratio2progress(init));
        var displayspeed=new EditText(context);
    //    displayspeed.setPadding(0,0,0,0);
            displayspeed.setImeOptions(editoptions);
        displayspeed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        displayspeed.setMinEms(2);
        String formstr=String.format(Locale.US, "%.2f",init);
        speed.setLayoutParams(new ViewGroup.LayoutParams(  MATCH_PARENT, WRAP_CONTENT));
        displayspeed.setText( formstr);

            
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public  void onProgressChanged (SeekBar seekBar, int progress, boolean fromUser) {
                float num=progress2ratio(progress);
                String form=String.format(Locale.US, "%.2f",num);
                displayspeed.setText( form);
                {if(doLog) {Log.i(LOG_ID,"onProgressChanged "+form);};};
                }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                {if(doLog) {Log.i(LOG_ID,"onStartTrackingTouch");};};
                }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                {if(doLog) {Log.i(LOG_ID,"onStopTrackingTouch");};};
                }
            });

            displayspeed.setOnEditorActionListener( new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER || actionId == EditorInfo.IME_ACTION_DONE) {
                     {if(doLog) {Log.i(LOG_ID,"onEditorAction");};};
                    var speedstr=v.getText().toString();
                    if(speedstr != null) {
                        float    curspeed = str2float(speedstr);
                        speed.setProgress(ratio2progress(curspeed));
                        {if(doLog) {Log.i(LOG_ID,"onEditorAction: "+speedstr+" "+curspeed);};};
                         tk.glucodata.help.hidekeyboard(context);
                        }
                    return true;
                   }
                return false;
                }});
                /*
            displayspeed.addTextChangedListener( new TextWatcher() {
                       public void afterTextChanged(Editable ed) {
                var speedstr=ed.toString();
                if(speedstr != null) {
                    float    curspeed = str2float(speedstr);
                    speed.setProgress(ratio2progress(curspeed));
                    {if(doLog) {Log.i(LOG_ID,"afterTextChanged: "+speedstr+" "+curspeed);};};
                    }

                           }
                       public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                       public void onTextChanged(CharSequence s, int start, int before, int count) { }
                      });*/

        return new View[] {speed,displayspeed};
        }
    else return null;
    }
public static boolean shouldtalk() {
    if(!DontTalk) {
        return  SuperGattCallback.dotalk||Natives.gettouchtalk()||Natives.speakmessages()||Natives.speakalarms();
        }
    else
        return false;
    }
public  static boolean istalking() {
    if(DontTalk)
        return false;
    else
        return SuperGattCallback.talker!=null;
    }
private static void cleanupConfigOverlay(MainActivity context, View layout) {
    tk.glucodata.help.hidekeyboard(context);
    removeContentView(layout);
    spinner=null;
    if(Menus.on)
        Menus.show(context);
    context.lightBars(!getInvertColors( ));
    }

public static View createConfigView(MainActivity context, Runnable onClose) {
    return createConfigView(context, onClose, null);
    }

public static View createConfigView(MainActivity context, Runnable onClose, Runnable onProfileChange) {
    return makeConfigView(context,false,onClose,onProfileChange);
    }

private static View makeConfigView(MainActivity context, boolean overlayMode, Runnable onClose, Runnable onProfileChange) {
    if(DontTalk) {
        return new View(context);
        }
    if(!istalking()) {
        SuperGattCallback.newtalker(context);
        }

    var separation=new EditText(context);

    separation.setImeOptions(editoptions);
    separation.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    separation.setMinEms(2);
    int sep=(int)(cursep/1000L);
    separation.setText(sep+"");
    var seplabel=getlabel(context,context.getString(R.string.secondsbetween));
    float density=GlucoseCurve.metrics.density;
    int pad=(int)(density*3);
    seplabel.setPadding(pad*3,0,0,0);
    var speeds=slider(context,curspeed);

    var pitchs=slider(context,curpitch);
    var cancel=getbutton(context,R.string.cancel);

    var save=getbutton(context,R.string.save);
    var width= GlucoseCurve.getwidth();
    var speedlabel=getlabel(context,context.getString(R.string.speed));
    speedlabel.setPadding(pad,0,pad*2,0);
    var pitchlabel=getlabel(context,context.getString(R.string.pitch));
    pitchlabel.setPadding(pad,0,pad*2,0);
    var voicelabel=getlabel(context,context.getString(R.string.talker));
    var active=getcheckbox(context,R.string.speakglucose, SuperGattCallback.dotalk);
    active.setPadding(0,0,pad*3,0);

    var mediasound=getcheckbox(context,"MEDIA", Natives.getSoundType( )==AudioAttributes.USAGE_MEDIA);
    mediasound.setOnCheckedChangeListener(
             (buttonView,  isChecked) -> {
                 if (isChecked) {
                     Natives.setSoundType(AudioAttributes.USAGE_MEDIA);
                 } else {
                     Natives.setSoundType(isWearable ? USAGE_ASSISTANCE_SONIFICATION : AudioAttributes.USAGE_NOTIFICATION);
                 }
                 Notify.makenotification_audio();
                    }
                    );


    var test=getbutton(context,context.getString(R.string.test));
    if(spinner!=null) {
            {if(doLog) {Log.i(LOG_ID, "Talker.config spinner=!null");};};
            try {
            ViewGroup par = (ViewGroup) spinner.getParent();
            if (par != null)
                par.removeView(spinner);
            }
        catch (Throwable th) {
            Log.stack(LOG_ID,"spinner",th);
            }
        }
    else
            Log.i(LOG_ID,"Talker.config spinner==null");
    var spin= spinner!=null?spinner:((android.os.Build.VERSION.SDK_INT >= minandroid)? (spinner=getGenSpin(context)):null);

    int[] spinpos={-1};
    var touchtalk= getcheckbox(context,context.getString(R.string.talk_touch), Natives.gettouchtalk());
    var speakmessages= getcheckbox(context,context.getString(R.string.speakmessages), Natives.speakmessages());
    var speakalarms= getcheckbox(context,context.getString(R.string.speakalarms), Natives.speakalarms());
    var pro=getProfileSpinner(context);
    int curProfile=Natives.getProfile();
    pro.setSelection(curProfile);
    pro.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
    @Override
    public  void onItemSelected (AdapterView<?> parent, View view, int position, long id) {
        if(position!=curProfile) {
           Natives.setProfile(position);
           SuperGattCallback.initAlarmTalk();
           if(overlayMode) {
               MainActivity.doonback();
               config(context);
               }
           else if(onProfileChange!=null) {
               onProfileChange.run();
               }
           }
        }
    @Override
    public  void onNothingSelected (AdapterView<?> parent) {

    } });
    if(android.os.Build.VERSION.SDK_INT >= minandroid) {
        spin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public  void onItemSelected (AdapterView<?> parent, View view, int position, long id) {
                {if(doLog) {Log.i(LOG_ID,"onItemSelected "+position);};};

                spinpos[0]=position;
                }
            @Override
            public  void onNothingSelected (AdapterView<?> parent) {

            } });
        spin.setAdapter(new RangeAdapter<Voice>(voiceChoice, context, voice -> {
                return getFriendlyVoiceName(voice);
                }));
        {if(doLog) {Log.i(LOG_ID,"voicepos="+voicepos);};};
        if(voicepos>=0&&voicepos<voiceChoice.size())
            spin.setSelection(voicepos);
        spinpos[0]=-1;
        }
    ViewGroup layout;
    var schedules=getbutton(context,R.string.schedules);

    if(isWearable) {
        int marg=(int)(width*.1f);
         Layout.getMargins(voicelabel).topMargin=marg;
         Layout.getMargins(cancel).leftMargin=marg;
         Layout.getMargins(test).rightMargin=marg;
         Layout.getMargins(separation).rightMargin=marg;
        var lay=new Layout(context,(l,w,h)-> {
            return new int[] {w,h};
            },

             new View[]{voicelabel},new View[]{spin},new View[]{active},new View[]{seplabel,separation}, new View[]{touchtalk},new View[]{ speakmessages},new View[]{speakalarms },new View[]{speedlabel},new View[]{speeds[1],speeds[0]},new View[]{pitchlabel},new View[]{pitchs[1],pitchs[0]},new View[]{mediasound},  new View[]{pro},new View[]{schedules},new View[]{cancel,test},new View[]{save});

        var scroll=new ScrollView(context);
        scroll.addView(lay);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(false);
       scroll.setScrollbarFadingEnabled(true);
       scroll.setVerticalScrollBarEnabled(Applic.scrollbar);
        layout=scroll;
       }
    else {
        View[]  firstrow;
        if(android.os.Build.VERSION.SDK_INT >= minandroid) {
             firstrow=new View[]{active,seplabel,separation,voicelabel,spin};
             }
        else {
            var space=new Space(context);
            space.setMinimumWidth((int)(width*0.4));
            firstrow=new View[]{active,seplabel,separation,space};
          }
        var secondrow=new View[]{touchtalk, speakmessages, speakalarms };
        Layout.getMargins(cancel).leftMargin=Layout.getMargins(save).rightMargin=(int)(width*.05f);
        var helpview=getbutton(context,R.string.helpname);
        helpview.setOnClickListener(v-> help.help(R.string.talkhelp,context));
        layout= new Layout(context,(l,w,h)-> {
            return new int[] {w,h};
            },firstrow,secondrow,new View[]{speedlabel,speeds[1],speeds[0],pro},new View[]{pitchlabel,pitchs[1],pitchs[0],mediasound}, new View[]{cancel,helpview,schedules,test,save});
        }

    final var lay=layout;
    schedules.setOnClickListener(v->  {
        scheduleProfiles(context,lay);
    });
    layout.setBackgroundColor( Applic.backgroundcolor);
    cancel.setOnClickListener(v->  {
        if(onClose!=null)
            onClose.run();
        });
    Runnable getvalues=()-> {
        try {
            if (android.os.Build.VERSION.SDK_INT >= minandroid) {
                int pos=spinpos[0];
                if(pos>=0) {
                    voicepos=pos;
                    }
                  }
             var str = separation.getText().toString();
            if(str != null) {
                cursep = Integer.parseInt(str)*1000L;
                var now=System.currentTimeMillis();
                nexttime=now+cursep;
                }
            var speedstr=((EditText)speeds[1]).getText().toString();
            if(speedstr != null) {
                curspeed = str2float(speedstr);
                {if(doLog) {Log.i(LOG_ID,"speedstr: "+speedstr+" "+curspeed);};};
                }
            var pitchstr=((EditText)pitchs[1]).getText().toString();
            if(pitchstr != null) {
                curpitch = str2float(pitchstr);
                {if(doLog) {Log.i(LOG_ID,"pitchstr: "+pitchstr+" "+curpitch);};};
                }
            } catch(Throwable th) {
                Log.stack(LOG_ID,"parseInt",th);
                }

         };
    save.setOnClickListener(v->  {
        getvalues.run();

        if(active.isChecked()||touchtalk.isChecked()||speakmessages.isChecked()||speakalarms.isChecked()) {
            SuperGattCallback.newtalker(context);
            SuperGattCallback.dotalk = active.isChecked();
            settouchtalk(touchtalk.isChecked());
            Natives.setspeakmessages(speakmessages.isChecked());
            Natives.setspeakalarms(speakalarms.isChecked());
            }
        else {
            settouchtalk(false);
            Natives.setspeakmessages(false);
            Natives.setspeakalarms(false);
            SuperGattCallback.endtalk();
            }
        Natives.saveVoice(curspeed,curpitch,(int)(cursep/1000L),voicepos,SuperGattCallback.dotalk);

        if(onClose!=null)
            onClose.run();
        });
    test.setOnClickListener(v->  {
        var current = CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout);
        var say=current!=null?current.getSpeechPrimaryStr():context.getString(R.string.tts_missed_readings);
        getvalues.run();
        if(istalking()) {
            var talk=SuperGattCallback.talker;
            if(talk!=null) {
                talk.setvalues();
                talk.setvoice();
                talk.speak(say);
                return;
                }
            }
        playstring=say;
        SuperGattCallback.newtalker(context);
        });

    if(overlayMode) {
        var top=MainActivity.systembarTop;
        var left=MainActivity.systembarLeft;
        layout.setPadding(left+(int)(density*5.0),top,MainActivity.systembarRight+(int)(density*8.0),MainActivity.systembarBottom);
        context.lightBars(false);
        }
    else {
        int sidepad=(int)(density*8.0f);
        int toppad=(int)(density*4.0f);
        int bottompad=(int)(density*12.0f);
        layout.setPadding(sidepad,toppad,sidepad,bottompad);
        }

    final var trackedSpinner=spin;
    layout.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            tk.glucodata.help.hidekeyboard(context);
            if(spinner==trackedSpinner) {
                spinner=null;
                }
        }
    });
    return layout;
    }

public static void config(MainActivity context) {
    if(!DontTalk) {
        final View layout=makeConfigView(context,true,()-> context.doonback(),null);
        MainActivity.setonback(()-> cleanupConfigOverlay(context,layout));
        context.addContentView(layout, new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
        }
  }
}
/*
TODO
van EditText naar Slider
Hoe met test?
Probleem: veranderingen moeten eerst uitgevoerd zijn voordat test mogelijk is.
Mogelijkheden:
- 
*/
