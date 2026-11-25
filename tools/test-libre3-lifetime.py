#!/usr/bin/env python3
"""Run the native lifetime rules for a managed Libre 3 across day 14 and day 15 (host C++20 compiler).

Compiles the production bodies of getmaxtime(), officialendtime(), expectedEndTime(), the roster's
maxtime(), the Clone stream walk (Sensoren::update), the retire check (Sensoren::checkinfo) and
setSensorWearDays over stub records. A 15-day Plus must keep its full wear everywhere, and a sensor
still sending just past its rated end must not be retired or dropped from Clone.
"""

import os
from pathlib import Path
import subprocess
import tempfile

CPP = Path(__file__).resolve().parents[1] / "Common/src/main/cpp"


def body(source, marker):
    begin = source.index(marker)
    end = source.index("{", begin) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[begin:end]


def main():
    header = (CPP / "SensorGlucoseData.hpp").read_text()
    roster = (CPP / "sensoren.hpp").read_text()
    glue = (CPP / "g.cpp").read_text()
    lifetime = "\n".join(body(header, marker) for marker in (
        "  int getweardurationMIN() const", "  int getweardurationSEC() const",
        "  uint32_t officialendtime() const", "  int expectedWearDuration() const",
        "  uint32_t expectedEndTime() const", "  uint32_t getmaxtime() const"))
    entry = "\n".join(body(roster, marker) for marker in (
        "  uint32_t wearduration() const", "  uint32_t maxtime() const"))
    walks = "\n".join((
        body(roster, "  template <typename F> bool checkinfo("),
        body(roster, "  int update(crypt_t *pass, Connect *connect, const int ind, int &firstsensor,\n             int otheralso")))
    wear = body(glue, "if (SensorGlucoseData *hist = ensureDirectStreamShellForId(str, 0)) {\n    if (!hist->error()) {\n      if (auto *info = hist->getinfo()) {\n        info->wearduration2")
    code = (TEST.replace("@LIFETIME@", lifetime).replace("@ENTRY@", entry)
            .replace("@WALKS@", walks).replace("@WEAR@", wear))
    with tempfile.TemporaryDirectory(prefix="juggluco-libre3-lifetime-") as temporary:
        source = Path(temporary) / "lifetime.cpp"
        source.write_text(code)
        executable = Path(temporary) / "lifetime"
        subprocess.run([os.environ.get("CXX", "c++"), "-std=c++20", "-O1", "-g", "-Wall",
                        str(source), "-o", str(executable)], check=True)
        return subprocess.run([str(executable)], timeout=60).returncode


TEST = r"""
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <ctime>
#include <vector>
#define LOGGER(...) ((void)0)
using jint = int;
constexpr int maxdays = 46, maxdaysAccu = 15, stdMaxDaysSI = 24, maxSIhours = 24 * 24;
static uint32_t clockNow;
static time_t fakeTime(void *) { return clockNow; }
#define time fakeTime
struct crypt_t {};
struct Connect {};
struct Info {
  uint32_t starttime; uint8_t days; uint16_t wearduration, wearduration2;
  bool notchinese = false, useCustomCalibration = false;
};
struct SensorGlucoseData {
  Info info;
  int sensorIndex = 0, kind = 9;  // 9: managed Libre 3 shell, 0: another managed shell
  uint32_t last = 0;
  int sends = 0;
  const Info *getinfo() const { return &info; }
  Info *getinfo() { return &info; }
  bool error() const { return false; }
  bool isLibre2() const { return false; }
  bool isLibre3() const { return false; }
  bool isDexcom() const { return false; }
  bool isAccuChek() const { return false; }
  bool isAir() const { return false; }
  bool isAiDex() const { return false; }
  bool isSibionics() const { return false; }
  bool isSibionics1() const { return false; }
  int siSubtype() const { return 0; }
  bool isManagedLibre3() const { return kind == 9; }
  bool isInResetMode() const { return false; }
  bool hasData(uint32_t) const { return false; }
  bool hasSensorError(uint32_t) const { return false; }
  uint32_t lastused() const { return last; }
  uint32_t getstarttime() const { return info.starttime; }
  int send(crypt_t *, Connect *, int, int, int) { ++sends; return 2; }
@LIFETIME@
};
struct sensor {
  uint32_t starttime = 0, endtime = 0;
  uint8_t halfdays = 0, finished = 0, present = 0;
  char name[17] = "0D1234ABC";
  void markFinished() { finished = 1; }
@ENTRY@
};
struct Sensoren {
  std::vector<SensorGlucoseData> data;
  std::vector<sensor> entries;
  sensor *sensorlist() { return entries.data(); }
  sensor *getsensor(int i) { return &entries[i]; }
  SensorGlucoseData *getSensorData(int i) { return &data[i]; }
  int last() const { return static_cast<int>(entries.size()) - 1; }
  template <class T> void alignMirrorRemoteBase(T *, int) {}
@WALKS@
};
static Sensoren *sensors;
static SensorGlucoseData *ensureDirectStreamShellForId(const char *str, int) {
  return sensors->getSensorData(str[0] - '0');
}
static void setSensorWearDays(const char *str, int days) {
@WEAR@
}

int main() {
  constexpr uint32_t start = 1795000000u, day = 24 * 60 * 60, minute = 60;
  for (int kind : {9, 0}) {
    for (int wear : {14, 15}) {
      Sensoren all;
      sensors = &all;
      // As ensureDirectStreamShell leaves a new shell: 14 days of geometry, no roster lifetime.
      all.data.push_back({.info = {.starttime = start, .days = 14, .wearduration = 0, .wearduration2 = 0}, .kind = kind});
      all.entries.push_back({.starttime = start});
      setSensorWearDays("0", wear);
      auto &hist = all.data[0];
      auto &entry = all.entries[0];
      assert(hist.officialendtime() == start + wear * day);
      assert(entry.halfdays < stdMaxDaysSI * 2);  // never read as a Sibionics
      const auto clone = [&](uint32_t at) {
        clockNow = at;
        entry.finished = 0;
        hist.sends = 0;
        int first = 0;
        all.update(nullptr, nullptr, 0, first, 1, &SensorGlucoseData::send);
        return hist.sends == 1;
      };
      const auto retired = [&](uint32_t at) {
        clockNow = at;
        entry.finished = 0;
        hist.last = at - minute;
        all.checkinfo(0, at, [](const SensorGlucoseData *) { return false; });
        return entry.finished != 0;
      };
      if (kind != 9) {
        // Other shells keep what they had.
        assert(entry.halfdays == 0);
        assert(hist.getmaxtime() == start + wear * day);
        assert(hist.expectedEndTime() == start + wear * day + 12 * 60 * minute);
        continue;
      }
      // Every minute around the two boundaries, and the old roster cut at 14.5 days.
      for (uint32_t edge : {14 * day, 14 * day + day / 2, 15 * day}) {
        if (edge > static_cast<uint32_t>(wear) * day) continue;
        for (uint32_t at = start + edge - 5 * minute; at <= start + edge + 5 * minute; at += minute) {
          assert(clone(at));
          assert(!retired(at));
        }
      }
      // The last minutes arrive after the rated end; a sensor gone for a day is retired.
      assert(hist.expectedEndTime() == hist.officialendtime());
      assert(!retired(hist.officialendtime() + day - minute));
      assert(clone(hist.officialendtime() + day - minute));
      assert(retired(hist.officialendtime() + day + minute));
    }
  }
  std::puts("managed Libre 3 lifetime: ok");
  return 0;
}
"""

if __name__ == "__main__":
    raise SystemExit(main())
