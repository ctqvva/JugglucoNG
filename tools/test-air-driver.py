#!/usr/bin/env python3
"""Run Air's production loader/record and GATT methods with injected failures.

This verifies retry and transport ownership, not the vendor glucose algorithm
or Android pairing. Requires a host C++ compiler and a JDK.
"""
from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def method(source, signature, after=0):
    start = source.index(signature, after)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
        end += 1
    return source[start:end]


def run(command):
    subprocess.run(command, check=True, timeout=45)


def library(directory):
    source = (ROOT / 'Common/src/main/cpp/air/java.cpp').read_text()
    loader = source[source.index('typedef unsigned char (*air1_opcal4_algorithm_t)'):
                    source.index('bool airstream::setNumberNew(')]
    process = method(source, 'static jlong airProcessData(', source.index('// Returns 0:'))
    fixture = r'''
#include <cassert>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <string_view>
#include <thread>
#include <vector>
#include "air.hpp"
#define NOLOG
#define LOGGER(...) ((void)0)
#define LOGAR(...) ((void)0)
using jbyte = signed char;
using jlong = int64_t;
struct ScanData { int getid() const { return 0; } uint32_t gettime() const { return 0; } };
struct SensorGlucoseData {
  struct Info { int askEarlier = 0; } info;
  int cursor = 0;
  bool sensorerror = false;
  uint32_t sensorErrorTime = 0;
  Info *getinfo() { return &info; }
  int getLastAir() const { return cursor; }
  void setLastAir(int n) { cursor = n; }
  const ScanData *lastpoll() const { return nullptr; }
};
template<class T> struct Memory { T value{}; T *data() { return &value; } };
struct airstream {
  SensorGlucoseData *hist;
  int tmpiter = 0, tmptot = 1;
  Memory<DeviceInfo3Obj> sensorInfo;
  Memory<air1_opcal4_arguments_t> generated;
  air_input input;
  air1_opcal4_output_t output;
  air1_opcal4_debug_t debug;
  explicit airstream(SensorGlucoseData *s) : hist(s) {}
  void setNumberNew(int n) { tmpiter = 0; tmptot = n; }
};
static int opens = 0, closes = 0;
unsigned char algorithm(air1_opcal4_device_info_t *, air1_opcal4_cgm_input_t *in,
    air1_opcal4_cal_list_t *, air1_opcal4_arguments_t *, air1_opcal4_output_t *out,
    air1_opcal4_debug_t *) {
  out->result_glucose = 125;
  out->seq_number_final = in->seq_number;
  out->measurement_time_standard = in->measurement_time_standard;
  return 1;
}
void *openlib(std::string_view name) {
  assert(name == "/libCALCULATION.so");
  return ++opens == 1 ? nullptr : reinterpret_cast<void *>(1);
}
const char *dlerror() { return "injected failure"; }
int dlclose(void *) { ++closes; return 0; }
void *dlsym(void *, const char *) {
  return opens == 2 ? nullptr : reinterpret_cast<void *>(&algorithm);
}
jlong mkres(SensorGlucoseData *, uint32_t, uint32_t, int, int, int, float) { return 1250; }
int rate2changeindex(float) { return 3; }
void airsavehistory(SensorGlucoseData *, const air1_opcal4_output_t &) {}
void showm(const m *) {}
// PRODUCTION_LOADER
// PRODUCTION_PROCESS
jlong sample(airstream &stream, int sequence) {
  AirData data{};
  data.reg0 = 0xC5; data.reg1 = 1; data.sequenceNumber = sequence;
  data.time = 1800000000; data.temperature = 3300;
  jlong time = 1800000000000LL;
  return airProcessData(&stream, reinterpret_cast<const jbyte *>(&data), sizeof(data), &time);
}
int main() {
  SensorGlucoseData sensor;
  airstream stream(&sensor);
  assert(sample(stream, 7) == 0 && sensor.cursor == 0 && sensor.sensorerror);
  assert(sample(stream, 7) == 0 && sensor.cursor == 0 && closes == 1);
  assert(sample(stream, 7) == 1250 && sensor.cursor == 7 && opens == 3);
  assert(sample(stream, 8) == 1250 && sensor.cursor == 8 && opens == 3);
  std::vector<std::thread> workers;
  for (int i = 0; i < 8; ++i) workers.emplace_back([] {
    SensorGlucoseData other;
    airstream otherStream(&other);
    assert(sample(otherStream, 9) == 1250 && other.cursor == 9);
  });
  for (auto &worker : workers) worker.join();
  assert(opens == 3 && closes == 1);
}
'''
    cpp = directory / 'library.cpp'
    cpp.write_text(fixture.replace('// PRODUCTION_LOADER', loader).replace('// PRODUCTION_PROCESS', process))
    binary = directory / 'library'
    run([os.environ.get('CXX', 'c++'), '-std=c++17', '-pthread',
         '-I', str(ROOT / 'Common/src/main/cpp/air'), str(cpp), '-o', str(binary)])
    run([str(binary)])
    print('Air library: failed dlopen/dlsym retry, cursor preservation, cached success, concurrent sensors passed')


def gatt(directory):
    air = (ROOT / 'Common/src/dex/java/tk/glucodata/AirGattCallback.java').read_text()
    base = (ROOT / 'Common/src/main/java/tk/glucodata/SuperGattCallback.java').read_text()
    base_methods = '\n'.join(method(base, signature) for signature in [
        'protected final synchronized boolean acceptConnectionAttemptCallback(',
        'public void onConnectionStateChange(', 'public final boolean hasLocallyConnectedGatt('])
    air_methods = '\n'.join(method(air, signature) for signature in [
        'private boolean isCurrentGatt(', 'private synchronized void enableNotificationIfCurrent(',
        'public void onConnectionStateChange(',
        'public void onServicesDiscovered(',
        'public void onCharacteristicWrite(',
        'public void onCharacteristicChanged(',
        'public void onMtuChanged(',
        'private void afterReads(', 'private void enableDataOrBond(',
        'public void onDescriptorWrite(', 'private synchronized void writeAppIdIfCurrent('])
    fixture = r'''
package tk.glucodata;
import java.util.*;
import java.util.concurrent.TimeUnit;
@interface NonNull {}
class BluetoothProfile { static final int STATE_CONNECTED = 2, STATE_DISCONNECTED = 0; }
class BluetoothDevice {
  int bonds, bondState;
  int getBondState() { return bondState; }
  boolean createBond() { ++bonds; return true; }
  String getAddress() { return "synthetic"; }
}
class BluetoothGatt {
  static final int GATT_SUCCESS = 0;
  int closes, reconnects, mtus, discovers, notifications, writes;
  final BluetoothDevice device = new BluetoothDevice();
  void writeCharacteristic(BluetoothGattCharacteristic c) { ++writes; }
  BluetoothDevice getDevice() { return device; }
  void close() { ++closes; }
  void connect() { ++reconnects; }
  void requestMtu(int size) { ++mtus; }
  void discoverServices() { ++discovers; }
}
class BluetoothGattCharacteristic {
  final String uuid;
  BluetoothGattCharacteristic(String uuid) { this.uuid = uuid; }
  UUID getUuid() { return UUID.fromString(uuid); }
  void setWriteType(int type) {}
  void setValue(byte[] value) {}
}
class BluetoothGattDescriptor {
  final BluetoothGattCharacteristic characteristic;
  BluetoothGattDescriptor(BluetoothGattCharacteristic c) { characteristic = c; }
  BluetoothGattCharacteristic getCharacteristic() { return characteristic; }
  UUID getUuid() { return characteristic.getUuid(); }
  byte[] getValue() { return new byte[0]; }
}
class PendingIntent {}
class Log {
  static void i(String tag, String text) {} static void e(String tag, String text) {}
  static void showbytes(String tag, byte[] bytes) {}
}
class WearSensorClaim { static int disconnects; static void onLocalGattDisconnected(String s) { ++disconnects; } }
class Applic {
  static final Scheduler scheduler = new Scheduler();
  static void postDelayed(Runnable r, long delay) { scheduler.jobs.add(r); }
  static class Scheduler {
    final List<Runnable> jobs = new ArrayList<>();
    void schedule(Runnable r, long delay, TimeUnit unit) { jobs.add(r); }
    void drain() { var pending = new ArrayList<>(jobs); jobs.clear(); pending.forEach(Runnable::run); }
  }
}
class SensorBluetooth {
  static final SensorBluetooth blueone = new SensorBluetooth();
  int connects;
  void connectToActiveDevice(Object cb, int delay) { ++connects; }
}
class PlatformCallback { public void onConnectionStateChange(BluetoothGatt g, int status, int state) {} }
class SuperGattCallback extends PlatformCallback {
  static final String LOG_ID = "test";
  boolean stop, autoconnect;
  long dataptr = 1;
  String SerialNumber = "synthetic";
  BluetoothGatt mBluetoothGatt, locallyConnectedGatt;
  BluetoothDevice mActiveBluetoothDevice;
  long[] constatchange = new long[2], wrotepass = new long[2];
  int stateChanges;
  static class Deadline { void completed(BluetoothGatt g) {} }
  final Deadline connectDeadline = new Deadline();
  void noteFirstGattCallback(String name, BluetoothGatt g) {}
  void setConStatus(int status) { ++stateChanges; }
  void disconnect() {}
  void enableNotification(BluetoothGatt g, BluetoothGattCharacteristic c) { ++g.notifications; }
  // PRODUCTION_BASE
}
class AirGattCallback extends SuperGattCallback {
  static final boolean doLog = false;
  static final int GATT_SUCCESS = 0, BOND_BONDED = 12, GATT_INSUFFICIENT_AUTHENTICATION = 5, WRITE_TYPE_NO_RESPONSE = 1;
  boolean unusedSensor;
  volatile int connectionEpoch;
  static final String LOG_ID = "test", UUIDchar11 = "00000000-0000-0000-0000-000000000001",
      UUIDchar21 = "00000000-0000-0000-0000-000000000002", UUIDchar22 = "00000000-0000-0000-0000-000000000003";
  long datatime;
  PendingIntent onalarm;
  boolean receiveNotes = true;
  String swRevision = "1.5";
  final BluetoothGattCharacteristic charact11 = new BluetoothGattCharacteristic(UUIDchar11),
      charact21 = new BluetoothGattCharacteristic(UUIDchar21), charact22 = new BluetoothGattCharacteristic(UUIDchar22);
  int resets, frames, discoveries;
  void resetValues() { ++resets; }
  boolean discover(BluetoothGatt g) { ++discoveries; return true; }
  void writeAuthIfCurrent(BluetoothGatt g, int epoch) {}
  void onChar11Changed(BluetoothGatt g, byte[] bytes) { ++frames; }
  void onChar21Changed(BluetoothGattCharacteristic c, BluetoothGatt g, byte[] bytes) { ++frames; }
  void onChar22Changed(BluetoothGatt g, byte[] bytes) { ++frames; }
  void showCharacter(String tag, BluetoothGattCharacteristic c) {}
  static boolean getalarmclock() { return false; }
  static PendingIntent setalarm(long t, PendingIntent p, String s) { return p; }
  // PRODUCTION_AIR
  static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  public static void main(String[] args) {
    AirGattCallback cb = new AirGattCallback();
    BluetoothGatt old = new BluetoothGatt(), current = new BluetoothGatt();
    cb.mBluetoothGatt = current;
    cb.mActiveBluetoothDevice = current.device;
    cb.onConnectionStateChange(current, 0, BluetoothProfile.STATE_CONNECTED);
    check(cb.hasLocallyConnectedGatt() && current.mtus == 1, "current connection records local ownership");
    cb.onConnectionStateChange(old, 0, BluetoothProfile.STATE_DISCONNECTED);
    check(cb.mBluetoothGatt == current && cb.resets == 0 && SensorBluetooth.blueone.connects == 0,
          "retired disconnect must preserve replacement and avoid reconnect");
    cb.onMtuChanged(old, 512, 0);
    cb.onServicesDiscovered(old, 0);
    cb.onCharacteristicWrite(old, cb.charact11, 1);
    cb.onCharacteristicChanged(old, cb.charact11, new byte[]{1});
    check(old.discovers == 0 && cb.discoveries == 0 && cb.receiveNotes && cb.frames == 0,
          "retired callbacks cannot progress protocol or process glucose");
    cb.onCharacteristicChanged(current, cb.charact11, new byte[]{1});
    check(cb.frames == 1, "current notification still reaches record processing");
    cb.afterReads(current);
    cb.mBluetoothGatt = old;
    Applic.scheduler.drain();
    check(current.notifications == 0, "retired delayed CCCD write must be ignored");
    cb.mBluetoothGatt = current;
    cb.enableDataOrBond(current);
    cb.stop = true;
    Applic.scheduler.drain();
    check(current.device.bonds == 0, "stop cancels delayed bond");
    cb.stop = false;
    cb.enableDataOrBond(current);
    Applic.scheduler.drain();
    check(current.device.bonds == 1, "current delayed bond still runs");
    cb.afterReads(current);
    Applic.scheduler.drain();
    check(current.notifications == 1, "current delayed CCCD write still runs");
    cb.dataptr = 0;
    cb.onCharacteristicChanged(current, cb.charact11, new byte[]{1});
    check(cb.frames == 1, "freed native pointer must not receive notifications");
    cb.dataptr = 1;
    cb.onConnectionStateChange(current, 0, BluetoothProfile.STATE_DISCONNECTED);
    check(current.closes == 1 && cb.mBluetoothGatt == null && !cb.hasLocallyConnectedGatt()
          && WearSensorClaim.disconnects == 1 && SensorBluetooth.blueone.connects == 1,
          "current disconnect clears local ownership and retains normal reconnect");
    BluetoothGatt next = new BluetoothGatt();
    cb.mBluetoothGatt = next;
    cb.onDescriptorWrite(next, new BluetoothGattDescriptor(cb.charact22), 0);
    check(next.writes == 0 && !cb.receiveNotes, "app-ID write waits off the callback thread");
    cb.mBluetoothGatt = old;
    Applic.scheduler.drain();
    check(next.writes == 0, "retired delayed app-ID write must be ignored");
    cb.mBluetoothGatt = next;
    cb.onDescriptorWrite(next, new BluetoothGattDescriptor(cb.charact22), 0);
    Applic.scheduler.drain();
    check(next.writes == 1, "current delayed app-ID write still runs");
    cb.onDescriptorWrite(next, new BluetoothGattDescriptor(cb.charact22), 0);
    cb.autoconnect = true;
    cb.onConnectionStateChange(next, 0, BluetoothProfile.STATE_DISCONNECTED);
    cb.onConnectionStateChange(next, 0, BluetoothProfile.STATE_CONNECTED);
    Applic.scheduler.drain();
    check(next.reconnects == 1 && next.writes == 1,
          "a write scheduled before an auto-connect reconnect on the same GATT is dropped");
    System.out.println("Air GATT: retired callbacks, local ownership, delayed work and freed pointer passed");
  }
}
'''
    # The base helper spells the framework constants with their qualified name.
    base_methods = base_methods.replace('android.bluetooth.BluetoothProfile.', 'BluetoothProfile.')
    java = directory / 'AirGattCallback.java'
    java.write_text(fixture.replace('// PRODUCTION_BASE', base_methods).replace('// PRODUCTION_AIR', air_methods))
    run(['javac', '-d', str(directory), str(java)])
    run(['java', '-cp', str(directory), 'tk.glucodata.AirGattCallback'])


if __name__ == '__main__':
    with tempfile.TemporaryDirectory(prefix='air-regression-') as temporary:
        directory = Path(temporary)
        {'library': library, 'gatt': gatt}[sys.argv[1]](directory)
