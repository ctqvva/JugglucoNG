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

import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.bluetooth.BluetoothGattCharacteristic.FORMAT_UINT16;
import static android.bluetooth.BluetoothGattCharacteristic.FORMAT_UINT32;
import static android.bluetooth.BluetoothGattCharacteristic.FORMAT_UINT8;
import static android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
import static android.content.Context.ALARM_SERVICE;
import static tk.glucodata.DexGattCallback.setalarm;
import static tk.glucodata.Libre2GattCallback.showCharacter;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Natives.getalarmclock;
import static tk.glucodata.util.sleep;
import static tk.glucodata.util.timestring;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * CareSens Air (i-SENS), ported from upstream Juggluco 11.3. The transmitter is paired with
 * the PIN from the package's QR code; records go through native airProcessData, which runs
 * i-SENS' algorithm from libCALCULATION.so.
 */
class AirGattCallback extends SuperGattCallback {
    private final boolean askExtraInfo = doLog;
    static private final String LOG_ID = "AirGattCallback";
    private boolean unusedSensor;
    private boolean didRun = false;

    AirGattCallback(String SerialNumber, long dataptr) {
        super(SerialNumber, dataptr, SensorSourceResolver.SENSOR_KIND_CARESENS_AIR);
        if (doLog) {Log.d(LOG_ID, SerialNumber + " AirGattCallback(..)");}
        showtime = 6 * 60 * 1000L;
        unusedSensor = Natives.airGetLast(dataptr) <= 0;
    }

    private final String csairKey = "tq1Tg265o4UFD8tfPvNqUCiYyCxkhdZV";
    private String swRevision = "";
    // Also cleared by the delayed setup writes, off the GATT callback thread.
    private volatile boolean receiveNotes = false;
    final private String AppID = "csair";

    // The GATT callbacks below check identity but must not hold this monitor:
    // they call into SensorBluetooth (scanStarter) and other callbacks
    // (othersworking -> connectDevice), while SensorBluetooth.removeDevice holds
    // its own lock and then calls free() here. Holding both ways deadlocks.
    // dataptr's lifetime is guarded by nativeLock instead. It is held only around
    // the native calls, which never call back into Java, so it is a leaf lock:
    // free() takes it after this monitor, and nothing holding it waits for another.
    private final Object nativeLock = new Object();

    // Changes on every connection state change. With auto-connect the same GATT
    // object reconnects, so the delayed setup writes check this as well.
    private volatile int connectionEpoch = 0;

    private boolean isCurrentGatt(BluetoothGatt gatt) {
        return !stop && dataptr != 0L && gatt != null && gatt == mBluetoothGatt;
    }

    private synchronized void enableNotificationIfCurrent(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (isCurrentGatt(gatt))
            enableNotification(gatt, characteristic);
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onDescriptorWrite(BluetoothGatt bluetoothGatt, BluetoothGattDescriptor bluetoothGattDescriptor, int status) {
        if (!isCurrentGatt(bluetoothGatt))
            return;
        BluetoothGattCharacteristic characteristic = bluetoothGattDescriptor.getCharacteristic();
        if (doLog) {
            byte[] value = bluetoothGattDescriptor.getValue();
            Log.showbytes(LOG_ID + " onDescriptorWrite char: " + characteristic.getUuid().toString() + " desc: " + bluetoothGattDescriptor.getUuid().toString() + " status=" + status, value);
        }
        if (status != GATT_SUCCESS) {
            if (status == GATT_INSUFFICIENT_AUTHENTICATION) {
                Log.e(LOG_ID, "onDescriptorWrite GATT_INSUFFICIENT_AUTHENTICATION");
            }
            return;
        }
        String uuidstr = characteristic.getUuid().toString();
        if (uuidstr.equals(UUIDchar11)) {
            enableNotification(bluetoothGatt, charact21);
            return;
        }
        // The transmitter wants a pause before each of these writes. Wait on the
        // scheduler, not here: sleeping blocks every other GATT callback.
        if (uuidstr.equals(UUIDchar21)) {
            receiveNotes = false;
            final int epoch = connectionEpoch;
            Applic.scheduler.schedule(() -> writeAuthIfCurrent(bluetoothGatt, epoch), 500, TimeUnit.MILLISECONDS);
            return;
        }
        if (uuidstr.equals(UUIDchar22)) {
            receiveNotes = false;
            final int epoch = connectionEpoch;
            Applic.scheduler.schedule(() -> writeAppIdIfCurrent(bluetoothGatt, epoch), 100, TimeUnit.MILLISECONDS);
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void writeAuthIfCurrent(BluetoothGatt bluetoothGatt, int epoch) {
        if (!isCurrentGatt(bluetoothGatt) || epoch != connectionEpoch)
            return;
        charact21.setWriteType(WRITE_TYPE_NO_RESPONSE);
        if (swRevision.compareTo("1.4") < 0) {
            byte b2 = 0;
            charact21.setValue(new byte[]{-64, 1, (byte) 16, (byte) 39, b2, b2, (byte) (b2 & 255), (byte) ((b2 >> 8) & 255), (byte) 1, b2, b2});
        } else {
            final String serial = sensorSerial;
            if (serial == null || serial.length() < 6) {
                Log.e(LOG_ID, "writeAuth: no transmitter serial");
                disconnect();
                return;
            }
            final int seriallen = serial.length();
            final var lastsix = serial.substring(seriallen - 6);
            final String iv = lastsix + lastsix + serial.substring(seriallen - 4);
            ByteBuffer auth = ByteBuffer.allocate(18);
            auth.order(ByteOrder.LITTLE_ENDIAN);
            try {
                Charset charset = StandardCharsets.UTF_8;
                SecretKeySpec secretKeySpec = new SecretKeySpec(csairKey.getBytes(charset), "AES");
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, new IvParameterSpec(iv.getBytes(charset)));
                byte[] encrypted = cipher.doFinal(serial.getBytes(charset));
                auth.put((byte) -64);
                auth.put((byte) 1);
                auth.put(encrypted, 0, Math.min(encrypted.length, auth.remaining()));
            } catch (Throwable th) {
                Log.stack(LOG_ID, "writeAuth encrypt", th);
                disconnect();
                return;
            }
            charact21.setValue(auth.array());
        }
        receiveNotes = false;
        bluetoothGatt.writeCharacteristic(charact21);
    }

    @SuppressLint("MissingPermission")
    private synchronized void writeAppIdIfCurrent(BluetoothGatt bluetoothGatt, int epoch) {
        if (!isCurrentGatt(bluetoothGatt) || epoch != connectionEpoch)
            return;
        charact22.setWriteType(WRITE_TYPE_NO_RESPONSE);
        byte[] buf = new byte[35];
        final byte[] start = {-64, 3, (byte) 'c', (byte) 's', (byte) 'a', (byte) 'i', (byte) 'r'};
        System.arraycopy(start, 0, buf, 0, start.length);
        if (unusedSensor)
            buf[34] = 1;
        charact22.setValue(buf);
        receiveNotes = false;
        bluetoothGatt.writeCharacteristic(charact22);
    }

    private PendingIntent onalarm = null;

    private void cancelalarm() {
        if (onalarm != null) {
            if (doLog) {Log.i(LOG_ID, "cancelalarm");}
            AlarmManager manager = (AlarmManager) Applic.app.getSystemService(ALARM_SERVICE);
            manager.cancel(onalarm);
            onalarm = null;
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
        noteFirstGattCallback("onConnectionStateChange", bluetoothGatt);
        if (!acceptConnectionAttemptCallback(bluetoothGatt, newState) || dataptr == 0L)
            return;
        super.onConnectionStateChange(bluetoothGatt, status, newState);
        ++connectionEpoch;
        long tim = System.currentTimeMillis();
        if (doLog) {
            final var bondstate = bluetoothGatt.getDevice().getBondState();
            final String[] state = {"DISCONNECTED", "CONNECTING", "CONNECTED", "DISCONNECTING"};
            Log.i(LOG_ID, SerialNumber + " onConnectionStateChange, status:" + status + ", state: " + (newState < state.length ? state[newState] : newState) + " bondstate=" + bondstate);
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            bluetoothGatt.requestMtu(512);
            constatchange[0] = tim;
        } else {
            resetValues();
            setConStatus(status);
            constatchange[1] = tim;
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!autoconnect) {
                    bluetoothGatt.close();
                    mBluetoothGatt = null;
                    if (!stop) {
                        var sensorbluetooth = SensorBluetooth.blueone;
                        if (sensorbluetooth != null) {
                            // The transmitter has a record every 5 minutes; reconnecting
                            // sooner after a reading only drains the battery.
                            final long alreadywaited = tim - datatime;
                            if (getalarmclock()) {
                                final long mmsectimebetween = 5 * 60 * 1000;
                                long stillwait = mmsectimebetween - alreadywaited - 1000;
                                if (doLog) {Log.i(LOG_ID, " alreadywaited=" + alreadywaited + " stillwait=" + stillwait);}
                                if (stillwait > 0)
                                    onalarm = setalarm(tim + stillwait, onalarm, SerialNumber);
                                else
                                    sensorbluetooth.connectToActiveDevice(this, 0);
                            } else {
                                sensorbluetooth.connectToActiveDevice(this, 0);
                            }
                        }
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
        }
    }

    private static final String UUIDchar1 = "c4de7e96-5a9d-11e9-8647-d663bd873d93";
    private static final String UUIDchar2 = "c4de83c8-5a9d-11e9-8647-d663bd873d93";
    private static final String UUIDchar3 = "c4de8544-5a9d-11e9-8647-d663bd873d93";
    private static final String UUIDchar4 = "c4de86a2-5a9d-11e9-8647-d663bd873d93";
    private static final String UUIDchar5 = "c4de87e2-5a9d-11e9-8647-d663bd873d93";
    private static final String UUIDchar6 = "c4de89ae-5a9d-11e9-8647-d663bd873d93";
    private static final String UUIDchar7 = "c4de8af8-5a9d-11e9-8647-d663bd873d93";
    private static final String UUIDchar11 = "c4de9b74-5a9d-11e9-8647-d663bd873d93";
    private static final String UUIDchar21 = "c4de9ee4-5a9d-11e9-8647-d663bd873d93";
    private static final String UUIDchar22 = "c4dec61c-5a9d-11e9-8647-d663bd873d93";

    private BluetoothGattCharacteristic charact1;
    private BluetoothGattCharacteristic charact2;
    private BluetoothGattCharacteristic charact3;
    private BluetoothGattCharacteristic charact4;
    private BluetoothGattCharacteristic charact5;
    private BluetoothGattCharacteristic charact6;
    private BluetoothGattCharacteristic charact7;
    private BluetoothGattCharacteristic charact11;
    private BluetoothGattCharacteristic charact21;
    private BluetoothGattCharacteristic charact22;

    @SuppressLint("MissingPermission")
    private boolean discover(BluetoothGatt bluetoothGatt) {
        // Handles from an earlier connection must not make this one look complete.
        charact1 = charact2 = charact3 = charact4 = charact5 = charact6 = charact7 = null;
        charact11 = charact21 = charact22 = null;
        for (BluetoothGattService bluetoothGattService : bluetoothGatt.getServices()) {
            if (doLog)
                Log.i(LOG_ID, "Service: " + bluetoothGattService.getUuid().toString());
            for (var s : bluetoothGattService.getCharacteristics()) {
                var uuid = s.getUuid().toString();
                if (doLog)
                    Log.i(LOG_ID, "Characteristic: " + uuid);
                switch (uuid) {
                    case UUIDchar1: charact1 = s; break;
                    case UUIDchar2: charact2 = s; break;
                    case UUIDchar3: charact3 = s; break;
                    case UUIDchar4: charact4 = s; break;
                    case UUIDchar5: charact5 = s; break;
                    case UUIDchar6: charact6 = s; break;
                    case UUIDchar7: charact7 = s; break;
                    case UUIDchar11: charact11 = s; break;
                    case UUIDchar21: charact21 = s; break;
                    case UUIDchar22: charact22 = s; break;
                }
            }
        }
        if (charact11 == null || charact21 == null || charact22 == null) {
            Log.e(LOG_ID, "discover: ERROR: data characteristics missing");
            return false;
        }
        if (didRun) {
            afterReads(bluetoothGatt);
        } else {
            final var first = askExtraInfo ? charact1 : charact3;
            if (first == null) {
                Log.e(LOG_ID, "discover: ERROR: " + (askExtraInfo ? UUIDchar1 : UUIDchar3) + " missing");
                return false;
            }
            bluetoothGatt.readCharacteristic(first);
        }
        return true;
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
        if (!isCurrentGatt(bluetoothGatt))
            return;
        if (doLog) {Log.i(LOG_ID, "BLE onServicesDiscovered, status: " + status);}
        if (status == GATT_SUCCESS) {
            if (!discover(bluetoothGatt))
                disconnect();
            return;
        }
        disconnect();
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic, int status) {
        if (!isCurrentGatt(bluetoothGatt))
            return;
        switch (status) {
            case GATT_SUCCESS:
                if (doLog) {Log.i(LOG_ID, "onCharacteristicRead success");}
                characterRead(bluetoothGatt, bluetoothGattCharacteristic);
                return;
            case GATT_INSUFFICIENT_AUTHENTICATION:
                if (doLog) {Log.i(LOG_ID, "onCharacteristicRead GATT_INSUFFICIENT_AUTHENTICATION");}
                break;
            default:
                Log.e(LOG_ID, "onCharacteristicRead " + bluetoothGattCharacteristic.getUuid() + " status=" + status);
                break;
        }
        wrotepass[1] = System.currentTimeMillis();
        handshake = "CharacteristicRead " + bluetoothGattCharacteristic.getUuid().toString();
    }

    private String sensorSerial = null;

    @SuppressLint("MissingPermission")
    private void afterReads(BluetoothGatt bluetoothGatt) {
        if (doLog)
            Log.i(LOG_ID, "afterReads");
        if (swRevision.compareTo("1.5") >= 0) {
            Applic.scheduler.schedule(() -> enableNotificationIfCurrent(bluetoothGatt, charact22), 100, TimeUnit.MILLISECONDS);
            return;
        }
        enableDataOrBond(bluetoothGatt);
    }

    @SuppressLint("MissingPermission")
    private void enableDataOrBond(BluetoothGatt bluetoothGatt) {
        final BluetoothDevice device = mActiveBluetoothDevice;
        if (device == null) {
            Log.e(LOG_ID, "enableDataOrBond mActiveBluetoothDevice==null");
            disconnect();
            return;
        }
        if (device.getBondState() == BOND_BONDED) {
            wrotepass[0] = System.currentTimeMillis();
            Applic.scheduler.schedule(() -> enableNotificationIfCurrent(bluetoothGatt, charact11), 100, TimeUnit.MILLISECONDS);
            return;
        }
        Log.i(LOG_ID, "createBond");
        Applic.postDelayed(() -> {
            synchronized (this) {
                if (isCurrentGatt(bluetoothGatt))
                    device.createBond();
            }
        }, 1000L);
    }

    @SuppressLint("MissingPermission")
    private void readNext(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic next) {
        if (next != null)
            bluetoothGatt.readCharacteristic(next);
    }

    @SuppressLint("MissingPermission")
    private void characterRead(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        switch (bluetoothGattCharacteristic.getUuid().toString()) {
            case UUIDchar1: {
                if (doLog)
                    Log.showbytes(LOG_ID + " charact1", bluetoothGattCharacteristic.getValue());
                readNext(bluetoothGatt, charact2);
                return;
            }
            case UUIDchar2: {
                if (doLog)
                    Log.i(LOG_ID, "ModelNumber: " + bluetoothGattCharacteristic.getStringValue(0));
                readNext(bluetoothGatt, charact4);
                return;
            }
            case UUIDchar3: {
                sensorSerial = bluetoothGattCharacteristic.getStringValue(0);
                if (doLog)
                    Log.i(LOG_ID, "sensorSerial/charact3: " + sensorSerial);
                readNext(bluetoothGatt, charact6);
                return;
            }
            case UUIDchar4: {
                String fwRevision = bluetoothGattCharacteristic.getStringValue(0);
                if (fwRevision != null && fwRevision.compareTo("500.300.1.1") >= 0) {
                    if (doLog)
                        Log.i(LOG_ID, "Accepted fwRevision: " + fwRevision);
                    readNext(bluetoothGatt, charact5);
                    return;
                }
                handshake = "Unsupported firmware " + fwRevision;
                wrotepass[1] = System.currentTimeMillis();
                Log.e(LOG_ID, "WRONG fwRevision: " + fwRevision);
                bluetoothGatt.disconnect();
                return;
            }
            case UUIDchar5: {
                if (doLog)
                    Log.i(LOG_ID, "hwRevision : " + bluetoothGattCharacteristic.getStringValue(0));
                readNext(bluetoothGatt, charact7);
                return;
            }
            case UUIDchar7: {
                if (doLog)
                    Log.i(LOG_ID, "manufacturer: " + bluetoothGattCharacteristic.getStringValue(0));
                readNext(bluetoothGatt, charact3);
                return;
            }
            case UUIDchar6: {
                final String revision = bluetoothGattCharacteristic.getStringValue(0);
                swRevision = revision == null ? "" : revision;
                if (doLog)
                    Log.i(LOG_ID, "swRevision: " + swRevision);
                didRun = true;
                afterReads(bluetoothGatt);
                return;
            }
        }
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic, int status) {
        if (!isCurrentGatt(bluetoothGatt))
            return;
        if (doLog)
            showCharacter("onCharacteristicWrite " + bluetoothGatt.getDevice().getAddress() + " status:" + status + " ", bluetoothGattCharacteristic);
        receiveNotes = status == GATT_SUCCESS;
    }

    private void onChar22Changed(BluetoothGatt bluetoothGatt, byte[] value) {
        if (value.length < 35) {
            Log.e(LOG_ID, "onCharacteristicChanged: value charact22 < 35: " + value.length);
            return;
        }
        if ((value[0] & 0xFF) == 192 && value[1] == 3) {
            final byte last = value[value.length - 1];
            if (last > 0) {
                unusedSensor = false;
                switch (last) {
                    case 1:
                        handshake = "DEVICE MATCH FAILED";
                        break;
                    case 2:
                        handshake = "APPID MATCH FAILED";
                        break;
                    default:
                        handshake = "RECONNECT FAILED";
                        break;
                }
                wrotepass[1] = System.currentTimeMillis();
                Log.e(LOG_ID, "onChar22Changed: " + handshake);
                disconnect();
                return;
            }
            String strTrim = new String(value, 2, value.length - 3, StandardCharsets.US_ASCII).trim();
            if (!AppID.equals(strTrim)) {
                wrotepass[1] = System.currentTimeMillis();
                handshake = "appId different " + strTrim + " != " + AppID + " last=" + last;
                Log.e(LOG_ID, handshake);
                disconnect();
                return;
            }
            enableDataOrBond(bluetoothGatt);
        }
    }

    private boolean noticedNumberRecords = false;
    private boolean syncTime = false;

    private void resetValues() {
        syncTime = false;
        noticedNumberRecords = false;
        forCRCbuf = null;
    }

    @SuppressLint("MissingPermission")
    private void numberRecords(BluetoothGatt gatt) {
        if (doLog)
            Log.i(LOG_ID, "numberRecords");
        charact11.setWriteType(WRITE_TYPE_NO_RESPONSE);
        charact11.setValue(new byte[2]);
        charact11.setValue(197, FORMAT_UINT8, 0);
        charact11.setValue(1, FORMAT_UINT8, 1);
        receiveNotes = false;
        gatt.writeCharacteristic(charact11);
    }

    private int redrawer = 0;

    private void onChar11Changed(BluetoothGatt bluetoothGatt, byte[] value) {
        long[] timeptr = {System.currentTimeMillis()};
        final long res;
        synchronized (nativeLock) {
            if (dataptr == 0L)
                return;
            res = Natives.airProcessData(dataptr, value, timeptr);
        }
        if (res == 3L) {
            if (!noticedNumberRecords) {
                numberRecords(bluetoothGatt);
                noticedNumberRecords = true;
            }
            return;
        }
        if (res == 2L) {
            disconnect();
            return;
        }
        if (res == 1L) {
            if (redrawer++ % 10 == 9)
                Applic.app.redraw();
            return;
        }
        final long uittime = timeptr[0];
        handleGlucoseResult(res, uittime);
        if (res != 0L) {
            datatime = uittime;
            disconnect();
        }
    }

    private long datatime = 0L;

    private static void settwobytesplusints(BluetoothGattCharacteristic characteristic, int i, int i2, int... numArr) {
        characteristic.setValue(new byte[2 + numArr.length * 4]);
        characteristic.setValue(i, FORMAT_UINT8, 0);
        characteristic.setValue(i2, FORMAT_UINT8, 1);
        int offset = 2;
        for (int num : numArr) {
            characteristic.setValue(num, FORMAT_UINT32, offset);
            offset += 4;
        }
    }

    @SuppressLint("MissingPermission")
    private void askSensorInfo(BluetoothGatt bluetoothGatt) {
        if (doLog)
            Log.i(LOG_ID, "askSensorInfo");
        charact21.setWriteType(WRITE_TYPE_NO_RESPONSE);
        charact21.setValue(new byte[2]);
        charact21.setValue(194, FORMAT_UINT8, 0);
        charact21.setValue(1, FORMAT_UINT8, 1);
        receiveNotes = false;
        bluetoothGatt.writeCharacteristic(charact21);
    }

    @SuppressLint("MissingPermission")
    private void setAppInfo(BluetoothGatt bluetoothGatt) {
        if (doLog)
            Log.i(LOG_ID, "setApplicationInformation userID: 0");
        charact21.setWriteType(WRITE_TYPE_NO_RESPONSE);
        settwobytesplusints(charact21, 192, 2, 0);
        receiveNotes = false;
        bluetoothGatt.writeCharacteristic(charact21);
    }

    @SuppressLint("MissingPermission")
    private void sendSyncTime(BluetoothGatt bluetoothGatt) {
        charact21.setWriteType(WRITE_TYPE_NO_RESPONSE);
        long msecs = System.currentTimeMillis();
        long secs = msecs / 1000;
        if (doLog)
            Log.i(LOG_ID, "sendSyncTime: " + timestring(msecs) + " Seconds: " + secs);
        charact21.setValue(new byte[]{-61, 2, (byte) ((int) (secs & 0xFF)), (byte) ((int) ((secs >> 8) & 0xFF)), (byte) ((int) ((secs >> 16) & 0xFF)), (byte) ((int) (0xFF & (secs >> 24)))});
        bluetoothGatt.writeCharacteristic(charact21);
        receiveNotes = false;
    }

    @SuppressLint("MissingPermission")
    private void requestData(BluetoothGatt bluetoothGatt) {
        final int lastval;
        synchronized (nativeLock) {
            if (dataptr == 0L)
                return;
            lastval = Natives.airGetLast(dataptr);
        }
        if (lastval < 0) {
            disconnect();
            return;
        }
        if (doLog)
            Log.i(LOG_ID, "requestData last received: " + lastval);
        charact11.setWriteType(WRITE_TYPE_NO_RESPONSE);
        settwobytesplusints(charact11, 196, 1, lastval);
        receiveNotes = false;
        bluetoothGatt.writeCharacteristic(charact11);
    }

    // Sensor information arrives in several 0xC2 packets and ends with a CRC over all of it.
    private static final int SENSOR_INFO_CRC_BUFFER = 726;
    private ByteBuffer forCRCbuf = null;

    private void onChar21Changed(BluetoothGattCharacteristic characteristic, BluetoothGatt bluetoothGatt, byte[] value) {
        if (value.length < 2)
            return;
        final int firstByte = value[0] & 0xFF;
        final int secondByte = value[1] & 0xFF;
        if (firstByte == 192 && secondByte == 1) {
            if (value.length < 13) {
                Log.e(LOG_ID, "onChar21Changed applicationInfo too short: " + value.length);
                return;
            }
            long secs = System.currentTimeMillis() / 1000;
            long deviceTimeSecs = characteristic.getIntValue(FORMAT_UINT32, 2) & 0xFFFFFFFFL;
            int userID = characteristic.getIntValue(FORMAT_UINT32, 6);
            int dataCountperSet = characteristic.getIntValue(FORMAT_UINT8, 10);
            int AdcInterval = characteristic.getIntValue(FORMAT_UINT16, 11);
            syncTime = Math.abs(secs - deviceTimeSecs) >= AdcInterval / 5000;
            if (syncTime && doLog)
                Log.i(LOG_ID, "onChar21Changed timeSync: currentTime:" + secs + " deviceTimeSeconds:" + deviceTimeSecs);
            final int charactOff = 30;
            int QcResultFlag;
            int KeyCheckResult;
            if (swRevision.compareTo("1.5") >= 0 && value.length >= charactOff + 2) {
                QcResultFlag = value[charactOff] & 0xFF;
                KeyCheckResult = value[charactOff + 1] & 0xFF;
            } else {
                QcResultFlag = 0;
                KeyCheckResult = 1;
            }
            if (QcResultFlag != 0 && QcResultFlag != 16) {
                Log.e(LOG_ID, "ERROR QcResultFlag = " + QcResultFlag);
            }
            if (KeyCheckResult == 0) {
                handshake = "KeyCheckResult = 0";
                wrotepass[1] = System.currentTimeMillis();
                Log.e(LOG_ID, handshake);
                unbond();
                return;
            }
            if (doLog)
                Log.i(LOG_ID, "getApplicationInfo deviceTime: " + timestring(deviceTimeSecs * 1000L) + ", UserID: " + userID + ", DataCountPerSet: " + dataCountperSet + ", AdcInterval: " + AdcInterval);
            final int last;
            synchronized (nativeLock) {
                if (dataptr == 0L)
                    return;
                last = Natives.airGetLast(dataptr);
            }
            if (last <= 0) {
                setAppInfo(bluetoothGatt);
            } else if (syncTime) {
                sendSyncTime(bluetoothGatt);
            } else {
                requestData(bluetoothGatt);
            }
            return;
        }
        if (firstByte == 192 && secondByte == 2) {
            if (value.length < 14) {
                Log.e(LOG_ID, "onChar21Changed startSensor too short: " + value.length);
                return;
            }
            float eapp, vref;
            if (swRevision.compareTo("1.3") >= 0) {
                ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
                eapp = buffer.getFloat(2);
                vref = buffer.getFloat(6);
            } else {
                eapp = characteristic.getIntValue(FORMAT_UINT32, 2) / 1.0E7f;
                vref = characteristic.getIntValue(FORMAT_UINT32, 6) / 1.0E7f;
            }
            int elapsedSecs = characteristic.getIntValue(FORMAT_UINT32, 10);
            synchronized (nativeLock) {
                if (dataptr == 0L)
                    return;
                Natives.airSaveStartSensor(dataptr, eapp, vref, elapsedSecs);
            }
            askSensorInfo(bluetoothGatt);
            return;
        }

        if (firstByte == 0xC2 && secondByte == 1) {
            final boolean saved;
            synchronized (nativeLock) {
                if (dataptr == 0L)
                    return;
                saved = Natives.airSaveSensorInfo(dataptr, value);
            }
            if (!saved) {
                disconnect();
                return;
            }
            forCRCbuf = ByteBuffer.allocate(SENSOR_INFO_CRC_BUFFER).order(ByteOrder.BIG_ENDIAN);
            appendForCrc(value);
            return;
        }
        if (firstByte == 0xC2 && secondByte == 2) {
            appendForCrc(value);
            synchronized (nativeLock) {
                if (dataptr == 0L)
                    return;
                Natives.airSaveSensorInfo2(dataptr, value);
            }
            return;
        }
        if (firstByte == 0xC2 && secondByte == 3) {
            if (doLog)
                Log.i(LOG_ID, "onChar21Changed 0xC2 0x03: crc");
            final ByteBuffer buffer = forCRCbuf;
            if (buffer == null) {
                Log.e(LOG_ID, "crc without sensor information");
                disconnect();
                return;
            }
            final byte[] array = buffer.array();
            final int length = array.length - 2;
            final int expected = buffer.getShort(length) & 0xFFFF;
            if (crcver1(array, length) != expected) {
                if (crcver2(array, length) != expected) {
                    handshake = "crc != qcCrc DISCONNECT";
                    wrotepass[1] = System.currentTimeMillis();
                    Log.e(LOG_ID, handshake);
                    disconnect();
                    return;
                }
            } else if (doLog) {
                Log.i(LOG_ID, "crc SUCCESS");
            }
            if (syncTime) {
                sendSyncTime(bluetoothGatt);
            } else {
                requestData(bluetoothGatt);
            }
            return;
        }
        if (firstByte == 195 && secondByte == 2) {
            if (doLog)
                Log.i(LOG_ID, "setTimeSynchronization");
            requestData(bluetoothGatt);
            return;
        }
        if (firstByte == 204 && secondByte == 2) {
            handshake = "Sensor Ended " + sensorSerial;
            wrotepass[1] = System.currentTimeMillis();
            Log.i(LOG_ID, handshake);
            disconnect();
            unbond();
            return;
        }
        if (firstByte == 198 && secondByte == 1) {
            if (doLog)
                Log.i(LOG_ID, "setTimeSynchronization 0xC6");
            return;
        }
        if (firstByte == 0xC6 && secondByte == 2) {
            if (doLog)
                Log.i(LOG_ID, "setAppInfo 0xC6");
            return;
        }
        if (firstByte == 205 && secondByte == 2) {
            Log.i(LOG_ID, "setTransmitterReset");
            unbond();
            disconnect();
        }
    }

    private void appendForCrc(byte[] value) {
        final ByteBuffer buffer = forCRCbuf;
        if (buffer == null)
            return;
        final int length = Math.min(value.length - 2, buffer.remaining());
        if (length > 0)
            buffer.put(value, 2, length);
    }

    @Override
    public void onCharacteristicChanged(@NonNull BluetoothGatt bluetoothGatt, @NonNull BluetoothGattCharacteristic bluetoothGattCharacteristic, @NonNull byte[] value) {
        if (!isCurrentGatt(bluetoothGatt))
            return;
        final String uuidstr = bluetoothGattCharacteristic.getUuid().toString();
        if (doLog)
            Log.showbytes(LOG_ID + " receiveNotes=" + receiveNotes + " onCharacteristicChanged UUID: " + uuidstr, value);
        if (!receiveNotes) {
            return;
        }
        switch (uuidstr) {
            case UUIDchar11: onChar11Changed(bluetoothGatt, value); return;
            case UUIDchar21: onChar21Changed(bluetoothGattCharacteristic, bluetoothGatt, value); return;
            case UUIDchar22: onChar22Changed(bluetoothGatt, value); return;
        }
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        onCharacteristicChanged(bluetoothGatt, bluetoothGattCharacteristic, bluetoothGattCharacteristic.getValue());
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onMtuChanged(BluetoothGatt bluetoothGatt, int mtu, int status) {
        if (!isCurrentGatt(bluetoothGatt))
            return;
        if (status == GATT_SUCCESS) {
            if (doLog)
                Log.i(LOG_ID, "onMtuChanged " + mtu + " SUCCESS");
            bluetoothGatt.discoverServices();
        } else {
            Log.e(LOG_ID, "onMtuChanged failed status=" + status);
            disconnect();
        }
    }

    /** Advertised as "CSAir " followed by the last four characters of the serial. */
    @Override
    public boolean matchDeviceName(String nameDevice, String address) {
        final String start = "CSAir ";
        return nameDevice != null && SerialNumber.length() >= 4 && nameDevice.startsWith(start)
                && nameDevice.regionMatches(start.length(), SerialNumber, SerialNumber.length() - 4, 4);
    }

    static private final UUID ScanServiceUUID = UUID.fromString("c4de9a20-5a9d-11e9-8647-d663bd873d93");

    @Override
    public UUID getService() {
        return ScanServiceUUID;
    }

    @SuppressLint("MissingPermission")
    @Override
    public synchronized boolean pairingRequest() {
        if (stop || dataptr == 0L)
            return false;
        final BluetoothDevice device = mActiveBluetoothDevice;
        if (device == null) {
            Log.e(LOG_ID, "pairingRequest mActiveBluetoothDevice==null");
            return false;
        }
        final byte[] pin = Natives.airGetPin(dataptr);
        if (pin == null) {
            return false;
        }
        Log.i(LOG_ID, "pairingRequest setPin");
        return device.setPin(pin);
    }

    @Override
    public synchronized void bonded() {
        wrotepass[0] = System.currentTimeMillis();
        final var gatt = mBluetoothGatt;
        if (isCurrentGatt(gatt) && charact11 != null)
            enableNotification(gatt, charact11);
    }

    private void unbond() {
        var device = mActiveBluetoothDevice;
        if (device == null) {
            var bluetoothGatt = mBluetoothGatt;
            if (bluetoothGatt != null)
                device = bluetoothGatt.getDevice();
            if (device == null) {
                Log.e(LOG_ID, "unbond device==null");
                return;
            }
        }
        try {
            Method method = device.getClass().getMethod("removeBond", (Class[]) null);
            if ((boolean) method.invoke(device, (Object[]) null)) {
                if (doLog) {Log.i(LOG_ID, "Removed bond");}
            }
        } catch (Throwable e) {
            Log.stack(LOG_ID, "ERROR: could not remove bond", e);
        }
    }

    private static int crcver1(byte[] bArr, int len) {
        int total = 65535;
        for (int index = 0; index < len; ++index) {
            byte b2 = bArr[index];
            int i2 = (((total << 8) | (total >>> 8)) & 65535) ^ (b2 & 255);
            int i10 = i2 ^ ((i2 & 255) >> 4);
            int i11 = i10 ^ ((i10 << 12) & 65535);
            total = i11 ^ (((i11 & 255) << 5) & 65535);
        }
        return total & 65535;
    }

    private static int crcver2(byte[] bArr, int len) {
        int total = 65535;
        for (int index = 0; index < len; ++index) {
            byte b2 = bArr[index];
            total ^= (b2 << 8) & 65535;
            for (int i2 = 0; i2 < 8; i2++) {
                int i10 = 32768 & total;
                int i11 = total << 1;
                if (i10 != 0) {
                    i11 ^= 4129;
                }
                total = i11 & 65535;
            }
        }
        return total;
    }

    @Override
    synchronized void free() {
        cancelalarm();
        unbond();
        // Waits out a native call already in progress; later ones see dataptr == 0.
        synchronized (nativeLock) {
            super.free();
        }
    }
}
