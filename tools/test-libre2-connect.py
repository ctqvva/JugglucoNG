#!/usr/bin/env python3
"""Run production Libre 2 reconnect methods against a deterministic BLE/scheduler fake.
Android services and native sensor authentication are deliberately outside this test.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
base = (root / 'Common/src/main/java/tk/glucodata/SuperGattCallback.java').read_text()
libre = (root / 'Common/src/main/java/tk/glucodata/Libre2GattCallback.java').read_text()


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


def field(source, name, end):
    start = source.index(name)
    return source[start:source.index(end, start) + len(end)]


base_methods = '\n'.join(method(base, signature) for signature in [
    'private void connectionAttemptExpired(', 'protected final void watchConnectionAttempt(',
    'protected final synchronized boolean acceptConnectionAttemptCallback(',
    'private Runnable getConnectDevice(', 'public synchronized boolean connectDevice(',
    'private synchronized void markConnectRunnableStarted(', 'private synchronized void clearPendingConnect(',
    'public final synchronized void closeGattTransport(', 'public synchronized void disconnect(',
    'public synchronized void setPause(', 'public void onConnectionStateChange(',
    'public void close()', 'protected boolean useAutoConnect(',
    'protected void onConnectionAttemptTimeout(',
])
libre_methods = '\n'.join(method(libre, signature) for signature in [
    'protected boolean useAutoConnect(', 'protected long connectionAttemptTimeoutMillis(',
    'private boolean acceptGattCallback(', 'private void cancelDisconnectDeadline(',
    'private void resetConnectionState(', 'private synchronized void retryAfterDisconnect(',
    'private synchronized void requestDisconnect(', 'public synchronized void disconnect(',
    'public synchronized boolean connectDevice(', 'public synchronized boolean reconnect(',
    'public synchronized void setPause(', 'public synchronized void onConnectionStateChange(',
    'public synchronized void onServicesDiscovered(', 'public synchronized void onDescriptorWrite(',
    'public synchronized void onCharacteristicWrite(', 'public synchronized void onCharacteristicChanged(',
    'public synchronized void onReadRemoteRssi(', 'public synchronized void close()',
    'private Runnable forCurrentGatt(', 'private void endBLEHandler(',
])

# Fields and all lifecycle/entry-point methods above come from production sources.
# Only Android/JNI calls and the glucose/authentication payload handlers are fake.
java = r'''
package tk.glucodata;
import java.util.*;
import java.util.concurrent.*;
import android.bluetooth.BluetoothProfile;
import static java.util.Objects.nonNull;

class SuperGattCallback {
    static boolean doLog=false, isWearable=false, autoconnect=true;
    static final int GATT_SUCCESS=0;
    static final String LOG_ID="test";
    boolean stop, connectPending;
    long dataptr=1L, foundtime, connectTime, showtime=60_000L;
    long[] charcha={0L,0L}, constatchange={0L,0L}, wrotepass={0L,0L};
    String SerialNumber="synthetic", mActiveDeviceAddress="fake", mDeviceName, handshake, constatstatusstr;
    int sensorgen=1, readrssi=9999;
    BluetoothGatt mBluetoothGatt, locallyConnectedGatt;
    BluetoothDevice mActiveBluetoothDevice=new BluetoothDevice();
    ScheduledFuture<?> pendingConnectFuture;
    static SensorBluetooth blueone=new SensorBluetooth();
    static class SensorBluetooth {
        static SensorBluetooth blueone=SuperGattCallback.blueone;
        boolean enabled=true; boolean bluetoothIsEnabled(){return enabled;} void scanStarter(int delay){}
    }
    static class CloneSensorRegistry { static boolean clone; static boolean isCloneSensor(String s){return clone;} }
    static class SensorOwnershipRuntime { static boolean blocked; static boolean blocksLocalConnection(String s){return blocked;} }
    static class WearSensorClaim { static int releases; static void onLocalGattDisconnected(String s){releases++;} }
    static class Natives { static int resets; static void resetbluetooth(long ptr){resets++;} }
    static class Build { static class VERSION { static int SDK_INT=37; } static class VERSION_CODES { static int M=23; } }
    static class R { static class string { static int turn_on_nearby_devices_permission=0; } }
    static class Log {
        static void i(String a,String b){} static void d(String a,String b){} static void e(String a,String b){}
        static void stack(String a,String b,Throwable t){throw new AssertionError(b,t);}
    }
    static class BluetoothGatt {
        int closed, disconnected, discoveries, writes; boolean discoverySucceeds=true;
        void disconnect(){disconnected++;} void close(){closed++;}
        boolean discoverServices(){discoveries++;return discoverySucceeds;}
        BluetoothDevice getDevice(){return new BluetoothDevice();}
        BluetoothGattService getService(UUID u){return new BluetoothGattService();}
        boolean setCharacteristicNotification(BluetoothGattCharacteristic c,boolean v){return true;}
        boolean writeDescriptor(BluetoothGattDescriptor d){writes++;return true;}
    }
    static class BluetoothDevice {
        static int TRANSPORT_LE=2; int calls; List<Boolean> modes=new ArrayList<>();
        List<BluetoothGatt> gatts=new ArrayList<>();
        String getName(){return "fake";} String getAddress(){return "fake";}
        BluetoothGatt connectGatt(Object app,boolean auto,SuperGattCallback cb,int... transport){
            check(gatts.stream().allMatch(g->g.closed==1),"connectGatt before old transport closed");
            calls++;modes.add(auto);BluetoothGatt g=new BluetoothGatt();gatts.add(g);return g;
        }
    }
    static class BluetoothGattCharacteristic {
        static final UUID RAW=UUID.randomUUID();
        UUID getUuid(){return RAW;} byte[] getValue(){return new byte[46];}
        BluetoothGattDescriptor getDescriptor(UUID u){return new BluetoothGattDescriptor();}
    }
    static class BluetoothGattDescriptor {
        static byte[] ENABLE_NOTIFICATION_VALUE={1,0};
        boolean setValue(byte[] v){return true;}
        BluetoothGattCharacteristic getCharacteristic(){return new BluetoothGattCharacteristic();}
    }
    static class BluetoothGattService {
        BluetoothGattCharacteristic getCharacteristic(UUID u){return new BluetoothGattCharacteristic();}
    }
    static class Applic {
        static final Applic app=new Applic(); static Scheduler scheduler;
        static Applic getContext(){return app;} String getString(int id){return "";}
        static void Toaster(String s){} static void Toaster(int id){} static boolean mayscan(){return true;}
        Handler getHandler(){return new Handler();}
    }
    static class Handler { void removeCallbacks(Runnable task){} }
    static class Scheduler extends ScheduledThreadPoolExecutor {
        long now; List<Job> jobs=new ArrayList<>();
        Scheduler(){super(1);}
        public ScheduledFuture<?> schedule(Runnable r,long delay,TimeUnit unit){Job j=new Job(r,now+unit.toMillis(delay));jobs.add(j);return j;}
        void advance(long delta){long target=now+delta;while(true){Job j=jobs.stream().filter(x->!x.done&&x.at<=target).min(Comparator.comparingLong(x->x.at)).orElse(null);if(j==null)break;now=j.at;j.fire();}now=target;}
        Job last(){return jobs.get(jobs.size()-1);}
    }
    static class Job implements ScheduledFuture<Object> {
        Runnable task; long at; boolean done,cancelled;
        Job(Runnable r,long a){task=r;at=a;}
        void fire(){done=true;if(!cancelled)task.run();}
        public boolean cancel(boolean interrupt){cancelled=true;done=true;return true;}
        public boolean isCancelled(){return cancelled;}public boolean isDone(){return done;}
        public Object get(){return null;}public Object get(long t,TimeUnit u){return null;}
        public long getDelay(TimeUnit u){return 0;}public int compareTo(Delayed d){return 0;}
    }
    protected long connectionAttemptTimeoutMillis(){return 0L;}
    boolean allowConnectWithoutDataptr(){return false;}
    void armConnectCallbackLatency(){} void noteFirstGattCallback(String s,BluetoothGatt g){}
    void setpriority(BluetoothGatt g){} void setGattOptions(BluetoothGatt g){}
    void setConStatus(int status){constatstatusstr="Status="+status;}
    void noteLossOfSignal(long now){constatstatusstr="Loss of signal";constatchange[1]=now;}
    void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int s){}
    void disablenotification(BluetoothGatt g,BluetoothGattCharacteristic c){}
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
'''
java += field(base, 'private final GattConnectDeadline<BluetoothGatt> connectDeadline', '}, this::connectionAttemptExpired);')
java += '\n' + base_methods + '\n}\nclass Libre2GattCallback extends SuperGattCallback {\n'
java += field(libre, 'private final GattConnectDeadline<BluetoothGatt> disconnectDeadline', '}, this::retryAfterDisconnect);')
java += r'''
    BluetoothGatt disconnectingGatt;
    long reconnectDelayMillis;
    boolean connected, pack1, pack2, justenablednotification, failedbefore;
    int conphase, BLELoginposted, frames, logins;
    long bleLoginGeneration;
    Runnable mBLELoginHandler;
    BluetoothGattCharacteristic BLELogincharacteristic, CompositeRawDatacharacteristic, characteristic;
    static UUID mADCCustomServiceUUID=UUID.randomUUID(), mCharacteristicConfigDescriptor=UUID.randomUUID();
    static UUID mCharacteristicUUID_CompositeRawData=BluetoothGattCharacteristic.RAW, mCharacteristicUUID_BLELogin=UUID.randomUUID();
    boolean m2831x(){logins++;return true;}
    void writeBLELogin(){logins++;}
    void phase2(byte[] value){logins++;} void phase3(byte[] value){logins++;}
    void oldonCharacteristicChanged(byte[] value){frames++;}
    static void showCharacter(String s,BluetoothGattCharacteristic c){}
'''
java += libre_methods
java += r'''
    static Libre2GattCallback fresh(){
        Applic.scheduler=new Scheduler();CloneSensorRegistry.clone=false;SensorOwnershipRuntime.blocked=false;
        Natives.resets=0;WearSensorClaim.releases=0;
        Libre2GattCallback cb=new Libre2GattCallback();cb.connectDevice(0);Applic.scheduler.advance(0);
        check(cb.mActiveBluetoothDevice.calls==1,"initial connect");
        check(!cb.mActiveBluetoothDevice.modes.get(0),"Libre 2 inherited global autoConnect=true");
        return cb;
    }
    static void ignoredCallbacks(Libre2GattCallback cb,BluetoothGatt retired){
        BluetoothGatt current=cb.mBluetoothGatt;int calls=cb.mActiveBluetoothDevice.calls;
        String status=cb.constatstatusstr;long lost=cb.constatchange[1];
        cb.onConnectionStateChange(retired,0,2);cb.onConnectionStateChange(retired,19,0);
        cb.onServicesDiscovered(retired,0);cb.onDescriptorWrite(retired,null,133);
        cb.onCharacteristicWrite(retired,null,0);cb.onCharacteristicChanged(retired,null);
        cb.onReadRemoteRssi(retired,-12,0);
        check(cb.mBluetoothGatt==current&&cb.mActiveBluetoothDevice.calls==calls,"retired callback disturbed replacement");
        check(cb.frames==0&&cb.logins==0&&cb.readrssi==9999,"retired payload callback acted");
        check(Objects.equals(status,cb.constatstatusstr)&&lost==cb.constatchange[1],"retired callback changed status");
        check(Natives.resets==0,"retired status 19 reset authentication");
    }
    public static void main(String[] args){
        // The reported trace: repeated ~30s failures, then a healthy streaming connection.
        Libre2GattCallback cb=fresh();BluetoothGatt old=null;
        for(int status:new int[]{8,147,147,133,19}){
            old=cb.mBluetoothGatt;Applic.scheduler.advance(30_000);
            cb.onConnectionStateChange(old,status,0);
            check(old.closed==1&&cb.mBluetoothGatt==null,"disconnect did not retire old GATT");
            Applic.scheduler.advance(0);ignoredCallbacks(cb,old);
        }
        check(cb.mActiveBluetoothDevice.calls==6,"failed attempts did not each get a fresh transport");
        check(cb.mActiveBluetoothDevice.modes.stream().noneMatch(Boolean::booleanValue),"reconnect used autoConnect");
        BluetoothGatt current=cb.mBluetoothGatt;cb.onConnectionStateChange(current,0,2);
        cb.onServicesDiscovered(current,0);cb.onCharacteristicChanged(current,new BluetoothGattCharacteristic());
        check(current.discoveries==1&&cb.logins==1&&cb.frames==1,"healthy streaming did not resume");
        Applic.scheduler.advance(360_000);check(cb.mBluetoothGatt==current,"healthy connection expired");

        // Periodic recovery waits for DISCONNECTED and duplicate requests do not extend the fallback.
        cb=fresh();old=cb.mBluetoothGatt;cb.onConnectionStateChange(old,0,2);
        check(cb.reconnect(cb.connectTime+60_001),"periodic reconnect rejected");
        check(old.closed==0&&old.disconnected==1&&cb.mActiveBluetoothDevice.calls==1,"reconnect closed before DISCONNECTED");
        Job fallback=Applic.scheduler.last();Applic.scheduler.advance(1_000);cb.connectDevice(0);cb.disconnect();
        check(old.disconnected==1,"duplicate disconnect request");
        cb.onConnectionStateChange(old,0,2);check(old.discoveries==1,"late connected result restarted login while disconnecting");
        cb.onServicesDiscovered(old,0);cb.onCharacteristicChanged(old,null);
        Applic.scheduler.advance(999);check(old.closed==0,"fallback early");
        Applic.scheduler.advance(1);check(old.closed==1&&cb.mActiveBluetoothDevice.calls==2,"missing DISCONNECTED did not recover after 2s");
        ignoredCallbacks(cb,old);current=cb.mBluetoothGatt;fallback.task.run();
        check(cb.mBluetoothGatt==current&&cb.mActiveBluetoothDevice.calls==2,"dispatched fallback disturbed replacement");

        // Delivered DISCONNECTED cancels fallback, respects requested delay, and clears packet/login state.
        cb=fresh();old=cb.mBluetoothGatt;cb.conphase=4;cb.pack1=cb.pack2=true;
        cb.BLELogincharacteristic=new BluetoothGattCharacteristic();cb.connectDevice(500);
        fallback=Applic.scheduler.last();cb.onConnectionStateChange(old,0,0);
        check(cb.conphase==0&&!cb.pack1&&!cb.pack2&&cb.BLELogincharacteristic==null,"old handshake/packet state survived");
        Applic.scheduler.advance(499);check(cb.mActiveBluetoothDevice.calls==1,"connect delay ignored");
        Applic.scheduler.advance(1);check(cb.mActiveBluetoothDevice.calls==2,"delayed fresh connect missing");
        fallback.task.run();check(cb.mActiveBluetoothDevice.calls==2,"cancelled fallback retried twice");

        // Diagnostic disconnect/connect requests must retain the later requested delay.
        cb=fresh();old=cb.mBluetoothGatt;cb.disconnect();fallback=Applic.scheduler.last();
        cb.connectDevice(100);
        check(Applic.scheduler.last()==fallback&&old.disconnected==1,"delay update restarted disconnect");
        cb.onConnectionStateChange(old,0,0);
        Applic.scheduler.advance(99);check(cb.mActiveBluetoothDevice.calls==1,"diagnostic reconnect delay ignored");
        Applic.scheduler.advance(1);check(cb.mActiveBluetoothDevice.calls==2,"diagnostic reconnect missing");
        fallback.task.run();check(cb.mActiveBluetoothDevice.calls==2,"diagnostic fallback retried twice");

        // Later resume requests update the delay without moving the original 2s fallback.
        cb=fresh();old=cb.mBluetoothGatt;cb.disconnect();fallback=Applic.scheduler.last();
        Applic.scheduler.advance(1_000);cb.connectDevice(500);
        Applic.scheduler.advance(500);cb.connectDevice(750);
        check(Applic.scheduler.last()==fallback&&old.disconnected==1,"resume extended disconnect deadline");
        Applic.scheduler.advance(499);check(old.closed==0,"updated-delay fallback early");
        Applic.scheduler.advance(1);
        check(old.closed==1&&cb.mActiveBluetoothDevice.calls==1,"fallback ignored updated reconnect delay");
        Applic.scheduler.advance(749);check(cb.mActiveBluetoothDevice.calls==1,"latest reconnect delay ignored");
        Applic.scheduler.advance(1);check(cb.mActiveBluetoothDevice.calls==2,"updated reconnect missing");
        ignoredCallbacks(cb,old);fallback.task.run();
        check(cb.mActiveBluetoothDevice.calls==2,"updated-delay fallback retried twice");

        // A silent connection result retains the shared deadline; intermediate states do not cancel it.
        cb=fresh();old=cb.mBluetoothGatt;cb.onConnectionStateChange(old,0,1);
        Applic.scheduler.advance(119_999);check(old.closed==0,"connection deadline early");
        Applic.scheduler.advance(1);check(old.closed==1&&cb.mActiveBluetoothDevice.calls==2,"silent connect never recovered");
        ignoredCallbacks(cb,old);

        // Service/descriptor failures use the bounded path too.
        cb=fresh();old=cb.mBluetoothGatt;old.discoverySucceeds=false;cb.onConnectionStateChange(old,0,2);
        Applic.scheduler.advance(2_000);check(old.closed==1&&cb.mActiveBluetoothDevice.calls==2,"discovery failure stuck");
        cb=fresh();old=cb.mBluetoothGatt;cb.onDescriptorWrite(old,null,133);
        Applic.scheduler.advance(2_000);check(old.closed==1&&cb.mActiveBluetoothDevice.calls==2,"descriptor failure stuck");
        cb=fresh();old=cb.mBluetoothGatt;cb.justenablednotification=true;cb.onConnectionStateChange(old,19,0);
        check(Natives.resets==1&&!cb.justenablednotification,"existing status 19 auth policy lost");

        // close, pause, removal and ownership changes cannot resurrect a local sensor.
        cb=fresh();cb.disconnect();fallback=Applic.scheduler.last();cb.close();fallback.task.run();
        Applic.scheduler.advance(120_000);check(cb.mActiveBluetoothDevice.calls==1,"close resurrected sensor");
        cb=fresh();cb.disconnect();fallback=Applic.scheduler.last();cb.setPause(true);cb.setPause(false);fallback.task.run();
        Applic.scheduler.advance(120_000);check(cb.mActiveBluetoothDevice.calls==1,"pause/resume resurrected cancelled disconnect");
        cb=fresh();old=cb.mBluetoothGatt;cb.disconnect();cb.setPause(true);cb.onConnectionStateChange(old,0,0);
        Applic.scheduler.advance(120_000);check(old.closed==1&&cb.mActiveBluetoothDevice.calls==1,"paused callback reopened sensor");
        for(int guard=0;guard<3;guard++){
            cb=fresh();old=cb.mBluetoothGatt;cb.disconnect();
            if(guard==0)cb.dataptr=0;else if(guard==1)CloneSensorRegistry.clone=true;else SensorOwnershipRuntime.blocked=true;
            Applic.scheduler.advance(120_000);check(old.closed==1&&cb.mActiveBluetoothDevice.calls==1,"fallback ignored stop/ownership guard");
            cb=fresh();if(guard==0)cb.dataptr=0;else if(guard==1)CloneSensorRegistry.clone=true;else SensorOwnershipRuntime.blocked=true;
            Applic.scheduler.advance(120_000);check(cb.mActiveBluetoothDevice.calls==1,"connect deadline ignored removal/ownership");
        }
        cb=fresh();cb.close();cb.connectDevice(0);cb.setPause(true);Applic.scheduler.advance(0);
        check(cb.mActiveBluetoothDevice.calls==1,"queued connect ignored pause");
        cb=fresh();cb.close();cb.connectDevice(500);cb.disconnect();Applic.scheduler.advance(120_000);
        check(cb.mActiveBluetoothDevice.calls==1,"disconnect without GATT failed to cancel queued connect");
        cb=fresh();old=cb.mBluetoothGatt;cb.setPause(true);cb.disconnect();Applic.scheduler.advance(120_000);
        check(old.closed==1&&cb.mActiveBluetoothDevice.calls==1,"disconnect after pause failed to retire transport");

        // Handler retries already dispatched before cancellation are tied to their original GATT.
        cb=fresh();old=cb.mBluetoothGatt;int[] retries={0};Runnable login=cb.forCurrentGatt(()->retries[0]++);
        login.run();check(retries[0]==1,"current login retry rejected");
        cb.disconnect();login.run();check(retries[0]==1,"login ran while disconnecting");
        cb.onConnectionStateChange(old,0,0);Applic.scheduler.advance(0);login.run();
        check(retries[0]==1,"retired login retry acted on fresh transport");
        cb.onConnectionStateChange(cb.mBluetoothGatt,0,2);cb.disconnect();Applic.scheduler.advance(2_000);
        check(WearSensorClaim.releases==1,"local connection claim survived retirement");
        cb=fresh();login=cb.forCurrentGatt(()->retries[0]++);cb.setPause(true);cb.setPause(false);login.run();
        check(retries[0]==1,"cancelled login ran after pause/resume on the same GATT");
        login=cb.forCurrentGatt(()->retries[0]++);Runnable replacement=cb.forCurrentGatt(()->retries[0]++);
        login.run();check(retries[0]==1,"superseded login ran on the same GATT");
        replacement.run();check(retries[0]==2,"replacement login rejected");
        System.out.println("PASS: Libre 2 fresh-GATT recovery, callback isolation, deadlines, cancellation, ownership and login retries");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='libre2-connect-') as directory:
    directory = Path(directory)
    (directory / 'Libre2GattCallback.java').write_text(java)
    (directory / 'BluetoothProfile.java').write_text(
        'package android.bluetooth; public class BluetoothProfile {'
        'public static final int STATE_CONNECTED=2,STATE_DISCONNECTED=0;}')
    subprocess.run(['javac', '-d', str(directory), str(directory / 'Libre2GattCallback.java'),
                    str(directory / 'BluetoothProfile.java'),
                    str(root / 'Common/src/main/java/tk/glucodata/GattConnectDeadline.java')], check=True)
    subprocess.run(['java', '-cp', str(directory), 'tk.glucodata.Libre2GattCallback'], check=True)
