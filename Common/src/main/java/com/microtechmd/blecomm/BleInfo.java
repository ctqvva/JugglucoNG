package com.microtechmd.blecomm;

import android.bluetooth.BluetoothDevice;
import com.microtechmd.blecomm.controller.BleControllerInfo;

/* loaded from: classes2.dex */
public class BleInfo {
    public BleControllerInfo bleControllerInfo;
    public BluetoothDevice device;

    public BleInfo(BleControllerInfo bleControllerInfo, BluetoothDevice bluetoothDevice) {
        this.bleControllerInfo = bleControllerInfo;
        this.device = bluetoothDevice;
    }

    public BleControllerInfo getBleControllerInfo() {
        return this.bleControllerInfo;
    }

    public void setBleControllerInfo(BleControllerInfo bleControllerInfo) {
        this.bleControllerInfo = bleControllerInfo;
    }

    public BluetoothDevice getDevice() {
        return this.device;
    }

    public void setDevice(BluetoothDevice bluetoothDevice) {
        this.device = bluetoothDevice;
    }
}
