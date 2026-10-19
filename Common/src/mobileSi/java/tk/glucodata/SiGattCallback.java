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
/*      Thu Mar 28 20:14:36 CET 2024                                                 */

package tk.glucodata;

import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static tk.glucodata.Applic.hasNotChinese;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Libre2GattCallback.showCharacter;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.UUID;

import java.util.concurrent.TimeUnit;

import static tk.glucodata.util.sleep;

public class SiGattCallback extends SuperGattCallback {

   static private final String LOG_ID = "SiGattCallback";
   static int siNR = 0;

   public SiGattCallback(String SerialNumber, long dataptr) {
      super(SerialNumber, dataptr, 0x10);
      {
         if (doLog) {
            Log.d(LOG_ID, SerialNumber + " SiGattCallback(..)");
         }
         ;
      }
      ;
      ++siNR;
   }

   @SuppressLint("MissingPermission")
   @Override // android.bluetooth.BluetoothGattCallback
   public synchronized void onDescriptorWrite(BluetoothGatt bluetoothGatt,
         BluetoothGattDescriptor bluetoothGattDescriptor,
         int status) {
      if (stop || dataptr == 0)
         return;
      super.onDescriptorWrite(bluetoothGatt, bluetoothGattDescriptor, status);
      long tim = System.currentTimeMillis();
      if (doLog) {
         BluetoothGattCharacteristic characteristic = bluetoothGattDescriptor.getCharacteristic();
         byte[] value = bluetoothGattDescriptor.getValue();
         {
            if (doLog) {
               Log.showbytes("onDescriptorWrite char: " + characteristic.getUuid().toString() + " desc: "
                     + bluetoothGattDescriptor.getUuid().toString() + " status=" + status, value);
            }
            ;
         }
      }
      if (status == BluetoothGatt.GATT_SUCCESS) {
         if (Natives.siNotchinese(dataptr))
            authenticate();
         else
            askvalues(bluetoothGatt);
      } else {
         var mess = "onDescriptorWrite failed";
         handshake = mess;
         wrotepass[1] = tim;
         Log.e(LOG_ID, mess);
         disconnect();
      }
   }

   boolean connected = false;
   private boolean pendingHistoryUiRefresh = false;
   private long historyReplayBaselineSec = 0L;
   private boolean pendingHistoryRoomMerge = false;
   private final Runnable historyUiRefreshRunnable = new Runnable() {
      @Override
      public void run() {
         pendingHistoryUiRefresh = false;
         if (stop || dataptr == 0)
            return;
         final String liveMainSensor = SensorIdentity.resolveLiveMainSensor(SerialNumber);
         if (liveMainSensor != null && !liveMainSensor.isEmpty() && !SensorIdentity.matches(SerialNumber, liveMainSensor))
            return;
         long[] lastGlucose = Natives.getlastGlucose();
         final boolean glucoseAdvanced =
               lastGlucose != null &&
               lastGlucose.length >= 2 &&
               lastGlucose[0] > historyReplayBaselineSec;
         if (glucoseAdvanced) {
            if (constatstatusstr != null &&
                  (constatstatusstr.equals(Applic.app.getString(R.string.status_waiting_for_data)) ||
                   constatstatusstr.equals(Applic.app.getString(R.string.status_raw_values_received)))) {
               constatstatusstr = "";
            }
            historyReplayBaselineSec = lastGlucose[0];
         }
         if (pendingHistoryRoomMerge) {
            pendingHistoryRoomMerge = false;
            HistorySyncAccess.mergeFullSyncForSensor(SerialNumber);
         }
         Applic.updatescreen();
         UiRefreshBus.requestDataRefresh();
      }
   };

   private void scheduleHistoryUiRefresh() {
      long[] lastGlucose = Natives.getlastGlucose();
      if (lastGlucose != null && lastGlucose.length >= 1) {
         historyReplayBaselineSec = Math.max(historyReplayBaselineSec, lastGlucose[0]);
      }
      pendingHistoryUiRefresh = true;
      pendingHistoryRoomMerge = true;
      Applic.app.getHandler().removeCallbacks(historyUiRefreshRunnable);
      Applic.app.getHandler().postDelayed(historyUiRefreshRunnable, 250L);
   }

   @SuppressLint("MissingPermission")
   @Override
   public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
      noteFirstGattCallback("onConnectionStateChange", bluetoothGatt);
      if (stop) {
         if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            bluetoothGatt.close();
            mBluetoothGatt = null;
         }
         {
            if (doLog) {
               Log.i(LOG_ID, "onConnectionStateChange stop==true");
            }
            ;
         }
         ;
         return;
      }
      long tim = System.currentTimeMillis();
      if (doLog) {
         final String[] state = { "DISCONNECTED", "CONNECTING", "CONNECTED", "DISCONNECTING" };
         {
            if (doLog) {
               Log.i(LOG_ID, SerialNumber + " onConnectionStateChange, status:" + status + ", state: "
                     + (newState < state.length ? state[newState] : newState));
            }
            ;
         }
         ;
      }
      if (newState == BluetoothProfile.STATE_CONNECTED) {
         constatchange[0] = tim;
         if (!bluetoothGatt.discoverServices()) {
            Log.e(LOG_ID, "bluetoothGatt.discoverServices()  failed");
            disconnect();
         }
         connected = true;
         constatstatusstr = Applic.app.getString(R.string.status_waiting_for_data);
      } else {
         connected = false;
         if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (!autoconnect) {
               bluetoothGatt.close();
               mBluetoothGatt = null;
               if (!stop) {
                  var sensorbluetooth = SensorBluetooth.blueone;
                  if (sensorbluetooth != null)
                     sensorbluetooth.connectToActiveDevice(this, 0);
               }
            } else {
               if (!stop) {
                  bluetoothGatt.connect();
               } else {
                  bluetoothGatt.close();
                  mBluetoothGatt = null;
               }
            }
         }
         setConStatus(status);
         constatchange[1] = tim;
      }
   }

   static private final UUID serviceUUID = UUID.fromString("0000ff30-0000-1000-8000-00805f9b34fb");
   static private final UUID serviceChar1UUID = UUID.fromString("0000ff31-0000-1000-8000-00805f9b34fb");
   static private final UUID serviceChar2UUID = UUID.fromString("0000ff32-0000-1000-8000-00805f9b34fb");

   private BluetoothGattCharacteristic service1, service2;

   private boolean discover(BluetoothGatt bluetoothGatt) {
      {
         if (doLog) {
            Log.i(LOG_ID, "discover");
         }
         ;
      }
      ;
      BluetoothGattService service = bluetoothGatt.getService(serviceUUID);
      if (service == null) {
         var mess = "getService(serviceUUID)==null";
         {
            if (doLog) {
               Log.i(LOG_ID, mess);
            }
            ;
         }
         ;
         handshake = mess;
         wrotepass[1] = System.currentTimeMillis();
         return false;
      }

      service1 = service.getCharacteristic(serviceChar1UUID);
      service2 = service.getCharacteristic(serviceChar2UUID);
      if (service1 == null || service2 == null) {
         var mess = (service1 == null ? "service1==null " : "") + (service2 == null ? "service2==null" : "");
         {
            if (doLog) {
               Log.i(LOG_ID, mess);
            }
            ;
         }
         ;
         wrotepass[1] = System.currentTimeMillis();
         return false;
      }

      return enablenotifications(bluetoothGatt);
   }

   private boolean enablenotifications(BluetoothGatt bluetoothGatt) {
      {
         if (doLog) {
            Log.i(LOG_ID, "enablenotifications");
         }
         ;
      }
      ;

      var des = service1.getDescriptor(mCharacteristicConfigDescriptor);
      if (des == null) {
         final var tim = System.currentTimeMillis();
         var mes = "service1.getDescriptor(descriptor)==null";
         {
            if (doLog) {
               Log.i(LOG_ID, mes);
            }
            ;
         }
         ;
         handshake = mes;
         wrotepass[1] = tim;
         return false;
      }
      if (bluetoothGatt.setCharacteristicNotification(service1, true)) {
         des.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
         BluetoothGattCharacteristic characteristic = des.getCharacteristic();
         int writeType = characteristic.getWriteType();
         characteristic.setWriteType(2);
         boolean writeDescriptor = bluetoothGatt.writeDescriptor(des);
         characteristic.setWriteType(writeType);
         if (!writeDescriptor) {
            var mess = "writeDescriptor failed";
            Log.e(LOG_ID, mess);
            handshake = mess;
            wrotepass[1] = System.currentTimeMillis();
            return false;
         }
         {
            if (doLog) {
               Log.i(LOG_ID, "writeDescriptor=" + writeDescriptor);
            }
            ;
         }
         ;
      } else {
         var mess = "setCharacteristicNotification(service1,true) failed";
         Log.e(LOG_ID, mess);
         handshake = mess;
         wrotepass[1] = System.currentTimeMillis();
         return false;
      }
      {
         if (doLog) {
            Log.i(LOG_ID, "enablenotifications succeeded");
         }
         ;
      }
      ;
      return true;
   }
   // private static final UUID l =
   // UUID.fromString("00002A05-0000-1000-8000-00805f9b34fb");

   @Override // android.bluetooth.BluetoothGattCallback
   public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
      {
         if (doLog) {
            Log.i(LOG_ID, "BLE onServicesDiscovered invoked, status: " + status);
         }
         ;
      }
      ;
      if (status != GATT_SUCCESS || !discover(bluetoothGatt)) {
         disconnect();
      }

   }

   @SuppressLint("MissingPermission")
   private void askvalues(BluetoothGatt bluetoothGatt) {
      var tim = System.currentTimeMillis();
      final byte[] data = Natives.siAsknewdata(dataptr);
      if (data == null) {
         var mess = "siAsknewdata==null";
         handshake = mess;
         wrotepass[1] = tim;
         Log.e(LOG_ID, mess);
         disconnect();
         return;
      }
      if (write2(data))
         wrotepass[0] = tim;
   }

   @Override
   public synchronized boolean requestLatestDataForReplay() {
      if (stop || dataptr == 0) {
         if (doLog) {
            Log.i(LOG_ID, SerialNumber + " requestLatestDataForReplay: skipped (stop=" + stop + ", dataptr=" + dataptr + ")");
         }
         return false;
      }
      if (!connected || mBluetoothGatt == null || service2 == null) {
         if (doLog) {
            Log.i(LOG_ID, SerialNumber + " requestLatestDataForReplay: skipped (connected=" + connected
                  + ", gatt=" + (mBluetoothGatt != null) + ", service2=" + (service2 != null) + ")");
         }
         return false;
      }

      final byte[] data = Natives.siAsknewdata(dataptr);
      if (data == null) {
         Log.w(LOG_ID, SerialNumber + " requestLatestDataForReplay: siAsknewdata returned null");
         return false;
      }

      final boolean ok = write2(data);
      if (ok) {
         wrotepass[0] = System.currentTimeMillis();
      }
      Log.i(LOG_ID, SerialNumber + " requestLatestDataForReplay: write=" + ok);
      return ok;
   }

   @Override
   public void onCharacteristicWrite(BluetoothGatt bluetoothGatt,
         BluetoothGattCharacteristic bluetoothGattCharacteristic, int status) {
      if (Log.doLog) {
         {
            if (doLog) {
               Log.d(LOG_ID, bluetoothGatt.getDevice().getAddress() + " onCharacteristicWrite, status:" + status
                     + " UUID:" + bluetoothGattCharacteristic.getUuid().toString());
            }
            ;
         }
         ;
      }
   }

   @SuppressLint("MissingPermission")
   private boolean write2(byte[] bytes) {
      if (bytes == null || bytes.length == 0) {
         Log.e(LOG_ID, "write2 skipped empty command");
         return false;
      }
      if (service2 == null) {
         disconnect();
         return false;
      }

      service2.setValue(bytes);
      var blue = mBluetoothGatt;
      if (blue != null)
         return blue.writeCharacteristic(service2);
      else
         disconnect();
      return false;
   }

   private void authenticate() {
      // justAuthenticated=true;
      if (hasNotChinese) {
         final var bytes = Natives.siAuthBytes(dataptr);
         if (bytes != null)
            write2(bytes);
      }
   }

   private void activate() {
      if (hasNotChinese)
         write2(Natives.getSIActivation());
   }

   private boolean writeReset() {
      if (hasNotChinese) {
         if (write2(Natives.getSIResetBytes())) {
            Log.i(LOG_ID, "writeReset successful");
            return true;
         } else {
            Log.i(LOG_ID, "writeReset failed");
            return false;
         }
      }
      return true;
   }

   private void tryer(Supplier<Boolean> worked) {
      if (worked.get())
         return;
      Applic.scheduler.schedule(() -> {
         for (int i = 0; i < 16; i++) {
            if (!connected) {
               {
                  if (doLog) {
                     Log.i(LOG_ID, "tryer stops not connected");
                  }
                  ;
               }
               ;
               return;
            }
            if (worked.get())
               return;
            sleep(20);
         }
      }, 20, TimeUnit.MILLISECONDS);
   }

   private boolean novalue = false;

   @SuppressLint("MissingPermission")
   private void sendtime() {
      if (hasNotChinese)
         write2(Natives.getSItimecmd());
   }

   private synchronized void processchanged(byte[] value) {
      if (stop || dataptr == 0)
         return;
      long timmsec = System.currentTimeMillis();
      long res = Natives.SIprocessData(dataptr, value, timmsec);
      if (res == 10L) {
         tryer(() -> writeReset());
         Natives.setResetSibionics2(dataptr, false);
         novalue = true;
         Applic.app.getHandler().postDelayed(() -> {
            if (novalue) {
               Log.e(LOG_ID, "1: postDelayed disconnect");
               disconnect();
               novalue = false;
            }
         }, 3 * 60 * 1000L);
         return;
      }
      if (res == 2L) {
         if (!novalue) {
            novalue = true;
            Applic.app.getHandler().postDelayed(() -> {
               if (novalue) {
                  Log.e(LOG_ID, "2: postDelayed disconnect");
                  disconnect();
                  novalue = false;
               }
            }, 30 * 1000L);
         }
         return;
      }
      ;
      if (res == 8L) {
         if (!novalue) {
            novalue = true;
            Applic.app.getHandler().postDelayed(() -> {
               if (novalue) {
                  Log.e(LOG_ID, "3: postDelayed disconnect");
                  disconnect();
                  novalue = false;
               }
            }, 5 * 60 * 1000L);
         }
         return;
      }
      ;
      if (res == 9L) {
         return;
      }
      novalue = false;
      if (res == 3L) {
         Log.e(LOG_ID, "3: disconnect");
         disconnect();
         return;
      }
      if (res == 1L) {
         sensorstartmsec = Natives.getSensorStartmsec(dataptr);
         scheduleHistoryUiRefresh();
         return;
       }
      if (res == 11L) {
         charcha[0] = timmsec;
         SensorBluetooth.othersworking(this, timmsec);
         if (constatstatusstr != null &&
             constatstatusstr.equals(Applic.app.getString(R.string.status_waiting_for_data))) {
            constatstatusstr = Applic.app.getString(R.string.status_raw_values_received);
         }
         scheduleHistoryUiRefresh();
         return;
      }
      if (res == 4L) {
         Applic.app.getHandler().postDelayed(() -> {
            authenticate();
         }, 1000L);
         return;
      }
      if (res == 5L) {
         sendtime();
         return;
      }
      if (res == 6L) {
         activate();
         return;
      }
      if (res == 7L) {
         var blue = mBluetoothGatt;
         if (blue != null)
            askvalues(blue);
         return;
      }
      // Handle status updates based on glucose result
      if (res != 0L) {
         // Non-zero glucose: clear any waiting/raw status
         if (constatstatusstr != null && 
             (constatstatusstr.equals(Applic.app.getString(R.string.status_waiting_for_data)) ||
              constatstatusstr.equals(Applic.app.getString(R.string.status_raw_values_received)))) {
            constatstatusstr = "";
         }
         handleGlucoseResult(res, timmsec);
      } else {
         // res == 0: glucose is 0 (warmup period or algorithm not ready)
         // Set status to indicate raw values are being received
         int viewMode = Natives.getViewMode(dataptr);
         boolean isRawMode = (viewMode == 1 || viewMode == 3);
         
         if (constatstatusstr != null && 
             constatstatusstr.equals(Applic.app.getString(R.string.status_waiting_for_data))) {
            // We've received data but glucose is 0 - sensor is in warmup
            if (isRawMode) {
               constatstatusstr = Applic.app.getString(R.string.status_raw_values_received);
               if (doLog) {
                  Log.i(LOG_ID, SerialNumber + " warmup: raw values received, glucose=0");
               }
            }
         }
         handleGlucoseResult(res, timmsec);
      }
   }

   /*
    * void testchanged() {
    * if(doLog) {
    * byte [][] examples={
    * {(byte)0x23,(byte)0xF7,(byte)0x6F,(byte)0xD9,(byte)0xF4},
    * {(byte)0x23,(byte)0xF0,(byte)0x6F,(byte)0xDA,(byte)0xFA},
    * {(byte)0x23,(byte)0xF4,(byte)0x6F,(byte)0xDA,(byte)0xFE},
    * {(byte)0x23,(byte)0xF6,(byte)0x6F,(byte)0xDA,(byte)0xF0},
    * {(byte)0x23,(byte)0xFF,(byte)0x6F,(byte)0xDA,(byte)0xF9}};
    * for(var el:examples) {
    * processchanged(el);
    * }
    * }
    * }
    */
   @Override // android.bluetooth.BluetoothGattCallback
   public void onCharacteristicChanged(BluetoothGatt bluetoothGatt,
         BluetoothGattCharacteristic bluetoothGattCharacteristic) {
      byte[] value = bluetoothGattCharacteristic.getValue();
      if (doLog) {
         Log.showbytes("SiGattCallback RX", value);
      }
      showCharacter("onCharacteristicChanged", bluetoothGattCharacteristic);
      processchanged(value);
   }

   @Override
   public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
      {
         if (doLog) {
            Log.i(LOG_ID, "onReadRemoteRssi(BluetoothGatt," + rssi + "," + status
                  + (status == GATT_SUCCESS ? " SUCCESS" : " FAILURE"));
         }
         ;
      }
      ;
      if (status == GATT_SUCCESS) {
         readrssi = rssi;
      }
   }

   @Override
   public boolean matchDeviceName(String deviceName, String address) {
      if (deviceName == null)
         return false;
      var savedname = Natives.siGetDeviceName(dataptr);
      if (savedname != null && deviceName.equals(savedname))
         return true;

      // Sibionics 2 requires explicit transmitter pairing (second QR/manual code).
      // Do not auto-learn from 4-digit suffix matching when no transmitter is saved.
      if (Natives.getSiSubtype(dataptr) == 3) {
         return false;
      }

      final var len = deviceName.length();
      final String bluetoothNum = Natives.getSiBluetoothNum(dataptr);
      if (bluetoothNum.regionMatches(0, deviceName, len - 4, 4)) {
         Natives.siSaveDeviceName(dataptr, deviceName);
         return true;
      }
      return false;
   }

   @Override
   public synchronized void free() {
      if (stop)
         return;
      super.free();
      --siNR;
   }

   @Override
   public UUID getService() {
      return serviceUUID;
   }
}
