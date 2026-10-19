package com.microtechmd.blecomm.controller;

import com.microtechmd.blecomm.entity.NewSensorEntity;

/* loaded from: classes2.dex */
public abstract class BleControllerProxy extends BleController {
    public abstract void clearPair();

    public abstract int getCalTemp();

    public abstract void getDefaultParam();

    public abstract int getStatus();

    public abstract void getTransInfo();

    public abstract boolean isInitialized();

    public abstract boolean isNativePaired();

    public abstract boolean isProductionExpire();

    public abstract void newSensor(NewSensorEntity newSensorEntity);

    public abstract void setAutoUpdate();

    public abstract void setDefaultParam(float[] fArr);

    public abstract void setDefaultParamByteArray(byte[] bArr);

    public abstract void setDynamicMode(int i);

    public abstract void startTime();
}
