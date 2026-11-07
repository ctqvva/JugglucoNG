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
    'bool dexTimeFitsSlot(const int id, const int64_t t) const',
    'bool dexSlotTimeFits(const int id) const', 'int dexSlotFor(const uint32_t tim) const',
    'void repairDexcomMinuteSlots()', 'void consecutivelifecount()',
    'std::span<const ScanData> getPolldata() const',
])
backfill = extract((root / 'Common/src/main/cpp/dexcom/java.cpp').read_text(),
                   'fromjava(getDexbackfillcmd)(JNIEnv *envin, jclass _cl, jlong dataptr)')
backfill = 'jbyteArray ' + backfill.replace('fromjava(getDexbackfillcmd)', 'getDexbackfillcmd')

# The mirror writer must take Dexcom slots from the snapping helper, and the
# store must be repaired when it is opened.
assert re.search(r'lifeCount = dexslots \? hist->dexSlotFor\(timestamp\)', writer), 'writer must snap Dexcom slots'
assert re.search(r'if \(isDexcom\(\)\)\s*repairDexcomMinuteSlots\(\);', header), 'Dexcom stores must be repaired on load'

fixture = r'''
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <ctime>
#include <span>
#include <vector>
#define NOLOG
#define LOGGER(...) do{if(false)printf(__VA_ARGS__);}while(0)
#define LOGAR(...) do{}while(0)
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
    int getDexMaxSecs() const { return 10 * 86400; }
    int getmaxdexcount() const { return 2880; }
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
// The production getDexbackfillcmd, with the JNI layer faked.
constexpr int DEXSECONDS = 300;
struct JNIEnv {};
using jclass = int; using jlong = long long;
struct jbyteArray {
    int first = 0, second = 0; bool asked = false;
    jbyteArray(nullptr_t) {}
    jbyteArray(int start, int end) : first(start), second(end), asked(true) {}
    explicit operator bool() const { return asked; }
    const jbyteArray *operator->() const { return this; }
};
struct dexcomstream { SensorGlucoseData *hist; };
static jbyteArray mkbackfillcmd(JNIEnv *, int start, int end) { return {start, end}; }
/* BACKFILL */
static jbyteArray ask(SensorGlucoseData &d) {
    dexcomstream stream{&d}; JNIEnv env;
    return getDexbackfillcmd(&env, 0, reinterpret_cast<jlong>(&stream));
}
int main() {
    const uint32_t S = 1790000000u;
    {   // A cursor left past the repaired end must not hide a missed slot.
        SensorGlucoseData d; d.metadata.starttime = uint32_t(time(nullptr)) - 369 * 300;
        const uint32_t start = d.metadata.starttime; const int phase = 200;
        for (int k = 300; k <= 365; ++k) d.driver(k, phase, 110);
        d.metadata.pollstart = 300;
        d.driver(367, phase, 120);
        d.oldMirror(start + 367 * 300 + phase - 120, 100);
        d.driver(368, phase, 121);
        d.metadata.lastLifeCountReceived = uint16_t(d.pollcount() - 1);
        assert(d.pollcount() > 1800 && d.metadata.lastLifeCountReceived > 1800);
        d.repairDexcomMinuteSlots();
        assert(d.pollcount() == 369 && d.metadata.lastLifeCountReceived == 365);
        const auto cmd = ask(d);
        assert(cmd && cmd->first <= 366 * 300 && cmd->second >= 366 * 300 + phase);
    }
    {   // Copies recovered below pollstart stay in the range getPolldata shows,
        // whether pollstart was past the repaired end or just above the copies.
        for (int stored : {300, 1900}) {
            SensorGlucoseData d; d.metadata.starttime = S;
            for (int k = 340; k <= 345; ++k) d.driver(k, 200, 110);
            d.metadata.lastLifeCountReceived = 345;
            for (int k = 335; k <= 339; ++k) d.oldMirror(S + k * 300 + 80, 90 + k % 7);
            d.metadata.pollstart = stored == 300 ? 340 : stored;
            d.repairDexcomMinuteSlots();
            assert(d.pollcount() == 346 && d.metadata.pollstart == 335);
            const auto shown = d.getPolldata();
            assert(shown.size() == 11 && shown.front().id == 335 && shown.back().id == 345);
            for (int k = 335; k <= 339; ++k) assert(shown[k - 335].g == 90 + k % 7);
        }
        // No copy to recover: pollstart goes to the first reading kept.
        SensorGlucoseData d; d.metadata.starttime = S;
        for (int k = 40; k <= 50; ++k) d.driver(k, 200, 110);
        d.metadata.pollstart = 1900; d.metadata.lastLifeCountReceived = 50;
        d.repairDexcomMinuteSlots();
        assert(d.metadata.pollstart == 40 && d.getPolldata().size() == 11);
    }
    {   // Interrupted runs. Copies already cleared, the count not yet cut:
        SensorGlucoseData d; d.metadata.starttime = S;
        for (int k = 300; k <= 368; ++k) d.driver(k, 200, 110);
        d.metadata.lastLifeCountReceived = 368;
        for (int k = 360; k <= 368; ++k) d.oldMirror(S + k * 300 + 80, 100);
        d.metadata.update[0].streamstart = d.pollcount();
        const int inflated = d.pollcount();
        for (int i = 369; i < inflated; ++i)
            if (d.polls[i].g) d.polls[i] = {uint32_t(S + i * 300), i, 0, 0, 0};
        assert(d.pollcount() == inflated);
        d.repairDexcomMinuteSlots();
        assert(d.pollcount() == 369 && d.newest() == 368 && d.metadata.update[0].streamstart == 369);
        // Stopped between copying a reading and clearing it: no double, no loss.
        SensorGlucoseData half; half.metadata.starttime = S;
        for (int k = 300; k <= 365; ++k) half.driver(k, 200, 110);
        half.driver(367, 200, 120);
        half.metadata.lastLifeCountReceived = 365;
        half.oldMirror(S + 366 * 300 + 80, 99);
        const int copy = (366 * 300 + 80) / 60;
        half.polls[366] = {half.polls[copy].t, 366, 99, 0, 0};
        half.repairDexcomMinuteSlots();
        assert(half.pollcount() == 368 && half.polls[366].g == 99 && !half.polls[copy].g);
        // Stopped after the count was cut, before the rest of the metadata:
        SensorGlucoseData meta; meta.metadata.starttime = S;
        for (int k = 300; k <= 365; ++k) meta.driver(k, 200, 110);
        meta.driver(367, 200, 120);
        meta.metadata.pollstart = 300;
        meta.metadata.lastLifeCountReceived = 1850; meta.metadata.update[0].streamstart = 1851;
        meta.repairDexcomMinuteSlots();
        assert(meta.metadata.lastLifeCountReceived == 365 && meta.metadata.update[0].streamstart == 368);
    }
    {   // Padding the sensor's own readings overtook stays as empty slots, but
        // must not keep minute times: lookups by time need the stream in order.
        SensorGlucoseData d; d.metadata.starttime = S;
        for (int k = 300; k <= 310; ++k) d.driver(k, 200, 110);
        d.oldMirror(S + 310 * 300 + 80, 100);
        for (int k = 311; k <= 320; ++k) if (k != 315) d.driver(k, 200, 110);
        d.repairDexcomMinuteSlots();
        assert(d.pollcount() == 321 && !d.polls[315].g);
        for (int k = 301; k <= 320; ++k) assert(d.polls[k].t > d.polls[k - 1].t);
    }
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
        // The backup had sent up to the old tail: it sends from the moved slot again.
        assert(d.metadata.lastLifeCountReceived == 365 && d.metadata.update[0].streamstart == 366);
        d.metadata.update[0].streamstart = 369;   // sent since; a later load keeps it
        d.repairDexcomMinuteSlots();
        assert(d.metadata.update[0].streamstart == 369);
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
'''.replace('/* HELPERS */', helpers).replace('/* BACKFILL */', backfill)

with tempfile.TemporaryDirectory(prefix='dexcom-slot-repair-') as directory:
    directory = Path(directory)
    source, binary = directory / 'repair.cpp', directory / 'repair'
    source.write_text(fixture)
    subprocess.run(shlex.split(os.environ.get('CXX', 'c++')) + ['-std=c++20', '-Wall', str(source), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
