
#define setthreadname(buf) prctl(PR_SET_NAME, buf, 0, 0, 0)
/*#ifndef NOLOG
#define TESTGEN2 1
#endif       */
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
/*      Fri Jan 27 12:35:35 CET 2023 */

//
// Created by jka on 27-11-20.
//
#include <cinttypes>
#include <future>
#include <jni.h>
#include <map>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>
extern "C" {
typedef void (*sighandler_t)(int);

sighandler_t bsd_signal(int signum, sighandler_t handler);
};
#define asignal signal
// #include <sys/syscall.h>
// #undef NOLOG
#include "fromjava.h"
#include "inout.hpp"
#include "libre2.hpp"
#include "logs.hpp"
#include "serial.hpp"
#include "streamdata.hpp"

#include "datbackup.hpp"
#include "error_codes.h"
#include "exchangetrend.hpp"
#include "settings/settings.hpp"
#ifdef DYNLINK
#define abbottdec(x) (*x)
#define abbottcall(x) x
#else
#define abbottdec(x)                                                           \
  Java_com_abbottdiabetescare_flashglucose_sensorabstractionservice_dataprocessing_DataProcessingNative_##x
#define abbottcall(x)                                                          \
  Java_com_abbottdiabetescare_flashglucose_sensorabstractionservice_dataprocessing_DataProcessingNative_##x
#endif
extern jbyteArray getjtoken(JNIEnv *env);
extern "C" JNIEXPORT jlong JNICALL abbottdec(getStatusCode)(
    JNIEnv *env, jobject obj, jstring serial, jint elapsed1, jint elapsed2,
    jint jint4, jboolean endedearly);

// extern string getstatus(JNIEnv *env, jobject thiz, const char *serial,time_t
// start,time_t lastread,time_t nu,bool endedearly) ;
void testserial(JNIEnv *env, jobject thiz);
#ifdef NDEBUG
#define EXTRA 50
#else
#define EXTRA 20
#endif
bool setbluetoothon = false;
extern void setusedsensors();
#include "debugclone.hpp"
extern int timestr(char *buf, time_t tim);
extern lastscan_t scantoshow;
// JNIEXPORT void JNICALL Java_tk_glucodata_Glucose_nfcdata(JNIEnv *env, jobject
// thiz, jbyteArray uid, jbyteArray info,jbyteArray data) { JNIEXPORT int
// JNICALL Java_tk_glucodata_Natives_nfcdata(JNIEnv *env, jclass thiz,
// jbyteArray uid, jbyteArray info,jbyteArray data) {
#if defined(SCANLOG) //|| defined(__arm__)

class scanlogger {
  int senslen;
  char buf[1024];
  const scandata *datptr;
  unsigned long issame;
  static constexpr const char rawin[]{"rawin.dat"};

public:
  void firstdata(const std::string_view sensordir, const scandata *datptr) {
    this->datptr = datptr;
    senslen = sensordir.length();
    //        buf=new char[senslen+30+EXTRA];
    time_t tim = datptr->gettime();
    memcpy(buf, sensordir.data(), senslen);
    buf[senslen++] = '/';
    senslen += timestr(buf + senslen, tim);
    mkdir(buf, 0700);
    buf[senslen++] = '/';
    memcpy(buf + senslen, rawin, sizeof(rawin));
    issame = *reinterpret_cast<unsigned long *>(datptr->data->data());
    writeall(buf, datptr->data->data(), datptr->data->size());
    const char infostr[]{"info.dat"};
    memcpy(buf + senslen, infostr, sizeof(infostr));
    writeall(buf, datptr->info->data(), datptr->info->size());
  }
  void lastdata() {
    const char raw[]{"raw.dat"};
    if (issame != *reinterpret_cast<unsigned long *>(datptr->data->data())) {
      memcpy(buf + senslen, raw, sizeof(raw));
      writeall(buf, datptr->data->data(), datptr->data->size());
    } else {
      buf[senslen] = '\0';
      int dir = open(buf, O_DIRECTORY | O_PATH);
      if (dir > 0) {
        if (renameat(dir, rawin, dir, raw) != 0) {
          lerror("renameat rawin.dat raw.dat");
        }
        close(dir);
      } else
        lerror(buf);
    }
    //       delete[] buf;
  }
};
#if defined(SCANLOG)
inline constexpr const static bool logscan = true;
#else
bool logscan = false;
#endif
#else

class scanlogger {
public:
  void firstdata(const std::string_view sensordir, const scandata *datptr) {}
  void lastdata() {}
};
#define logscan false
#endif
#ifdef LOGSCANRESULT
void AlgorithmResults::showresults(FILE *stream, scandata *dat) const {
  time_t nutime = dat->gettime();
  int nuid = glu->id;
  fprintf(stream, "History:\n");
  constexpr const int maxtim = 17;
  char buf[maxtim];
  jint len = hist->size();
  int uselen = std::min(history::num, len);
  for (int i = 0; i < uselen; i++) {
    const GlucoseValue *g = (*hist)[i];
    int gv = g->value;
    int id = g->id;
    time_t was = nutime - (nuid - id) * 60;
    uint16_t raw = dat->gethistoryglucose(i);

    showtime(&was, buf);
    fprintf(stream, "Glucose %d\t%.1f\t(%d\t%.1f)\t%d\t%d\t%s\n", gv,
            gv / convfactordL, (int)roundf(raw / 10.0), raw / 180.0, id,
            g->dataQuality, buf);
  }
  showtime(&nutime, buf);
  fputc('\n', stream);
  for (int i = 0; i < trend::num; i++) {
    uint16_t raw = dat->gettrendglucose(i);
    fprintf(stream, "%.1f ", raw / 180.0);
  }
  fprintf(stream, "\n%s Nu Glucose %.1f %s %f %d %d\n", buf,
          (float)glu->value / convfactordL, glu->trendstr(), glu->rate(), nuid,
          glu->dataQuality);
  fflush(stream);
}
static void logscanresult(const AlgorithmResults *alg) {
  extern pathconcat logbasedir;
  static pathconcat logfile(logbasedir, "uit.txt");
  if (mkdir(logbasedir.data(), 0700) && errno != EEXIST) {
    LOGGER("mkdir(%s) failed\n", logbasedir.data());
  } else {
    FILE *fp = fopen(logfile.data(), "a");
    alg->showresults(fp, datptr);
    fclose(fp);
  }
}
#else
#define logscanresult(alg)
#endif
pid_t nfcdatatid = 0;
thread_local jmp_buf jumpenv;
thread_local bool jumpenvset = false;
void usr2handler(int get) {
  int tid = syscall(SYS_gettid);
  LOGGER("handler: %d\n", tid);
  if (jumpenvset) {
    asignal(get, SIG_IGN);
    longjmp(jumpenv, tid);
  }
  LOGSTRING("no jump\n");
}

constexpr const int usesig = SIGUSR2;
void alarmhandler(int sig) {
#ifndef NOLOG
  pid_t tid = syscall(SYS_gettid);
  LOGGER("Alarm %d\n", tid);
#endif
  if (nfcdatatid != 0) {
    asignal(SIGALRM, SIG_IGN);
    pid_t grid = syscall(SYS_getpid);
    if (tgkill(grid, nfcdatatid, usesig))
      lerror("tgkill");
  }
}
// s/setthreadname(\([^);
extern void setstreaming(SensorGlucoseData *hist);
extern void wakeaftermin(const int waitmin);
extern "C" JNIEXPORT jint JNICALL fromjava(nfcdata)(JNIEnv *env, jclass thiz,
                                                    jbyteArray uid,
                                                    jbyteArray info,
                                                    jbyteArray data) {
  nfcdatatid = syscall(SYS_gettid);
  LOGGER("nfcdata %d\n", nfcdatatid);
  setthreadname("NFC");
  destruct dest([]() {
    nfcdatatid = 0;
    asignal(SIGALRM, SIG_IGN);
    asignal(usesig, SIG_IGN);
  });
  asignal(usesig, usr2handler);
  asignal(SIGALRM, alarmhandler);
  static const int waitsig = 60;
  alarm(waitsig);
  if (setjmp(jumpenv) == nfcdatatid) {
    LOGSTRING("after jump");
    return 12 << 16;
  }
  jumpenvset = true;

  if (abbottinit() < 0) {
    LOGAR("abbottinit failed");
    return 10 << 16;
  }
  LOGSTRING("voor Abbott:Abbott\n");
  time_t tim = time(nullptr);
  std::unique_ptr<scandata> unidatptr(new scandata(env, info, data, tim));
  scandata *datptr = unidatptr.get();
  Abbott ab(env, sensorbasedir, uid, datptr->info);
  LOGSTRING("Na Abbott:Abbott\n");
  if (ab.error()) {
    LOGSTRING("Error in Abbott::Abbott\n");
    return 11 << 16;
  }

  /*
      timevalues times= patchtimevalues(datptr->info) ;
      if(times.wear>0) {
          LOGGER("warmup=%d,wear=%d\n",times.warmup,times.wear);
          } */

  scanlogger logs;
#ifdef SCANLOG
  if (logscan) {
    logs.firstdata(ab.getsensordir(), datptr);
  }
#endif
  auto [alg, scanda] = ab.ProcessOne(datptr);
  // Abbott::scanresult_t
  //     const AlgorithmResults *alg; const ScanData *scanda;
  LOGSTRING("after ab.ProcessOne\n");
  if (logscan) {
    logs.lastdata();
  }
  LOGSTRING("after lastdata()\n");
  destruct _back([]() { backup->wakebackup(Backup::wakeall); });
  int ret = 2 << 16;
  if (alg == Initialized) {
    LOGSTRING("Initialized\n");
    const int min = datptr->getSensorAgeInMinutes();
    const bool enablestreaming =
        setbluetoothon || (ab.hist && !ab.hist->streamingIsEnabled());
    if (min < 60)
      ret = 5 << 16 | (60 - min) << 24;
    else
      ret = 7 << 16;
    if (enablestreaming)
      ret |= (0x80 << 16);

    sensor *senso = sensors->getsensor(ab.sensorindex);
    senso->initialized = true;
    senso->halfdays = 29;
  } else {
    if (alg) {
      destruct _desalg(
          [alg = alg] { delete alg; }); //[alg] also works with g++ 13.1.1 , but
                                        // not with clang++ version 14.0.7
      LOGAR("alg!=null");
      if (scanda) {
        LOGSTRING("scanda\n");
        logscanresult(alg);
        int gluval = alg->currentglucose().getValue();
        if (gluval) {
          scantoshow = {ab.sensorindex, scanda, static_cast<uint32_t>(tim)};
          wakeaftermin(0);
          if (setbluetoothon || !ab.hist->streamingIsEnabled()) {
            return 8 << 16 | gluval;
            /*
        if(!waitstreaming()|| ((tim-ab.hist->beginscans()->gettime())>120)) {
            return 8<<16|gluval;
            }
        else   {
            if(!ab.hist->resetdevice) {
                ab.hist->resetdevice=true;
                return 9<<16|gluval;
                }
            } */
          }
          sensor *senso = sensors->getsensor(ab.sensorindex);
          if (senso->finished) {
            LOGAR("was finished");
            setstreaming(ab.hist); // NEEDED/
            setusedsensors();      // NEEDED
            senso->finished = 0;
            backup->resensordata(ab.sensorindex);
            return 8 << 16 | gluval;
          }
          return gluval;
        }
        const int min = datptr->getSensorAgeInMinutes();
        if (min < 60) {
          sensor *senso = sensors->getsensor(ab.sensorindex);
          if (senso->finished) {
            LOGSTRING("was finished\n");
            setbluetoothon = true;
            senso->finished = 0;
            backup->resensordata(ab.sensorindex);
          }
          senso->initialized = true;
          const bool enablestreaming =
              setbluetoothon || (ab.hist && !ab.hist->streamingIsEnabled());
          ret = 5 << 16 | (60 - min) << 24;
          if (enablestreaming)
            ret |= (0x80 << 16);
          return ret;
        } else {
          sensor *senso = sensors->getsensor(ab.sensorindex);
          jlong ret;
          if (alg->removed) {
            LOGGER("%s was %d,set senso->finished=1;\n",
                   senso->shortsensorname()->data(), senso->finished);
            if (alg->getlsaDetected()) {
              ret = SAS_SENSOR_TERMINATED << 16;
            } else {
              ab.removestate();
              if (!senso->finished) {
                senso->finished = 1;
                setstreaming(ab.hist);
                setusedsensors();
                backup->resensordata(ab.sensorindex);
              }
              return SAS_SENSOR_REMOVED << 16;
            }

          } else {
            ret = 6 << 16;
          }
          if (senso->finished) {
            LOGSTRING("was finished\n");
            setstreaming(ab.hist); // NEEDED/
            setusedsensors();      // NEEDED
            senso->finished = 0;
            backup->resensordata(ab.sensorindex);
          }
          return ret;
        }
      }
      ret = 4 << 16;
    } else {
      LOGAR("alg==null");
      if (datptr->getSensorAgeInMinutes() == 0)
        ret = 3 << 16;
    }
  }
  return ret;
}

// extern SensorGlucoseData  *usedhist;
// SensorGlucoseData  *usedhist;

extern Sensoren *sensors;

// #define fromjava(x) Java_tk_glucodata_Natives_ ##x
// extern data_t * unlockKeySensor(const SensorGlucoseData *usedhist) ;
extern data_t *unlockKeySensor(SensorGlucoseData *usedhist,
                               scanstate *stateptr);
// public static native long getdataptr(String sensorname);
// int  nusensornr=0,maxsens=4;; SensorGlucoseData ** nusensors=new
// SensorGlucoseData *[maxsens];
extern "C" JNIEXPORT jlong JNICALL fromjava(getsensorptr)(JNIEnv *env,
                                                          jclass cl,
                                                          jlong dataptr) {
  streamdata *sdata = reinterpret_cast<streamdata *>(dataptr);
  if (!sdata) {

    return 0LL;
  }
  return reinterpret_cast<jlong>(sdata->hist);
}
double calibrateONEtest(const SensorGlucoseData *sens, const ScanData &value);
extern "C" JNIEXPORT jlong JNICALL fromjava(streamfromSensorptr)(
    JNIEnv *env, jclass cl, jlong sensorptr, int pos) {
  const auto *sens = reinterpret_cast<const SensorGlucoseData *>(sensorptr);
  const ScanData *start = sens->beginpolls();
  const int len = sens->pollcount();
  for (int i = pos; i < len; i++) {
    const ScanData *item = start + i;
    if (item->valid()) {
      for (++i; i < len && !start[i].valid(); i++)
        ;
      long mgdL;
      if (double calibrated = calibrateONEtest(sens, *item);
          !isnan(calibrated)) {
        mgdL = (long)round(calibrated);
      } else
        mgdL = item->getmgdL();
      return ((jlong)item->gettime()) |
             (((jlong)mgdL) << 32 | ((jlong)i) << 48);
    }
  }
  return ((jlong)len) << 48;
}
extern "C" JNIEXPORT void JNICALL fromjava(setHidefromSensorptr)(
    JNIEnv *env, jclass cl, jlong sensorptr, jboolean hide) {
  reinterpret_cast<SensorGlucoseData *>(sensorptr)->hide = hide;
}
extern "C" JNIEXPORT jboolean JNICALL
fromjava(getHidefromSensorptr)(JNIEnv *env, jclass cl, jlong sensorptr) {
  return reinterpret_cast<const SensorGlucoseData *>(sensorptr)->hide;
}

static SensorGlucoseData *findSensorByName(JNIEnv *env, jstring jsensor) {
  if (!jsensor || !sensors) {
    return nullptr;
  }
  const char *sensorChars = env->GetStringUTFChars(jsensor, nullptr);
  if (!sensorChars) {
    return nullptr;
  }
  SensorGlucoseData *sens = sensors->getSensorData(sensorChars);
  if (!sens) {
    sens = sensors->gethistshort(sensorChars);
  }
  if (sens) {
    sens->sensorerror = false;
  } else {
    LOGGER("ERROR: %s unknown sensor\n", sensorChars);
  }
  env->ReleaseStringUTFChars(jsensor, sensorChars);
  return sens;
}

static jint sensorKindForUi(const SensorGlucoseData *sens) {
  if (!sens) {
    return -1;
  }
  if (sens->isSibionics()) {
    return 0x10;
  }
  if (sens->isAccuChek()) {
    return 0x20;
  }
  if (sens->isAiDex()) {
    return 0x30;
  }
  if (sens->isDexcom()) {
    return 0x40;
  }
  if (sens->isLibre3()) {
    return 3;
  }
  if (sens->isLibre2()) {
    return 2;
  }
  return 0;
}

extern "C" JNIEXPORT void JNICALL fromjava(healthConnectReset)(JNIEnv *env,
                                                               jclass cl) {
  sensors->onallsensors([](SensorGlucoseData *sens) {
    auto *info = sens->getinfo();
    info->healthconnectiter = info->pollstart;
  });
}
extern "C" JNIEXPORT jint JNICALL
fromjava(healthConnectfromSensorptr)(JNIEnv *env, jclass cl, jlong sensorptr) {
  LOGGER("healthConnectfromSensorptr(%p)\n", sensorptr);
  auto *info = reinterpret_cast<SensorGlucoseData *>(sensorptr)->getinfo();
  int start = info->healthconnectiter;
  if (!start) {
    info->healthconnectiter = start = info->pollstart;
  }
  auto res = start | ((int)info->pollcount << 16);
  LOGGER("healthConnectfromSensorptr=%x\n", res);
  return res;
}
extern "C" JNIEXPORT void JNICALL fromjava(healthConnectWritten)(
    JNIEnv *env, jclass cl, jlong sensorptr, jint pos) {
  reinterpret_cast<SensorGlucoseData *>(sensorptr)
      ->getinfo()
      ->healthconnectiter = pos;
}
extern "C" JNIEXPORT jlong JNICALL fromjava(getSensorStartmsec)(JNIEnv *env,
                                                                jclass cl,
                                                                jlong dataptr) {
  if (!dataptr)
    return 0LL;
  const streamdata *sdata = reinterpret_cast<const streamdata *>(dataptr);
  return sdata->hist->getstarttime() * 1000LL;
}
extern "C" JNIEXPORT jlong JNICALL
fromjava(getSensorStartmsecFromSensorptr)(JNIEnv *env, jclass cl,
                                          jlong sensorptr) {
  const auto *sens = reinterpret_cast<const SensorGlucoseData *>(sensorptr);
  if (!sens)
    return 0LL;
  return sens->getstarttime() * 1000LL;
}
extern "C" JNIEXPORT void JNICALL fromjava(updateUsedSensors)(JNIEnv *env,
                                                              jclass cl) {
  setusedsensors();
}

static void finishsensor(SensorGlucoseData *sensorptr, int sensorindex) {
  LOGGER("finishSensor %s\n", sensorptr->showsensorname().data());
  sensors->finishsensor(sensorindex);
  setstreaming(sensorptr);
  setusedsensors();
  backup->wakebackup(Backup::wakeall);
}
extern "C" JNIEXPORT void JNICALL fromjava(finishSensor)(JNIEnv *env, jclass cl,
                                                         jlong dataptr) {
  streamdata *sdata = reinterpret_cast<streamdata *>(dataptr);
  if (!sdata) {
    LOGAR("finishSensor dataptr=null");
    return;
  }
  SensorGlucoseData *sensorptr = sdata->hist;
  int sensorindex = sdata->sensorindex;
  finishsensor(sensorptr, sensorindex);
}

static std::vector<int> sensorIndicesFromId(JNIEnv *env, jstring jsensor) {
  if (!sensors || !jsensor)
    return {};
  const char *sensorChars = env->GetStringUTFChars(jsensor, nullptr);
  if (!sensorChars)
    return {};
  const auto sensorindices =
      sensors->sensorindices(std::string_view(sensorChars));
  env->ReleaseStringUTFChars(jsensor, sensorChars);
  return sensorindices;
}

extern "C" JNIEXPORT jboolean JNICALL
fromjava(removeSensorById)(JNIEnv *env, jclass cl, jstring jsensor) {
  const auto sensorindices = sensorIndicesFromId(env, jsensor);
  if (sensorindices.empty()) {
    LOGAR("removeSensorById unknown sensor");
    return JNI_FALSE;
  }
  bool removedAll = true;
  for (const int sensorindex : sensorindices) {
    SensorGlucoseData *sensorptr = sensors->getSensorData(sensorindex);
    LOGGER("removeSensorById %s index=%d of %zu\n",
           sensors->getsensor(sensorindex)->showsensorname(), sensorindex,
           sensorindices.size());
    if (!sensors->removesensor(sensorindex)) {
      removedAll = false;
      continue;
    }
    if (sensorptr)
      setstreaming(sensorptr);
    if (!sensors->getsensor(sensorindex)->wasRemovedByUser())
      removedAll = false;
  }
  setusedsensors();
  if (backup)
    backup->wakebackup(Backup::wakeall);
  return removedAll ? JNI_TRUE : JNI_FALSE;
}

static void unfinishsensor(SensorGlucoseData *sensorptr, int sensorindex) {
  LOGGER("unfinishSensor %s\n", sensorptr->showsensorname().data());
  sensors->unfinishsensor(sensorindex);
  setusedsensors();
}
extern "C" JNIEXPORT void JNICALL fromjava(unfinishSensor)(JNIEnv *env,
                                                           jclass cl,
                                                           jlong dataptr) {
  streamdata *sdata = reinterpret_cast<streamdata *>(dataptr);
  if (!sdata) {
    LOGAR("unfinishSensor dataptr=null");
    return;
  }
  SensorGlucoseData *sensorptr = sdata->hist;
  int sensorindex = sdata->sensorindex;
  unfinishsensor(sensorptr, sensorindex);
}

extern bool streamHistory();
#ifdef SIBIONICS
extern bool siInit(bool);
#endif
extern "C" JNIEXPORT jlong JNICALL fromjava(str2sensorptr)(JNIEnv *env,
                                                           jclass cl,
                                                           jstring jsensor) {
  if (!sensors) {
    LOGAR("ERROR: sensors==null");
    return 0LL;
  }
  const char *sensorChars = env->GetStringUTFChars(jsensor, nullptr);
  if (sensorChars == nullptr)
    return 0LL;

  int sensorindex = sensors->sensorindexshort(sensorChars);
  if (sensorindex < 0) {
    LOGGER("ERROR: %s unknown sensor\n", sensorChars);
    env->ReleaseStringUTFChars(jsensor, sensorChars);
    return 0LL;
  }
  SensorGlucoseData *sens = sensors->getSensorData(sensorindex);
  sens->sensorerror = false;
  env->ReleaseStringUTFChars(jsensor, sensorChars);
  return reinterpret_cast<jlong>(sens);
}

extern "C" JNIEXPORT jlong JNICALL fromjava(getSensorEndData)(JNIEnv *env,
                                                              jclass cl,
                                                              jstring jsensor) {
  if (!sensors) {
    LOGAR("ERROR: sensors==null");
    return 0LL;
  }
  const char *sensorChars = env->GetStringUTFChars(jsensor, nullptr);
  if (sensorChars == nullptr)
    return 0LL;

  const SensorGlucoseData *sens = sensors->gethistshort(sensorChars);
  if (sens == nullptr) {
    LOGGER("ERROR: %s unknown sensor addr\n", sensorChars);
    env->ReleaseStringUTFChars(jsensor, sensorChars);
    return 0LL;
  }
  env->ReleaseStringUTFChars(jsensor, sensorChars);
  jlong show = (!(sens->pollcount() && sens->isLibre3() > 0));
  return sens->expectedEndTime() | show << 32;
}
static SensorGlucoseData *ensureDirectStreamShellForId(const char *sensorId,
                                                       uint32_t startTimeSec,
                                                       size_t minimumRecords = 0);
static void seedDirectStreamStateIfMissing(SensorGlucoseData *hist,
                                           time_t timestamp);
static void syncListStarttime(SensorGlucoseData *hist, uint32_t start);
static bool isManagedDirectStreamSensorId(JNIEnv *env, const char *sensorId);
extern "C" JNIEXPORT jlong JNICALL fromjava(getdataptr)(JNIEnv *env, jclass cl,
                                                        jstring jsensor) {
  if (!jsensor) {
    LOGAR("ERROR: getdataptr jsensor==null");
    return 0LL;
  }
  if (!sensors) {
    LOGAR("ERROR: sensors==null");
    return 0LL;
  }

  const char *sensor_chars = env->GetStringUTFChars(jsensor, nullptr);
  if (!sensor_chars) {
    return 0LL;
  }
  std::string_view sensor(sensor_chars);
  bool managedDirectStreamId = false;

  int sensorindex = sensors->sensorindexshort(sensor_chars);
  SensorGlucoseData *sens = nullptr;
  if (sensorindex >= 0) {
    sens = sensors->getSensorData(sensorindex);
  }
  if (!sens) {
    managedDirectStreamId = isManagedDirectStreamSensorId(env, sensor_chars);
    if (managedDirectStreamId) {
      sens = ensureDirectStreamShellForId(sensor_chars, 0);
      if (sens) {
        sensorindex = sens->sensorIndex;
      }
    }
  }
  if (!sens && sensorindex < 0) {
    if (sensor.length() >= 2 && sensor.substr(0, 2) == "X-") {
      sensorindex = sensors->addsensor(sensor);
      if (sensorindex >= 0) {
        sens = sensors->getSensorData(sensorindex);
      }
    } else {
      LOGGER("ERROR: %.*s unknown sensor\n", (int)sensor.length(),
             sensor.data());
      env->ReleaseStringUTFChars(jsensor, sensor_chars);
      return 0LL;
    }
  }

  if (!sens && sensorindex >= 0) {
    sens = sensors->getSensorData(sensorindex);
  }
  if (!sens) {
    LOGGER("ERROR: getSensorData(%d) returns null\n", sensorindex);
    env->ReleaseStringUTFChars(jsensor, sensor_chars);
    return 0LL;
  }
  sens->sensorerror = false;
  auto makeStreamData = [&]() -> streamdata * {
    streamdata *candidate = nullptr;
#ifdef SIBIONICS
    if (sens->isSibionics()) {
      LOGGER("getdataptr(%.*s) isSibionics notchinese=%d\n",
             (int)sensor.length(), sensor.data(), sens->notchinese());
      if (!siInit(sens->notchinese())) {
        LOGAR("siInit()==false");
        return nullptr;
      }
      candidate = new sistream(sensorindex, sens);
    } else
#endif
    {
#ifdef DEXCOM
      if (sens->isDexcom()) {
        LOGGER("getdataptr(%.*s) Dexcom\n", (int)sensor.length(),
               sensor.data());
        candidate = new dexcomstream(sensorindex, sens);
      } else
#endif
      {
        if (sens->isLibre3()) {
          LOGGER("getdataptr(%.*s) Libre3\n", (int)sensor.length(),
                 sensor.data());
          candidate = new libre3stream(sensorindex, sens);
        } else {
          if (sens->isAccuChek()) {
            LOGGER("getdataptr(%.*s) AccuChek\n", (int)sensor.length(),
                   sensor.data());
            candidate = new accustream(sensorindex, sens);
          } else if (sens->isAiDex()) {
            LOGGER("getdataptr(%.*s) AiDex\n", (int)sensor.length(),
                   sensor.data());
            candidate = new aidexstream(sensorindex, sens);
          } else {
            LOGGER("getdataptr(%.*s) Libre2\n", (int)sensor.length(),
                   sensor.data());
            candidate = new libre2stream(sensorindex, sens);
            if (streamHistory()) {
              if (!sens->getinfo()->startedwithStreamhistory) {
                sens->getinfo()->startedwithStreamhistory =
                    std::max(sens->getinfo()->endhistory, 1);
              }
            }
          }
        }
      }
    }
    return candidate;
  };

  streamdata *data = makeStreamData();

  if (data && data->good()) {
    env->ReleaseStringUTFChars(jsensor, sensor_chars);
    LOGGER("getdataptr()=%p sens=%p\n", data, sens);
    return reinterpret_cast<jlong>(data);
  }

  if (data) {
    LOGSTRING("getdataptr(): !data->good()\n");
    delete data;
  } else {
    LOGSTRING("getdataptr(): data==null\n");
  }

  if (!managedDirectStreamId) {
    managedDirectStreamId = isManagedDirectStreamSensorId(env, sensor_chars);
  }
  if (managedDirectStreamId) {
    seedDirectStreamStateIfMissing(sens, time(nullptr));
    data = makeStreamData();
    if (data && data->good()) {
      env->ReleaseStringUTFChars(jsensor, sensor_chars);
      LOGGER("getdataptr() retry=%p sens=%p\n", data, sens);
      return reinterpret_cast<jlong>(data);
    }
    if (data) {
      LOGSTRING("getdataptr(): retry !data->good()\n");
      delete data;
    } else {
      LOGSTRING("getdataptr(): retry data==null\n");
    }
  }

  env->ReleaseStringUTFChars(jsensor, sensor_chars);
  return 0LL;
}
extern "C" JNIEXPORT void JNICALL fromjava(freedataptr)(JNIEnv *envin,
                                                        jclass cl,
                                                        jlong dataptr) {
  LOGGER("freedataptr(%p)\n", dataptr);
  streamdata *sdata = reinterpret_cast<streamdata *>(dataptr);
  delete sdata;
}
extern "C" JNIEXPORT jboolean JNICALL
fromjava(askstreamingEnabled)(JNIEnv *env, jclass cl, jlong dataptr) {
  if (!dataptr)
    return false;
  return reinterpret_cast<streamdata *>(dataptr)->hist->streamingIsEnabled() ==
         1;
}

#ifdef SKIPTRIEDOFTEN
#ifdef NOLOG
static constexpr const int DEXTRYADDRESSSECS = 60 * 15;
#else
static constexpr const int DEXTRYADDRESSSECS = 60 * 5;
#endif
#endif
extern "C" JNIEXPORT void JNICALL fromjava(setDeviceAddress)(
    JNIEnv *env, jclass cl, jlong dataptr, jstring jdeviceAddress) {
  if (!dataptr)
    return;
  SensorGlucoseData *usedhist = reinterpret_cast<streamdata *>(dataptr)->hist;
  if (!usedhist)
    return;
  char *deviceaddress = usedhist->deviceaddress();
  if (!jdeviceAddress) {
    deviceaddress[0] = '\0';
  } else {
    const jint getlen = std::min(env->GetStringUTFLength(jdeviceAddress), 17);
    env->GetStringUTFRegion(jdeviceAddress, 0, getlen, deviceaddress);
    deviceaddress[getlen] = '\0';
    usedhist->scannedAddress = true;

#ifdef SKIPTRIEDOFTEN
    if (usedhist->isDexcom()) {
      auto &usedAddresses = usedhist->usedAddresses;
      if (usedAddresses.empty() ||
          memcmp(usedAddresses.back().data(), deviceaddress, getlen + 1)) {
        uint32_t now = time(nullptr);
        const auto &newel = *reinterpret_cast<const address_t *>(deviceaddress);
        if (!usedAddresses.empty() && now < usedhist->usedAddressesTime) {
          usedAddresses.back() = newel;
        } else
          usedAddresses.emplace_back(newel);
        usedhist->usedAddressesTime = now + DEXTRYADDRESSSECS;
      }
    }
#endif
  }
  LOGGER("setDeviceAddress(%s)\n", deviceaddress);
}
extern "C" JNIEXPORT int JNICALL
fromjava(getSensorptrLibreVersion)(JNIEnv *envin, jclass cl, jlong sensorptr) {
  const SensorGlucoseData *sens =
      reinterpret_cast<const SensorGlucoseData *>(sensorptr);
  if (!sens) {
    return -1;
  }
  if (sens->isSibionics()) {
    return 0x10;
  }
  if (sens->isDexcom()) {
    return 0x40;
  }
  if (sens->isLibre3()) {
    return 3;
  }
  if (sens->isLibre3()) {
    return 2;
  }
  return 0;
}
extern "C" JNIEXPORT int JNICALL fromjava(getLibreVersion)(JNIEnv *envin,
                                                           jclass cl,
                                                           jlong dataptr) {
  if (!dataptr)
    return 0;
  streamdata *sdata = reinterpret_cast<streamdata *>(dataptr);
  return sdata->libreversion;
}
extern "C" JNIEXPORT jstring JNICALL fromjava(getSensorName)(JNIEnv *envin,
                                                             jclass cl,
                                                             jlong dataptr) {
  if (!dataptr)
    return nullptr;
  const SensorGlucoseData *usedhist =
      reinterpret_cast<streamdata *>(dataptr)->hist;
  if (!usedhist)
    return nullptr;
  const char *name = usedhist->shortsensorname()->data();
  LOGGER("getSensorName()=%s\n", name);
  return envin->NewStringUTF(name);
}
extern "C" JNIEXPORT jstring JNICALL fromjava(sensorptr2str)(JNIEnv *envin,
                                                             jclass cl,
                                                             jlong sensorptr) {
  if (!sensorptr)
    return nullptr;
  const SensorGlucoseData *usedhist =
      reinterpret_cast<const SensorGlucoseData *>(sensorptr);
  const char *name = usedhist->shortsensorname()->data();
  LOGGER("getSensorName()=%s\n", name);
  return envin->NewStringUTF(name);
}

extern "C" JNIEXPORT jstring JNICALL fromjava(resolveFullSensorName)(
    JNIEnv *envin, jclass cl, jstring sensorId) {
  if (!sensors || !sensorId)
    return nullptr;
  const char *str = envin->GetStringUTFChars(sensorId, nullptr);
  if (!str)
    return nullptr;

  int ind = sensors->sensorindex(str);
  if (ind < 0) {
    ind = sensors->sensorindexshort(str);
  }
  if (ind < 0) {
    envin->ReleaseStringUTFChars(sensorId, str);
    return nullptr;
  }

  const sensor *sens = sensors->getsensor(ind);
  const char *name = sens ? sens->fullname().data() : nullptr;
  jstring result = (name && name[0]) ? envin->NewStringUTF(name) : nullptr;
  envin->ReleaseStringUTFChars(sensorId, str);
  return result;
}

extern "C" JNIEXPORT jint JNICALL fromjava(getSensorIndex)(
    JNIEnv *envin, jclass cl, jstring sensorId) {
  if (!sensors || !sensorId)
    return -1;
  const char *str = envin->GetStringUTFChars(sensorId, nullptr);
  if (!str)
    return -1;

  int ind = sensors->sensorindex(str);
  if (ind < 0) {
    ind = sensors->sensorindexshort(str);
  }
  envin->ReleaseStringUTFChars(sensorId, str);
  return ind;
}

extern "C" JNIEXPORT jint JNICALL fromjava(getSensorIndexFromDataPtr)(
    JNIEnv *envin, jclass cl, jlong dataptr) {
  const streamdata *sdata = reinterpret_cast<const streamdata *>(dataptr);
  return sdata ? sdata->sensorindex : -1;
}

/*
extern "C" JNIEXPORT jstring JNICALL   fromjava(getUsedSensorName)(JNIEnv
*envin, jclass cl,jlong dataptr) { if(!dataptr) return nullptr; const
SensorGlucoseData *usedhist=reinterpret_cast<streamdata *>(dataptr)->hist ;
 if(!usedhist)
     return nullptr;
 const char *name=usedhist->shortsensorname()->data();
 LOGGER("getSensorName()=%s\n",name);
 return envin->NewStringUTF(name);
 }
extern "C" JNIEXPORT jstring JNICALL   fromjava(getShowSensorName)(JNIEnv
*envin, jclass cl,jlong dataptr) { if(!dataptr) return nullptr; const
SensorGlucoseData *usedhist=reinterpret_cast<streamdata *>(dataptr)->hist ;
 if(!usedhist)
     return nullptr;
 const char *name=usedhist->showsensorname()->data();
 LOGGER("getShowSensorName()=%s\n",name);
 return envin->NewStringUTF(name);
 } */

extern "C" JNIEXPORT jstring JNICALL fromjava(getDeviceAddress)(
    JNIEnv *envin, jclass cl, jlong dataptr, jboolean getnew) {
  LOGGER("getDeviceAddress(%p,%d)\n", dataptr, getnew);
  if (!dataptr) {
    LOGAR("getDeviceAddress(null)");
    return nullptr;
  }
  const SensorGlucoseData *usedhist =
      reinterpret_cast<streamdata *>(dataptr)->hist;
  if (!usedhist) {
    LOGAR("getDeviceAddress() usedhist==null");
    return nullptr;
  }
  const char *address = usedhist->deviceaddress();
  if (!*address) {
    LOGAR("deviceaddress()==null");
    return nullptr;
  }
  if (usedhist->isAccuChek() ||
      (getnew && !usedhist->scannedAddress && !usedhist->isLibre() &&
       !usedhist->isAiDex())) {
    LOGAR("getDeviceAddress() !libre getnew");
    return nullptr;
  }
  LOGGER("getDeviceAddress()=%s\n", address);
  return envin->NewStringUTF(address);
}
#include "strconcat.hpp"
extern strconcat getsensortext(const SensorGlucoseData *hist);
extern "C" JNIEXPORT jstring JNICALL
fromjava(getSensorStatusByName)(JNIEnv *envin, jclass cl, jstring jsensor) {
  const SensorGlucoseData *usedhist = findSensorByName(envin, jsensor);
  if (!usedhist)
    return envin->NewStringUTF("");
  return envin->NewStringUTF(getsensortext(usedhist).data());
}
extern "C" JNIEXPORT jlongArray JNICALL
fromjava(getSensorUiSnapshot)(JNIEnv *env, jclass cl, jstring jsensor) {
  const SensorGlucoseData *sens = findSensorByName(env, jsensor);
  if (!sens) {
    return nullptr;
  }
  const auto *info = sens->getinfo();
  const jlong values[5]{
      static_cast<jlong>(sensorKindForUi(sens)),
      static_cast<jlong>(info ? info->viewMode : 0),
      static_cast<jlong>(sens->getstarttime()) * 1000LL,
      static_cast<jlong>(sens->expectedEndTime()) * 1000LL,
      static_cast<jlong>(sens->officialendtime()) * 1000LL};
  jlongArray result = env->NewLongArray(5);
  if (!result) {
    return nullptr;
  }
  env->SetLongArrayRegion(result, 0, 5, values);
  return result;
}
extern "C" JNIEXPORT jstring JNICALL fromjava(getsensortext)(JNIEnv *envin,
                                                             jclass cl,
                                                             jlong dataptr) {
  if (!dataptr)
    return envin->NewStringUTF("");
  const streamdata *str = reinterpret_cast<const streamdata *>(dataptr);
  const SensorGlucoseData *usedhist = str->hist;
  if (!usedhist)
    return envin->NewStringUTF("");
  return envin->NewStringUTF(getsensortext(usedhist).data());
}

extern "C" JNIEXPORT void JNICALL fromjava(resetbluetooth)(JNIEnv *envin,
                                                           jclass cl,
                                                           jlong dataptr) {
  if (!dataptr)
    return;
  SensorGlucoseData *usedhist = reinterpret_cast<streamdata *>(dataptr)->hist;
  if (!usedhist)
    return;
  //    if(!usedhist->bluetoothfirst()||!waitstreaming())
  usedhist->setbluetoothOn(0);
  if (!backup)
    return;
  int maxint = backup->getupdatedata()->sendnr;
  LOGGER("resetbluetooth %d\n", maxint);
  usedhist->sendbluetoothOn(maxint);
  backup->wakebackup(Backup::wakestream);
}

extern "C" JNIEXPORT jbyteArray JNICALL
fromjava(sensorUnlockKey)(JNIEnv *envin, jclass cl, jlong dataptr) {
  streamdata *const streamptr = reinterpret_cast<streamdata *>(dataptr);
  LOGGER("sensorUnlockKey %p\n", streamptr);
  setthreadname("sensorUnlockKey");
  if (!dataptr) {
    return nullptr;
  }
  if (streamptr->libreversion != 2) {
    LOGGER("libreversion=%d\n", streamptr->libreversion);
    return nullptr;
  }
  scanstate state;
  if (data_t *key = unlockKeySensor(streamptr->hist, &state)) {
    const int uitlen = key->size();
    jbyteArray uit = envin->NewByteArray(uitlen);
    envin->SetByteArrayRegion(uit, 0, uitlen, key->data());
    //        data_t::deleteex(key); //IN map don't delete
    return uit;
  }
  LOGAR("unlockKeySensor==null");
  return nullptr;
}
extern "C" JNIEXPORT jbyteArray JNICALL
fromjava(getsensorident)(JNIEnv *envin, jclass cl, jlong dataptr) {
  streamdata *streamptr = reinterpret_cast<streamdata *>(dataptr);
  if (!dataptr)
    return nullptr;
  if (streamptr->libreversion != 2)
    return nullptr;
  SensorGlucoseData *usedhist = streamptr->hist;
  if (!usedhist)
    return nullptr;
  jbyteArray uit = envin->NewByteArray(8);
  envin->SetByteArrayRegion(uit, 0, 8, usedhist->getsensorident()->data());
  return uit;
}
extern "C" JNIEXPORT jint JNICALL fromjava(getsensorgen)(JNIEnv *envin,
                                                         jclass cl,
                                                         jlong dataptr) {
  streamdata *const streamptr = reinterpret_cast<streamdata *>(dataptr);
  if (!dataptr)
    return -1;
  const SensorGlucoseData *usedhist = streamptr->hist;
  if (!usedhist)
    return -1;
  return usedhist->getsensorgen();
}

extern "C" JNIEXPORT jbyteArray JNICALL fromjava(
    getstreamingAuthenticationData)(JNIEnv *envin, jclass cl, jlong dataptr) {
  streamdata *const streamptr = reinterpret_cast<streamdata *>(dataptr);
  LOGGER("getstreamingAuthenticationData %p\n", streamptr);
  setthreadname("getstreamingAuthenticationData");
  if (!dataptr)
    return nullptr;
  if (streamptr->libreversion != 2)
    return nullptr;
  const SensorGlucoseData *usedhist = streamptr->hist;
  if (!usedhist)
    return nullptr;
  const auto auth = usedhist->getinfo()->getauth();
  const auto authlen = auth.size();
  jbyteArray uit = envin->NewByteArray(authlen);
  envin->SetByteArrayRegion(uit, 0, authlen, (const jbyte *)auth.data());
  return uit;
}
/*
extern time_t *glucosetime;
extern float *glucoserate;
//extern  float *glucose;
extern  uint32_t *glucose;
//typedef const char **sensorname;
typedef const sensorname_t *constcharptr_t;
extern constcharptr_t *sensorname;
*/

// extern streams_t laststream;
// extern data_t *fromjbyteArray(JNIEnv *env,jbyteArray jar,jint len=-1) ;
constexpr const jlong isHighest = 4LL << 48;
constexpr const jlong isHigh = 6LL << 48;
constexpr const jlong isLow = 7LL << 48;
constexpr const jlong isAgain = 3LL << 48;
constexpr const jlong isLowest = 5LL << 48;

constexpr const jlong isVeryHigh = 16LL << 48;
constexpr const jlong isVeryLow = 17LL << 48;

constexpr const jlong isPreHigh = 18LL << 48;
constexpr const jlong isPreLow = 19LL << 48;

#include "gluconfig.hpp"
extern void setbuffer(char *);

static void savestate(libre2stream *sdata) {
  if (sdata->state->map.size() < 4 * 4096)
    return;
  string_view sensordir = sdata->hist->getsensordir();
  const auto *lastpo = sdata->hist->lastpoll();
  auto name = scanstate::makefilename(sensordir, lastpo->gettime());
  auto *state = sdata->state->map.data();
  writeall(name.data(), state, sdata->state->map.size() * sizeof(state[0]));

  const char *old;
  {
    std::lock_guard<std::mutex> lock(sdata->hist->mutex);

    old = getpreviousstate(sensordir).data();
    scanstate::makelink(name);
  }
  delete[] name.data();
  unlink(old);
  delete[] old;
}

static jlong getalarmonly(const uint32_t mgL, float drate,
                          const SensorGlucoseData *hist) {
  auto res =
      mgL < glucoselowestmgL
          ? isLowest
          : (mgL > (hist->getmaxmgdL() * 10)
                 ? isHighest
                 : (settings->veryhighAlarm(mgL)
                        ? isVeryHigh
                        : (settings->highAlarm(mgL)
                               ? isHigh
                               : (settings->verylowAlarm(mgL)
                                      ? isVeryLow
                                      : (settings->lowAlarm(mgL)
                                             ? isLow
                                             : (settings->prelowAlarm(mgL,
                                                                      drate)
                                                    ? isPreLow
                                                    : (settings->prehighAlarm(
                                                           mgL, drate)
                                                           ? isPreHigh
                                                           : (settings->availableAlarm() &&
                                                                      hist->waiting
                                                                  ? isAgain
                                                                  : 0))))))));
  return res;
}
int getalarmcode(const uint32_t mgL, float drate, SensorGlucoseData *hist) {
  int res = getalarmonly(mgL, drate, hist) >> 48;
  hist->waiting = false;
  return res;
}
float threshold(float drate) { return glnearnull(drate) ? 0.0f : drate; }
extern "C" JNIEXPORT jfloat JNICALL fromjava(thresholdchange)(JNIEnv *envin,
                                                              jclass cl,
                                                              jfloat drate) {
  return threshold(drate);
}

extern double calibrateNow(const SensorGlucoseData *sens, const uint32_t time,
                           const double value);
static jlong glucoselong(uint32_t nu, uint32_t glval, float drate,
                         const SensorGlucoseData *hist) {
  if (!glval)
    return 0LL;
  const jlong rate = roundl(((long double)drate) * 1000LL);

  const double cali = calibrateNow(hist, nu, glval);
  uint32_t mgL;
  if (!isnan(cali)) {
    mgL = (uint32_t)round(cali * 10.0);
  } else
    mgL = glval * 10;

  const jlong alarmcode = getalarmonly(mgL, drate, hist);
  const jlong res = (rate & 0xFFFF) << 32 | alarmcode | mgL;
  LOGGER("glucoselong=%" PRId64 "\n", res);
  return res;
}
// extern const SensorGlucoseData * getlaststream(const uint32_t);
extern std::pair<const SensorGlucoseData *, int>
getlaststream(const uint32_t nu);
extern "C" JNIEXPORT jlongArray JNICALL fromjava(getlastGlucose)(JNIEnv *env,
                                                                 jclass cl) {
  auto nu = time(nullptr);
  if (const auto [hist, _] = getlaststream(nu); hist) {
    const ScanData *poll = hist->lastValidStream();
    if (poll) {
      jlong uit[2];
      uit[0] = poll->gettime();
      uit[1] = glucoselong(poll->gettime(), poll->getmgdL(), poll->getchange(),
                           hist);
      jlongArray juit = env->NewLongArray(2);
      env->SetLongArrayRegion(juit, 0, 2, uit);
      return juit;
    }
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL fromjava(addGlucoseInjection)(
    JNIEnv *env, jclass cl, jlong timestamp, jfloat glucose, jstring sensorId) {
  if (!sensors || !sensorId)
    return;
  const char *str = env->GetStringUTFChars(sensorId, NULL);
  if (!str)
    return;

  if (timestamp > 0) {
    int ind = -1;
    auto *list = sensors->sensorlist();
    if (list) {
      ind = sensors->sensorindexshort(str);
      if (ind < 0) {
        ind = sensors->addsensor(std::string_view(str));
      }
    }

    if (ind >= 0) {
      if (SensorGlucoseData *hist = sensors->getSensorData(ind)) {
        if (hist->error()) {
          env->ReleaseStringUTFChars(sensorId, str);
          return;
        }

        auto *info = hist->getinfo();
        if (!info) {
          env->ReleaseStringUTFChars(sensorId, str);
          return;
        }

        uint32_t start = hist->getstarttime();
        if (!start && timestamp > 3600) {
          start = timestamp - 3600;
          info->starttime = start;
          syncListStarttime(hist, start);
        }

        int lifeCount = 0;
        if (start > 0 && timestamp >= start) {
          lifeCount = (timestamp - start) / 60;
        }

        uint16_t mgVal = (uint16_t)(glucose * 10.0f);
        int pos = hist->getScanendhistory();

        if (pos >= 0 && pos < hist->maxpos()) {
          hist->savenewhistory(pos, lifeCount, mgVal);
        }

        setstreaming(hist);
      }
    }
  }
  env->ReleaseStringUTFChars(sensorId, str);
}

static SensorGlucoseData *ensureDirectStreamShellForId(const char *sensorId,
                                                       uint32_t startTimeSec,
                                                       size_t minimumRecords) {
  if (!sensors || !sensorId || !sensorId[0]) {
    return nullptr;
  }
  return sensors->ensureDirectStreamShell(std::string_view(sensorId),
                                          startTimeSec, minimumRecords);
}

static bool isManagedDirectStreamSensorId(JNIEnv *env, const char *sensorId) {
  if (!env || !sensorId || !sensorId[0]) {
    return false;
  }
  static jclass sensorIdentityClass = nullptr;
  static jmethodID usesNativeDirectStreamShellMethod = nullptr;
  if (!sensorIdentityClass) {
    jclass localClass = env->FindClass("tk/glucodata/SensorIdentity");
    if (!localClass) {
      LOGSTRING("FindClass(tk/glucodata/SensorIdentity) failed\n");
      env->ExceptionClear();
      return false;
    }
    sensorIdentityClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    if (!sensorIdentityClass) {
      return false;
    }
  }
  if (!usesNativeDirectStreamShellMethod) {
    usesNativeDirectStreamShellMethod = env->GetStaticMethodID(
        sensorIdentityClass, "usesNativeDirectStreamShell",
        "(Ljava/lang/String;)Z");
    if (!usesNativeDirectStreamShellMethod) {
      LOGSTRING("GetStaticMethodID(SensorIdentity.usesNativeDirectStreamShell) failed\n");
      env->ExceptionClear();
      return false;
    }
  }
  jstring jsensor = env->NewStringUTF(sensorId);
  if (!jsensor) {
    return false;
  }
  const jboolean managed = env->CallStaticBooleanMethod(
      sensorIdentityClass, usesNativeDirectStreamShellMethod, jsensor);
  env->DeleteLocalRef(jsensor);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return false;
  }
  return managed == JNI_TRUE;
}

static void seedDirectStreamStateIfMissing(SensorGlucoseData *hist,
                                           time_t timestamp) {
  if (!hist) {
    return;
  }
  const string_view sensordir = hist->getsensordir();
  auto prevstate = getpreviousstate(sensordir);
  const bool hasstate = prevstate.data() != nullptr;
  delete[] prevstate.data();
  if (hasstate) {
    return;
  }
  const time_t stateTime = timestamp > 0 ? timestamp : time(nullptr);
  scanstate seeded(defaultscanstate);
  if (data_t *mess = seeded.alloc(1)) {
    mess->buf[0] = 0;
    seeded.setpos(MESS, mess);
    auto name = scanstate::makefilename(sensordir, stateTime);
    auto *state = seeded.map.data();
    writeall(name.data(), state, seeded.map.size() * sizeof(state[0]));
    scanstate::makelink(name);
    delete[] name.data();
  }
}

static void syncListStarttime(SensorGlucoseData *hist, uint32_t start) {
  if (!hist || !sensors || !Sensoren::validSensorStarttime(start))
    return;
  sensors->setSensorStarttime(hist->sensorIndex, start);
}

static int compactRawMgdl(jfloat rawGlucose) {
  if (rawGlucose <= 0.0f)
    return 0;
  constexpr float mgdlToMmol = 1.0f / 18.0182f;
  return (int)roundf(rawGlucose * mgdlToMmol * 10.0f);
}

static bool storeGlucoseStreamSample(SensorGlucoseData *hist, const char *sensorId,
                                     jlong timestamp, jfloat glucose,
                                     jfloat rawGlucose, jfloat temperatureC,
                                     bool overwriteRaw, bool overwriteTemp,
                                     bool quiet, int *rewindFrom) {
  if (!hist || hist->error() || timestamp <= 0)
    return false;
  auto *info = hist->getinfo();
  if (!info)
    return false;

  uint32_t start = info->starttime;
  if (!start && timestamp > 3600) {
    start = timestamp - 3600;
    info->starttime = start;
    syncListStarttime(hist, start);
  }

  int lifeCount = 0;
  if (start > 0 && timestamp >= start)
    lifeCount = (timestamp - start) / 60;
  const uint16_t mgVal = (uint16_t)(glucose * 10.0f);

  if (!hist->validPollIndex(lifeCount)) {
    // Capacity failures remain loud even for a bulk reconciliation: unlike a
    // successful duplicate write, this is lost medical data.
    LOGGER("%s: live reading at %lld DROPPED, lifeCount=%lld outside poll "
           "capacity=%zu (starttime=%u)\n",
           sensorId, (long long)timestamp, (long long)lifeCount,
           hist->pollStorageCapacity(), start);
    return false;
  }

  int preservedRaw = 0;
  uint16_t preservedTemp = 0;
  const ScanData *pollsbuf = hist->getPollsData();
  const bool fillsPollGap = pollsbuf && pollsbuf[lifeCount].g <= 0;
  if (hist->hasStreamID(lifeCount)) {
    const RawData *rawbuf = hist->getRawPollsData();
    if (rawbuf)
      preservedRaw = rawbuf[lifeCount].raw;
    preservedTemp = hist->getTempForPoll(lifeCount);
  }
  if (overwriteRaw && rawGlucose > 0.0f)
    preservedRaw = compactRawMgdl(rawGlucose);
  if (overwriteTemp && temperatureC > 0.0f)
    preservedTemp = static_cast<uint16_t>(temperatureC * 10.0f);

  const float change = derivechangeforsample(hist->getPollsData(), 0, lifeCount,
                                             mgVal, timestamp);
  const bool stored = quiet
                          ? hist->savepollallIDsQuiet<60>(
                                timestamp, lifeCount, mgVal, 0, change,
                                preservedRaw, preservedTemp)
                          : hist->savepollallIDs<60>(
                                timestamp, lifeCount, mgVal, 0, change,
                                preservedRaw, preservedTemp);
  if (!stored)
    return false;

  if (fillsPollGap && lifeCount <= UINT16_MAX && info->nightiter > lifeCount) {
    if (!quiet) {
      LOGGER("%s: poll %d backfilled behind Nightscout cursor %u; rewinding\n",
             sensorId, lifeCount, (unsigned)info->nightiter);
    }
    __atomic_store_n(&info->nightiter, (uint16_t)lifeCount, __ATOMIC_RELAXED);
  }
  if (backup) {
    if (rewindFrom) {
      *rewindFrom = *rewindFrom < 0 ? lifeCount : std::min(*rewindFrom, lifeCount);
    } else {
      hist->backstream(lifeCount);
      hist->backhistory(lifeCount);
    }
  }
  return true;
}

static bool addGlucoseStreamInternal(JNIEnv *env, jlong timestamp, jfloat glucose,
                                     jfloat rawGlucose, jfloat temperatureC,
                                     jstring sensorId, bool overwriteRaw,
                                     bool overwriteTemp) {
  if (!sensors || !sensorId)
    return false;
  const char *str = env->GetStringUTFChars(sensorId, NULL);
  if (!str)
    return false;

  bool stored = false;
  if (timestamp > 0) {
    if (SensorGlucoseData *hist = ensureDirectStreamShellForId(str, 0)) {
      seedDirectStreamStateIfMissing(hist, timestamp);
      stored = storeGlucoseStreamSample(
          hist, str, timestamp, glucose, rawGlucose, temperatureC,
          overwriteRaw, overwriteTemp, false, nullptr);
      if (stored)
        setstreaming(hist);
    }
  }
  env->ReleaseStringUTFChars(sensorId, str);
  return stored;
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(addGlucoseStream)(
    JNIEnv *env, jclass cl, jlong timestamp, jfloat glucose, jstring sensorId) {
  return addGlucoseStreamInternal(env, timestamp, glucose, 0.0f, 0.0f, sensorId,
                                  false, false)
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(addGlucoseStreamWithTemp)(
    JNIEnv *env, jclass cl, jlong timestamp, jfloat glucose, jfloat temperatureC,
    jstring sensorId) {
  return addGlucoseStreamInternal(env, timestamp, glucose, 0.0f, temperatureC,
                                  sensorId, false, true)
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL fromjava(addGlucoseStreamBatchWithTemp)(
    JNIEnv *env, jclass cl, jlongArray timestamps, jfloatArray glucoses,
    jfloatArray temperatures, jstring sensorId) {
  if (!sensors || !timestamps || !glucoses || !temperatures || !sensorId)
    return 0;
  const jsize count = env->GetArrayLength(timestamps);
  if (count <= 0 || env->GetArrayLength(glucoses) != count ||
      env->GetArrayLength(temperatures) != count)
    return 0;

  const char *str = env->GetStringUTFChars(sensorId, nullptr);
  jlong *timeValues = env->GetLongArrayElements(timestamps, nullptr);
  jfloat *glucoseValues = env->GetFloatArrayElements(glucoses, nullptr);
  jfloat *temperatureValues = env->GetFloatArrayElements(temperatures, nullptr);
  if (!str || !timeValues || !glucoseValues || !temperatureValues) {
    if (temperatureValues)
      env->ReleaseFloatArrayElements(temperatures, temperatureValues, JNI_ABORT);
    if (glucoseValues)
      env->ReleaseFloatArrayElements(glucoses, glucoseValues, JNI_ABORT);
    if (timeValues)
      env->ReleaseLongArrayElements(timestamps, timeValues, JNI_ABORT);
    if (str)
      env->ReleaseStringUTFChars(sensorId, str);
    return 0;
  }

  jint stored = 0;
  jlong firstTimestamp = 0;
  for (jsize index = 0; index < count && firstTimestamp <= 0; ++index)
    firstTimestamp = timeValues[index];
  if (SensorGlucoseData *hist = ensureDirectStreamShellForId(str, 0)) {
    seedDirectStreamStateIfMissing(hist, firstTimestamp);
    int rewindFrom = -1;
    for (jsize index = 0; index < count; ++index) {
      if (storeGlucoseStreamSample(
              hist, str, timeValues[index], glucoseValues[index], 0.0f,
              temperatureValues[index], false, true, true, &rewindFrom)) {
        ++stored;
      }
    }
    if (rewindFrom >= 0 && backup) {
      hist->backstream(rewindFrom);
      hist->backhistory(rewindFrom);
    }
    if (stored > 0)
      setstreaming(hist);
  }

  env->ReleaseFloatArrayElements(temperatures, temperatureValues, JNI_ABORT);
  env->ReleaseFloatArrayElements(glucoses, glucoseValues, JNI_ABORT);
  env->ReleaseLongArrayElements(timestamps, timeValues, JNI_ABORT);
  env->ReleaseStringUTFChars(sensorId, str);
  return stored;
}

// Raw-carrying sibling of addGlucoseStreamBatchWithTemp, for drivers whose
// history mirror is minute-index-addressed and idempotent (Sibionics, Anytime).
// The point of the batch form is what happens *around* the loop, not the loop:
// one shell resolve, one seedDirectStreamStateIfMissing (stat + read + alloc),
// quiet writes that skip the per-sample LOGGER, a single backstream/backhistory
// rewind from the lowest index touched, and one setstreaming. Mirroring a
// 4867-sample history one call at a time cost all of that per reading.
extern "C" JNIEXPORT jint JNICALL fromjava(addGlucoseStreamBatchWithRawTemp)(
    JNIEnv *env, jclass cl, jlongArray timestamps, jfloatArray glucoses,
    jfloatArray raws, jfloatArray temperatures, jstring sensorId) {
  if (!sensors || !timestamps || !glucoses || !raws || !temperatures ||
      !sensorId)
    return 0;
  const jsize count = env->GetArrayLength(timestamps);
  if (count <= 0 || env->GetArrayLength(glucoses) != count ||
      env->GetArrayLength(raws) != count ||
      env->GetArrayLength(temperatures) != count)
    return 0;

  const char *str = env->GetStringUTFChars(sensorId, nullptr);
  jlong *timeValues = env->GetLongArrayElements(timestamps, nullptr);
  jfloat *glucoseValues = env->GetFloatArrayElements(glucoses, nullptr);
  jfloat *rawValues = env->GetFloatArrayElements(raws, nullptr);
  jfloat *temperatureValues = env->GetFloatArrayElements(temperatures, nullptr);
  if (!str || !timeValues || !glucoseValues || !rawValues ||
      !temperatureValues) {
    if (temperatureValues)
      env->ReleaseFloatArrayElements(temperatures, temperatureValues, JNI_ABORT);
    if (rawValues)
      env->ReleaseFloatArrayElements(raws, rawValues, JNI_ABORT);
    if (glucoseValues)
      env->ReleaseFloatArrayElements(glucoses, glucoseValues, JNI_ABORT);
    if (timeValues)
      env->ReleaseLongArrayElements(timestamps, timeValues, JNI_ABORT);
    if (str)
      env->ReleaseStringUTFChars(sensorId, str);
    return 0;
  }

  jint stored = 0;
  jlong firstTimestamp = 0;
  for (jsize index = 0; index < count && firstTimestamp <= 0; ++index)
    firstTimestamp = timeValues[index];
  if (SensorGlucoseData *hist = ensureDirectStreamShellForId(str, 0)) {
    seedDirectStreamStateIfMissing(hist, firstTimestamp);
    int rewindFrom = -1;
    for (jsize index = 0; index < count; ++index) {
      if (storeGlucoseStreamSample(
              hist, str, timeValues[index], glucoseValues[index],
              rawValues[index], temperatureValues[index], true, true, true,
              &rewindFrom)) {
        ++stored;
      }
    }
    if (rewindFrom >= 0 && backup) {
      hist->backstream(rewindFrom);
      hist->backhistory(rewindFrom);
    }
    if (stored > 0)
      setstreaming(hist);
  }

  env->ReleaseFloatArrayElements(temperatures, temperatureValues, JNI_ABORT);
  env->ReleaseFloatArrayElements(raws, rawValues, JNI_ABORT);
  env->ReleaseFloatArrayElements(glucoses, glucoseValues, JNI_ABORT);
  env->ReleaseLongArrayElements(timestamps, timeValues, JNI_ABORT);
  env->ReleaseStringUTFChars(sensorId, str);
  return stored;
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(addGlucoseStreamWithRawTemp)(
    JNIEnv *env, jclass cl, jlong timestamp, jfloat glucose, jfloat rawGlucose,
    jfloat temperatureC, jstring sensorId) {
  return addGlucoseStreamInternal(env, timestamp, glucose, rawGlucose,
                                  temperatureC, sensorId, true, true)
             ? JNI_TRUE
             : JNI_FALSE;
}

static jlong ensureSensorShellInternal(const char *str, jlong startTimeSec,
                                       size_t minimumRecords) {
  SensorGlucoseData *hist =
      ensureDirectStreamShellForId(str, static_cast<uint32_t>(startTimeSec),
                                   minimumRecords);
  if (!hist || hist->error()) {
    return 0LL;
  }
  seedDirectStreamStateIfMissing(hist, static_cast<time_t>(startTimeSec));

  hist->sensorerror = false;
  const int ind = hist->sensorIndex;
  if (auto *sens = sensors->getsensor(ind)) {
    sens->reactivateFromData();
    if (startTimeSec > 0 &&
        (sens->starttime == 0 ||
         static_cast<uint32_t>(startTimeSec) < sens->starttime)) {
      sens->starttime = static_cast<uint32_t>(startTimeSec);
    }
  }
  if (auto *info = hist->getinfo()) {
    if (startTimeSec > 0 &&
        (info->starttime == 0 ||
         static_cast<uint32_t>(startTimeSec) < info->starttime)) {
      info->starttime = static_cast<uint32_t>(startTimeSec);
      syncListStarttime(hist, static_cast<uint32_t>(startTimeSec));
    }
  }

  return reinterpret_cast<jlong>(hist);
}

extern "C" JNIEXPORT jlong JNICALL fromjava(ensureSensorShell)(
    JNIEnv *env, jclass cl, jstring sensorId, jlong startTimeSec) {
  if (!sensors || !sensorId)
    return 0LL;
  const char *str = env->GetStringUTFChars(sensorId, NULL);
  if (!str)
    return 0LL;
  const jlong result = ensureSensorShellInternal(str, startTimeSec, 0);
  env->ReleaseStringUTFChars(sensorId, str);
  return result;
}

extern "C" JNIEXPORT jlong JNICALL fromjava(ensureSensorShellWithCapacity)(
    JNIEnv *env, jclass cl, jstring sensorId, jlong startTimeSec,
    jint minimumRecords) {
  if (!sensors || !sensorId || minimumRecords <= 0)
    return 0LL;
  const char *str = env->GetStringUTFChars(sensorId, nullptr);
  if (!str)
    return 0LL;
  const jlong result = ensureSensorShellInternal(
      str, startTimeSec, static_cast<size_t>(minimumRecords));
  env->ReleaseStringUTFChars(sensorId, str);
  return result;
}

// Set the wear duration (days) for a direct-stream sensor (e.g. Ottai) by id. The
// AiDex helper aidexSetWearDays takes an aidexstream* and cannot be reused here —
// direct-stream sensors are plain SensorGlucoseData*. Writes info->wearduration2
// (minutes) so officialendtime()/expectedEndTime() and getmaxtime() reflect the
// real activated life.
extern "C" JNIEXPORT void JNICALL fromjava(setSensorWearDays)(
    JNIEnv *env, jclass cl, jstring sensorId, jint days) {
  // wearduration2 is uint16_t minutes, so past 45 days the product wraps into a
  // lifetime shorter than the sensor's; a garbage value must not land at all.
  constexpr const jint maxweardays = 0xFFFF / (24 * 60);
  if (!sensors || !sensorId || days <= 0 || days > maxweardays)
    return;
  const char *str = env->GetStringUTFChars(sensorId, NULL);
  if (!str)
    return;
  if (SensorGlucoseData *hist = ensureDirectStreamShellForId(str, 0)) {
    if (!hist->error()) {
      if (auto *info = hist->getinfo()) {
        info->wearduration2 = static_cast<uint16_t>(days * 24 * 60);
        // Deliberately NOT raising info->days here. It is the shell's storage
        // geometry, and data.dat/polls.dat/current.dat are all sized from it in
        // the SensorGlucoseData constructor's initializer list, while maxpos()
        // and maxstreampos() recompute from it on every call. Raising it
        // mid-session would leave those bounds describing twice the region that
        // is actually mapped. getmaxtime() consults wearduration2 instead, so
        // the lifetime the sensor negotiated is honoured without touching the
        // geometry of files that are already open.
        LOGGER("setSensorWearDays: %s days=%d wear=%u\n", str, days,
               info->wearduration2);
      }
    }
  }
  env->ReleaseStringUTFChars(sensorId, str);
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(hasSensorStreamCapacity)(
    JNIEnv *env, jclass cl, jstring sensorId, jint minimumRecords) {
  if (!sensors || !sensorId || minimumRecords <= 0)
    return JNI_FALSE;
  const char *str = env->GetStringUTFChars(sensorId, nullptr);
  if (!str)
    return JNI_FALSE;
  SensorGlucoseData *hist = ensureDirectStreamShellForId(str, 0);
  const bool ready =
      hist && !hist->error() &&
      hist->pollStorageCapacity() >= static_cast<size_t>(minimumRecords);
  env->ReleaseStringUTFChars(sensorId, str);
  return ready ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL fromjava(rebaseDirectStreamWindow)(
    JNIEnv *env, jclass cl, jstring sensorId, jlong startTimeSec) {
  if (!sensors || !sensorId || startTimeSec <= 0)
    return;
  const char *str = env->GetStringUTFChars(sensorId, NULL);
  if (!str)
    return;

  if (SensorGlucoseData *hist =
          ensureDirectStreamShellForId(str, static_cast<uint32_t>(startTimeSec))) {
    seedDirectStreamStateIfMissing(hist, static_cast<time_t>(startTimeSec));
    if (!hist->error()) {
      hist->rebaseDirectStreamWindow(static_cast<uint32_t>(startTimeSec));
      syncListStarttime(hist, static_cast<uint32_t>(startTimeSec));
    }
  }

  env->ReleaseStringUTFChars(sensorId, str);
}

extern "C" JNIEXPORT void JNICALL
fromjava(addRawGlucoseStream)(JNIEnv *env, jclass cl, jlong timestamp,
                              jfloat rawGlucose, jstring sensorId) {
  if (!sensors || !sensorId)
    return;
  const char *str = env->GetStringUTFChars(sensorId, NULL);
  if (!str)
    return;

  if (timestamp > 0) {
    if (SensorGlucoseData *hist = ensureDirectStreamShellForId(str, 0)) {
      if (hist->error()) {
        env->ReleaseStringUTFChars(sensorId, str);
        return;
      }
      seedDirectStreamStateIfMissing(hist, timestamp);
      auto *info = hist->getinfo();
      if (!info) {
        env->ReleaseStringUTFChars(sensorId, str);
        return;
      }

      uint32_t start = info->starttime;
      if (!start && timestamp > 3600) {
        start = timestamp - 3600;
        info->starttime = start;
        syncListStarttime(hist, start);
      }

      int lifeCount = 0;
      if (start > 0 && timestamp >= start) {
        lifeCount = (timestamp - start) / 60;
      }

      if (hist->validPollIndex(lifeCount) && hist->hasStreamID(lifeCount)) {
        int preservedAuto = hist->getPollsData()[lifeCount].g;
        uint16_t preservedTemp = hist->getTempForPoll(lifeCount);
        int rawVal = 0;
        if (rawGlucose > 0) {
          rawVal = compactRawMgdl(rawGlucose);
        } else {
          const RawData *rawbuf = hist->getRawPollsData();
          if (rawbuf) {
            rawVal = rawbuf[lifeCount].raw;
          }
        }
        // See addGlucoseStreamInternal(): derive rather than storing a fake flat 0.0f.
        const float change = derivechangeforsample(hist->getPollsData(), 0, lifeCount,
                                                   preservedAuto, timestamp);
        hist->savepollallIDs<60>(timestamp, lifeCount, preservedAuto, 0, change,
                                 rawVal, preservedTemp);
        if (backup) {
          // See addGlucoseStream(): raw-lane rewrites need the same mirror
          // resend range so follower Room data can be corrected too.
          hist->backstream(lifeCount);
          hist->backhistory(lifeCount);
        }
        setstreaming(hist);
      }
    }
  }
  env->ReleaseStringUTFChars(sensorId, str);
}

extern double calibrateNow(const SensorGlucoseData *sens,
                           const ScanData &value);

using HistorySamples = std::map<jlong, std::pair<jlong, jlong>>;

// Compose reads Room, while Libre NFC history is stored in historydata rather
// than polls. Export that 15-minute history so an NFC scan can populate the
// dashboard instead of only reaching native followers/LibreView.
static void appendStoredHistory(const SensorGlucoseData *hist, jlong starttime,
                                HistorySamples &samples) {
  const int start = std::max(0, hist->getstarthistory());
  const int end = std::min(hist->getAllendhistory(), hist->maxpos());
  for (int pos = start; pos < end; ++pos) {
    const Glucose *item = hist->getglucose(pos);
    if (!item->valid() || item->gettime() <= starttime)
      continue;

    samples[item->gettime()] = {(jlong)item->getsputnik(), 0};
  }
}

static void appendScanHistory(const SensorGlucoseData *hist, jlong starttime,
                              HistorySamples &samples) {
  const auto scans = hist->getScandata();
  for (const auto &item : scans) {
    if (!item.valid() || item.t <= starttime)
      continue;

    const double cali = calibrateNow(hist, item);
    const jlong valAuto = !isnan(cali) ? (jlong)(cali * 10) : (jlong)item.g * 10;

    // Scans have no dedicated raw lane like rawpolls.dat. Assignment is
    // intentional: a scan is a newer source than the periodic history point.
    samples[item.t] = {valAuto, 0};
  }
}

static bool rawOnlyPollValid(const ScanData &item, uint16_t rawVal,
                             jlong starttime) {
  return rawVal > 0 && item.t > starttime && item.id >= 0 &&
         item.t > 1598911200u && item.t < 2145909600u;
}

static void appendPollHistory(const SensorGlucoseData *hist, jlong starttime,
                              HistorySamples &samples) {
  auto polls = hist->getPolldata();
  static const double convfactordL = 18.0182;

  for (const auto &item : polls) {
    const uint16_t rawVal = hist->getRawForPoll(&item);
    const bool autoValid = item.valid();
    if ((!autoValid || item.t <= starttime) &&
        !rawOnlyPollValid(item, rawVal, starttime)) {
      continue;
    }

    jlong valAuto = 0;
    if (autoValid) {
      double cali = calibrateNow(hist, item);
      valAuto = !isnan(cali) ? (jlong)(cali * 10) : (jlong)item.g * 10;
    }

    const jlong valRaw = rawVal > 0 ? (jlong)(rawVal * convfactordL) : 0;

    // BLE polls are the highest-resolution source and win exact timestamp
    // collisions with NFC history or scans.
    samples[item.t] = {valAuto, valRaw};
  }
}

static jlongArray historySamplesToJava(JNIEnv *env,
                                       const HistorySamples &samples) {
  if (samples.empty())
    return nullptr;

  std::vector<jlong> result;
  result.reserve(samples.size() * 3);
  for (const auto &[time, values] : samples) {
    result.push_back(time);
    result.push_back(values.first);
    result.push_back(values.second);
  }

  jlongArray jresult = env->NewLongArray(result.size());
  env->SetLongArrayRegion(jresult, 0, result.size(), result.data());
  return jresult;
}

extern "C" JNIEXPORT jlongArray JNICALL
fromjava(getGlucoseHistory)(JNIEnv *env, jclass cl, jlong starttime) {
  // Use the user-selected main sensor instead of getlaststream() which picks
  // whichever sensor updated most recently — causing cross-sensor
  // contamination.
  const int mainIdx = sensors->infoblockptr()->current;
  const SensorGlucoseData *hist =
      (mainIdx >= 0) ? sensors->getSensorData(mainIdx) : nullptr;

  if (!hist)
    return nullptr;

  HistorySamples samples;
  appendStoredHistory(hist, starttime, samples);
  appendScanHistory(hist, starttime, samples);
  appendPollHistory(hist, starttime, samples);
  return historySamplesToJava(env, samples);
}

/**
 * Multi-sensor variant: get glucose history for a SPECIFIC sensor by its short
 * name. Unlike getGlucoseHistory() which always reads infoblockptr()->current,
 * this allows the Kotlin layer to sync ALL sensors into Room DB individually.
 *
 * Returns the same format: [time_sec, auto_mgdl*10, raw_mgdl*10, ...]
 * Returns null if sensor not found or has no data.
 */
extern "C" JNIEXPORT jlongArray JNICALL fromjava(getGlucoseHistoryForSensor)(
    JNIEnv *env, jclass cl, jstring sensorName, jlong starttime) {
  if (!sensorName)
    return nullptr;

  const char *name = env->GetStringUTFChars(sensorName, NULL);
  if (!name)
    return nullptr;

  // Try full name first, then short name
  SensorGlucoseData *hist = sensors->getSensorData(name);
  if (!hist)
    hist = sensors->gethistshort(name);

  env->ReleaseStringUTFChars(sensorName, name);

  if (!hist)
    return nullptr;

  HistorySamples samples;
  appendStoredHistory(hist, starttime, samples);
  appendScanHistory(hist, starttime, samples);
  appendPollHistory(hist, starttime, samples);
  return historySamplesToJava(env, samples);
}

jlong glucoseback(uint32_t nu, uint32_t glval, float drate,
                  SensorGlucoseData *hist) {
  if (!glval)
    return 0LL;
  hist->setbluetoothOn(1);
  auto res = glucoselong(nu, glval, drate, hist);
  hist->waiting = false;
  return res;
}

extern void wakeuploader();

extern void wakestreamuploader();
extern void wakelibrecurrent();
void wakewithcurrent() {
  wakestreamuploader();
#if !defined(WEAROS) && !defined(TESTMENU)
  if (settings->data()->LibreCurrentOnly) {
    LOGAR("wakelibrecurrent()");
    wakelibrecurrent();
  } else {
    LOGAR("!LibreCurrentOnly");
  }
#endif
}
extern "C" JNIEXPORT jlong JNICALL fromjava(processTooth)(
    JNIEnv *envin, jclass cl, jlong dataptr, jbyteArray bluetoothdata) {
  //    setbuffer(mystatus);
  setthreadname("processTooth");
  LOGGER("processTooth %ld\n", syscall(SYS_gettid));
  libre2stream *sdata = reinterpret_cast<libre2stream *>(dataptr);

  //    jint index=sdata->index;
  data_t *bluedata = fromjbyteArray(envin, bluetoothdata);
  uint32_t nu = time(NULL);
#ifndef NORAWSTREAM
  const struct iovec iov[2]{
      {reinterpret_cast<void *>(&nu), sizeof(nu)},
      {reinterpret_cast<void *>(bluedata->data()), (size_t)bluedata->size()}};
  writev(sdata->blueuit, iov, 2);
  /*  write(sdata->blueuit,reinterpret_cast<const char *>(&nu),sizeof(nu));
    write(sdata->blueuit,bluedata->data(),bluedata->size()); */
#endif
  //    scanstate *newstate=new scanstate(sdata->hist->getsensordir(),nu);
  scanstate *newstate = new scanstate(defaultscanstate);
  const AlgorithmResults *algres = sdata->processTooth(bluedata, newstate, nu);

  data_t::deleteex(bluedata);
  //    delete newstate;
  if (algres == ALGDUP_VALUE) {
    delete newstate;
    return 1LL;
  }
  if (algres) {
    destruct _algdes([algres] { delete algres; });
    constexpr unsigned waittime = 60 * 60;
    static time_t savetime = nu + waittime;
    sdata->setstate(newstate);
    if (nu > savetime) {
      savestate(sdata);
      savetime = nu + waittime;
    }
    decltype(auto) gluc = algres->currentglucose();
    const uint32_t glval = gluc.getValue();
    const float drate = gluc.rate();
    if (jlong res = glucoseback(nu, glval, drate, sdata->hist)) {
      sensor *senso = sensors->getsensor(sdata->sensorindex);
      sdata->hist->sensorerror = false;
      if (senso->finished) {
        LOGGER("processTooth finished=%d\n", senso->finished);
        if (senso->reactivateFromData())
          backup->resensordata(sdata->sensorindex);
      }

      backup->wakebackup(Backup::wakestream);
      wakewithcurrent();

      return res;
    }
  } else
    delete newstate;
  sdata->hist->sensorerror = true;
  sdata->hist->sensorErrorTime = nu;
  return 0LL;
}

/*
extern "C" JNIEXPORT void JNICALL   fromjava(backupstream)(JNIEnv *envin, jclass
cl,jlong dataptr) { streamdata *sdata=reinterpret_cast<streamdata *>(dataptr);
backup->updatestream(sdata->hist);
}
*/

extern "C" JNIEXPORT void JNICALL fromjava(setcurrentsensor)(JNIEnv *envin,
                                                             jclass cl,
                                                             jstring name) {
  if (!name)
    return;
  const char *str = envin->GetStringUTFChars(name, NULL);
  if (str) {
    sensors->setCurrentSensor(str);
    envin->ReleaseStringUTFChars(name, str);
  }
}

extern "C" JNIEXPORT jstring JNICALL fromjava(lastsensorname)(JNIEnv *envin,
                                                              jclass cl) {
  if (const auto *name = sensors->shortsensorname()->data()) {
    return envin->NewStringUTF(name);
  }
  return nullptr;
}
extern "C" JNIEXPORT jlong JNICALL fromjava(laststarttime)(JNIEnv *envin,
                                                           jclass cl) {
  return sensors->laststarttime();
}

extern "C" JNIEXPORT jlong JNICALL fromjava(getSensorEndTime)(
    JNIEnv *env, jclass cl, jlong dataptr, jboolean official) {
  if (!dataptr)
    return 0;
  const streamdata *sdata = reinterpret_cast<const streamdata *>(dataptr);
  const SensorGlucoseData *sens = sdata->hist;

  if (!sens)
    return 0;

  if (official) {
    return (jlong)sens->officialendtime() * 1000L;
  } else {
    return (jlong)sens->expectedEndTime() * 1000L;
  }
}
extern "C" JNIEXPORT jlong JNICALL
fromjava(getSensorEndTimeFromSensorptr)(JNIEnv *env, jclass cl, jlong sensorptr,
                                        jboolean official) {
  const auto *sens = reinterpret_cast<const SensorGlucoseData *>(sensorptr);
  if (!sens)
    return 0;

  if (official) {
    return (jlong)sens->officialendtime() * 1000L;
  } else {
    return (jlong)sens->expectedEndTime() * 1000L;
  }
}

extern std::vector<int> usedsensors;
extern void setusedsensors();
extern std::vector<int> usedsensorssnapshot();
extern jclass JNIString;

static jobjectArray sensorNamesToJavaArray(JNIEnv *env,
                                           const std::vector<std::string> &names) {
  jobjectArray sensjar = env->NewObjectArray(names.size(), JNIString, nullptr);
  for (int i = 0; i < static_cast<int>(names.size()); i++) {
    jstring name = env->NewStringUTF(names[i].c_str());
    if (name) {
      env->SetObjectArrayElement(sensjar, i, name);
      env->DeleteLocalRef(name);
    }
  }
  return sensjar;
}

/*
extern "C" JNIEXPORT jboolean  JNICALL   fromjava(hasSibionics)(JNIEnv *env,
jclass cl) { setusedsensors(); const int len= usedsensors.size(); for(int
i=0;i<len;i++) { const int index=usedsensors[i]; if(sensors->isSibionics(index))
            return true;
          }
   return false;
    }  */
extern bool hasGlucoseMeters();
extern "C" JNIEXPORT jboolean JNICALL fromjava(hasNeedScan)(JNIEnv *env,
                                                            jclass cl) {
  const auto active = usedsensorssnapshot();
  for (const int index : active) {
    if (sensors->needsScan(index))
      return true;
  }
  return hasGlucoseMeters();
}
#ifndef WEAROS
extern "C" JNIEXPORT jlongArray JNICALL fromjava(activeSensorPtrs)(JNIEnv *env,
                                                                   jclass cl) {
  const auto active = usedsensorssnapshot();
  const int len = active.size();
  std::vector<jlong> longar(len);
  LOGGER("activeSensorPtrs  len=%d\n", len);
  for (int i = 0; i < len; i++) {
    int index = active[i];
    const SensorGlucoseData *sens = sensors->getSensorData(index);
    longar[i] = reinterpret_cast<jlong>(sens);
  }
  jlongArray ptrAr = env->NewLongArray(len);
  if (len > 0)
    env->SetLongArrayRegion(ptrAr, 0, len, longar.data());
  return ptrAr;
}

extern "C" JNIEXPORT jstring JNICALL
fromjava(namefromSensorptr)(JNIEnv *env, jclass cl, jlong sensorptr) {
  const auto *sens = reinterpret_cast<const SensorGlucoseData *>(sensorptr);
  return env->NewStringUTF(sens->shortsensorname()->data());
}

extern "C" JNIEXPORT jstring JNICALL
fromjava(sensortextfromSensorptr)(JNIEnv *envin, jclass cl, jlong sensorptr) {
  if (!sensorptr)
    return envin->NewStringUTF("");
  const auto *sens = reinterpret_cast<const SensorGlucoseData *>(sensorptr);
  if (!sens)
    return envin->NewStringUTF("");
  return envin->NewStringUTF(getsensortext(sens).data());
}
extern "C" JNIEXPORT jint JNICALL
fromjava(getViewModeFromSensorptr)(JNIEnv *env, jclass cl, jlong sensorptr) {
  const auto *sens = reinterpret_cast<const SensorGlucoseData *>(sensorptr);
  if (!sens)
    return 0;
  const auto *info = sens->getinfo();
  return info ? info->viewMode : 0;
}

extern "C" JNIEXPORT void JNICALL
fromjava(finishfromSensorptr)(JNIEnv *env, jclass cl, jlong sensorptr) {
  auto *sens = reinterpret_cast<SensorGlucoseData *>(sensorptr);
  const int sensorindex = sensors->sensorindex(sens->sensorname()->data());
  finishsensor(sens, sensorindex);
}
#endif
#ifdef LIBRE3
extern "C" JNIEXPORT jobjectArray JNICALL fromjava(activeSensors)(JNIEnv *env,
                                                                  jclass cl) {
  const auto active = usedsensorssnapshot();
  std::vector<std::string> names;
  names.reserve(active.size());
  for (const int index : active) {
    const char *name = sensors->shortsensorname_chars(index);
    if (name)
      names.emplace_back(name);
  }
  return sensorNamesToJavaArray(env, names);
}
#else
extern "C" JNIEXPORT jobjectArray JNICALL fromjava(activeSensors)(JNIEnv *env,
                                                                  jclass cl) {
  const auto active = usedsensorssnapshot();
  std::vector<std::string> names;
  names.reserve(active.size());
  for (const int index : active) {
    const SensorGlucoseData *sens = sensors->getSensorData(index);
    const char *name = sensors->shortsensorname_chars(index);
    if (sens && !sens->isLibre3() && name)
      names.emplace_back(name);
  }
  return sensorNamesToJavaArray(env, names);
}
#endif

// extern "C" JNIEXPORT void JNICALL   fromjava(saveState)(JNIEnv *envin, jclass
// cl,jlong dataptr) {

/*
extern "C" JNIEXPORT void JNICALL   fromjava(setwaiting)(JNIEnv *envin, jclass
cl,jlong dataptr,jboolean val) { streamdata *sdata=reinterpret_cast<streamdata
*>(dataptr); SensorGlucoseData *usedhist=sdata->hist ; usedhist->waiting=val;
    }
extern "C" JNIEXPORT jboolean JNICALL   fromjava(iswaiting)(JNIEnv *envin,
jclass cl,jlong dataptr) { if(!settings->availableAlarm()) return false; else {
        streamdata *sdata=reinterpret_cast<streamdata *>(dataptr);
        const SensorGlucoseData *usedhist=sdata->hist ;
        return usedhist->waiting;
        }
    }
    */

#if defined(__aarch64__)
#define archstring "arm64-v8a"
#elif defined(__x86_64__)
#define archstring "x86_64"
#elif defined(__i386__)
#define archstring "x86"
#else // defined(__arm__)
#define archstring "armeabi-v7a"
#endif

extern "C" JNIEXPORT jstring JNICALL fromjava(getLibraryName)(JNIEnv *env,
                                                              jclass cl) {
  return env->NewStringUTF("lib/" archstring "/libDataProcessing.so");
}
extern "C" JNIEXPORT jstring JNICALL fromjava(getCPUarch)(JNIEnv *env,
                                                          jclass cl) {
  return env->NewStringUTF(archstring);
}

/*
#include "destruct.hpp"

extern "C" JNIEXPORT jstring  JNICALL   fromjava(chmod)(JNIEnv *env, jclass
cl,jstring filename,jint mode) { const char *str = env->GetStringUTFChars(
filename, NULL); if (str == nullptr) return nullptr; destruct
dest([filename,str,env]() {env->ReleaseStringUTFChars(filename, str);});
    if(chmod(str,mode)!=0) {
        const int errn=errno;
        return env->NewStringUTF(strerror(errn));
        }
    return nullptr;
    }

extern "C" JNIEXPORT void  JNICALL   fromjava(startbackup)(JNIEnv *env, jclass
cl) { if(backup ) { setthreadname( "Backup"); backup->backupthread();
        }
    }*/

extern "C" JNIEXPORT jboolean JNICALL fromjava(sameSensor)(JNIEnv *env,
                                                           jclass _cl,
                                                           jlong one,
                                                           jlong two) {
  if (one == 0LL || two == 0LL)
    return false;
  streamdata *sone = reinterpret_cast<streamdata *>(one);
  streamdata *stwo = reinterpret_cast<streamdata *>(two);
  if (sone->sensorindex == stwo->sensorindex)
    return true;
  return false;
}
#ifdef NFCMEM
extern "C" JNIEXPORT jlong JNICALL fromjava(nfcptr)(JNIEnv *env, jclass _cl,
                                                    jbyteArray juid,
                                                    jbyteArray jinfo) {
  abbottinit();
  return reinterpret_cast<jlong>(new NfcMemory(env, juid, jinfo));
}
extern "C" JNIEXPORT jint JNICALL fromjava(nfcstart)(JNIEnv *_env, jclass _cl,
                                                     jlong ptr) {
  NfcMemory *mem = reinterpret_cast<NfcMemory *>(ptr);
  return mem->nextspan();
}
extern "C" JNIEXPORT jint JNICALL fromjava(nfcadd)(JNIEnv *env, jclass _cl,
                                                   jlong ptr,
                                                   jbyteArray jscanned) {
  NfcMemory *mem = reinterpret_cast<NfcMemory *>(ptr);
  data_t *scanned = fromjbyteArray(env, jscanned);
  LOGGER("nfcadd %p #%d\n", ptr, scanned->size());
  mem->add(scanned);
  data_t::deleteex(scanned);
  return mem->nextspan();
};
extern "C" JNIEXPORT jbyteArray JNICALL fromjava(nfcgetresults)(JNIEnv *env,
                                                                jclass _cl,
                                                                jlong ptr) {
  LOGSTRING("nfcgetresults\n");
  NfcMemory *mem = reinterpret_cast<NfcMemory *>(ptr);
  int len = mem->len;
  jbyteArray uit = env->NewByteArray(len);
  env->SetByteArrayRegion(uit, 0, len, mem->results->data());
  delete mem;
  return uit;
}
#endif
#define abbottdec(x) (*x)
#define abbottcall(x) x
extern "C" jint JNICALL abbottdec(P1)(JNIEnv *envin, jobject obj, jint i,
                                      jint i2, jbyteArray bArr,
                                      jbyteArray bArr2, jbyteArray token72);
extern "C" jbyteArray JNICALL abbottdec(P2)(JNIEnv *, jobject, jint, jint,
                                            jbyteArray, jbyteArray,
                                            jbyteArray token72);
/*
extern "C"  jint JNICALL   fromjava(V1)(JNIEnv *envin, jobject obj, jint i, jint
i2,jbyteArray  bArr,jbyteArray  bArr2)  { return  abbottcall(P1)(envin, obj, i,
i2, bArr, bArr2)  ;
    }
extern "C"  jbyteArray JNICALL fromjava(V2)(JNIEnv *env, jobject obj, jint i1,
jint i2, jbyteArray ar1, jbyteArray ar2) { return
abbottcall(P2)(env,obj,i1,i2,ar1,ar2);
    }*/

int abbottreinit();
bool resetwrong();
bool setgen2() {
  debugclone();
  if (!P1) {
    resetwrong();
    return abbottreinit() >= 0;
  } else {
    return abbottinit() >= 0;
  }
}
#ifdef SAVEPS
/*
struct p1struct {
    jint i1;
    jint i2;
    unsigned char  *ar1;
    unsigned char  *ar2;
    jint res;
    }; */
/*
static void writearray(JNIEnv *env,jbyteArray  jar,FILE *fp,const char *name) {
const data_t *ar=fromjbyteArray(env,jar);
fprintf(fp,"const unsigned char %s[]={",name);
const unsigned char *dat=reinterpret_cast<const unsigned char*>(ar->data());
const int len= ar->size();
for(int i=0;i<len;i++)
    fprintf(fp,"0x%x,",dat[i]);
fputs("};\n",fp);
}
*/
static void writearray(JNIEnv *env, jbyteArray jar, FILE *fp) {
  fprintf(fp, ",{");
  if (jar) {
    const data_t *ar = fromjbyteArray(env, jar);
    const unsigned char *dat =
        reinterpret_cast<const unsigned char *>(ar->data());
    const int len = ar->size();
    for (int i = 0; i < len; i++)
      fprintf(fp, "0x%x,", dat[i]);
    data_t::deleteex(ar);
  };
  fprintf(fp, "}");
}
int p1funcit = 0;
FILE *funcfp;
extern "C" JNIEXPORT jint JNICALL fromjava(V1)(JNIEnv *env, jobject obj, jint i,
                                               jint i2, jbyteArray bArr,
                                               jbyteArray bArr2) {
  setgen2();
  jint res = abbottcall(P1)(env, obj, i, i2, bArr, bArr2, getjtoken(env));
  fprintf(funcfp, "p1struct p1_%d={%d,%d", p1funcit++, i, i2);
  writearray(env, bArr, funcfp);
  writearray(env, bArr2, funcfp);
  fprintf(funcfp, ",%d};\n", res);
  fflush(funcfp);
  return res;
}
int p2funcit = 0;
extern "C" JNIEXPORT jbyteArray JNICALL fromjava(V2)(JNIEnv *env, jobject obj,
                                                     jint i1, jint i2,
                                                     jbyteArray jar1,
                                                     jbyteArray jar2) {
  setgen2();
  jbyteArray jres =
      abbottcall(P2)(env, obj, i1, i2, jar1, jar2, getjtoken(env));
  fprintf(funcfp, "p2struct p2_%d={%d,%d", p2funcit++, i1, i2);
  writearray(env, jar1, funcfp);
  writearray(env, jar2, funcfp);
  writearray(env, jres, funcfp);
  fputs("};\n", funcfp);
  fflush(funcfp);
  return jres;
}
#else

extern "C" JNIEXPORT jint JNICALL fromjava(V1)(JNIEnv *env, jclass obj, jint i,
                                               jint i2, jbyteArray bArr,
                                               jbyteArray bArr2) {
  setgen2();
  jint res = abbottcall(P1)(env, obj, i, i2, bArr, bArr2, getjtoken(env));
  LOGGER("V1(%i,%i,%p,%p)=%i\n", i, i2, bArr, bArr2, res);
  return res;
}
int p2funcit = 0;
extern "C" JNIEXPORT jbyteArray JNICALL fromjava(V2)(JNIEnv *env, jclass obj,
                                                     jint i1, jint i2,
                                                     jbyteArray jar1,
                                                     jbyteArray jar2) {
  setgen2();
  jbyteArray jres =
      abbottcall(P2)(env, obj, i1, i2, jar1, jar2, getjtoken(env));
  LOGGER("V2(%d,%d,%p,%p)=%p\n", i1, i2, jar1, jar2, jres);
  return jres;
}
#endif
/*
bool registernatives(JNIEnv* env) {
    if(!(abbottcall(P2)&&abbottcall(P2)))
        return false;

const char nativesclass[]= "tk/glucodata/Natives";
    jclass c = env->FindClass(nativesclass);
    LOGSTRING("after FindClass \n");
#ifndef NDEBUG
extern string_view filesdir;
    pathconcat funcfile(filesdir,"funcs.h");
    funcfp=fopen(funcfile.data(),"w");
    if(funcfp==nullptr) {
        flerror("open %s failed: ",funcfile.data());
        }
#endif
    if (c == nullptr) {
        LOGGER("FindClass(%s) failed\n",nativesclass);
        return false;
    }
         static const JNINativeMethod methods[] = {
            {"P1", "(II[B[B)I", reinterpret_cast<void*>(P1wrap)},
            {"P2", "(II[B[B)[B", reinterpret_cast<void*>(P2wrap)}
        };
    int rc = env->RegisterNatives(c, methods,
sizeof(methods)/sizeof(methods[0]));

   env->DeleteLocalRef(c);
    if (rc != JNI_OK)  {
        LOGSTRING("RegisterNatives failed\n");
        return false;
        }
        LOGSTRING("RegisterNatives  OK\n");
   return true;
  }
static bool *registeredptr=nullptr;
*/
extern "C" JNIEXPORT jboolean JNICALL fromjava(abbottinit)(JNIEnv *env,
                                                           jclass _cl) {
  if (abbottinit(false) < 0)
    return false;
  return P1 != nullptr;
}

static void reinitabbotter(std::promise<bool> *prom) {
  resetwrong();
  prom->set_value(abbottreinit() >= 0);
}
extern bool reinitabbott();
bool reinitabbott() {
  LOGSTRING("abbottreinit\n");
  pid_t pid = syscall(SYS_getpid);
  pid_t tid = syscall(SYS_gettid);
  if (tid == pid) {
    LOGGER("tid=%d mainthread\n", tid);
    std::promise<bool> prom;
    std::future<bool> fut = prom.get_future();
    std::thread thr(reinitabbotter, &prom);
    thr.join();
    return fut.get();
  } else {
    LOGGER("already on thread pid=%d!=tid=%d\n", pid, tid);
    resetwrong();
    return abbottreinit() >= 0;
  }
}
extern "C" JNIEXPORT jboolean JNICALL fromjava(abbottreinit)(JNIEnv *env,
                                                             jclass _cl) {
  return reinitabbott();
}
void setstreaming(SensorGlucoseData *hist) {
  LOGSTRING("setstreaming(SensorGlucoseData)\n");
  if (!backup)
    return;
  int maxint = backup->getupdatedata()->sendnr;
  if (maxint > maxsendtohost) {
    maxint = maxsendtohost;
    backup->getupdatedata()->sendnr = maxint;
  }
  if (maxint > 0)
    hist->setsendstreaming(maxint);
}
void sendKAuth(SensorGlucoseData *hist) {
  LOGSTRING("setsendKauth(SensorGlucoseData)\n");
  if (!backup)
    return;
  int maxint = backup->getupdatedata()->sendnr;
  hist->setsendKAuth(maxint);
}

void sendsiScan(SensorGlucoseData *hist) {
  int maxint = backup->getupdatedata()->sendnr;
  hist->setsiScan(maxint);
}

void sendstreaming(SensorGlucoseData *hist) {
  setstreaming(hist);
  backup->wakebackup(Backup::wakeall);
}

extern "C" JNIEXPORT void JNICALL fromjava(reenableStreaming)(JNIEnv *env,
                                                              jclass _cl) {
  setbluetoothon = true;
}

static int lastgen = 0;
bool hasGen2 = false, hasGen1 = false;
/*#ifndef NDEBUG
#define TESTGEN2 1
#endif */
#ifdef TESTGEN2
int getlastGen() {
  extern void setusedsensors();
  extern std::vector<int> usedsensors;
  setusedsensors();
  bool has2 = true, has1 = true;
  hasGen1 = has1;
  hasGen2 = has2;
  //    lastgen=has2?2:1;
  return has2 ? 2 : 1;
}
#else
int getlastGen() {
  if (lastgen)
    return lastgen;
  extern void setusedsensors();
  extern std::vector<int> usedsensors;
  setusedsensors();
  bool has2 = false, has1 = false;
  for (int index : usedsensors) {
    auto sens = sensors->getSensorData(index);
    if (sens->useLibre2rootcheck()) {
      has2 = true;
    } else {
      has1 = true;
    }
  }
  hasGen1 = has1;
  hasGen2 = has2;
  //    lastgen=has2?2:1;
  //    return lastgen;
  return has2 ? 2 : 1;
}
#endif

extern void closedynlib();
extern bool switchgen2();
extern oldprocessStream_t oldprocessStream;

extern bool reinitabbott();
bool switchgen2() {
  if (!P1) {
    lastgen = 2;
    if (oldprocessStream) {
      closedynlib();
      return reinitabbott() && P1;
      /*
      if(abbottreinit()>=0&&P1)
              return true; */
      return false;
    }
  }
  return true;
}
extern "C" JNIEXPORT jboolean JNICALL fromjava(switchgen2)(JNIEnv *env,
                                                           jclass cl) {

  if (P1 == nullptr) {
    lastgen = 2;
    if (abbottinit() < 0)
      return false;
#ifdef CARRY_LIBS
    if (!P1) {
      closedynlib();
      if (abbottreinit() >= 0 && P1)
        return true;
      return false;
    }
#else
    return P1;
#endif
  }
  return true;
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(hasstreamed)(JNIEnv *env,
                                                            jclass _cl) {
  if (sensors)
    return sensors->hasstream();
  return false;
}
extern "C" JNIEXPORT jboolean JNICALL fromjava(hasscans)(JNIEnv *env,
                                                         jclass _cl) {
  if (sensors)
    return sensors->hasscans();
  return false;
}

extern std::string_view getdeltaname(float rate);
extern "C" JNIEXPORT jstring JNICALL fromjava(getxDripTrendName)(JNIEnv *envin,
                                                                 jclass cl,
                                                                 jfloat rate) {
  return envin->NewStringUTF(getdeltaname(rate).data());
}
extern "C" JNIEXPORT jint JNICALL fromjava(getinfogen)(JNIEnv *env, jclass _cl,
                                                       jbyteArray jinfo) {
  jsize lens = env->GetArrayLength(jinfo);
  char info[lens];
  env->GetByteArrayRegion(jinfo, 0, lens, reinterpret_cast<jbyte *>(info));
  int gen = SensorGlucoseData::getgeneration(info);

#ifndef NOLOG
  const char label[] = "getinfogen ";
  auto labellen = sizeof(label) - 1;
  int totlen = labellen + lens * 3 + 4;
  char mess[totlen];
  memcpy(mess, label, labellen);
  int pos = labellen;
  for (int i = 0; i < lens; i++) {
    pos += sprintf(mess + pos, "%02X ", info[i]);
  }
  pos += sprintf(mess + pos, "=%d", gen);
  LOGGERN(mess, pos);
#endif

  return gen;
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(hasData)(JNIEnv *env,
                                                        jclass _cl) {
  return sensors->last() >= 0 || settings->getlabelcount() > 0;
}

extern "C" JNIEXPORT jboolean JNICALL
fromjava(optionStreamHistory)(JNIEnv *env, jclass cl, jlong dataptr) {
#if defined(__aarch64__)
  return false;
#else
  const streamdata *sdata = reinterpret_cast<const streamdata *>(dataptr);
  if (!sdata) {
    return !settings->data()->nobluetooth;
  }
  if (sdata->libreversion >= 2)
    return false;
  const SensorGlucoseData *sensorptr = sdata->hist;
  return !sensorptr->useLibre2rootcheck();
#endif
}
