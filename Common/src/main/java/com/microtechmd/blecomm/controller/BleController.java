package com.microtechmd.blecomm.controller;

import com.microtechmd.blecomm.BleAdapter;

/* loaded from: classes2.dex */
public abstract class BleController {
    private long messageCallbackPtr;
    protected long ptr;

    public interface DiscoveredCallback {
        void onDiscovered(BleControllerInfo bleControllerInfo);
    }

    public interface MessageCallback {
        void onReceive(int i, boolean z, byte[] bArr);
    }

    public static native void setBleAdapter(BleAdapter bleAdapter);

    public static native void setDiscoveredCallback(DiscoveredCallback discoveredCallback);

    public static native void startScan();

    public static native void stopScan();

    public native void disconnect();

    public native byte[] getHostAddress();

    public native byte[] getId();

    public native byte[] getKey();

    public native String getMac();

    public native String getName();

    public native int getRssi();

    public native String getSn();

    public native int pair();

    public native void register();

    public native void setHostAddress(byte[] bArr);

    public native void setId(byte[] bArr);

    public native void setKey(byte[] bArr);

    public native void setMac(String str);

    public native void setMessageCallback(MessageCallback messageCallback);

    public native void setName(String str);

    public native void setRssi(int i);

    public native void setSn(String str);

    public native int unpair();

    public native void unregister();
}
