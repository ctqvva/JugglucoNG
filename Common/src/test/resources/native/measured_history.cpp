#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <map>
#include <utility>
#include <vector>

// g.cpp builds against the NDK's libc++, which also declares isnan at global
// scope; libstdc++ (Linux CI) only has std::isnan.
using std::isnan;
using jlong = int64_t;
using HistorySamples = std::map<jlong, std::pair<jlong, jlong>>;

struct Glucose {
    uint32_t time;
    uint16_t mgdlTimes10;
    uint16_t id = 0;
    bool valid() const { return mgdlTimes10 > 380 && mgdlTimes10 < 5020; }
    uint32_t gettime() const { return time; }
    uint16_t getsputnik() const { return mgdlTimes10; }
    uint32_t getid() const { return id; }
};

struct ScanData {
    uint32_t t;
    int32_t g;
    int32_t id = 0;
    uint16_t raw = 0; // rawpolls.dat, mmol/L x 10
    bool valid() const { return g > 0; }
};

struct SensorGlucoseData {
    bool dexcom;
    std::vector<Glucose> history;
    int first = 0;
    std::vector<ScanData> polls = {};
    bool aidex = false;
    std::vector<ScanData> scans = {};
    bool libre3 = false;
    bool patchinfo = true; // all zero on a managed driver's direct stream shell
    bool isDexcom() const { return dexcom; }
    bool hasLibrePatchInfo() const { return patchinfo; }
    bool isAiDex() const { return aidex; }
    // Native decides Libre 2 by elimination, AiDex shells included.
    bool isLibre2() const { return !dexcom && !libre3; }
    const std::vector<ScanData>& getPolldata() const { return polls; }
    const std::vector<ScanData>& getScandata() const { return scans; }
    uint16_t getRawForPoll(const ScanData *poll) const { return poll ? poll->raw : 0; }
    int getstarthistory() const { return first; }
    int getAllendhistory() const { return history.size(); }
    int maxpos() const { return history.size(); }
    const Glucose* getglucose(int pos) const { return &history.at(pos); }
};

// Native calibration (calibrate/calculate.cpp): NAN when none applies. Tests
// switch it on to see that history goes through it exactly as the stream does.
static double calibrationOffset = NAN;
double calibrateNow(const SensorGlucoseData *, const uint32_t, const double value) {
    return std::isnan(calibrationOffset) ? NAN : value + calibrationOffset;
}
double calibrateNow(const SensorGlucoseData *sens, const ScanData &value) {
    return calibrateNow(sens, value.t, (double)value.g);
}

// PRODUCTION_EXPORTER

static HistorySamples exportAll(const SensorGlucoseData &sens, jlong starttime = 0) {
    HistorySamples samples;
    appendStoredHistory(&sens, starttime, samples);
    appendScanHistory(&sens, starttime, samples);
    appendPollHistory(&sens, starttime, samples);
    return samples;
}

int main() {
    // Issue #250 trace: measured 136 mg/dL at 16:04:33, forecast 125 at 16:14:33.
    constexpr uint32_t measuredTime = 1787925873;
    constexpr uint32_t forecastTime = measuredTime + 600;
    const SensorGlucoseData dexcom{true, {{measuredTime - 300, 1400},
                                        {forecastTime, 1250}}};
    HistorySamples samples;
    appendStoredHistory(&dexcom, measuredTime - 120, samples);
    assert(samples.empty()); // Live queries must not return the future forecast.

    appendStoredHistory(&dexcom, 0, samples);
    assert(samples.empty()); // Full sync must also exclude old, now-past forecasts.

    // A forecast colliding with an actual poll must never overwrite the measurement.
    samples[forecastTime] = {1360, 0};
    appendStoredHistory(&dexcom, 0, samples);
    assert(samples.size() == 1);
    assert(samples.at(forecastTime).first == 1360);

    // Libre NFC history exports in JNI units (mg/dL * 10). With no stream it is
    // the Auto line's only answer, and it is the History (Raw) lane as well.
    const SensorGlucoseData libre{false, {{measuredTime - 900, 770, 10},
                                        {measuredTime, 1360, 25},
                                        {forecastTime, 0, 35}}};
    samples.clear();
    appendStoredHistory(&libre, 0, samples);
    assert(samples.size() == 2);
    assert(samples.at(measuredTime - 900).first == 770);
    assert(samples.at(measuredTime).first == 1360);
    assert(samples.at(measuredTime).second == 1360);

    samples.clear();
    appendStoredHistory(&libre, measuredTime - 900, samples);
    assert(samples.size() == 1); // starttime is exclusive.
    assert(samples.begin()->first == measuredTime);

    // Libre 1/2 with a BLE stream: Auto is the stream and History is paired to
    // the stream reading of the same minute id.
    {
        constexpr int firstId = 1000;
        const uint32_t t0 = measuredTime;
        auto at = [&](int id) { return t0 + (uint32_t)(id - firstId) * 60; };
        std::vector<ScanData> stream;
        for (int id = firstId; id <= firstId + 20; ++id) {
            if (id == firstId + 3) continue; // one missed minute
            stream.push_back({at(id) + 7, 120, id});
        }
        // An uncompensated NFC trend minute (saveScanTrend): raw only.
        stream.push_back({at(firstId - 30), 0, firstId - 30, 40});
        const SensorGlucoseData streaming{false,
                                          {{at(firstId - 15), 900, firstId - 15},
                                           {at(firstId + 3), 1010, firstId + 3},
                                           {at(firstId + 5), 1000, firstId + 5},
                                           {at(firstId + 20), 1100, firstId + 20}},
                                          0, stream};
        samples = exportAll(streaming);
        // 20 stream minutes plus two history-only minutes; no NFC trend row.
        assert(samples.size() == 22);
        assert(samples.count(at(firstId - 30)) == 0);
        // Paired: the stream reading carries its minute's history.
        assert(samples.at(at(firstId + 5) + 7).first == 1200);
        assert(samples.at(at(firstId + 5) + 7).second == 1000);
        assert(samples.at(at(firstId + 20) + 7).second == 1100);
        assert(samples.count(at(firstId + 5)) == 0); // no second row for it
        // A stream minute with no history has an empty History lane.
        assert(samples.at(at(firstId + 6) + 7).second == 0);
        // The missed minute is covered by the stream on both sides: History
        // only, so the Stream line does not jump to the sensor's other answer.
        assert(samples.at(at(firstId + 3)).first == 0);
        assert(samples.at(at(firstId + 3)).second == 1010);
        // Before the stream starts the history is the Auto line's answer too:
        // dropping it would break the line across 15 + 15 minutes.
        assert(samples.at(at(firstId - 15)).first == 900);
        assert(samples.at(at(firstId - 15)).second == 900);
    }

    // A missed sensor minute's history at 12:15:10 and the next minute's poll at
    // 12:15:50 share a clock minute, which Room stores as one row: the history
    // rides on that poll instead of becoming a row of its own.
    {
        const uint32_t base = measuredTime - (measuredTime % 60);
        const SensorGlucoseData shared{false,
                                       {{base + 10, 1000, 41}},
                                       0, {{base - 60 + 50, 118, 40}, {base + 50, 120, 42}}};
        samples = exportAll(shared);
        assert(samples.size() == 2);
        assert(samples.count(base + 10) == 0);
        assert(samples.at(base + 50).first == 1200);
        assert(samples.at(base + 50).second == 1000);
        // The other poll keeps an empty History lane.
        assert(samples.at(base - 10).second == 0);
    }

    // NFC only: a scan at 12:00:50 and history for 12:00:10 that a later scan
    // brought share a clock minute. The history rides on the scan's row, so
    // Room keeps both lanes; another minute's history keeps its own row.
    {
        const uint32_t base = measuredTime - (measuredTime % 60);
        SensorGlucoseData nfcOnly{false, {{base + 10, 1000, 41}, {base + 900, 1050, 56}}};
        nfcOnly.scans = {{base + 50, 125, 0}};
        samples = exportAll(nfcOnly);
        assert(samples.size() == 2);
        assert(samples.count(base + 10) == 0);
        assert(samples.at(base + 50).first == 1250);
        assert(samples.at(base + 50).second == 1000);
        assert(samples.at(base + 900).first == 1050);
        assert(samples.at(base + 900).second == 1050);
        // Other sensors keep the scan's Raw lane empty and the history row.
        nfcOnly.patchinfo = false;
        samples = exportAll(nfcOnly);
        assert(samples.size() == 3);
        assert(samples.at(base + 50).second == 0);
        assert(samples.at(base + 10).first == 1000);
    }

    // A BLE dropout: last poll 12:11, history at 12:15 and 12:30, neither with
    // a stream reading. Without the 12:15 point on the Auto line, the line would
    // break across 19 minutes.
    {
        const uint32_t noon = measuredTime - (measuredTime % 3600);
        std::vector<ScanData> dropout;
        for (int minute = 0; minute <= 11; ++minute)
            dropout.push_back({noon + minute * 60, 120, minute});
        const SensorGlucoseData droppedOut{false,
                                           {{noon + 15 * 60, 1000, 15},
                                            {noon + 30 * 60, 1100, 30}},
                                           0, dropout};
        samples = exportAll(droppedOut);
        assert(samples.at(noon + 15 * 60).first == 1000);
        assert(samples.at(noon + 30 * 60).first == 1100);
    }

    // The edge: a stream reading five minutes on each side covers a history
    // point; a second further on either side does not.
    {
        const SensorGlucoseData edge{false,
                                     {{measuredTime - 900, 1000, 1},
                                      {measuredTime - 901, 1010, 2},
                                      {measuredTime - 899, 1020, 3}},
                                     0, {{measuredTime - 1200, 120, 100}, {measuredTime - 600, 120, 101}}};
        samples.clear();
        appendStoredHistory(&edge, 0, samples);
        assert(samples.at(measuredTime - 900).first == 0);
        assert(samples.at(measuredTime - 901).first == 1010);
        assert(samples.at(measuredTime - 899).first == 1020);
    }

    // AiDex shells, other managed drivers' shells (Ottai, Anytime, Medtrum...:
    // Libre 2 by elimination, no patch info) and Libre 3 keep their history
    // and raw lanes as before.
    {
        const SensorGlucoseData base{false,
                                     {{measuredTime, 1200, 7}},
                                     0, {{measuredTime + 30, 120, 7, 70}}};
        SensorGlucoseData aidex = base;
        aidex.aidex = true;
        SensorGlucoseData managedShell = base;
        managedShell.patchinfo = false;
        SensorGlucoseData libre3 = base;
        libre3.libre3 = true;
        for (const SensorGlucoseData *other : {&aidex, &managedShell, &libre3}) {
            samples = exportAll(*other);
            assert(samples.size() == 2);
            assert(samples.at(measuredTime).first == 1200);
            assert(samples.at(measuredTime).second == 0);
            assert(samples.at(measuredTime + 30).second == (jlong)(70 * 18.0182));
        }
    }

    // Native calibration reaches history exactly as it reaches the stream.
    {
        calibrationOffset = 10.0;
        const SensorGlucoseData calibrated{false,
                                           {{measuredTime, 1000, 7},
                                            {measuredTime + 3600, 1100, 67}},
                                           0, {{measuredTime + 30, 120, 7}}};
        samples = exportAll(calibrated);
        assert(samples.at(measuredTime + 30).first == 1300);
        assert(samples.at(measuredTime + 30).second == 1100);
        assert(samples.at(measuredTime + 3600).first == 1200);
        assert(samples.at(measuredTime + 3600).second == 1200);
        calibrationOffset = NAN;
    }

    const SensorGlucoseData empty{false, {}};
    samples.clear();
    appendStoredHistory(&empty, 0, samples);
    assert(samples.empty());
}
