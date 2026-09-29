package com.eveningoutpost.dexdrip.services.broadcastservice.models;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Keep;

import java.util.ArrayList;
import java.util.List;

//import lombok.Getter;
//import lombok.Setter;
@Keep
public class GraphLine implements Parcelable {
    public static final Creator<GraphLine> CREATOR = new Creator<GraphLine>() {

        @Override
        public GraphLine createFromParcel(Parcel source) {
            return new GraphLine(source);
        }

        @Override
        public GraphLine[] newArray(int size) {
            return new GraphLine[size];
        }
    };
//    @Getter
 //   @Setter
    @Keep
    public List<GraphPoint> values;
  //  @Getter
   // @Setter
    private int color;

    public GraphLine(int col) {
        values = new ArrayList<>();
        color = col;
    }
   // public GraphLine() { this(0); }
@Keep
 public void add(float x,float y) {
          values.add(new GraphPoint(x,y));
 	}
	/*
    public GraphLine(Line line) {
        values = new ArrayList<>();
        line.update(0);
        for (PointValue pointValue : line.getValues()) {
            values.add(new GraphPoint(pointValue.getX(), pointValue.getY()));
        }
        color = line.getColor();
    }     public GraphLine(Line line) {
        values = new ArrayList<>();
        line.update(0);
        for (PointValue pointValue : line.getValues()) {
            double real_timestamp = pointValue.getX();
            values.add(new GraphPoint((float)real_timestamp, (float)pointValue.getY()));
        }
        color = line.getColor();
    }
*/

    // legacy API: the typed readArrayList(ClassLoader,Class) overload is API 33+, minSdk is 26.
    // The parcel comes from another app, so keep only the elements that really are GraphPoints.
    @SuppressWarnings("deprecation")
    public GraphLine(Parcel parcel) {
        values = new ArrayList<>();
        final ArrayList<?> read = parcel.readArrayList(GraphPoint.class.getClassLoader());
        if (read != null)
            for (Object p : read)
                if (p instanceof GraphPoint)
                    values.add((GraphPoint) p);
        color = parcel.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeList(values);
        parcel.writeInt(color);
    }
}
