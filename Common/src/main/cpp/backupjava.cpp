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

#include "datbackup.hpp"
#include "fromjava.h"
#include "net/netstuff.hpp"
#include <alloca.h>
#include <cstring>
#include <jni.h>
#include <string_view>
#include <vector>
extern jclass JNIString;
extern jstring myNewStringUTF(JNIEnv *env, const std::string_view str);
extern bool networkpresent;

extern "C" JNIEXPORT jboolean JNICALL fromjava(backuphasrestore)(JNIEnv *env,
                                                                 jclass cl) {
  return backup->getupdatedata()->hasrestore;
}
extern "C" JNIEXPORT jint JNICALL fromjava(backuphostNr)(JNIEnv *env,
                                                         jclass cl) {
  return backup->gethostnr();
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(detectIP)(JNIEnv *envin,
                                                         jclass cl, jint pos) {
  const passhost_t &host = backup->getupdatedata()->allhosts[pos];
  return host.detect;
}
extern "C" JNIEXPORT jboolean JNICALL
fromjava(getbackupHasHostname)(JNIEnv *envin, jclass cl, jint pos) {
  const passhost_t &host = backup->getupdatedata()->allhosts[pos];
  return host.hashostname();
}
extern "C" JNIEXPORT jobjectArray JNICALL fromjava(getbackupIPs)(JNIEnv *env,
                                                                 jclass cl,
                                                                 jint pos) {
  if (!backup) {
    LOGSTRING("backup==null\n");
    return nullptr;
  }
  const auto hostnr = backup->gethostnr();
  if (pos >= hostnr) {
    LOGGER("pos(%d)>=backup->gethostnr()(%d)\n", pos, hostnr);
    return nullptr;
  }
  passhost_t &host = backup->getupdatedata()->allhosts[pos];
  LOGGER("%s pos=%d nr=%d index=%d\n", host.getnameif(), pos, host.nr,
         host.index);
  int len = host.nr;
  if (len < 0 || len > passhost_t::maxip) {
    LOGGER("host.nr==%d\n", len);
    host.nr = len = 0;
  }
  jobjectArray ipar = env->NewObjectArray(len, JNIString, nullptr);
  if (!ipar) {
    LOGGER(R"(NewObjectArray(%d,JNIString==null)"
           "\n",
           len);
    return nullptr;
  }
  if (len > 0) {
    if (host.hashostname()) {
      env->SetObjectArrayElement(ipar, 0,
                                 env->NewStringUTF(host.gethostname()));
    } else {
      for (int i = 0; i < len; i++) {
        namehost name(host.ips + i);
        LOGGER("%s\n", name.data());
        env->SetObjectArrayElement(ipar, i, env->NewStringUTF(name));
      }
    }
  }
  return ipar;
}

/*
extern "C" JNIEXPORT jstring JNICALL   fromjava(getbackuphostname)(JNIEnv
*envin, jclass cl,jint pos) {
    if(address(backup->getupdatedata()->allhosts[pos])) {
        auto host=backup->gethost(pos);
        return envin->NewStringUTF(host);
        }
    return nullptr;
    }
    */

int getposbylabel(const char *label) {
  const int nr = backup->gethostnr();
  for (int pos = 0; pos < nr; ++pos) {
    const passhost_t &host = backup->getupdatedata()->allhosts[pos];
    if (!host.hasname)
      continue;
    if (!strcmp(host.getname(), label)) {
      LOGGER("getposbylabel(%s)=%d\n", label, pos);
      return pos;
    }
  }
  LOGGER("getposbylabel(%s)=-1\n", label);
  return -1;
}
bool removebylabel(const char *label) {
  int pos = getposbylabel(label);
  if (pos < 0)
    return false;
  backup->deletehost(pos);
  return true;
}
/*
extern "C" JNIEXPORT jboolean JNICALL   fromjava(removebylabel)(JNIEnv *env,
jclass cl,jstring jlabel) { const char *label = env->GetStringUTFChars( jlabel,
NULL); if(!label) return false; destruct   dest([jlabel,label,env]()
{env->ReleaseStringUTFChars(jlabel, label);}); return removebylabel(label);
    } */

#ifndef ABBOTT
passhost_t *getwearoshost(const bool create, const char *label, bool,
                          bool = false);
bool resetbylabel(const char *label, bool galaxy) {

  int pos = getposbylabel(label);
  if (pos < 0)
    return false;
  const passhost_t &host = backup->getupdatedata()->allhosts[pos];
  const int nr = host.nr;
  if (nr > 0) {
    struct sockaddr_in6 ips[passhost_t::maxip];
    memcpy(ips, host.ips, sizeof(ips));

    passhost_t *newhost = getwearoshost(true, label, galaxy, true);
    memcpy(newhost->ips, ips, sizeof(ips));
    newhost->nr = std::min(nr, passhost_t::maxip);
  } else {
    backup->deletehost(pos);
  }
  return true;
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(resetbylabel)(JNIEnv *env,
                                                             jclass cl,
                                                             jstring jlabel,
                                                             jboolean galaxy) {
  const char *label = env->GetStringUTFChars(jlabel, NULL);
  if (!label)
    return false;
  LOGGER("resetbylabel(%s,%d)\n", label, galaxy);
  destruct dest(
      [jlabel, label, env]() { env->ReleaseStringUTFChars(jlabel, label); });
  return resetbylabel(label, galaxy);
}
#endif
const char *gethostlabel(int pos) {
  if (!backup || pos < 0 || pos >= backup->gethostnr())
    return nullptr;
  const passhost_t &host = backup->getupdatedata()->allhosts[pos];
  if (!host.hasname)
    return nullptr;
  return host.getname();
}
bool gethosttestip(int pos) {
  if (!backup || pos >= backup->gethostnr())
    return true;
  const passhost_t &host = backup->getupdatedata()->allhosts[pos];
  return !host.noip;
}
extern "C" JNIEXPORT jboolean JNICALL fromjava(getbackuptestip)(JNIEnv *envin,
                                                                jclass cl,
                                                                jint pos) {
  return gethosttestip(pos);
}
extern "C" JNIEXPORT jstring JNICALL fromjava(getbackuplabel)(JNIEnv *envin,
                                                              jclass cl,
                                                              jint pos) {
  if (const char *label = gethostlabel(pos))
    return myNewStringUTF(envin, label);
  return nullptr;
}
extern "C" JNIEXPORT jstring JNICALL fromjava(getbackuppassword)(JNIEnv *envin,
                                                                 jclass cl,
                                                                 jint pos) {
  if (!backup || pos >= backup->gethostnr())
    return nullptr;
  return myNewStringUTF(envin, backup->getpass(pos).data());
}
extern "C" JNIEXPORT jstring JNICALL fromjava(getbackuphostport)(JNIEnv *envin,
                                                                 jclass cl,
                                                                 jint pos) {
  if (!backup || pos >= backup->gethostnr())
    return nullptr;

  char port[6];
  backup->getport(pos, port);
  return envin->NewStringUTF(port);
}
extern "C" JNIEXPORT jboolean JNICALL fromjava(isWearOS)(JNIEnv *envin,
                                                         jclass cl, jint pos) {
  if (!backup || pos < 0 || pos >= backup->gethostnr()) {
    LOGGER("isWearos(%d)=false\n", pos);
    return false;
  }
  auto ret = backup->getupdatedata()->allhosts[pos].wearos;
  LOGGER("isWearos(%d)=%d\n", pos, ret);
  return ret;
}

bool getpassive(int pos);
bool getactive(int pos);
extern "C" JNIEXPORT jboolean JNICALL
fromjava(getbackuphostactive)(JNIEnv *envin, jclass cl, jint pos) {
  bool active = getactive(pos);
  LOGGER("getbackuphostactive(%d)=%d\n", pos, active);
  return active;
}

extern "C" JNIEXPORT jboolean JNICALL
fromjava(getbackuphostpassive)(JNIEnv *envin, jclass cl, jint pos) {
  return getpassive(pos);
}
extern "C" JNIEXPORT int JNICALL fromjava(getbackuphostreceive)(JNIEnv *envin,
                                                                jclass cl,
                                                                jint pos) {
  if (pos < backup->getupdatedata()->hostnr)
    return backup->getupdatedata()->allhosts[pos].receivefrom;
  return 0;
}
extern "C" JNIEXPORT jboolean JNICALL fromjava(getbackuphostnums)(JNIEnv *envin,
                                                                  jclass cl,
                                                                  jint pos) {
  if (pos < backup->getupdatedata()->hostnr) {
    int index = backup->getupdatedata()->allhosts[pos].index;
    if (index >= 0)
      return backup->getupdatedata()->tosend[index].sendnums;
  }
  return false;
}
extern "C" JNIEXPORT jboolean JNICALL
fromjava(getbackuphoststream)(JNIEnv *envin, jclass cl, jint pos) {
  if (pos < backup->getupdatedata()->hostnr) {
    int index = backup->getupdatedata()->allhosts[pos].index;
    if (index >= 0)
      return backup->getupdatedata()->tosend[index].sendstream;
  }
  return false;
}
extern "C" JNIEXPORT jboolean JNICALL
fromjava(getbackuphostscans)(JNIEnv *envin, jclass cl, jint pos) {
  if (pos < backup->getupdatedata()->hostnr) {
    int index = backup->getupdatedata()->allhosts[pos].index;
    if (index >= 0)
      return backup->getupdatedata()->tosend[index].sendscans;
  }
  return false;
}

extern "C" JNIEXPORT jboolean JNICALL
fromjava(getbackupTestLabel)(JNIEnv *envin, jclass cl, jint pos) {
  // Dummy implementation as testlabel likely transient or not stored
  return false;
}

extern "C" JNIEXPORT jboolean JNICALL
fromjava(getbackupActiveOnly)(JNIEnv *envin, jclass cl, jint pos) {
  // Wrapper for getbackuphostactive if that represents activeonly
  return getactive(pos);
}

extern "C" JNIEXPORT void JNICALL fromjava(setreceiveport)(JNIEnv *env,
                                                           jclass cl,
                                                           jstring jport) {
  if (jport == nullptr)
    return;
  const char *port_chars = env->GetStringUTFChars(jport, nullptr);
  if (port_chars == nullptr)
    return;
  jint portlen = strlen(port_chars);
  if (portlen < 6) {
    if (backup->getupdatedata()->port[portlen] ||
        memcmp(port_chars, backup->getupdatedata()->port, portlen)) {
      memcpy(backup->getupdatedata()->port, port_chars, portlen);
      backup->getupdatedata()->port[portlen] = '\0';
      //        backup->stopreceiver();
      backup->startreceiver(true);
    }
  }
  env->ReleaseStringUTFChars(jport, port_chars);
}
extern "C" JNIEXPORT jstring JNICALL fromjava(getreceiveport)(JNIEnv *env,
                                                              jclass cl) {

  return env->NewStringUTF(backup->getupdatedata()->port);
}
/*
extern "C" JNIEXPORT jboolean JNICALL   fromjava(stringarray)(JNIEnv *env,
jclass cl,jobjectArray jar ) { constexpr const int maxad=4; int
len=env->GetArrayLength(jar); LOGSTRING("stringarray "); const char
port[]="8795"; struct sockaddr_in6     connect[maxad]; int
uselen=std::min(maxad,len); for(int i=0;i<uselen;i++) { jstring
jname=(jstring)env->GetObjectArrayElement(jar,i); int namelen=
env->GetStringUTFLength( jname); char name[namelen+1]; env->GetStringUTFRegion(
jname, 0,namelen, name); name[namelen]='\0'; LOGGER("%s ",name);
        if(!getaddr(name,port,connect+i))
            return  false;
        }
    LOGSTRING("\nips: ");
    for(int i=0;i<uselen;i++) {
        LOGGER("%s ",namehost(connect+i));
        }
    LOGSTRING("\n");
    return true;
    }
    */

// extern "C" JNIEXPORT jint JNICALL   fromjava(changebackuphost)(JNIEnv *env,
// jclass cl,jint pos,jobjectArray jnames,jint nr,jboolean detect,jstring
// jport,jboolean nums,jboolean stream,jboolean scans,jboolean recover,jboolean
// receive,jboolean reconnect,jboolean accepts,jstring jpass,jlong starttime) {
// extern bool mkwearos;

#ifndef TESTMENU
#include <mutex>
extern std::mutex change_host_mutex;
#endif
extern "C" JNIEXPORT jint JNICALL fromjava(changebackuphost)(
    JNIEnv *env, jclass cl, jint pos, jobjectArray jnames, jint nr,
    jboolean detect, jstring jport, jboolean nums, jboolean stream,
    jboolean scans, jboolean recover, jboolean receive, jboolean activeonly,
    jboolean passiveonly, jstring jpass, jlong starttime, jstring jlabel,
    jboolean testip, jboolean hashostname, jstring jicelabel, jboolean side) {
#ifndef TESTMENU
  LOGAR("changebackuphost const std::lock_guard<std::mutex> "
        "lock(change_host_mutex)");
  const std::lock_guard<std::mutex> lock(change_host_mutex);
#endif
  LOGGER("changebackuphost(%d,%p,%d,%d,%p,%d,%d,%d,%d%,%d,%d,%d,%p,%ld,%p,%d,%"
         "d)\n",
         pos, jnames, nr, detect, jport, nums, stream, scans, recover, receive,
         activeonly, passiveonly, jpass, starttime, jlabel, testip,
         hashostname);
  const char *passptr = nullptr;
  if (jpass) {
    passptr = env->GetStringUTFChars(jpass, nullptr);
  }
  const char *label = jlabel ? env->GetStringUTFChars(jlabel, NULL) : nullptr;
  jint res = -1;
  const bool hasicelabel =
      jicelabel && env->GetStringUTFLength(jicelabel) > 0;

  if (hasicelabel) {
    const char *ICElabel = env->GetStringUTFChars(jicelabel, nullptr);
    if (ICElabel) {
      res = backup->changeICEhost(
          ICElabel, pos, nums, stream, scans, receive,
          passptr ? std::string_view(passptr) : std::string_view(), starttime,
          label, side, true);
      env->ReleaseStringUTFChars(jicelabel, ICElabel);
    }
  } else {
    const char *port = env->GetStringUTFChars(jport, nullptr);
    if (!port) {
      if (jlabel)
        env->ReleaseStringUTFChars(jlabel, label);
      if (jpass)
        env->ReleaseStringUTFChars(jpass, passptr);
      return -1;
    }
    int portlen = strlen(port);

    const int arlen = jnames ? std::min(env->GetArrayLength(jnames), nr) : 0;
    res = backup->changehost(
        pos, env, jnames, arlen, detect, std::string_view(port, portlen), nums,
        stream, scans, recover, receive, activeonly,
        passptr ? std::string_view(passptr) : std::string_view(), starttime,
        passiveonly, label, testip, true, hashostname);
    env->ReleaseStringUTFChars(jport, port);
  }
  if (jlabel)
    env->ReleaseStringUTFChars(jlabel, label);
  if (jpass)
    env->ReleaseStringUTFChars(jpass, passptr);
  return res;
}
extern "C" JNIEXPORT jboolean JNICALL fromjava(isreceiving)(JNIEnv *env,
                                                            jclass cl) {
  return backup->isreceiving();
}
extern "C" JNIEXPORT void JNICALL fromjava(deletebackuphost)(JNIEnv *env,
                                                             jclass cl,
                                                             jint pos) {
  backup->deletehost(pos);
}
extern "C" JNIEXPORT jlong JNICALL fromjava(lastuptodate)(JNIEnv *env,
                                                          jclass cl, jint pos) {
  return lastuptodate[pos] * 1000LL;
}
extern "C" JNIEXPORT void JNICALL fromjava(setWifi)(JNIEnv *env, jclass cl,
                                                    jboolean val) {
  settings->data()->keepWifi = val;
}
extern "C" JNIEXPORT jboolean JNICALL fromjava(getWifi)(JNIEnv *env,
                                                        jclass cl) {
  return settings->data()->keepWifi;
}
extern "C" JNIEXPORT jboolean JNICALL fromjava(stopWifi)(JNIEnv *env,
                                                         jclass cl) {
  if (settings->data()->keepWifi)
    return false;

  return (time(nullptr) - ((long)lastuptodate[0])) < 2 * 60;
}

extern "C" JNIEXPORT void JNICALL fromjava(resetbackuphost)(JNIEnv *env,
                                                            jclass cl,
                                                            jint pos) {
  backup->resethost(pos);
}
extern void wakeaftermin(const int waitmin);
extern void wakeuploader();

extern "C" JNIEXPORT void JNICALL fromjava(networkpresent)(JNIEnv *env,
                                                           jclass cl) {
  LOGAR("networkpresent");
  if (backup) {
    backup->getupdatedata()->wakesender();
    networkpresent = true;
    backup->notupdatedsettings();
    //    backup->wakebackup();
  } else
    networkpresent = true;

  wakeuploader();
#if !defined(WEAROS) && !defined(TESTMENU)
  wakeaftermin(0);
#endif
  LOGAR("end networkpresend");
}
void resetnetwork() {
  LOGSTRING("resetnetwork\n");
  if (backup) {
    extern void recreateAgents();
    recreateAgents();
    backup->closeallsocks();
    backup->getupdatedata()->wakesender();
    networkpresent = true;
    backup->notupdatedsettings();
  }
}
extern "C" JNIEXPORT void JNICALL fromjava(resetnetwork)(JNIEnv *env,
                                                         jclass cl) {
  resetnetwork();
}

extern "C" JNIEXPORT void JNICALL fromjava(networkabsent)(JNIEnv *env,
                                                          jclass cl) {
  LOGSTRING("networkabsent\n");
  resetnetwork();
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(getICEside)(JNIEnv *env,
                                                           jclass cl,
                                                           jint pos) {
  if (!backup || pos < 0 || pos >= backup->gethostnr())
    return false;
  const passhost_t &host = backup->getupdatedata()->allhosts[pos];
  return host.side;
}

extern "C" JNIEXPORT jstring JNICALL fromjava(getICElabel)(JNIEnv *env,
                                                           jclass cl,
                                                           jint pos) {
  if (!backup || pos < 0 || pos >= backup->gethostnr())
    return nullptr;
  const passhost_t &host = backup->getupdatedata()->allhosts[pos];
  if (!host.ICE)
    return nullptr;
  return myNewStringUTF(env, host.getICEname());
}

extern int makeICEBackupSender();
extern "C" JNIEXPORT jint JNICALL fromjava(makeICESender)(JNIEnv *env,
                                                          jclass cl) {
  return makeICEBackupSender();
}

extern int makeICEBackupReceiver();
extern "C" JNIEXPORT jint JNICALL fromjava(makeICEReceiver)(JNIEnv *env,
                                                            jclass cl) {
  return makeICEBackupReceiver();
}

extern int makeHomeBackupSender();
extern "C" JNIEXPORT jint JNICALL fromjava(makeHomeSender)(JNIEnv *env,
                                                           jclass cl) {
// This calls makeHomeBackupSender which seems to exist in backup.hpp
#if !defined(WEAROS) && __NDK_MAJOR__ >= 26
  return makeHomeBackupSender();
#else
  return -1;
#endif
}

extern int makeHomeBackupReceiver();
extern "C" JNIEXPORT jint JNICALL fromjava(makeHomeReceiver)(JNIEnv *env,
                                                             jclass cl) {
  return makeHomeBackupReceiver();
}
/*    networkpresent=false;
      if(backup) {
          backup->closeallsocks();
          } */

extern "C" JNIEXPORT void JNICALL fromjava(wakestreamsender)(JNIEnv *env,
                                                             jclass cl) {
  if (backup) {
    backup->getupdatedata()->wakestreamsender();
  }
}
extern "C" JNIEXPORT void JNICALL fromjava(wakestreamhereonly)(JNIEnv *env,
                                                               jclass cl) {
  if (backup) {
    backup->wakebackup(Backup::wakestream);
  }
}
/*
extern "C" JNIEXPORT void JNICALL   fromjava(wakeallsender)(JNIEnv *env, jclass
cl) { if(backup) { backup->getupdatedata()->wakesender();
    }
} */
extern "C" JNIEXPORT void JNICALL fromjava(wakebackup)(JNIEnv *env, jclass cl) {
  if (backup) {
    backup->getupdatedata()->wakesender();
    backup->wakebackup();
  }
}
extern "C" JNIEXPORT void JNICALL fromjava(wakehereonly)(JNIEnv *env,
                                                         jclass cl) {
  if (backup) {
    backup->wakebackup();
  }
}

extern "C" JNIEXPORT jboolean JNICALL
fromjava(getHostDeactivated)(JNIEnv *envin, jclass cl, jint pos) {
  if (pos < backup->getupdatedata()->hostnr) {
    return backup->getupdatedata()->allhosts[pos].deactivated;
  }
  return true;
}
extern "C" JNIEXPORT void JNICALL fromjava(setHostDeactivated)(JNIEnv *envin,
                                                               jclass cl,
                                                               jint pos,
                                                               jboolean val) {
  backup->deactivateHost(pos, val);
}

#if !defined(WEAROS) && __NDK_MAJOR__ >= 26
extern std::string mkbackjson(int pos);
extern "C" JNIEXPORT jstring JNICALL fromjava(getbackJson)(JNIEnv *envin,
                                                           jclass cl,
                                                           jint pos) {
  auto jsonstr = mkbackjson(pos);
  char *data = jsonstr.data();
  data[jsonstr.size()] = '\0';
  return myNewStringUTF(envin, jsonstr.data());
}
extern int makeHomeBackupSender();
extern "C" JNIEXPORT jint JNICALL fromjava(makeHomeCopy)(JNIEnv *envin,
                                                         jclass cl) {
  return makeHomeBackupSender();
}
#endif

// TurnServer Implementation
static updatedata::turnserver_t *getTurnServerSlot(bool create = false) {
  if (!backup) {
    return nullptr;
  }
  auto *data = backup->getupdatedata();
  if (!create && data->NRturnserver == 0) {
    return nullptr;
  }
  auto *slot = &data->turnserver[0];
  if (create && data->NRturnserver == 0) {
    if (!slot->port) {
      slot->port = 3478;
    }
    data->NRturnserver = 1;
  }
  return slot;
}

static void syncTurnServerPresence() {
  if (!backup) {
    return;
  }
  auto *data = backup->getupdatedata();
  auto &slot = data->turnserver[0];
  if (slot.hostname[0]) {
    data->NRturnserver = 1;
    if (!slot.port) {
      slot.port = 3478;
    }
  } else {
    data->NRturnserver = 0;
  }
}

static void copyTurnString(JNIEnv *env, jstring val, char *dest, int maxlen) {
  if (!dest || maxlen <= 0) {
    return;
  }
  if (val == nullptr) {
    dest[0] = '\0';
    return;
  }
  const char *str = env->GetStringUTFChars(val, nullptr);
  if (!str) {
    dest[0] = '\0';
    return;
  }
  strncpy(dest, str, maxlen - 1);
  dest[maxlen - 1] = '\0';
  env->ReleaseStringUTFChars(val, str);
}

extern "C" JNIEXPORT jint JNICALL fromjava(TurnServerNR)(JNIEnv *env,
                                                         jclass cl) {
  return backup ? backup->getupdatedata()->NRturnserver : 0;
}

extern "C" JNIEXPORT jstring JNICALL fromjava(getTurnHost)(JNIEnv *env,
                                                            jclass cl,
                                                            jint pos) {
  if (pos == 0) {
    if (const auto *slot = getTurnServerSlot(false)) {
      return myNewStringUTF(env, slot->hostname);
    }
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL fromjava(setTurnHost)(JNIEnv *env, jclass cl,
                                                         jint pos, jstring val) {
  if (pos == 0) {
    if (auto *slot = getTurnServerSlot(true)) {
      copyTurnString(env, val, slot->hostname,
                     updatedata::turnserver_t::maxhostname);
      syncTurnServerPresence();
    }
  }
}

extern "C" JNIEXPORT jint JNICALL fromjava(getTurnPort)(JNIEnv *env, jclass cl,
                                                         jint pos) {
  if (pos == 0) {
    if (const auto *slot = getTurnServerSlot(false)) {
      return slot->port;
    }
  }
  return 3478;
}

extern "C" JNIEXPORT void JNICALL fromjava(setTurnPort)(JNIEnv *env, jclass cl,
                                                         jint pos, jint port) {
  if (pos == 0) {
    if (auto *slot = getTurnServerSlot(true)) {
      slot->port = port > 0 ? port : 3478;
      syncTurnServerPresence();
    }
  }
}

extern "C" JNIEXPORT jstring JNICALL fromjava(getTurnUser)(JNIEnv *env,
                                                            jclass cl,
                                                            jint pos) {
  if (pos == 0) {
    if (const auto *slot = getTurnServerSlot(false)) {
      return myNewStringUTF(env, slot->username);
    }
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL fromjava(setTurnUser)(JNIEnv *env, jclass cl,
                                                         jint pos, jstring val) {
  if (pos == 0) {
    if (auto *slot = getTurnServerSlot(true)) {
      copyTurnString(env, val, slot->username,
                     updatedata::turnserver_t::maxusername);
      syncTurnServerPresence();
    }
  }
}

extern "C" JNIEXPORT jstring JNICALL fromjava(getTurnPassword)(JNIEnv *env,
                                                                jclass cl,
                                                                jint pos) {
  if (pos == 0) {
    if (const auto *slot = getTurnServerSlot(false)) {
      return myNewStringUTF(env, slot->password);
    }
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL fromjava(setTurnPassword)(JNIEnv *env,
                                                             jclass cl, jint pos,
                                                             jstring val) {
  if (pos == 0) {
    if (auto *slot = getTurnServerSlot(true)) {
      copyTurnString(env, val, slot->password,
                     updatedata::turnserver_t::maxpassword);
      syncTurnServerPresence();
    }
  }
}

extern "C" JNIEXPORT void JNICALL fromjava(deleteTurnServer)(JNIEnv *env,
                                                              jclass cl,
                                                              jint pos) {
  if (pos == 0) {
    if (auto *slot = getTurnServerSlot(false)) {
      slot->clear();
      backup->getupdatedata()->NRturnserver = 0;
    }
  }
}
