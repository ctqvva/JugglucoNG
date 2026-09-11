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
/*      Thu Apr 04 20:15:06 CEST 2024 */

#ifdef SIBIONICS
#include "SensorGlucoseData.hpp"
#include "config.h"
#include "datbackup.hpp"
#include "sensoren.hpp"
#include "settings/settings.hpp"
#include "sibionics/SiContext.hpp"
#include <bit>
#include <numeric>
#include <stdint.h>
#include <stdio.h>
#include <vector>
extern Sensoren *sensors;
#ifndef LOGGER
#define LOGGER(...) printf(__VA_ARGS__)
#endif

int sitrend2abbott(int sitrend) {
  if (sitrend < -2) {
    if (sitrend == -3)
      return 1;
    return 0;
  }
  if (sitrend > 2) {
    if (sitrend == 3)
      return 1;

    return 0;
  }
  return sitrend + 3;
}
float sitrend2RateOfChange(int sitrend) { return sitrend * 1.3f; }
#ifdef MAIN
#define savejson(sens, name, index, alg, getjson) x
#define glucoseback(glval, drate, hist)
#else
// #include "sibionics/json.hpp"
// extern bool savejson(SensorGlucoseData *sens,std::string_view, int
// index,const AlgorithmContext *alg,getjson_t getjson ); extern getjson_t
// getjson3; extern jlong glucoseback(uint32_t glval,float
// drate,SensorGlucoseData *hist) ;
extern jlong glucoseback(uint32_t nu, uint32_t glval, float drate,
                         SensorGlucoseData *hist);
#ifndef NOLOG
void logbytes(std::string_view text, const uint8_t *value, int vallen) {
  int totlen = text.size() + 2 + vallen * 3 + 2;
  char mess[totlen];
  memcpy(mess, text.data(), text.size());
  char *ptr = mess + text.size();
  *ptr++ = ':';
  for (int i = 0; i < vallen; i++) {
    ptr += sprintf(ptr, " %02X", value[i]);
  }
  *ptr++ = '\n';
  LOGGERN(mess, ptr - mess);
}
#else
#define logbytes(text, value, vallen)
#endif
#endif
#define saveSi3(sens, index, eventTime, save, value, temp, last)

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

static void updateStartTimeFromAcceptedPacket(SensorGlucoseData *sens,
                                              sensor *sensor, int index,
                                              time_t eventTime, int maxid,
                                              int numOfUnreceived) {
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
  if (!Sensoren::validSensorStarttime(packetStart) ||
      packetStart > (uint32_t)eventTime) {
    return;
  }

  constexpr uint32_t startDriftTolerance = 30;
  const bool currentPacket = numOfUnreceived == 0;
  const bool earlyPacket = index <= 9;
  if (!currentPacket && !earlyPacket) {
    return;
  }

  const uint32_t oldInfoStart = info->starttime;
  const uint32_t infoDiff = oldInfoStart > packetStart
                                ? oldInfoStart - packetStart
                                : packetStart - oldInfoStart;
  const bool validInfoStart = Sensoren::validSensorStarttime(oldInfoStart);
  const bool updateInfo =
      !validInfoStart ||
      (infoDiff > startDriftTolerance &&
       (currentPacket || packetStart > oldInfoStart + startDriftTolerance));

  const uint32_t oldListStart = sensor->starttime;
  const uint32_t listDiff = oldListStart > packetStart
                                ? oldListStart - packetStart
                                : packetStart - oldListStart;
  const bool validListStart = Sensoren::validSensorStarttime(oldListStart);
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
         "maxid=%d numOfUnreceived=%d itime=%ld\n",
         oldInfoStart, oldListStart, packetStart, index, maxid,
         numOfUnreceived, (long)eventTime);
}

jlong SiContext::processData(SensorGlucoseData *sens, time_t nowsecs,
                             int8_t *data, int totlen, int sensorindex) {
  logbytes("SIprocess", (uint8_t *)data, totlen);
  if (data[2] != 9 || data[0] != -86 || data[1] != 85) {
    static constexpr const int8_t doauth[] = {
        (int8_t)0x23, (int8_t)0xF7, (int8_t)0x6F, (int8_t)0xD9, (int8_t)0xF4};
    if (totlen == sizeof(doauth) && !memcmp(data, doauth, sizeof(doauth)) &&
        !sens->pollcount()) {
      setNotchinese(sens);
      return 4LL;
    }
    LOGGER("wrong start %d %d %d\n", data[0], data[1], data[2]);
    return 2LL;
  }
  {
    const int len = totlen - 1;
    const long sum = std::accumulate(data, data + len, 0L);
    if (((int8_t)((~(sum & 0xFF)) + 1)) != data[len]) {
      LOGGER("wrong sum %ld %d\n", sum, data[len]);
      return 2LL;
    }
  }
  sensor *sensor = sensors->getsensor(sensorindex);
  const int multiple = data[3];
  const int maxoff = multiple * 14;
  int8_t *const start = data + 4;
  for (int off = 0; off < maxoff; off += 14) {
    const uint16_t *one = reinterpret_cast<uint16_t *>(start + off);
    const int index = std::byteswap(one[0]);
    const uint16_t rawTemp = std::byteswap(one[1]);
    const double temp = rawTemp / 10.0;
    const double value = std::byteswap(one[3]) / 10.0;
    const int rawCurrent = std::byteswap(one[3]); // raw sensor integer (0.1 mmol/L units)
    const int numOfUnreceived = std::byteswap(one[5]);

    const int maxid = sens->getSiIndex();
    if (index != maxid) {
      if (index < maxid) {
        LOGGER("SIprocess index=%d<maxid=%d\n", index,
               maxid); // probably run parallel with other app requesting
                       // different index. Reconnect
        return 3LL;
      } else {
        LOGGER("SIprocess index=%d>maxid=%d\n", index,
               maxid); // Idem. Don't block forever.
        // Original Juggluco logic: retry more on larger gaps
        int maxretry =
            (index - maxid) < 20 ? 2 : ((index - maxid) < 200 ? 5 : 10);
        if (sens->retried++ < maxretry) {
          return 3LL;
        }
        // Retries exhausted, fall through to process
      }
    }
    const int addtime = std::byteswap(one[6]);
    long offtime = addtime - (numOfUnreceived * 60);
    const bool infuture = offtime > 0;
    LOGGER("Siprocess: addtime=%d added=%ld\n", addtime, offtime);
    time_t eventTime = nowsecs + offtime;
    if (infuture) {
      LOGGER("Siprocess: wrong time: %ld seconds future addtime=%d index=%d "
             "temp=%f value=%f numOfUnreceived=%d\n",
             offtime, addtime, index, temp, value, numOfUnreceived);
      eventTime = nowsecs;
    }
    // Original Juggluco logic - process3 result goes directly into newvalue
    // check
    // Initialize deterministically. Later branches still decide whether the
    // sample is usable; do not silently substitute raw as a calibrated value.
    double newvalue = 0.0;
    if (value > 0.1 && value < 3000.0 &&
        (newvalue = process3(index, value, temp)) > 1) {
      sens->getinfo()->pollinterval = newvalue - value;
    } else {
      if (sens->getinfo()->pollinterval < 40)
        newvalue = value + sens->getinfo()->pollinterval;
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

#ifndef NOLOG
    const long electric = std::byteswap(one[2]);
    const int status = std::byteswap(one[4]);
#endif
    const bool rawFallbackUsable = !(newvalue > 1 && newvalue < 30) &&
                                   value > 1.0 && value < 30.0;
    const double persistedValue = rawFallbackUsable ? value : newvalue;
    const int mgdL = std::round(persistedValue * convfactordL);
    const int trend = algcontext->ig_trend;
    const float change = sitrend2RateOfChange(trend);
    const int abbotttrend = sitrend2abbott(trend);
    LOGGER("SIprocess: index=%d temp=%f electric=%ld value=%f->%f status=%d "
           "numOfUnreceived=%d addtime=%d trend=%d rate=%.2f abbotttrend=%d\n",
           index, temp, electric, value, mgdL / convfactordL, status,
           numOfUnreceived, addtime, trend, change, abbotttrend);

    //             if(infuture) sens->setSiIndex(index+1);
    // Valid range check aligned with sanity check thresholds (0.5-40)
    if (persistedValue > 1 && persistedValue < 30) {
      if (rawFallbackUsable) {
        LOGGER("SIprocess raw fallback: index=%d temp=%f value=%f "
               "numOfUnreceived=%d\n",
               index, temp, value, numOfUnreceived);
      }
      sens->savestream(eventTime, index, mgdL, abbotttrend, change, rawCurrent,
                       rawTemp);
      if (!infuture) {
        updateStartTimeFromAcceptedPacket(sens, sensor, index, eventTime, maxid,
                                          numOfUnreceived);
      }
      sens->setSiIndex(index + 1);
      sens->retried = 0;
      saveSi3(sens, index, eventTime, !infuture, value, temp, !numOfUnreceived);
      if (!numOfUnreceived) {
        // Current-time data (not replay). If we were in reset mode,
        // replay is done — exit reset mode so normal operation resumes.
        if (sens->isInResetMode()) {
          LOGGER("SIprocess: replay reached current data (eventTime=%ld), exiting reset mode\n", (long)eventTime);
          sens->exitResetMode();
        }
        sens->sensorerror = false;
        if (sensor->finished && sensor->reactivateFromData()) {
          LOGSTRING("SIprocess finished becomes 0\n");
          backup->resensordata(sensorindex);
        }
        auto res = glucoseback(eventTime, mgdL, change, sens);
        //                   if(!(index%5))
        //                   savejson(sens,sens->statefile,index,algcontext,getjson3);
        backup->wakebackup(Backup::wakestream);
        extern void wakewithcurrent();
        wakewithcurrent();
        return res;
      } else {
        /*                   if(!infuture&&!(index%500)) {
                                savejson(sens,sens->statefile,index,algcontext,getjson3);
                                backup->wakebackup(Backup::wakestream);
                                } */
        sens->receivehistory = nowsecs;
      }
      const int last = sens->pollcount() - 1;
      if (last < sens->getbroadcastfrom())
        sens->setbroadcastfrom(last);

    } else {
      if (index == maxid)
        sens->setSiIndex(maxid + 1);
      LOGGER("SIprocess failed: index=%d temp=%f value=%f numOfUnreceived=%d\n",
             index, temp, value, numOfUnreceived);
      const bool savedRawOnly =
          saveRawOnlyPoll(sens, eventTime, index, rawCurrent, rawTemp);
      if (!infuture && savedRawOnly) {
        updateStartTimeFromAcceptedPacket(sens, sensor, index, eventTime, maxid,
                                          numOfUnreceived);
      }
      if (!numOfUnreceived && savedRawOnly) {
        sens->receivehistory = nowsecs;
        return 11LL;
      }
      if (!numOfUnreceived && !(index % 5)) {
        sens->sensorerror = true;
        sens->sensorErrorTime = nowsecs;
        return 0LL;
      }
    }
  }
  return 1LL;
}
/*
int main() {
#include "init.cpp"
for(auto &el:data)
      processData((int8_t*)el.data(),el.size());
   } */
#endif
