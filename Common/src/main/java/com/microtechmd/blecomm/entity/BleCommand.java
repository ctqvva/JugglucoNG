package com.microtechmd.blecomm.entity;

/* loaded from: classes2.dex */
public class BleCommand {
    private byte[] data;
    private int op;
    private int param;
    private int port;

    public BleCommand(int i, int i2, int i3, byte[] bArr) {
        this.port = i;
        this.op = i2;
        this.param = i3;
        if (bArr == null) {
            this.data = new byte[0];
        } else {
            this.data = bArr;
        }
    }

    public int getPort() {
        return this.port;
    }

    public int getOp() {
        return this.op;
    }

    public int getParam() {
        return this.param;
    }

    public byte[] getData() {
        return this.data;
    }
}
