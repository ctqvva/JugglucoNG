#!/usr/bin/env python3
"""Exercise the production Libre 3 backfill methods against a fake BLE/native layer:
a finished backfill must copy native history into Room from where the gap began (#620).
No Android services, glucose algorithms or sensor credentials are used.
"""
from pathlib import Path
import re, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
libre = (root / 'Common/src/libre3/java/tk/glucodata/Libre3GattCallback.java').read_text()

def method(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        if text[end] == '{': depth += 1
        elif text[end] == '}': depth -= 1
        end += 1
    return text[start:end]

methods = [method(libre, x) for x in [
    'private void noteBackfill(', 'private void syncBackfillToRoom(',
    'private void fillHistory(', 'private void    fillClinical(', 'private void receivedpatchstatus(',
    'private void access1100(', 'private boolean qsendcommand(', 'private boolean sendcommandonly(',
    'private void onqueue(', 'private boolean fromqueue(',
]]
fields = [re.search(r'private long backfillRoomSyncFromMsec=0L;', libre).group(0)]

preamble = r'''
package tk.glucodata;
import java.util.*;
import java.util.concurrent.*;
class Libre3GattCallback {
    static boolean doLog=false, isWearable=false;
    static final boolean doTEST=false;
    static String LOG_ID="test";
    String SerialNumber="synthetic";
    long sensorptr=1L, cryptptr=1L;
    int lastEventReceived=0;
    boolean wrotecharacter=false, backFillInProgress=false;
    final Queue<byte[]> sendqueue=new ConcurrentLinkedQueue<byte[]>();
    static class Log { static void i(String a,String b){} static void e(String a,String b){} }
    static void showbytes(String s,byte[] b){}
    static byte[] intDecrypt(long p,int kind,byte[] v){return v;}
    static byte[] intEncrypt(long p,int kind,byte[] v){return v;}
    static class Characteristic { void setValue(byte[] v){} }
    static class Gatt { int writes; boolean writeCharacteristic(Characteristic c){writes++;return true;} }
    Characteristic gattCharPatchDataControl=new Characteristic();
    Gatt mBluetoothGatt=new Gatt();
    void setretrytimer(){} void disconnect(){}
    static class Natives {
        static int lastLife, lastHist, status;
        static int getlastLifeCountReceived(long p){return lastLife;}
        static int getlastHistoricLifeCountReceived(long p){return lastHist;}
        static int libre3processpatchstatus(long p,byte[] v){return status;}
        static byte[] libre3ControlHistory(int a,int b){return new byte[]{1};}
        static byte[] libre3ClinicalControl(int a,int b){return new byte[]{2};}
        static byte[] libre3EventLogControl(int a){return new byte[]{3};}
        static boolean getDisconnectSensor(){return false;}
    }
    static class HistorySyncAccess {
        static List<Long> windows=new ArrayList<>();
        static void syncSensorWindowFromNative(String serial,long startMs){windows.add(startMs);}
    }
'''
body = preamble + '\n'.join(fields) + '\n' + '\n'.join(methods) + r'''
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static void status(Libre3GattCallback cb,int lifeCount){Natives.status=lifeCount;cb.receivedpatchstatus(new byte[0]);}
    static void drain(Libre3GattCallback cb){int guard=0;while(cb.wrotecharacter&&guard++<10)cb.access1100(new byte[0]);}
    static void near(long actual,long expected,String message){check(Math.abs(actual-expected)<5_000L,message+": "+actual+" vs "+expected);}
    public static void main(String[] args){
        // Connected and current: nothing to backfill, so no Room window sync.
        Libre3GattCallback cb=new Libre3GattCallback();
        Natives.lastLife=1000;Natives.lastHist=980;status(cb,1000);drain(cb);
        check(HistorySyncAccess.windows.isEmpty(),"steady state must not sync a window");

        // Three hours away: history and clinical backfill both queue; Room sync once, after the last response.
        cb=new Libre3GattCallback();cb.lastEventReceived=1;
        Natives.lastLife=1000;Natives.lastHist=980;status(cb,1180);
        check(cb.sendqueue.size()==1&&cb.wrotecharacter,"history sent, clinical queued");
        cb.access1100(new byte[0]);
        check(HistorySyncAccess.windows.isEmpty(),"synced before the queued backfill finished");
        long now=System.currentTimeMillis();
        cb.access1100(new byte[0]);
        check(HistorySyncAccess.windows.size()==1,"finished backfill must sync Room once");
        near(HistorySyncAccess.windows.get(0),now-(1180-980+5)*60_000L,"window must start before the oldest requested history");
        cb.access1100(new byte[0]);
        check(HistorySyncAccess.windows.size()==1,"later control responses must not resync");

        // Clinical-only gap (history already current): window starts at the clinical gap.
        HistorySyncAccess.windows.clear();cb=new Libre3GattCallback();cb.lastEventReceived=1;
        Natives.lastLife=1100;Natives.lastHist=1160;status(cb,1180);now=System.currentTimeMillis();drain(cb);
        check(HistorySyncAccess.windows.size()==1,"clinical backfill must sync Room");
        near(HistorySyncAccess.windows.get(0),now-(1180-1100+5)*60_000L,"window must start at the clinical gap");
        System.out.println("PASS: Libre 3 backfill syncs Room from the gap start once the sensor finishes");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='libre3-backfill-') as d:
    d = Path(d); (d / 'Libre3GattCallback.java').write_text(body)
    subprocess.run(['javac', '-d', str(d), str(d / 'Libre3GattCallback.java')], check=True)
    subprocess.run(['java', '-cp', str(d), 'tk.glucodata.Libre3GattCallback'], check=True)
