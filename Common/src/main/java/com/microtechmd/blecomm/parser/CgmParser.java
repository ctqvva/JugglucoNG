package com.microtechmd.blecomm.parser;

import java.util.List;

/* loaded from: classes2.dex */
public class CgmParser {
    public static native <T extends CgmBroadcastEntity> T getBroadcast(byte[] bArr);

    public static native <T extends CgmConfigEntity> T getDeviceConfig(byte[] bArr);

    public static native <T extends CgmDeviceEntity> T getDeviceInfo(byte[] bArr);

    public static native <V extends CgmHistoryEntity> List<V> getFullHistories(byte[] bArr);

    public static native <V extends CgmHistoryEntity> List<V> getHistories(byte[] bArr);

    public static native <V extends CgmHistoryEntity> V getHistory(byte[] bArr);

    public static native <T extends CgmBroadcastEntity> void setBroadcastClass(Class<T> cls);

    public static native <V extends CgmConfigEntity> void setDeviceConfigClass(Class<V> cls);

    public static native <V extends CgmDeviceEntity> void setDeviceInfoClass(Class<V> cls);

    public static native <V extends CgmHistoryEntity> void setHistoryClass(Class<V> cls);
}
