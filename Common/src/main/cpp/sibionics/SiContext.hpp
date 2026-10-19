#ifdef SIBIONICS
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
/*      Thu Apr 04 20:14:40 CEST 2024 */

#pragma once
#include "config.h"
#include "scanstate.hpp"
#include <cstdint>
#include <ctime>
#include <jni.h>
class SensorGlucoseData;
#include "AlgorithmContext.hpp"
template <typename T> struct gegs;
typedef gegs<signed char> data_t;
class SiContext {
private:
  multimmap binState;
  AlgorithmContext *algcontext;
  bool notchinese;
  int badValueStreak =
      0; // Per-sensor streak counter (was static — shared across sensors)

  double process2(int index, double value, double temp);
  double process3(int index, double value, double temp);
  void release();

public:
  bool isNotchinese() const { return notchinese; }
  void setNotchinese(SensorGlucoseData *sens);
  SiContext(SensorGlucoseData *sens);
  jlong processData(SensorGlucoseData *sens, time_t nowsecs, int8_t *data,
                    int totlen, int sensorindex);
#ifdef NOTCHINESE
  jlong processData2(SensorGlucoseData *sens, time_t nowsecs, data_t *data,
                     int sensorindex);
#endif
  ~SiContext();
  void prepareHardwareReset(SensorGlucoseData *sens);
  void reset(SensorGlucoseData *sens);
  void resetAll(SensorGlucoseData *sens);
  void wipeDataOnly(SensorGlucoseData *sens);
  void localReplay(SensorGlucoseData *sens);
  bool reloadFromPersistedState(SensorGlucoseData *sens);
  // Edit 86: Allow JNI to clear the bad-value streak counter when
  // custom calibration settings change, preventing stale streaks
  // from triggering an immediate algorithm reset.
  void clearBadValueStreak() { badValueStreak = 0; }
};
#endif
