package com.microtechmd.blecomm;

import android.bluetooth.BluetoothDevice;
import java.util.concurrent.ConcurrentHashMap;

/* loaded from: classes2.dex */
public class BluetoothDeviceStore {
    private final ConcurrentHashMap<String, BluetoothDevice> mDeviceMap = new ConcurrentHashMap<>();

    public void add(BluetoothDevice bluetoothDevice) {
        if (bluetoothDevice == null || this.mDeviceMap.containsKey(bluetoothDevice.getAddress())) {
            return;
        }
        this.mDeviceMap.put(bluetoothDevice.getAddress(), bluetoothDevice);
    }

    public void remove(BluetoothDevice bluetoothDevice) {
        if (bluetoothDevice == null) {
            return;
        }
        this.mDeviceMap.remove(bluetoothDevice.getAddress());
    }

    public void clear() {
        this.mDeviceMap.clear();
    }

    public ConcurrentHashMap<String, BluetoothDevice> getDeviceMap() {
        return this.mDeviceMap;
    }

    public String toString() {
        return "BluetoothLeDeviceStore{mDeviceMap=" + this.mDeviceMap + '}';
    }
}
