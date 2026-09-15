#include <algorithm>
#include <cassert>
#include <cstdint>
#include <map>
#include <utility>
#include <vector>

using jlong = int64_t;
using HistorySamples = std::map<jlong, std::pair<jlong, jlong>>;

struct Glucose {
    uint32_t time;
    uint16_t mgdlTimes10;
    bool valid() const { return mgdlTimes10 > 380 && mgdlTimes10 < 5020; }
    uint32_t gettime() const { return time; }
    uint16_t getsputnik() const { return mgdlTimes10; }
};

struct SensorGlucoseData {
    bool dexcom;
    std::vector<Glucose> history;
    int first = 0;
    bool isDexcom() const { return dexcom; }
    int getstarthistory() const { return first; }
    int getAllendhistory() const { return history.size(); }
    int maxpos() const { return history.size(); }
    const Glucose* getglucose(int pos) const { return &history.at(pos); }
};

// PRODUCTION_EXPORTER

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

    // Libre NFC history must still export in JNI units (mg/dL * 10), with no raw lane.
    const SensorGlucoseData libre{false, {{measuredTime - 900, 770},
                                        {measuredTime, 1360},
                                        {forecastTime, 0}}};
    samples.clear();
    appendStoredHistory(&libre, 0, samples);
    assert(samples.size() == 2);
    assert(samples.at(measuredTime - 900).first == 770);
    assert(samples.at(measuredTime).first == 1360);
    assert(samples.at(measuredTime).second == 0);

    samples.clear();
    appendStoredHistory(&libre, measuredTime - 900, samples);
    assert(samples.size() == 1); // starttime is exclusive.
    assert(samples.begin()->first == measuredTime);

    const SensorGlucoseData empty{false, {}};
    samples.clear();
    appendStoredHistory(&empty, 0, samples);
    assert(samples.empty());
}
