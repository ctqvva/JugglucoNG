package com.microtechmd.blecomm.entity;

/* loaded from: classes2.dex */
public class NewSensorEntity {
    AidexXDatetimeEntity aidexXDatetimeEntity;
    Long dateTime;
    Boolean isNew;

    public NewSensorEntity(AidexXDatetimeEntity aidexXDatetimeEntity) {
        this.aidexXDatetimeEntity = aidexXDatetimeEntity;
    }

    public NewSensorEntity(Boolean bool, Long l) {
        this.isNew = bool;
        this.dateTime = l;
    }

    public AidexXDatetimeEntity getAidexXDatetimeEntity() {
        return this.aidexXDatetimeEntity;
    }

    public Boolean getNew() {
        return this.isNew;
    }

    public Long getDateTime() {
        return this.dateTime;
    }
}
