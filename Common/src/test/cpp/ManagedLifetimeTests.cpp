// Lifetime of a managed Libre 3 across day 14 and day 15, on the production Sensoren and
// SensorGlucoseData: real headers, real files, nothing re-implemented. Run by
// tools/test-libre3-lifetime.py, which also builds it for the phone's 4 KB page size.
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
constexpr uint32_t minute = 60, day = 24 * 60 * minute;

int sent;
// Counts the records Clone's live walk reaches. It touches no member, so it may
// stand in for updatestream(), which needs a peer.
struct Probe : SensorGlucoseData {
  int count(crypt_t *, Connect *, int, int, int) {
    ++sent;
    return 2;
  }
};

struct Shell {
  std::filesystem::path dir;
  Sensoren all;
  SensorGlucoseData *hist = nullptr;

  Shell(const std::filesystem::path &root, int number)
      : dir(make(root, number)), all(keep(dir.string())) {
    sensors = &all;
  }
  // As Libre3BleManager.ensureNativeWindow opens it.
  void open(const char *name, uint32_t start, unsigned family, int wearDays) {
    hist = all.ensureDirectStreamShell(name, start, 32 * 24 * 60);
    if (hist && family)
      hist->getinfo()->setManagedFamilyCode(family);
    if (hist && wearDays)
      all.setWearDays(name, wearDays);
  }
  sensor *entry() { return all.getsensor(hist->sensorIndex); }
  bool cloneReaches() {
    sent = 0;
    int first = 0;
    all.update(nullptr, nullptr, 0, first, 1,
               static_cast<int (SensorGlucoseData::*)(crypt_t *, Connect *, int, int, int)>(&Probe::count));
    return sent == 1;
  }
  bool retiredAt(uint32_t now) {
    entry()->finished = 0;
    all.checkinfo(hist->sensorIndex, now, [](const SensorGlucoseData *) { return false; });
    return entry()->finished != 0;
  }
  static std::filesystem::path make(const std::filesystem::path &root, int number) {
    auto dir = root / std::to_string(number);
    std::filesystem::create_directories(dir);
    return dir;
  }
  // Sensoren keeps a view of its base directory.
  static std::string_view keep(std::string path) { return *new std::string(std::move(path)); }
};
} // namespace

int main(int, char **argv) {
  const std::filesystem::path root = argv[1];
  const uint32_t now = static_cast<uint32_t>(time(nullptr));
  int number = 0;
  for (int wear : {14, 15}) {
    for (uint32_t edge : {14 * day, 14 * day + day / 2, 15 * day}) {
      for (int offset = -3; offset <= 3; ++offset) {
        const uint32_t age = edge + offset * static_cast<int>(minute);
        const std::string what = std::to_string(wear) + " days rated, minute " + std::to_string(age / minute);
        Shell shell(root, number++);
        shell.open("0D1234ABC", now - age, SensorGlucoseData::managedLibre3Family, wear);
        CHECK(shell.hist);
        CHECK(shell.hist->getstarttime() == now - age);
        // Native calls the shell a Libre 2 by elimination; its wear must not be read as one.
        CHECK(shell.hist->isLibre2());
        CHECK(shell.hist->getweardurationMIN() == wear * 24 * 60);
        CHECK(shell.hist->officialendtime() == now - age + wear * day);
        CHECK(shell.hist->expectedEndTime() == shell.hist->officialendtime());
        // Never read as a Sibionics.
        CHECK(!shell.all.isSibionics(shell.hist->sensorIndex));
        // The sensor decides when it stops; it has a day past its rated wear.
        if (age == wear * day + day)
          continue; // the clock moves while this runs
        const bool within = age < wear * day + day;
        CHECK(shell.cloneReaches() == within);
        CHECK(shell.retiredAt(now) == !within);
      }
    }
  }
  {
    const std::string what = "wear not reported yet";
    Shell shell(root, number++);
    shell.open("0D1234ABC", now - 13 * day, SensorGlucoseData::managedLibre3Family, 0);
    CHECK(shell.hist->getweardurationMIN() == 14 * 24 * 60);
    CHECK(shell.cloneReaches());
    CHECK(!shell.retiredAt(now));
  }
  {
    const std::string what = "reopened with the stored wear";
    const int same = number++;
    { Shell shell(root, same); shell.open("0D1234ABC", now - 14 * day - minute, SensorGlucoseData::managedLibre3Family, 15); }
    Shell shell(root, same);
    shell.open("0D1234ABC", now - 14 * day - minute, 0, 0);
    CHECK(shell.hist->isManagedLibre3());
    CHECK(shell.hist->officialendtime() == shell.hist->getstarttime() + 15 * day);
    CHECK(shell.hist->pollStorageCapacity() >= 32 * 24 * 60);
    CHECK(shell.cloneReaches());
    CHECK(!shell.retiredAt(now));
  }
  std::puts("managed Libre 3 lifetime: ok");
  return 0;
}
