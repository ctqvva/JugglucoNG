package com.microtechmd.blecomm.controller;

import com.microtechmd.blecomm.BlecommLoader;
import com.microtechmd.blecomm.entity.AidexXDatetimeEntity;
import com.microtechmd.blecomm.entity.NewSensorEntity;
import android.util.Log;

/* loaded from: classes2.dex */
public class AidexXController extends BleControllerProxy {
    private static final String TAG = "AidexXController";
    private native void constructor();

    private native void constructorWithInfo(BleControllerInfo bleControllerInfo);

    private native void destructor();

    public native int calibration(int i, int i2);

    public int calibrationWithLog(int i, int i2) {
        Log.d(TAG, "Native method: calibration(" + i + ", " + i2 + ")");
        return calibration(i, i2);
    }

    public native int clearStorage();

    public native int deleteBond();

    public native int getBroadcastData();

    public int getBroadcastDataWithLog() {
        Log.d(TAG, "Native method: getBroadcastData()");
        return getBroadcastData();
    }

    public native int getCalibration(int i);

    public native int getCalibrationRange();

    public native int getControllerCalTemp();

    public native int getControllerStatus();

    public native int getDefaultParamData();

    public native int getDeviceInfo();

    public int getDeviceInfoWithLog() {
        Log.d(TAG, "Native method: getDeviceInfo()");
        return getDeviceInfo();
    }

    public native int getHistories(int i);

    public int getHistoriesWithLog(int i) {
        Log.d(TAG, "Native method: getHistories(" + i + ")");
        return getHistories(i);
    }

    public native int getHistoryRange();

    public native int getRawHistories(int i);

    public native int getSensorCheck(int i);

    public native int getStartTime();

    public int getStartTimeWithLog() {
        Log.d(TAG, "Native method: getStartTime()");
        return getStartTime();
    }

    public native int isAesInitialized();

    public native int isBleNativePaired();

    public native int isProductExpire();

    public native int newSensor(AidexXDatetimeEntity aidexXDatetimeEntity);

    public native int reset();

    public native int setAutoUpdateStatus();

    public native int setDefaultParamByteData(byte[] bArr);

    public native int setDefaultParamData(float[] fArr);

    public native int setDynamicAdvMode(int i);

    public native int setGcBiasTrimming(int i);

    public native int setGcImeasTrimming(int i, int i2);

    public native int shelfMode();

    static {
        BlecommLoader.ensureLoaded();
    }

    public static AidexXController getInstance() {
        return new AidexXController();
    }

    public static AidexXController getInstance(BleControllerInfo bleControllerInfo) {
        return new AidexXController(bleControllerInfo);
    }

    private AidexXController() {
        constructor();
    }

    private AidexXController(BleControllerInfo bleControllerInfo) {
        constructorWithInfo(bleControllerInfo);
    }

    protected void finalize() throws Throwable {
        destructor();
        super.finalize();
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void getTransInfo() {
        Log.d(TAG, "Calling getDeviceInfo()");
        getDeviceInfoWithLog();
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void getDefaultParam() {
        getDefaultParamData();
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void setDefaultParam(float[] fArr) {
        setDefaultParamData(fArr);
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void setDefaultParamByteArray(byte[] bArr) {
        setDefaultParamByteData(bArr);
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void newSensor(NewSensorEntity newSensorEntity) {
        newSensor(newSensorEntity.getAidexXDatetimeEntity());
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void setDynamicMode(int i) {
        setDynamicAdvMode(i);
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void setAutoUpdate() {
        setAutoUpdateStatus();
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public boolean isNativePaired() {
        return isBleNativePaired() == 1;
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public boolean isInitialized() {
        return isAesInitialized() == 1;
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public boolean isProductionExpire() {
        return isProductExpire() == 1;
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public int getStatus() {
        return getControllerStatus();
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public int getCalTemp() {
        return getControllerCalTemp();
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void startTime() {
        Log.d(TAG, "Calling getStartTime() in AidexXController");
        getStartTimeWithLog();
    }

    @Override // com.microtechmd.blecomm.controller.BleControllerProxy
    public void clearPair() {
        deleteBond();
    }
}
