package com.microtechmd.blecomm.parser;

/* loaded from: classes2.dex */
public class AidexXHistoryEntity {
    public int glucose;
    public int isValid;
    public int quality;
    public int status;
    public int timeOffset;

    public AidexXHistoryEntity() {
    }

    public AidexXHistoryEntity(int i, int i2, int i3, int i4, int i5) {
        this.timeOffset = i;
        this.glucose = i2;
        this.status = i3;
        this.quality = i4;
        this.isValid = i5;
    }

    public String toString() {
        return "history:[timeOffset=" + this.timeOffset + ", glucose=" + this.glucose + ", status=" + this.status + ", quality=" + this.quality + ", isValid=" + this.isValid + ']';
    }
}
