#!/usr/bin/env python3
"""Run the production Dexcom slot helpers from SensorGlucoseData.hpp on a fake store (#767).

Watch copies used to be written into the 5-minute Dexcom store at minute indices.
That padded the stream days past the live slot and left an old copy at its end.
This test covers two things. First, the load-time repair: it must move each copy
into its own slot when the sensor missed that slot, drop the copies the sensor
already has, and end the stream at the newest real reading. Second, slot snapping:
a copy timed by another device's clock must land in the same slot as the sensor's
reading, not the next one. No sensor data or credentials are involved. Requires a
host C++20 compiler (CXX or c++).
"""
from pathlib import Path
import os, re, shlex, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
header = (root / 'Common/src/main/cpp/SensorGlucoseData.hpp').read_text()
writer = (root / 'Common/src/main/cpp/g.cpp').read_text()


def extract(source, signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        if source[end] == '{': depth += 1
        elif source[end] == '}': depth -= 1
        end += 1
    return source[start:end]


helpers = '\n'.join(extract(header, s) for s in [
    'bool validPollIndex(int64_t index) const', 'bool hasStreamID(const int id) const',
    'bool dexSlotTimeFits(const int id) const', 'int dexSlotFor(const uint32_t tim) const',
    'void repairDexcomMinuteSlots()',
])

# The mirror writer must take Dexcom slots from the snapping helper, and the
# store must be repaired when it is opened.
assert re.search(r'lifeCount = dexslots \? hist->dexSlotFor\(timestamp\)', writer), 'writer must snap Dexcom slots'
assert re.search(r'if \(isDexcom\(\)\)\s*repairDexcomMinuteSlots\(\);', header), 'Dexcom stores must be repaired on load'

fixture = r'''
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <vector>
#define LOGGER(...) do{}while(0)
using namespace std;
struct RawData { uint16_t raw; };
struct ScanData { uint32_t t; int32_t id; int32_t g; int32_t tr; float ch; uint32_t gettime() const { return t; } };
struct Info {
    uint32_t starttime = 0, pollcount = 0;
    uint16_t pollstart = 0, lastLifeCountReceived = 0;
    struct { uint32_t streamstart, rawstreamstart; } update[8]{};
};
struct SensorGlucoseData {
    Info metadata;
    vector<ScanData> polls = vector<ScanData>(4000);
    vector<RawData> rawpolls = vector<RawData>(4000);
    vector<uint16_t> temppolls = vector<uint16_t>(4000);
    Info *getinfo() { return &metadata; }
    const Info *getinfo() const { return &metadata; }
    uint32_t getstarttime() const { return metadata.starttime; }
    int pollcount() const { return metadata.pollcount; }
    size_t pollStorageCapacity() const { return polls.size(); }
    const ScanData *getstream(int i) const { return &polls[i]; }
    /* HELPERS */

    // The sensor's own reading for slot k (driver: id=(sensor secs)/300).
    void driver(int k, int phase, int g) {
        polls[k] = {uint32_t(metadata.starttime + k * 300 + phase), k, g, 0, 0};
        if (int(metadata.pollcount) <= k) {
            for (int i = metadata.pollcount; i < k; ++i)
                polls[i] = {uint32_t(metadata.starttime + i * 300), i, 0, 0, 0};
            metadata.pollcount = k + 1;
        }
    }
    // The pre-#767 mirror writer: minute index, gaps padded at 60-s steps.
    void oldMirror(uint32_t t, int g) {
        const int id = (t - metadata.starttime) / 60;
        for (int i = metadata.pollcount; i < id; ++i)
            if (!polls[i].t || polls[i].id != i)
                polls[i] = {uint32_t(t - (id - i) * 60), i, 0, 0, 0};
        polls[id] = {t, id, g, 0, 0};
        rawpolls[id] = {uint16_t(g + 1)};
        metadata.pollcount = max<uint32_t>(metadata.pollcount, id + 1);
    }
    int newest() const {
        for (int i = pollcount() - 1; i >= 0; --i) if (polls[i].g) return i;
        return -1;
    }
};
int main() {
    const uint32_t S = 1790000000u;
    {   // The #767 trace: phone has 300..365, 367, 368 (366 missed); the watch's
        // copies, two minutes earlier and 5 mg/dL lower, sit at minute indices.
        SensorGlucoseData d; d.metadata.starttime = S;
        const int phase = 200;
        for (int k = 300; k <= 365; ++k) d.driver(k, phase, 100 + k % 7);
        d.driver(367, phase, 120);
        d.metadata.lastLifeCountReceived = 365;
        for (int k = 360; k <= 368; ++k) d.oldMirror(S + k * 300 + phase - 120, 95 + k % 7);
        d.driver(368, phase, 121);   // the phone's own reading, written below the padded end
        d.metadata.update[0].streamstart = d.pollcount();
        assert(d.pollcount() > 1800 && d.newest() > 1800);
        d.repairDexcomMinuteSlots();
        assert(d.pollcount() == 369 && d.newest() == 368);
        assert(d.polls[366].g == 95 + 366 % 7 && d.polls[366].id == 366 && d.rawpolls[366].raw == 95 + 366 % 7 + 1);
        assert(d.dexSlotTimeFits(366));
        assert(d.polls[367].g == 120 && d.polls[368].g == 121);       // the sensor's own readings win
        for (int k = 300; k <= 365; ++k) assert(d.polls[k].g == 100 + k % 7);
        for (int i = 369; i < 1900; ++i) assert(!d.polls[i].g);        // no copy left to resurface later
        assert(d.metadata.lastLifeCountReceived == 365 && d.metadata.update[0].streamstart == 369);
        // A gap fill the driver pads later must not revive an old copy.
        d.driver(372, phase, 130);
        for (int i = 369; i < 372; ++i) assert(!d.polls[i].g);
        // Repair is a no-op on a healthy store.
        const auto before = d.polls; const auto count = d.pollcount();
        d.repairDexcomMinuteSlots();
        assert(d.pollcount() == count);
        for (int i = 0; i < 4000; ++i) assert(before[i].t == d.polls[i].t && before[i].g == d.polls[i].g);
    }
    {   // Clock skew: a copy taken by another device's clock maps to the slot of
        // the sensor's reading, not a neighbour, even near a slot boundary.
        for (int phase : {20, 150, 280, -40, 330}) {
            SensorGlucoseData d; d.metadata.starttime = S;
            for (int k = 100; k <= 200; ++k) d.driver(k, phase, 110);
            d.metadata.lastLifeCountReceived = 200;
            for (int skew : {-140, -120, -60, 0, 60, 120, 140}) {
                const int k = 150;
                const uint32_t t = S + k * 300 + phase + skew;
                assert(d.dexSlotFor(t) == k);
            }
            // The next reading, five minutes on, is the next slot.
            assert(d.dexSlotFor(S + 201 * 300 + phase + 100) == 201);
        }
        // Plain division would have pushed a late copy into the next slot.
        SensorGlucoseData d; d.metadata.starttime = S;
        for (int k = 0; k <= 50; ++k) d.driver(k, 280, 110);
        d.metadata.lastLifeCountReceived = 50;
        const uint32_t late = S + 40 * 300 + 280 + 60;
        assert((late - S) / 300 == 41 && d.dexSlotFor(late) == 40);
        // A store whose cursor reading is not in its slot still snaps from the newest one that is.
        d.metadata.lastLifeCountReceived = 0; d.polls[0].g = 0;
        assert(d.dexSlotFor(late) == 40);
    }
    {   // Without any sensor reading to align to (a store fed only by copies):
        // plain division, and the repair moves every copy to its slot.
        SensorGlucoseData d; d.metadata.starttime = S;
        assert(d.dexSlotFor(S + 7 * 300 + 299) == 7 && d.dexSlotFor(S - 1) == -1);
        for (int k = 20; k <= 30; ++k) d.oldMirror(S + k * 300 + 100, 90 + k);
        d.repairDexcomMinuteSlots();
        assert(d.pollcount() == 31 && d.newest() == 30);
        for (int k = 20; k <= 30; ++k) assert(d.polls[k].g == 90 + k && d.dexSlotTimeFits(k));
    }
    {   // Entries timed neither by slot nor by minute are left alone, and the
        // stream is not cut below them.
        SensorGlucoseData d; d.metadata.starttime = S;
        for (int k = 0; k <= 10; ++k) d.driver(k, 100, 110);
        d.oldMirror(S + 10 * 300 + 40, 99);   // minute copy of reading 10 → index 50
        d.polls[60] = {S + 3 * 300, 60, 105, 0, 0};
        d.metadata.pollcount = 61;
        d.repairDexcomMinuteSlots();
        assert(d.pollcount() == 61 && d.polls[60].g == 105 && !d.polls[50].g && d.polls[10].g == 110);
    }
    puts("PASS: Dexcom minute-indexed copies are moved to their slots or dropped, the stream ends at the newest reading, and skewed copies keep their slot");
}
'''.replace('/* HELPERS */', helpers)

with tempfile.TemporaryDirectory(prefix='dexcom-slot-repair-') as directory:
    directory = Path(directory)
    source, binary = directory / 'repair.cpp', directory / 'repair'
    source.write_text(fixture)
    subprocess.run(shlex.split(os.environ.get('CXX', 'c++')) + ['-std=c++20', '-Wall', str(source), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
