#!/usr/bin/env python3
"""Run the production glucose-meter reconnect pacing methods against a deterministic alarm clock.
Android Bluetooth and the meter protocols are outside this test; openGatt() is counted, not run.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
gatt = (root / 'Common/src/mobile/java/tk/glucodata/GlucoseMeterGatt.java').read_text()
pacing = (root / 'Common/src/main/java/tk/glucodata/glucosemeter/MeterSyncPacing.kt').read_text()


def method(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
        end += 1
    return source[start:end]


methods = '\n'.join(method(gatt, signature) for signature in [
    'private synchronized void markLinkAnswered(', 'private synchronized void markLinkFailed(',
    'private synchronized void markLinkBroughtNew(', 'private synchronized void resetLink(',
    'boolean staysOn(',
    'synchronized void cancelPacedReconnect(',
    'private synchronized MeterLinkOutcome currentLinkOutcome(',
    'private synchronized long pacedReconnectDelay()',
    'private synchronized long pacedReconnectDelay(int intervalMinutes)',
    'private synchronized boolean shouldDisconnectBeforePacedWait(',
    'private void disconnectIfPaced(',
    'private synchronized boolean consumePacedDisconnect(',
    'private synchronized void schedulePacedReconnect(', 'synchronized void runPacedReconnect(',
    'synchronized void pacingChanged(',
    'public void onConnectionStateChange(',
])
# The pacing rule itself is covered by MeterSyncPacingTests; here the pacer is a recording fake.
assert 'class MeterLinkPacer' in pacing and 'fun linkEnded(outcome: MeterLinkOutcome, staysOn: Boolean, minutes: Int)' in pacing
assert 'fun wouldWait(outcome: MeterLinkOutcome, staysOn: Boolean, minutes: Int)' in pacing
assert 'pacedDisconnectGatt = null;' in method(gatt, 'public synchronized void close(')

java = r'''
package tk.glucodata;
import java.util.*;
import java.util.concurrent.*;

public class GlucoseMeterGatt {
    static final boolean doLog=false;
    static final String LOG_ID="GlucoseMeterGatt";
    static final int BOND_NONE=10, BOND_BONDED=12;
    static class BluetoothDevice {
        static final int BOND_NONE=10, BOND_BONDING=11, BOND_BONDED=12;
        int bondState=BOND_NONE;
        int getBondState(){return bondState;}
    }
    static class BluetoothProfile {
        static final int STATE_DISCONNECTED=0, STATE_CONNECTED=2;
    }
    static String bondString(int state){return ""+state;}
    static class Log {
        static void i(String tag,String msg){}
        static void e(String tag,String msg){}
    }
    static class BluetoothGatt {
        final BluetoothDevice device=new BluetoothDevice();
        int disconnects, connects, closes;
        BluetoothDevice getDevice(){return device;}
        boolean discoverServices(){return true;}
        boolean connect(){connects++;return true;}
        void disconnect(){disconnects++;}
        void close(){closes++;}
    }
    enum MeterLinkOutcome { UNANSWERED, NEW_READINGS, NOTHING_NEW, FAILED }
    static class MeterSyncPacing {
        static int minutes=5;
        static int minutes(){return minutes;}
    }
    static class MeterLinkPacer {
        MeterLinkOutcome outcome; boolean staysOn; int minutes; long nextWait;
        MeterLinkOutcome previewOutcome; boolean previewStaysOn; int previewMinutes; boolean nextWouldWait;
        long linkEnded(MeterLinkOutcome o,boolean s,int m){outcome=o;staysOn=s;minutes=m;return nextWait;}
        boolean wouldWait(MeterLinkOutcome o,boolean s,int m){previewOutcome=o;previewStaysOn=s;previewMinutes=m;return nextWouldWait;}
    }
    // One alarm per meter, as AlarmManager keeps one per PendingIntent; delivery hands the
    // ticket back the way MeterRecheckAlarm.onReceive does.
    static class MeterRecheckAlarm {
        static long now=1_000_000L; static Map<Integer,long[]> alarms=new HashMap<>(); static int cancels;
        static GlucoseMeterGatt meter;
        static void set(int index,long ticket,long delay){alarms.put(index,new long[]{ticket,now+delay});}
        static void cancel(int index){cancels++;alarms.remove(index);}
        static long[] pending(){return alarms.get(meter.meterIndex);}
        static void advance(long delta){long target=now+delta;long[] a=pending();
            if(a!=null&&a[1]<=target){alarms.remove(meter.meterIndex);now=a[1];meter.runPacedReconnect(a[0]);}now=target;}
    }
    final int meterIndex=3;
    boolean autoConnect=true, useConnect=false;
    boolean stop, connected, discovered, isBonded;
    long connectedTime, disconnectedTime;
    Object satelliteSession;
    BluetoothGatt mBluetoothGatt; int opened;
    void updateview(){}
    void openGatt(){opened++;mBluetoothGatt=new BluetoothGatt();}
    void connectActiveOrScan(long delayMillis){}
    void disconnect(){if(mBluetoothGatt!=null)mBluetoothGatt.disconnect();}
    public synchronized void close(){
        if(mBluetoothGatt!=null){
            mBluetoothGatt.disconnect();
            mBluetoothGatt.close();
            mBluetoothGatt=null;
        }
        pacedDisconnectGatt=null;
    }
    private boolean linkAnswered=false;
    private boolean linkFailed=false;
    private boolean linkBroughtNew=false;
    private boolean satelliteMode=false;
    private final MeterLinkPacer pacer=new MeterLinkPacer();
    private boolean pacedWaiting=false;
    private long pacedTicket=0L;
    private BluetoothGatt pacedDisconnectGatt=null;
''' + methods + r'''
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static GlucoseMeterGatt fresh(){MeterRecheckAlarm.alarms.clear();MeterRecheckAlarm.cancels=0;MeterSyncPacing.minutes=5;
        return MeterRecheckAlarm.meter=new GlucoseMeterGatt();}
    public static void main(String[] args){
        // Each link reaches the pacer as one outcome, and the next link starts clean.
        GlucoseMeterGatt m=fresh();m.markLinkAnswered();m.pacedReconnectDelay();
        check(m.pacer.outcome==MeterLinkOutcome.NOTHING_NEW,"clean empty sync not NOTHING_NEW");
        m.pacedReconnectDelay();check(m.pacer.outcome==MeterLinkOutcome.UNANSWERED,"old sync leaked into a later link");
        m.markLinkFailed();m.pacedReconnectDelay();check(m.pacer.outcome==MeterLinkOutcome.FAILED,"failed sync not FAILED");
        m.markLinkFailed();m.markLinkBroughtNew();m.pacedReconnectDelay();
        check(m.pacer.outcome==MeterLinkOutcome.NEW_READINGS,"stored readings hidden by a later error");
        check(m.pacer.minutes==5,"interval not passed through");

        // Early disconnect on finished Satellite sync only when connected, active, matching GATT, and pacer would wait.
        m=fresh();m.satelliteMode=true;m.connected=true;m.markLinkAnswered();m.pacer.nextWouldWait=true;
        BluetoothGatt live=new BluetoothGatt(), stale=new BluetoothGatt(); m.mBluetoothGatt=live;
        check(m.shouldDisconnectBeforePacedWait(),"finished paced sync did not request disconnect");
        check(m.pacer.previewOutcome==MeterLinkOutcome.NOTHING_NEW&&m.pacer.previewStaysOn&&m.pacer.previewMinutes==5,"preview inputs wrong");
        m.disconnectIfPaced(stale);check(stale.disconnects==0&&live.disconnects==0,"stale GATT disconnected");
        m.disconnectIfPaced(live);check(live.disconnects==1,"live GATT not disconnected before paced wait");
        m.disconnectIfPaced(live);check(live.disconnects==1,"duplicate early disconnect on same GATT");
        m.pacer.nextWouldWait=false;
        check(!m.shouldDisconnectBeforePacedWait(),"unpaced sync requested early disconnect");
        m.disconnectIfPaced(live);check(live.disconnects==1,"unpaced sync disconnected live GATT");
        m.pacer.nextWouldWait=true;m.connected=false;
        check(!m.shouldDisconnectBeforePacedWait(),"disconnected link requested early disconnect");
        m.connected=true;m.stop=true;
        check(!m.shouldDisconnectBeforePacedWait(),"stopped meter requested early disconnect");

        // API 26 (useConnect=false): if pacing is switched to Right away (0) after early disconnect
        // but before STATE_DISCONNECTED arrives, onConnectionStateChange must explicitly re-arm connect().
        m=fresh();m.useConnect=false;m.satelliteMode=true;m.connected=true;
        live=new BluetoothGatt();m.mBluetoothGatt=live;
        m.markLinkAnswered();m.pacer.nextWouldWait=true;
        m.disconnectIfPaced(live);check(live.disconnects==1,"early disconnect missing");
        MeterSyncPacing.minutes=0;m.pacer.nextWait=0L;
        m.pacingChanged();check(m.opened==0,"pacingChanged dialled before disconnect completed");
        m.onConnectionStateChange(live,0,BluetoothProfile.STATE_DISCONNECTED);
        check(live.connects==1,"API 26 connection not re-armed after early disconnect turned unpaced");
        check(live.closes==0&&m.mBluetoothGatt==live,"unpaced callback closed GATT");
        check(MeterRecheckAlarm.pending()==null&&m.opened==0,"unpaced callback armed alarm or reopened GATT");
        // Subsequent remote-initiated disconnect on API 26 with pacing=0 must not call connect() again.
        m.onConnectionStateChange(live,0,BluetoothProfile.STATE_CONNECTED);
        m.markLinkAnswered();m.pacer.nextWouldWait=false;
        m.disconnectIfPaced(live);check(live.disconnects==1,"unpaced sync triggered early disconnect");
        m.onConnectionStateChange(live,0,BluetoothProfile.STATE_DISCONNECTED);
        check(live.connects==1,"remote-initiated disconnect on API 26 called connect()");

        // API 26 (useConnect=false): normal paced disconnect closes GATT, arms alarm, and skips connect().
        m=fresh();m.useConnect=false;m.satelliteMode=true;m.connected=true;
        live=new BluetoothGatt();m.mBluetoothGatt=live;
        m.markLinkAnswered();m.pacer.nextWouldWait=true;m.pacer.nextWait=300_000L;
        m.disconnectIfPaced(live);
        m.onConnectionStateChange(live,0,BluetoothProfile.STATE_DISCONNECTED);
        check(live.connects==0,"paced disconnect re-armed connect()");
        check(live.closes==1&&m.mBluetoothGatt==null,"paced disconnect did not close GATT");
        check(MeterRecheckAlarm.pending()!=null,"paced disconnect did not arm alarm");

        // API 27+ (useConnect=true): mid-disconnect switch to 0 calls connect() once, not twice.
        m=fresh();m.useConnect=true;m.satelliteMode=true;m.connected=true;
        live=new BluetoothGatt();m.mBluetoothGatt=live;
        m.markLinkAnswered();m.pacer.nextWouldWait=true;
        m.disconnectIfPaced(live);
        MeterSyncPacing.minutes=0;m.pacer.nextWait=0L;
        m.onConnectionStateChange(live,0,BluetoothProfile.STATE_DISCONNECTED);
        check(live.connects==1&&live.closes==0,"API 27+ did not reconnect exactly once");

        // Closing or replacing GATT before STATE_DISCONNECTED prevents re-arming the stale GATT.
        m=fresh();m.useConnect=false;m.satelliteMode=true;m.connected=true;
        live=new BluetoothGatt();m.mBluetoothGatt=live;
        m.markLinkAnswered();m.pacer.nextWouldWait=true;
        m.disconnectIfPaced(live);
        m.close();
        MeterSyncPacing.minutes=0;m.pacer.nextWait=0L;
        m.onConnectionStateChange(live,0,BluetoothProfile.STATE_DISCONNECTED);
        check(live.connects==0,"stale closed GATT was re-armed on API 26");

        // Whether the meter stays on comes from the link (the Satellite service), not from timing.
        m=fresh();m.markLinkAnswered();m.pacedReconnectDelay();check(!m.pacer.staysOn,"an unknown meter counted as staying on");
        m.satelliteMode=true;m.markLinkAnswered();m.pacedReconnectDelay();check(m.pacer.staysOn,"a Satellite link not counted as staying on");

        // The pacer's wait is armed as this meter's alarm, then dials once.
        m=fresh();m.pacer.nextWait=300_000L;check(m.pacedReconnectDelay()==300_000L,"pacer wait dropped");
        m.schedulePacedReconnect(300_000L);MeterRecheckAlarm.advance(299_999);check(m.opened==0,"paced reconnect early");
        MeterRecheckAlarm.advance(1);check(m.opened==1,"paced reconnect missing");

        check(MeterRecheckAlarm.pending()==null,"fired alarm still pending");

        // Review #800: an alarm already being delivered must not act once it is cancelled.
        m=fresh();m.schedulePacedReconnect(300_000L);long[] started=MeterRecheckAlarm.pending();
        m.cancelPacedReconnect();check(MeterRecheckAlarm.pending()==null,"cancel left the alarm set");
        m.runPacedReconnect(started[0]);check(m.opened==0,"cancelled alarm dialled while being delivered");
        // Connecting with nothing waiting does not touch AlarmManager.
        m=fresh();m.cancelPacedReconnect();check(MeterRecheckAlarm.cancels==0,"cancelled an alarm that was never set");
        // A newer link (retry, Bluetooth back on) is never replaced by the paced task.
        m=fresh();m.schedulePacedReconnect(300_000L);m.mBluetoothGatt=new BluetoothGatt();
        MeterRecheckAlarm.advance(300_000);check(m.opened==0,"paced task replaced a live link");
        // Rescheduling supersedes the earlier ticket.
        m=fresh();m.schedulePacedReconnect(300_000L);long[] first=MeterRecheckAlarm.pending();m.schedulePacedReconnect(600_000L);
        m.runPacedReconnect(first[0]);check(m.opened==0,"superseded paced task dialled");
        MeterRecheckAlarm.advance(600_000);check(m.opened==1,"rescheduled task missing");
        // A meter removed while it waits stays removed, even if its alarm is already on its way.
        m=fresh();m.schedulePacedReconnect(300_000L);long[] removed=MeterRecheckAlarm.pending();
        m.stop=true;m.cancelPacedReconnect();check(MeterRecheckAlarm.pending()==null,"removed meter kept its alarm");
        m.runPacedReconnect(removed[0]);check(m.opened==0,"removed meter dialled");

        // Changing the interval dials a waiting meter now, and only once.
        m=fresh();m.schedulePacedReconnect(1_800_000L);m.pacingChanged();check(m.opened==1,"interval change left meter waiting");
        check(MeterRecheckAlarm.pending()==null,"interval change left the old alarm set");
        MeterRecheckAlarm.advance(1_800_000);check(m.opened==1,"old paced task dialled after interval change");
        m=fresh();m.pacingChanged();check(m.opened==0,"interval change dialled a meter that was not waiting");
        System.out.println("PASS: link outcomes, stays-on from the link, early disconnect preview, API 26 re-arm, paced alarm, stale and superseded alarms, live links, removal, interval change");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='meter-pacing-') as directory:
    directory = Path(directory)
    (directory / 'GlucoseMeterGatt.java').write_text(java)
    subprocess.run(['javac', '-d', str(directory), str(directory / 'GlucoseMeterGatt.java')], check=True)
    subprocess.run(['java', '-cp', str(directory), 'tk.glucodata.GlucoseMeterGatt'], check=True)
