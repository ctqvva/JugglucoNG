package com.microtechmd.blecomm.parser;

/* loaded from: classes2.dex */
public class BgmParser {
    public static native <T extends BgmDeviceEntity> T getDeviceInfo(byte[] bArr);

    public static native <V extends BgmHistoryEntity> V getHistory(byte[] bArr);

    public static native <T extends BgmDeviceEntity> void setDeviceInfoClass(Class<T> cls);

    public static native <V extends BgmHistoryEntity> void setHistoryClass(Class<V> cls);
}
