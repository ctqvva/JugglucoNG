package com.microtechmd.blecomm.parser;

/* loaded from: classes2.dex */
public class PumpParser {
    public static native float[] getBolus(byte[] bArr);

    public static native <T extends PumpBroadcastEntity> T getBroadcast(byte[] bArr);

    public static native <T extends PumpDeviceEntity> T getDeviceInfo(byte[] bArr);

    public static native <V extends PumpHistoryEntity> V getHistory(byte[] bArr);

    public static native <T extends PumpBroadcastEntity> void setBroadcastClass(Class<T> cls);

    public static native <T extends PumpDeviceEntity> void setDeviceInfoClass(Class<T> cls);

    public static native <V extends PumpHistoryEntity> void setHistoryClass(Class<V> cls);
}
