#!/usr/bin/env python3
"""Run the production Glucose Profile record and clock decoders (meter/java.cpp) natively.

JNI, settings and the legacy number store are stand-ins; the packed MeterData /
MeterTime layouts and the save bodies are the production ones. Discussion #787:
a CareSens meter that reads the standard Current Time and sends Time Offset only
when it changes.
"""
from pathlib import Path
import os
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / 'Common/src/main/cpp'
source = (CPP / 'meter/java.cpp').read_text()


def block(signature):
    masked = re.sub(r'"(?:\\.|[^"\\])*"|//[^\n]*', lambda m: ' ' * len(m.group()), source)
    start = source.index(signature)
    end = masked.index('{', start) + 1
    depth = 1
    while depth:
        depth += (masked[end] == '{') - (masked[end] == '}')
        end += 1
    return source[start:end]


offsets = source[source.index('// GLS 3.1.1.4/3.4.3.4'):source.index('static MeterSaveResult saveGlucoseMeter(')]
production = '\n'.join([
    source[source.index('struct MeterSaveResult {'):source.index('static MeterSaveResult storeDecodedGlucoseMeter(')],
    block('static MeterSaveResult storeDecodedGlucoseMeter('),
    offsets,
    block('static MeterSaveResult saveGlucoseMeter('),
    block('extern "C" JNIEXPORT jboolean JNICALL fromjava(GlucoseMeterSaveTime)('),
])

code = r'''
#include <atomic>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <limits>
#include <vector>
#define LOGGER(...) ((void)0)
#define LOGAR(...) ((void)0)
#define JNIEXPORT
#define JNICALL
#define fromjava(name) name
#include "settings/GlucoseMeter.hpp"
#include "meter/MeterData.hpp"
using jint = int32_t;
using jboolean = bool;
using jclass = void *;
struct ByteArray { std::vector<uint8_t> bytes; };
using jbyteArray = ByteArray *;
struct JNIEnv { int GetArrayLength(jbyteArray a) { return (int)a->bytes.size(); } };
struct CritAr {
  std::vector<uint8_t> copy;
  CritAr(JNIEnv *, jbyteArray a) : copy(a->bytes) { copy.resize(32); }  // slack, as a JNI pin gives none
  const uint8_t *data() const { return copy.data(); }
};
constexpr int maxvarnr = 10;
constexpr float convertmultmmol = 1 / 18.0f;
struct Num {};
struct Numdata { Num *numsaveonly(uint32_t, float, int, int) { return nullptr; } };
Numdata *getherenums() { return nullptr; }
void addCalibration(uint32_t, int, Num *, const Numdata *) {}
void setnumchanged(uint32_t) {}
struct Backup { enum { wakenums }; void wakebackup(int) {} } *backup = nullptr;
struct Data {
  int bloodvar = maxvarnr, unit = 0;
  GlucoseMeter meters[maxglucosemeters]{};
  GlucoseMeter *getGlucoseMeter(int i) { return i >= 0 && i < maxglucosemeters ? &meters[i] : nullptr; }
};
struct Settings { Data d; Data *data() { return &d; } } settingsObject, *settings = &settingsObject;
''' + production + r'''
static void check(bool ok, const char *message) { if (!ok) { fprintf(stderr, "FAIL: %s\n", message); exit(1); } }

static ByteArray clock(int year, int month, int day, int hour, int minute, int second) {
  return {{(uint8_t)year, (uint8_t)(year >> 8), (uint8_t)month, (uint8_t)day, (uint8_t)hour,
           (uint8_t)minute, (uint8_t)second, 4, 0, 0}};
}
// Glucose Measurement: flags, sequence, base time, [offset], concentration (kg/L SFLOAT) + type/location.
static ByteArray record(int index, struct tm when, bool withOffset, int16_t offset, int mgdl) {
  ByteArray r;
  r.bytes = {(uint8_t)((withOffset ? 1 : 0) | 2), (uint8_t)index, (uint8_t)(index >> 8)};
  const int year = when.tm_year + 1900;
  for (int b : {year & 0xff, year >> 8, when.tm_mon + 1, when.tm_mday, when.tm_hour, when.tm_min, when.tm_sec})
    r.bytes.push_back((uint8_t)b);
  if (withOffset) { r.bytes.push_back((uint8_t)offset); r.bytes.push_back((uint8_t)(offset >> 8)); }
  const uint16_t sfloat = (uint16_t)((0xB << 12) | mgdl);  // mgdl * 10^-5 kg/L
  r.bytes.push_back((uint8_t)sfloat); r.bytes.push_back((uint8_t)(sfloat >> 8));
  r.bytes.push_back(0x11);
  return r;
}
static struct tm localAt(time_t t) { struct tm m; localtime_r(&t, &m); return m; }

int main() {
  JNIEnv env;
  const int index = 2;
  GlucoseMeter *meter = settings->data()->getGlucoseMeter(index);
  const time_t now = time(nullptr);
  const time_t first = now - 3 * 3600, second = now - 2 * 3600, third = now - 3600;

  // A valid Current Time read sets the correction.
  auto good = clock(localAt(now).tm_year + 1900, localAt(now).tm_mon + 1, localAt(now).tm_mday,
                    localAt(now).tm_hour, localAt(now).tm_min, localAt(now).tm_sec);
  check(GlucoseMeterSaveTime(&env, nullptr, index, &good), "valid Current Time refused");
  check(std::abs(meter->timeoffset) <= 2, "valid Current Time gave a correction");

  // Unknown month / day (0), or a date off the calendar, must not change it.
  meter->timeoffset = 0;
  for (auto bad : {clock(2026, 0, 20, 11, 47, 0), clock(2026, 11, 0, 11, 47, 0), clock(0, 11, 20, 11, 47, 0),
                   clock(2026, 2, 31, 11, 47, 0), clock(2026, 11, 20, 24, 0, 0)}) {
    check(!GlucoseMeterSaveTime(&env, nullptr, index, &bad), "unknown clock accepted");
    check(meter->timeoffset == 0, "unknown clock changed the correction");
  }
  auto leap = clock(2028, 2, 29, 12, 0, 0);
  check(GlucoseMeterSaveTime(&env, nullptr, index, &leap), "29 February of a leap year refused");
  meter->timeoffset = 0;

  // Records 1-3: the offset (+30 min) only in the first; 2 and 3 are 13 bytes.
  auto r1 = record(1, localAt(first), true, 30, 120);
  auto r2 = record(2, localAt(second), false, 0, 130);
  auto r3 = record(3, localAt(third), false, 0, 140);
  check(r1.bytes.size() == 15 && r2.bytes.size() == 13, "fixture lengths");
  auto s1 = saveGlucoseMeter(&env, index, &r1);
  auto s2 = saveGlucoseMeter(&env, index, &r2);
  auto s3 = saveGlucoseMeter(&env, index, &r3);
  check(s1.stored && s2.stored && s3.stored, "13-byte record dropped");
  check(s1.timestamp == first + 1800 && s2.timestamp == second + 1800 && s3.timestamp == third + 1800,
        "omitted Time Offset not carried over from the first record");
  check(s2.mgdlTenths == 1300, "13-byte record decoded wrong");
  check(meter->nextIndex == 4, "cursor");

  // A new report starts with its own offset.
  auto r4 = record(4, localAt(now - 600), true, 0, 150);
  check(saveGlucoseMeter(&env, index, &r4).timestamp == now - 600, "new reported offset ignored");

  // Truncated records are refused without moving the cursor; one without a value or a
  // real base time is skipped and the cursor moves past it.
  auto truncated = record(5, localAt(now - 300), true, 0, 150);
  truncated.bytes.resize(14);
  check(!saveGlucoseMeter(&env, index, &truncated).stored && meter->nextIndex == 5, "truncated record");
  auto noValue = record(5, localAt(now - 300), false, 0, 150);
  noValue.bytes[0] = 0; noValue.bytes.resize(10);
  check(!saveGlucoseMeter(&env, index, &noValue).stored && meter->nextIndex == 6, "record without value");
  auto offCalendar = record(6, localAt(now - 200), false, 0, 150);
  offCalendar.bytes[5] = 13;  // mktime would make it next January, ahead of every later record
  const auto lastTime = meter->lastTime;
  check(!saveGlucoseMeter(&env, index, &offCalendar).stored && meter->lastTime == lastTime && meter->nextIndex == 7,
        "record off the calendar stored or moved lastTime");
  auto r7 = record(7, localAt(now - 100), false, 0, 160);
  check(saveGlucoseMeter(&env, index, &r7).stored, "record after an off-calendar one dropped");

  // A skipped record (no value, or off the calendar) that announces a new offset still
  // sets it for the records that omit it.
  auto skippedOffset = record(8, localAt(now - 90), true, -15, 150);
  skippedOffset.bytes[0] = 1; skippedOffset.bytes.resize(12);  // offset, no concentration
  check(!saveGlucoseMeter(&env, index, &skippedOffset).stored && meter->nextIndex == 9, "valueless record with offset");
  auto r9 = record(9, localAt(now - 80 + 900), false, 0, 150);
  auto s9 = saveGlucoseMeter(&env, index, &r9);
  check(s9.stored && s9.timestamp == now - 80, "offset from a valueless record not carried");
  auto undatedOffset = record(10, localAt(now - 70), true, 10, 150);
  undatedOffset.bytes[5] = 0;  // month unknown
  check(!saveGlucoseMeter(&env, index, &undatedOffset).stored && meter->nextIndex == 11, "undated record with offset");
  auto r11 = record(11, localAt(now - 60 - 600), false, 0, 150);
  auto s11 = saveGlucoseMeter(&env, index, &r11);
  check(s11.stored && s11.timestamp == now - 60, "offset from an undated record not carried");
  puts("PASS: clock validation, 13-byte records, carried Time Offset, truncated/valueless/undated records, offsets from skipped records");
}
'''

with tempfile.TemporaryDirectory(prefix='meter-records-') as directory:
    directory = Path(directory)
    (directory / 'test.cpp').write_text(code)
    cxx = os.environ.get('CXX', 'c++')
    subprocess.run([cxx, '-std=c++20', '-Wno-c99-designator', f'-I{CPP}', '-o', str(directory / 'test'),
                    str(directory / 'test.cpp')], check=True)
    subprocess.run([str(directory / 'test')], check=True)
