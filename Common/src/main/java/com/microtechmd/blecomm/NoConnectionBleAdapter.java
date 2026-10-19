package com.microtechmd.blecomm;

import java.util.Timer;
import java.util.TimerTask;

/* loaded from: classes2.dex */
public abstract class NoConnectionBleAdapter extends BleAdapter {
    @Override // com.microtechmd.blecomm.BleAdapter
    public void executeStartScan() {
    }

    @Override // com.microtechmd.blecomm.BleAdapter
    public void executeStopScan() {
    }

    @Override // com.microtechmd.blecomm.BleAdapter
    public boolean isReadyToConnect(String str) {
        return true;
    }

    @Override // com.microtechmd.blecomm.BleAdapter
    public void executeConnect(String str) {
        new Timer().schedule(new TimerTask() { // from class: com.microtechmd.blecomm.NoConnectionBleAdapter.1
            @Override // java.util.TimerTask, java.lang.Runnable
            public void run() {
                NoConnectionBleAdapter.this.onConnectSuccess();
            }
        }, 200L);
    }

    @Override // com.microtechmd.blecomm.BleAdapter
    public void executeDisconnect() {
        new Timer().schedule(new TimerTask() { // from class: com.microtechmd.blecomm.NoConnectionBleAdapter.2
            @Override // java.util.TimerTask, java.lang.Runnable
            public void run() {
                NoConnectionBleAdapter.this.onDisconnected();
            }
        }, 200L);
    }
}
