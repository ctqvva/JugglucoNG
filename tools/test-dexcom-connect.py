#!/usr/bin/env python3
"""Execute Dexcom's production recovery methods against deterministic Android fakes."""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'Common/src/dex/java/tk/glucodata/DexGattCallback.java').read_text()


def method(signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        if source[end] == '{': depth += 1
        elif source[end] == '}': depth -= 1
        end += 1
    return re.sub(r'@SuppressLint\([^)]*\)|@NonNull\s*', '', source[start:end])


methods = '\n'.join(method(s) for s in [
    'public DexGattCallback(', 'private void preferBackgroundConnect(',
    'protected boolean useAutoConnect(', 'protected long connectionAttemptTimeoutMillis(',
    'protected void onConnectionAttemptTimeout(', 'public synchronized boolean connectDevice(',
    'public synchronized void setPause(',
    'public synchronized void onConnectionStateChange(', 'public synchronized void close(',
    'static private PendingIntent mkintents(', 'static  PendingIntent  setalarm(',
    'private void cancelalarm(',
    'public synchronized void onDescriptorWrite(', 'public synchronized void onServicesDiscovered(',
    'public synchronized void onCharacteristicChanged(',
    'private static PowerManager.WakeLock getwakelock(', 'private void getlock(',
    'private synchronized void releaselock(' if 'private synchronized void releaselock(' in source
    else 'private void releaselock(',
])
fields = source[source.index('private static final long DEXCOM_WARMUP_MSEC'):source.index('    public DexGattCallback(')]
body = r'''
package tk.glucodata;
import java.util.*;
class SuperGattCallback {
    static boolean defaultAuto, alarmClock, doLog=false, isWearable=false;
    static final String LOG_ID="test", ALARM_SERVICE="alarm", POWER_SERVICE="power";
    static final int BOND_NONE=10, BOND_BONDED=12;
    boolean stop, removedBond, connected, bonded, justdata, lastDataInvalidDuringWarmup, backfilled, has_service;
    long dataptr, showtime, foundtime, datatime, lastWarmupRetryAt;
    int phase=-1, triedinvain, payloadCalls; static final int GetData=10, RequestAuth=5;
    static final int GATT_SUCCESS=0;
    BluetoothGattCharacteristic[] charact={new BluetoothGattCharacteristic(),new BluetoothGattCharacteristic(),new BluetoothGattCharacteristic(),new BluetoothGattCharacteristic()};
    String SerialNumber, mActiveDeviceAddress, handshake;
    long[] constatchange={0,0}, wrotepass={0,0};
    BluetoothGatt mBluetoothGatt;
    int directCalls, closes, rescans;
    long executorDelay=-1;
    SuperGattCallback(String serial,long ptr,int gen){SerialNumber=serial;dataptr=ptr;mActiveDeviceAddress=Natives.getDeviceAddress(ptr,true);}
    protected boolean useAutoConnect(){return defaultAuto;}
    protected long connectionAttemptTimeoutMillis(){return 0;}
    protected void onConnectionAttemptTimeout(){}
    public synchronized boolean connectDevice(long delay){directCalls++;executorDelay=delay;mBluetoothGatt=new BluetoothGatt();return true;}
    public void close(){closes++;mBluetoothGatt=null;}
    public void setPause(boolean pause){stop=pause;}
    public void onConnectionStateChange(BluetoothGatt g,int status,int state){}
    public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){}
    public void onServicesDiscovered(BluetoothGatt g,int status){}
    public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){}
    boolean discover(BluetoothGatt g){payloadCalls++;return true;}
    void tryer(Runnable r){r.run();}void enableIndication(BluetoothGatt g,BluetoothGattCharacteristic c){}
    void askbackfill(){}void write(int which,byte[] value){}void requestAuth(){}void docmd0(BluetoothGatt g){}
    void enableGattDescriptor(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){}
    void getcert(byte[] v){payloadCalls++;}void authenticate(byte[] v){payloadCalls++;}void getdata(byte[] v){payloadCalls++;}
    boolean acceptConnectionAttemptCallback(BluetoothGatt g,int state){return g==mBluetoothGatt&&!stop;}
    void noteFirstGattCallback(String s,BluetoothGatt g){}
    void disconnect(){} void resetconnect(){}
    void searchforDeviceAddress(){rescans++;mActiveDeviceAddress=null;}
    void unbond(){} void setConStatus(int status){}
    static boolean dexKnownSensor(long p){return true;} static boolean getalarmclock(){return alarmClock;}
    static PendingIntent getBroadcast(Context c,int id,Intent i,int flags){return new PendingIntent(i.action);}
}
class BluetoothProfile {static final int STATE_CONNECTED=2,STATE_DISCONNECTED=0;}
class BluetoothDevice {static final int BOND_BONDING=11;int getBondState(){return 12;}}
class BluetoothGatt {static final int GATT_CONNECTION_TIMEOUT=147,GATT_SUCCESS=0;BluetoothDevice getDevice(){return new BluetoothDevice();}boolean discoverServices(){return true;}}
class BluetoothGattCharacteristic {static final int WRITE_TYPE_DEFAULT=2;void setWriteType(int t){}UUID getUuid(){return UUID.randomUUID();}}
class BluetoothGattDescriptor {BluetoothGattCharacteristic characteristic;BluetoothGattDescriptor(BluetoothGattCharacteristic c){characteristic=c;}BluetoothGattCharacteristic getCharacteristic(){return characteristic;}byte[] getValue(){return new byte[]{0};}UUID getUuid(){return UUID.randomUUID();}}
class Build {static class VERSION {static int SDK_INT=36;}static class VERSION_CODES {static int M=23;}}
class android {static class os {static class Build extends tk.glucodata.Build {}}}
class CloneSensorRegistry {static boolean clone;static boolean isCloneSensor(String s){return clone;}}
class SensorOwnershipRuntime {static boolean blocked;static boolean blocksLocalConnection(String s){return blocked;}}
class Natives {static String stored="F0:00:00:00:00:01";static String getDeviceAddress(long p,boolean fresh){return fresh?null:stored;}static long lastglucosetime(){return System.currentTimeMillis();}static boolean isAuthenticated(long p){return true;}static void dexbackfill(long p,byte[] v){}}
class Log {static void i(String a,String b){}static void d(String a,String b){}static void e(String a,String b){}static void stack(String a,String b,Throwable t){}static void showbytes(String s,byte[] v){}}
class Context {static final int MODE_PRIVATE=0;Object getSystemService(String s){return s.equals("power")?Applic.power:Applic.alarms;}}
class PowerManager {
    static final int PARTIAL_WAKE_LOCK=1;
    static Object owner;static int releases;
    List<WakeLock> created=new ArrayList<>();
    WakeLock newWakeLock(int level,String tag){WakeLock w=new WakeLock();created.add(w);return w;}
    static class WakeLock {
        boolean held;
        void acquire(){held=true;}
        void release(){
            if(!Thread.holdsLock(owner))throw new AssertionError("wake lock released outside callback monitor");
            if(!held)throw new AssertionError("wake lock released twice");
            held=false;releases++;
        }
    }
}
class Intent {String action;Intent(Context c,Class<?> cls){}void setAction(String s){action=s;}}
class ConnectReceiver {}
class PendingIntent {static final int FLAG_IMMUTABLE=1;String serial;PendingIntent(String s){serial=s;}}
class AlarmManager {
    boolean refused;int scheduled,cancelled;long at;PendingIntent pending;
    static class AlarmClockInfo {long at;AlarmClockInfo(long a,PendingIntent p){at=a;}}
    void setAlarmClock(AlarmClockInfo info,PendingIntent p){if(refused)throw new SecurityException();scheduled++;at=info.at;pending=p;}
    void cancel(PendingIntent p){cancelled++;if(p==pending)pending=null;}
}
class Prefs {
    Map<String,Boolean> values=new HashMap<>();
    boolean getBoolean(String key,boolean fallback){return values.getOrDefault(key,fallback);}
    Prefs edit(){return this;}Prefs putBoolean(String key,boolean v){values.put(key,v);return this;}void apply(){}
}
class Applic extends Context {
    static Applic app=new Applic();static Prefs prefs=new Prefs();static AlarmManager alarms=new AlarmManager();
    static PowerManager power=new PowerManager();
    Prefs getSharedPreferences(String s,int mode){return prefs;}static void wakemirrors(){}
}
class SensorBluetooth {
    static SensorBluetooth blueone=new SensorBluetooth();
    void connectToActiveDevice(SuperGattCallback cb,long delay){cb.connectDevice(delay);}
}
public class DexGattCallback extends SuperGattCallback {
    PendingIntent onalarm;static int alarmrequest=14;
    private PowerManager.WakeLock wakelock;
'''+fields+methods+r'''
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static DexGattCallback fresh(){
        Applic.prefs=new Prefs();Applic.alarms=new AlarmManager();
        Applic.power=new PowerManager();PowerManager.releases=0;
        defaultAuto=false;alarmClock=false;CloneSensorRegistry.clone=false;SensorOwnershipRuntime.blocked=false;
        DexGattCallback cb=new DexGattCallback("sensor-A",1);PowerManager.owner=cb;cb.connectDevice(0);return cb;
    }
    public static void main(String[] args){
        DexGattCallback cb=fresh();BluetoothGatt first=cb.mBluetoothGatt;
        check(cb.mActiveDeviceAddress.equals(Natives.stored),"process startup lost saved address");
        check(!cb.useAutoConnect()&&cb.connectionAttemptTimeoutMillis()==45000,"initial direct attempt policy");
        cb.onConnectionStateChange(first,147,0);
        check(cb.useAutoConnect()&&cb.connectionAttemptTimeoutMillis()==0,"timeout did not switch to background");
        check(cb.rescans==0&&cb.mActiveDeviceAddress.equals(Natives.stored),"timeout discarded identity");
        check(new DexGattCallback("sensor-A",1).useAutoConnect(),"process restart forgot mode");
        check(!new DexGattCallback("sensor-B",2).useAutoConnect(),"mode leaked across sensors");
        BluetoothGatt current=cb.mBluetoothGatt;int calls=cb.directCalls, releases=PowerManager.releases;
        cb.onConnectionStateChange(first,147,0);cb.onConnectionStateChange(first,0,2);
        check(cb.mBluetoothGatt==current&&cb.directCalls==calls&&PowerManager.releases==releases,"retired callback changed live attempt");
        cb.onServicesDiscovered(first,0);cb.onDescriptorWrite(first,new BluetoothGattDescriptor(cb.charact[3]),0);cb.onCharacteristicChanged(first,cb.charact[0],new byte[0]);
        check(cb.payloadCalls==0&&!cb.has_service,"retired payload callback mutated new session");
        cb.onServicesDiscovered(current,0);cb.onCharacteristicChanged(current,cb.charact[0],new byte[0]);check(cb.payloadCalls==2,"current payload callback rejected");
        cb=fresh();cb.onConnectionAttemptTimeout();check(cb.useAutoConnect(),"silent direct attempt did not fall back");
        cb=fresh();cb.onConnectionStateChange(cb.mBluetoothGatt,133,0);check(cb.useAutoConnect(),"legacy failure did not fall back");
        cb=fresh();first=cb.mBluetoothGatt;cb.onConnectionStateChange(first,0,2);cb.onConnectionStateChange(first,147,0);
        check(!cb.useAutoConnect(),"connected link loss treated as failed direct dial");
        cb=fresh();first=cb.mBluetoothGatt;cb.datatime=System.currentTimeMillis();cb.onConnectionStateChange(first,0,2);cb.onConnectionStateChange(first,147,0);
        check(!cb.useAutoConnect(),"recent-reading early return forgot connected attempt");
        cb=fresh();first=cb.mBluetoothGatt;cb.onConnectionStateChange(first,0,2);cb.close();cb.connectDevice(0);cb.onConnectionStateChange(cb.mBluetoothGatt,147,0);
        check(cb.useAutoConnect(),"missing old disconnect prevented new-attempt fallback");
        cb=fresh();first=cb.mBluetoothGatt;cb.datatime=System.currentTimeMillis();cb.justdata=true;cb.onConnectionStateChange(first,19,0);
        check(cb.useAutoConnect()&&Applic.alarms.scheduled==1&&cb.directCalls==1,"data reconnect did not use wakeup alarm");
        cb=fresh();first=cb.mBluetoothGatt;cb.justdata=true;cb.lastDataInvalidDuringWarmup=true;cb.lastWarmupRetryAt=System.currentTimeMillis()+600000;cb.onConnectionStateChange(first,19,0);
        check(Applic.alarms.scheduled==1&&cb.directCalls==1,"warmup used executor timer");
        cb=fresh();cb.close();calls=cb.directCalls;cb.connectDevice(300000);
        check(Applic.alarms.scheduled==1&&cb.directCalls==calls,"delayed connect set executor latch");
        cb.connectDevice(0);check(cb.directCalls==calls+1&&Applic.alarms.pending==null,"alarm blocked immediate recovery");
        cb=fresh();Applic.alarms.refused=true;cb.connectDevice(300000);check(cb.executorDelay==0&&cb.directCalls==2,"alarm denial left no retry");
        cb=fresh();cb.stop=true;check(!cb.connectDevice(300000)&&Applic.alarms.scheduled==0,"paused sensor scheduled alarm");
        cb=fresh();cb.connectDevice(300000);cb.setPause(true);check(Applic.alarms.pending==null&&!cb.connectDevice(0),"pause retained wakeup alarm");
        cb=fresh();cb.dataptr=0;check(!cb.connectDevice(300000),"removed sensor scheduled alarm");
        cb=fresh();CloneSensorRegistry.clone=true;check(!cb.connectDevice(300000),"clone sensor scheduled alarm");
        cb=fresh();SensorOwnershipRuntime.blocked=true;check(!cb.connectDevice(300000),"released sensor scheduled alarm");
        cb=fresh();defaultAuto=true;check(cb.useAutoConnect()&&cb.connectionAttemptTimeoutMillis()==0,"application background setting ignored");
        cb=fresh();first=cb.mBluetoothGatt;cb.onConnectionStateChange(first,0,2);
        PowerManager.WakeLock firstLock=cb.wakelock;check(firstLock.held,"connection did not acquire wake lock");
        cb.close();check(!firstLock.held&&cb.wakelock==null&&PowerManager.releases==1,"Bluetooth-off close leaked wake lock");
        cb.close();check(PowerManager.releases==1,"repeated close released wake lock twice");
        cb.connectDevice(0);current=cb.mBluetoothGatt;cb.onConnectionStateChange(current,0,2);
        PowerManager.WakeLock nextLock=cb.wakelock;
        cb.onConnectionStateChange(first,0,0);check(nextLock.held&&cb.wakelock==nextLock,"retired disconnect released replacement wake lock");
        cb.onConnectionStateChange(current,19,0);check(!nextLock.held&&PowerManager.releases==2,"normal disconnect leaked/double-released wake lock");
        cb=fresh();cb.onConnectionStateChange(cb.mBluetoothGatt,0,2);firstLock=cb.wakelock;cb.setPause(true);
        check(!firstLock.held&&cb.wakelock==null,"pause leaked wake lock");
        cb.close();check(PowerManager.releases==1,"close after pause released twice");
        System.out.println("PASS: Dexcom timeout fallback, persisted identity/mode, stale callbacks, wakeup scheduling and ownership guards");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='dexcom-connect-') as directory:
    directory = Path(directory)
    java = directory / 'DexGattCallback.java'
    java.write_text(body)
    subprocess.run(['javac', '-d', str(directory), str(java)], check=True)
    subprocess.run(['java', '-cp', str(directory), 'tk.glucodata.DexGattCallback'], check=True)
