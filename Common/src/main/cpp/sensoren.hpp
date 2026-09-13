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
/*      Fri Jan 27 12:35:09 CET 2023 */

#ifndef SENSOREN_H
#define SENSOREN_H
#include "SensorGlucoseData.hpp"
#include "inout.hpp"
#include "settings/settings.hpp"
#include <algorithm>
#include <iostream>
#include <limits.h>
#include <string.h>
#include <time.h>
#include <vector>
using namespace std;
// char sensorid[]="E007-0M0063KNUJ0";
// #define sensorid "E007-0M0063KNUJ0"xxxxxx
inline constexpr const int sensornamelen = 16;
inline constexpr const uint8_t sensorFinished = 1;
inline constexpr const uint8_t sensorRemovedByUser = 2;
#include "gltype.hpp"
class SensorGlucoseData;
inline constexpr const uint16_t LISTEND = 0xFFFF;
#ifdef SIBIONICS
static constexpr const std::string_view sibionicsRecognition = "0697283164";
#endif
struct sensor {
  uint32_t starttime;
  uint32_t endtime;
  char name[sensornamelen + 1];
  uint8_t present;
  uint8_t finished : 7;
  bool initialized : 1;
  uint8_t halfdays;
  //   uint8_t reserved[4];
  uint16_t next;
  uint16_t prev;
  bool wasRemovedByUser() const { return finished == sensorRemovedByUser; }
  void markFinished() {
    if (!wasRemovedByUser())
      finished = sensorFinished;
  }
  void markRemovedByUser() { finished = sensorRemovedByUser; }
  bool reactivateFromData() {
    if (wasRemovedByUser())
      return false;
    const bool changed = finished != 0;
    finished = 0;
    return changed;
  }
  void reactivateExplicitly() { finished = 0; }
  size_t fulllen() const { return strnlen(name, sensornamelen); }
  std::string_view fullname() const { return {name, fulllen()}; }
  const char *shortsensorname_chars() const {
    if (name[0] == 'X' && name[1] == '-')
      return name;
    return name + 5;
  }
  std::string_view shortsensorname_view() const {
    if (name[0] == 'X' && name[1] == '-')
      return fullname();
    const size_t len = fulllen();
    if (len <= 5)
      return {};
    return {name + 5, len - 5};
  }
  const sensorname_t *shortsensorname() const {
    return reinterpret_cast<const sensorname_t *>(shortsensorname_chars());
  }
  const char *showsensorname() const {
    const char *sname = shortsensorname_chars();
    if (!memcmp(sname, "XX", 2))
      return sname + 2;
    return sname;
  }
  uint32_t wearduration() const {
    if (halfdays >= (stdMaxDaysSI * 2))
      return maxSIhours * 60 * 60;
    return (halfdays ? halfdays : 29) * 12 * 60 * 60;
  }
  uint32_t maxtime() const { return starttime + wearduration(); }
  uint32_t officialendTime() const { return starttime + wearduration(); }
  uint32_t largemaxtime() const { return maxtime() + 60 * 60 * 24; }
} __attribute__((packed)) __attribute__((aligned(4))); /*always 32 bytes */

extern void sendstartsensors(int startpos);
extern void sendKAuth(SensorGlucoseData *hist);
extern void sendsiScan(SensorGlucoseData *hist);
extern void setstreaming(SensorGlucoseData *hist);
class Sensoren {
  string inbasedir;
  pathconcat mapfile;
  typedef Mmap<struct sensor> MapType;
  MapType map;
  struct infoblock {
    int32_t last, current, markold, start, version;
  };
  // infoblock *infoblockptr();
  // sensor *sensorlist();
  int maxhist;
  SensorGlucoseData **hist;
  int error = 0;

public:
  const string &getbasedir() const { return inbasedir; };

  int geterror() const { return error; }

  int32_t capacity() const {
    return map.count() - 1;
    //   return infoblockptr()->capacity;
  }

  sensor *sensorlist() { return map.data() + 1; }

  const sensor *sensorlist() const { return map.data() + 1; }

  int32_t last() const {
    auto l = reinterpret_cast<const infoblock *>(map.data())->last;
    /*
    for(;l>=0&&!sensorlist()[l].starttime; --l)  {
       LOGGER("last() %d starttime==0\n",l);
       } */
    return l;
  }

  infoblock *infoblockptr() {
    return reinterpret_cast<infoblock *>(map.data());
  }
  const infoblock *infoblockptr() const {
    return reinterpret_cast<const infoblock *>(map.data());
  }

  Sensoren(string_view basedirin)
      : inbasedir(basedirin), mapfile{inbasedir, "sensors.dat"},
        map(mapfile, 1024), maxhist(map.data() ? (last() + 3) : 100),
        hist(new SensorGlucoseData *[maxhist]()) {
    LOGGER("maxhist=%d\n", maxhist);
    trimTrailingEmptySensorSlots("open");
    setindices();
  }
  void setlibre3nums() {
#ifdef LIBRENUMBERS
    const SensorGlucoseData *sens = getSensorData();
    extern void setlibrenum3(bool);
    setlibrenum3(sens != nullptr && sens->isLibre3());
#endif
  }
  void setversions() {
    bool libre3;
    bool libre2;
    if (settings->data()->sendnumbers) {
      setlibre3nums();
      libre3 = settings->data()->libre3nums;
      libre2 = !libre3;
    } else {
      libre2 = libre3 = false;
    }

    uint32_t starttime = settings->data()->startlibretime;
    uint32_t sendfrom = starttime ? starttime : (time(nullptr) - librekeepsecs);
#ifndef NOLOG
    {
      time_t showtime = sendfrom;
      LOGGER("settings->data()->libre3nums=%d %u %s\n", libre3, sendfrom,
             ctime(&showtime));
    }
#endif
    int lastsens = last();
    for (int i = settings->data()->startlibreview; i <= lastsens; i++) {
      const SensorGlucoseData *sens = getSensorData(i);
      if (sens && sens->isLibre2()) {
        settings->data()->startlibreview = i;
        if (!sens->getinfo()->libreviewsendall &&
            sens->getmaxtime() > sendfrom) {
          libre2 = true;
          break;
        }
      }
    }
    LOGGER("settings->data()->startlibre3view=%d "
           "settings->data()->startlibreview=%d\n",
           settings->data()->startlibre3view, settings->data()->startlibreview);
    for (int i = settings->data()->startlibre3view; i <= lastsens; i++) {
      const SensorGlucoseData *sens = getSensorData(i);
      if (!sens) {
        LOGGER("ERROR getSensorData(i)==NULL\n", i);
        continue;
      }
      if (sens->isLibre3()) {
        settings->data()->startlibre3view = i;
#ifndef NOLOG
        time_t tim = sens->getmaxtime();
        LOGGER("libre3 %d sendnall=%d starttime=%s", i,
               sens->getinfo()->libreviewsendall, ctime(&tim));
#endif
        if (!sens->getinfo()->libreviewsendall &&
            sens->getmaxtime() > sendfrom) {
          libre3 = true;
          break;
        }
      }
    }
    settings->data()->haslibre2 = libre2;
    settings->data()->haslibre3 = libre3;
    LOGGER("haslibre2=%d, haslibre3=%d\n", libre2, libre3);
  }
  static bool create(string_view inbasedir) {
    pathconcat file{inbasedir, "sensors.dat"};
    const int pagesize = SensorGlucoseData::blocksize;
    {
      struct stat st;
      if (!stat(file, &st) && ((st.st_mode & S_IFMT) == S_IFREG) &&
          st.st_size >= pagesize)
        return true;
    }

    mkdir(inbasedir.data(), 0700);
    //   constexpr int cap=2;

    const int cap = pagesize / sizeof(struct sensor);
    LOGGER("map(%s,%d) getpagesize=%d\n", file.data(), cap, pagesize);
    MapType map(file, cap);
    auto dat = reinterpret_cast<infoblock *>(map.data());
    if (!dat)
      return false;
    constexpr int starthist = 5;
    if (dat->markold < starthist)
      dat[0] = {-1, -1, starthist, 0};
    LOGSTRING("Sensoren::create success\n");
    return true;
  }

  uint32_t timefirstdata() {
    static uint32_t first = UINT32_MAX;
    if (last() >= 0) {
      int i = getstartindex();
      for (int it = 0; it <= 3; it++) {
        const sensor *sens = getsensor(i);
        if (sens->next == 0 && sens->prev == 0)
          setindices();
        if (sens->present) {
          if (const SensorGlucoseData *hist = getSensorData(i)) {
            auto tim = hist->getfirsttime();
            if (tim < first) {
              first = tim;
            }
          }
        }
        i = sens->next;
        if (i == LISTEND)
          break;
      }
    }
    if (first == UINT32_MAX)
      return time(nullptr) - 24 * 60 * 60;
    return first;
  }

  uint32_t timelastdata() {
    //      auto nr = last();
    int nr = getendindex();
    if (nr >= 0) {
      if (const SensorGlucoseData *his = getSensorData(nr))
        return his->lastused();
    }
    return time(NULL);
  }

  void extend(int newcap) {
    map.extend(mapfile, 1 + newcap);
    //   infoblockptr()=reinterpret_cast<infoblock*>(map.data());
    //   sensorlist()=map.data()+1;
  }

private:
  bool validSensorIndex(const int ind) const {
    return ind >= 0 && ind <= infoblockptr()->last;
  }

  bool populatedSensorSlot(const int ind) const {
    return validSensorIndex(ind) && sensorlist()[ind].name[0] != '\0';
  }

  bool emptyClearedSensorSlot(const int ind) const {
    if (!validSensorIndex(ind))
      return false;
    const sensor &sens = sensorlist()[ind];
    return sens.name[0] == '\0' && sens.starttime == 0 && sens.endtime == 0 &&
           sens.present == 0;
  }

  bool trimTrailingEmptySensorSlots(const char *reason) {
    int32_t &lastref = infoblockptr()->last;
    const int32_t oldlast = lastref;
    while (lastref >= 0 && emptyClearedSensorSlot(lastref)) {
      if (lastref < maxhist && hist[lastref]) {
        auto *old = hist[lastref];
        hist[lastref] = nullptr;
        delete old;
      }
      --lastref;
    }
    if (oldlast == lastref)
      return false;

    LOGGER("Self-heal: trimmed sensors.dat last %d -> %d (%s)\n",
           oldlast, lastref, reason ? reason : "");
    int32_t &current = infoblockptr()->current;
    if (current > lastref || !populatedSensorSlot(current))
      current = -1;
    return true;
  }

  void setmaxhistory(int max) {
    LOGGER("setmaxhistory(%d)\n", max);
    SensorGlucoseData **tmphist = new SensorGlucoseData *[max]();
    memcpy(tmphist, hist, sizeof(SensorGlucoseData *) * maxhist);
    SensorGlucoseData **oldhist = hist;
    hist = tmphist;
    delete[] oldhist;
    maxhist = max;
  }

  int getmaxhistory() const { return maxhist; }

  void removeunused() {
    if (const int l = last(); l >= 0 && l < maxhist) {
      SensorGlucoseData *hist = getSensorData(l);
      if (hist && hist->unused()) {
        if (removelinks(infoblockptr()->last--))
          setindices();
        sendstartsensors(l);
        delete hist;
      }
    }
  }

public:
  void deletelast() {
    if (const int l = last(); l >= 0 && l < maxhist) {
      auto *old = hist[l];
      LOGGER("maxhist=%d deletelast %d %p\n", maxhist, l, old);
      if (old && old->unused()) {
        LOGGER("deletelast before delete old (%p)\n", old);
        //           delete   old;
        std::destroy_at(old);
        LOGAR("after destroy_at(old)");
        operator delete(old);
        LOGAR("deletelast after delete old");
        hist[l] = nullptr;
      }
    }
  }
  int addsensor(const span<const char> name) {
    return addsensor(std::string_view(name.data(), name.size()));
  }
  int addsensor(const string_view name, size_t minimumPollRecords = 0) {
    LOGGER("addsensor(%.16s)\n", name.data());
    removeunused();
    int endindex = getendindex();
    infoblockptr()->last++;
    sensorlistmap();
    if (last() >= getmaxhistory()) {
      setmaxhistory(last() * 2);
    }
    const int32_t lastpos = infoblockptr()->last;
    pathconcat sdir(inbasedir, name);
    mkdir(sdir.data(), 0700);
    SensorGlucoseData::initInfoFile(pathconcat(sdir, "info.dat"), 14, 3);
    SensorGlucoseData *histel =
        minimumPollRecords > 0
            ? new SensorGlucoseData(
                  sdir, lastpos,
                  SensorGlucoseData::DirectStreamCapacity{
                      minimumPollRecords})
            : new SensorGlucoseData(sdir, lastpos);
    if (!histel->error()) {
      std::string_view svname(name);
      if (svname.length() >= 2 && svname.substr(0, 2) == "X-") {
        auto info = histel->getinfo();
        if (info) {
          info->aidex = 1;
        }
      }
    }
    hist[lastpos] = histel;
    LOGGER("hist[%d]=%p\n", lastpos, histel);
    sensorlist()[lastpos].starttime = histel->getstarttime();
    auto &sensorname = sensorlist()[lastpos].name;
    memset(sensorname, 0, sizeof(sensorname));
    const size_t copylen = min(name.length(), static_cast<size_t>(sensornamelen));
    memcpy(sensorname, name.data(), copylen);
    sensorname[copylen] = '\0';
    sensorlist()[lastpos].present = 1;
    sensorlist()[lastpos].endtime = 0;
    sensorlist()[lastpos].finished = 0;
    sensorlist()[lastpos].initialized = false;
      // Multi-sensor fix: Only auto-set the new sensor as main if there is no current
      // main sensor. This prevents silently overriding the user's selection when a
      // second sensor is added (e.g., adding Sibionics while AiDex is already main).
      if (infoblockptr()->current < 0) {
          infoblockptr()->current = lastpos;
      }
      makelinks(lastpos, endindex);
#ifndef NOLOG
    time_t tim = sensorlist()[lastpos].starttime;
    LOGGER("add sensor %.16s starttime=%d %s", name.data(), tim, ctime(&tim));
#endif
    return lastpos;
  }
  SensorGlucoseData *ensureDirectStreamShell(
      const string_view name, uint32_t starttime = 0,
      size_t minimumPollRecords = 0) {
    if (name.empty()) {
      return nullptr;
    }
    int ind = sensorindex(name);
    if (ind < 0) {
      ind = sensorindexshort(name);
    }
    if (ind < 0) {
      ind = addsensor(name, minimumPollRecords);
    }
    if (ind < 0) {
      return nullptr;
    }
    sensorlistmap();
    const auto fullname = sensorlist()[ind].fullname();
    if (fullname.empty()) {
      return nullptr;
    }
    pathconcat sdir(inbasedir, fullname);
    mkdir(sdir.data(), 0700);
    SensorGlucoseData::initInfoFile(pathconcat(sdir, "info.dat"), 14, 3);
    if (hist[ind] && hist[ind]->error()) {
      auto *broken = hist[ind];
      hist[ind] = nullptr;
      delete broken;
    }
    if (!hist[ind]) {
      hist[ind] =
          minimumPollRecords > 0
              ? new SensorGlucoseData(
                    sdir, ind,
                    SensorGlucoseData::DirectStreamCapacity{
                        minimumPollRecords})
              : new SensorGlucoseData(sdir, ind);
      LOGGER("repair hist[%d]=%p\n", ind, hist[ind]);
    }
    if (!hist[ind] || hist[ind]->error()) {
      if (hist[ind]) {
        auto *broken = hist[ind];
        hist[ind] = nullptr;
        delete broken;
      }
      return nullptr;
    }
    if (minimumPollRecords > 0 &&
        hist[ind]->pollStorageCapacity() < minimumPollRecords) {
      LOGGER("ensureDirectStreamShell %.16s capacity=%zu requested=%zu; "
             "active mapping unchanged\n",
             name.data(), hist[ind]->pollStorageCapacity(),
             minimumPollRecords);
      return nullptr;
    }
    auto *info = hist[ind]->getinfo();
    if (starttime > 0) {
      if (info && (info->starttime == 0 || starttime < info->starttime)) {
        info->starttime = starttime;
      }
      if (sensorlist()[ind].starttime == 0 || starttime < sensorlist()[ind].starttime) {
        sensorlist()[ind].starttime = starttime;
      }
    } else if (sensorlist()[ind].starttime == 0) {
      sensorlist()[ind].starttime = hist[ind]->getstarttime();
    }
    sensorlist()[ind].present = 1;
    sensorlist()[ind].reactivateFromData();
    return hist[ind];
  }
  const sensor *getsensor(const int ind) const { return sensorlist() + ind; }

  sensor *getsensor(const int ind) { return sensorlist() + ind; }
  bool isSibionics(const int ind) const {
    const sensor *sens = getsensor(ind);
    return sens->halfdays >= (stdMaxDaysSI * 2);
  }

  bool needsScan(const int ind) const {
    const sensor *sens = getsensor(ind);
    const auto res = sens->halfdays >= (stdMaxDaysSI * 2) ||
                     sens->halfdays == (maxdaysDex * 2);
    LOGGER("needsScan(%d,%s)=%d\n", ind, sens->name, res);
    return res;
  }

  static SensorGlucoseData::longsensorname_t
  namelibre3(const std::string_view sensorid) {
    SensorGlucoseData::longsensorname_t sens;
    constexpr const char start[] = "E07A-XXXX";
    const int len = sensorid.length();
    const int startlen = sens.size() - len;
    memcpy(&sens[0], start, startlen);
    memcpy(&sens[0] + startlen, sensorid.data(), len);
    return sens;
  }
#ifdef LIBRE3
  SensorGlucoseData *makelibre3sensor(std::string_view shortname,
                                      uint32_t starttime, uint32_t now) {
    return makelibre3sensor(shortname, starttime, 0, nullptr, now);
  }
  int makelibre3sensorindex(std::string_view shortname, uint32_t starttime,
                            const uint32_t pin, const char *deviceaddress,
                            uint32_t now, uint16_t warmup,
                            uint16_t wearduration) {
    const auto name = namelibre3(shortname);

#ifndef NOLOG
    time_t tim = starttime;
    LOGGER("makelibre3sensor(%s,%u) %s", shortname.data(), starttime,
           ctime(&tim));
#endif

    // longsensorname_t is exactly 16 bytes and has no trailing NUL. Keep the
    // lookup bounded so rescanning a known sensor reuses its existing slot.
    if (sensor *sensgegs =
            findsensorm(std::string_view(name.data(), name.size()))) {
      LOGGER("known sensor %s\n", sensgegs->showsensorname());
      const int sensindex = sensgegs - sensorlist();
      SensorGlucoseData *sens = getSensorData(sensindex);
      if (pin) {
        sens->getinfo()->pin = pin;
      }
      if (deviceaddress) {
        char *address = sens->deviceaddress();
        if (!*address)
          strcpy(address, deviceaddress);
      }
      sens->getinfo()->haskAuth = false;
      sendKAuth(sens);
      sensgegs->finished = 0;

      sens->getinfo()->lastscantime = now;
      //      int sensorindex=sensgegs - sensorlist();

      void resensordata(int sensorindex);
      resensordata(sensindex);
      return sensindex;
    }
    const pathconcat sensordir(inbasedir, name);
    SensorGlucoseData::mkdatabase3(sensordir, starttime, pin, deviceaddress,
                                   warmup, wearduration);
    const int ind = addsensor(std::string_view(name.data(), name.size()));
    sensor *sen = getsensor(ind);
    sen->halfdays = 2 * wearduration / (24 * 60);
    sen->initialized = true;
    return ind;
  }
#endif
// 0106972831641803112412191725121810LT4F241247J21241247YEZ1450HAJ02 EU
// sibionics 0106972831641117112406121725061110LT48240601R21240601YL08230BFY73
// Hematoxic 0106972831641476112412231725122210LT46241219C21WD9QAXGA52WS4V
// len=63 Sibionics light
// 0106972831641476112504081726040710LT46250316C21P2250316015APD66 len=65
// Sibionics 2
//^]0106972831640165112312091724120810LT41231108C^]21231108GEPD802JPP76
// SIBIONICS  name 31108GEPD802JPP7
//^]0106972831641483112411201726051910LT46241155C^]21P22411J6EP  SIBIONICS2
// transmitter 1155CJ6EP2411
#ifdef SIBIONICS

  static auto namefromSIgegs(const char *gegs, const int len, bool hasnum) {
    if (len < 65) {
      std::string uit;
      uit.reserve(16);
      const int endlen = len - 49;
      const int startlen = 16 - endlen;
      uit.append(gegs + 22, startlen);
      uit.append(gegs + 49, endlen);
      return uit;
    } else {
      const char *start = gegs + len - (hasnum ? 17 : 16);
      return std::string(start, start + 16);
    }
  }
#endif
#ifdef DEXCOM
  std::pair<int, SensorGlucoseData *>
  makeDexComSensorindex(const char *pin, std::string_view gegs, uint32_t now) {
    std::array<char, 16> name;
    if (gegs.size() == 55) {
      std::copy_n(&gegs[19], 12, name.data());
    } else {
      if (gegs.size() >= 15) {
        std::copy_n(gegs.end() - 15, 7, name.data());
        std::copy_n(&gegs[5], 5, name.data() + 7);
      } else {
        const char *end = gegs.end();
        int uitit = 0;
        for (const char *iter = gegs.data(); uitit < 11; ++iter) {
          if (iter == end) {
            return {-1, nullptr};
          }
          if (isprint(*iter)) {
            name[uitit++] = *iter;
          }
        }
      }
    }
    std::copy_n(pin, 4, name.data() + 12);
    LOGGER("makeDexComSensorindex %s name=%.16s\n", pin, name.data());
    removeunused();
    if (sensor *sensgegs =
            findsensorm(std::string_view(name.data(), name.size()))) {
      LOGGER("known sensor %s\n", sensgegs->showsensorname());
      const int sensindex = sensgegs - sensorlist();
      SensorGlucoseData *sens = getSensorData(sensindex);
      sendKAuth(sens);
      sendsiScan(sens);
      setstreaming(sens);
      sensgegs->finished = 0;
      auto *info = sens->getinfo();
      info->lastscantime = now;
      if (!info->pollcount)
        info->starttime = now; // Not needed
      void resensordata(int sensorindex);
      resensordata(sensindex);
      return {sensindex, sens};
    }
    const pathconcat sensordir(inbasedir, name);
    SensorGlucoseData::mkdatabaseDex(sensordir, gegs, now);
    const int ind = addsensor(name);
    sensor *sen = getsensor(ind);
    sen->initialized = true;
    sen->halfdays = maxdaysDex * 2;
    return {ind, getSensorData(ind)};
  }

private:
  static bool dexcomEnd(const char *endcode) {
    if (!memcmp(endcode - 7, "240", 3)) {
      const char *pin = endcode - 4;
      for (auto *iter = pin; iter < endcode; ++iter) {
        if (!isdigit(*iter))
          return false;
      }
      return true;
    }
    return false;
  }
#endif

  // Scanned: 01040156300880101125031317260203211R000162641
  // (01)04015630088010 11 250313 17 260203(21)1R000162641
  struct accuScan {
    char controlHaak;
    char s01[2];
    char num[14];
    char tus1[2];
    char proYear[2];
    char proMonth[2];
    char proDay[2];
    char tus2[2];
    char exYear[2];
    char exMonth[2];
    char exDay[2];
    char s21[2];
    char name[11];
  };
  std::pair<int, SensorGlucoseData *>
  makeAccuCheckSensorindex(std::string_view scanned, uint32_t now) {
    if (scanned.size() != 46) {
      return {-1, nullptr};
    }
    std::string_view name = scanned.substr(scanned.size() - 16, 16);
#ifndef NOLOG
    const accuScan *accu = reinterpret_cast<const accuScan *>(scanned.data());
    LOGGER("AccuChek %.11s %.16s expiration year %.2s month %.2s day %.2s\n",
           accu->name, name.data(), accu->exYear, accu->exMonth, accu->exDay);
#endif
    if (sensor *sensgegs = findsensorm(name)) {
      LOGGER("known sensor %s\n", sensgegs->showsensorname());
      const int sensindex = sensgegs - sensorlist();
      SensorGlucoseData *sens = getSensorData(sensindex);
      sensgegs->finished = 0;
      auto *info = sens->getinfo();
      info->accuChek = true;
      sendsiScan(sens);

      info->lastscantime = now;
      if (!info->pollcount)
        info->starttime = now; // Not needed
      void resensordata(int sensorindex);
      resensordata(sensindex);
      return {sensindex, sens};
    }
    const pathconcat sensordir(inbasedir, name);
    SensorGlucoseData::mkdatabaseAccu(sensordir, scanned, now);
    const int ind = addsensor(name);
    sensor *sen = getsensor(ind);
    sen->initialized = true;
    sen->halfdays = maxdaysAccu * 2;
    return {ind, getSensorData(ind)};
  }

public:
#ifdef SIBIONICS
  std::pair<int, SensorGlucoseData *> makeSIsensorindex(std::string_view gegsSI,
                                                        uint32_t now) {

#ifndef NOLOG
    LOGGER("makeSIsensorindex(%s) len=%d\n", gegsSI.data(), gegsSI.size());
#endif
    bool hasnum = std::ranges::contains_subrange(gegsSI, sibionicsRecognition);
    const auto *endcode = gegsSI.end();
    /*   bool
     * hasnum=std::search(gegsSI.begin(),endcode,sibionicsRecognition.begin(),sibionicsRecognition.end())!=endcode;
     */
    if (!hasnum) {
      std::string_view si = "(SI)";
      if (gegsSI.size() < 36 || !std::ranges::contains_subrange(gegsSI, si)) {
        if (dexcomEnd(endcode)) {
          if (const auto res = makeDexComSensorindex(endcode - 4, gegsSI, now);
              res.first >= 0)
            return res;
        }
        return makeAccuCheckSensorindex(gegsSI, now);
      }
    }
    if (gegsSI.size() == 59) {
      LOGAR("Transmitter tag");
      return {-1, nullptr};
    }
    const auto name = namefromSIgegs(gegsSI.data(), gegsSI.size(), hasnum);

    removeunused();
    if (sensor *sensgegs = findsensorm(name)) {
      LOGGER("known sensor %s\n", sensgegs->showsensorname());
      const int sensindex = sensgegs - sensorlist();
      SensorGlucoseData *sens = getSensorData(sensindex);
      sendKAuth(sens);
      setstreaming(sens);
      sensgegs->finished = 0;
      auto *info = sens->getinfo();
      info->lastscantime = now;
      if (!info->pollcount)
        info->starttime = now; // Not needed
      void resensordata(int sensorindex);
      resensordata(sensindex);
      return {sensindex, sens};
    }
    const pathconcat sensordir(inbasedir, name);
    SensorGlucoseData::mkdatabaseSI(sensordir, gegsSI, now, hasnum);
    const int ind = addsensor(static_cast<std::string_view>(name));
    sensor *sen = getsensor(ind);
    sen->initialized = true;
    sen->halfdays = maxdaysSI * 2;
    return {ind, getSensorData(ind)};
  }
#endif
#ifdef LIBRE3
  SensorGlucoseData *makelibre3sensor(std::string_view shortname,
                                      uint32_t starttime, const uint32_t pin,
                                      const char *deviceaddress,
                                      const uint32_t now) {
    int sensindex = makelibre3sensorindex(shortname, starttime, pin,
                                          deviceaddress, now, 60, 14 * 24 * 60);
    if (sensindex < 0)
      return nullptr;
    return getSensorData(sensindex);
  }
#endif
  const sensor *findsensor(const std::string_view wanted) const {
    const sensor *end = sensorlist() + last() + 1;
    const sensor *sens =
        find_if(sensorlist(), end, [wanted](const sensor &sens) -> bool {
          return sens.fullname() == wanted;
        });
    if (end == sens)
      return nullptr;
    return sens;
  }

  const sensor *findsensor(const char *name) const {
    if (!name)
      return nullptr;
    return findsensor(std::string_view(name));
  }

  const sensor *findsensorshort(const std::string_view wanted) const {
    const sensor *end = sensorlist() + last() + 1;
    const sensor *sens =
        find_if(sensorlist(), end, [wanted](const sensor &sens) -> bool {
          if (sens.fullname() == wanted) {
            return true;
          }
          if (wanted.rfind("X-", 0) == 0) {
            return false;
          }
          return sens.shortsensorname_view() == wanted;
        });
    if (end == sens)
      return nullptr;
    return sens;
  }

  const sensor *findsensorshort(const char *name) const {
    if (!name)
      return nullptr;
    return findsensorshort(std::string_view(name));
  }

  sensor *findsensorm(const char *name) {
    const sensor *sens = findsensor(name);
    return const_cast<sensor *>(sens);
  }

  sensor *findsensorm(const std::string_view name) {
    const sensor *sens = findsensor(name);
    return const_cast<sensor *>(sens);
  }

  vector<int> sensorindices(const std::string_view wanted) const {
    vector<int> out;
    if (wanted.empty())
      return out;
    const bool fullNameOnly = wanted.rfind("X-", 0) == 0;
    for (int index = 0; index <= last(); ++index) {
      const sensor &sens = sensorlist()[index];
      if (sens.fullname() == wanted ||
          (!fullNameOnly && sens.shortsensorname_view() == wanted)) {
        out.push_back(index);
      }
    }
    return out;
  }

  int sensorindex(const char *name) const {
    const sensor *sens = findsensor(name);
    if (!sens)
      return -1;
    return sens - sensorlist();
  }

  int sensorindex(const std::string_view name) const {
    const sensor *sens = findsensor(name);
    if (!sens)
      return -1;
    return sens - sensorlist();
  }

  int sensorindexshort(const char *name) const {
    const sensor *sens = findsensorshort(name);
    if (!sens)
      return -1;
    return sens - sensorlist();
  }

  int sensorindexshort(const std::string_view name) const {
    const sensor *sens = findsensorshort(name);
    if (!sens)
      return -1;
    return sens - sensorlist();
  }

  SensorGlucoseData *getSensorData(const char *name) {
    if (int ind = sensorindex(name); ind >= 0)
      return getSensorData(ind);
    return nullptr;
  }

  SensorGlucoseData *gethistshort(const char *name) {
    if (int ind = sensorindexshort(name); ind >= 0)
      return getSensorData(ind);
    return nullptr;
  }

  void setCurrentSensor(const char *name) {
    if (!name || !*name) {
      infoblockptr()->current = -1;
      return;
    }
    if (int ind = sensorindex(name); ind >= 0) {
      infoblockptr()->current = ind;
    } else {
      if (int ind = sensorindexshort(name); ind >= 0) {
        infoblockptr()->current = ind;
      }
    }
  }

  //   static constexpr const uint32_t sensorageseconds = 15 * 24 * 60 * 60u;
  static constexpr const uint32_t maxageseconds = 24 * 24 * 60 * 60u;
  template <typename F>
  void sensorsInPeriod(vector<int> &out, uint32_t starttime, uint32_t endtime,
                       F dont) {
    const uint32_t nu = time(nullptr);
    constexpr const int maxage = maxSIhours * 60 * 60;
    const uint32_t oldsecs = maxage >= starttime ? 0 : starttime - maxage;
    int endindex = getendindex();
    if (endindex >= 0) {
      for (int prev, i = endindex; i != LISTEND; i = prev) {
        auto &sensor = sensorlist()[i];
        if (sensor.next == 0 && sensor.prev == 0)
          setindices();
        prev = sensor.prev;
        const uint32_t startsensor = sensor.starttime;
        if (startsensor >= endtime) {
          LOGGER("%d: sensorsInPeriod startsensor (%u) >= endtime (%u) \n", i,
                 startsensor, endtime);
          continue;
        }

        if (sensor.maxtime() <= oldsecs) {
          LOGGER("sensorsInPeriod %s old %u\n", showsensorname(i),
                 sensor.maxtime());
          break;
        }

        auto oneend = sensor.endtime;
        if (sensor.finished && oneend && oneend < starttime) {
          LOGGER(
              "%d: sensorsInPeriod finished &&endtime (%u) <starttime (%u)\n",
              i, oneend, starttime);
          continue;
        }
        if (!checkinfo(i, nu, dont)) {
          continue;
        }
        oneend = sensor.endtime;
        if (oneend && oneend < starttime) {
          LOGGER("%d: sensorsInPeriod endtime (%u) <starttime (%u)\n", i,
                 oneend, starttime);
          continue;
        }
        out.push_back(i);
      }
    }
  }

  vector<int> sensorsInPeriod(uint32_t starttime, uint32_t endtime) {
    std::vector<int> outvector;
    sensorsInPeriod(outvector, starttime, endtime,
                    [](const SensorGlucoseData *s) { return false; });
    return outvector;
  }
  vector<int> shownSensorsInPeriod(uint32_t starttime, uint32_t endtime) {
    std::vector<int> outvector;
    sensorsInPeriod(outvector, starttime, endtime,
                    [](const SensorGlucoseData *s) { return s->hide; });
    return outvector;
  }
  bool sensorsInPeriod(int i, uint32_t starttime, uint32_t endtime) {
    auto &sensor = sensorlist()[i];
    const uint32_t startsensor = sensor.starttime;
    if (startsensor >= endtime)
      return false;
    if (sensor.maxtime() <= starttime)
      return false;
    const auto oneend = sensor.endtime;
    if (sensor.finished && oneend && oneend < starttime) {
      return false;
      ;
    }
    const uint32_t nu = time(nullptr);
    checkinfo(i, nu);
    if (sensor.endtime && sensor.endtime < starttime)
      return false;

    return true;
  }

  int firstafter(uint32_t starttime) {
    int started = starttime - 60 * 60;
    int i = getendindex();
    if (i < 0)
      return 0;
    for (int prev; i != LISTEND; i = prev) {
      sensor *s = getsensor(i);
      if (s->next == 0 && s->prev == 0)
        setindices();
      prev = s->prev;
      if (s->starttime > started)
        continue;
      if (SensorGlucoseData *sens = getSensorData(i)) {
        auto firsttime = sens->getfirsttime();
        if (firsttime == UINT32_MAX)
          continue;
        if (firsttime <= starttime)
          return i;
      }
    }
    return 0;
  }
  /*Finds the sensor with a starttime around tim */
  sensor *findwithstarttime(uint32_t tim, int distance = 60 * 60 * 4) {
    sensor *starts = sensorlist();
    sensor *ends = sensorlist() + last() + 1;
    if (sensor *hit = std::find_if(starts, ends,
                                   [tim, distance](sensor &sen) {
                                     return labs((long)sen.starttime -
                                                 (long)tim) < distance;
                                   });
        hit != ends)
      return hit;
    return nullptr;
  }

  /*
  vector<SensorGlucoseData *> sensorsInPeriod(uint32_t starttime,uint32_t
  endtime) { vector<SensorGlucoseData *> out; for(int i=last();i>=0;i--) {
        if(sensorlist()[i].starttime>=endtime)
           continue;
        if(sensorlist()[i].finished&&sensorlist()[i].endtime<=starttime) {
           continue;
           }
        checkinfo(i);
        if(sensorlist()[i].endtime&&sensorlist()[i].endtime<=starttime) {
           if(sensorlist()[i].finished)
              break;
           else
              continue;
           }
        out.push_back(hist[i]);
        }
     return out;
     }
     */
  void sensorlistmap() {
    const auto minsize = last() + 100;
    if (minsize >= capacity())
      extend(minsize * 2);
  }
  const SensorGlucoseData *getSensorData(int ind = -1) const {
    return const_cast<const SensorGlucoseData *>(
        const_cast<Sensoren *>(this)->getSensorData(ind));
  }
  SensorGlucoseData *getSensorData(int ind = -1) {
    if (ind < 0) {
      ind = last();
      if (ind < 0) {
        static time_t nextlog = 0;
        static uint32_t suppressed = 0;
        const time_t now = time(nullptr);
        if (now >= nextlog) {
          if (suppressed) {
            LOGGER("getSensorData last()<0 (suppressed %u repeats)\n", suppressed);
          } else {
            LOGSTRING("getSensorData last()<0\n");
          }
          suppressed = 0;
          nextlog = now + 30;
        } else {
          ++suppressed;
        }
        return nullptr;
      }
    }
    sensorlistmap();
    if (ind >= getmaxhistory())
      setmaxhistory(ind * 2);
    const char *name = sensorlist()[ind].name;
    if (!hist[ind]) {
      if (!name[0]) {
        LOGGER("getSensorData sensorlist()[%d].name[0]==0\n", ind);
        return nullptr;
      }
      LOGGER("getSensorData(%d) %s\n", ind, name);
      hist[ind] =
          new SensorGlucoseData(pathconcat(inbasedir, sensorlist()[ind].fullname()), ind);
      LOGGER("hist[%d]=%p\n", ind, hist[ind]);
    }
    if (hist[ind]) {
      const bool error = hist[ind]->error();
      if (!error) {
        if (hist[ind]->infowrong()) {
          LOGGER("hist[%d] %s: infoblock wrong", ind, name);
          goto INFOWRONGERROR;
        }
      } else {
      INFOWRONGERROR:
        LOGGER("%s: hist[%d]->error()\n", name, ind);
        SensorGlucoseData *tmp = hist[ind];
        hist[ind] = nullptr;
        delete tmp;
        // Self-heal: when the per-sensor directory itself is gone, the slot
        // will never recover on its own. Java-side removal paths (e.g.
        // ICanHealthBleManager.terminateManagedSensor, MQ driver removal)
        // delete the on-disk directory but cannot rewrite sensors.dat, so
        // without this clear the slot's name[] would stay populated forever
        // and every getSensorData(ind) call would re-construct a broken
        // SensorGlucoseData (observed: 528 calls / 294 errors in a single
        // post-removal session). We only purge when the directory is
        // actually missing — transient mmap failures (low memory, etc.)
        // leave it alone so a healthy sensor is never accidentally erased.
        pathconcat sdir(inbasedir, sensorlist()[ind].fullname());
        struct stat dirstat;
        if (stat(sdir.data(), &dirstat) != 0) {
          LOGGER("Self-heal: clearing sensors.dat slot %d (%s) — directory gone\n",
                 ind, name);
          sensorlist()[ind].name[0] = '\0';
          sensorlist()[ind].starttime = 0;
          sensorlist()[ind].endtime = 0;
          sensorlist()[ind].present = 0;
          sensorlist()[ind].finished = 0;
          sensorlist()[ind].initialized = false;
          if (infoblockptr()->current == ind)
            infoblockptr()->current = -1;
          if (trimTrailingEmptySensorSlots("missing directory"))
            setindices();
        }
        return nullptr;
      }
      sensorlist()[ind].present = 1;
      sensorlist()[ind].starttime = hist[ind]->getstarttime();
      return hist[ind];
    }
    LOGSTRING("getSensorData new SensorGlucoseData(...)=NULL\n");
    return nullptr;
  }

  bool hasstream() {
    for (int ind = last(); ind >= 0; --ind) {
      if (auto sens = getSensorData(ind)) {
        if (sens->pollcount() > 0)
          return true;
      }
    }
    return false;
  }
  bool hasscans() {
    for (int ind = last(); ind >= 0; --ind) {
      if (auto sens = getSensorData(ind)) {
        if (sens->scancount() > 0)
          return true;
      }
    }
    return false;
  }
  void finishsensor(int ind) {
    if (ind >= 0 && ind <= last()) {
      sensorlist()[ind].markFinished();
    }
  }
  bool removesensor(int ind) {
    if (ind < 0 || ind > last())
      return false;
    sensorlist()[ind].markRemovedByUser();
    return true;
  }
  void unfinishsensor(int ind) {
    if (ind >= 0 && ind <= last()) {
      sensorlist()[ind].reactivateExplicitly();
    }
  }
  template <typename F> bool checkinfo(const int ind, uint32_t nu, F dont) {

    const SensorGlucoseData *thishist = getSensorData(ind);
    if (!thishist) {
      sensorlist()[ind].present = 0;
      return false;
    } else {
      if (dont(thishist)) {
        return false;
      }

      sensorlist()[ind].present = 1;
      uint32_t maxtime = thishist->getmaxtime();
      if (maxtime < nu) {
        if (thishist->isInResetMode())
          return true;
        // if(((thishist->isAccuChek()&&thishist->pollcount()<4000)||(thishist->isDexcom()&&thishist->pollcount()<maxdexcount))&&(nu-sensorlist()[ind].endtime)<
        // youngsensorsecs)
        if (thishist->hasData(nu)) {
          return true;
        } else if (thishist->isSibionics() && thishist->hasSensorError(nu)) {
          LOGGER("%s expired with recent Sibionics sensor error; keeping active\n",
                 sensorlist()[ind].name);
          return true;
        } else {
          LOGGER("%s finished was %d set to 1\n", sensorlist()[ind].name,
                 sensorlist()[ind].finished);
          sensorlist()[ind].markFinished();
        }
      }
      sensorlist()[ind].endtime = thishist->lastused();
    }
    // "TODO test on presence"
    return true;
  }
  void checkinfo(const int ind, uint32_t nu) {
    checkinfo(ind, nu, [](const SensorGlucoseData *) { return false; });
  }

  void checkall() {
    const uint32_t nu = time(nullptr);
    for (int i = 0; i <= last(); i++)
      checkinfo(i, nu);
  }

  void deletehist() {
    if (hist) {
      int usedmax = std::min(last() + 1, maxhist);
      for (int i = 0; i < usedmax; i++) {
        delete hist[i];
      }
      delete[] hist;
    }
  }

  ~Sensoren() { deletehist(); }

  typedef array<char, 11> sensorname_t;

  const char *shortsensorname_chars(int index) const {
    const sensor *sens = getsensor(index);
    return sens->shortsensorname_chars();
  }
  const sensorname_t *shortsensorname(int index) const {
    return reinterpret_cast<const sensorname_t *>(shortsensorname_chars(index));
  }
  const char *showsensorname(int index) const {
    const sensor *sens = getsensor(index);
    return sens->showsensorname();
  }

  const char *shortsensorname_chars() const {
    if (int l = infoblockptr()->current; populatedSensorSlot(l))
      return shortsensorname_chars(l);
    if (int l = last(); populatedSensorSlot(l))
      return shortsensorname_chars(l);
    return nullptr;
  }
  const sensorname_t *shortsensorname() const {
    // Use current() (the user-selected main sensor) instead of last() (the most
    // recently added) This enables the "Main Sensor Toggle" feature
    if (int l = infoblockptr()->current; populatedSensorSlot(l))
      return shortsensorname(l);
    // Fallback to last() if current is invalid (though expected to be synced)
    if (int l = last(); populatedSensorSlot(l))
      return shortsensorname(l);
    return nullptr;
  }

  const uint32_t laststarttime() const {
    if (int l = last(); l >= 0) {
      return sensorlist()[l].starttime;
    }
    return 0;
  }

  const uint32_t sensorStarttimei(const int ind) const {
    return sensorlist()[ind].starttime;
  }
  const uint32_t sensorStarttime(const char *name) const {
    if (int ind = sensorindex(name); ind >= 0)
      return sensorlist()[ind].starttime;
    return 0;
  }

  static bool validSensorStarttime(uint32_t tim) {
    return tim > 1598911200u && tim < 2145909600u;
  }

  bool setSensorStarttime(int ind, uint32_t starttime, bool reorder = true) {
    if (ind < 0 || ind > last() || !validSensorStarttime(starttime))
      return false;
    sensor &sens = sensorlist()[ind];
    if (sens.starttime == starttime)
      return true;
    sens.starttime = starttime;
    sens.present = 1;
    if (reorder)
      setindices();
    return true;
  }

  bool repairMissingStarttime(int ind, sensor &sens, SensorGlucoseData *hist,
                              bool reorder = true) {
    if (sens.starttime)
      return true;
    if (!hist)
      hist = getSensorData(ind);
    if (!hist) {
      LOGGER("sensor %d has no starttime and no readable history; marking inactive\n",
             ind);
      sens.present = 0;
      sens.markFinished();
      return false;
    }

    uint32_t start = hist->getstarttime();
    if (!validSensorStarttime(start)) {
      uint32_t anchor = hist->getfirsttime();
      if (!validSensorStarttime(anchor))
        anchor = hist->lastused();
      if (validSensorStarttime(anchor)) {
        start = anchor > 3600u ? anchor - 3600u : anchor;
        if (auto *info = hist->getinfo())
          info->starttime = start;
      }
    }

    if (!validSensorStarttime(start)) {
      if (!hist->pollcount() && !hist->scancount()) {
        LOGGER("sensor %d has no starttime and no data; marking inactive\n", ind);
        sens.present = 0;
        sens.markFinished();
      }
      return false;
    }

    LOGGER("repaired sensor %d %s missing starttime=%u\n", ind,
           sens.showsensorname(), start);
    return setSensorStarttime(ind, start, reorder);
  }

  vector<int> bluetoothactive(uint32_t tim, uint32_t nu) {
    vector<int> out;
    const uint32_t oldsecs = nu - maxSIhours * 60 * 60;
    const uint32_t newsecs = nu - youngsensorsecs;
    bool repairedOrder = false;
    int i = getendindex();
    if (i >= 0) {
      for (int prev; i != LISTEND; i = prev) {
        auto &sensor = sensorlist()[i];
        if (sensor.next == 0 && sensor.prev == 0)
          setindices();
        prev = sensor.prev;
        if (sensor.finished) {
          continue;
        }
        if (sensor.endtime < newsecs) {
          SensorGlucoseData *hist = getSensorData(i);
          if (!hist) {
            if (!sensor.starttime) {
              sensor.present = 0;
              sensor.markFinished();
            } else {
              LOGSTRING("hist==null\n");
            }
            continue;
          }
          if (!sensor.starttime) {
            if (!repairMissingStarttime(i, sensor, hist, false)) {
              continue;
            }
            repairedOrder = true;
          }

          if (hist->isSibionics() && hist->hasSensorError(nu)) {
            out.push_back(i);
            continue;
          }
          const auto sensmax = hist->lastused();  // Use actual last data time, not expected max time
          if (sensmax <= oldsecs) {
            LOGGER("blueactive %s old %u\n", showsensorname(i), sensmax);
            break;
          }
          const auto lasttime = hist->lastused();
          bool canuse = hist->canusestreaming();
          // If in reset mode, ignore gap timeout to allow backfill.
          // isInResetMode() handles auto-clearing when gap closes.
          if (!canuse ||
              (lasttime && lasttime < tim && !hist->isInResetMode())) {
            continue;
          }
        } else {
          SensorGlucoseData *hist = getSensorData(i);
          if (!hist) {
            LOGSTRING("hist==null\n");
            continue;
          }
          if (!sensor.starttime) {
            if (!repairMissingStarttime(i, sensor, hist, false)) {
              continue;
            }
            repairedOrder = true;
          }
          if (!hist->canusestreaming())
            continue;
        }
        out.push_back(i);
      }
    }
    if (repairedOrder)
      setindices();
    setlibre3nums();
    return out;
  }
  std::pair<int, uint32_t> lastused(uint32_t (SensorGlucoseData::*proc)(void)
                                        const) {
    uint32_t lasttime = 0;
    int newst = -1;
    int i = getendindex();
    if (i >= 0) {
      while (true) {
        sensor *sens = getsensor(i);
        if (sens->next == 0 && sens->prev == 0)
          setindices();
        if (sens->maxtime() < lasttime)
          break;
        if (const SensorGlucoseData *hist = getSensorData(i)) {
          if (const uint32_t tim = (hist->*proc)(); tim > lasttime) {
            lasttime = tim;
            newst = i;
          }
        }
        i = sens->prev;
        if (i == LISTEND)
          break;
      }
    }
    return {newst, lasttime};
  }

  int lastscanned() {
    auto [id, _] = lastused(&SensorGlucoseData::getlastscantime);
    return id;
  }

  auto lastpolltime() { return lastused(&SensorGlucoseData::getlastpolltime); }

  auto firstpolltime() {
    int i = getstartindex();
    if (i >= 0) {
      do {
        sensor *sens = getsensor(i);
        if (sens->next == 0 && sens->prev == 0)
          setindices();
        if (sens->present) {
          if (const SensorGlucoseData *hist = getSensorData(i)) {
            auto tim = hist->firstpolltime();
            if (tim < UINT32_MAX)
              return tim;
          }
        }
        i = sens->next;
      } while (i != LISTEND);
    }
    return (decltype(std::declval<SensorGlucoseData>().firstpolltime()))0;
  }

  const SensorGlucoseData *laststreamsensor() {
    int i = getendindex();
    if (i >= 0) {
      do {
        if (const SensorGlucoseData *hist = getSensorData(i)) {
          if (hist->pollcount() > 0)
            return hist;
        }
        sensor *sens = getsensor(i);
        if (sens->next == 0 && sens->prev == 0)
          setindices();
        i = sens->prev;
      } while (i != LISTEND);
    }
    return nullptr;
  }

  /*
  void converttrends() {
  for(int i=0;i<=last();i++) {
     SensorGlucoseData *hist=getSensorData(i);
     hist->converttrends();
     }
  }
  void convertlast() {
  SensorGlucoseData *hist=getSensorData(last());
  hist->converttrends();
  }
  */

  void removeoldstates();

  void updateinit(const int ind) {
    for (int i = last(); i >= 0; i--) {
      if (SensorGlucoseData *hist = getSensorData(i))
        hist->updateinit(ind);
    }
  }

  bool setbackuptime(crypt_t *pass, Connect *connect, const int ind,
                     const uint32_t starttime, uint16_t &starttimeindex) {
    int nr = last() + 1;
    if (starttimeindex > nr)
      starttimeindex = nr;
    while (starttimeindex > 0) {
      auto newindex = starttimeindex - 1;
      if (SensorGlucoseData *hist = getSensorData(newindex)) {
        if (!hist->setbackuptime(pass, connect, ind, starttime))
          return false;
      } else {
        LOGGER("getSensorData(%d) failed\n", newindex);
        return false;
      }
      starttimeindex = newindex;
    }
    return true;
  }

  inline static constexpr const std::string_view sensorfile{
      "sensors/sensors.dat"};

  int writeStartime(crypt_t *pass, Connect *connect, const int sensorindex) {
    const uint8_t *starttimeptr =
        reinterpret_cast<uint8_t *>(&getsensor(sensorindex)->starttime);
    const uint8_t *startdata = reinterpret_cast<uint8_t *>(map.data());
    const int offset = starttimeptr - startdata;
    if (!connect->senddata(pass, offset, starttimeptr, sizeof(uint32_t),
                  sensorfile)) {
      LOGAR("writeStartime: sending starttime failed");
      return 0;
    }
    LOGGER("writeStartime: send starttime %u offset=%d\n",
           getsensor(sensorindex)->starttime, offset);
    return 1;
  }

  void alignMirrorRemoteBase(SensorGlucoseData *hist, const int sensorindex) const {
    if (!hist || sensorindex < 0 || sensorindex > last())
      return;
    const auto fullname = getsensor(sensorindex)->fullname();
    if (fullname.empty())
      return;
    std::string remoteBase("sensors/");
    remoteBase.append(fullname.data(), fullname.size());
    hist->setMirrorRemoteBase(remoteBase);
  }

  int sendCalibrates(crypt_t *pass, Connect *connect, int ind,
                     uint16_t &startSendCalibrate) {
    const int lastsens = last();
    const int first = startSendCalibrate;
    if (lastsens < first)
      return 2;
    extern std::mutex caliMutex;
    std::lock_guard<std::mutex> lock(caliMutex);
    extern std::string javaExportCalibrationProfile(const char *serial);
    int did = 0;
    for (int sindex = first; sindex <= lastsens; ++sindex) {
      SensorGlucoseData *sens = getSensorData(sindex);
      if (!sens)
        return 0;
      alignMirrorRemoteBase(sens, sindex);
      int res = sens->updateCali(pass, connect, ind, sindex);
      if (!res)
        return 0;
      did |= res;
      const char *serial = getsensor(sindex)->shortsensorname_chars();
      if (serial && serial[0]) {
        if (std::string json = javaExportCalibrationProfile(serial);
            !json.empty()) {
          std::string relpath = "mirror/calibration/";
          relpath += serial;
          relpath += ".json";
          if (!connect->senddata(pass, 0,
                        reinterpret_cast<const senddata_t *>(json.data()),
                        static_cast<int>(json.size()), relpath)) {
            LOGGER("sendCalibrates: failed sending Kotlin profile for %s\n",
                   serial);
            return 0;
          }
          did |= 1;
        }
      }
    }
    startSendCalibrate = lastsens;
    return did;
  }
  int update(crypt_t *pass, Connect *connect, const int ind, int &startupdate,
             int &firstsensor, const bool upstream, const bool upscan,
             const bool restoreinfo, const bool resetdevices) {
    LOGGER("Sensoren::update firstsensor=%d sock=%d ind=%d resetdevices=%d\n",
           firstsensor, connect->getSenderIdent(), ind, resetdevices);
    int changed = INT_MAX;
    int did = 2;

#ifdef SENDSHOW
    int lastlast = -1;
#endif
    bool newdevices = false;

    const int lastsens = last();
    int newfirst = -1;
    std::vector<SensorGlucoseData *> sendstream;
    destruct failed([&sendstream, ind] {
      for (auto *el : sendstream)
        el->getinfo()->update[ind].sendstreaming = true;
    });

    const uint32_t now = time(NULL);
    for (int i = firstsensor; i <= lastsens; i++) {
      LOGGER("sensor %d\n", i);
      if (SensorGlucoseData *hist = getSensorData(i)) {
        alignMirrorRemoteBase(hist, i);
        if (hist->error())
          continue;
        if (newfirst < 0 &&
            (!sensorlist()[i].finished || now < hist->getmaxtime())) {
          newfirst = i;
        }

        if (upstream) {
          const int resstream = hist->updatestream(pass, connect, ind, i, 0);
          switch (resstream) {
          case 0:
            return did & 0x4;
            //                  case 1:
          };

#ifdef SENDSHOW
          if (resstream == 1 && i == lastsens)
            lastlast = i;
#endif
          did |= resstream;
          if (hist->isSibionics()) {
            int jsonres = hist->sendSibionicsState(pass, connect,
                                                   ind); // TODO send less often
            if (!jsonres) {
              return did & 0x4;
            }
            did |= jsonres;
          }
        }

        if (upscan) {
          const int resscan =
              hist->updatescan(pass, connect, ind, i, i >= startupdate, upstream);
          switch (resscan) {
          case 0:
            return did & 0x4;
          case 5:
            sendstream.push_back(hist);
            newdevices = true;
          case 1: {
            if (changed > i)
              changed = i + 1; // MODIFIED
          }
          };
          did |= resscan;
        }
      } else
        return did & 0x4;
    }
    if (((lastsens >= startupdate || resetdevices) && (changed = 0, true)) ||
        changed < INT_MAX) {
      const int endsens = lastsens + 1;
      const auto *begin =
          map.data(); // Start with info block, sensor at position 1
      const int afterend = endsens + 1; // sensors start from 1

      LOGGER("senddata(%d,%p,%d,%s)\n", changed, begin + changed,
             afterend - changed, sensorfile.data());

      std::vector<subdata> vect;
      int subtract;

      if (changed == 0) {
        vect.reserve(2);
        vect.push_back({reinterpret_cast<const senddata_t *>(&lastsens), 0, 4});
        subtract = 4;
      } else {
        vect.reserve(1);
        subtract = 0;
      }
      vect.push_back(
          {reinterpret_cast<const senddata_t *>(begin + changed) + subtract,
           static_cast<int>(changed * sizeof(begin[0])) + subtract,
           static_cast<int>((afterend - changed) * sizeof(begin[0])) -
               subtract});

      if (!connect->senddata(pass, vect, sensorfile))
        return did & 0x4;

      /*
               if (!senddata(pass, sock, changed, begin + changed, begin +
         afterend , sensorfile)) return did&0x4; */
      startupdate = endsens;

      did = 1;
      if (newdevices) {
        if (!connect->sendResetDevices(pass)) {
          LOGGER("GLU %s: sendResetDevices(pass) failed\n",
                 shortsensorname()->data());
          return did & 0x4;
        } else {
          LOGGER("GLU %s: sendResetDevices(pass)\n",
                 shortsensorname()->data());
        }
      }
    }
#ifdef SENDSHOW
    if (lastlast >= 0) {
      if (!connect->sendshowglucose(pass, lastlast))
        return did & 0x4;
    }
#endif
    if (newfirst >= 0)
      firstsensor = newfirst;
    failed.active = false;
    return did;
  }

  int update(crypt_t *pass, Connect *connect, const int ind, int &firstsensor,
             int otheralso,
             int (SensorGlucoseData::*proc)(crypt_t *, Connect *, int, int, int)) {
    uint32_t now = time(NULL);
    int did = 2;
    for (int i = last(); i >= firstsensor; i--) {
      auto &sensor = sensorlist()[i];
      if (sensor.maxtime() < now)
        break;
      if (!sensor.finished) {
        if (SensorGlucoseData *hist = getSensorData(i)) {
          alignMirrorRemoteBase(hist, i);
          if (hist->error())
            continue;
          int subdid = (hist->*proc)(pass, connect, ind, i, otheralso);
          if (!subdid)
            return 0;
          did |= subdid;
        }
      }
    }
    return did;
  }

  int updatescanss(crypt_t *pass, Connect *connect, const int ind,
                   int &firstsensor, int streamalso) {
    LOGGER("updatescanss sock=%d ind=%d\n", connect->getSenderIdent(), ind);
    return update(pass, connect, ind, firstsensor, streamalso,
                  &SensorGlucoseData::updatescanalg);
  }

  int updatestreams(crypt_t *pass, Connect *connect, const int ind,
                    int &firstsensor, int scanalso) {
    LOGGER("updatestreams sock=%d ind=%d\n", connect->getSenderIdent(), ind);
    int res = update(pass, connect, ind, firstsensor, scanalso,
                     &SensorGlucoseData::updatestream);

    return res;
  }
  template <class FUN> void onallsensors(const FUN &fun) {
    for (int i = last(); i >= 0; i--) {
      if (SensorGlucoseData *hist = getSensorData(i))
        fun(hist);
    }
  };
  bool knownDex(const char *name, const char *address) const {
    for (int i = last(); i >= 0; i--) {
      const sensor *sen = getsensor(i);
      if (!memcmp(sen->name, "E0", 2))
        continue;
      if (const SensorGlucoseData *sens = getSensorData(i)) {
        if (!sens->isDexcom())
          continue;
        if (!strcmp(sens->getinfo()->DexDeviceName, name) &&
            !strcmp(sens->deviceaddress(), address)) {
          LOGGER("KnownDex %d %s %s\n", i, name, address);
          return true;
        }
      }
    }
    LOGGER("no KnownDex %s %s\n", name, address);
    return false;
  }

public:
  void makelinks(const int index, int previndex) {
    LOGGER("makelinks(%d,%d)\n", index, previndex);
    sensor *sens = getsensor(index);
    if (previndex < 0) {
      sens->next = sens->prev = LISTEND;
    } else
      placesensorinlist(sens, index, previndex);
  }
  void setindices() {
    const int lastpos = last();
    LOGGER("setindices %d\n", lastpos);
    if (lastpos < 0)
      return;
    sensor *first = getsensor(0);
    first->prev = LISTEND;
    first->next = LISTEND;
    int lastsensor = 0;
    for (int i = 1; i <= lastpos; i++) {
      sensor *sens = getsensor(i);
      lastsensor = placesensorinlist(sens, i, lastsensor);
    }
  }

private:
  bool removelinks(int index) {
    sensor *sens = getsensor(index);
    int wasprev = sens->prev;
    int wasnext = sens->next;
    if (wasnext == 0 && 0 == wasprev) {
      return true;
    }
    if (wasprev != LISTEND) {
      sensor *psensor = getsensor(wasprev);
      if (psensor->next != index)
        return true;
      LOGGER("sensor %d next=%d\n", wasprev, wasnext);
      psensor->next = wasnext;
    }
    if (wasnext != LISTEND) {
      sensor *psensor = getsensor(wasnext);
      if (psensor->prev != index)
        return true;
      LOGGER("sensor %d prev=%d\n", wasnext, wasprev);
      psensor->prev = wasprev;
    }
    return false;
  }
  int placesensorinlist(sensor *sens, int index, int prev) {
    sensor *const prevsens = getsensor(prev);
    if (prevsens->starttime <= sens->starttime) {
      int wasnext = prevsens->next;
      prevsens->next = index;
      sens->prev = prev;
      sens->next = wasnext;
      return index;
    }
    const int newprev = prevsens->prev;
    if (newprev == LISTEND) {
      sens->prev = LISTEND;
      sens->next = prev;
      prevsens->prev = index;
      return prev;
    }
    placesensorinlist(sens, index, newprev);
    int nownext = sens->next;
    sensor *nextsens = getsensor(nownext);
    nextsens->prev = index;
    return prev;
  }
  sensor *getend() {
    int i = last();
    sensor *sens = getsensor(i);
    if (sens->next == 0 && sens->prev == 0) {
      setindices();
    }
    while (sens->next != LISTEND) {
      sens = getsensor(sens->next);
    }
    return sens;
  }
  int getendindex() {
    int index = last();
    if (index < 0)
      return -1;
    sensor *sens = getsensor(index);
    if (sens->next == 0 && sens->prev == 0) {
      setindices();
    }
    while (sens->next != LISTEND) {
      index = sens->next;
      sens = getsensor(index);
    }
    return index;
  }

  sensor *getstart() {
    int i = 0;
    sensor *sens = getsensor(i);
    if (sens->next == 0 && sens->prev == 0) {
      setindices();
    }
    while (sens->prev != LISTEND) {
      sens = getsensor(sens->prev);
    }
    return sens;
  }
  int getstartindex() {
    const int lastindex = last();
    if (lastindex < 0)
      return -1;
    int first = 0;
    sensor *sens = getsensor(first);
    if (sens->next == 0 && sens->prev == 0) {
      setindices();
    }
    for (; sens->prev != LISTEND; sens = getsensor(first)) {
      first = sens->prev;
    }
    return first;
  }
  /*
  sensor *getprev(const sensor *sens)  {
  if(sens->prev==LISTEND)
  return nullptr;
  return getsensor(sens->prev);
  }
  int getprevindex(int index)  {
  const sensor *sens=getsensor(index);
  return sens->prev;
  } */
  template <typename F> void intersens(F func) {
    if (last() < 0)
      return;
    for (sensor *sens = getend();; sens = getsensor(sens->prev)) {
      func(sens);
      if (sens->prev == LISTEND) {
        return;
      }
    }
  }
  template <typename F> void fromstart(F func) {
    if (last() < 0)
      return;
    for (sensor *sens = getstart();; sens = getsensor(sens->next)) {
      func(sens);
      if (sens->next == LISTEND) {
        return;
      }
    }
  }
};
inline std::ostream &operator<<(std::ostream &os, const sensor &sens) {
  os << sens.name << endl;
  time_t tim = sens.starttime;
  os << "starttime:\t" << ctime(&tim);
  tim = sens.endtime;
  os << (!sens.finished ? "Not " : "") << "Finished" << endl;
  if (tim)
    os << "endtime:\t" << ctime(&tim);
  os << (!sens.present ? "Not " : "") << "Present" << endl;
  return os;
}

#endif
