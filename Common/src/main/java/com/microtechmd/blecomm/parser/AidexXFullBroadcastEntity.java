package com.microtechmd.blecomm.parser;

import java.util.List;

/* loaded from: classes2.dex */
public class AidexXFullBroadcastEntity {
    public int calTemp;
    public int calTimeOffset;
    public List<AidexXHistoryEntity> history;
    public int historyCount;
    public int historyTimeOffset;
    public int status;
    public int trend;

    public AidexXFullBroadcastEntity() {
    }

    public AidexXFullBroadcastEntity(int i, int i2, int i3, int i4, List<AidexXHistoryEntity> list, int i5, int i6) {
        this.history = list;
        this.historyTimeOffset = i;
        this.calTimeOffset = i6;
        this.historyCount = i5;
        this.status = i2;
        this.calTemp = i3;
        this.trend = i4;
    }

    public String toString() {
        return "{history=" + this.history + ", historyTimeOffset=" + this.historyTimeOffset + ", calTimeOffset=" + this.calTimeOffset + ", historyCount=" + this.historyCount + ", status=" + this.status + ", calTemp=" + this.calTemp + ", trend=" + this.trend + '}';
    }
}
