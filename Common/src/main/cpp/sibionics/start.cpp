/*      This file is part of Juggluco, an Android app to receive and display */
/*      glucose values from Freestyle Libre 2 and 3 sensors. */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com> */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify */
/*      it under the terms of the GNU General Public License as published */
/*      by the Free Software Foundation, either version 3 of the License, or */
/*      (at your option) any later version. */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. */
/*      See the GNU General Public License for more details. */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>. */
/*                                                                                   */
/*      Thu Apr 04 20:14:31 CEST 2024 */

#ifdef SIBIONICS

#include "config.h"
// #define SI3ONLY
// #define SIHISTORY
#include "SensorGlucoseData.hpp"
#include "datbackup.hpp"
#include "destruct.hpp"
#include "fromjava.h"
#include "jniclass.hpp"
#include "jnisubin.hpp"
#include "sibionics/AlgorithmContext.hpp"
#include "streamdata.hpp"
#include <atomic>
#include <dlfcn.h>
#include <mutex>
#include <string_view>
extern void sendstreaming(SensorGlucoseData *hist);

extern bool siInit();

static std::atomic<uint32_t> gSiNativeFreshRestartOp{0};
static std::atomic<uint32_t> gSiRebindOp{0};
static std::atomic<uint32_t> gSiRestoreOp{0};

extern "C" JNIEXPORT jstring JNICALL
fromjava(getSiBluetoothNum)(JNIEnv *envin, jclass cl, jlong dataptr) {
  if (!dataptr)
    return nullptr;
  const SensorGlucoseData *usedhist =
      reinterpret_cast<streamdata *>(dataptr)->hist;
  if (!usedhist)
    return nullptr;
  if (!usedhist->isSibionics())
    return nullptr;
  const char *name = usedhist->getinfo()->siBlueToothNum;
  LOGGER("getSiBluetoothNum()=%s\n", name);
  return envin->NewStringUTF(name);
}

extern int newGlucoseMeter(std::string_view scangegs);
extern "C" JNIEXPORT jstring JNICALL fromjava(addSIscangetName)(
    JNIEnv *env, jclass cl, jstring jgegs, jintArray jindexptr) {
  if (!jgegs) {
    LOGAR("addSIscangetName(null)");
    return nullptr;
  }
  const char *gegs = env->GetStringUTFChars(jgegs, NULL);
  if (!gegs) {
    LOGAR("addSIscangetName GetStringUTFChars()=null");
    return nullptr;
  }
  destruct dest(
      [jgegs, gegs, env]() { env->ReleaseStringUTFChars(jgegs, gegs); });
  const size_t gegslen = env->GetStringUTFLength(jgegs);
  std::string_view scangegs{gegs, gegslen};
  auto [sensindex, sens] = sensors->makeSIsensorindex(scangegs, time(nullptr));
  if (sens) {
    const char *name = sens->shortsensorname()->data();
    LOGGER("addSIscangetName(%s)=%s\n", gegs, name);
    sendstreaming(sens); // TODO??
    backup->resendResetDevices();
    backup->wakebackup(Backup::wakeall);
    return env->NewStringUTF(name);
  } else {
    if (int meterIndex = newGlucoseMeter(scangegs); meterIndex >= 0) {
      CritArSave<jint> indexptr(env, jindexptr);
      *indexptr.data() = meterIndex;
    }
  }
  return nullptr;
}
extern "C" JNIEXPORT void JNICALL fromjava(siSaveDeviceName)(
    JNIEnv *env, jclass cl, jlong dataptr, jstring jdeviceName) {
  if (!dataptr)
    return;
  streamdata *sdata = reinterpret_cast<streamdata *>(dataptr);
  jint getlen = env->GetStringUTFLength(jdeviceName);
  auto *sens = sdata->hist;
  auto *info = sens->getinfo();
  const int maxlen = sizeof(info->siDeviceName);
  if ((getlen + 1) > maxlen) {
    LOGGER("deviceNamelen=%d toolarge\n", getlen);
  }
  int len = std::min(maxlen - 1, getlen);
  info->siToken = '%';
  char *name = (char *)info->siDeviceName;
  env->GetStringUTFRegion(jdeviceName, 0, len, name);
  info->siDeviceNamelen = len;

  name[len] = '\0';
  sendstreaming(sens);
  backup->resendResetDevices();
  backup->wakebackup(Backup::wakeall);
  // sendstreaming(sens);
}

static bool clearSiTransmitterBinding(SensorGlucoseData *sens) {
  if (!sens || !sens->isSibionics()) {
    return false;
  }
  auto *info = sens->getinfo();
  info->siToken = '\0';
  info->siDeviceNamelen = 0;
  memset(info->siDeviceName, 0, sizeof(info->siDeviceName));
  char *address = sens->deviceaddress();
  if (address) {
    address[0] = '\0';
  }
  sens->scannedAddress = false;
  return true;
}

extern "C" JNIEXPORT void JNICALL fromjava(siClearTransmitterBinding)(
    JNIEnv *env, jclass cl, jlong dataptr) {
  if (!dataptr)
    return;
  streamdata *sdata = reinterpret_cast<streamdata *>(dataptr);
  auto *sens = sdata->hist;
  if (!clearSiTransmitterBinding(sens)) {
    return;
  }
  sendstreaming(sens);
  if (backup) {
    backup->resendResetDevices();
    backup->wakebackup(Backup::wakeall);
  }
  LOGSTRING("siClearTransmitterBinding: cleared saved transmitter identity\n");
}
extern "C" JNIEXPORT void JNICALL fromjava(setSensorptrResetSibionics2)(
    JNIEnv *env, jclass cl, jlong sensorptr, jboolean val) {
  if (!sensorptr)
    return;
  reinterpret_cast<SensorGlucoseData *>(sensorptr)->getinfo()->reset = val;
}
extern "C" JNIEXPORT jboolean JNICALL
fromjava(getSensorptrResetSibionics2)(JNIEnv *env, jclass cl, jlong sensorptr) {
  if (!sensorptr)
    return false;
  return reinterpret_cast<SensorGlucoseData *>(sensorptr)->getinfo()->reset;
}

extern "C" JNIEXPORT void JNICALL fromjava(setResetSibionics2)(JNIEnv *env,
                                                               jclass cl,
                                                               jlong dataptr,
                                                               jboolean val) {
  if (!dataptr)
    return;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  sens->getinfo()->reset = val;
}

extern "C" JNIEXPORT void JNICALL fromjava(prepareSibionicsHardwareReset)(
    JNIEnv *env, jclass cl, jlong dataptr) {
  if (!dataptr)
    return;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  if (!sens)
    return;
  stream->sicontext.prepareHardwareReset(sens);
}

extern "C" JNIEXPORT void JNICALL fromjava(setAutoResetDays)(JNIEnv *env,
                                                             jclass cl,
                                                             jlong dataptr,
                                                             jint val) {
  if (!dataptr)
    return;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  if (!sens)
    return;
  auto *info = sens->getinfo();
  if (!info)
    return;
  if (val < 0)
    val = 0;
  if (val > 255)
    val = 255;
  if (info->autoResetDays != val) {
    info->siBetween = 0;
  }
  info->autoResetDays = val;
}

extern "C" JNIEXPORT jint JNICALL fromjava(getAutoResetDays)(JNIEnv *env,
                                                             jclass cl,
                                                             jlong dataptr) {
  if (!dataptr)
    return 0;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  return sens->getinfo()->autoResetDays;
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(isSibionics2)(JNIEnv *env,
                                                             jclass cl,
                                                             jlong dataptr) {
  if (!dataptr)
    return false;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  return sens && sens->isSibionics2();
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(isSibionics)(JNIEnv *env,
                                                            jclass cl,
                                                            jlong dataptr) {
  if (!dataptr)
    return false;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  return sens && sens->isSibionics();
}

extern "C" JNIEXPORT void JNICALL fromjava(siClearCalibration)(JNIEnv *env,
                                                               jclass cl,
                                                               jlong dataptr) {
  if (!dataptr)
    return;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  if (sens)
    stream->sicontext.reset(sens);
}

extern "C" JNIEXPORT void JNICALL fromjava(siClearAll)(JNIEnv *env, jclass cl,
                                                       jlong dataptr) {
  if (!dataptr)
    return;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  if (sens)
    stream->sicontext.resetAll(sens);
}

// Custom Calibration Settings JNI
extern "C" JNIEXPORT void JNICALL fromjava(setCustomCalibrationSettings)(
    JNIEnv *env, jclass cl, jlong dataptr, jboolean enabled, jint index,
    jboolean autoReset) {
  if (!dataptr)
    return;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  if (sens) {
    auto *info = sens->getinfo();
    const bool enabledBool = enabled == JNI_TRUE;
    const bool autoResetBool = autoReset == JNI_TRUE;
    if (info->useCustomCalibration == enabledBool &&
        info->customCalIndex == index &&
        info->autoResetAlgorithm == autoResetBool) {
      LOGGER("JNI setCustomCalibrationSettings: unchanged "
             "(enabled=%d,index=%d,autoReset=%d), skipping\n",
             enabledBool, index, autoResetBool);
      return;
    }
    info->useCustomCalibration = enabledBool;
    info->customCalIndex = index;
    info->autoResetAlgorithm = autoResetBool;
    // Edit 86: When toggling custom calibration, reset the bad-value streak
    // counter to prevent pre-existing streaks from immediately triggering
    // an algorithm reset (which causes a pause/play reset loop).
    stream->sicontext.clearBadValueStreak();
    uint32_t oldViewMode = info->viewMode;
    // viewMode is a UI selection (Auto/Raw/Auto+Raw/Raw+Auto), not an
    // algorithm control flag. Do not mutate it here.
    LOGGER("JNI setCustomCalibrationSettings: enabled=%d, index=%d, "
           "autoReset=%d, viewMode=%d->%d (streak cleared)\n",
           enabledBool, index, autoResetBool, oldViewMode, info->viewMode);
  }
}

extern "C" JNIEXPORT jlong JNICALL
fromjava(getCustomCalibrationSettings)(JNIEnv *env, jclass cl, jlong dataptr) {
  if (!dataptr)
    return 0;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  if (!sens)
    return 0;

  auto *info = sens->getinfo();
  // Pack settings into a long: bits 0-7 = flags, bits 8-15 = index
  jlong result = 0;
  if (info->useCustomCalibration)
    result |= 1;
  if (info->autoResetAlgorithm)
    result |= 2;
  result |= (static_cast<jlong>(info->customCalIndex) << 8);

  return result;
}

extern "C" JNIEXPORT void JNICALL fromjava(setViewMode)(JNIEnv *env, jclass cl,
                                                        jlong dataptr,
                                                        jint mode) {
  if (!dataptr)
    return;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  if (stream->hist) {
    uint32_t old = stream->hist->getinfo()->viewMode;
    stream->hist->getinfo()->viewMode = mode;
    LOGGER("JNI setViewMode: %d -> %d\n", old, mode);
  }
}

extern "C" JNIEXPORT jint JNICALL fromjava(getViewMode)(JNIEnv *env, jclass cl,
                                                        jlong dataptr) {
  if (!dataptr)
    return 0;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  if (stream->hist) {
    jint vm = stream->hist->getinfo()->viewMode;
    return vm;
  }
  return 0;
}
extern "C" JNIEXPORT jboolean JNICALL fromjava(getResetSibionics2)(
    JNIEnv *env, jclass cl, jlong dataptr) {
  if (!dataptr)
    return false;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  if (!sens)
    return false;
  auto *info = sens->getinfo();
  return info && info->reset;
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(siSensorptrTransmitterScan)(
    JNIEnv *env, jclass cl, jlong sensorptr, jstring jscancode) {
  if (!sensorptr) {
    LOGAR("siSensorptrTransmitterScan sensorptr==null");
    return false;
  }
  const jint getlen = env->GetStringUTFLength(jscancode);
  if (getlen != 59) {
    LOGGER("siSensorptrTransmitterScan len==%d\n", getlen);
    return false;
  }
  const char *scancode = env->GetStringUTFChars(jscancode, NULL);
  if (!scancode) {
    LOGAR("siSensorptrTransmitterScan  GetStringUTFChars()=null");
    return false;
  }
  if (!std::ranges::contains_subrange(std::string_view(scancode, getlen),
                                      sibionicsRecognition)) {
    LOGGER("siSensorptrTransmitterScan  not %s in %s\n",
           sibionicsRecognition.data(), scancode);
    return false;
  }

  auto *sens = reinterpret_cast<SensorGlucoseData *>(sensorptr);
  auto *info = sens->getinfo();
  info->siToken = '%';
  info->siType = 3;
  info->notchinese = true;

  char *name = (char *)info->siDeviceName;
  constexpr const int namlen = 10;
  memcpy(name, scancode + getlen - namlen, namlen);
  info->siDeviceNamelen = namlen;
  name[namlen] = '\0';
  LOGGER("siSensorptrTransmitterScan %s subtype=3 notchinese=1\n", name);
  sendstreaming(sens);
  backup->resendResetDevices();
  backup->wakebackup(Backup::wakeall);
  return true;
}
/*
extern "C" JNIEXPORT jboolean JNICALL   fromjava(siTransmitterScan)(JNIEnv *env,
jclass cl,jlong dataptr,jstring jscancode) { streamdata
*sdata=reinterpret_cast<streamdata *>(dataptr); auto *sens= sdata->hist; return
fromjava(siSensorptrTransmitterScan)(env,cl,(jlong)sens,jscancode);
   } */
extern "C" JNIEXPORT jstring JNICALL fromjava(siGetDeviceName)(JNIEnv *env,
                                                               jclass cl,
                                                               jlong dataptr) {
  if (!dataptr)
    return nullptr;
  const streamdata *sdata = reinterpret_cast<streamdata *>(dataptr);
  const auto *sens = sdata->hist;
  const auto *info = sens->getinfo();
  if (info->siDeviceNamelen <= 0)
    return nullptr;
  const char *name = (char *)info->siDeviceName;
  return env->NewStringUTF(name);
}

/*
extern "C" JNIEXPORT int JNICALL   fromjava(getSIindex)(JNIEnv *env, jclass
cl,jlong dataptr) { if(!dataptr) return 0; const streamdata
*sdata=reinterpret_cast<const streamdata *>(dataptr); return
sdata->hist->getSiIndex();
   } */

// extern bool savejson(SensorGlucoseData *sens,std::string_view, int
// index,const AlgorithmContext *alg );

extern data_t *fromjbyteArray(JNIEnv *env, jbyteArray jar, jint len = -1);

extern std::string_view libdirname;
#include "sibionics/json.hpp"
void *openlib(std::string_view libname) {
  int liblen = libdirname.size();
  if (liblen <= 0) {
    LOGGER("libdirname.size()=%d\n", liblen);
    return nullptr;
  }
  int libnamelen = libname.size() + 1;
  char fullpath[libnamelen + liblen];
  memcpy(fullpath, libdirname.data(), liblen);
  memcpy(fullpath + liblen, libname.data(), libnamelen);
  LOGGER("open %s\n", fullpath);
  return dlopen(fullpath, RTLD_NOW);
}

//"_ZN21NativeAlgorithmV1_1_223getJsonAlgorithmContextEv";
//_ZN22NativeAlgorithmV1_1_3B23getJsonAlgorithmContextEv
//_ZN22NativeAlgorithmV1_1_3B23setJsonAlgorithmContextEPc
// #define algjavastr(x)
// "Java_com_algorithm_v1_11_13_1b_NativeAlgorithmLibraryV1_11_13B_" #x
#include "jnidef.h"

extern JNIEnv *subenv;

extern JavaVM *getnewvm();

extern bool loadjson(SensorGlucoseData *sens, const char *statename,
                     const AlgorithmContext *alg, setjson_t setjson);
#ifdef SI3ONLY
#undef algLibName
#undef jniAlglib
#undef vers
#undef algjavastr
#undef jsonname

#define jsonname(et, end)                                                      \
  "_ZN22NativeAlgorithmV1_1_3B23" #et                                          \
  "JsonAlgorithmContext" #end // Makes one in 5 minutes
#define algLibName "/libnative-algorithm-v1_1_3_B.so";
#define jniAlglib "/libnative-algorithm-jni-v113B.so";
#define vers(x) x
#undef algjavastr

#undef targetlow
#undef targethigh
#define targetlow 3.9
#define targethigh 7.8
#define algjavastr(x)                                                          \
  "Java_com_algorithm_v1_11_13_1b_NativeAlgorithmLibraryV1_11_13B_" #x

#include "jnifuncs.hpp"
#else
#undef jniAlglib
#undef vers
#undef algjavastr

#undef targetlow
#undef targethigh
#ifdef NOTCHINESE
#define targetlow 4.4
#define targethigh 11.1
// #define jniAlglib     "/libnative-algorithm-jni-v112.so";
// #define algjavastr(x)
// "Java_com_algorithm_v1_11_12_NativeAlgorithmLibraryV1_11_12_" #x #define
// algLibName "/libnative-algorithm-v1_1_2.so" #define jsonname(et,end)
// "_ZN21NativeAlgorithmV1_1_223" #et  "JsonAlgorithmContext" #end

/*#define jniAlglib     "/libnative-algorithm-jni-v112F.so";
#define algjavastr(x)
"Java_com_algorithm_v1_11_12_1f_NativeAlgorithmLibraryV1_11_12F_" #x #define
algLibName "/libnative-algorithm-v1_1_2F.so"; #define jsonname(et,end)
"_ZN22NativeAlgorithmV1_1_2F23" #et  "JsonAlgorithmContext" #end */

#define jniAlglib "/libnative-algorithm-jni-v116A.so";
#define algjavastr(x) "Java_com_algorithm_v116a_NativeAlgorithmLibraryV116A_" #x

#define vers(x) x##2

#include "jnifuncs.hpp"
#endif

// #ifdef SIHISTORY
#if 1
#undef algLibName
#undef jniAlglib
#undef vers
#undef algjavastr
#undef jsonname

#undef targetlow
#undef targethigh
#define targetlow 3.9
#define targethigh 7.8

/*#define jsonname(et,end) "_ZN22NativeAlgorithmV1_1_3B23" #et
"JsonAlgorithmContext" #end //Makes one in 5 minutes #define algLibName
"/libnative-algorithm-v1_1_3_B.so"; #define jniAlglib
"/libnative-algorithm-jni-v113B.so"; #define algjavastr(x)
"Java_com_algorithm_v1_11_13_1b_NativeAlgorithmLibraryV1_11_13B_" #x */

#define jsonname(et, end)                                                      \
  "_ZN22NativeAlgorithmV1_1_5G23" #et                                          \
  "JsonAlgorithmContext" #end // Makes one in 5 minutes

#define algLibName "/libnative-algorithm-v1_1_5G.so";
#define jniAlglib "/libnative-algorithm-jni-v115G.so";
#define algjavastr(x)                                                          \
  "Java_com_algorithm_v1_11_15_1g_NativeAlgorithmLibraryV1_11_15G_" #x

#define vers(x) x##3
#include "jnifuncs.hpp"

#endif
#endif

#ifdef NOTCHINESE
#define datahandlestr(x)                                                       \
  "Java_com_no_sisense_enanddecryption_CGMDataHandle130_" #x

#define algDatahandleName "/libdata-handle-lib.so";
algtype(V120SpiltData) V120SpiltData;
algtype(v120RegisterKey) v120RegisterKey;
algtype(V120ApplyAuthentication) V120ApplyAuthentication;
algtype(V120RawData) V120RawData;
algtype(V120Activation) V120Activation;
algtype(V120Reset) V120Reset;
algtype(V120IsecUpdate) V120IsecUpdate;
static bool getDatahandle() {
  std::string_view alglib = algDatahandleName;
  void *handle = openlib(alglib);
  if (!handle) {
    LOGGER("dlopen %s failed: %s\n", alglib.data(), dlerror());
    return false;
  }
  {
    constexpr const char str[] = datahandlestr(V120SpiltData);
    V120SpiltData = (algtype(V120SpiltData))dlsym(handle, str);
    if (!V120SpiltData) {
      LOGGER("dlsym %s failed: %s\n", str, dlerror());
      return false;
    }
  }

  {
    constexpr const char str[] = datahandlestr(v120RegisterKey);
    v120RegisterKey = (algtype(v120RegisterKey))dlsym(handle, str);
    if (!v120RegisterKey) {
      LOGGER("dlsym %s failed: %s\n", str, dlerror());
      return false;
    }
  }
  {
    constexpr const char str[] = datahandlestr(V120ApplyAuthentication);
    V120ApplyAuthentication =
        (algtype(V120ApplyAuthentication))dlsym(handle, str);
    if (!V120ApplyAuthentication) {
      LOGGER("dlsym %s failed: %s\n", str, dlerror());
      return false;
    }
  }
  {
    constexpr const char str[] = datahandlestr(V120RawData);
    V120RawData = (algtype(V120RawData))dlsym(handle, str);
    if (!V120RawData) {
      LOGGER("dlsym %s failed: %s\n", str, dlerror());
      return false;
    }
  }
  {
    constexpr const char str[] = datahandlestr(V120Activation);
    V120Activation = (algtype(V120Activation))dlsym(handle, str);
    if (!V120Activation) {
      LOGGER("dlsym %s failed: %s\n", str, dlerror());
      return false;
    }
  }
  {
    constexpr const char str[] = datahandlestr(V120Reset);
    V120Reset = (algtype(V120Reset))dlsym(handle, str);
    if (!V120Reset) {
      LOGGER("dlsym %s failed: %s\n", str, dlerror());
      return false;
    }
  }
  {
    constexpr const char str[] = datahandlestr(V120IsecUpdate);
    V120IsecUpdate = (algtype(V120IsecUpdate))dlsym(handle, str);
    if (!V120IsecUpdate) {
      LOGGER("dlsym %s failed: %s\n", str, dlerror());
      return false;
    }
  }

  LOGAR("found datahandle functions");
  return true;
}
#endif
#ifdef NOTCHINESE
bool siInit2() {
  static bool nativeReady = false;
  static bool jniReady = false;
  static bool datahandleReady = false;
  if (!nativeReady) {
    nativeReady = getNativefunctions2();
  }
  if (!jniReady) {
    jniReady = getJNIfunctions2();
  }
  if (!datahandleReady) {
    datahandleReady = getDatahandle();
  }
  return nativeReady && jniReady && datahandleReady;
}
#endif
bool siInit3() {
  static bool init = getNativefunctions3() && getJNIfunctions3();
  return init;
}
bool siInit(bool notchinese) {
#ifdef NOTCHINESE

  if (notchinese)
    return siInit2();
#endif
  return siInit3();
};

#include <charconv>
bool loadjson(SensorGlucoseData *sens, const char *statename,
              const AlgorithmContext *alg, setjson_t setjson) {
  auto *nati = reinterpret_cast<NativeAlgorithm *>(alg->mNativeContext);
  if (!nati) {
    LOGAR("mNativeContext==null");
    return false;
  }
  sens->mutex.lock();
  Readall json(statename);
  sens->mutex.unlock();
  if (!json.data()) {
    LOGGER("read %s failed\n", statename);
    return false;
  }
#ifndef NOLOG
  int res =
#endif
      setjson(nati, json.data());
  LOGGER("setjson()=%d\n", res);
  return true;
}
/*
bool savejson(SensorGlucoseData *sens,const string_view name,int index,const
AlgorithmContext *alg,getjson_t getjson) { if(!getjson) {
        LOGAR("getjson==null");
        return false;
        }
   auto *nati=reinterpret_cast<NativeAlgorithm*>(alg ->mNativeContext);
   if(!nati) {
      LOGAR("mNativeContext==null");
      return false;
      }
    const char *json=getjson(nati);
    LOGGER("getjson()=%p\n",json);
    if(!json) {
        return false;
        }
    int jsonlen=strlen(json);
    if(!json) {
        LOGAR("jsonlen==0");
        return false;
        }
    const int maxbuf=name.size()+6+2;
    char buf[maxbuf];
   memcpy(buf,name.data(),name.size());
    char *startnum=buf+name.size();
    auto [ptr,ec]  =std::to_chars(startnum,buf+maxbuf,index);
   *ptr='\0';
    bool success=writeall(buf,json,jsonlen);
    if(!success) {
        return false;
        }
    int res;
    {
    std::lock_guard<std::mutex> lock(sens->mutex);
    res=rename(buf,name.data());
    }
    if(res) {
        flerror("rename(%s,%s) failed",buf,name.data());
        return false;
        }
    return true;
    }
    */
#include "sibionics/SiContext.hpp"

void SiContext::setNotchinese(SensorGlucoseData *sens) {
  sens->setNotchinese();
#ifdef NOTCHINESE
  release();
  if (!siInit2()) {
    LOGAR("SiContext::setNotchinese siInit2 failed");
  }
  algcontext = initAlgorithm2(sens, binState);
#endif
  notchinese = true;
}
SiContext::SiContext(SensorGlucoseData *sens)
    : binState(2, sens->binstatefile, 4096),
      algcontext(
#ifdef NOTCHINESE
          sens->notchinese() ? initAlgorithm2(sens, binState) :
#endif

                             initAlgorithm3(sens, binState)),
      notchinese(sens->notchinese()) {};
void SiContext::release() {
  if (!algcontext) {
    LOGSTRING("releaseAlgorithmContext skipped: algcontext==null\n");
    return;
  }
#ifndef NOLOG
  int res =
#endif
      (
#ifdef NOTCHINESE
          notchinese ? releaseAlgorithmContext2 :
#endif

                     releaseAlgorithmContext3)(
          subenv, nullptr, reinterpret_cast<jobject>(algcontext));
  LOGGER("releaseAlgorithmContext(%p)=%d notchinese=%d\n", algcontext, res,
         notchinese);
  delete algcontext;
  algcontext = nullptr;
}

void SiContext::prepareHardwareReset(SensorGlucoseData *sens) {
  if (!sens) {
    LOGAR("SiContext::prepareHardwareReset sens==null");
    return;
  }
  auto *info = sens->getinfo();
  if (!info) {
    LOGAR("SiContext::prepareHardwareReset info==null");
    return;
  }

  const int savedSiIndex = sens->getSiIndex();
  const bool savedReset = info->reset;
  LOGGER("SiContext::prepareHardwareReset() sensor=%s siIndex=%d "
         "pollcount=%u starttime=%u starthistory=%d\n",
         sens->deviceaddress(), savedSiIndex, info->pollcount, info->starttime,
         info->starthistory);

  release();

  if (binState.map.data() && binState.map.size() > 0) {
    memset(binState.map.data(), 0, binState.map.size());
    LOGSTRING("SiContext::prepareHardwareReset zeroed binState memory map.\n");
  }
  binState.reset();
  unlink(sens->statefile.c_str());
  unlink(pathconcat(sens->getsensordir(), "state3.json").c_str());

  info->lastCalResetTime = (uint32_t)time(nullptr);
  info->caliNr = 0;
  info->pollinterval = 0.0;
  badValueStreak = 0;
  sens->sensorerror = false;
  sens->sensorErrorTime = 0;
  sens->retried = 0;

  // Recreate with siIndex=0 so initAlgorithm does not restore the stale
  // pre-reset binary state or trigger a local replay. Restore siIndex
  // afterwards so the first post-reset packet can still be bridged/appended by
  // the normal index<maxid logic instead of overwriting existing history.
  sens->setSiIndex(0);
  if (notchinese) {
    algcontext = initAlgorithm2(sens, binState);
  } else {
    algcontext = initAlgorithm3(sens, binState);
  }
  sens->setSiIndex(savedSiIndex > 0 ? savedSiIndex : 1);
  info->reset = savedReset;

  LOGGER("SiContext::prepareHardwareReset() done ok=%d restoredSiIndex=%d "
         "reset=%d mNativeContext=%lld\n",
         algcontext != nullptr, sens->getSiIndex(), info->reset ? 1 : 0,
         algcontext ? algcontext->mNativeContext : 0LL);
}

void SiContext::reset(SensorGlucoseData *sens) {
  LOGGER("SiContext::reset() called for sensor %s\n", sens->deviceaddress());
  auto *info = sens->getinfo();
  if (info && info->useCustomCalibration) {
    if (!sens->backupPolls()) {
      LOGSTRING("SiContext::reset(): backupPolls failed before custom reset\n");
    }
  }
  release(); // Properly destroy existing algorithm context

  // Wipe the entire memory mapped file content to ensure no stale state
  // persists
  if (binState.map.data() && binState.map.size() > 0) {
    memset(binState.map.data(), 0, binState.map.size());
    LOGSTRING("SiContext::reset() zeroed out binState memory map.\n");
  }

  binState.reset(); // Reset allocator headers

  // Keep state.bin mapped and zeroed in-place.
  // Unlinking a currently mapped file makes future SiContext instances reopen
  // an empty new inode while the old context keeps writing to the deleted one.
  // That causes "binState empty" loops and breaks restore/replay continuity.
  // Also delete JSON backups to prevent reloading old state
  unlink(sens->statefile.c_str());
  unlink(pathconcat(sens->getsensordir(), "state3.json").c_str());

  // Record reset timestamp for cooldown guard (prevents reset loops)
  info->lastCalResetTime = (uint32_t)time(nullptr);

  // Clear old calibration
  info->caliNr = 0;

  // Note: Do NOT reset starttime, scancount, etc. to preserve history.
  // Explicitly clear the reset flag to ensure we don't trigger a hardware reset
  // accidentally.
  info->reset = false;

  // Save current siIndex before recreating algorithm
  const int savedSiIndex = sens->getSiIndex();
  sens->setSiIndex(0); // Temporarily set to 0 so initAlgorithm doesn't trigger
                       // duplicate reset

  // Recreate algcontext with clean state
  if (notchinese) {
    algcontext = initAlgorithm2(sens, binState);
  } else {
    algcontext = initAlgorithm3(sens, binState);
  }

  // Restore siIndex - set to savedSiIndex - windowSize for rolling window
  int windowSize = 0;
  int newIndex = 1;

  // Custom calibration: rewind siIndex by the rolling window size so
  // the algorithm replays enough history to recalibrate.
  // viewMode is independent of useCustomCalibration — viewMode controls
  // which value (auto/raw/custom) is DISPLAYED, not whether the
  // calibration algorithm runs.
  if (info->useCustomCalibration) {
    int hoursWindow =
        SensorGlucoseData::getCustomCalHours(info->customCalIndex);
    if (hoursWindow <= 0) {
      // MAX mode: replay all history from index 1
      newIndex = 1;
      LOGSTRING("SiContext::reset() Custom MAX mode: resetting to index 1\n");
    } else {
      // Dynamic interval calculation (mirrors resetSiIndex in glucose.cpp)
      float interval = sens->getinfo()->pollinterval;
      if (interval < 59.0f) {
        interval =
            60.0f; // Fallback to 1 min (most common for custom cal sensors)
        LOGGER("SiContext::reset (Custom): interval too small (%.1f), using "
               "fallback 60.0s\n",
               sens->getinfo()->pollinterval);
      }
      windowSize = (int)((hoursWindow * 3600.0f) / interval);
      // Idempotent Reset: Calculate from TIME, not current index
      // This prevents repeated resets from drifting infinitely backward.
      time_t now = time(NULL);
      uint32_t starttime = sens->getinfo()->starttime;

      // Safety check for starttime
      if (starttime > 0 && now > starttime) {
        float calcInterval = interval > 0.1f ? interval : 60.0f;
        int maxIndex = (int)((now - starttime) / calcInterval);

        // Target is "Now - Window", regardless of current siIndex state
        newIndex = maxIndex > windowSize ? (maxIndex - windowSize) : 1;

        LOGGER("SiContext::reset() Custom (Time-Based): now=%ld start=%d "
               "elapsed=%d maxIndex=%d windowSize=%d newIndex=%d\n",
               now, starttime, (int)(now - starttime), maxIndex, windowSize,
               newIndex);
      } else {
        // Fallback if time is invalid
        newIndex = savedSiIndex > windowSize ? (savedSiIndex - windowSize) : 1;
        LOGGER("SiContext::reset() Custom (Fallback): Time invalid, using "
               "savedSiIndex. newIndex=%d\n",
               newIndex);
      }
    }

    // info->useCustomCalibration = false; // DON'T CLEAR: Keeps UI switch
    // enabled!
  } else {
    // Native mode: Full reset to index 1 to re-process entire history
    newIndex = 1;
    LOGSTRING("SiContext::reset() NATIVE MODE: resetting to index 1 "
              "(processing all history)\n");
  }

  sens->setSiIndex(newIndex);

  LOGGER("SiContext::reset() set siIndex: saved=%d window=%d new=%d\n",
         savedSiIndex, windowSize, newIndex);
  LOGGER("SiContext::reset() recreated algcontext. mNativeContext=%lld\n",
         algcontext ? algcontext->mNativeContext : 0LL);
  // Enter reset mode so checkinfo keeps sensor active and eu.cpp handles Gap.
  sens->enterResetMode();

  LOGSTRING(
      "SiContext::reset() completed. Fresh algorithm, ready for new data.\n");
}

void SiContext::resetAll(SensorGlucoseData *sens) {
  LOGGER("SiContext::resetAll() called for sensor %s\n", sens->deviceaddress());
  release();

  if (binState.map.data() && binState.map.size() > 0) {
    memset(binState.map.data(), 0, binState.map.size());
  }

  binState.reset();
  unlink(sens->statefile.c_str());
  unlink(pathconcat(sens->getsensordir(), "state3.json").c_str());

  auto *info = sens->getinfo();
  info->caliNr = 0;

  // FULL RESET: Wipe history counters
  info->starttime = time(nullptr);
  if (sensors)
    sensors->setSensorStarttime(sens->sensorIndex, info->starttime);
  info->scancount = 0;
  info->pollcount = 0;
  info->starthistory = 0;
  info->endhistory = 0;

  // TRIGGER SENSOR RESET

  info->reset = true;

  if (notchinese) {
    algcontext = initAlgorithm2(sens, binState);
  } else {
    algcontext = initAlgorithm3(sens, binState);
  }
  // Exit reset mode to restore standard gap checks after Factory Reset.
  sens->exitResetMode();

  LOGSTRING("SiContext::resetAll() COMPLETE FACTORY RESET performed.\n");
}

void SiContext::wipeDataOnly(SensorGlucoseData *sens) {
  LOGGER("SiContext::wipeDataOnly() called for sensor %s\n",
         sens->deviceaddress());
  release();

  if (binState.map.data() && binState.map.size() > 0) {
    memset(binState.map.data(), 0, binState.map.size());
  }

  binState.reset();
  unlink(sens->statefile.c_str());
  unlink(pathconcat(sens->getsensordir(), "state3.json").c_str());

  auto *info = sens->getinfo();
  info->caliNr = 0;

  // FULL RESET: Wipe history counters
  info->starttime = time(nullptr);
  if (sensors)
    sensors->setSensorStarttime(sens->sensorIndex, info->starttime);
  info->scancount = 0;
  info->pollcount = 0;
  info->starthistory = 0;
  info->endhistory = 0;

  // DO NOT TRIGGER SENSOR RESET
  info->reset = false;

  if (notchinese) {
    algcontext = initAlgorithm2(sens, binState);
  } else {
    algcontext = initAlgorithm3(sens, binState);
  }
  LOGSTRING(
      "SiContext::wipeDataOnly() Local data wiped without sensor reset.\n");
}

extern int sitrend2abbott(int sitrend);
extern float sitrend2RateOfChange(int sitrend);

void SiContext::localReplay(SensorGlucoseData *sens) {
  LOGGER("SiContext::localReplay() called for sensor %s\n",
         sens->deviceaddress());

  // Step 0: Preserve the current native baseline before custom replay.
  // Native mode replay (used for manual native restart) must not overwrite the
  // snapshot with already-mutated custom data.
  if (sens->getinfo()->useCustomCalibration) {
    if (!sens->backupPolls()) {
      LOGSTRING("localReplay: backupPolls failed before custom replay\n");
    }
  }

  // Step 1: Destroy existing algorithm context
  release();

  // Step 2: Wipe binState (same as reset())
  if (binState.map.data() && binState.map.size() > 0) {
    memset(binState.map.data(), 0, binState.map.size());
  }
  binState.reset();
  unlink(sens->statefile.c_str());
  unlink(pathconcat(sens->getsensordir(), "state3.json").c_str());

  auto *info = sens->getinfo();
  info->lastCalResetTime = (uint32_t)time(nullptr);
  info->caliNr = 0;
  info->reset = false;

  // Step 3: Recreate fresh algorithm context (siIndex=0 to skip binState
  // restore)
  const int savedSiIndex = sens->getSiIndex();
  sens->setSiIndex(0);
  if (notchinese) {
    algcontext = initAlgorithm2(sens, binState);
  } else {
    algcontext = initAlgorithm3(sens, binState);
  }

  if (!algcontext) {
    LOGSTRING("localReplay: initAlgorithm returned null, aborting\n");
    sens->setSiIndex(savedSiIndex);
    return;
  }

  // Step 4: Calculate replay window start
  const int totalPolls = info->pollcount;
  int replayStart = 0;

  if (info->useCustomCalibration) {
    int hoursWindow =
        SensorGlucoseData::getCustomCalHours(info->customCalIndex);
    if (hoursWindow <= 0) {
      // MAX mode: replay all stored data
      replayStart = 0;
      LOGGER("localReplay: custom cal MAX mode, replaying all %d polls\n",
             totalPolls);
    } else {
      float interval = info->pollinterval;
      if (interval < 59.0f)
        interval = 60.0f;
      int windowSize = (int)((hoursWindow * 3600.0f) / interval);
      replayStart = totalPolls > windowSize ? (totalPolls - windowSize) : 0;
      LOGGER("localReplay: custom cal window=%dH, windowSize=%d, "
             "replayStart=%d, totalPolls=%d\n",
             hoursWindow, windowSize, replayStart, totalPolls);
    }
  } else {
    // Native mode: replay everything
    replayStart = 0;
    LOGGER("localReplay: native mode, replaying all %d polls\n", totalPolls);
  }

  // Step 5: Replay stored raw data through the algorithm
  // Key insight: process2/process3 only return a calibrated value every ~5th
  // poll. For intermediate polls the result is 0. The live BLE path (eu.cpp,
  // process.cpp) handles this via pollinterval interpolation:
  //   - On a calibrated poll: pollinterval = newvalue - value (offset)
  //   - On intermediate polls: newvalue = value + pollinterval
  // We must replicate this logic exactly, otherwise 4/5 polls get raw values.
  const ScanData *pollsData = sens->getPollsData();
  const RawData *rawData = sens->getRawPollsData();
  const uint16_t *tempData = sens->getTempPollsData();
  int processed = 0;
  int skipped = 0;

  // Start from zero — a fresh algorithm has no prior calibration offset.
  // Seeding from info->pollinterval (last known) was wrong because that value
  // was computed by the OLD algorithm state; using it with a FRESH algorithm
  // produces incorrect interpolation for the first ~5 polls.
  double replayPollInterval = 0;

  // Bad-value streak counter (matches eu.cpp:416-456)
  int replayBadStreak = 0;
  constexpr int REPLAY_MAX_BAD_STREAK = 5;

  for (int i = replayStart; i < totalPolls; i++) {
    // Match live BLE path exactly (eu.cpp:398-456).
    // The live path:
    //   if (current > 1 && value < 3000 && (newvalue=process2(..)) > 1 &&
    //   (index % 5 == 0))
    //       pollinterval = newvalue - value;
    //   else
    //       if (pollinterval < 40) newvalue = value + pollinterval;
    //   if (newvalue > 50) newvalue = value;  // sanity fallback

    const uint16_t rawVal = rawData[i].raw;
    if (rawVal == 0) {
      skipped++;
      continue;
    }

    int current = (int)rawVal; // raw in 0.1 mmol/L units, same as BLE 'current'
    double value = current / 10.0;
    int index = pollsData[i].id;

    double temp;
    if (tempData && tempData[i] > 0) {
      temp = tempData[i] / 10.0;
    } else {
      temp = 36.0;
    }

    // Initialize to raw value — prevents UB when pollinterval >= 40
    // (same implicit behavior as eu.cpp:398 where newvalue is uninitialized
    //  but always set before use via process2 or the else branch)
    double newvalue = value;

    if (algcontext) {
      // Exact eu.cpp:402-408 logic
      if (current > 1 && value < 3000.0 &&
          (newvalue = (notchinese ? process2(index, value, temp)
                                  : process3(index, value, temp))) > 1 &&
          (index % 5 == 0)) {
        replayPollInterval = newvalue - value;
      } else {
        if (replayPollInterval < 40)
          newvalue = value + replayPollInterval;
      }
    } else {
      newvalue = value;
    }

    // Bad-value streak logic (matches eu.cpp:414-456)
    // Prevents one bad calibration from snowballing through pollinterval
    if (newvalue > 50.0) {
      replayBadStreak++;
      if (replayBadStreak >= REPLAY_MAX_BAD_STREAK) {
        // Re-init algorithm (same as eu.cpp:443 reset for custom cal)
        LOGGER("localReplay: %d consecutive bad values at poll %d, "
               "re-initializing algorithm\n",
               replayBadStreak, i);
        release();
        if (binState.map.data() && binState.map.size() > 0) {
          memset(binState.map.data(), 0, binState.map.size());
        }
        binState.reset();
        sens->setSiIndex(0);
        if (notchinese) {
          algcontext = initAlgorithm2(sens, binState);
        } else {
          algcontext = initAlgorithm3(sens, binState);
        }
        replayPollInterval = 0;
        replayBadStreak = 0;
      }
      newvalue = value; // fallback to raw
    } else {
      replayBadStreak = 0;
    }

    if (newvalue > 1 && newvalue < 30) {
      const int mgdL = (int)std::round(newvalue * convfactordL);
      const int trend = algcontext ? algcontext->ig_trend : 0;
      const float change = sitrend2RateOfChange(trend);
      const int abbotttrend = sitrend2abbott(trend);

      // Overwrite existing poll entry with recalibrated value
      // Pass stored temp through to preserve it in temppolls
      uint16_t storedTemp = (tempData && tempData[i] > 0) ? tempData[i] : 0;
      sens->saveStreamAgain(pollsData[i].t, index, mgdL, abbotttrend, change,
                            (int)rawVal, storedTemp);
      processed++;
    } else {
      skipped++;
    }
  }

  // Write final pollinterval back so live BLE path continues smoothly
  info->pollinterval = replayPollInterval;

  // Step 6: Restore siIndex to where it was (BLE continues from here)
  sens->setSiIndex(savedSiIndex);

  // Reset bad value streak since we just rebuilt everything
  badValueStreak = 0;

  LOGGER("localReplay: done. processed=%d, skipped=%d, siIndex restored to "
         "%d\n",
         processed, skipped, savedSiIndex);
  // Do NOT enter reset mode — this was synchronous, no BLE replay needed
}

bool SiContext::reloadFromPersistedState(SensorGlucoseData *sens) {
  if (!sens) {
    LOGAR("SiContext::reloadFromPersistedState sens==null");
    return false;
  }

  auto *info = sens->getinfo();
  const int savedSiIndex = sens->getSiIndex() > 0 ? sens->getSiIndex() : 1;
  LOGGER("SiContext::reloadFromPersistedState() start: siIndex=%d\n",
         savedSiIndex);

  // Restore path must import state.bin immediately. initAlgorithm only imports
  // binary state when not in reset mode, so force reset mode off here.
  sens->exitResetMode();
  // Mark this as a recent reset-style operation so initAlgorithm doesn't
  // trigger resetSiIndex() if the restored binState is unexpectedly empty.
  info->lastCalResetTime = (uint32_t)time(nullptr);

  release();

  // Re-open mmap so restored state.bin contents are visible immediately.
  binState.map.extend(sens->binstatefile, 4096);
  if (!binState.map.data()) {
    LOGSTRING("SiContext::reloadFromPersistedState: remap state.bin failed\n");
  }

  sens->setSiIndex(savedSiIndex);
  if (notchinese) {
    algcontext = initAlgorithm2(sens, binState);
  } else {
    algcontext = initAlgorithm3(sens, binState);
  }

  if (!algcontext) {
    LOGSTRING("SiContext::reloadFromPersistedState: init failed, retrying with "
              "fresh binState\n");
    if (binState.map.data() && binState.map.size() > 0) {
      memset(binState.map.data(), 0, binState.map.size());
    }
    binState.reset();
    sens->setSiIndex(1);
    if (notchinese) {
      algcontext = initAlgorithm2(sens, binState);
    } else {
      algcontext = initAlgorithm3(sens, binState);
    }
    sens->setSiIndex(savedSiIndex);
  }

  clearBadValueStreak();
  LOGGER(
      "SiContext::reloadFromPersistedState() done: ok=%d mNativeContext=%lld "
      "siIndex=%d\n",
      algcontext != nullptr, algcontext ? algcontext->mNativeContext : 0LL,
      sens->getSiIndex());
  return algcontext != nullptr;
}

SiContext::~SiContext() { release(); };

// Temperature data from temppolls.dat for any sensor that populates it.
extern "C" JNIEXPORT jintArray JNICALL
fromjava(getTemperatureData)(JNIEnv *env, jclass cl, jlong sensorptr) {
  if (!sensorptr)
    return nullptr;
  const auto *sens = reinterpret_cast<const SensorGlucoseData *>(sensorptr);

  const uint16_t *tempData = sens->getTempPollsData();
  const int count = sens->pollcount();
  if (!tempData || count <= 0)
    return nullptr;

  jintArray result = env->NewIntArray(count);
  if (!result)
    return nullptr;

  jint *buf = env->GetIntArrayElements(result, nullptr);
  if (!buf)
    return nullptr;

  for (int i = 0; i < count; i++) {
    buf[i] = (jint)tempData[i]; // Temperature in 0.1°C units
  }

  env->ReleaseIntArrayElements(result, buf, 0);
  return result;
}

extern "C" JNIEXPORT jintArray JNICALL
fromjava(getTemperatureDataByName)(JNIEnv *env, jclass cl, jstring jsensor) {
  if (!jsensor || !sensors)
    return nullptr;
  const char *sensorChars = env->GetStringUTFChars(jsensor, nullptr);
  if (!sensorChars)
    return nullptr;

  const auto *sens = sensors->getSensorData(sensorChars);
  if (!sens) {
    sens = sensors->gethistshort(sensorChars);
  }
  env->ReleaseStringUTFChars(jsensor, sensorChars);
  if (!sens)
    return nullptr;

  const uint16_t *tempData = sens->getTempPollsData();
  const int count = sens->pollcount();
  if (!tempData || count <= 0)
    return nullptr;

  jintArray result = env->NewIntArray(count);
  if (!result)
    return nullptr;

  jint *buf = env->GetIntArrayElements(result, nullptr);
  if (!buf)
    return nullptr;

  for (int i = 0; i < count; i++) {
    buf[i] = (jint)tempData[i];
  }

  env->ReleaseIntArrayElements(result, buf, 0);
  return result;
}

extern "C" JNIEXPORT jlong JNICALL
fromjava(SIprocessData)(JNIEnv *envin, jclass cl, jlong dataptr,
                        jbyteArray bluetoothdata, jlong mmsec) {
  if (!dataptr) {
    LOGAR("SIprocessData dataptr==null");
    return 0LL;
  }
  sistream *sdata = reinterpret_cast<sistream *>(dataptr);
  SensorGlucoseData *sens = sdata->hist;
  if (!sens) {
    LOGAR("SIprocessData SensorGlucoseData==null");
    return 0LL;
  }
  uint32_t timsec = mmsec / 1000L;
  data_t *bluedata = fromjbyteArray(envin, bluetoothdata);
  destruct _destbluedata([bluedata] { data_t::deleteex(bluedata); });
  if (sens->notchinese() && !sdata->sicontext.isNotchinese()) {
    LOGAR("SIprocessData refreshing context to notchinese");
    sdata->sicontext.setNotchinese(sens);
  }
  auto *info = sens->getinfo();
  if (!info) {
    LOGAR("SIprocessData SensorGlucoseData info==null");
    return 0LL;
  }
  /*
    if(sens->getinfo()->reset) {
          if(!sens->getinfo()->notchinese||!V120Reset) {
              sdata->sicontext.setNotchinese(sens);
              }
          LOGAR("SIprocessData reset");
          return 10LL;
          } */
  if (info->reset && sens->isSibionics() && sens->siSubtype() <= 3) {
    if (!sens->notchinese() && sens->siSubtype() <= 2) {
      LOGGER("SIprocessData direct reset pending subtype=%u notchinese=0\n",
             sens->siSubtype());
      return 10LL;
    }
    sdata->sicontext.prepareHardwareReset(sens);
    LOGGER("SIprocessData reset subtype=%u notchinese=%d\n",
           sens->siSubtype(), sens->notchinese() ? 1 : 0);
    return 10LL;
  }
  if (sens->notchinese()) {
    if (info->autoResetDays > 0) {
      if (timsec > info->starttime) {
        float age_days = (timsec - info->starttime) / (24.0f * 3600.0f);
        if (age_days >= info->autoResetDays) {
          if (info->siBetween == 0) {
            info->reset = true;
            info->siBetween = 1;
            sdata->sicontext.prepareHardwareReset(sens);
            LOGGER("Auto Reset triggered: age=%.2f days limit=%d\n", age_days,
                   info->autoResetDays);
            return 10LL;
          }
        } else {
          if (info->siBetween != 0)
            info->siBetween = 0;
        }
      }
    }
    // Daily auto-replay: when autoResetAlgorithm ("Restart daily") is enabled
    // AND custom calibration is active, trigger a localReplay every 24 hours.
    // This is independent of autoResetDays (hardware reset cycle).
    if (info->autoResetAlgorithm && info->useCustomCalibration) {
      uint32_t lastReplay = info->lastCalResetTime;
      if (lastReplay > 0 && timsec > lastReplay) {
        uint32_t elapsed = timsec - lastReplay;
        if (elapsed >= 24 * 3600) {
          LOGGER("Daily auto-replay triggered: elapsed=%u sec since last "
                 "replay\n",
                 elapsed);
          sdata->sicontext.localReplay(sens);
          // localReplay already sets lastCalResetTime internally
        }
      }
    }
    const jlong res = sdata->sicontext.processData2(sens, timsec, bluedata,
                                                    sdata->sensorindex);
    LOGGER("processData2=%lld\n", res);
    return res;
  } else {
    const jlong res = sdata->sicontext.processData(
        sens, timsec, bluedata->data(), bluedata->size(), sdata->sensorindex);
    LOGGER("processData=%lld\n", res);
    return res;
  }
}

extern "C" JNIEXPORT void JNICALL fromjava(siWipeDataOnly)(JNIEnv *env,
                                                           jclass cl,
                                                           jlong dataptr) {
  if (!dataptr)
    return;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  if (sens)
    stream->sicontext.wipeDataOnly(sens);
}

extern "C" JNIEXPORT void JNICALL fromjava(siLocalReplay)(JNIEnv *env,
                                                          jclass cl,
                                                          jlong dataptr) {
  if (!dataptr)
    return;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  if (sens) {
    stream->sicontext.localReplay(sens);
  }
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(siRestoreOriginalPolls)(
    JNIEnv *env, jclass cl, jlong dataptr) {
  if (!dataptr)
    return JNI_FALSE;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  if (!sens)
    return JNI_FALSE;
  auto *info = sens->getinfo();
  if (!info)
    return JNI_FALSE;

  const uint32_t opId = ++gSiRestoreOp;
  const uint32_t viewModeBefore = info->viewMode;
  const int siIndexBefore = sens->getSiIndex();
  const int pollcountBefore = info->pollcount;

  const int restoreCode = sens->restorePollsWithReason();
  LOGGER("siRestoreOriginalPolls[%u]: restore=%s(%d) viewMode=%u pollcount=%d "
         "siIndex=%d\n",
         opId, SensorGlucoseData::restorePollsResultName(restoreCode),
         restoreCode, viewModeBefore, pollcountBefore, siIndexBefore);
  if (restoreCode != SensorGlucoseData::RESTORE_OK)
    return JNI_FALSE;

  info->useCustomCalibration = false;
  info->customCalIndex = 0;
  info->autoResetAlgorithm = false;
  stream->sicontext.clearBadValueStreak();
  sens->exitResetMode();

  const bool rebound = stream->sicontext.reloadFromPersistedState(sens);
  info->viewMode = viewModeBefore;

  LOGGER("siRestoreOriginalPolls[%u]: rebound=%d viewMode=%u->%u siIndex=%d->%d\n",
         opId, rebound ? 1 : 0, viewModeBefore, info->viewMode, siIndexBefore,
         sens->getSiIndex());
  if (rebound) {
    sens->removeBackupPolls();
  }
  return rebound ? JNI_TRUE : JNI_FALSE;
}

static jboolean siRebindNativeContextImpl(jlong dataptr, jint preservedViewMode,
                                          const char *sourceTag,
                                          uint32_t opId) {
  if (!dataptr)
    return JNI_FALSE;
  sistream *stream = reinterpret_cast<sistream *>(dataptr);
  auto *sens = stream->hist;
  if (!sens)
    return JNI_FALSE;
  auto *info = sens->getinfo();
  if (!info)
    return JNI_FALSE;

  const bool wasCustomEnabled = info->useCustomCalibration;
  const uint32_t viewModeBefore = info->viewMode;
  const int siIndexBefore = sens->getSiIndex();
  const int totalPolls = info->pollcount;
  const int resetModeBefore = sens->isInResetMode() ? 1 : 0;

  LOGGER("%s[%u]: start viewMode=%u requested=%d pollcount=%d siIndex=%d "
         "resetMode=%d custom=(enabled=%d,index=%d,autoReset=%d)\n",
         sourceTag, opId, viewModeBefore, preservedViewMode, totalPolls,
         siIndexBefore, resetModeBefore, info->useCustomCalibration,
         info->customCalIndex, info->autoResetAlgorithm);

  // Force native calibration mode before context reload. Do this directly
  // (without setCustomCalibrationSettings JNI path) to avoid toggle
  // side-effects and duplicate writes.
  info->useCustomCalibration = false;
  info->customCalIndex = 0;
  info->autoResetAlgorithm = false;
  stream->sicontext.clearBadValueStreak();

  sens->exitResetMode();
  const bool rebound = stream->sicontext.reloadFromPersistedState(sens);

  uint32_t finalViewMode = viewModeBefore;
  if (preservedViewMode >= 0 && preservedViewMode <= 3) {
    finalViewMode = static_cast<uint32_t>(preservedViewMode);
  }
  info->viewMode = finalViewMode;

  const int siIndexAfter = sens->getSiIndex();
  LOGGER("%s[%u]: done ok=%d wasCustomEnabled=%d viewMode=%u->%u pollcount=%d "
         "siIndex=%d->%d resetMode=%d->%d\n",
         sourceTag, opId, rebound ? 1 : 0, wasCustomEnabled ? 1 : 0,
         viewModeBefore, info->viewMode, totalPolls, siIndexBefore, siIndexAfter,
         resetModeBefore, sens->isInResetMode() ? 1 : 0);
  return rebound ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(siRebindNativeContext)(
    JNIEnv *env, jclass cl, jlong dataptr, jint preservedViewMode) {
  const uint32_t opId = ++gSiRebindOp;
  return siRebindNativeContextImpl(dataptr, preservedViewMode,
                                   "siRebindNativeContext", opId);
}

// Compatibility alias. Semantics now match siRebindNativeContext.
extern "C" JNIEXPORT jboolean JNICALL fromjava(siRestartNativeFresh)(
    JNIEnv *env, jclass cl, jlong dataptr, jint preservedViewMode) {
  const uint32_t opId = ++gSiNativeFreshRestartOp;
  return siRebindNativeContextImpl(dataptr, preservedViewMode,
                                   "siRestartNativeFresh(alias)", opId);
}

#else
bool siInit() { return false; }
#endif
