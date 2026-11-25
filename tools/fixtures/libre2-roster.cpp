// Built by tools/test-libre2-processing.py: what the app itself ends when a Libre 2 passes its wear.
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <string_view>
#include <vector>

#define CHECK(c, ...) do { if(!(c)) { std::fprintf(stderr, __VA_ARGS__); std::fputc('\n', stderr); return 1; } } while(0)
#define LOGGER(...) ((void)0)
#define LOGSTRING(...) ((void)0)
static time_t clock_now;
static time_t fake_time(time_t *) { return clock_now; }
#define time fake_time

constexpr int sensornamelen = 16, stdMaxDaysSI = 24, maxSIhours = 24 * 24, maxdaysAccu = 14, maxdays = 45;
constexpr int interval5 = 5 * 60, sensorFinished = 1, sensorRemovedByUser = 2;
struct sensorname_t {};
struct crypt_t {};
struct Connect {};
namespace dexcom { constexpr int wearMinutes(int d) { return d * 1440; } }
/* LIST_ENTRY */

struct Info {
    uint32_t starttime = 0;
    uint16_t interval = 900, wearduration = 0, wearduration2 = 0;
    uint8_t days = 15;
    bool useCustomCalibration = false;
    struct { signed char data[6]; } info{};
};
struct SensorGlucoseData {
    Info inf;
    bool aidex = false;
    int fed = 0;
    Info *getinfo() { return &inf; }
    const Info *getinfo() const { return &inf; }
    bool isAccuChek() const { return false; }
    bool isSibionics() const { return false; }
    bool isSibionics1() const { return false; }
    bool isDexcom() const { return false; }
    bool isAir() const { return false; }
    bool isAiDex() const { return aidex; }
    bool isInResetMode() const { return false; }
    bool hasData(uint32_t) const { return false; }
    bool error() const { return false; }
    uint32_t getstarttime() const { return inf.starttime; }
    uint32_t lastused() const { return lastPacketTime; }
    int send(crypt_t *, Connect *, int, int, int) { ++fed; return 1; }
/* SENSOR_METHODS */
/* WEAR_METHODS */
};
struct Roster {
    std::vector<sensor> entries;
    std::vector<SensorGlucoseData> data;
    sensor *sensorlist() { return entries.data(); }
    int last() const { return int(entries.size()) - 1; }
    SensorGlucoseData *getSensorData(int i) { return &data[i]; }
    void alignMirrorRemoteBase(SensorGlucoseData *, int) {}
/* ROSTER_METHODS */
};

constexpr uint32_t start = 1700000000, day = 86400;
static Roster roster(uint16_t wear, uint8_t days, uint8_t halfdays, bool nfc = true) {
    Roster r;
    r.entries.resize(1);
    r.data.resize(1);
    r.entries[0] = {};
    r.entries[0].starttime = start;
    r.entries[0].halfdays = halfdays;
    r.data[0].inf.starttime = start;
    r.data[0].inf.wearduration = wear;
    r.data[0].inf.days = days;
    if(nfc) r.data[0].inf.info.data[2] = 0x39;
    return r;
}
static bool finished(Roster r, uint32_t at, uint32_t packet) {
    r.data[0].lastPacketTime = packet;
    r.checkinfo(0, at, [](const SensorGlucoseData *) { return false; });
    return r.entries[0].finished;
}
static bool fed(Roster r, uint32_t at) {
    clock_now = at;
    int first = 0;
    Roster::coverLibre2Grace(r.entries[0], r.data[0]);
    r.update(nullptr, nullptr, 0, first, 0, &SensorGlucoseData::send);
    return r.data[0].fed;
}
int main() {
    // Records as mkdatabase makes them, a plus sensor whose list entry was set to 14.5 days, and a
    // record whose storage geometry does not cover the extra day.
    const struct { uint16_t wear; uint8_t days, halfdays; } cases[]{
        {14 * 1440, 15, 29}, {15 * 1440, 16, 31}, {15 * 1440, 16, 29}, {10 * 1440, 11, 21}, {21 * 1440, 22, 43}, {14 * 1440, 14, 29}};
    for(const auto &c : cases) {
        const auto r = roster(c.wear, c.days, c.halfdays);
        const uint32_t nominal = start + c.wear * 60u;
        CHECK(r.data[0].officialendtime() == nominal, "the official end moved");
        CHECK(r.data[0].getmaxtime() >= nominal + day, "%u min: retired before the extra day is over", c.wear);
        for(uint32_t at : {nominal - 1, nominal, nominal + 1, nominal + day / 2, nominal + day / 2 + 1, nominal + day}) {
            CHECK(!finished(r, at, 0), "%u min: finished %d s after the nominal end", c.wear, int(at - nominal));
            CHECK(fed(r, at), "%u min: other devices no longer fed %d s after the nominal end", c.wear, int(at - nominal));
        }
        // Afterwards the sensor is over, unless its frames still arrive.
        const uint32_t over = std::max(nominal + day, r.data[0].getmaxtime()) + 1;
        CHECK(finished(r, over, 0) && finished(r, over, over - 600), "a silent sensor was kept");
        CHECK(!finished(r, over, over - 60), "a sensor still sending was retired");
    }
    // A managed driver's shell is Libre 2 by elimination only and keeps its own lifetime.
    const auto shell = roster(14 * 1440, 14, 28, false);
    CHECK(shell.data[0].getmaxtime() == start + 14 * day, "a managed shell got the extra day");
    CHECK(finished(shell, start + 14 * day + 1, start + 14 * day), "a managed shell was kept");
    auto entry = shell.entries[0];
    Roster::coverLibre2Grace(entry, shell.data[0]);
    CHECK(entry.halfdays == 28, "a managed shell's list entry changed");
    // Values from 24 days mean Sibionics in the list; a 30-day sensor never gets one from here.
    auto long_wear = roster(30 * 1440, 31, 0);
    Roster::coverLibre2Grace(long_wear.entries[0], long_wear.data[0]);
    CHECK(long_wear.entries[0].halfdays == 0, "a Libre 2 was made to look like Sibionics");
    std::puts("PASS: Libre 2 retirement and the feed to other devices at the nominal and extended wear");
}
