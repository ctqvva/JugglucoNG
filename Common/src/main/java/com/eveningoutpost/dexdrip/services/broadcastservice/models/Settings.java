package com.eveningoutpost.dexdrip.services.broadcastservice.models;

import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.Keep;
//import lombok.Setter;
@Keep
public class Settings implements Parcelable {
    public static final Creator<Settings> CREATOR = new Creator<Settings>() {
        @Override
        public Settings createFromParcel(Parcel in) {
            return new Settings(in);
        }
        @Override
        public Settings[] newArray(int size) {
            return new Settings[size];
        }
    };
//   @Setter
    private long graphStart;
    private long graphEnd;
    private String apkName;
    private boolean displayGraph;

    public Settings(Parcel in) {
        apkName = in.readString();
        graphStart = in.readLong();
        graphEnd = in.readLong();
        displayGraph = in.readInt() == 1;
    }

    public Settings() {
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(apkName);
        parcel.writeLong(graphStart);
        parcel.writeLong(graphEnd);
        parcel.writeInt(displayGraph ? 1 : 0);
    }

    /** For saving registered clients; apkName goes last since it may contain the separator. */
    public String serialize() {
        return graphStart + "," + graphEnd + "," + (displayGraph ? 1 : 0) + "," + (apkName == null ? "" : apkName);
    }

    public static Settings deserialize(String value) {
        try {
            final String[] parts = value.split(",", 4);
            if (parts.length != 4) return null;
            final Settings settings = new Settings();
            settings.graphStart = Long.parseLong(parts[0]);
            settings.graphEnd = Long.parseLong(parts[1]);
            settings.displayGraph = "1".equals(parts[2]);
            settings.apkName = parts[3].isEmpty() ? null : parts[3];
            return settings;
        } catch (RuntimeException e) {
            return null;
        }
    }

    //<editor-fold defaultstate="collapsed" desc="delombok">
    @SuppressWarnings("all")
    public long getGraphStart() {
        return this.graphStart;
    }

    @SuppressWarnings("all")
    public boolean isDisplayGraph() {
        return this.displayGraph;
    }
    //</editor-fold>
}
