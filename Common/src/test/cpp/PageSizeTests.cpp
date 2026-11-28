// Native sensor records under different page sizes, on the production Sensoren and
// SensorGlucoseData with real files. tools/test-native-page-size.py runs each step in a process of
// its own, as after an app restart, and may change the page size between two steps, as after a
// restore on another phone.
#include "sensoren.hpp"

#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <string>

std::string_view globalbasedir;
Sensoren *sensors;
Settings *settings;
const char *gformat;
int gludecimal;
int showui;
void sendstartsensors(int) {}
void sendKAuth(SensorGlucoseData *) {}
void sendsiScan(SensorGlucoseData *) {}
void setstreaming(SensorGlucoseData *) {}
void resensordata(int) {}
void SensorGlucoseData::prunedata() {}

// Asked for by static initialisers too, before main().
extern "C" long sysconf(int name) {
  if (name == _SC_PAGESIZE)
    return std::atol(std::getenv("TEST_PAGE_SIZE"));
  return -1;
}
extern "C" int getpagesize() { return static_cast<int>(sysconf(_SC_PAGESIZE)); }

#define CHECK(condition)                                                       \
  do {                                                                         \
    if (!(condition)) {                                                        \
      std::fprintf(stderr, "%s:%d: %s [%s, %ld byte pages]\n", __FILE__,       \
                   __LINE__, #condition, what.c_str(), sysconf(_SC_PAGESIZE));              \
      std::exit(1);                                                            \
    }                                                                          \
  } while (0)

namespace {
namespace fs = std::filesystem;
constexpr uint32_t minute = 60, day = 24 * 60 * minute;
constexpr int scansTaken = 5;

struct Record {
  const char *name;
  unsigned family; // ManagedSensorUiFamily.nativeCode, 0 for a sensor native reads itself
  void (*create)(std::string_view dir, uint32_t start);
};

// A native record exists before its slot is added, as after a first scan. The managed drivers' shells
// do not: ensureDirectStreamShell makes them.
void libre2(std::string_view dir, uint32_t start) {
  const char uid[8] = {1, 2, 3, 4, 5, 6, 7, 8}, info[6] = {static_cast<char>(0x9d), 8, 0x30, 1, 0x5f, 0x44};
  const timevalues times{60, 14 * 24 * 60};
  SensorGlucoseData::mkdatabase(dir, start, uid, info, &times, 3, start, info);
}
void libre3(std::string_view dir, uint32_t start) {
  SensorGlucoseData::mkdatabase3(dir, start, 1, "00:11:22:33:44:55", 60, 14 * 24 * 60);
}
template <class Shape> void shaped(std::string_view dir, uint32_t start, Shape shape) {
  fs::create_directories(dir);
  const pathconcat info(dir, "info.dat");
  SensorGlucoseData::initInfoFile(info, 14, 3);
  auto stored = readtype<SensorGlucoseData::Info>(info);
  stored.starttime = start;
  shape(stored);
  writeall(info, &stored, sizeof(stored));
}
void dexcom(std::string_view dir, uint32_t start) {
  shaped(dir, start, [](auto &info) { info.dexcom = 1; info.days = 12; });
}
void accu(std::string_view dir, uint32_t start) {
  shaped(dir, start, [](auto &info) { info.accuChek = 1; });
}
void air(std::string_view dir, uint32_t start) {
  shaped(dir, start, [](auto &info) { info.air = 1; info.days = 16; });
}
void sibionics(std::string_view dir, uint32_t start) {
  shaped(dir, start, [](auto &info) { info.sibionics = 1; info.days = 45; });
}

const Record records[] = {
    {"E007-0M00LIBRE2X", 0, libre2}, {"E07A-XX0D1234ABC", 0, libre3},
    {"DEXCOM0000000001", 0, dexcom}, {"ACCU000000000001", 0, accu},
    {"AIR0000000000001", 0, air},    {"SIBIONICS0000001", 0, sibionics},
    {"MQ00000000000001", 1, nullptr}, {"X-AIDEX00001", 2, nullptr},
    {"ICAN000000000001", 3, nullptr}, {"ANY0000000000001", 4, nullptr},
    {"OTTAI00000000001", 5, nullptr}, {"SIBI:LT4125", 6, nullptr},
    {"NSF:follow", 7, nullptr},       {"MEDTRUM000000001", 8, nullptr},
    {"0D1234ABC", 9, nullptr},
};

// Readings of a step that ran `step` steps after the first.
int slotOf(int step, int i) { return 100 + step * 50 + i; }
int mgdlOf(int step, int i) { return 80 + step * 7 + i; }

void write(SensorGlucoseData *data, int step, uint32_t start, const std::string &what) {
  for (int i = 0; i < 10; ++i) {
    const int slot = slotOf(step, i);
    if (data->streaminterval() == 5)
      CHECK(data->savepollallIDs<300>(start + slot * 300, slot, mgdlOf(step, i), 3, 0.5f, 11 + i, 360));
    else
      CHECK(data->savepollallIDs<60>(start + slot * 60, slot, mgdlOf(step, i), 3, 0.5f, 11 + i, 360));
  }
  if (!data->canscan())
    return;
  uint8_t bytes[sizeof(nfcpatch)];
  std::fill_n(bytes, sizeof(bytes), uint8_t(0x21 + step));
  bytes[offsetof(nfcpatch, trendindex)] = bytes[offsetof(nfcpatch, historyindex)] = 0;
  nfcdata scan(bytes);
  for (int i = 0; i < scansTaken; ++i)
    data->saveglucose(&scan, start + (step * 100 + i) * minute, step * 100 + i, mgdlOf(step, i), 2, 0.25f);
}

void verify(SensorGlucoseData *data, int steps, uint32_t start, const std::string &what) {
  for (int step = 0; step < steps; ++step) {
    for (int i = 0; i < 10; ++i) {
      const int slot = slotOf(step, i);
      CHECK(data->hasStreamID(slot));
      const ScanData &poll = data->getPollsData()[slot];
      CHECK(poll.gettime() == start + slot * data->streaminterval() * 60);
      CHECK(poll.getmgdL() == mgdlOf(step, i) && poll.tr == 3);
      CHECK(data->getRawForPoll(&poll) == 11 + i);
      CHECK(data->getTempForPoll(slot) == 360);
    }
    if (!data->canscan())
      continue;
    for (int i = 0; i < scansTaken; ++i) {
      const int index = step * scansTaken + i;
      const ScanData *scan = data->getscan(index);
      CHECK(scan->id == step * 100 + i && scan->getmgdL() == mgdlOf(step, i));
      const uint8_t byte = uint8_t(0x21 + step);
      CHECK(data->gettrendsbuf(index)[3] == (uint16_t(byte | byte << 8) & 0x3fff));
    }
  }
  if (data->canscan())
    CHECK(data->scancount() == steps * scansTaken);
}

void layout(const fs::path &root) {
  for (const auto &entry : fs::recursive_directory_iterator(root))
    if (entry.is_regular_file())
      std::printf("%s %ju\n", entry.path().lexically_relative(root).c_str(), uintmax_t(entry.file_size()));
}
} // namespace

// <directory> create|reopen|repair|full|layout <start> <steps already written>
int main(int, char **argv) {
  const fs::path root = argv[1];
  const std::string step = argv[2];
  const uint32_t start = std::strtoul(argv[3], nullptr, 10);
  const int written = std::atoi(argv[4]);
  const std::string base = root.string();
  std::string what = step;
  if (step == "layout") {
    layout(root);
    return 0;
  }
  Settings stored(base, "settings.dat", "NL");
  settings = &stored;
  CHECK(settings->data());
  CHECK(Sensoren::create(base));
  Sensoren all{std::string_view(base)};
  sensors = &all;
  if (step == "create")
    CHECK(all.last() < 0);
  for (const auto &record : records) {
    what = step + " " + record.name;
    SensorGlucoseData *data;
    if (step == "create" && record.create) {
      record.create((root / record.name).string(), start);
      data = all.getSensorData(all.addsensor(std::string_view(record.name)));
    } else if (record.family) {
      // A driver asks for its shell on every connect.
      data = all.ensureDirectStreamShell(record.name, start, 32 * 24 * 60);
      CHECK(data);
      data->getinfo()->setManagedFamilyCode(record.family);
    } else {
      data = all.getSensorData(all.sensorindex(std::string_view(record.name)));
    }
    CHECK(data && !data->error());
    CHECK(data->canscan() == (record.family || record.create == libre2));
    CHECK(data->getstarttime() == start);
    if (record.create == libre3)
      CHECK(data->isLibre3());
    if (step == "full") {
      // Scans up to the end of the storage and past it: refused, and nothing written outside the files.
      if (data->canscan()) {
        uint8_t bytes[sizeof(nfcpatch)]{};
        nfcdata scan(bytes);
        for (int i = 0; i < 100000; ++i)
          data->saveglucose(&scan, start + i, i, 100, 2, 0.25f);
        CHECK(data->scancount() == 1024);
      }
      continue;
    }
    // Scan files that were never mapped hold nothing; scancount was never raised.
    if (step == "repair" && data->canscan()) {
      data->getinfo()->scancount = 0;
      for (int i = 0; i < 10; ++i)
        CHECK(data->hasStreamID(slotOf(0, i)));
      write(data, 1, start, what);
      CHECK(data->scancount() == scansTaken && data->getscan(0)->getmgdL() == mgdlOf(1, 0));
      continue;
    }
    verify(data, written, start, what);
    write(data, written, start, what);
    verify(data, written + 1, start, what);
  }
  return 0;
}
