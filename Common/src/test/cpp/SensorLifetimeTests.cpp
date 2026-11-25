// How long native treats each kind of sensor as running: Clone's live stream, retiring, and the ends
// it reports. Drives the production Sensoren and SensorGlucoseData on real files; nothing about how a
// sensor is classified or stored is re-implemented here. Run by tools/test-sensor-lifetime.py.
#include "sensoren.hpp"

#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <string>

std::string_view globalbasedir;
Sensoren *sensors;
void sendstartsensors(int) {}
void SensorGlucoseData::prunedata() {}
#ifdef __APPLE__
// Records are laid out in the phone's 4 KB pages.
extern "C" long sysconf(int) { return 4096; }
#endif

#define CHECK(condition)                                                       \
  do {                                                                         \
    if (!(condition)) {                                                        \
      std::fprintf(stderr, "%s:%d: %s [%s]\n", __FILE__, __LINE__, #condition, \
                   what.c_str());                                              \
      std::exit(1);                                                            \
    }                                                                          \
  } while (0)

namespace {
constexpr uint32_t minute = 60, hour = 60 * minute, day = 24 * hour;

std::vector<int> walked;
// Notes the records Clone's live walk reaches. It touches no member, so it may
// stand in for updatestream(), which needs a peer.
struct Probe : SensorGlucoseData {
  int note(crypt_t *, Connect *, int, int index, int) {
    walked.push_back(index);
    return 2;
  }
};

struct Records {
  Sensoren all;
  explicit Records(const std::filesystem::path &root, int number)
      : all(directory(root, number)) {
    sensors = &all;
  }
  SensorGlucoseData *shell(const char *name, uint32_t start, int capacityDays = 32) {
    auto *data = all.ensureDirectStreamShell(name, start, capacityDays * 24 * 60);
    if (data)
      data->getinfo()->lastscantime = start; // opened when the sensor started
    return data;
  }
  std::vector<int> cloneWalks() {
    walked.clear();
    int first = 0;
    all.update(nullptr, nullptr, 0, first, 1,
               static_cast<int (SensorGlucoseData::*)(crypt_t *, Connect *, int, int, int)>(&Probe::note));
    return walked;
  }
  bool cloneReaches(int index) {
    const auto reached = cloneWalks();
    return std::find(reached.begin(), reached.end(), index) != reached.end();
  }
  bool retires(int index, uint32_t now) {
    all.getsensor(index)->finished = 0;
    all.checkinfo(index, now, [](const SensorGlucoseData *) { return false; });
    const bool retired = all.getsensor(index)->finished != 0;
    all.getsensor(index)->finished = 0;
    return retired;
  }
  // Sensoren keeps a view of its base directory.
  static std::string_view directory(const std::filesystem::path &root, int number) {
    const auto dir = root / std::to_string(number);
    std::filesystem::create_directories(dir);
    return *new std::string(dir.string());
  }
};

void reading(SensorGlucoseData *data, uint32_t at) {
  const uint32_t interval = data->streaminterval() * minute;
  const int slot = static_cast<int>((at - data->getstarttime()) / interval);
  if (interval == minute)
    data->savepollallIDs<60>(at, slot, 100, 0, 0.0f);
  else
    data->savepollallIDs<300>(at, slot, 100, 0, 0.0f);
}
} // namespace

int main(int, char **argv) {
  const std::filesystem::path root = argv[1];
  const uint32_t now = static_cast<uint32_t>(time(nullptr));
  int number = 0;

  // ManagedSensorUiFamily.nativeCode: MQ, AiDex, iCan, Anytime, Ottai, Sibionics, follower, Medtrum,
  // and one that does not exist yet.
  struct Family { const char *name; unsigned code; } families[] = {
      {"MQ0001", 1}, {"X-AIDEX00001", 2}, {"ICAN0001", 3}, {"ANY0001", 4}, {"OTTAI0001", 5},
      {"SIBI:LT4125", 6}, {"NSF:follow", 7}, {"MT00001", 8}, {"NEW0001", 12}};
  for (const auto &family : families) {
    for (int wear : {14, 15, 16, 21, 28, 30}) {
      const uint32_t ages[] = {14 * day - hour, 14 * day + 11 * hour, 14 * day + 13 * hour, wear * day - 2 * minute};
      for (uint32_t age : ages) {
        if (age >= wear * day)
          continue;
        const std::string what = std::string(family.name) + " rated " + std::to_string(wear) + " days at hour " + std::to_string(age / hour);
        Records records(root, number++);
        auto *data = records.shell(family.name, now - age);
        CHECK(data && data->isDriverShell());
        data->getinfo()->setManagedFamilyCode(family.code);
        records.all.setWearDays(family.name, wear);
        const int index = data->sensorIndex;
        CHECK(!records.all.isSibionics(index));
        CHECK(!records.all.needsScan(index));
        CHECK(data->getweardurationMIN() == wear * 24 * 60);
        CHECK(data->officialendtime() == data->getstarttime() + wear * day);
        // Silent or not, a sensor inside its wear is sent and kept.
        CHECK(records.cloneReaches(index));
        CHECK(!records.retires(index, now));
        // As activity: the newest reading is found although the list says 14.5 days.
        reading(data, now - minute);
        CHECK(records.all.lastpolltime().first == index);
      }
      const std::string what = std::string(family.name) + " rated " + std::to_string(wear) + " days, past its end";
      Records records(root, number++);
      auto *data = records.shell(family.name, now - wear * day - 3 * hour, 40);
      CHECK(data);
      data->getinfo()->setManagedFamilyCode(family.code);
      records.all.setWearDays(family.name, wear);
      const int index = data->sensorIndex;
      // Still sending: nobody but the sensor ends it.
      reading(data, now - 5 * minute);
      CHECK(records.cloneReaches(index));
      CHECK(!records.retires(index, now));
      // Silent for two hours: retired. Clone's walk stops with the same rule.
      CHECK(records.retires(index, now + 3 * hour));
    }
  }
  {
    const std::string what = "a reading stamped just ahead of the clock";
    Records records(root, number++);
    auto *data = records.shell("OTTAI0001", now - 15 * day, 40);
    records.all.setWearDays("OTTAI0001", 14);
    reading(data, now + 30);
    CHECK(!records.retires(data->sensorIndex, now));
  }
  {
    const std::string what = "wear never reported";
    Records records(root, number++);
    auto *data = records.shell("MQ0001", now - 13 * day);
    // The field still holds the identity length it shares storage with.
    CHECK(data->getinfo()->wearduration2 < 24 * 60);
    CHECK(data->getweardurationMIN() == 14 * 24 * 60);
    CHECK(records.cloneReaches(data->sensorIndex));
    CHECK(!records.retires(data->sensorIndex, now));
  }
  {
    const std::string what = "an ended short sensor beside an older long one";
    Records records(root, number++);
    auto *ottai = records.shell("OTTAI0001", now - 26 * day, 40);
    records.all.setWearDays("OTTAI0001", 28);
    reading(ottai, now - minute);
    auto *dexcom = records.shell("DEXCOM01", now - 20 * day, 40);
    dexcom->getinfo()->dexcom = 1;
    dexcom->getinfo()->days = 12;
    records.all.getsensor(dexcom->sensorIndex)->halfdays = 24;
    CHECK(records.cloneWalks() == std::vector<int>{ottai->sensorIndex});
    CHECK(records.all.lastpolltime().first == ottai->sensorIndex);
  }
  {
    const std::string what = "removed by the user";
    Records records(root, number++);
    auto *data = records.shell("OTTAI0001", now - 5 * day);
    reading(data, now - minute);
    records.all.getsensor(data->sensorIndex)->finished = sensorRemovedByUser;
    CHECK(!records.cloneReaches(data->sensorIndex));
  }

  // The sensors native decodes itself keep the lives they had: sent until the later of the list's
  // and the record's end, and retired there although they hold a fresh reading.
  struct Native { const char *name; int halfdays; int lifeDays; void (*shape)(SensorGlucoseData::Info *); } natives[] = {
      {"E007-LIBRE2", 29, 15, [](SensorGlucoseData::Info *info) { info->info.data[0] = static_cast<signed char>(0x9d); info->days = 15; }},
      {"E07A-XXLIBRE3", 28, 15, [](SensorGlucoseData::Info *info) { info->interval = SensorGlucoseData::interval5; info->days = 15; info->wearduration2 = 14 * 24 * 60; }},
      {"E07A-XXLIBRE3P", 30, 16, [](SensorGlucoseData::Info *info) { info->interval = SensorGlucoseData::interval5; info->days = 16; info->wearduration2 = 15 * 24 * 60; }},
      {"DEXCOM10", 24, 12, [](SensorGlucoseData::Info *info) { info->dexcom = 1; info->days = 12; info->wearduration2 = 0; }},
      {"DEXCOM15", 34, 17, [](SensorGlucoseData::Info *info) { info->dexcom = 1; info->days = 17; info->wearduration2 = 0; }},
      {"ACCU", 30, 15, [](SensorGlucoseData::Info *info) { info->accuChek = 1; info->wearduration2 = 0; }},
      {"AIR", 32, 16, [](SensorGlucoseData::Info *info) { info->air = 1; info->days = 16; info->wearduration2 = 0; }},
  };
  for (const auto &native : natives) {
    const std::string what = native.name;
    for (int beyond : {-1, 1}) {
      Records records(root, number++);
      auto *data = records.shell(native.name, now - native.lifeDays * day + (beyond < 0 ? hour : -static_cast<int>(hour)), 40);
      CHECK(data);
      native.shape(data->getinfo());
      records.all.getsensor(data->sensorIndex)->halfdays = native.halfdays;
      CHECK(!data->isDriverShell());
      CHECK(!records.all.isSibionics(data->sensorIndex));
      CHECK(records.cloneReaches(data->sensorIndex) == (beyond < 0));
      CHECK(records.retires(data->sensorIndex, now) == (beyond > 0));
      reading(data, now - minute);
      CHECK(records.retires(data->sensorIndex, now) == (beyond > 0));
    }
  }
  {
    const std::string what = "native Sibionics";
    Records records(root, number++);
    auto *data = records.shell("SIBIONICS01", now - 20 * day, 40);
    data->getinfo()->sibionics = 1;
    data->getinfo()->days = stdMaxDaysSI;
    records.all.getsensor(data->sensorIndex)->halfdays = maxdaysSI * 2;
    CHECK(records.all.isSibionics(data->sensorIndex));
    CHECK(records.cloneReaches(data->sensorIndex));
    CHECK(!records.retires(data->sensorIndex, now));
  }
  std::puts("sensor lifetime: ok");
  return 0;
}
