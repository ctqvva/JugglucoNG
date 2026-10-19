/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+,              */
/*      Sibionics GS1Sb and Accu-Chek SmartGuide sensors.                            */
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
/*      Fri Sep 19 14:52:28 CEST 2025                                                */
package tk.glucodata;


import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.webkit.ConsoleMessage.MessageLevel.LOG;
import static tk.glucodata.AccuGattCallback.ManufacturerNameCharUUID;
//import static tk.glucodata.BluetoothGlucoseMeter.bondString;
import static tk.glucodata.Applic.app;
import static tk.glucodata.BluetoothGlucoseMeter.mBluetoothAdapter;
import static tk.glucodata.Libre2GattCallback.showCharacter;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Natives.showbytes;
import static tk.glucodata.SuperGattCallback.bondString;
import static tk.glucodata.SuperGattCallback.enableIndicationnote;
import static tk.glucodata.SuperGattCallback.enableNotificationnote;
import static tk.glucodata.util.sleep;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.concurrent.TimeUnit;

import tk.glucodata.glucosemeter.SatelliteMeterCredentials;
import tk.glucodata.glucosemeter.SatelliteMeterProtocol;
import tk.glucodata.glucosemeter.SatelliteMeterSession;
import tk.glucodata.glucosemeter.SatelliteSessionUpdate;
import tk.glucodata.glucosemeter.VerioSession;

public  class GlucoseMeterGatt  extends BluetoothGattCallback {
    MeterList.MeterView view=null;
    final static private String LOG_ID="GlucoseMeterGatt";
    // Direct connect (false) right after we've just seen the device advertise -
    // from the picker or from MeterScanner matching a configured meter - then
    // back to the whitelist-based background connect (true) once we're in.
    // Meters like the Contour Plus advertise in sub-second bursts with long
    // gaps; the whitelist scan behind autoConnect=true is nowhere near
    // aggressive enough to catch that window, so a fresh sighting always
    // needs a direct connectGatt() to have a real chance of landing.
    private boolean autoConnect=true;
    protected BluetoothGatt mBluetoothGatt;
    public BluetoothDevice mActiveBluetoothDevice;
    public final int meterIndex;
    static private final boolean useConnect=(Build.VERSION.SDK_INT >26);
    public GlucoseMeterGatt(int index) {  
       meterIndex=index;

        }
void updateview() {
        if(view!=null) {
            ((MainActivity)view.getContext()).runOnUiThread(()-> view.setdata(view.meterIndex));
                } 
        }
        /*
public GlucoseMeterGatt(String deviceName) {  
   this(Natives.GlucoseMeterGetIndex(deviceName));
    } */
public String getDeviceAddress() {
        return Natives.GlucoseMeterDeviceAddress(meterIndex);
        }
public boolean setDeviceAddress(String address) {
        return Natives.GlucoseMeterSetDeviceAddress(meterIndex,address);
        }
public String getDeviceName() {
        return Natives.GlucoseMeterDeviceName(meterIndex);
        }
    public void setDevice(BluetoothDevice device) {
        mActiveBluetoothDevice = device;
        if(device!=null) {
            String address = device.getAddress();
            setDeviceAddress(address);
            // We were just handed this device from a scan result (picker pairing or
            // MeterScanner matching a configured meter), so it is advertising right
            // now - connect directly instead of waiting on the whitelist scan.
            autoConnect=false;
            }
        }
long foundtime=0L;
 private static final String TimeCharUUID = "00002a2b-0000-1000-8000-00805f9b34fb";
 private static final String GlucoseCharUUID = "00002a18-0000-1000-8000-00805f9b34fb";
 private static final String ContextCharUUID = "00002a34-0000-1000-8000-00805f9b34fb";
 private static final String RecordsCharUUID = "00002a52-0000-1000-8000-00805f9b34fb";
 private static final String DateTimeCharUUID = "00002a08-0000-1000-8000-00805f9b34fb";
 private static final String IsensTimeCharUUID ="0000fff1-0000-1000-8000-00805f9b34fb";
 private static final String SatelliteRxCharUUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
 private static final String SatelliteTxCharUUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";
 private static final String VerioWriteCharUUID = "af9df7a2-e595-11e3-96b4-0002a5d5c51b";
 private static final String VerioNotifyCharUUID = "af9df7a3-e595-11e3-96b4-0002a5d5c51b";
 private static final String GlucoseServiceUUID = "00001808-0000-1000-8000-00805f9b34fb";



private  BluetoothGattCharacteristic TimeChar;
private  BluetoothGattCharacteristic DateTimeChar;
private  BluetoothGattCharacteristic IsensTimeChar;
private  BluetoothGattCharacteristic GlucoseChar;
private  BluetoothGattCharacteristic ContextChar;
private  BluetoothGattCharacteristic RecordsChar;
private  BluetoothGattCharacteristic ManufacturerNameChar;
private BluetoothGattCharacteristic SatelliteRxChar;
private BluetoothGattCharacteristic SatelliteTxChar;
private SatelliteMeterSession satelliteSession;
private boolean satelliteMode=false;
private BluetoothGattCharacteristic VerioWriteChar;
private BluetoothGattCharacteristic VerioNotifyChar;
private final VerioSession verioSession=new VerioSession();



private boolean discovered=false;
private boolean discover(BluetoothGatt bluetoothGatt) {
    var services=bluetoothGatt.getServices();
    boolean success=false;
    satelliteMode=false;
    SatelliteRxChar=null;
    SatelliteTxChar=null;
    satelliteSession=null;
    VerioWriteChar=null;
    VerioNotifyChar=null;
    verioSession.reset();
    for(var ser:services) {
        if(doLog) Log.i(LOG_ID,"service: "+ser.getUuid().toString());
        final boolean satelliteService=ser.getUuid().equals(SatelliteMeterProtocol.SERVICE_UUID);
        final boolean verioService=ser.getUuid().equals(VerioSession.SERVICE_UUID);
        // Some meters (Accu-Chek Instant confirmed by an HCI capture) carry a second,
        // vendor-private service with its own characteristic that reuses the standard
        // Record Access Control Point UUID. Without this guard the vendor one is
        // discovered last and silently overwrites the real one, so every RACP write
        // lands on the wrong attribute and the meter rejects it with ATT error 0x81.
        final boolean glucoseService=ser.getUuid().toString().equals(GlucoseServiceUUID);
        var chars=ser.getCharacteristics();
        for(var s:chars) {
            var uuid=s.getUuid().toString();
            if(doLog) Log.i(LOG_ID,"Characteristic: "+uuid);
            switch(uuid) {
                case ManufacturerNameCharUUID: ManufacturerNameChar=s;break;
                case TimeCharUUID: TimeChar=s;break;
                case DateTimeCharUUID: DateTimeChar=s;break;
                case IsensTimeCharUUID: IsensTimeChar=s;break;
                case GlucoseCharUUID: GlucoseChar=s;success=true;break;
                case ContextCharUUID: ContextChar=s;break;
                case RecordsCharUUID: if(glucoseService || RecordsChar==null) RecordsChar=s;break;
                case SatelliteRxCharUUID: if(satelliteService) SatelliteRxChar=s;break;
                case SatelliteTxCharUUID: if(satelliteService) SatelliteTxChar=s;break;
                case VerioWriteCharUUID: if(verioService) VerioWriteChar=s;break;
                case VerioNotifyCharUUID: if(verioService) VerioNotifyChar=s;break;
                }
            }
        }
    if(SatelliteRxChar!=null && SatelliteTxChar!=null) {
        satelliteMode=true;
        success=true;
        if(doLog) Log.i(LOG_ID,"Satellite Nordic UART service discovered");
        beginSatelliteSession(bluetoothGatt);
    }
    else if(VerioWriteChar!=null && VerioNotifyChar!=null) {
        // OneTouch Verio Flex: no glucose service at all, just this vendor pair
        success=true;
        if(doLog) Log.i(LOG_ID,"OneTouch Verio service discovered");
        if(!enableNotification(bluetoothGatt,VerioNotifyChar)) {
            Log.e(LOG_ID,"Could not enable Verio notifications");
            success=false;
            }
        }
    else if(success)  {
        if(doLog) Log.i(LOG_ID,"discover succesfull");
        if(ManufacturerNameChar==null) {
            Log.e(LOG_ID,"no manufacturer name characteristic");
            return false;
            }
        tryer( ()->
            {
            return bluetoothGatt.readCharacteristic(ManufacturerNameChar);
            });
        }
    else {
        if(doLog)
            Log.i(LOG_ID,"discover failed");
        }
    discovered=success;
    return success;
    }

private void beginVerioSession(BluetoothGatt gatt) {
    verioSession.begin();
    writeNextVerioCommand(gatt);
    }

private void writeNextVerioCommand(BluetoothGatt gatt) {
    final byte[] command=verioSession.take();
    if(command==null)
        return;
    if(doLog) Log.showbytes(LOG_ID+": verio write",command);
    if(!writeVerio(gatt,command)) {
        // no write callback will follow, so keep the command for the next try
        Log.e(LOG_ID,"verio write failed, command kept");
        verioSession.putBack(command);
        }
    }

private void processVerioResponse(BluetoothGatt gatt, byte[] value) {
    if(doLog) Log.showbytes(LOG_ID+": verio notification",value);
    verioSession.onNotification(value,Natives.GlucoseMeterGetLastPos(meterIndex));
    final var readings=verioSession.takeReadings();
    if(!readings.isEmpty())
        persistVerioReadings(readings);
    // the answer queues the ack and the next request, and no write is in
    // flight any more, so drain it here
    writeNextVerioCommand(gatt);
    }

private void persistVerioReadings(List<VerioSession.Reading> readings) {
    for(final var reading:readings) {
        final long[] saved=Natives.GlucoseMeterSaveDecodedResult(
            meterIndex,reading.getTimestampMillis(),reading.getMgdlTenths());
        if(saved==null || saved.length<2) continue;
        GlucoseMeterJournalBridge.record(meterIndex,saved[0],saved[1]);
        if(saved.length>=3 && saved[2]!=0L) newvalues=true;
        final int stored=Natives.GlucoseMeterGetLastPos(meterIndex);
        if(reading.getRecord()>stored)
            Natives.GlucoseMeterSetLastPos(meterIndex,reading.getRecord());
        }
    receivedTime=System.currentTimeMillis();
    updateview();
    Applic.app.redraw();
    }

private void beginSatelliteSession(BluetoothGatt gatt) {
    final String pin=SatelliteMeterProtocol.resolvePin(SatelliteMeterCredentials.load());
    if(pin==null) {
        Log.e(LOG_ID,"Satellite meter code is missing or invalid");
        Applic.argToaster(app, R.string.satellite_meter_code_missing, android.widget.Toast.LENGTH_LONG);
        return;
    }
    satelliteSession=new SatelliteMeterSession(pin,System.currentTimeMillis());
    if(!enableNotification(gatt,SatelliteTxChar)) {
        Log.e(LOG_ID,"Could not enable Satellite notifications");
    }
}

long receivedTime=0L;
boolean newvalues=false;
    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        final var value=characteristic.getValue();
        final var uuid=characteristic.getUuid().toString();
        if(doLog)
            Log.showbytes("Meter: onCharacteristicChanged "+uuid,value);
        switch(uuid) {
            case SatelliteTxCharUUID:
                processSatelliteResponse(gatt,value);
                break;
            case VerioNotifyCharUUID:
                processVerioResponse(gatt,value);
                break;
            case GlucoseCharUUID:
                final long[] saved = Natives.GlucoseMeterSaveResult(meterIndex,value);
                if(saved != null && saved.length >= 2) {
                    GlucoseMeterJournalBridge.record(meterIndex, saved[0], saved[1]);
                }
                if(saved != null && saved.length >= 3 && saved[2] != 0L) {
                    newvalues=true;
                    receivedTime=System.currentTimeMillis();
                    updateview();
                    Applic.app.redraw();
                    }
                break;
            case RecordsCharUUID:
                if(Natives.recordCharacteristicChanged(value)) {
                    newvalues=true;
                    Applic.app.redraw();
                    }
                else
                    newvalues=false;
                receivedTime=System.currentTimeMillis();
               updateview();
                break;
            }

    }

private synchronized void processSatelliteResponse(BluetoothGatt gatt, byte[] value) {
    final SatelliteMeterSession session=satelliteSession;
    if(session==null) {
        Log.e(LOG_ID,"Ignoring Satellite response without an active session");
        return;
    }
    final SatelliteSessionUpdate update=session.onNotification(value);
    if(update.getError()!=null) {
        Log.e(LOG_ID,update.getError());
    }
    if(update.getComplete()) {
        persistSatelliteReadings(update.getReadings());
        satelliteSession=null;
        return;
    }
    final byte[] command=update.getCommand();
    if(command!=null) {
        tryer(()->writeSatellite(gatt,command));
    }
}

private void persistSatelliteReadings(List<SatelliteMeterProtocol.Reading> readings) {
    boolean stored=false;
    for(final SatelliteMeterProtocol.Reading reading:readings) {
        final long[] saved=Natives.GlucoseMeterSaveDecodedResult(
            meterIndex,reading.getTimestampMillis(),reading.getMgdlTenths());
        if(saved==null || saved.length<2) continue;
        stored=true;
        GlucoseMeterJournalBridge.record(meterIndex,saved[0],saved[1]);
        if(saved.length>=3 && saved[2]!=0L) newvalues=true;
    }
    if(stored) {
        receivedTime=System.currentTimeMillis();
        updateview();
        Applic.app.redraw();
    }
}


private boolean firstRecordonly=false;
// Roche meters (Accu-Chek Instant confirmed by trace) accept the write of a
// filtered RACP "records >= sequence" request but then never answer and drop
// the link, unlike xDrip which only ever sends them the plain ALL_RECORDS
// request. newerRecords stays on for every other manufacturer.
private boolean allRecordsOnly=false;
static private final boolean newerRecords=true;

protected  final boolean enableNotification(BluetoothGatt bluetoothGatt1, BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        return  enableNotificationnote(""+meterIndex, bluetoothGatt1,  bluetoothGattCharacteristic);
        }
protected  final boolean enableIndication(BluetoothGatt bluetoothGatt1, BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        return  enableIndicationnote(""+meterIndex, bluetoothGatt1, bluetoothGattCharacteristic) ;
        }
private void tryer(Supplier<Boolean> worked) {
        if(worked.get())
            return;
        Applic.scheduler.schedule(() -> { 
             for(int i=0;i<16;i++) {
                  if(!connected) {
                       {if(doLog) {Log.i(LOG_ID,"tryer stops not connected");};};
                      return;
                      }
                  if(worked.get()) return; 
                  sleep(20) ;
                 } }, 20, TimeUnit.MILLISECONDS);
       } 
private void handleManufactory(BluetoothGatt gatt,String manufacturer) {
        if(doLog)
            Log.i(LOG_ID,"handleManufactory "+ manufacturer);
        if(manufacturer.startsWith("Roche")) {
                allRecordsOnly=true;
                if(doLog)
                        Log.i(LOG_ID,"read DateTime");
                tryer(()->gatt.readCharacteristic(DateTimeChar));
                return;
                }
        if(manufacturer.startsWith("TaiDoc")) {
                firstRecordonly=true;
                tryer(()->enableNotification(gatt, GlucoseChar));
                return;
                }
        if(manufacturer.startsWith("i-SENS")) {
               // newerRecords=true;
                tryer(()->enableNotification(gatt, IsensTimeChar));
                return;
                }
        tryer(()->gatt.readCharacteristic(TimeChar));
       }
    @Override
    public void onCharacteristicRead(@NonNull BluetoothGatt bluetoothGatt,@NonNull  BluetoothGattCharacteristic bluetoothGattCharacteristic, int status) {
        final var value=bluetoothGattCharacteristic.getValue();
        final var uuid=bluetoothGattCharacteristic.getUuid().toString();
        if(doLog) {Log.d(LOG_ID, bluetoothGatt.getDevice().getAddress() + " onCharacteristicRead, status:" + status + " UUID:" + uuid);};;

      if(status!=GATT_SUCCESS) {
            if(status == GATT_INSUFFICIENT_AUTHENTICATION) { 
                if(doLog)
                    Log.i(LOG_ID,"onCharacteristicRead GATT_INSUFFICIENT_AUTHENTICATION");
                return;
                }
            if(doLog) {
               String message = "CharacteristicRead "+bluetoothGattCharacteristic.getUuid().toString();
               Log.i(LOG_ID,message);
               }
            disconnect();
            return;
            }
        switch(uuid) {
            case ManufacturerNameCharUUID: handleManufactory(bluetoothGatt,new String(value));break;
            case DateTimeCharUUID: 
            case TimeCharUUID: 
                Natives.GlucoseMeterSaveTime(meterIndex,value);
                if(ContextChar!=null) {
                    tryer(()->enableNotification(bluetoothGatt, ContextChar));
                    }
                else {
                    tryer(()->enableNotification(bluetoothGatt, GlucoseChar));break;
                    }
                break;
                 
            default: Log.e(LOG_ID,"onCharacteristicRead: Unknown UUID "+uuid);break;
            };
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        final var uuid=characteristic.getUuid().toString();
 //       final var value=characteristic.getValue();
        if(doLog) {
            showCharacter("onCharacteristicWrite",characteristic);
            }
        switch(uuid) {
            case IsensTimeCharUUID: tryer(()->enableNotification(gatt, GlucoseChar));break;

            case VerioWriteCharUUID:
                // the meter takes one command at a time, so send the next queued one
                if(status!=GATT_SUCCESS)
                    Log.e(LOG_ID,"verio write status: "+status);
                writeNextVerioCommand(gatt);
                break;

            default:
            }
    }
boolean connected=false;
 boolean stop=false;
 long connectedTime=0L;
 long disconnectedTime=0L;
 boolean isBonded=false;
    @Override
    public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
        long tim = System.currentTimeMillis();
        final var bondstate = bluetoothGatt.getDevice().getBondState();
        if (doLog) {
                final String[] state = {"DISCONNECTED", "CONNECTING", "CONNECTED", "DISCONNECTING"};
                Log.i(LOG_ID, meterIndex + " onConnectionStateChange, status:" + status + ", state: " + (newState < state.length ? state[newState] : newState) + " bondstate= "+bondString(bondstate) +" "+ bondstate);
             }
       if(newState == BluetoothProfile.STATE_CONNECTED) {
         isBonded=bondstate==BOND_BONDED;
         connectedTime=tim;
         updateview();
         // The fresh-sighting direct connect (if that is what got us here) has done
         // its job; go back to the battery-friendly whitelist reconnect for whenever
         // this link drops later.
         autoConnect=true;
        if(stop) {
            close();
            return;
            }
          discovered=false;
          connected=true;
          if(bondstate == BluetoothDevice.BOND_BONDING) {
              {if(doLog) {Log.i(LOG_ID, "wait BOND_BONDING");};};
              }
          else {
            if(bondstate == BOND_NONE || bondstate == BOND_BONDED) {
                  if(!bluetoothGatt.discoverServices()) {
                         final String mess="bluetoothGatt.discoverServices()  failed";
                         Log.e(LOG_ID, mess);
                         disconnect();
                        }    
                   else
                     Log.i(LOG_ID,"discoverServices() called");

            } else {
                Log.e(LOG_ID, "getBondState() returns unknown state " + bondstate);
                disconnect();
                }
            }
        } else {
           disconnectedTime=tim;
           updateview();
           connected=false;
           satelliteSession=null;
            if(bondstate == BluetoothDevice.BOND_BONDING) {
                   {if(doLog) {Log.i(LOG_ID, "BOND_BONDING");};};
                   }
            if(newState == BluetoothProfile.STATE_DISCONNECTED) {
                if(stop) {
                        if(doLog)
                           Log.i(LOG_ID,"stopping");
                      close();
                        }
                  else {
                     if(autoConnect)  {
                        if(useConnect)
                            bluetoothGatt.connect();
                        }
                    else {
                        // The one direct attempt a fresh sighting earns did not land.
                        // Fall back to the whitelist connect: retrying directly would
                        // keep the radio dialing for as long as the meter stays silent,
                        // which for a meter is most of the day. The next sighting
                        // (MeterScanner or the picker, via setDevice) earns another try.
                        autoConnect=true;
                        connectActiveOrScan(0);
                        }
                      }
                 }
         }
    }

    @Override
    public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        if(doLog)
            Log.i(LOG_ID,"onDescriptorRead/3 "+status);
    }

    @Override
    public void onDescriptorRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattDescriptor descriptor, int status, @NonNull byte[] value) {
        if(doLog)
                Log.i(LOG_ID,"onDescriptorRead/4 "+status);
    }

  private boolean writer(BluetoothGatt mBluetoothGatt,BluetoothGattCharacteristic cha, byte[] data) {
        if (!cha.setValue(data)) {
            {if(doLog){Log.showbytes(LOG_ID + ": " +cha.getUuid().toString() + " cha.setValue failed", data);};}
            return false;
        }
        if (!mBluetoothGatt.writeCharacteristic(cha)) {
            {if(doLog){Log.showbytes(LOG_ID + ": " +cha.getUuid().toString() + " writeCharacteristic failed", data);};}
            return false;
        }
        {if(doLog){Log.showbytes(LOG_ID + " writeCharacteristic: "+cha.getUuid().toString(), data);};}
        return true;
    }

private boolean writeSatellite(BluetoothGatt gatt,byte[] command) {
    final BluetoothGattCharacteristic characteristic=SatelliteRxChar;
    if(characteristic==null || !characteristic.setValue(command)) {
        Log.e(LOG_ID,"Could not prepare Satellite command");
        return false;
    }
    final boolean written=gatt.writeCharacteristic(characteristic);
    if(doLog) Log.i(LOG_ID,written ? "Satellite command written" : "Satellite command write failed");
    return written;
}

private boolean writeVerio(BluetoothGatt gatt,byte[] command) {
    final BluetoothGattCharacteristic characteristic=VerioWriteChar;
    if(characteristic==null || !characteristic.setValue(command)) {
        Log.e(LOG_ID,"Could not prepare Verio command");
        return false;
        }
    return gatt.writeCharacteristic(characteristic);
}
//s/\<\([a-zA-Z0-9]*\).writeCharacteristic(\([^,]*\),\([^)]*\))/writer(\1,\2,\3)

private void setCareSenseTime(BluetoothGatt bluetoothGatt) {
    byte[] cmd=Natives.careSenseTimeCMD();
    tryer(()-> writer(bluetoothGatt,IsensTimeChar,cmd));
    }
    @Override
    public void onDescriptorWrite(BluetoothGatt bluetoothGatt, BluetoothGattDescriptor bluetoothGattDescriptor, int status) {
        BluetoothGattCharacteristic characteristic = bluetoothGattDescriptor.getCharacteristic();
        var uuid=characteristic.getUuid().toString();
        if (doLog) {
            byte[] value = bluetoothGattDescriptor.getValue();
            {if(doLog){showbytes("GlucoseMeter: onDescriptorWrite char: " + uuid + " desc: " + bluetoothGattDescriptor.getUuid().toString() + " status=" + status, value);};}
            }
        switch(uuid) {
           case SatelliteTxCharUUID:
                if(status!=GATT_SUCCESS) {
                    Log.e(LOG_ID,"Satellite notification descriptor failed: "+status);
                    satelliteSession=null;
                    break;
                }
                final SatelliteMeterSession session=satelliteSession;
                if(session!=null) {
                    final byte[] command=session.notificationsEnabled().getCommand();
                    if(command!=null) tryer(()->writeSatellite(bluetoothGatt,command));
                }
                break;
           case VerioNotifyCharUUID:
                if(status!=GATT_SUCCESS) {
                    Log.e(LOG_ID,"Verio notification descriptor failed: "+status);
                    break;
                    }
                beginVerioSession(bluetoothGatt);
                break;
           case GlucoseCharUUID:
                tryer(()->enableIndication(bluetoothGatt, RecordsChar));
                break;
           case ContextCharUUID:
                    tryer(()->enableNotification(bluetoothGatt, GlucoseChar));
                    break;
           case RecordsCharUUID:
                if(firstRecordonly) {
                      byte[] cmd={1,(byte)0x5};
                      tryer(()->writer(bluetoothGatt, RecordsChar,cmd));
                      }
                else if(allRecordsOnly) {
                      byte[] cmd={1,(byte)0x1};
                      tryer(()->writer(bluetoothGatt, RecordsChar,cmd));
                      }
                else {
                    if(newerRecords) {
                        byte[] cmd=Natives.getGlucoseMeterNewCMD(meterIndex);
                        if(cmd!=null) {
                            tryer(()->writer(bluetoothGatt, RecordsChar,cmd));
                            }
                        else {
                              disconnect();
                               }
                        }
                    else {
                       byte[] cmd={1,(byte)0x1};
                       tryer(()->writer(bluetoothGatt, RecordsChar,cmd));
                    }
                    }
                    break;
          case IsensTimeCharUUID:
                    setCareSenseTime(bluetoothGatt);
                    break;
            }
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        if(doLog) {
            Log.i(LOG_ID,"onMtuChanged mtu="+mtu+" status="+ status);
            }
    }

    @Override
    public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
        if(doLog) {
            Log.i(LOG_ID,"onPhyRead txPhy="+ txPhy+"  rxPhy="+rxPhy+" status="+ status);
            }
    }

    @Override
    public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
        if(doLog) {
            Log.i(LOG_ID,"onPhyUpdate txPhy="+ txPhy+"  rxPhy="+rxPhy+" status="+ status);
            }
       }

    @Override
    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
        if(doLog) {
            Log.i(LOG_ID,"onReadRemoteRssi rssi"+ rssi+" status"+status);
            }
    }

    @Override
    public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
        if(doLog) {
            Log.i(LOG_ID,"onReliableWriteCompleted "+status);
            }
    }

    @Override
    public void onServiceChanged(@NonNull BluetoothGatt gatt) {
       if(doLog)
        Log.i(LOG_ID,"onServiceChanged ");
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        if(doLog)
            Log.i(LOG_ID,"onServicesDiscovered "+status);
        if(status==GATT_SUCCESS && requestBond(gatt))
            // bonded() calls discoverServices() again once BOND_BONDED arrives
            return;
        discover(gatt);
    }

    // Classic BLE bonding, like xDrip does for the same meters. Glucose meters
    // keep their glucose characteristic encrypted, so without a bond every read
    // fails with GATT_INSUFFICIENT_AUTHENTICATION and the meter stays silent.
    // A meter that is not in pairing mode simply never answers, so fall back to
    // an unbonded attempt via bonded()'s BOND_NONE branch.
    private boolean requestBond(BluetoothGatt gatt) {
        if(hasNordicUartService(gatt))
            return false; // Satellite does its own PIN authentication over NUS and rejects bonding
        final var device=gatt.getDevice();
        final int bondstate=device.getBondState();
        if(bondstate==BOND_BONDED)
            return false;
        if(bondstate==BluetoothDevice.BOND_BONDING)
            return true; // wait for BOND_BONDED
        if(doLog) {Log.i(LOG_ID, "createBond(), the meter must be in pairing mode");};
        return device.createBond();
    }

    private boolean hasNordicUartService(BluetoothGatt gatt) {
        final var services=gatt.getServices();
        if(services==null)
            return false;
        for(var service:services) {
            if(service.getUuid().equals(SatelliteMeterProtocol.SERVICE_UUID))
                return true;
            }
        return false;
    }


    private Runnable getConnectDevice() {
        disconnect();
        return () -> {
             close();
            if(doLog) {Log.i(LOG_ID,"getConnectDevice Runnable "+ meterIndex);};
            if(!BluetoothGlucoseMeter.bluetoothIsEnabled()) {
                Log.e(LOG_ID, "!bluetoothIsEnabled() ");
                return ;
                }
            var device= mActiveBluetoothDevice;
            if(device==null) {
                Log.e(LOG_ID, meterIndex +" "+"device==null");
                return;
                }
        
            if (mBluetoothGatt != null) {
                if(doLog) {Log.d(LOG_ID, meterIndex + " mBluetoothGatt!=null");};
                return;
                }
            if(doLog) {
                var devname=device.getName();
                if(devname!=null) {
                        if(doLog)
                            Log.d(LOG_ID, meterIndex + " Try connection to " + device.getAddress()+ " "+devname);;
                        }
                }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    mBluetoothGatt = device.connectGatt(app, autoConnect, this, BluetoothDevice.TRANSPORT_LE);
                } else {
                    mBluetoothGatt = device.connectGatt(app, autoConnect, this);
                    }

            if(doLog) {Log.i(LOG_ID,meterIndex+" after connectGatt ="+mBluetoothGatt);};
            } catch (SecurityException se) {
                var mess = se.getMessage();
                mess = mess == null ? "" : mess;
                String uit = ((Build.VERSION.SDK_INT > 30) ? Applic.getContext().getString(R.string.turn_on_nearby_devices_permission)  : mess) ;
                Applic.Toaster(uit);

                Log.stack(LOG_ID, meterIndex +" "+ "connectGatt", se);
            } catch (Throwable e) {
                Log.stack(LOG_ID, meterIndex +" "+ "connectGatt", e);

                }
        };
    }

    public void close() {
       if(doLog) {Log.i(LOG_ID,"close "+meterIndex);};
        var tmpgatt=mBluetoothGatt ;
        if (tmpgatt != null) {
            try {
                tmpgatt.disconnect();
                tmpgatt.close();
            } catch (Throwable se) {
                var mess = se.getMessage();
                mess = mess == null ? "" : mess;
                String uit = ((Build.VERSION.SDK_INT > 30) ? Applic.getContext().getString(R.string.turn_on_nearby_devices_permission)  : mess) ;
                Applic.Toaster(uit);
                Log.stack(LOG_ID, meterIndex +" "+ "BluetoothGatt.close()", se);
            }
        finally {    
            mBluetoothGatt = null;
            }
        }
    else {
        {if(doLog) {Log.i(LOG_ID,"close mBluetoothGatt==null");};};
        }

    }

public void disconnect() {
    final var thegatt= mBluetoothGatt;
    if(thegatt!=null) {
        {if(doLog) {Log.i(LOG_ID,"Disconnect");};};
        thegatt.disconnect();
        }
     else  {
        {if(doLog) {Log.i(LOG_ID,"Disconnect mBluetoothGatt==null");};};
      }
    }

void retrySatelliteSession() {
    if(satelliteMode) {
        satelliteSession=null;
        disconnect();
    }
}
 public boolean connectActiveDevice(long delayMillis) {
    if(doLog) {Log.i(LOG_ID,"connectDevice("+delayMillis+") "+ meterIndex);};
    Runnable connect=getConnectDevice();
    if(connect==null) 
        return false;
    Applic.scheduler.schedule(connect, delayMillis, TimeUnit.MILLISECONDS);
    return true;
    }
 public void connectActiveOrScan(long delayMillis) {
         if(!connectActiveDevice(delayMillis)) {
               BluetoothGlucoseMeter.startScanner(delayMillis);
               }
        }
 public void connectOrScan(long delayMillis) {
         if(!connectDevice(delayMillis)) {
               BluetoothGlucoseMeter.startScanner(delayMillis);
               }
        }
 public boolean connectDevice(long delayMillis) {
        disconnect();
        if(askDevice()) {
                return connectActiveDevice(delayMillis);
                }
        return false;
        }

boolean askDevice() {
    String mActiveDeviceAddress=getDeviceAddress();
    if(mActiveDeviceAddress != null) {
        if(BluetoothAdapter.checkBluetoothAddress(mActiveDeviceAddress)) {
            if(doLog) {Log.i(LOG_ID,"getDevice "+ meterIndex+" checkBluetoothAddress(" +mActiveDeviceAddress +") succeeded");};
            mActiveBluetoothDevice = mBluetoothAdapter.getRemoteDevice(mActiveDeviceAddress);
            return true;
             } 
           if(doLog) {Log.i(LOG_ID, "getDevice "+ meterIndex+" checkBluetoothAddress(" +mActiveDeviceAddress +") failed");};
           setDeviceAddress(null);
           return false;
           }
    if(doLog) {Log.i(LOG_ID,"getDevice no address "+meterIndex);};
    return false;
    }

    public void bonded() {
        final var bondstate = mActiveBluetoothDevice.getBondState();
        switch(bondstate) {
            case BluetoothDevice.BOND_BONDING: {
                if(doLog) {Log.i(LOG_ID,"bonding");};
                    try {
                        var uristr = "android.resource://" + app.getPackageName() + "/" + R.raw.bonded;
                        Uri uri = Uri.parse(uristr);
                        Ringtone ring = RingtoneManager.getRingtone(app, uri);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ring.setLooping(false);
                        }
                        ring.play();

                    } catch (Throwable th) {
                            Log.stack(LOG_ID, "bonded sound", th);
                   }
                };break;
            case  BOND_BONDED:  {
                isBonded=true;
                updateview();
                if(!discovered) {
                  if(mBluetoothGatt==null) {
                     if(doLog)
                          Log.i(LOG_ID,"bonded: mBluetoothGatt");
                      disconnect();
                      return;
                      }
                  if(mBluetoothGatt.discoverServices()) {
                        if(doLog)
                             Log.i(LOG_ID,"bonded: bluetoothGatt.discoverServices()  called");
                        }
                  else {
                         final String mess="bonded: bluetoothGatt.discoverServices()  failed";
                         Log.e(LOG_ID, mess);
                         disconnect();
                        }    
                    } 
                };break;
            case BOND_NONE: {
                // createBond() did not take, try the unbonded reads anyway
                if(doLog) {Log.i(LOG_ID, "bonded: BOND_NONE");};
                if(!discovered && mBluetoothGatt!=null)
                    discover(mBluetoothGatt);
                };break;
        }
    }

}
