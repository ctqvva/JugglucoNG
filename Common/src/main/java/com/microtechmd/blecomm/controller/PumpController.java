package com.microtechmd.blecomm.controller;

/* loaded from: classes2.dex */
public class PumpController extends BleController {
    private native void constructor();

    private native void destructor();

    public native int clearAddress();

    public native int getBasalProfile();

    public native int getBolusProfile();

    public native int getDeviceInfo();

    public native int getHistory(int i);

    public native int getMode();

    public native int getOcclusion();

    public native int getPumpCapacityStatus();

    public native int getSetting();

    public native int setAddress();

    public native int setAutoMode(boolean z);

    public native int setBasalProfile(float[] fArr);

    public native int setBolusProfile(float f, float f2, float f3, int i);

    public native int setBolusRatio(int i, int i2);

    public native int setCgmSn(String str);

    public native int setDatetime(String str);

    public native int setEventConfirmed(int i, int i2, int i3);

    public native int setGlucose(float f);

    public native int setGlucoseTarget(float f, float f2);

    public native int setIsf(float f);

    public native int setMode(int i);

    public native int setPriming(float f);

    public native int setRewinding(float f);

    public native int setSetting(float[] fArr);

    public native int setTemporaryPercentProfile(int i, int i2);

    public native int setTemporaryProfile(float f, int i);

    public PumpController() {
        constructor();
    }

    protected void finalize() throws Throwable {
        destructor();
        super.finalize();
    }
}
