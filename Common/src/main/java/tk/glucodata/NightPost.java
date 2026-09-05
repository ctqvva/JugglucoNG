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
/*      Thu Mar 23 21:04:47 CET 2023                                                 */


package tk.glucodata;
import static android.view.View.GONE;
import static java.net.HttpURLConnection.HTTP_OK;

import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Backup.getedit;
import static tk.glucodata.Layout.getMargins;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Log.stackline;
import static tk.glucodata.Natives.setNightUploader;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.Specific.useclose;
import static tk.glucodata.bluediag.datestr;
import static tk.glucodata.help.help;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Toast;

import androidx.annotation.Keep;

import com.google.android.gms.security.ProviderInstaller;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;

import tk.glucodata.settings.LibreNumbers;

public class NightPost  {
    private static final String LOG_ID="NightPost";
    private static final int ERROR_INVALID_URL = -2;
    /** No HTTP status to report: the request never left, or no answer came back. */
    public static final int ERROR_NO_RESPONSE = -1;
    /** Ancillary upload skipped: no token cached and none could be fetched. */
    private static final int ERROR_NO_TOKEN = -3;
    private static final int UPLOAD_CONNECT_TIMEOUT_MS = 15_000;
    private static final int UPLOAD_READ_TIMEOUT_MS = 60_000;
    private static final int DEVICE_STATUS_CONNECT_TIMEOUT_MS = 3_000;
    private static final int DEVICE_STATUS_READ_TIMEOUT_MS = 5_000;
    private static final int ANCILLARY_TOKEN_CONNECT_TIMEOUT_MS = 10_000;
    private static final int ANCILLARY_TOKEN_READ_TIMEOUT_MS = 15_000;
    private static final AtomicBoolean deviceStatusUploadInFlight = new AtomicBoolean(false);
    /**
     * Outcome of the IOB devicestatus upload, for the settings screen. The status card
     * otherwise only reports the native entries uploader, so a dead devicestatus path was
     * invisible outside logcat - and a "no token" skip must not read like a server
     * rejection. The battery devicestatus shares the queue but not this state: it is not
     * what the screen names.
     */
    public static final int DEVICE_STATUS_NONE=0;
    public static final int DEVICE_STATUS_ACCEPTED=1;
    public static final int DEVICE_STATUS_NO_TOKEN=2;
    public static final int DEVICE_STATUS_REJECTED=3;
    /** Outcome and its HTTP code travel together, so the screen cannot pair one with the other's code. */
    private record IobDeviceStatusState(int outcome,int code) {}
    private static volatile IobDeviceStatusState iobDeviceStatus=new IobDeviceStatusState(DEVICE_STATUS_NONE,0);

    private static void setIobDeviceStatusOutcome(int code) {
        final int outcome;
        if(code==HTTP_OK || code==HttpURLConnection.HTTP_CREATED)
            outcome=DEVICE_STATUS_ACCEPTED;
        else if(code==ERROR_NO_TOKEN)
            outcome=DEVICE_STATUS_NO_TOKEN;
        else
            outcome=DEVICE_STATUS_REJECTED;
        iobDeviceStatus=new IobDeviceStatusState(outcome,code);
    }

    /** Forgets the last outcome, so corrected settings do not keep showing the old failure. */
    public static void clearDeviceStatusOutcome() {
        iobDeviceStatus=new IobDeviceStatusState(DEVICE_STATUS_NONE,0);
    }

    public static int getDeviceStatusOutcome() {
        return iobDeviceStatus.outcome();
    }

    public static int getDeviceStatusLastCode() {
        return iobDeviceStatus.code();
    }
    private static final ThreadPoolExecutor deviceStatusExecutor = new ThreadPoolExecutor(
            0,
            1,
            30L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1),
            runnable -> {
                Thread thread = new Thread(runnable, "Nightscout-device-status");
                thread.setDaemon(true);
                return thread;
            }
    );

private static synchronized void patch() {
      try {
          ProviderInstaller.installIfNeeded(Applic.app);
      }
    catch(Throwable th) {
        uploadstatus= "ProviderInstaller.installIfNeeded: \n"+stackline(th);
         Log.e(LOG_ID,uploadstatus);
          }
      }

static String getstring(HttpURLConnection con)  throws IOException{
    var stream = con.getErrorStream();
    if(stream == null) {
        stream = con.getInputStream();
    }
    if(stream == null) {
        return "";
    }
    try(BufferedReader in = new BufferedReader(new InputStreamReader(stream))) {
        StringBuffer response = new StringBuffer();
        String inputLine;
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
            }
        return response.toString();
        }
    finally {
        con.disconnect();
        }
    }

private static  String getstart(HttpURLConnection con,int max)  throws IOException{
    try(var in = con.getInputStream()) {
        int len=max;
        byte[] buf=new byte[len];
        int res=in.read(buf,0,len);
        return new String(buf,0,res);
        }
    finally {
        con.disconnect();
        }
    }

final  static String nothing=Applic.getContext().getString(R.string.triednothing).intern();
final static String success=Applic.getContext().getString(R.string.success).intern();
static private String uploadstatus=nothing;
/**
 * Body of the last answer the primary (treatment) path got from Nightscout, for the
 * journal uploader. A refusal there carries the reason in the body ("Missing permission
 * api:treatments:update"), and the bare status code the call returns is not enough to
 * act on it. Empty when the last request never got an answer.
 */
private static volatile String lastPrimaryResponseBody="";
/** Response paired with the native uploader's status code, kept separate from treatments. */
private static volatile String lastNativeUploadResponseBody="";

@Keep
static public String getLastPrimaryResponseBody() {
    return lastPrimaryResponseBody;
    }

@Keep
static public String getLastNativeUploadResponseBody() {
    return lastNativeUploadResponseBody;
    }

/**
 * Deletes a Nightscout document; true when the server confirms it is gone.
 * Called from native (uploadtreatment.cpp) via JNI, so the signature stays boolean.
 * New callers should prefer {@link #deleteUrlCode}, which keeps the status apart
 * from "already gone" and from a network failure worth retrying.
 */
@Keep
static public boolean deleteUrl(String urlstring,String secret) {
    final int code=deleteUrlCode(urlstring,secret);
    return code==HTTP_OK || code==HttpURLConnection.HTTP_NO_CONTENT;
    }

/**
 * DELETEs a Nightscout document and reports the HTTP status.
 *
 * @return the response code, {@link #ERROR_NO_RESPONSE} when the request could not be
 *         made or answered at all (bad URL, missing token, network failure).
 */
@Keep
static public int deleteUrlCode(String urlstring,String secret) {
    patch();
    uploadtime=System.currentTimeMillis();
    {if(doLog) {Log.i(LOG_ID,"deleteUrl "+urlstring);};};
    HttpURLConnection urlConnection=null;
    try {
        URL url = new URL(urlstring);
    uploadstatus=" start deleteURL "+urlstring;
        urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setConnectTimeout(UPLOAD_CONNECT_TIMEOUT_MS);
        urlConnection.setReadTimeout(UPLOAD_READ_TIMEOUT_MS);
        if(secret!=null)
                urlConnection.setRequestProperty("api-secret", secret);
        else {
            final String auth = gettoken(
                    uploadtime,
                    UPLOAD_CONNECT_TIMEOUT_MS,
                    UPLOAD_READ_TIMEOUT_MS,
                    true
            );
            if(auth.isEmpty()) {
                return ERROR_NO_RESPONSE;
            }
            urlConnection.setRequestProperty("Authorization", auth);
        }
        urlConnection.setRequestProperty("Content-Type", "application/json");
        urlConnection.setRequestMethod("DELETE");

        final int code=urlConnection.getResponseCode();
        String res=getstring(urlConnection);
        lastPrimaryResponseBody=res;
        if(code==HTTP_OK || code==HttpURLConnection.HTTP_NO_CONTENT) {
            {if(doLog) {Log.i(LOG_ID,"deleteUrl success "+res);};};
            uploadstatus=success;
            }
        else {
            String delerror="deleteUrl "+urlstring+" failure code="+code+'\n'+res;
            Log.e(LOG_ID,delerror);
            uploadstatus=delerror;
            }
        return code;
        }
    catch(Throwable th) {
        lastPrimaryResponseBody="";
        String error ="deleteUrl error:\n"+stackline(th);
        uploadstatus=error;
        Log.e(LOG_ID,error);
        return ERROR_NO_RESPONSE;
        }
    finally {
        if(urlConnection!=null)
            urlConnection.disconnect();
        }
    }

/*
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhY2Nlc3NUb2tlbiI6ImFhcHMtOTQ0Y2YzZGVkYTMxMTkxNCIsImlhdCI6MTcwODg1NDE1NiwiZXhwIjoxNzA4ODgyOTU2fQ.YrNGSUPiz-3zxv6ZxfOO_Sm98bKrK0eDjZYIR6LPQUY",
  "sub": "aaps",
  "permissionGroups": [
    [
      "*"
    ],
    []
  ],
  "iat": 1708854156,
  "exp": 1708882956
} */
static private long  expire=0L;
static private String token="";

static JSONObject  readJSONObject(HttpURLConnection urlConnection)  throws IOException, JSONException {
    String ant=getstring(urlConnection);
    if(doLog) {
        Log.format("%s: readJSONObject len=%d %s",LOG_ID,ant.length(),ant);
        }
     return new JSONObject(ant);
    }

/**
 * One token exchange with Nightscout: the grant when it gave one, otherwise the reason it
 * did not, in its own words where it had any.
 */
public record TokenExchange(NightscoutTokenGrant grant,int code,String error) {
    public boolean ok() { return grant!=null; }
    }

private static TokenExchange exchangeToken(int connectTimeoutMs,int readTimeoutMs) {
    var Nighturl=Natives.getnightuploadurl();
    var secret=Natives.getnightuploadsecret();
    var authstr=Nighturl+ "/api/v2/authorization/request/"+secret;
    HttpURLConnection urlConnection=null;
    try {
        URL url = new URL(authstr);
        urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setConnectTimeout(connectTimeoutMs);
        urlConnection.setReadTimeout(readTimeoutMs);
        urlConnection.setRequestMethod("GET");
        final int code=urlConnection.getResponseCode();
        final String body=getstring(urlConnection);
        if(doLog) {
            Log.format("%s: token exchange code=%d len=%d %s",LOG_ID,code,body.length(),body);
            }
        final NightscoutTokenGrant grant=code==HTTP_OK?NightscoutTokenGrant.parse(body):null;
        if(grant!=null)
            return new TokenExchange(grant,code,"");
        final String reason=NightscoutTokenGrant.refusalMessage(body);
        return new TokenExchange(null,code,"HTTP "+code+(reason.isEmpty()?"":": "+reason));
        }
    catch(Throwable th) {
        return new TokenExchange(null,-1,th==null?"Network error":String.valueOf(th.getMessage()));
        }
    finally {
        if(urlConnection!=null)
            urlConnection.disconnect();
        }
    }

private static void cacheGrant(NightscoutTokenGrant grant) {
    expire=grant.getExpiresAtMillis();
    token="Bearer "+grant.getToken();
    }

private static synchronized String gettoken(
        long now,
        int connectTimeoutMs,
        int readTimeoutMs,
        boolean updateVisibleStatus
) {
    if(now<expire)
        return token;
    final TokenExchange exchange=exchangeToken(connectTimeoutMs,readTimeoutMs);
    if(exchange.ok()) {
        cacheGrant(exchange.grant());
        return token;
        }
    final String error="gettoken failed "+exchange.error();
    if(updateVisibleStatus)
        uploadstatus=error;
    Log.e(LOG_ID,error);
    return "";
    }

/**
 * Drops the cached token and exchanges the access token for a fresh one right away.
 *
 * <p>Nightscout puts the permissions inside the JWT and this class keeps that JWT until it
 * expires -- hours, typically -- so a role corrected on the server looks like it did nothing
 * until then. This is the settings screen's way out: it forces the exchange and hands back
 * what the new token grants, so whether the change landed can be seen rather than inferred
 * from the next 403. The uploader's status line is left alone; the screen reports this itself.
 */
@Keep
static public synchronized TokenExchange refreshToken() {
    expire=0L;
    token="";
    final TokenExchange exchange=exchangeToken(UPLOAD_CONNECT_TIMEOUT_MS,UPLOAD_READ_TIMEOUT_MS);
    if(exchange.ok())
        cacheGrant(exchange.grant());
    else
        Log.e(LOG_ID,"token refresh failed "+exchange.error());
    return exchange;
    }

/**
 * Authorization header for v3 requests made outside this class, reusing the token the v3
 * upload path already fetches and caches. Empty when none could be had, so the caller can
 * fall back rather than send a header it knows is wrong.
 *
 * <p>Leaves the visible upload status alone: a read failing must not overwrite what the
 * uploader last reported.
 */
@Keep
static public String getV3AuthorizationHeader() {
    return gettoken(System.currentTimeMillis(),UPLOAD_CONNECT_TIMEOUT_MS,UPLOAD_READ_TIMEOUT_MS,false);
    }

private static synchronized String getCachedToken(long now) {
    return now<expire ? token : "";
    }

private static long ancillaryTokenAttemptTime=0L;

/**
 * Token for the ancillary (device-status) path: the cached one when still valid,
 * otherwise at most one fetch per {@link NightscoutIobDeviceStatus#TOKEN_RETRY_MILLIS},
 * quiet on the visible upload status.
 *
 * <p>The fetch does not inherit the caller's device-status timeouts. Those are tight on
 * purpose (a late battery or IOB document is worthless), but a Nightscout instance that
 * has to wake up first would then miss every token request and the cache would stay
 * empty forever, which is the failure this path exists to end. They are still well under
 * the primary uploader's, so a slow auth endpoint cannot park the class lock for a minute.
 */
private static synchronized String getAncillaryToken(long now) {
    final String cached=getCachedToken(now);
    if(!cached.isEmpty())
        return cached;
    if(!NightscoutIobDeviceStatus.tokenRetryDue(now, ancillaryTokenAttemptTime))
        return "";
    ancillaryTokenAttemptTime=now;
    return gettoken(now,ANCILLARY_TOKEN_CONNECT_TIMEOUT_MS,ANCILLARY_TOKEN_READ_TIMEOUT_MS,false);
    }

private static long uploadtime=System.currentTimeMillis();

/**
 * Uploads journal-derived treatments (insulin / carbs / fingerstick) to Nightscout.
 * Replaces the legacy native uploadtreatments() path. Looked up via reflection so
 * the wearable build (which doesn't include the journal Room database) can no-op.
 */
@Keep
static public boolean uploadJournalTreatments(boolean useV3) {
    try {
        Class<?> uploader = Class.forName("tk.glucodata.data.journal.JournalTreatmentUploader");
        if(!Natives.getpostTreatments()) {
            java.lang.reflect.Method receiveMethod = uploader.getMethod("getReceiveTreatments");
            if(!Boolean.TRUE.equals(receiveMethod.invoke(null))) {
                return true;
                }
            }
        java.lang.reflect.Method method = uploader.getMethod("uploadAll", boolean.class);
        Object result = method.invoke(null, useV3);
        return Boolean.TRUE.equals(result);
        }
    catch(ClassNotFoundException nfe) {
        // Wearable build: no journal DB compiled in. Treat as success.
        return true;
        }
    catch(Throwable th) {
        Log.e(LOG_ID,"uploadJournalTreatments error:\n"+stackline(th));
        return false;
        }
    }

/**
 * Phone battery charge as a percentage, or -1 when it cannot be read.
 *
 * <p>Uploaded to Nightscout as {@code devicestatus[].uploader.battery}, which is what the
 * Nightscout UI reads for its uploader-battery pill. Remote followers rely on it to notice a
 * dying phone before glucose data silently stops arriving (issue #113).
 */
@Keep
static public int batteryPercent() {
    try {
        final Context context = Applic.getContext();
        if(context == null)
            return -1;
        final Intent status = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(status == null)
            return -1;
        final int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if(level < 0 || scale <= 0)
            return -1;
        return Math.round(level * 100.0f / scale);
        }
    catch(Throwable th) {
        Log.e(LOG_ID,"batteryPercent error:\n"+stackline(th));
        return -1;
        }
    }

private static final String PREFS_NAME="tk.glucodata_preferences";
private static final String PREF_UPLOAD_IOB="nightscout_upload_iob";
private static long iobStatusUploadTime=0L;
private static float iobStatusLastIob=Float.NaN;
private static float iobStatusLastEiob=Float.NaN;
private static float iobStatusLastCob=Float.NaN;

/**
 * Opt-in: off by default because enabling it changes which source the
 * Nightscout site's IOB pill uses (NG's journal instead of Nightscout's own
 * treatment calculation from its profile DIA).
 */
@Keep
static public boolean getUploadIob() {
    final Context context = Applic.getContext();
    if(context == null)
        return false;
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_UPLOAD_IOB, false);
    }

@Keep
static public void setUploadIob(boolean enabled) {
    final Context context = Applic.getContext();
    if(context == null)
        return;
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_UPLOAD_IOB, enabled)
            .apply();
    }

/**
 * Uploads the journal IOB/eIOB/COB as a Nightscout devicestatus when the
 * opt-in setting is on and one is due. Called from the native uploader loop
 * after entries and treatments; always best-effort, so failures here never
 * hold back glucose. The journal snapshot is the same one the notification
 * and the glucodata broadcast display, so the site shows the phone's number.
 */
@Keep
static public boolean maybeUploadIobDeviceStatus(String httpurl,String secret) {
    try {
        if(!getUploadIob())
            return true;
        final long now=System.currentTimeMillis();
        if(!NightscoutIobDeviceStatus.fastIntervalElapsed(now, iobStatusUploadTime))
            return true;
        final float[] values=JournalIobAccess.snapshot(now);
        if(values==null || values.length<3)
            return true;
        if(!NightscoutIobDeviceStatus.shouldUpload(
                now,
                iobStatusUploadTime,
                values[0],
                values[1],
                values[2],
                iobStatusLastIob,
                iobStatusLastEiob,
                iobStatusLastCob))
            return true;
        //The endpoint the native side picks follows the same setting, so the document has to
        //agree with it: v3 takes a single document with an "app" field, v1 an array.
        final String document=NightscoutIobDeviceStatus.buildDocument(now,values[0],values[1],values[2],Natives.getnightscoutV3());
        if(document==null)
            return true;
        if(uploadDeviceStatusAsync(httpurl,document.getBytes(java.nio.charset.StandardCharsets.UTF_8),secret,false,true)) {
            iobStatusUploadTime=now;
            iobStatusLastIob=values[0];
            iobStatusLastEiob=values[1];
            iobStatusLastCob=values[2];
            }
        return true;
        }
    catch(Throwable th) {
        Log.e(LOG_ID,"maybeUploadIobDeviceStatus error:\n"+stackline(th));
        return true;
        }
    }

@Keep
static public int upload(String httpurl,byte[] postdata,String secret,boolean put) {
    return uploadWithTimeouts(
            httpurl,
            postdata,
            secret,
            put?"PUT":"POST",
            UPLOAD_CONNECT_TIMEOUT_MS,
            UPLOAD_READ_TIMEOUT_MS,
            true,
            "primary"
    );
    }

/** Native entries upload, with a response body that stays paired with its native status. */
@Keep
static public int uploadNative(String httpurl,byte[] postdata,String secret,boolean put) {
    final int code=upload(httpurl,postdata,secret,put);
    lastNativeUploadResponseBody=lastPrimaryResponseBody;
    return code;
    }

/** Partial API v3 document update at a concrete identifier endpoint. */
@Keep
static public int uploadPatch(String httpurl,byte[] postdata,String secret) {
    return uploadWithTimeouts(
            httpurl,
            postdata,
            secret,
            "PATCH",
            UPLOAD_CONNECT_TIMEOUT_MS,
            UPLOAD_READ_TIMEOUT_MS,
            true,
            "primary"
    );
    }

/**
 * Queues uploader-battery status away from the serialized glucose uploader.
 *
 * <p>The native caller throttles attempts. This additional in-flight gate prevents a slow
 * endpoint from accumulating work if a future caller bypasses that throttle.
 *
 * <p>Called from native with this exact signature, so it stays the battery entry point.
 */
@Keep
static public boolean uploadDeviceStatusAsync(
        String httpurl,
        byte[] postdata,
        String secret,
        boolean put
) {
    return uploadDeviceStatusAsync(httpurl,postdata,secret,put,false);
    }

/**
 * @param iobChannel true for the journal IOB document, false for the uploader battery.
 *        Both share this queue, but only the IOB channel reports its outcome to the
 *        settings screen: the status line there names IOB, and a failing battery upload
 *        must not be shown under that name.
 */
static private boolean uploadDeviceStatusAsync(
        String httpurl,
        byte[] postdata,
        String secret,
        boolean put,
        boolean iobChannel
) {
    if(!deviceStatusUploadInFlight.compareAndSet(false, true)) {
        Log.i(LOG_ID,"device-status upload already in flight; dropping duplicate");
        return false;
    }
    try {
        deviceStatusExecutor.execute(() -> {
            try {
                final int code=uploadWithTimeouts(
                        httpurl,
                        postdata,
                        secret,
                        put?"PUT":"POST",
                        DEVICE_STATUS_CONNECT_TIMEOUT_MS,
                        DEVICE_STATUS_READ_TIMEOUT_MS,
                        false,
                        iobChannel?"device-status(iob)":"device-status"
                );
                if(iobChannel)
                    setIobDeviceStatusOutcome(code);
                if(code==HTTP_OK || code==HttpURLConnection.HTTP_CREATED)
                    Log.i(LOG_ID,"device-status upload accepted code="+code);
                else if(code!=ERROR_NO_TOKEN)
                    Log.e(LOG_ID,"device-status upload failed code="+code);
            }
            finally {
                deviceStatusUploadInFlight.set(false);
            }
        });
        return true;
    }
    catch(Throwable th) {
        deviceStatusUploadInFlight.set(false);
        Log.e(LOG_ID,"device-status scheduling failure:\n"+stackline(th));
        return false;
    }
    }

private static int uploadWithTimeouts(
        String httpurl,
        byte[] postdata,
        String secret,
        String requestMethod,
        int connectTimeoutMs,
        int readTimeoutMs,
        boolean updateVisibleStatus,
        String requestKind
) {
    if(updateVisibleStatus)
        patch();
    final long requestTime=System.currentTimeMillis();
    if(updateVisibleStatus)
        uploadtime=requestTime;
    final int payloadLength=postdata==null ? 0 : postdata.length;
    {if(doLog) {Log.i(LOG_ID,requestKind+" upload("+httpurl+",#"+payloadLength+","+requestMethod+")");};};
    HttpURLConnection urlConnection=null;
    try {

      if(httpurl==null || !(httpurl.startsWith("https://") || httpurl.startsWith("http://"))) {
        final String invalid=requestKind+" upload failure:\nInvalid Nightscout URL: "+httpurl;
        if(updateVisibleStatus)
            uploadstatus=invalid;
        Log.e(LOG_ID,invalid);
        return ERROR_INVALID_URL;
      }
      if(postdata==null) {
        final String invalid=requestKind+" upload failure: empty payload";
        if(updateVisibleStatus)
            uploadstatus=invalid;
        Log.e(LOG_ID,invalid);
        return -1;
      }

        if(updateVisibleStatus)
            uploadstatus="start upload "+httpurl;
        URL url = new URL(httpurl);
        urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setConnectTimeout(connectTimeoutMs);
        urlConnection.setReadTimeout(readTimeoutMs);
        urlConnection.setRequestMethod(requestMethod);
        urlConnection.setDoOutput(true);
        if(secret!=null)
            urlConnection.setRequestProperty("api-secret", secret);
        else {
            // The ancillary path used to rely purely on the token a primary upload cached.
            // After a reinstall, process death or token expiry that cache is empty and
            // nothing refills it, so device status stayed dead until a manual resend
            // happened to trigger a primary upload. It now fetches a token itself, but
            // throttled: a refusing server is asked once per interval, not per attempt.
            final String auth = updateVisibleStatus
                    ? gettoken(requestTime, connectTimeoutMs, readTimeoutMs, true)
                    : getAncillaryToken(requestTime);
            if(auth.isEmpty()) {
                Log.e(LOG_ID,requestKind+" upload skipped: no Nightscout token");
                return updateVisibleStatus ? -1 : ERROR_NO_TOKEN;
            }
            urlConnection.setRequestProperty("Authorization", auth);
        }
        urlConnection.setRequestProperty("Content-Type", "application/json");
           urlConnection.setRequestProperty( "Content-Length", Integer.toString( postdata.length ));

        try(OutputStream outputPost = new BufferedOutputStream(urlConnection.getOutputStream())) {
            outputPost.write(postdata);
            outputPost.flush();
        }
        final int code=urlConnection.getResponseCode();
        String res=getstring(urlConnection);
        if(updateVisibleStatus)
            lastPrimaryResponseBody=res;
        final String resstr=requestKind+" upload ResponseCode="+code+"\n"+res;
        if(code!=200&&code!=201) {
            if(updateVisibleStatus)
                uploadstatus=resstr;
            Log.e(LOG_ID,resstr);
            }
        else {
            if(updateVisibleStatus)
                uploadstatus=success;
            {if(doLog) {Log.i(LOG_ID,resstr);};};
            }
        return code;
         }
    catch(Throwable th) {
        final String posterror=requestKind+" upload failure:\n"+stackline(th);
        if(updateVisibleStatus) {
            lastPrimaryResponseBody="";
            uploadstatus=posterror;
            }
        Log.e(LOG_ID,posterror);
        return -1;
        }
    finally {
        if(urlConnection!=null)
            urlConnection.disconnect();
        }
     }
private static    void askclearupload(Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(" ").setMessage(R.string.resendquestion).
           setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
            Natives.resetuploader();
                    }
                }) .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
            }
        }).show().setCanceledOnTouchOutside(false);
    }

public static void  config(MainActivity act, View settingsview) {
    EnableControls(settingsview,false);
    var urllabel=getlabel(act,"Nightscout URL");
    var url=getedit(act, Natives.getnightuploadurl());
//    final int minems=isWearable?12:16;
    final int minems=12;
        url.setMinEms(minems);
    var secretlabel=getlabel(act,R.string.secret);
    secretlabel.setPadding(0,0,0,0);
    var editsecret= new EditText(act);
        editsecret.setImeOptions(editoptions);
        editsecret.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editsecret.setTransformationMethod(new PasswordTransformationMethod());
     editsecret.setMinEms(minems);
    String secretwas=Natives.getnightuploadsecret();
    if(secretwas!=null) {
        editsecret.setText(secretwas);
        }
    var save=getbutton(act,R.string.save);
    var cancel=getbutton(act,R.string.cancel);
    var clear=getbutton(act,R.string.resenddata);
    clear.setOnClickListener(v->  askclearupload(act));
    var wake=getbutton(act,act.getString(R.string.sendnow));
    wake.setOnClickListener(v-> Natives.wakeuploader());
    Button help;
    CheckBox treatments=getcheckbox(act,R.string.sendamounts,Natives.getpostTreatments());
    if(!isWearable) {
        help=getbutton(act,R.string.helpname);
        help.setOnClickListener(v-> help(R.string.NightPost,act));
        }
    final CheckBox v3box=!isWearable?getcheckbox(act,"test V3",Natives.getnightscoutV3()):null;
    boolean useuploader=Natives.getuseuploader();
    var activebox=getcheckbox(act,R.string.active,useuploader);
       var visible = new CheckBox(act);
       visible.setButtonDrawable(R.drawable.password_visible);
      visible.setMinimumWidth(0);
      visible.setMinWidth(0);
        getMargins(wake).topMargin= (int)(GlucoseCurve.metrics.density*7.0);
       //visible.setText(R.string.visible);
    int pad= (int)tk.glucodata.GlucoseCurve.metrics.density*7;
    visible.setPadding(0,0,pad,0);
        visible.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
                        editsecret.setInputType(isChecked?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:InputType.TYPE_TEXT_VARIATION_PASSWORD);
                        if(isChecked)
                                        editsecret.setTransformationMethod(null);
                        else
                                        editsecret.setTransformationMethod(new PasswordTransformationMethod());
                        });

      var statusview=getlabel(act,datestr(uploadtime)+": "+uploadstatus);
      int statuspad=  (int)tk.glucodata.GlucoseCurve.metrics.density*7;
    statusview.setPadding(statuspad,statuspad,statuspad,statuspad);
    if(!useclose)
        cancel.setVisibility(GONE);
   Layout layout;
   if(isWearable) {
      var space1=new Space(act);
      var space2=new Space(act);
       layout=new Layout(act, (lay, w, h) -> { return new int[] {w,h};}, new View[]{secretlabel},new View[]{visible},new View[]{editsecret},new View[]{urllabel},new View[]{url},new View[]{statusview},new View[]{treatments},new View[]{clear},new View[]{wake},new View[]{space1,activebox,cancel,space2},new View[]{save});
   }
      else {
    layout=new Layout(act, (lay, w, h) -> {
        var height=GlucoseCurve.getheight();
        var width=GlucoseCurve.getwidth();
                        if(w>=width||h>=height) {
                                lay.setX(0);
                                }
                        else {
                                lay.setX((width-w)/2); 
                                };

            lay.setY(MainActivity.systembarTop);
                        return new int[] {w,h};}, new View[]{urllabel,url},new View[]{secretlabel,editsecret,visible},new View[]{statusview},new View[]{activebox,v3box,clear,wake},new View[]{treatments,help,cancel,save});

      }
        int laypar;
        final View allview=isWearable?new ScrollView(act):layout;
        if(isWearable) {
            ((ScrollView)allview).addView(layout);
            laypar=ViewGroup.LayoutParams.MATCH_PARENT;
            layout.setBackgroundColor(tk.glucodata.Applic.backgroundcolor);
           int allpad=  (int)tk.glucodata.GlucoseCurve.metrics.density*8;
            layout.setPadding(allpad,allpad,(int)(GlucoseCurve.metrics.density*12.0),allpad);

        treatments.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
              if(isChecked) {
                if(!Natives.canSendNumbers(1)) {
                    Toast.makeText(act, R.string.libresetalllabels, Toast.LENGTH_LONG).show();
                    treatments.setChecked(false);
                    return;
                    }
                  }
                Natives.setpostTreatments(isChecked);
                });
        } else {
            int[] nochangeamounts={0};
            treatments.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
                switch(nochangeamounts[0])  {
                    case 0: {
                        ++nochangeamounts[0];
                        treatments.setChecked(!isChecked);
                        LibreNumbers.mklayout(act,1,treatments,nochangeamounts,layout);
                        };break;
                    case  2: Natives.setpostTreatments(isChecked);break;

                    };
                });
            laypar=ViewGroup.LayoutParams.WRAP_CONTENT;
              allview.setBackgroundResource(R.drawable.dialogbackground);
               allview.setPadding(pad,pad,pad,pad);
               }

        act.addContentView(allview, new ViewGroup.LayoutParams(laypar,laypar));
    Runnable closerun=()-> {
        allview.setVisibility(GONE);
        removeContentView(allview);
        EnableControls(settingsview,true);
        };
    act.setonback(closerun);
    cancel.setOnClickListener(v->  {
            act.poponback();
            closerun.run();
            });
    save.setOnClickListener(v-> {
            act.poponback();
            closerun.run();
            setNightUploader(url.getText().toString(),editsecret.getText().toString(),activebox.isChecked(),isWearable?false:v3box.isChecked());
            });
    
    }
 }
