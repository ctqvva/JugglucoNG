package tk.glucodata.NovoPen.opennov;

import java.util.ArrayList;
import java.util.List;

import tk.glucodata.NovoPen.opennov.mt.ARequest;
import tk.glucodata.NovoPen.opennov.mt.Apdu;
import tk.glucodata.NovoPen.opennov.mt.Configuration;
import tk.glucodata.NovoPen.opennov.mt.EventReport;
import tk.glucodata.NovoPen.opennov.mt.EventRequest;
import tk.glucodata.NovoPen.opennov.mt.SegmentInfoList;
import tk.glucodata.NovoPen.opennov.mt.Specification;
import tk.glucodata.NovoPen.opennov.mt.TrigSegmDataXfer;

/**
 * JamOrHam
 * OpenNov context holder
 */

public class OpContext {

    public Specification specification;
//    public RelativeTime relativeTime;
    public EventRequest eventRequest;
    public EventReport eventReport;
    public Configuration configuration;
    public TrigSegmDataXfer trigSegmDataXfer;
    public SegmentInfoList segmentInfoList;
//    public IdModel model;
    public ARequest aRequest;
    public Apdu apdu;
    public int invokeId = -1;

    static final public class Doses {
        public long referencetime;
        public byte[] rawdoses;

        public Doses(long referencetime, byte[] rawdoses) {
            this.referencetime=referencetime;
            this.rawdoses=rawdoses;
        }
    }
    public final List<Doses> doses=new ArrayList<>();

    private long referencetime=UNSET_REFERENCE_TIME;
    private static final long UNSET_REFERENCE_TIME=Long.MIN_VALUE;

    /**
     * Epoch second the pen's uptime counter is pinned to, captured once per scan.
     *
     * The pen has no clock of its own, so an absolute dose time only exists as
     * "phone clock now minus pen counter now". Reading that pair again for every event
     * report would land a second or two away each time, because NFC transfer latency
     * varies, and the doses of one scan would then disagree with each other about when
     * they happened. Pinning it on the first report keeps a whole read on one timeline.
     */
    public long referenceTime(long relativeTime) {
        if (referencetime == UNSET_REFERENCE_TIME) {
            referencetime = (System.currentTimeMillis() / 1000L) - relativeTime;
        }
        return referencetime;
    }
    public Configuration getConfiguration() {
        if (configuration != null) {
            return configuration;
        } else {
            if (eventReport != null && eventReport.configuration != null) {
                configuration = eventReport.configuration; // cache
                return configuration;
            }
        }
        return null;
    }

    public boolean hasConfiguration() {
        return configuration != null || getConfiguration() != null;
    }

    public boolean isError() {
        return apdu == null || apdu.isError();
    }

    public boolean wantsRelease() {
        return apdu != null && apdu.wantsRelease();
    }

}
