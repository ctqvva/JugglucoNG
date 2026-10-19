package com.microtechmd.blecomm.controller;

/* loaded from: classes2.dex */
public class BgmController extends BleController {
    private native void constructor();

    private native void destructor();

    public native int getDeviceInfo();

    public native int getHistory(int i);

    public BgmController() {
        constructor();
    }

    protected void finalize() throws Throwable {
        destructor();
        super.finalize();
    }
}
