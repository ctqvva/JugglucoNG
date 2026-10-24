/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Sun Mar 10 11:38:16 CET 2024                                                 */


package tk.glucodata;

import androidx.health.connect.client.records.BloodGlucoseRecord;
import androidx.health.connect.client.records.metadata.Metadata;
import androidx.health.connect.client.units.BloodGlucose;
import java.time.Instant;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToLongFunction;

/** A snapshot of valid readings within a bounded native poll-index range. */
public final class GlucoseList extends AbstractList<BloodGlucoseRecord> {
    private final List<BloodGlucoseRecord> records = new ArrayList<>();

    public GlucoseList(Metadata meta, long sensorptr, int start, int len, String sensorName) {
        this(meta, start, len, sensorName, pos -> Natives.streamfromSensorptr(sensorptr, pos, start + len));
    }

    // A reader seam lets host tests exercise sparse native windows without JNI.
    GlucoseList(Metadata meta, int start, int len, String sensorName, IntToLongFunction reader) {
        final int end = start + len;
        int pos = start;
        while (pos < end) {
            final long packed = reader.applyAsLong(pos);
            final long time = packed & 0xFFFFFFFFL;
            final int mgdl = (int) ((packed >>> 32) & 0xFFFF);
            final int next = (int) ((packed >>> 48) & 0xFFFF);
            if (time > 0 && mgdl > 0) {
                final long clientVersion = 0L;
                final String clientRecordId = "juggluco-ng:glucose:" + sensorName + ":" + time;
                final Metadata metadata = Metadata.unknownRecordingMethod(
                        clientRecordId, clientVersion, meta.getDevice());
                records.add(new BloodGlucoseRecord(Instant.ofEpochSecond(time), null, metadata,
                        BloodGlucose.milligramsPerDeciliter(mgdl), 1, 0, 0));
            }
            if (next <= pos) break;
            pos = next;
        }
    }

    @Override public BloodGlucoseRecord get(int index) { return records.get(index); }
    @Override public int size() { return records.size(); }
}
