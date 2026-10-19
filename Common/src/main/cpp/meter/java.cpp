#include "fromjava.h"
#include "jniclass.hpp"
#include "settings/settings.hpp"
#include "share/hexstr.hpp"
#include <cmath>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <time.h>

struct getfrom {
  uint8_t reg[3] = {1, 3, 1};
  uint16_t index;
} __attribute__((packed));

auto getfromcmd(uint16_t index) {
  const getfrom from{.index = index};
  return from;
}

static auto getcaretime(time_t tim) {
  struct tm tmbuf;
  localtime_r(&tim, &tmbuf);
  int16_t year = tmbuf.tm_year + 1900;
  int8_t mon = tmbuf.tm_mon + 1;
  int8_t day = tmbuf.tm_mday;
  int8_t hour = tmbuf.tm_hour;
  int8_t min = tmbuf.tm_min;
  int8_t sec = tmbuf.tm_sec;
  struct {
    uint8_t reg[4]{0xC0, 0x03, 0x01, 0x00};
    int16_t year;
    int8_t month;
    int8_t day;
    int8_t hour;
    int8_t min;
    int8_t sec;
  } __attribute__((packed)) uit{.year = year,
                                .month = mon,
                                .day = day,
                                .hour = hour,
                                .min = min,
                                .sec = sec};
  return uit;
}

extern "C" JNIEXPORT jbyteArray JNICALL fromjava(careSenseTimeCMD)(JNIEnv *env,
                                                                   jclass cl) {
  const auto cmd = getcaretime(time(nullptr));
#ifndef NOLOG
  {
    const hexstr cmdhex(cmd);
    LOGGER("careSenseTimeCMD()=%s\n", cmdhex.str());
  }
#endif
  constexpr const int len = sizeof(cmd);
  jbyteArray uit = env->NewByteArray(len);
  env->SetByteArrayRegion(uit, 0, len, reinterpret_cast<const jbyte *>(&cmd));
  return uit;
}
#include "MeterData.hpp"
#include "nums/numdata.hpp"
extern Numdata *getherenums();

extern void addCalibration(uint32_t tim, int type, Num *num,
                           const Numdata *numdata);
extern void setnumchanged(uint32_t tim);
struct MeterSaveResult {
  uint32_t timestamp{};
  int mgdlTenths{};
  bool stored{};
  bool recentLegacyValue{};
};

static MeterSaveResult storeDecodedGlucoseMeter(GlucoseMeter *meter,
                                                jint meterIndex,
                                                uint32_t timestamp,
                                                float mgdL) {
  if (!meter) {
    LOGGER("GlucoseMeterSave no meter at %d\n", meterIndex);
    return {};
  }
  if (!std::isfinite(mgdL) || mgdL <= 0.0f) {
    LOGGER("GlucoseMeterSave invalid glucose %.2f\n", mgdL);
    return {};
  }
  MeterSaveResult result;
  if (timestamp <= meter->lastTime)
    return result;

  result.timestamp = timestamp;
  result.mgdlTenths = std::lround(mgdL * 10.0f);
  result.stored = true;

  const auto bloodvar = settings->data()->bloodvar;
  if (bloodvar < maxvarnr) {
    float displayValue;
    if (settings->data()->unit == 1) {
      displayValue = std::round(convertmultmmol * 100.0f * mgdL) * .1f;
    } else {
      displayValue = std::roundf(mgdL);
    }
    Numdata *numda = getherenums();
    if (Num *num = numda->numsaveonly(timestamp, displayValue, bloodvar, 0)) {
      uint32_t now = time(nullptr);
      if (abs((int)(now - timestamp)) < 60) {
        addCalibration(timestamp, bloodvar, num, numda);
        if (backup)
          backup->wakebackup(Backup::wakenums);
        setnumchanged(now);
        result.recentLegacyValue = true;
      }
    }
  } else {
    LOGAR("GlucoseMeterSave: no legacy blood glucose label set; journal only");
  }
  meter->lastTime = timestamp;
  return result;
}

static MeterSaveResult saveGlucoseMeter(JNIEnv *env, jint meterIndex,
                                        jbyteArray value) {
  const auto arlen = env->GetArrayLength(value);
  if (arlen < 14) {
    LOGGER("GlucoseMeterSave length (%d) <14\n", arlen);
    return {};
  }
  const CritAr bluedata(env, value);
  const MeterData *glu = reinterpret_cast<const MeterData *>(bluedata.data());
  GlucoseMeter *meter = settings->data()->getGlucoseMeter(meterIndex);
  if (!meter) {
    LOGGER("GlucoseMeterSave no meter at %d\n", meterIndex);
    return {};
  }
  const auto timeoffset = glu->getTimeoffset();
  const uint32_t timeorig = glu->time.getLocaltime();
  const uint32_t timcorrected = timeorig + meter->timeoffset + timeoffset * 60;
  auto mgdL = glu->getmgdL();
  if (!std::isfinite(mgdL) || mgdL <= 0.0f) {
    LOGGER("GlucoseMeterSave invalid glucose %.2f\n", mgdL);
    return {};
  }
#ifndef NOLOG
  char timebuf1[27], timebuf2[27];
  time_t ort = timeorig, cort = timcorrected;
  LOGGER("GlucoseMeterSave index=%d	%.2f	mg/dL	%.1f	mmol/L	"
         "	timeOffsetPresent=%d	contextInfoFollows=%d	"
         "typeAndLocationPresent=%d orig=%.24s genoffset=%d specoffset=%d "
         "corrected=%s",
         glu->index, mgdL, mgdL / 18.0, glu->timeOffsetPresent,
         glu->contextInfoFollows, glu->typeAndLocationPresent,
         ctime_r(&ort, timebuf1), meter->timeoffset, timeoffset,
         ctime_r(&cort, timebuf2));
#endif
  MeterSaveResult result =
      storeDecodedGlucoseMeter(meter, meterIndex, timcorrected, mgdL);
  meter->nextIndex = glu->index + 1;
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(GlucoseMeterSave)(
    JNIEnv *env, jclass cl, jint meterIndex, jbyteArray value) {
  return saveGlucoseMeter(env, meterIndex, value).recentLegacyValue;
}

extern "C" JNIEXPORT jlongArray JNICALL fromjava(GlucoseMeterSaveResult)(
    JNIEnv *env, jclass cl, jint meterIndex, jbyteArray value) {
  const auto result = saveGlucoseMeter(env, meterIndex, value);
  if (!result.stored)
    return nullptr;
  const jlong data[]{static_cast<jlong>(result.timestamp) * 1000LL,
                     static_cast<jlong>(result.mgdlTenths),
                     result.recentLegacyValue ? 1LL : 0LL};
  jlongArray out = env->NewLongArray(3);
  if (out)
    env->SetLongArrayRegion(out, 0, 3, data);
  return out;
}

extern "C" JNIEXPORT jlongArray JNICALL fromjava(GlucoseMeterSaveDecodedResult)(
    JNIEnv *env, jclass cl, jint meterIndex, jlong timestampMillis,
    jint mgdlTenths) {
  const auto timestampSeconds = timestampMillis / 1000LL;
  const auto now = static_cast<jlong>(time(nullptr));
  if (timestampMillis <= 0 || timestampSeconds > static_cast<jlong>(UINT32_MAX) ||
      timestampSeconds > now + 24LL * 60LL * 60LL ||
      mgdlTenths < 200 || mgdlTenths > 7000) {
    LOGAR("GlucoseMeterSaveDecodedResult invalid input");
    return nullptr;
  }
  GlucoseMeter *meter = settings->data()->getGlucoseMeter(meterIndex);
  const auto result = storeDecodedGlucoseMeter(
      meter, meterIndex, static_cast<uint32_t>(timestampSeconds),
      static_cast<float>(mgdlTenths) / 10.0f);
  if (!result.stored)
    return nullptr;
  const jlong data[]{static_cast<jlong>(result.timestamp) * 1000LL,
                     static_cast<jlong>(result.mgdlTenths),
                     result.recentLegacyValue ? 1LL : 0LL};
  jlongArray out = env->NewLongArray(3);
  if (out)
    env->SetLongArrayRegion(out, 0, 3, data);
  return out;
}
#include "Context.hpp"
extern "C" JNIEXPORT jboolean JNICALL fromjava(GlucoseMeterProcessContext)(
    JNIEnv *env, jclass cl, jint meterIndex, jbyteArray value) {
  const auto arlen = env->GetArrayLength(value);
  if (arlen < 3) {
    LOGGER("GlucoseMeterSaveContext length (%d) <14\n", arlen);
    return false;
  }
  const CritAr bluedata(env, value);
  const Context *con = reinterpret_cast<const Context *>(bluedata.data());
#ifndef NOLOG
  const hexstr conhex((uint8_t *)bluedata.data(), arlen);
  LOGGER("GlucoseMeterSaveContext index=%d  %s\n", con->index, conhex.str());
#endif
  return true;
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(GlucoseMeterSaveTime)(
    JNIEnv *env, jclass cl, jint meterIndex, jbyteArray value) {
  const auto arlen = env->GetArrayLength(value);
  if (arlen < 7) {
    LOGGER("GlucoseMeterSaveTime length (%d) <7\n", arlen);
    return false;
  }
  GlucoseMeter *meter = settings->data()->getGlucoseMeter(meterIndex);
  if (!meter) {
    LOGGER("GlucoseMeterSaveTime no meter at %d\n", meterIndex);
    return false;
  }
  const CritAr bluedata(env, value);
  const time_t metertime =
      reinterpret_cast<const MeterTime *>(bluedata.data())->getLocaltime();
  const time_t now = time(nullptr);
  int offset = now - metertime;
  meter->timeoffset = offset;
#ifndef NOLOG
  char timebuf[27];
  LOGGER("GlucoseMeterSaveTime offset%d now: %zu metertime: %zu %s\n", offset,
         now, metertime, ctime_r(&metertime, timebuf));
#endif
  return true;
}
extern "C" JNIEXPORT jbyteArray JNICALL
fromjava(getGlucoseMeterNewCMD)(JNIEnv *env, jclass cl, jint meterIndex) {
  GlucoseMeter *meter = settings->data()->getGlucoseMeter(meterIndex);
  if (!meter) {
    LOGGER("getGlucoseMeterNewCMD no meter at %d\n", meterIndex);
    return nullptr;
  }
  const auto nextIndex = meter->nextIndex;
  LOGGER("getGlucoseMeterNewCMD %d\n", nextIndex);
  auto cmd = getfromcmd(nextIndex);
  constexpr const int len = sizeof(cmd);
  jbyteArray uit = env->NewByteArray(len);
  env->SetByteArrayRegion(uit, 0, len, reinterpret_cast<const jbyte *>(&cmd));
  return uit;
}
//
// Contour7802H6195553
extern int newGlucoseMeter(std::string_view scangegs);
int newGlucoseMeter(std::string_view scangegs) {
  const int len = scangegs.size();
  if (len != 12) {
    LOGGER("newGlucoseMeter wrongsize %d %s\n", len, scangegs.data());
    return -1;
  }
  if (!settings->data()->bloodvar) {
    return -2;
  }
  std::string uit;
  uit.reserve(19);
  uit.append("Contour");
  uit.append(scangegs);
  if (GlucoseMeter *meter = settings->data()->giveGlucoseMeter(uit.data())) {
    meter->active = true;
    int meterIndex = meter - settings->data()->glucosemeters;
    LOGGER("newGlucoseMeter %s index=%d\n", uit.data(), meterIndex);
    return meterIndex;
  } else {
    return -1;
  }
}
// meter: onCharacteristicChanged 00002a52-0000-1000-8000-00805f9b34fb 06 00 01
// 01 meter: onCharacteristicChanged 00002a52-0000-1000-8000-00805f9b34fb 06 00
// 01 06

extern void calibrateLast();
extern "C" JNIEXPORT jboolean JNICALL fromjava(recordCharacteristicChanged)(
    JNIEnv *env, jclass cl, jbyteArray value) {
  const auto arlen = env->GetArrayLength(value);
  if (arlen < 4) {
    LOGGER("Meter: recordCharacteristicChanged length (%d) <4\n", arlen);
    return false;
  }
  const CritAr bluedata(env, value);
  const uint8_t *data = (const uint8_t *)bluedata.data();
  const uint8_t reg[] = {6, 0, 1};
  if (memcmp(data, reg, sizeof(reg))) {
    LOGAR("Meter: recordCharacteristicChanged unrecognised unput");
    return false;
  }
  switch (data[3]) {
  case 1: {
    LOGAR("Meter recordCharacteristicChanged received data");
    calibrateLast();
    return true;
  }
  case 6: {
    LOGAR("Meter recordCharacteristicChanged received NO data");
  }; break;
  default:
    LOGGER("Meter recordCharacteristicChanged unrecognised byte %d\n", data[3]);
  }
  return false;
}

extern "C" JNIEXPORT jint JNICALL
fromjava(GlucoseMeterGetLastPos)(JNIEnv *env, jclass cl, jint meterIndex) {
  if (const GlucoseMeter *meter =
          settings->data()->getGlucoseMeter(meterIndex)) {
    return meter->nextIndex;
  }
  return -1;
}
extern "C" JNIEXPORT jboolean JNICALL fromjava(GlucoseMeterSetLastPos)(
    JNIEnv *env, jclass cl, jint meterIndex, jint newpos) {
  if (GlucoseMeter *meter = settings->data()->getGlucoseMeter(meterIndex)) {
    meter->nextIndex = newpos;
    return true;
  }
  return false;
}

extern "C" JNIEXPORT jint JNICALL
fromjava(GlucoseMeterGetIndex)(JNIEnv *env, jclass cl, jstring jdeviceName) {
  if (jdeviceName == nullptr)
    return -1;
  const char *deviceName_chars = env->GetStringUTFChars(jdeviceName, nullptr);
  if (deviceName_chars == nullptr)
    return -1;
  std::string_view deviceName(deviceName_chars);
  const GlucoseMeter *meter = settings->data()->giveGlucoseMeter(deviceName);
  env->ReleaseStringUTFChars(jdeviceName, deviceName_chars);
  if (meter) {
    int meterIndex = meter - settings->data()->glucosemeters;
    return meterIndex;
  }
  return -1;
}
extern "C" JNIEXPORT jint JNICALL
fromjava(GlucoseMeterHasIndex)(JNIEnv *env, jclass cl, jstring jdeviceName) {
  if (jdeviceName == nullptr)
    return -1;
  const char *deviceName_chars = env->GetStringUTFChars(jdeviceName, nullptr);
  if (deviceName_chars == nullptr)
    return -1;
  std::string_view deviceName(deviceName_chars);
  const GlucoseMeter *meter = settings->data()->getGlucoseMeter(deviceName);
  env->ReleaseStringUTFChars(jdeviceName, deviceName_chars);
  if (meter) {
    int meterIndex = meter - settings->data()->glucosemeters;
    return meterIndex;
  }
  return -1;
}

extern "C" JNIEXPORT jintArray JNICALL
fromjava(getActiveGlucoseMeters)(JNIEnv *env, jclass cl) {
  const uint32_t nr = settings->data()->glucoseMeterNR;
  jint meters[nr];
  int used = 0;
  for (int i = 0; i < nr; i++) {
    if (settings->data()->glucosemeters[i].active) {
      meters[used++] = i;
    }
  }
  jintArray uit = env->NewIntArray(used);
  env->SetIntArrayRegion(uit, 0, used, meters);
  return uit;
}

extern "C" JNIEXPORT jstring JNICALL
fromjava(GlucoseMeterDeviceName)(JNIEnv *env, jclass cl, jint meterIndex) {
  if (GlucoseMeter *meter = settings->data()->getGlucoseMeter(meterIndex)) {
    return env->NewStringUTF(meter->deviceName);
  }
  return nullptr;
}
extern "C" JNIEXPORT jlong JNICALL
fromjava(GlucoseMeterGetLastTime)(JNIEnv *env, jclass cl, jint meterIndex) {
  if (const GlucoseMeter *meter =
          settings->data()->getGlucoseMeter(meterIndex)) {
#ifndef NOLOG
    time_t tim = meter->lastTime;
    LOGGER("GlucoseMeterGetLastTime %u %s", meter->lastTime, ctime(&tim));
#endif
    return meter->lastTime * 1000LL;
  }
  return 0LL;
}
extern "C" JNIEXPORT void JNICALL fromjava(GlucoseMeterSetLastTime)(
    JNIEnv *env, jclass cl, jint meterIndex, jlong lasttime) {
  if (GlucoseMeter *meter = settings->data()->getGlucoseMeter(meterIndex)) {
    meter->nextIndex = 0;
    meter->active = true;
    meter->lastTime = lasttime / 1000;
#ifndef NOLOG
    time_t tim = meter->lastTime;
    LOGGER("GlucoseMeterSetLastTime %u %s", meter->lastTime, ctime(&tim));
#endif
  }
}
extern "C" JNIEXPORT jboolean JNICALL fromjava(GlucoseMeterSetActive)(
    JNIEnv *env, jclass cl, jint meterIndex, jboolean active) {
  if (GlucoseMeter *meter = settings->data()->getGlucoseMeter(meterIndex)) {
    if (meter->active != active) {
      meter->active = active;
      return true;
    }
  }
  return false;
}
extern "C" JNIEXPORT jboolean JNICALL
fromjava(GlucoseMeterGetActive)(JNIEnv *env, jclass cl, jint meterIndex) {
  if (const GlucoseMeter *meter =
          settings->data()->getGlucoseMeter(meterIndex)) {
    return meter->active;
  }
  return false;
}
#include <format>
std::string deviceAddressString(const uint8_t *address) {
  return std::format("{:02X}:{:02X}:{:02X}:{:02X}:{:02X}:{:02X}", address[0],
                     address[1], address[2], address[3], address[4],
                     address[5]);
}
extern "C" JNIEXPORT jstring JNICALL
fromjava(GlucoseMeterDeviceAddress)(JNIEnv *env, jclass cl, jint meterIndex) {
  if (const GlucoseMeter *meter =
          settings->data()->getGlucoseMeter(meterIndex)) {
    const uint8_t *address = meter->deviceAddress;
    if (*reinterpret_cast<const int32_t *>(address) ||
        *reinterpret_cast<const int16_t *>(address + 4))
      return env->NewStringUTF(deviceAddressString(address).data());
  }
  return nullptr;
}
void deviceAddressBytes(const char *address, uint8_t *uit) {
  sscanf(address, "%hhX:%hhX:%hhX:%hhX:%hhX:%hhX", uit, uit + 1, uit + 2,
         uit + 3, uit + 4, uit + 5);
}
extern "C" JNIEXPORT jboolean JNICALL fromjava(GlucoseMeterSetDeviceAddress)(
    JNIEnv *env, jclass cl, jint meterIndex, jstring jaddress) {
  if (GlucoseMeter *meter = settings->data()->getGlucoseMeter(meterIndex)) {
    if (jaddress) {
      const char *in = env->GetStringUTFChars(jaddress, NULL);
      if (in != nullptr) {
        uint8_t *uit = meter->deviceAddress;
        deviceAddressBytes(in, uit);
        env->ReleaseStringUTFChars(jaddress, in);
        return true;
      }
    }
    uint8_t *address = meter->deviceAddress;
    std::fill_n(address, 6, 0);
    return true;
  }
  return false;
}

extern "C" JNIEXPORT jint JNICALL fromjava(GlucoseMeterCount)(JNIEnv *env,
                                                              jclass cl) {
  return settings->data()->glucoseMeterNR;
}

extern bool hasGlucoseMeters();
bool hasGlucoseMeters() {
  const int nr = settings->data()->glucoseMeterNR;
  for (int i = 0; i < nr; ++i) {
    if (settings->data()->glucosemeters[i].active) {
      return true;
    }
  }
  return false;
}
