/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+,              */
/*      Sibionics GS1Sb and Accu-Chek SmartGuide sensors.                            */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Tue Jan 06 12:49:00 CET 2026                                                 */

// CareSens Air, ported from upstream Juggluco 11.3 (air/java.cpp).
// Glucose comes from i-SENS' air1_opcal4_algorithm in libCALCULATION.so,
// which is not part of this repository and has to be packaged in jniLibs.
#if defined(SIBIONICS) && defined(DEXCOM)
#include <dlfcn.h>
#include <array>
#include <charconv>
#include <cmath>
#include <mutex>
#include <stdint.h>
#include <jni.h>
#include "logs.hpp"
#include "streamdata.hpp"
#include "fromjava.h"
#include "air.hpp"
#include "jniclass.hpp"
#include "glucose.hpp"
#include "datbackup.hpp"

extern void *openlib(std::string_view libname);

extern jlong mkres(SensorGlucoseData *sens, uint32_t timsec, uint32_t eventTime,
                   int min, int mgdL, int abbotttrend, float change);
extern int rate2changeindex(float rate);
typedef unsigned char (*air1_opcal4_algorithm_t)(
    air1_opcal4_device_info_t *, air1_opcal4_cgm_input_t *,
    air1_opcal4_cal_list_t *, air1_opcal4_arguments_t *, air1_opcal4_output_t *,
    air1_opcal4_debug_t *);
static air1_opcal4_algorithm_t air1_opcal4_algorithm;
static bool getlibfuncs() {
  // Several Air sensors can request the library concurrently. Cache success,
  // but let the next record retry a failed load or symbol lookup.
  static std::mutex mutex;
  const std::lock_guard lock(mutex);
  if (air1_opcal4_algorithm)
    return true;
  std::string_view libcal{"/libCALCULATION.so"};
  void *handle = openlib(libcal);
  if (!handle) {
    LOGGER("dlopen %s failed: %s\n", libcal.data(), dlerror());
    return false;
  }
  constexpr const char str[] = "air1_opcal4_algorithm";
  air1_opcal4_algorithm = (air1_opcal4_algorithm_t)dlsym(handle, str);
  if (!air1_opcal4_algorithm) {
    LOGGER("dlsym %s failed: %s\n", str, dlerror());
    dlclose(handle);
    return false;
  }
  return true;
}
bool airstream::setNumberNew(int nr) {
  LOGGER("airstream::setNumberNew(%s,%d)\n", hist->showsensorname().data(), nr);
  tmpiter = 0;
  tmptot = nr;
  ininfo = 0;
  return true;
}
extern "C" JNIEXPORT jint JNICALL fromjava(airGetLast)(JNIEnv *env, jclass cl,
                                                       jlong dataptr) {
  const airstream *sdata = reinterpret_cast<const airstream *>(dataptr);
  const SensorGlucoseData *sens = sdata->hist;
  if (!sens) {
    LOGAR("airGetLast SensorGlucoseData==null");
    return -1;
  }
  int res = sens->getLastAir();
  if (res > 0)
    res -= sens->getinfo()->askEarlier;
  LOGGER("airGetLast()=%d askEarlier=%d\n", res, sens->getinfo()->askEarlier);
  return res;
}

static void airsavehistory(SensorGlucoseData *sens,
                           const air1_opcal4_output_t &output) {
  const int lastid = output.seq_number_final;
  const uint32_t lasttime = output.measurement_time_standard;
  // The ids come from the vendor library; history is mapped for maxpos() slots.
  const int maxid = sens->maxpos();
  const auto inhistory = [maxid](int id) { return id > 0 && id < maxid; };

  int firstid = 0, endhistory = 0;
  for (int i = 0; i < 6; ++i) {
    const int id = output.smooth_seq[i];
    const double mgdL = output.smooth_result_glucose[i];
    if (inhistory(id) && mgdL > 0.0) {
      if (!firstid)
        firstid = id;
      const uint32_t time = (id - lastid) * 5 * 60 + lasttime;
      Glucose *item = sens->getglucose(id);
      item->time = time;
      const auto lifeCount = id * 5;
      item->id = lifeCount;
      const auto mgL = std::round(mgdL * 10.0);
      item->glu[1] = mgL;
#ifndef NOLOG
      time_t wastime = time;
      LOGGER("airsavehistory(%d,%d,%.1f) %s", id, lifeCount, mgL / convfactor,
             ctime(&wastime));
#endif
      endhistory = id;
    }
  }
  {
    const double mgdLdouble = output.result_glucose;
    if (inhistory(lastid) && mgdLdouble > 0.0) {
      const int mgL = std::round(mgdLdouble * 10.0);
      Glucose *item = sens->getglucose(lastid);
      item->time = lasttime;
      const auto lifeCount = lastid * 5;
      item->id = lifeCount;
      item->glu[1] = mgL;
#ifndef NOLOG
      time_t wastime = lasttime;
      LOGGER("airsavehistory(%d,%d,%.1f) %s", lastid, lifeCount,
             mgL / convfactor, ctime(&wastime));
#endif
      endhistory = lastid;
      if (!firstid)
        firstid = lastid;
    }
  }
  if (firstid) {
    if (sens->getstarthistory() <= 0) {
      int i = firstid - 1;
      for (; i > 0 && sens->getglucose(i)->id; --i)
        ;
      sens->setstarthistory(i + 1);
    }
    ++endhistory;
    if (endhistory > sens->getScanendhistory())
      sens->setendhistory(endhistory);
    sens->backhistory(firstid);
  };
}

template <typename T>
static char *printarray(char *ptr, char *end, const T *numbers, const int len) {
  for (int i = 0; i < (len - 1); ++i) {
    auto res = std::to_chars(ptr, end, numbers[i]);
    ptr = res.ptr;
    *ptr++ = ',';
    *ptr++ = ' ';
  };
  auto res = std::to_chars(ptr, end, numbers[len - 1]);
  *res.ptr = '\0';
  return res.ptr;
}
static void showm(const m *mptr) {
#ifndef NOLOG
  time_t time = mptr->measurement_time;
  constexpr int maxbuf = 400;
  char buf[maxbuf];
  char *endbuf = buf + maxbuf;
  int endpos =
      snprintf(buf, maxbuf, "m seq_num %d temperature %.1f unixtime=%lu: ",
               mptr->sequence_number, mptr->temperature, (unsigned long)time);
  const std::array<uint16_t, 30> glucose_array = mptr->glucose_array;
  char *end = printarray(buf + endpos, endbuf, glucose_array.data(), 30);
  *end++ = ' ';
  ctime_r(&time, end);
  LOGGERN(buf, end - buf + 24);
#endif
}
static jlong airProcessData(airstream *sdata, const jbyte *indata, int arlen,
                            jlong *timeres);
extern "C" JNIEXPORT jlong JNICALL fromjava(airProcessData)(
    JNIEnv *env, jclass cl, jlong dataptr, jbyteArray value,
    jlongArray jtimeres) {
  if (!value) {
    LOGAR("airProcessData value==null");
    return 1LL;
  }
  const auto arlen = env->GetArrayLength(value);
  if (arlen < 4) {
    LOGGER("airProcessData size  value %d < 4\n", arlen);
    return 1LL;
  }

  airstream *sdata = reinterpret_cast<airstream *>(dataptr);
  CritArSave<jlong> timeres(env, jtimeres);
  const CritAr bluedata(env, value);
  return airProcessData(sdata, bluedata.data(), arlen, timeres.data());
}
// Returns 0: no glucose (sensor error), 1: ignore, 2: disconnect,
// 3: ask the number of records, otherwise the glucoseback() result.
static jlong airProcessData(airstream *sdata, const jbyte *indata, int arlen,
                            jlong *timeres) {
  SensorGlucoseData *sens = sdata->hist;
  if (!sens) {
    LOGAR("airProcessData SensorGlucoseData==null");
    return 1LL;
  }
  const AirData *air = reinterpret_cast<const AirData *>(indata);
  if (air->reg1 != 1) {
    LOGGER("airProcessData second byte %d not 1\n", air->reg1);
    return 1LL;
  }
  const jlong msec = timeres[0];
  const uint32_t nowsec = msec / 1000LL;
  if (air->reg0 == 0xC4) {
    const int newrecords = air->numRecords;
    sdata->setNumberNew(newrecords);
    if (!newrecords) {
      if (const ScanData *last = sens->lastpoll()) {
        const int lastid = sens->getLastAir();
        const int dataid = last->getid() / 5;
        const int errortime = (lastid - dataid) * 5 * 60;
        const int waiting = nowsec - last->gettime() - errortime;
        LOGGER("getLastAir()=%d dataid=%d now=%u lastime=%u errortime=%d "
               "waiting=%d\n",
               lastid, dataid, nowsec, last->gettime(), errortime, waiting);
        if (waiting > 62 * 5) {
          ++sens->getinfo()->askEarlier;
          LOGGER("newrecords=0 set askEarlier=%d\n",
                 sens->getinfo()->askEarlier);
          return 2LL;
        }
      }
    }
    return 3LL;
  }
  if (air->reg0 != 0xC5) {
    LOGGER("airProcessData first byte %d not 197\n", air->reg0);
    return 1LL;
  }
  if (arlen < (int)sizeof(AirData)) {
    LOGGER("airProcessData size  value %d < AirData %d\n", arlen,
           (int)sizeof(AirData));
    return 1LL;
  }
  ++sdata->tmpiter;
  if (!air->deviceErrorCode) {
    if (!getlibfuncs()) {
      // Keep the last sequence number where it is: once the library is
      // installed, the transmitter resends these records.
      LOGAR("airProcessData: libCALCULATION.so missing, no glucose");
      sens->sensorerror = true;
      sens->sensorErrorTime = nowsec;
      return 0LL;
    }
    auto mtime = air->time;
    if (mtime < 31532400) {
      if (sdata->tmptot > 1)
        mtime = nowsec - (sdata->tmptot - sdata->tmpiter) * 300;
      else
        mtime = nowsec;
    }
    const int idnow = air->sequenceNumber;
    air_input &input = sdata->input;
    input = {.data = {.sequence_number = static_cast<uint16_t>(idnow),
                      .measurement_time = mtime,
                      .glucose_array = air->glucose_array,
                      .temperature = air->temperature / 100.0}};
    air1_opcal4_output_t &output = sdata->output;
    output = {};
    air1_opcal4_debug_t &debug = sdata->debug;
    debug = {};
    showm(&input.data);

    DeviceInfo3Obj *deviceInfo = sdata->sensorInfo.data();

    const unsigned char res = air1_opcal4_algorithm(
        reinterpret_cast<air1_opcal4_device_info_t *>(deviceInfo),
        &input.cgm_input, &input.empty, sdata->generated.data(), &output,
        &debug);
    const double mgdLdouble = output.result_glucose;

    if (res && !output.errcode) {
      const int mgdL = std::round(mgdLdouble);
      double trendrate = output.trendrate;
      if (trendrate > 99.0)
        trendrate = NAN;
      const int abbotttrend = rate2changeindex(trendrate);
      const int id = output.seq_number_final * 5;
      const uint32_t time = output.measurement_time_standard;
#ifndef NOLOG
      time_t tim = time;
      const char *label =
          abbotttrend < 6 ? GlucoseNow::trendString[abbotttrend] : "Error";
      LOGGER("airProcessData: nr=%d id=%d glucose=%.1f mg/dL %.1f mmol/L "
             "trendrate=%.2f %s (%d) %u  %s",
             output.seq_number_final, id, mgdLdouble, mgdLdouble / 18.0,
             trendrate, label, abbotttrend, time, ctime(&tim));
#endif
      if (mgdLdouble > 35.0 && mgdLdouble < 505.0) {
        auto res = mkres(sens, nowsec, time, id, mgdL, abbotttrend, trendrate);
        if (!res) {
          if (mgdL) {
            if (sens->getinfo()->askEarlier)
              --sens->getinfo()->askEarlier;

            LOGGER("set askEarlier=%d\n", sens->getinfo()->askEarlier);
            res = 2LL;
          }
        }
        airsavehistory(sens, output);
        timeres[0] = time * 1000LL;
        sens->setLastAir(output.seq_number_final);
        return res;
      }
    }
    sens->setLastAir(idnow);
    LOGGER("airProcessData: air1_opcal4_algorithm id=%d res=%d "
           "output.errcode=%d\n",
           idnow, (int)res, output.errcode);
  } else {
    LOGGER("airProcessData: air->deviceErrorCode=%d\n", air->deviceErrorCode);
  }
  sens->sensorerror = true;
  sens->sensorErrorTime = nowsec;
  return 0LL;
}
struct SensorInfo {
  uint8_t reg[2];
  uint8_t sensorVersion;
  float ycept;
  float slope100;
  float slope;
  float r2;
  float t90;
  float slope_ratio;
  char lot[10];
  char sensor_id[12];
  char expiration[6];
  uint16_t stabilizationInterval;
  uint16_t cgmDataInterval;
  uint16_t bleAdvInterval;
  uint8_t bleAdvDuration;
  uint8_t age;
  uint16_t allowedList;
  float maxGlucose;
  float minGlucose;
  uint8_t mCLibraryVersion;
} __attribute__((packed));

extern "C" JNIEXPORT jboolean JNICALL fromjava(airSaveSensorInfo)(
    JNIEnv *env, jclass cl, jlong dataptr, jbyteArray value) {
  if (!value) {
    LOGAR("airSaveSensorInfo value==null");
    return false;
  }
  airstream *sdata = reinterpret_cast<airstream *>(dataptr);
  SensorGlucoseData *sens = sdata->hist;
  if (!sens) {
    LOGAR("airSaveSensorInfo SensorGlucoseData==null");
    return false;
  }
  const auto arlen = env->GetArrayLength(value);
  if (arlen < (int)sizeof(SensorInfo)) {
    LOGGER("airSaveSensorInfo size  value %d < %zd\n", arlen,
           sizeof(SensorInfo));
    return false;
  }
  const CritAr bluedata(env, value);
  const SensorInfo *air = reinterpret_cast<const SensorInfo *>(bluedata.data());
  if (air->reg[0] != 0xC2) {
    LOGGER("airSaveSensorInfo first byte not 0xC2, but %d\n", air->reg[0]);
    return false;
  }
  if (air->reg[1] != 1) {
    LOGGER("airSaveSensorInfo second byte %d not 1\n", air->reg[1]);
    return false;
  }
  if (air->mCLibraryVersion < 2) {
    LOGAR("airSaveSensorInfo cLibraryVersion<2");
    sdata->ininfo = 0;
    return true;
  }
  LOGGER("airSaveSensorInfo lot %.10s minGlucose=%f maxGlucose=%f\n", air->lot,
         (double)air->minGlucose, (double)air->maxGlucose);
  auto *sensorinfo = sdata->sensorInfo.data();
  memcpy(sensorinfo, bluedata.data() + 2, sizeof(SensorInfo) - 2);
  sensorinfo->stabilizationInterval = 1800;
  sdata->ininfo = 72;
  return true;
}
extern "C" JNIEXPORT jboolean JNICALL fromjava(airSaveSensorInfo2)(
    JNIEnv *env, jclass cl, jlong dataptr, jbyteArray value) {
  if (!value) {
    LOGAR("airSaveSensorInfo2 value==null");
    return false;
  }
  airstream *sdata = reinterpret_cast<airstream *>(dataptr);
  SensorGlucoseData *sens = sdata->hist;
  if (!sens) {
    LOGAR("airSaveSensorInfo2 SensorGlucoseData==null");
    return false;
  }
  const auto arlen = env->GetArrayLength(value);
  const CritAr bluedata(env, value);
  const uint8_t *data = reinterpret_cast<const uint8_t *>(bluedata.data());
  if (arlen < 2 || data[0] != 0xC2) {
    LOGAR("airSaveSensorInfo2 first byte not 0xC2");
    return false;
  }
  if (data[1] != 2) {
    LOGGER("airSaveSensorInfo2 second byte %d not 2\n", data[1]);
    return false;
  }
  auto *sensorinfo = sdata->sensorInfo.data();
  const int ininfo = sdata->ininfo;
  int cplen;
  if (sensorinfo->cLibraryVersion >= 2) {
    cplen = ininfo == 72 ? 157 : 205;
  } else {
    cplen = ininfo ? 125 : 202;
  }
  // The transmitter decides how much it sends; never copy past the packet
  // or past the record.
  cplen = std::min(cplen, arlen - 2);
  cplen = std::min<int>(cplen, (int)sizeof(DeviceInfo3Obj) - ininfo);
  if (cplen <= 0) {
    LOGGER("airSaveSensorInfo2 ininfo=%d nothing to copy\n", ininfo);
    return false;
  }
  LOGGER("airSaveSensorInfo2 ininfo=%d len=%d\n", ininfo, cplen);
  memcpy(reinterpret_cast<uint8_t *>(sensorinfo) + ininfo, bluedata.data() + 2,
         cplen);
  sdata->ininfo += cplen;
  return true;
}

extern "C" JNIEXPORT void JNICALL fromjava(airSaveStartSensor)(
    JNIEnv *env, jclass cl, jlong dataptr, jfloat eapp, jfloat vref,
    jint elapsedSecs) {
  airstream *sdata = reinterpret_cast<airstream *>(dataptr);
  SensorGlucoseData *sens = sdata->hist;
  if (!sens) {
    LOGAR("airSaveStartSensor SensorGlucoseData==null");
    return;
  }
  DeviceInfo3Obj *sensorinfo = sdata->sensorInfo.data();
  const uint32_t sensorstart = time(nullptr) - elapsedSecs;
  sensorinfo->sensor_start_time = sensorstart;
  const uint32_t wasstart = sens->getinfo()->starttime;
  sens->getinfo()->starttime = sensorstart;
  sensorinfo->eapp = eapp;
  sensorinfo->vref = vref;
#ifndef NOLOG
  time_t time = sensorstart;
  LOGGER("airSaveStartSensor eapp=%f vref=%f elapsedSeconds=%d starttime=%u %s",
         eapp, vref, elapsedSecs, sensorstart, ctime(&time));
#endif
  if (abs((int)(wasstart - sensorstart)) > 30) {
    const int sensorindex = sens->sensorIndex;
    sensors->getsensor(sensorindex)->starttime = sensorstart;
    sensors->setindices();
    backup->resendResetDevices(&updateone::sendstream);
  }
}
extern "C" JNIEXPORT jbyteArray JNICALL fromjava(airGetPin)(JNIEnv *env,
                                                           jclass cl,
                                                           jlong dataptr) {
  airstream *sdata = reinterpret_cast<airstream *>(dataptr);
  SensorGlucoseData *sens = sdata->hist;
  if (!sens) {
    LOGAR("airGetPin SensorGlucoseData==null");
    return nullptr;
  }
  constexpr const int pinlen = sizeof(careSenseAirScan_t::pinCode);
  jbyteArray uit = env->NewByteArray(pinlen);
  env->SetByteArrayRegion(
      uit, 0, pinlen,
      reinterpret_cast<const jbyte *>(sens->getinfo()->airData.pinCode));
  LOGGER("airGetPin(%p)\n", (void *)dataptr);
  return uit;
}

#endif
