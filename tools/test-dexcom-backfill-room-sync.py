#!/usr/bin/env python3
"""Exercise the production Dexcom backfill methods against a fake BLE/native layer:
backfilled readings must be copied into Room from where the requested window starts,
when the sensor finishes or when the link drops mid-transfer (#767).
No Android services, glucose algorithms or sensor credentials are used.
"""
from pathlib import Path
import re, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
dex = (root / 'Common/src/dex/java/tk/glucodata/DexGattCallback.java').read_text()

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

methods = [method(dex, x) for x in [
    'private  void askbackfill(', 'private synchronized void noteBackfill(',
    'private synchronized void syncBackfillToRoom(', 'private    void getdata(',
    'public synchronized void close(',
]]
fields = [re.search(r'private long backfillRoomSyncFromMsec=0L;', dex).group(0)]

preamble = r'''
package tk.glucodata;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
class Base { void close(){} }
class DexGattCallback extends Base {
    static boolean doLog=false;
    static String LOG_ID="test";
    static final long DEXCOM_WARMUP_MSEC=0L, DEXCOM_WARMUP_RETRY_SLACK_MSEC=0L;
    String SerialNumber="synthetic";
    long dataptr=1L, sensorstartmsec=0L;
    boolean backfilled=true, connected=false, justdata=false, lastDataInvalidDuringWarmup=false;
    long datatime=0L, sampletime=0L, lastWarmupRetryAt=0L;
    Object mBluetoothGatt=null, connectedAttempt=null;
    Object[] charact=new Object[4];
    List<byte[]> writes=new ArrayList<>();
    static class Log { static void i(String a,String b){} }
    static class Applic {
        static class App { void redraw(){} }
        static App app=new App();
        static ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
    }
    static class Natives {
        static byte[] cmd;
        static long sensorStartMsec;
        static byte[] getDexbackfillcmd(long p){return cmd;}
        static long getSensorStartmsec(long p){return sensorStartMsec;}
        static void dexEndBackfill(long p){}
        static boolean dexcomProcessData(long p,byte[] v,long[] timeres){return false;}
    }
    static class HistorySyncAccess {
        static List<Long> windows=new ArrayList<>();
        static void syncSensorWindowFromNative(String serial,long startMs){windows.add(startMs);}
    }
    void tryer(Supplier<Boolean> worked){worked.get();}
    boolean write(int nr,byte[] data){writes.add(data);return true;}
    boolean enableNotification(Object gatt,Object c){return true;}
    boolean isWarmupReading(long t,long res){return false;}
    void handleGlucoseResult(long res,long t){}
    void saveDeviceName(){}
    void releaselock(){}
    void resetconnect(){backfilled=false;}
'''
body = preamble + '\n'.join(fields) + '\n' + '\n'.join(methods) + r'''
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static byte[] cmd(int start,int end){
        byte[] b=new byte[9];b[0]=0x59;
        for(int i=0;i<4;i++){b[1+i]=(byte)(start>>(8*i));b[5+i]=(byte)(end>>(8*i));}
        return b;
    }
    static void reset(){HistorySyncAccess.windows.clear();}
    public static void main(String[] args){
        final long sensorStart=1_793_000_000_000L;
        Natives.sensorStartMsec=sensorStart;
        // The reported gap: readings 100 and 101 missed, 102 arrives live.
        // Native asks from just after reading 99 to just before reading 102.
        final int last=99*300, live=102*300;
        final long firstMissing=sensorStart+100*300*1000L;

        // Sensor finishes the transfer: Room copies the gap once, from before the first missing reading.
        DexGattCallback cb=new DexGattCallback();
        Natives.cmd=cmd(last+60,live-60);
        cb.askbackfill();
        check(cb.writes.size()==1&&cb.writes.get(0)==Natives.cmd,"backfill command must be written");
        check(HistorySyncAccess.windows.isEmpty(),"synced before the sensor sent the backfill");
        cb.getdata(new byte[]{0x59,0});
        check(HistorySyncAccess.windows.size()==1,"finished backfill must sync Room once");
        long from=HistorySyncAccess.windows.get(0);
        check(from<=firstMissing,"window must reach the first missing reading: "+from+" > "+firstMissing);
        check(from>=sensorStart+last*1000L-5*60_000L,"window must not reach far behind the gap: "+from);
        cb.close();
        check(HistorySyncAccess.windows.size()==1,"disconnect after a finished backfill must not resync");

        // Link drops mid-transfer: what arrived is still copied, on close.
        reset();cb=new DexGattCallback();
        cb.askbackfill();
        cb.close();
        check(HistorySyncAccess.windows.size()==1&&HistorySyncAccess.windows.get(0)==from,"interrupted backfill must sync Room on disconnect");

        // Nothing missing: no command, no window sync.
        reset();cb=new DexGattCallback();
        Natives.cmd=null;
        cb.askbackfill();cb.getdata(new byte[]{0x59,0});cb.close();
        check(cb.writes.isEmpty()&&HistorySyncAccess.windows.isEmpty(),"steady state must not sync a window");

        // Unknown sensor start: do not sync from the epoch.
        reset();cb=new DexGattCallback();
        Natives.cmd=cmd(last+60,live-60);Natives.sensorStartMsec=0L;
        cb.askbackfill();cb.getdata(new byte[]{0x59,0});
        check(HistorySyncAccess.windows.isEmpty(),"no window without a sensor start");
        System.out.println("PASS: Dexcom backfill syncs Room from the requested window once the sensor finishes or disconnects");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='dexcom-backfill-') as d:
    d = Path(d); (d / 'DexGattCallback.java').write_text(body)
    subprocess.run(['javac', '-d', str(d), str(d / 'DexGattCallback.java')], check=True)
    subprocess.run(['java', '-cp', str(d), 'tk.glucodata.DexGattCallback'], check=True)
