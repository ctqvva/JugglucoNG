package com.microtechmd.blecomm.parser;

/* loaded from: classes2.dex */
public class AidexXAbstractEntity {
    public int calIndex;
    public int calTemp;
    public int status;
    public int timeOffset;
    public int trend;

    public AidexXAbstractEntity() {
    }

    public AidexXAbstractEntity(int i, int i2, int i3, int i4, int i5) {
        this.timeOffset = i;
        this.status = i2;
        this.calTemp = i3;
        this.trend = (byte) i4;
        this.calIndex = i5;
    }

    public String toString() {
        return "AidexXAbstractEntity{timeOffset=" + this.timeOffset + ", status=" + this.status + ", calTemp=" + this.calTemp + ", trend=" + this.trend + ", calIndex=" + this.calIndex + '}';
    }
}
