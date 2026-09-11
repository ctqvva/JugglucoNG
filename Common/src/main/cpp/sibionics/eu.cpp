
#ifdef SIBIONICS
#include "config.h"
#ifdef NOTCHINESE
#include "SensorGlucoseData.hpp"
#include "fromjava.h"
#include "logs.hpp"
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <inttypes.h>
#include <jni.h>
#include <memory>
#include <string_view>
// #include "sibionics/AlgorithmContext.hpp"
#include "datbackup.hpp"
#include "inout.hpp"
#include "jnidef.h"
#include "streamdata.hpp"
/*
static auto reverseaddress(const char address[]) {
   std::array<jbyte,6> uitar;
   auto *uit=uitar.data();
   sscanf(address,"%hhX:%hhX:%hhX:%hhX:%hhX:%hhX",uit+5,uit+4,uit+3,uit+2,uit+1,uit);
    return uitar;
   } */

/*
std::array<jbyte,6>  deviceArray(const char address[]) {
   std::array<jbyte,6> uitar;
   auto *uit=uitar.data();
   sscanf(address,"%hhX:%hhX:%hhX:%hhX:%hhX:%hhX",uit+5,uit+4,uit+3,uit+2,uit+1,uit);
   return uitar;
   } */

#ifndef NOLOG
extern void logbytes(std::string_view text, const uint8_t *value, int vallen);
#else
#define logbytes(text, value, vallen)
#endif

extern JNIEnv *subenv;
extern bool siInit2();

#include "share/hexstr.hpp"

static bool ensureV120DatahandleReady(const char *caller) {
  if (V120SpiltData && v120RegisterKey && V120ApplyAuthentication &&
      V120RawData && V120Activation && V120Reset && V120IsecUpdate) {
    return true;
  }
  if (!siInit2()) {
    LOGGER("%s: siInit2 failed\n", caller);
    return false;
  }
  if (!V120SpiltData || !v120RegisterKey || !V120ApplyAuthentication ||
      !V120RawData || !V120Activation || !V120Reset || !V120IsecUpdate) {
    LOGGER("%s: missing V120 functions split=%p register=%p auth=%p raw=%p "
           "activation=%p reset=%p isec=%p\n",
           caller, reinterpret_cast<void *>(V120SpiltData),
           reinterpret_cast<void *>(v120RegisterKey),
           reinterpret_cast<void *>(V120ApplyAuthentication),
           reinterpret_cast<void *>(V120RawData),
           reinterpret_cast<void *>(V120Activation),
           reinterpret_cast<void *>(V120Reset),
           reinterpret_cast<void *>(V120IsecUpdate));
    return false;
  }
  return true;
}

std::pair<std::unique_ptr<data_t>, int> getActivation(jlong timesec) {
  if (!ensureV120DatahandleReady("getActivation"))
    return {nullptr, 0};
  auto zero = data_t::newex(2);
  zero->clear();
  auto fill = data_t::newex(50);
  int ret = V120Activation(subenv, nullptr, 0, true, (jbyteArray)zero, timesec,
                           1234, (jbyteArray)fill, fill->size());
#ifndef NOLOG
  hexstr zerohex((uint8_t *)zero->data(), zero->size());
  hexstr fillhex((uint8_t *)fill->data(), ret);
  LOGGER("getActivation(%jd) zero=%s res=%s\n", timesec, zerohex.str(),
         fillhex.str());
#endif
  data_t::deleteex(zero);
  return {std::unique_ptr<data_t>(fill), ret};
}
extern "C" JNIEXPORT jbyteArray JNICALL fromjava(getSIActivation)(JNIEnv *env,
                                                                  jclass cl) {
  auto [cmd, len] = getActivation(time(nullptr));
  if (!cmd || len <= 0)
    return nullptr;
  jbyteArray uit = env->NewByteArray(len);
  env->SetByteArrayRegion(uit, 0, len, cmd.get()->data());
  return uit;
}

extern "C" JNIEXPORT jbyteArray JNICALL fromjava(getSIResetBytes)(JNIEnv *env,
                                                                  jclass cl) {
  if (!ensureV120DatahandleReady("getSIResetBytes"))
    return nullptr;
  auto zero = data_t::newex(2);
  zero->clear();
  auto fill = data_t::newex(1024);
  int uitlen = V120Reset(subenv, nullptr, 0, true, (jbyteArray)zero, 0,
                         (jbyteArray)fill, fill->size());
#ifndef NOLOG
  hexstr fillhex((uint8_t *)fill->data(), uitlen);
  LOGGER("V120Reset %s\n", fillhex.str());
#endif
  jbyteArray uit = env->NewByteArray(uitlen);
  env->SetByteArrayRegion(uit, 0, uitlen, fill->data());
  data_t::deleteex(zero);
  data_t::deleteex(fill);
  return uit;
}

/*
But you must add reset memory (it can be button):
byte[] bArr = new byte[1024];
int V120Reset =    CGMDataHandle130.V120Reset(0, true, new byte[2], 0, bArr,
1024); public static native int V120Reset(int i2, boolean z, byte[] bArr, int
i3, byte[] bArr2, int i4); final byte[] bArr2 = new byte[V120Reset];
System.arraycopy(bArr, 0, bArr2, 0, V120Reset);
service2.setValue(bArr2);
bluetoothGatt.writeCharacteristic(service2);

    public static native int V120Activation(int i2, boolean z, byte[] bArr, long
j, int i3, byte[] bArr2, int i4);
*/

static Data_t getIsecUpdate(jlong timesec) {
  if (!ensureV120DatahandleReady("getIsecUpdate"))
    return Data_t(0);
  Data_t zero(2);
  zero.clear();
  Data_t fill(50);
  int ret = V120IsecUpdate(subenv, nullptr, 0, true, zero, timesec, fill,
                           fill.data->size());
#ifndef NOLOG
  hexstr zerohex((uint8_t *)zero.data->data(), zero.data->size());
  hexstr fillhex((uint8_t *)fill.data->data(), ret);
  LOGGER("getIsecUpdate(%jd) zero=%s res=%s\n", timesec, zerohex.str(),
         fillhex.str());
#endif
  fill.used = ret;
  return fill;
}
extern "C" JNIEXPORT jbyteArray JNICALL fromjava(getSItimecmd)(JNIEnv *env,
                                                               jclass cl) {
  auto cmd = getIsecUpdate(time(nullptr));
  int len = cmd.used;
  if (len <= 0)
    return nullptr;
  jbyte *dat = cmd.data->data();
  jbyteArray uit = env->NewByteArray(len);
  env->SetByteArrayRegion(uit, 0, len, dat);
  return uit;
}

static void keysRegistered(int hema) {
  static constexpr const struct {
    std::string_view appid;
    std::string_view key;
  } appgegs[]{
      {"com.sisensing.sijoy"sv,
       "56CE249349040C94F8B4B2375A8752D5CBE7A17814B502D9132489C0BFDFC99F0CAC670E8CBB085AF1C780B3D282E3"sv}, // EU
      {"com.sisensing.rusibionics"sv,
       "60B05FEB7C0A148DEED2B3375A8754D9D0E6A5751BCE02D9132489C0BFDFC99F0CAC670E8DA7115CEACF87B7DE8FD4612E1B7638C2"sv}, // Hematonix
      {"com.sisensing.sisensingcgm"sv,
       "4E8E1CAF43051F97EEC9C1475A8752D5C387D17A65B002D9132489C0BFDFC99F0CAC670E8CBB1150E6D581B7D08FC03404052C57AD58"sv}, // Chinese
      {"com.sisensing.eco"sv,
       "068449FA5C1B1F97EEC9C1475A8752D5C387D17A65B002D9132489C0BFDFC99F0CAC670E9AB10D62FDE0B2B1E7"sv}}; // Sibionics 2
  const auto &gegs = appgegs[hema];

  data_t *sijkey = data_t::newex(gegs.key);
  data_t *name = data_t::newex(gegs.appid);
#ifndef NOLOG
  LOGGER("v120RegisterKey %.*s %.*s size=%d \n", sijkey->size(), sijkey->data(),
         name->size(), name->data(), name->size());
#endif
  v120RegisterKey(subenv, nullptr, (jbyteArray)sijkey, sijkey->size(),
                  (jbyteArray)name);
  LOGAR(" na v120RegisterKey");
  data_t::deleteex(name);
  data_t::deleteex(sijkey);
}

std::array<jbyte, 6> deviceArray(const char address[]);

Data_t makeauthbytes(const char *address, int hema) {
  if (!ensureV120DatahandleReady("makeauthbytes"))
    return Data_t(0);
  keysRegistered(hema);
  auto rev = deviceArray(address);
  Data_t jrev(rev);
  Data_t uitar(50);
  int uitlen = V120ApplyAuthentication(subenv, nullptr, 1, true, 0, jrev, uitar,
                                       uitar.capacity());
  uitar.used = uitlen;
  return uitar;
}
extern "C" JNIEXPORT jint JNICALL
fromjava(getSensorptrSiSubtype)(JNIEnv *env, jclass cl, jlong sensorptr) {
  if (!sensorptr) {
    LOGAR("getSensorptrSiSubtype sensorptr==0");
    return -1;
  }
  return reinterpret_cast<const SensorGlucoseData *>(sensorptr)->siSubtype();
}
extern "C" JNIEXPORT void JNICALL fromjava(setSensorptrSiSubtype)(
    JNIEnv *env, jclass cl, jlong sensorptr, jint type) {
  if (!sensorptr) {
    LOGAR("setSiSubtype sensorptr==0");
    return;
  }
  LOGGER("setSensorptrSiSubtype %d\n", type);
  auto *usedhist = reinterpret_cast<SensorGlucoseData *>(sensorptr);
  auto *info = usedhist->getinfo();
  info->siType = type;
  if (type == 3) {
    info->notchinese = true;
    LOGGER("setSensorptrSiSubtype %d forced notchinese\n", type);
  } else if (type == 2) {
    info->notchinese = false;
    LOGGER("setSensorptrSiSubtype %d forced chinese\n", type);
  }
  if (type != 3) {
    info->reset = false;
  }
  LOGGER("after usedhist->getinfo()->siType %d\n", type);
  sendsiScan(usedhist);
  LOGAR("sendsiScan(usedhist)");
  if (backup)
    backup->wakebackup(Backup::wakeall);
  LOGAR("wakebackup(Backup::wakeall)");
}
extern "C" JNIEXPORT jint JNICALL fromjava(getSiSubtype)(JNIEnv *env, jclass cl,
                                                         jlong dataptr) {
  if (!dataptr) {
    LOGAR("getSiSubtype dataptr==0");
    return -1;
  }
  const SensorGlucoseData *usedhist =
      reinterpret_cast<streamdata *>(dataptr)->hist;
  if (!usedhist) {
    LOGAR("getSiSubtype usedhist==null");
    return -1;
  }
  return usedhist->siSubtype();
}
/*
extern "C" JNIEXPORT void JNICALL   fromjava(setSiSubtype)(JNIEnv *env, jclass
cl,jlong dataptr,jint type) { if(!dataptr) { LOGAR("setSiSubtype dataptr==0");
    return;
    }
SensorGlucoseData *usedhist=reinterpret_cast<streamdata *>(dataptr)->hist ;
if(!usedhist) {
    LOGAR("setSiSubtype usedhist==null");
    return;
    }
LOGGER("setSiSubtype %d\n",type);
usedhist->getinfo()->siType=type;
LOGGER("after usedhist->getinfo()->siType %d\n",type);
sendsiScan(usedhist);
LOGAR("sendsiScan(usedhist)");
if(backup)
    backup->wakebackup(Backup::wakeall);
LOGAR("wakebackup(Backup::wakeall)");

}
*/
extern "C" JNIEXPORT jbyteArray JNICALL fromjava(siAuthBytes)(JNIEnv *env,
                                                              jclass cl,
                                                              jlong dataptr) {
  if (!dataptr) {
    LOGAR("siAuthBytes dataptr==null");
    return nullptr;
  }
  const SensorGlucoseData *usedhist =
      reinterpret_cast<streamdata *>(dataptr)->hist;
  if (!usedhist) {
    LOGAR("siAuthBytes usedhist==null");
    return nullptr;
  }
  const auto address = usedhist->deviceaddress();
  const auto data = makeauthbytes(address, usedhist->siSubtype());
  if (data.used <= 0) {
    LOGGER("siAuthBytes failed len=%d\n", data.used);
    return nullptr;
  }
  const auto *dat = data.data->data();
  const int len = data.used;
  logbytes("siAuthBytes", (const uint8_t *)dat, len);
  jbyteArray uit = env->NewByteArray(len);
  env->SetByteArrayRegion(uit, 0, len, dat);
  return uit;
}

extern Data_t askindexdata(jlong index);
Data_t askindexdata(jlong index) {
  if (!ensureV120DatahandleReady("askindexdata"))
    return Data_t(0);
  Data_t dat(30);
  Data_t zero(2);
  int res = V120RawData(subenv, nullptr, 0, true, (jbyteArray)zero, index, 0,
                        (jbyteArray)dat, dat.capacity());
  dat.used = res;
  return dat;
}

#ifdef TEST
#define THREADLOCAL
#else
#define THREADLOCAL thread_local
#endif
static THREADLOCAL jlong sprintargs[2048];
static THREADLOCAL int recordsprint = -1;
#define VISIBLE __attribute__((__visibility__("default")))
using vsprintf_chk_t = int (*)(char *, int, size_t, const char *, va_list);

static vsprintf_chk_t realVsprintfChk() {
  static vsprintf_chk_t real = reinterpret_cast<vsprintf_chk_t>(
      dlsym(RTLD_NEXT, "__vsprintf_chk"));
  return real;
}

static int recordAndFormatSprintf(char *s, int flag, size_t slen,
                                  const char *format, va_list args) {
  const bool recording = recordsprint >= 0;
  if (!recording) {
    va_list passthroughArgs;
    va_copy(passthroughArgs, args);
    if (auto real = realVsprintfChk()) {
      const int res = real(s, flag, slen, format, passthroughArgs);
      va_end(passthroughArgs);
      return res;
    }
    const size_t fallbackSize =
        slen == static_cast<size_t>(-1) ? 4096 : slen;
    const int res = std::vsnprintf(s, fallbackSize, format, passthroughArgs);
    va_end(passthroughArgs);
    return res;
  }
  if (recording &&
      recordsprint < (int)(sizeof(sprintargs) / sizeof(sprintargs[0]))) {
    va_list newargs;
    va_copy(newargs, args);
    jlong val = va_arg(newargs, jlong);
    sprintargs[recordsprint++] = val;
    va_end(newargs);
  }
  va_list formatArgs;
  va_copy(formatArgs, args);
  // Calling vsprintf here resolves back into this exported fortify shim on
  // Bionic, so use a large explicit bound for unknown-size sprintf callers.
  constexpr size_t unknownDestFallbackSize = 4096;
  const size_t formatSize =
      slen == static_cast<size_t>(-1) ? unknownDestFallbackSize : slen;
  const int res = std::vsnprintf(s, formatSize, format, formatArgs);
  va_end(formatArgs);
  if (recording) {
    LOGGER(" __vsprintf_chk(%s (%p),%d,%zu,%s,va_list)=%d\n", s, s, flag,
           slen, format, res);
  }
  return res;
}
extern "C" int VISIBLE __vSprintf_chk(char *s, int flag, size_t slen,
                                      const char *format, va_list args) {
  return recordAndFormatSprintf(s, flag, slen, format, args);
}
extern "C" int VISIBLE __vsprintf_chk(char *s, int flag, size_t slen,
                                      const char *format, va_list args) {
  return recordAndFormatSprintf(s, flag, slen, format, args);
}
#include <vector>
extern int sitrend2abbott(int sitrend);
extern float sitrend2RateOfChange(int sitrend);

// #include "sibionics/json.hpp"
// extern bool savejson(SensorGlucoseData *sens,std::string_view, int
// index,const AlgorithmContext *alg,getjson_t getjson ); extern getjson_t
// getjson2; extern jlong glucoseback(uint32_t glval,float
// drate,SensorGlucoseData *hist) ;
extern jlong glucoseback(uint32_t nu, uint32_t glval, float drate,
                         SensorGlucoseData *hist);

static bool saveRawOnlyPoll(SensorGlucoseData *sens, time_t eventTime,
                            int streamIndex, int rawCurrent,
                            uint16_t rawTemp) {
  if (!sens || eventTime <= 0 || rawCurrent <= 0) {
    return false;
  }
  sens->savestream(eventTime, streamIndex, 0, 0, 0.0f, rawCurrent, rawTemp);
  sens->sensorerror = false;
  sens->sensorErrorTime = 0;
  LOGGER("SIprocess raw-only: index=%d raw=%d temp=%u itime=%ld\n",
         streamIndex, rawCurrent, rawTemp, (long)eventTime);
  if (backup) {
    backup->wakebackup(Backup::wakestream);
  }
  extern void wakewithcurrent();
  wakewithcurrent();
  return true;
}

static bool validSiStartTime(uint32_t startTime) {
  return startTime > 1598911200u && startTime < 2145909600u;
}

static bool validSiPacketStart(uint32_t startTime, time_t eventTime) {
  return validSiStartTime(startTime) && startTime <= (uint32_t)eventTime;
}

static void updateStartTimeFromAcceptedPacket(SensorGlucoseData *sens,
                                              sensor *sensor, int index,
                                              time_t eventTime, int reindex) {
  if (!sens || !sensor || index < 0 || eventTime <= 1598911200) {
    return;
  }
  auto *info = sens->getinfo();
  if (!info) {
    return;
  }

  const uint64_t packetAgeSeconds = static_cast<uint64_t>(index) * 60ULL;
  if (packetAgeSeconds > static_cast<uint64_t>(eventTime)) {
    return;
  }
  const uint32_t packetStart =
      static_cast<uint32_t>(eventTime - packetAgeSeconds);
  if (!validSiPacketStart(packetStart, eventTime)) {
    return;
  }

  constexpr uint32_t startDriftTolerance = 30;
  const bool currentPacket = reindex == 0;
  const bool earlyPacket = index <= 9;
  if (!currentPacket && !earlyPacket) {
    return;
  }

  const uint32_t oldInfoStart = info->starttime;
  const uint32_t infoDiff = oldInfoStart > packetStart
                                ? oldInfoStart - packetStart
                                : packetStart - oldInfoStart;
  const bool validInfoStart = validSiStartTime(oldInfoStart);
  const bool updateInfo =
      !validInfoStart ||
      (infoDiff > startDriftTolerance &&
       (currentPacket || packetStart > oldInfoStart + startDriftTolerance));

  const uint32_t oldListStart = sensor->starttime;
  const uint32_t listDiff = oldListStart > packetStart
                                ? oldListStart - packetStart
                                : packetStart - oldListStart;
  const bool validListStart = validSiStartTime(oldListStart);
  const bool updateList =
      !validListStart ||
      (listDiff > startDriftTolerance &&
       (currentPacket || packetStart > oldListStart + startDriftTolerance));

  if (!updateInfo && !updateList) {
    return;
  }

  if (updateInfo)
    info->starttime = packetStart;
  if (updateList)
    sensor->starttime = packetStart;
  sensors->setindices();
  if (backup)
    backup->resendResetDevices(&updateone::sendstream);
  LOGGER("SIprocess updated starttime from packet old=%u/%u new=%u index=%d "
         "reindex=%d itime=%ld\n",
         oldInfoStart, oldListStart, packetStart, index, reindex,
         (long)eventTime);
}

static bool splitJsonValue(const char *json, const char *key, jlong &value) {
  if (!json || !key) {
    return false;
  }

  char needle[64];
  const int needleLen =
      std::snprintf(needle, sizeof(needle), "\"%s\":", key);
  if (needleLen <= 0 || needleLen >= (int)sizeof(needle)) {
    return false;
  }

  const char *pos = std::strstr(json, needle);
  if (!pos) {
    return false;
  }
  pos += needleLen;
  while (*pos == ' ' || *pos == '\t') {
    ++pos;
  }

  char *end = nullptr;
  const long long parsed = std::strtoll(pos, &end, 10);
  if (end == pos) {
    return false;
  }
  value = (jlong)parsed;
  return true;
}

static void splitJsonValueOrZero(const char *json, const char *key,
                                 jlong &value) {
  value = 0;
  splitJsonValue(json, key, value);
}

static bool fillSplitFieldsFromJson(const char *json, int nritems, int *idat,
                                    jlong *basear) {
  if (!json || nritems != 1 || !idat || !basear) {
    return false;
  }

  jlong ackType = 0;
  if (splitJsonValue(json, "u16reply_ack_type", ackType)) {
    jlong ackResult = 0;
    jlong errorCode = 0;
    if (!splitJsonValue(json, "u8reply_ack_resule", ackResult)) {
      splitJsonValue(json, "u8reply_ack_result", ackResult);
    }
    splitJsonValue(json, "u8error_code", errorCode);

    idat[0] = 49165;
    idat[1] = 0;
    basear[0] = ackType;
    basear[1] = ackResult;
    basear[2] = errorCode;
    return true;
  }

  jlong index = 0;
  jlong temp = 0;
  jlong current = 0;
  jlong itime = 0;
  if (!splitJsonValue(json, "index", index) ||
      !splitJsonValue(json, "temp", temp) ||
      !splitJsonValue(json, "current", current) ||
      !splitJsonValue(json, "itime", itime)) {
    return false;
  }

  idat[0] = 49159;
  idat[1] = 0;
  basear[0] = index;
  basear[1] = temp;
  basear[2] = current;
  splitJsonValueOrZero(json, "dump", basear[3]);
  splitJsonValueOrZero(json, "reindex", basear[4]);
  splitJsonValueOrZero(json, "glouse", basear[5]);
  splitJsonValueOrZero(json, "trend", basear[6]);
  splitJsonValueOrZero(json, "gwarn", basear[7]);
  splitJsonValueOrZero(json, "twarn", basear[8]);
  splitJsonValueOrZero(json, "cwarn", basear[9]);
  basear[10] = itime;
  return true;
}

jlong SiContext::processData2(SensorGlucoseData *sens, time_t nowsecs,
                              data_t *data, int sensorindex) {
  if (!ensureV120DatahandleReady("processData2"))
    return 2LL;
  const int datasize = data->size();
  logbytes("processData2 input: ", (const uint8_t *)data->data(), datasize);
  Gegs<jint> jiar(2);
  Data_t jsonuit(7168);
  Data_t bar2(2);

  memset(jiar.data->data(), '\0', sizeof(jint) * jiar.data->size());
  memset(jsonuit.data->data(), '\0', jsonuit.data->size());
  memset(bar2.data->data(), '\0', bar2.data->size());
  memset(sprintargs, '\0', sizeof(sprintargs));
  recordsprint = 0;
  int nritems =
      V120SpiltData(subenv, nullptr, 0, (jbyteArray)data, (jintArray)jiar,
                    (jbyteArray)jsonuit, true, (jbyteArray)bar2, datasize);
  if (nritems <= 0) {
    nritems =
        V120SpiltData(subenv, nullptr, 0, (jbyteArray)data, (jintArray)jiar,
                      (jbyteArray)jsonuit, false, (jbyteArray)bar2, datasize);
  }
  const int recorded = recordsprint;
  recordsprint = -1;
  auto *json = reinterpret_cast<char *>(jsonuit.data->data());
  if (jsonuit.data->size() > 0) {
    json[jsonuit.data->size() - 1] = '\0';
  }
#ifndef NOLOG
  static THREADLOCAL uint32_t splitLogCounter = 0;
  const bool logSplitDetail = (nritems <= 0) || ((++splitLogCounter & 0x3F) == 0);
  if (logSplitDetail) {
    LOGGER("nritems=%d\n", nritems);
    if (nritems <= 0) {
      LOGGER("%s\n", jsonuit.data->data());
      logbytes("bar2", (const uint8_t *)bar2.data->data(), bar2.data->size());
    }
  }
#else
  constexpr bool logSplitDetail = false;
#endif
  int *idat = jiar.data->data();
  jlong *basear = sprintargs;
  const bool parsedSplitJson =
      fillSplitFieldsFromJson(json, nritems, idat, basear);
#ifndef NOLOG
  if (parsedSplitJson && recorded <= 0) {
    LOGGER("SIprocessData2 json fallback type=%d first=%" PRId64 "\n", idat[0],
           basear[0]);
  }
#endif
#ifndef NOLOG
  if (logSplitDetail) {
    {
      char tmpbuf[80];
      const char str[] = "jiar:";
      memcpy(tmpbuf, str, sizeof(str) - 1);
      char *ptr = tmpbuf + sizeof(str) - 1;
      for (int i = 0; i < jiar.data->size(); i++) {
        ptr += sprintf(ptr, " %d", idat[i]);
      }
      *ptr++ = '\n';
      LOGGERN(tmpbuf, ptr - tmpbuf);
    }
  }
#endif
  switch (idat[0]) {
  case 49159: {
    sensor *sensor = sensors->getsensor(sensorindex);
    for (int i = 0; i < nritems; i++) {
      int maxid = sens->getSiIndex();
      int index = (int)basear[0];
      time_t eventTime = basear[10];
      if (index != maxid) {
        if (index < maxid) {
          LOGGER("SIprocess index=%d<maxid=%d\n", index, maxid);
          uint32_t lasttime = sens->getlastpolltime();
          if (eventTime > lasttime) {
            int larger = maxid - index;
            int add2index = 5 * ((larger + 4) / 5);
            sens->setSiAdd2Index(add2index);
            maxid = index;
            sens->setSiIndex(index);
            LOGGER("index=%d setSiAdd2Index(add2index=%d)\n", index, add2index);
          } else {
            return 3LL;
          }
        } else {
          // If in reset mode, this Gap is expected.
          // 1. Request History (to fill the gap).
          // 2. JUMP the gap (update maxid) and Process this packet (for UI).
          if (sens->isInResetMode()) {
            LOGSTRING("SIprocess Gap due to Reset. Requesting History & "
                      "Jumping Gap.\n");
            backup->resendResetDevices(&updateone::sendstream);

            // Accept the new index, jumping over the missing history for now.
            maxid = index;
            sens->setSiIndex(index);
            // Fall through to process stream...
          } else {
            LOGGER("SIprocess index=%d>maxid=%d\n", index, maxid);
            // Original Juggluco logic: retry more on larger gaps
            int maxretry =
                (index - maxid) < 20 ? 2 : ((index - maxid) < 200 ? 5 : 10);
            if (sens->retried++ < maxretry) {
              return 3LL;
            }
            // Retries exhausted, fall through to process
          }
        }
      }
      double temp = basear[1] / 10.0;
      auto current = basear[2];
      double value = current / 10.0;
      int reindex = (int)basear[4];
      const bool logPacket = (reindex == 0) || ((index & 0x3F) == 0);
      if (logPacket) {
        LOGGER("current=%" PRId64 " %.1f mmol/L\n", current, value);
      }
      auto trend = (int)basear[6];

      // Initialize deterministically. Later branches still decide whether the
      // sample is usable; do not silently substitute raw as a calibrated value.
      double newvalue = 0.0;
      if (algcontext) {
        // Original Juggluco logic - process2 result goes directly into newvalue
        // check
        if (current > 1 && value < 3000.0 &&
            (newvalue = process2(index, value, temp)) > 1 && (index % 5 == 0)) {
          sens->getinfo()->pollinterval = newvalue - value;
        } else {
          if (sens->getinfo()->pollinterval < 40)
            newvalue = value + sens->getinfo()->pollinterval;
        }
      } else {
        LOGAR("algcontext==null");
        newvalue = value;
      }

      // Sanity check with streak counter - only reset after consecutive bad
      // values (per-sensor via SiContext::badValueStreak member)
      constexpr int MAX_BAD_STREAK = 5;
      constexpr int RESET_COOLDOWN_SECS = 300; // 5 min cooldown between resets
      if (newvalue > 50.0) {
        badValueStreak++;
        LOGGER("SIprocess: newvalue=%f is out of range. Streak=%d\n", newvalue,
               badValueStreak);
        if (badValueStreak >= MAX_BAD_STREAK) {
          // Edit 85: Skip reset if already in reset mode (replaying history).
          // During replay, the algorithm processes old data with a fresh context
          // and regularly produces out-of-range values — this is expected and
          // should NOT trigger another reset (which would loop).
          if (sens->isInResetMode()) {
            LOGGER("SIprocess: in reset mode, ignoring bad streak (%d)\n",
                   badValueStreak);
          } else {
            // Edit 79: Cooldown guard — prevent reset loop after fresh algorithm start
            uint32_t lastReset = sens->getinfo()->lastCalResetTime;
            uint32_t now = (uint32_t)time(nullptr);
            if (lastReset > 0 && (now - lastReset) < RESET_COOLDOWN_SECS) {
              LOGGER("SIprocess: reset cooldown active (%d sec since last reset), skipping\n",
                     (int)(now - lastReset));
            } else {
              LOGGER("SIprocess: %d consecutive bad values, resetting algorithm\n",
                     badValueStreak);
              // Only reset when custom calibration is active — in native mode
              // (Auto), bad streaks are transient and the algorithm self-corrects.
              if (sens->getinfo()->useCustomCalibration) {
                this->reset(sens);
              } else {
                LOGSTRING("SIprocess: custom cal not enabled, skipping reset\n");
              }
            }
          }
          badValueStreak = 0;
        }
        // Fallback to raw sensor value
        newvalue = value;
      } else {
        // Good value - reset streak
        badValueStreak = 0;
      }
      const bool rawFallbackUsable = !(newvalue > 1 && newvalue < 30) &&
                                     value > 1.0 && value < 30.0;
      const double persistedValue = rawFallbackUsable ? value : newvalue;
      const int mgdL = std::round(persistedValue * convfactordL);
      const int trend2 = algcontext ? algcontext->ig_trend : trend;
      const float change = sitrend2RateOfChange(trend2);
      const int abbottrend = sitrend2abbott(trend2);
      const int totalIndex = sens->siAddedIndex(index);
      if (logPacket) {
        LOGGER("totalIndex=%d index=%d temp=%f value=%f newvalue=%f "
               "trend=(%d?) %d %d %1.f itime=%" PRIu64 " %s",
               totalIndex, index, temp, value, newvalue, trend, trend2,
               abbottrend, change, eventTime, ctime(&eventTime));
      }
      // Valid range check: 0.5-40 mmol/L. Values outside trigger sensorerror.
      // Aligned with sanity check above which auto-resets for same
      // thresholds.
      if (persistedValue > 1 && persistedValue < 30) {
        if (rawFallbackUsable) {
          LOGGER("SIprocess raw fallback: index=%d temp=%f value=%f "
                 "reindex=%d\n",
                 index, temp, value, reindex);
        }
        sens->savestream(eventTime, totalIndex, mgdL, abbottrend, change,
                         (int)current, (uint16_t)basear[1]);
        updateStartTimeFromAcceptedPacket(sens, sensor, index, eventTime,
                                          reindex);
        sens->setSiIndex(index + 1);
        sens->retried = 0;
        if (!reindex) {
          // Current-time data (not replay). If we were in reset mode,
          // replay is done — exit reset mode so normal operation resumes.
          if (sens->isInResetMode()) {
            LOGGER("SIprocess (eu): replay reached current data (eventTime=%ld), exiting reset mode\n", (long)eventTime);
            sens->exitResetMode();
          }
          sens->sensorerror = false;
          if (sensor->finished && sensor->reactivateFromData()) {
            LOGSTRING("SIprocess finished becomes 0\n");
            backup->resensordata(sensorindex);
          }

          int notifyMgdL = mgdL;
          if (sens->getinfo()->viewMode == 1) {
            notifyMgdL = std::round(value * convfactordL);
          }
          auto res = glucoseback(eventTime, notifyMgdL, change, sens);
          /*                     if(!(index%5))  {
                                  if(algcontext)
                                      savejson(sens,sens->statefile,index,algcontext,getjson2);
                                  } */
          backup->wakebackup(Backup::wakestream);
          extern void wakewithcurrent();
          wakewithcurrent();
          return res;
        } else {
          /*                   if(!(index%500)) {
                                  if(algcontext) {
                                     //
             savejson(sens,sens->statefile,index,algcontext,getjson2);
                                      backup->wakebackup(Backup::wakestream);
                                      }
                                  } */
          sens->receivehistory = nowsecs;
        }
        const int last = sens->pollcount() - 1;
        if (last < sens->getbroadcastfrom())
          sens->setbroadcastfrom(last);
      } else {
        if (index == maxid)
          sens->setSiIndex(maxid + 1);
        LOGGER("SIprocess failed: index=%d temp=%f value=%f reindex=%d\n",
               index, temp, value, reindex);
        const bool savedRawOnly =
            saveRawOnlyPoll(sens, eventTime, totalIndex, (int)current,
                            (uint16_t)basear[1]);
        if (savedRawOnly) {
          updateStartTimeFromAcceptedPacket(sens, sensor, index, eventTime,
                                            reindex);
        }
        if (!reindex && savedRawOnly) {
          sens->receivehistory = nowsecs;
          return 11LL;
        }
        if (!reindex && !(index % 5)) {
          sens->sensorerror = true;
          sens->sensorErrorTime = nowsecs;
          return 0LL;
        }
      }
      basear += 11;
    } // for loop
    return 1LL;
  }; break;
  case 49165: {
    int type = (int)basear[0];
    switch (type) {
    case 49161:
      return 9LL;
    case 49156:
      return 7LL;
    case 49153:
      return 4LL;
    case 49154: {
      if (basear[1] != 1) {
        int error = (int)basear[2];
        if (error != 9 && error != 10)
          return 4LL;
      }
      return 6LL;
    };
    case 49160:
      return 5LL;
    };

  }; break;
  case 49227: {
    if (sens->siSubtype() == 3) {
      return 10LL;
    }
    return 6LL; // Never used??
  }
  }
  return 8LL; ///?
}

#else
#include "fromjava.h"
#include <jni.h>
extern "C" JNIEXPORT jbyteArray JNICALL fromjava(getSIActivation)(JNIEnv *env,
                                                                  jclass cl) {
  return nullptr;
}
extern "C" JNIEXPORT jbyteArray JNICALL fromjava(getSItimecmd)(JNIEnv *env,
                                                               jclass cl) {
  return nullptr;
}
extern "C" JNIEXPORT jbyteArray JNICALL fromjava(siAuthBytes)(JNIEnv *env,
                                                              jclass cl,
                                                              jlong dataptr) {
  return nullptr;
}
#endif
#endif
