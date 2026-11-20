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
/*      Fri Jan 27 15:31:05 CET 2023                                                 */


package tk.glucodata;

import android.annotation.SuppressLint;
import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import androidx.annotation.Keep;
import androidx.annotation.RequiresApi;

import static android.bluetooth.BluetoothDevice.PHY_LE_1M;
import static android.bluetooth.BluetoothDevice.PHY_LE_2M;
import static android.bluetooth.BluetoothDevice.PHY_LE_CODED;
import static android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_BALANCED;
import static android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.content.Context.POWER_SERVICE;
import static java.util.Objects.nonNull;
import static tk.glucodata.Applic.app;
import static tk.glucodata.Applic.isWearable;
//import static tk.glucodata.DexGattCallback.setalarm;
import tk.glucodata.drivers.libre2.Libre2Gen2BleLogin;
import tk.glucodata.drivers.libre2.Libre2ManagedDriver;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.LossOfSensorAlarm.cancelalarm;
import static tk.glucodata.Natives.getstreamingAuthenticationData;
import static tk.glucodata.Natives.hasalarmloss;
import static tk.glucodata.Natives.processTooth;


public class Libre2GattCallback extends SuperGattCallback {
	private int conphase = 0;
	private BluetoothGatt disconnectingGatt;
	private long reconnectDelayMillis;
	private final GattConnectDeadline<BluetoothGatt> disconnectDeadline = new GattConnectDeadline<>(
			this, (task, delay) -> {
				var future = Applic.scheduler.schedule(task, delay, TimeUnit.MILLISECONDS);
				return () -> future.cancel(false);
			}, this::retryAfterDisconnect);
	// The sensor sends a complete packet every minute. A link that stays CONNECTED without one
	// gets the same fresh-GATT recovery as a reported disconnect, instead of waiting for the
	// loss-of-signal alarm. Fragments, RSSI and handshake callbacks do not count.
	// The scheduler does not run in deep sleep; that alarm remains the backstop there.
	private static final long STREAM_SILENCE_MILLIS = 150_000L;
	private final GattConnectDeadline<BluetoothGatt> streamDeadline = new GattConnectDeadline<>(
			this, (task, delay) -> {
				var future = Applic.scheduler.schedule(task, delay, TimeUnit.MILLISECONDS);
				return () -> future.cancel(false);
			}, this::streamSilent);
	private long linkConnectedAt = -1L, lastPacketAt = -1L;
	private int linkPackets;

	private void streamPacketReceived() {
		lastPacketAt = SystemClock.elapsedRealtime();
		++linkPackets;
		streamDeadline.arm(mBluetoothGatt, STREAM_SILENCE_MILLIS);
		clearLossOfSignalState();
	}

	private synchronized void streamSilent(BluetoothGatt gatt) {
		if (gatt != mBluetoothGatt || disconnectingGatt != null || stop) return;
		Log.i(LOG_ID, SerialNumber + " no complete packet for " + STREAM_SILENCE_MILLIS / 1000 + "s, reconnecting");
		noteLossOfSignal(System.currentTimeMillis());
		requestDisconnect(gatt, 0L);
	}

	// One line per link, also in the trace log, so it shows how long links last and whether the stream went quiet first.
	private void logLinkEnd(String why) {
		if (linkConnectedAt < 0L) return;
		final long now = SystemClock.elapsedRealtime();
		Log.i(LOG_ID, SerialNumber + " link ended (" + why + ") after " + (now - linkConnectedAt) / 1000
				+ "s, packets=" + linkPackets + ", last packet "
				+ (lastPacketAt < 0L ? "never" : (now - lastPacketAt) / 1000 + "s ago"));
		linkConnectedAt = -1L;
	}

	@Override
	protected boolean useAutoConnect() {
		return false;
	}

	@Override
	protected long connectionAttemptTimeoutMillis() {
		// Recovery policy for a connectGatt attempt that never reports a result.
		return 120_000L;
	}

	private boolean acceptGattCallback(BluetoothGatt gatt) {
		return gatt != null && gatt == mBluetoothGatt && disconnectingGatt == null && !stop;
	}

	private void cancelDisconnectDeadline() {
		disconnectDeadline.cancel();
		disconnectingGatt = null;
		reconnectDelayMillis = 0L;
	}

	private void resetConnectionState() {
		endBLEHandler();
		streamDeadline.cancel();
		connected = false;
		conphase = 0;
		pack1 = pack2 = false;
		BLELogincharacteristic = null;
		CompositeRawDatacharacteristic = null;
		characteristic = null;
	}

	private synchronized void retryAfterDisconnect(BluetoothGatt gatt) {
		if (gatt != mBluetoothGatt) return;
		final long delay = reconnectDelayMillis;
		cancelDisconnectDeadline();
		resetConnectionState();
		logLinkEnd("closed");
		// Retire the reference before close(), so late callbacks cannot affect its replacement.
		closeGattTransport();
		connectDevice(delay);
	}

	private synchronized void requestDisconnect(BluetoothGatt gatt, long delayMillis) {
		if (gatt == null || gatt != mBluetoothGatt) return;
		// Retain the latest reconnect delay without restarting an in-progress disconnect.
		reconnectDelayMillis = delayMillis;
		if (disconnectingGatt == gatt) return;
		disconnectingGatt = gatt;
		endBLEHandler();
		streamDeadline.cancel();
		// Android can omit DISCONNECTED. Repeated recovery requests must not extend this wait.
		disconnectDeadline.arm(gatt, 2_000L);
		try {
			super.disconnect();
		} catch (Throwable e) {
			Log.stack(LOG_ID, SerialNumber + " disconnect", e);
			retryAfterDisconnect(gatt);
		}
	}

	@Override
	public synchronized void disconnect() {
		if (mBluetoothGatt == null) super.disconnect();
		else requestDisconnect(mBluetoothGatt, 0L);
	}

	@Override
	public synchronized boolean connectDevice(long delayMillis) {
		if (stop || dataptr == 0L || CloneSensorRegistry.isCloneSensor(SerialNumber)
				|| SensorOwnershipRuntime.blocksLocalConnection(SerialNumber)) return false;
		if (mBluetoothGatt != null) {
			requestDisconnect(mBluetoothGatt, delayMillis);
			return true;
		}
		cancelDisconnectDeadline();
		resetConnectionState();
		return super.connectDevice(delayMillis);
	}

	@Override
	public synchronized boolean reconnect(long now) {
		final long old = now - showtime + 20;
		// Loss alarms call reconnect directly and may already be queued when a new
		// reading rearms the alarm. Protect a healthy link, but let ownership nudge
		// an idle callback even when its previous session's glucose is still recent.
		if (hasLocallyConnectedGatt() && charcha[0] >= old) return true;
		if (charcha[1] < old && connectTime < now - 60_000L) {
			noteLossOfSignal(now);
			return connectDevice(0L);
		}
		return true;
	}

	@Override
	public synchronized void setPause(boolean pause) {
		if (pause) {
			cancelDisconnectDeadline();
			endBLEHandler();
			streamDeadline.cancel();
			logLinkEnd("paused");
		}
		super.setPause(pause);
	}

       static private final UUID mADCCustomServiceUUID = UUID.fromString("0000fde3-0000-1000-8000-00805f9b34fb");
	static private final String LOG_ID = "Libre2GattCallback";

	public Libre2GattCallback(String SerialNumber, long dataptr) {
		super(SerialNumber,dataptr,Natives.getsensorgen(dataptr));
		{if(doLog) {Log.d(LOG_ID, "Libre2GattCallback(..)");};};
	}


	boolean pack1 = false, pack2 = false;
	final byte[] packet = new byte[46];


	@SuppressLint("MissingPermission")
	final void writeBLELogin() {
		conphase = 2;
		byte[] bArr = new byte[1];
		bArr[0] = 32;
		try {
			{if(doLog) {Log.i(LOG_ID, "Sent read security challenge");};};
			if(!BLELogincharacteristic.setValue(bArr)) {
				var mess="BLELogincharacteristic.setValue";
				wrotepass[1] = System.currentTimeMillis();
				handshake = mess;
				Log.e(LOG_ID,SerialNumber+" "+mess);
				var gatt= mBluetoothGatt;
				if(gatt!=null)
					requestDisconnect(gatt, 0L);
				return;
				}
			if(!mBluetoothGatt.writeCharacteristic(BLELogincharacteristic)) {
				var mess="writeCharacteristic(BLELogincharacteristic)";
				Log.e(LOG_ID,SerialNumber+" "+mess);
				handshake = mess;
				wrotepass[1] = System.currentTimeMillis();
				var gatt= mBluetoothGatt;
				if(gatt!=null)
					requestDisconnect(gatt, 0L);
				}
		} catch(Throwable e) {
			Log.stack(LOG_ID, SerialNumber+" onDescriptorWrite", e);
			if (Build.VERSION.SDK_INT > 30 && !Applic.mayscan())
				Applic.Toaster(R.string.turn_on_nearby_devices_permission);
			var gatt= mBluetoothGatt;
			if(gatt!=null)
				requestDisconnect(gatt, 0L);

		}
	}
static void showCharacter(String label, BluetoothGattCharacteristic characteristic) {
	byte[] value=characteristic.getValue();
        {if(doLog){Log.showbytes(label + " UUID: " + characteristic.getUuid().toString(), value);};}
    }
@Override // android.bluetooth.BluetoothGattCallback
	public synchronized void onDescriptorWrite(BluetoothGatt bluetoothGatt, BluetoothGattDescriptor bluetoothGattDescriptor, int status) {
		if (!acceptGattCallback(bluetoothGatt)) return;
		super.onDescriptorWrite(bluetoothGatt, bluetoothGattDescriptor, status);
		if(doLog)  {
		   BluetoothGattCharacteristic characteristic = bluetoothGattDescriptor.getCharacteristic();
			showCharacter(SerialNumber+" onDescriptorWrite status="+status, characteristic);
          }
      if(status!=GATT_SUCCESS ) {
				final var ch = bluetoothGattDescriptor == null ? null : bluetoothGattDescriptor.getCharacteristic();
				Log.e(LOG_ID, SerialNumber + " descriptor write failed, status:" + status
						+ " (" + gattStatusText(status) + ")" + (ch == null ? "" : " UUID:" + ch.getUuid()));
				setConStatus(status, false);
				requestDisconnect(bluetoothGatt, 0L);
            return;
         }
         
		if (sensorgen == 2) {
			if (conphase == 1)
				writeBLELogin();
			else
				runPendingBleStep();
		}
	}
private boolean connected=false;
	private int consecutiveConnectFailures = 0;
	@SuppressLint("MissingPermission")
	@Override
	public synchronized void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
		if (bluetoothGatt == null || bluetoothGatt != mBluetoothGatt) return;
		if (stop) {
			close();
			return;
		}
		if (disconnectingGatt != null && newState != BluetoothProfile.STATE_DISCONNECTED) return;
		if (!acceptConnectionAttemptCallback(bluetoothGatt, newState)) return;
		// Some stacks repeat CONNECTED for the same GATT. Starting discovery again
		// duplicates the login and can reset a security challenge already in flight.
		if (newState == BluetoothProfile.STATE_CONNECTED && status == GATT_SUCCESS && connected) {
			if (doLog) Log.i(LOG_ID, SerialNumber + " ignoring duplicate CONNECTED");
			return;
		}
		noteFirstGattCallback("onConnectionStateChange", bluetoothGatt);
		super.onConnectionStateChange(bluetoothGatt, status, newState);
		endBLEHandler();
		long tim = System.currentTimeMillis();
		try {
			final boolean attemptFailed = !(newState == BluetoothProfile.STATE_CONNECTED && status == GATT_SUCCESS);
			if (attemptFailed) {
				++consecutiveConnectFailures;
			} else {
				consecutiveConnectFailures = 0;
			}
			if (doLog) {
				final String[] states = {"DISCONNECTED", "CONNECTING", "CONNECTED", "DISCONNECTING"};
				Log.i(LOG_ID, SerialNumber + " onConnectionStateChange, status:" + status + " ("
						+ gattStatusText(status) + "), state: "
						+ (newState >= 0 && newState < states.length ? states[newState] : newState)
						+ (attemptFailed ? ", failed attempt " + consecutiveConnectFailures : ""));
			}
			if (!attemptFailed) {
				connected = true;
				// A refused connect attempt earlier in this recovery run is over now; keeping
				// its "Status=N" on the diagnostic status makes a working sensor look broken.
				setConStatus(GATT_SUCCESS);
				linkConnectedAt = SystemClock.elapsedRealtime();
				lastPacketAt = -1L;
				linkPackets = 0;
				// Also bounds a handshake that stalls after CONNECTED.
				streamDeadline.arm(bluetoothGatt, STREAM_SILENCE_MILLIS);
				constatchange[0] = tim;
				setpriority(bluetoothGatt);
				if (!bluetoothGatt.discoverServices()) {
					Log.e(LOG_ID, SerialNumber + " discoverServices() returned false");
					requestDisconnect(bluetoothGatt, 0L);
				}
			} else {
				connected = false;
				// A direct connect can fail with 147 and succeed on the next attempt: that is
				// retry chatter, not a sensor error. A link that was up and then dropped with a
				// timeout (8), a peer termination (19) or a GATT error (133) is a real outage and
				// stays in the history. Once "Loss of signal" is recorded, keep that label instead
				// of replacing it with the bare transport code.
				final boolean linkWasUp = linkConnectedAt >= 0L;
				final boolean intentionalLocalDisconnect = disconnectingGatt != null;
				if (!"Loss of signal".equals(constatstatusstr)) {
					if (status == GATT_SUCCESS) {
						// Android 12 can report a remote drop as status 0. It is a real outage
						// when the link was up and we did not ask for the disconnect.
						if (linkWasUp && !intentionalLocalDisconnect) {
							recordEstablishedLinkDrop(0);
						}
					} else {
						// Status 22 is "local host terminated": our own requested disconnect is
						// expected, but the same code arriving unsolicited after a live link is a
						// real event and belongs in the history.
						final boolean intentionalLocal22 = status == 22 && intentionalLocalDisconnect;
						setConStatus(status, linkWasUp && !intentionalLocal22);
					}
				}
				constatchange[1] = tim;
				if (newState == BluetoothProfile.STATE_DISCONNECTED) {
					closeManagedGen2();
					logLinkEnd("status=" + status);
					// Keep the existing Libre 2 notification/authentication recovery policy.
					if (status == 19 && justenablednotification) {
						Natives.resetbluetooth(dataptr);
						justenablednotification = false;
					}
					retryAfterDisconnect(bluetoothGatt);
				} else if (status != GATT_SUCCESS) {
					requestDisconnect(bluetoothGatt, 0L);
				}
			}
		} catch (Throwable e) {
			Log.stack(LOG_ID, SerialNumber + " onConnectionStateChange", e);
			if (Build.VERSION.SDK_INT > 30 && !Applic.mayscan())
				Applic.Toaster(R.string.turn_on_nearby_devices_permission);
			setConStatus(status);
			constatchange[1] = tim;
			requestDisconnect(bluetoothGatt, 0L);
		}
	}

	BluetoothGattCharacteristic BLELogincharacteristic;
	BluetoothGattCharacteristic CompositeRawDatacharacteristic;


	private  boolean m2831x() {
		try {
			var sensorbluetooth=SensorBluetooth.blueone;
			if(sensorbluetooth==null)
				return false;
			BluetoothGattService service = mBluetoothGatt.getService(mADCCustomServiceUUID);
			if (service != null) {
				BLELogincharacteristic = service.getCharacteristic(mCharacteristicUUID_BLELogin);
				CompositeRawDatacharacteristic = service.getCharacteristic(mCharacteristicUUID_CompositeRawData);
				if (BLELogincharacteristic != null && CompositeRawDatacharacteristic != null) {
					if (sensorgen == 2) {
						{if(doLog) {Log.i(LOG_ID, "Using security generation 2");};};
						conphase = 1;
						boolean isEnabled = asknotification(BLELogincharacteristic);
						{if(doLog) {Log.i(LOG_ID, "Enabled Security notification: " + isEnabled);};};
						return true;
					}
					{if(doLog) {Log.i(LOG_ID, "Using security generation 1");};};

					long tim = System.currentTimeMillis();
					byte[] key = Natives.sensorUnlockKey(dataptr);
					if (key != null) {
						conphase = 5;
						if(BLELogincharacteristic.setValue(key)) {
							if(mBluetoothGatt.writeCharacteristic(BLELogincharacteristic)) {
								if(doLog) {
									{if(doLog) {Log.v(LOG_ID, SerialNumber + " writeCharacteristic passcode");};};
									}
								return true;
								}
							else {
								Log.e(LOG_ID,SerialNumber + " writeCharacteristic failed passcode");
								wrotepass[1] = tim;
								handshake="writeCharacteristic";
								return false;
								}

							}
						else {
							wrotepass[1] = tim;
							handshake="setValue";
						Log.e(LOG_ID,SerialNumber + " setValue failed: passcode");
							return false;
							}
						} 
					else {
						wrotepass[1] = tim;
						handshake ="No key";
						Log.e(LOG_ID, SerialNumber + " has no streaming unlock key");
						return false;
					}
				}
				else {
					Log.e(LOG_ID, SerialNumber + " "
							+ (BLELogincharacteristic == null ? "BLELogincharacteristic==null" : "")
							+ (CompositeRawDatacharacteristic == null ? " CompositeRawDatacharacteristic==null" : ""));
					return false;

					}
			}
			else {
				Log.e(LOG_ID, SerialNumber + " getService(mADCCustomServiceUUID)==null");
				return false;
				}
		} catch (Throwable e) {
			Log.stack(LOG_ID, "onServicesDiscovered", e);
			if (Build.VERSION.SDK_INT > 30 && !Applic.mayscan())
				Applic.Toaster(R.string.turn_on_nearby_devices_permission);
			return false;
		}
	}


	@SuppressLint("MissingPermission")
    @Override // android.bluetooth.BluetoothGattCallback
	public synchronized void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
		if (!acceptGattCallback(bluetoothGatt)) return;
		// A repeated successful discovery must not move an active login back to phase 1.
		if (status == GATT_SUCCESS && conphase != 0) {
			if (doLog) Log.i(LOG_ID, SerialNumber + " ignoring duplicate service discovery, phase=" + conphase);
			return;
		}
		{if(doLog) {Log.i(LOG_ID, "BLE onServicesDiscovered invoked, status: " + status);};};
//		readrssi=9999; mBluetoothGatt.readRemoteRssi();
		if(status != GATT_SUCCESS||! m2831x()) {
			Log.e(LOG_ID, SerialNumber + " service discovery failed, status:" + status
					+ " (" + gattStatusText(status) + ")"
					+ (status == GATT_SUCCESS ? "; required service, characteristic or key missing" : ""));
			if (status != GATT_SUCCESS) setConStatus(status, false);
			requestDisconnect(mBluetoothGatt, 0L);
			}

	}


	/*
public void onCharacteristicWrite (BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                int status)
Callback indicating the result of a characteristic write operation.

If this callback is invoked while a reliable write transaction is in progress, the value of the characteristic represents the value reported by the remote device. An application should compare this value to the desired value to be written. If the values don't match, the application must abort the reliable write transaction.

Parameters
gatt	BluetoothGatt: GATT client invoked BluetoothGatt#writeCharacteristic
characteristic	BluetoothGattCharacteristic: Characteristic that was written to the associated remote device.
status	int: The result of the write operation BluetoothGatt#GATT_SUCCESS if the operation succeeds.
*/
private	boolean justenablednotification = false;
 private   BluetoothGattCharacteristic characteristic;
private   boolean failedbefore=false;
	@Override
	public synchronized void onCharacteristicWrite(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic, int status) {
		if (!acceptGattCallback(bluetoothGatt)) return;
		{if(doLog) {Log.d(LOG_ID, bluetoothGatt.getDevice().getAddress() + " onCharacteristicWrite, status:" + status + " UUID:" + bluetoothGattCharacteristic.getUuid().toString());};};
		if (status != GATT_SUCCESS) {
			Log.e(LOG_ID, SerialNumber + " characteristic write failed, status:" + status
					+ " (" + gattStatusText(status) + ")"
					+ (bluetoothGattCharacteristic == null ? "" : " UUID:" + bluetoothGattCharacteristic.getUuid()));
		}
		if (sensorgen == 2) {
			runPendingBleStep();
			return;
		}
		try {
			var sensorbluetooth=SensorBluetooth.blueone;
			if(sensorbluetooth==null) {
				{if(doLog) {Log.i(LOG_ID, "onCharacteristicWrite sensorbluetooth==null");};};
				return ;
				}
            BluetoothGattService service;
			BluetoothGattDescriptor descriptor;
			//noinspection MissingPermission
			boolean success = true;
			long tim = System.currentTimeMillis();
			justenablednotification = true;
			if(status == GATT_SUCCESS &&
                (service = bluetoothGatt.getService(mADCCustomServiceUUID)) != null &&
                (characteristic = service.getCharacteristic(mCharacteristicUUID_CompositeRawData)) != null &&
                bluetoothGatt.setCharacteristicNotification(characteristic, true) &&
                (descriptor = characteristic.getDescriptor(mCharacteristicConfigDescriptor)) != null) {
                        if(!descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                            Log.e(LOG_ID,"descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)  failed");
                            }
                        success = bluetoothGatt.writeDescriptor(descriptor);
                        } 
            else success = false;
			if (!success) {
				wrotepass[1] = tim;
				handshake = "Enabling notification failed";
				Log.e(LOG_ID, SerialNumber + " enabling notification failed, write status:" + status
						+ " (" + gattStatusText(status) + ")");
                if(failedbefore) {
                   Natives.resetbluetooth(dataptr);
                   failedbefore=false;
                   }
                else failedbefore=true;
                requestDisconnect(bluetoothGatt, 0L);
                return;
                }

         failedbefore=false;
			conphase = 4;
			wrotepass[0] = tim;
			pack1 = false;
			pack2 = false;
		} catch (Throwable e) {
			Log.stack(LOG_ID, SerialNumber+" onCharacteristicWrite", e);
			if (Build.VERSION.SDK_INT > 30 && !Applic.mayscan())
				Applic.Toaster(R.string.turn_on_nearby_devices_permission);
			requestDisconnect(bluetoothGatt, 0L);
		}
	}











private static PowerManager.WakeLock getwakelock() {
		return ((PowerManager) app.getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Juggluco::processTooth");
		}
	/*
private static PowerManager.WakeLock wakeLock=null; 
private static PowerManager.WakeLock getwakelock() {
		if(wakeLock==null) {
			PowerManager powerManager= (PowerManager) app.getSystemService(POWER_SERVICE);
			wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Juggluco::processTooth");
			}
		return wakeLock;
		}
		*/

private boolean setDescriptor(BluetoothGattCharacteristic ch, byte[] type) {
       var gatt=mBluetoothGatt;
        BluetoothGattDescriptor descriptor = ch.getDescriptor(mCharacteristicConfigDescriptor);
        if(!descriptor.setValue(type)) {
            Log.e(LOG_ID, SerialNumber + " " + "descriptor.setValue())  failed");
            return false;
        }
        return gatt.writeDescriptor(descriptor);
        }
private long datatime=0L;
private	void oldonCharacteristicChanged(byte[] value) {
		justenablednotification = false;
		int length = value.length;
		{if(doLog) {Log.d(LOG_ID, SerialNumber + " oldonCharacteristicChanged " + length);};};
		switch (length) {
			case 20: {
				System.arraycopy(value, 0, packet, 0, 20);
				pack1 = true;
				pack2 = false;
			}
			;
			break;
			case 18: {
				if (pack1) {
					System.arraycopy(value, 0, packet, 20, 18);
					pack2 = true;
				}
			}
			;
			break;
			case 8: {
				if (pack1 && pack2) {
					streamPacketReceived();
					final var wakeLock=Natives.hasRootcheck()?getwakelock():null;
					if(wakeLock!=null) {
						wakeLock.setReferenceCounted(false);
						wakeLock.acquire(Applic.GLUCOSE_WAKELOCK_TIMEOUT_MS);
						}
                        /*
                    if(isWearable) {
                        if(Natives.getDisconnectSensor()) {
                            Log.i(LOG_ID,"enableNotification(mBluetoothGatt, characteristic)");
                            setDescriptor(characteristic,  BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                            Applic.scheduler.schedule(()-> {
                                if(connected) {
                                   setDescriptor(characteristic,  BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                    }
                                    }, 30, TimeUnit.SECONDS);

                            }
                        } */

					try {
						pack1 = false;
						pack2 = false;
						System.arraycopy(value, 0, packet, 38, 8);
						final long timmsec = System.currentTimeMillis();
						final var gen2Login = managedGen2;
						final var newpacket= sensorgen!=2?packet:(gen2Login!=null?gen2Login.decryptFrame(packet):null);
						if(newpacket!=null) {
							long res = processTooth(dataptr, newpacket);
							if(res!=1L) {
								handleGlucoseResult(res,timmsec);
								}
							}
						datatime=timmsec;
						}
					finally {
						Applic.releasewakelock(wakeLock);
						}
                  /*  if(isWearable) {
                        if(Natives.getDisconnectSensor()&&!autoconnect) {
                            disconnect();  
                            }
                        } */
				}
			}
			;
			break;
			default: {
				{if(doLog) {Log.i(LOG_ID, SerialNumber + " onCharacteristicChanged: wrong length=" + value.length);};};
			}
			;
		}
		;
	}

	// Kotlin Gen2 login of the current link.
	private Libre2Gen2BleLogin managedGen2;

	private void closeManagedGen2() {
		final var login = managedGen2;
		managedGen2 = null;
		if (login != null)
			login.close();
	}

	private byte[] authenticateStream(byte[] bArr) {
		closeManagedGen2();
		final var login = Libre2ManagedDriver.gen2BleLogin();
		final byte[] answer = login.answerChallenge(Natives.getsensorident(dataptr), getstreamingAuthenticationData(dataptr), bArr);
		if(answer != null) {
			managedGen2 = login;
			return answer;
			}
		handshake = "Kotlin: " + login.getFailure();
		wrotepass[1] = System.currentTimeMillis();
		Log.e(LOG_ID, SerialNumber + " " + handshake);
		var gatt = mBluetoothGatt;
		if (gatt != null)
			requestDisconnect(gatt, 0L);
		return null;
	}


	//mCharacteristicUUID_BLELogin.toString().equals(bluetoothGattCharacteristic.getUuid().toString())
	public final void phase2(byte[] value) {
		if (value.length != 14) {
			Log.e(LOG_ID, SerialNumber+" phase2 wrong " + value);
			var gatt = mBluetoothGatt;
			if (gatt != null)
				requestDisconnect(gatt, 0L);
			return;
		}
		try {
			byte[] a = authenticateStream(value);
			if(a==null)  {
                		Log.e(LOG_ID, SerialNumber+" phase2 wrong (a == null)");
				var gatt = mBluetoothGatt;
				if (gatt != null)
					requestDisconnect(gatt, 0L);
				return;
				}
//            this.f14472dGb = new byte[25];
		    mBLELoginHandler = forCurrentGatt(() -> {
			BLELogincharacteristic.setValue(a);
            var gatt = mBluetoothGatt;
            if(gatt==null)
                return;
			if(!gatt.writeCharacteristic(BLELogincharacteristic)) {
			    var mess = "phase2 retry=" + BLELoginposted + " writeCharacteristic(BLELogincharacteristic)";
			    Log.e(LOG_ID, SerialNumber+" "+mess);
			    handshake = mess;
			    wrotepass[1] = System.currentTimeMillis();
			    if(BLELoginposted < 5) {
                    ++BLELoginposted;
                    Applic.app.getHandler().postDelayed(mBLELoginHandler, 100);
                    return;
                    }
			  //  var gatt = mBluetoothGatt;
			    if(gatt != null)
                    requestDisconnect(gatt, 0L);
			    return;
                }
			conphase = 3;
			justenablednotification = true;
			BLELoginposted = 0;
			mBLELoginHandler = null;
		    });
		    mBLELoginHandler.run();
		} catch (Exception e) {
			handshake = "streamingUnlock failed";
			Log.e(LOG_ID, SerialNumber+" "+handshake);
			wrotepass[1] = System.currentTimeMillis();
			var gatt = mBluetoothGatt;
			if (gatt != null)
				requestDisconnect(gatt, 0L);
		}
	}

/*
static	public final boolean enableNotification(BluetoothGatt bluetoothGatt,BluetoothGattCharacteristic bluetoothGattCharacteristic) {
	bluetoothGatt.setCharacteristicNotification(bluetoothGattCharacteristic, true);
	BluetoothGattDescriptor descriptor = bluetoothGattCharacteristic.getDescriptor(mCharacteristicConfigDescriptor);
	descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
	return bluetoothGatt.writeDescriptor(descriptor);
	}
private final boolean enableNotification(BluetoothGattCharacteristic bluetoothGattCharacteristic) {
	return enableNotification(mBluetoothGatt, bluetoothGattCharacteristic);
	} */

	private void startRawDataNotifications() {
            mBLELoginHandler = forCurrentGatt(() -> {
                if (!asknotification(CompositeRawDatacharacteristic)) {
                    Log.e(LOG_ID, SerialNumber+" phase3 retry=" + BLELoginposted + " enableNotification failed");
                    handshake = "Enable CompositeRawDatacharacteristic failed";
                    wrotepass[1] = System.currentTimeMillis();
                    if (BLELoginposted < 5) {
                        ++BLELoginposted;
                        Applic.app.getHandler().postDelayed(mBLELoginHandler, 100);
                        return;
                    }
                    conphase = 0;
                    var gatt = mBluetoothGatt;
                    if (gatt != null)
                        requestDisconnect(gatt, 0L);
                    return;
                }
                BLELoginposted = 0;
                mBLELoginHandler = null;
                wrotepass[0] = System.currentTimeMillis();
            });
            mBLELoginHandler.run();
	}

	public final void phase3(byte[] value) {
		final var gen2Login = managedGen2;
		if (gen2Login == null) {
			handshake = "phase3 without login";
			wrotepass[1] = System.currentTimeMillis();
			Log.e(LOG_ID, SerialNumber + " " + handshake);
			var gatt = mBluetoothGatt;
			if (gatt != null)
				requestDisconnect(gatt, 0L);
			return;
		}
		final var reply = gen2Login.onReplyPart(value);
		if (reply == Libre2Gen2BleLogin.Reply.NEED_MORE)
			return;
		if (reply == Libre2Gen2BleLogin.Reply.REJECTED) {
			handshake = "Kotlin: " + gen2Login.getFailure();
			wrotepass[1] = System.currentTimeMillis();
			Log.e(LOG_ID, SerialNumber + " " + handshake);
			closeManagedGen2();
			var gatt = mBluetoothGatt;
			if (gatt != null)
				requestDisconnect(gatt, 0L);
			return;
		}
		{if(doLog) {Log.i(LOG_ID, "Kotlin Gen2 session");};};
		conphase = 4;
		startRawDataNotifications();
	}

@Override // android.bluetooth.BluetoothGattCallback
public synchronized void onCharacteristicChanged(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic) {
		if (!acceptGattCallback(bluetoothGatt)) return;
	byte[] value = bluetoothGattCharacteristic.getValue();
	var uuid=bluetoothGattCharacteristic.getUuid();
	var uuidstr=uuid.toString();
	if(sensorgen != 2) {
		if (!uuid.equals(mCharacteristicUUID_CompositeRawData)) {
			if(doLog) {
				{if(doLog) {Log.i(LOG_ID, SerialNumber + " onCharacteristicChanged: wrong UUID:" + uuidstr);};};
				}
			return;
			}
		oldonCharacteristicChanged(value);
		return;
		}

	if(doLog) {
//      boolean str=uuidstr.equals(mCharacteristicUUID_CompositeRawData.toString());
      final boolean isRawData=uuid.equals(mCharacteristicUUID_CompositeRawData);
      {if(doLog) {Log.i(LOG_ID, "UUID: "+(isRawData?"CompositeRawData, ":"")+ " conphase=" + conphase);};};
		{if(doLog) {Log.i(LOG_ID, "onCharacteristicChanged " + uuidstr);};};
	     }
	if (!mCharacteristicUUID_BLELogin.equals(uuid)) {
		if (uuid.equals(mCharacteristicUUID_CompositeRawData) && conphase == 4) {
			oldonCharacteristicChanged(value);
		}
		}
	else {
		if(conphase == 2) {
			phase2(value);
			}
		else  {
			if(conphase == 3) {
				phase3(value);
				}
			}; 
		}
	}
private int BLELoginposted=0;
private Runnable		mBLELoginHandler=null; 
private long bleLoginGeneration;

// A Handler retry may already be dispatched when removeCallbacks() runs.
private Runnable forCurrentGatt(Runnable task) {
    final BluetoothGatt gatt = mBluetoothGatt;
    final long generation = ++bleLoginGeneration;
    return () -> {
        synchronized (this) {
            if (generation == bleLoginGeneration && acceptGattCallback(gatt)) task.run();
        }
    };
}

// Android runs one GATT operation at a time. The sensor can answer before Android confirms our
// previous write; the next login step is then refused as busy and waits for its 100 ms retry,
// long enough for the sensor to drop the link. Send it as soon as the confirmation arrives.
private void runPendingBleStep() {
    final Runnable pending = mBLELoginHandler;
    if (pending == null || BLELoginposted == 0)
        return;
    Applic.app.getHandler().removeCallbacks(pending);
    pending.run();
}

private void endBLEHandler() {
        ++bleLoginGeneration;
        BLELoginposted = 0;
        if (mBLELoginHandler != null) {
            Applic.app.getHandler().removeCallbacks(mBLELoginHandler);
            mBLELoginHandler = null;
        }
    }
@Override
	public synchronized void close() {
	   cancelDisconnectDeadline();
	   endBLEHandler();
      logLinkEnd("closed");
      final var gatt=mBluetoothGatt;
      if(nonNull(gatt)) {
         disablenotification(gatt,BLELogincharacteristic);
         disablenotification(gatt, CompositeRawDatacharacteristic) ;
         }
		super.close();
		resetConnectionState();
		}
@Override
public synchronized void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status)  {
		if (!acceptGattCallback(gatt)) return;
	{if(doLog) {Log.i(LOG_ID,"onReadRemoteRssi(BluetoothGatt,"+ rssi+","+status+(status==GATT_SUCCESS?" SUCCESS":" FAILURE"));};};
	if(status==GATT_SUCCESS) {
		readrssi=rssi;
		}
	}

@Override
public boolean matchDeviceName(String deviceName,String address) {
	if(deviceName==null||!deviceName.startsWith("ABBOTT")) {
		return false;
		}
	return SerialNumber.equals(deviceName.substring(6));
	}
@Override
void free() {
    super.free();
    cancelalarm();
    }
@Override
public UUID getService() {
   return mADCCustomServiceUUID;
   }
/*@Override
public void setGattOptions(BluetoothGatt gatt) {
	if(Build.VERSION.SDK_INT >= 26 ) {
        if(doLog) {Log.i(LOG_ID,"setGattOptions(BluetoothGatt gatt) setPreferredPhy PHY_LE_2M_MASK");};
        gatt.setPreferredPhy(
         BluetoothDevice.PHY_LE_2M_MASK,
         BluetoothDevice.PHY_LE_2M_MASK,
         BluetoothDevice.PHY_OPTION_NO_PREFERRED); 
         }
        } */

}
