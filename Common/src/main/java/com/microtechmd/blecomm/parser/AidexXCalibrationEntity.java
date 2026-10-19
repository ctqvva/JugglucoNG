package com.microtechmd.blecomm.parser;

/* loaded from: classes2.dex */
public class AidexXCalibrationEntity {
    public float cf;
    public int index;
    public int isValid;
    public float offset;
    public int referenceGlucose;
    public int timeOffset;

    public AidexXCalibrationEntity() {
    }

    public AidexXCalibrationEntity(int i, int i2, float f, float f2, int i3, int i4) {
        this.index = i;
        this.timeOffset = i2;
        this.cf = f;
        this.offset = f2;
        this.referenceGlucose = i3;
        this.isValid = i4;
    }

    public String toString() {
        return "index=" + this.index + ", timeOffset=" + this.timeOffset + ", referenceGlucose=" + this.referenceGlucose + ", cf=" + this.cf + ", offset=" + this.offset;
    }
}
