#pragma once
// Rate-of-change fallback shared by the ingest paths and the exchange serializers.
//
// Not every driver reports a rate of its own: AiDex's direct live frame carries no trend byte,
// Libre 3 history backfill stores none, and the generic managed-driver stream path never had one.
// ScanData.ch is NAN for those samples, which serializes as direction:"" and delta:0 into every
// exchange payload (Nightscout, the watch server, xDrip broadcasts) — see issue #114.
//
// This mirrors ExchangeTrend.deriveRate() in
// Common/src/main/java/tk/glucodata/ExchangeTrend.java. Keep the two in step; ExchangeTrendTests
// pins the Java side, and the thresholds that turn a rate into a 7-state arrow live in
// getdeltaindex() in glucose.cpp.
#include "SensorGlucoseData.hpp"
#include <cmath>
#include <cstdint>
#include <span>

// Below the floor, mg/dL quantisation dominates: on a 1-minute grid a single 1 mg/dL wobble reads
// as a full arrow of movement. Above the ceiling the older sample no longer describes where
// glucose is heading now, and emitting no arrow beats emitting a confidently wrong one.
static constexpr const uint32_t minderivegapsecs = 4 * 60;
static constexpr const uint32_t maxderivegapsecs = 20 * 60;

inline float deriveChangeMgdlPerMinute(const int prevmgdl, const uint32_t prevtime,
                                       const int curmgdl, const uint32_t curtime) {
  if (prevmgdl <= 0 || curmgdl <= 0 || curtime <= prevtime)
    return NAN;
  const uint32_t gap = curtime - prevtime;
  if (gap < minderivegapsecs || gap > maxderivegapsecs)
    return NAN;
  return (curmgdl - prevmgdl) / (gap / 60.0f);
}

// Rate to the legacy 5-state trend index stored in ScanData.tr, whose labels are
// GlucoseNow::trendString in glucose.hpp. Lives here rather than in dexcom/java.cpp so drivers can
// use it without depending on the Dexcom translation unit, which the nodex flavour omits entirely.
// rate2changeindex() in dexcom/java.cpp delegates here so there is one ladder, not two.
inline int ratetolegacytrendindex(const float rate) {
  if (std::isnan(rate))
    return 0;
  if (rate <= -2.0f)
    return 1;
  if (rate <= -1.0f)
    return 2;
  if (rate <= 1.0f)
    return 3;
  if (rate <= 2.0f)
    return 4;
  return 5;
}

// Rate for a sample at index pos whose value/time are given explicitly, so ingest paths can call
// this before polls[pos] has been written. Searches back from pos-1 for the newest usable
// predecessor rather than taking polls[pos-1] outright, so a 1-minute grid still measures over a
// useful span and gaps (g==0) are skipped instead of poisoning the result.
inline float derivechangeforsample(const ScanData *polls, const int first, const int pos,
                                   const int curmgdl, const uint32_t curtime) {
  if (!polls || pos <= first || !curtime || curmgdl <= 0)
    return NAN;
  for (int i = pos - 1; i >= first; --i) {
    const ScanData &prev = polls[i];
    const uint32_t prevtime = prev.gettime();
    if (!prevtime || prev.getmgdL() <= 0 || curtime <= prevtime)
      continue;
    if ((curtime - prevtime) > maxderivegapsecs) // only gets worse going further back
      return NAN;
    const float rate = deriveChangeMgdlPerMinute(prev.getmgdL(), prevtime, curmgdl, curtime);
    if (!isnan(rate))
      return rate;
  }
  return NAN;
}

// Rate for an already-stored sample.
inline float derivechangefrompolls(std::span<const ScanData> gdata, const int pos) {
  if (pos <= 0 || pos >= (int)gdata.size())
    return NAN;
  const ScanData &item = gdata[pos];
  return derivechangeforsample(gdata.data(), 0, pos, item.getmgdL(), item.gettime());
}

// Driver-reported rate when there is one, otherwise a derived fallback.
inline float effectivechange(std::span<const ScanData> gdata, const int pos, const ScanData &item) {
  const float reported = item.getchange();
  if (!isnan(reported))
    return reported;
  return derivechangefrompolls(gdata, pos);
}
