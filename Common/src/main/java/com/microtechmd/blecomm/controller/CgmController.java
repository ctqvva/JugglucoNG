package com.microtechmd.blecomm.controller;

import com.microtechmd.blecomm.BlecommLoader;
import com.microtechmd.blecomm.entity.NewSensorEntity;

/* loaded from: classes2.dex */
public class CgmController extends BleControllerProxy {
    private native void constructor();

    private native void destructor();

    public native int calibration(float f, long j);

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void clearPair() {
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public int getCalTemp() {
        return 0;
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void getDefaultParam() {
    }

    public native int getDefaultParamData();

    public native int getDeviceInfo();

    public native int getFullHistories(int i);

    public native int getHistories(int i);

    public native float getHyper();

    public native float getHypo();

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public int getStatus() {
        return 0;
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void getTransInfo() {
    }

    public native void initialSettings(float f, float f2);

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public boolean isInitialized() {
        return false;
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public boolean isNativePaired() {
        return false;
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public boolean isProductionExpire() {
        return false;
    }

    public native int newSensor(boolean z, long j);

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void newSensor(NewSensorEntity newSensorEntity) {
    }

    public native int recordBg(float f, long j);

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void setAutoUpdate() {
    }

    public native int setDatetime(long j);

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void setDefaultParam(float[] fArr) {
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void setDefaultParamByteArray(byte[] bArr) {
    }

    public native int setDefaultParamData(float[] fArr);

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void setDynamicMode(int i) {
    }

    public native int setHyper(float f);

    public native int setHypo(float f);

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void startTime() {
    }

    static {
        BlecommLoader.ensureLoaded();
    }

    public CgmController() {
        constructor();
    }

    protected void finalize() throws Throwable {
        destructor();
        super.finalize();
    }
}
