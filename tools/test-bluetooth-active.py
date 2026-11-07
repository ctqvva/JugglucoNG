#!/usr/bin/env python3
"""Run the production Sensoren::bluetoothactive walk on a fake sensor list (#872).

A G7 that was still reading dropped out of activeSensors, so updateDevicers removed
its driver and the sensor left the app. The walk goes from the latest-started sensor
back and stopped at the first one without data for 24 days, taking every sensor that
started before it as over too. A record that started recently and simply has no data
(set up and never read, a shell) stopped it as well, and dropped a live sensor that
started earlier. Also checks the reason the removal log now gives. Requires a host
C++20 compiler (CXX or c++).
"""
from pathlib import Path
import os, shlex, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'Common/src/main/cpp/sensoren.hpp').read_text()


def extract(signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        if source[end] == '{': depth += 1
        elif source[end] == '}': depth -= 1
        end += 1
    return source[start:end]


walk = '\n'.join([
    extract('vector<int> bluetoothactive(uint32_t tim, uint32_t nu) {'),
    extract('std::string bluetoothactiveReason('),
    'template <typename Report>\n' + extract('vector<int> bluetoothactive(uint32_t tim, uint32_t nu, Report &&report) {'),
])
fixture = r'''
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
using namespace std;
#define LOGGER(...) do{}while(0)
#define LOGSTRING(...) do{}while(0)
constexpr uint16_t LISTEND = 0xFFFF;
constexpr int maxSIhours = 24 * 24, youngsensorsecs = 7200;

struct SensorGlucoseData {
    uint32_t last = 0; bool bluetooth = true;
    bool isSibionics() const { return false; }
    bool hasSensorError(uint32_t) const { return false; }
    uint32_t lastused() const { return last; }
    bool canusestreaming() const { return bluetooth; }
    bool isInResetMode() const { return false; }
};
struct sensor {
    uint32_t starttime = 0, endtime = 0; char name[17]{}; uint8_t present = 1, finished = 0;
    uint16_t next = 1, prev = LISTEND;
    bool wasRemovedByUser() const { return finished == 2; }
    void markFinished() { finished = 1; }
};
struct Sensoren {
    vector<sensor> list; vector<SensorGlucoseData> data;
    sensor *sensorlist() { return list.data(); }
    int getendindex() const { return int(list.size()) - 1; }
    void setindices() {}
    void setlibre3nums() {}
    SensorGlucoseData *getSensorData(int i) { return &data[i]; }
    bool repairMissingStarttime(int, sensor &, SensorGlucoseData *, bool) { return false; }
    const char *showsensorname(int i) { return list[i].name; }
    // In start order; each one's prev is the one before.
    int add(const char *name, uint32_t start, uint32_t last, uint32_t endtime = 0) {
        sensor s; s.starttime = start; s.endtime = endtime; strcpy(s.name, name);
        s.prev = list.empty() ? LISTEND : uint16_t(list.size() - 1);
        list.push_back(s); data.push_back({last});
        return int(list.size()) - 1;
    }
    /* WALK */
};
int main() {
    const uint32_t nu = 1800000000u, day = 86400;
    auto active = [&](Sensoren &s) { return s.bluetoothactive(nu - day, nu); };
    {   // #872: a live G7, then a record set up later that never got a reading.
        Sensoren s;
        s.add("old-libre", nu - 60 * day, nu - 46 * day);
        const int g7 = s.add("g7", nu - 4 * day, nu - 120);
        const int empty = s.add("never-read", nu - 3600, 0);
        const auto out = active(s);
        assert(out.size() == 1 && out[0] == g7);
        assert(s.bluetoothactiveReason(g7, nu - day, nu) == "listed");
        assert(s.bluetoothactiveReason(empty, nu - day, nu) == "started within 24 days, no data");
        assert(s.bluetoothactiveReason(0, nu - day, nu) == "stops the walk");
    }
    {   // A sensor whose last data is over 24 days old still ends the walk.
        Sensoren s;
        const int earlier = s.add("earlier", nu - 50 * day, nu - 30 * day);
        s.add("over", nu - 40 * day, nu - 26 * day);
        assert(active(s).empty());
        assert(s.bluetoothactiveReason(earlier, nu - day, nu).starts_with("not reached: the walk stopped at over"));
    }
    {   // Each other way out says why.
        Sensoren s;
        const int quiet = s.add("quiet", nu - 5 * day, nu - 2 * day);
        const int done = s.add("done", nu - 4 * day, nu - 300); s.list[done].finished = 1;
        const int gone = s.add("gone", nu - 3 * day, nu - 300); s.list[gone].finished = 2;
        const int noble = s.add("nobt", nu - 2 * day, nu - 300); s.data[noble].bluetooth = false;
        const int live = s.add("live", nu - day, nu - 300, nu - 300);
        assert(active(s) == vector<int>{live});
        assert(s.bluetoothactiveReason(quiet, nu - day, nu) == "no data for a day");
        assert(s.bluetoothactiveReason(done, nu - day, nu) == "finished");
        assert(s.bluetoothactiveReason(gone, nu - day, nu) == "removed by the user");
        assert(s.bluetoothactiveReason(noble, nu - day, nu) == "not a Bluetooth sensor");
    }
    puts("PASS: a recently started record without data no longer hides a live sensor started before it; removal reasons named");
}
'''.replace('/* WALK */', walk)
with tempfile.TemporaryDirectory(prefix='bluetooth-active-') as directory:
    directory = Path(directory)
    src, binary = directory / 'walk.cpp', directory / 'walk'
    src.write_text(fixture)
    subprocess.run(shlex.split(os.environ.get('CXX', 'c++')) + ['-std=c++20', '-Wall', str(src), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
