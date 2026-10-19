package com.microtechmd.blecomm.entity;

import android.util.Log;
import java.util.Calendar;
import java.util.Date;

/* loaded from: classes2.dex */
public class AidexXDatetimeEntity {
    int day;
    int dstOffset;
    int hour;
    int minute;
    int month;
    int second;
    int timeZone;
    int year;

    public AidexXDatetimeEntity(Calendar calendar) {
        this.timeZone = ((calendar.getTimeZone().getRawOffset() / 1000) / 60) / 15;
        this.year = calendar.get(1);
        this.month = calendar.get(2) + 1;
        this.day = calendar.get(5);
        this.hour = calendar.get(11);
        this.minute = calendar.get(12);
        this.second = calendar.get(13);
        this.dstOffset = (((calendar.getTimeZone().getOffset(new Date().getTime()) - calendar.getTimeZone().getRawOffset()) / 1000) / 60) / 15;
        Log.e("", "set --> timeZone : " + this.timeZone + ", dstOffset : " + this.dstOffset + ", " + this.year + "-" + this.month + "-" + this.day + " " + this.hour + ":" + this.minute + ":" + this.second);
    }
}
