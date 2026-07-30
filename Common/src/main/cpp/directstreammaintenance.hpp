#pragma once

#include <algorithm>
#include <cstddef>
#include <limits>
#include <string_view>
#include <utility>

namespace directstream {

template <typename PollMap, typename RawMap, typename TemperatureMap>
bool ensurePollStorageCapacity(
    PollMap &polls,
    RawMap &rawpolls,
    TemperatureMap &temppolls,
    std::string_view sensorDir,
    size_t minimumRecords) {
  const size_t current =
      std::min({polls.size(), rawpolls.size(), temppolls.size()});
  if (current >= minimumRecords)
    return true;
  if (minimumRecords > static_cast<size_t>(std::numeric_limits<int>::max()))
    return false;

  const int requested = static_cast<int>(minimumRecords);
  PollMap expandedPolls(sensorDir, "polls.dat", requested);
  RawMap expandedRaw(sensorDir, "rawpolls.dat", requested);
  TemperatureMap expandedTemperatures(sensorDir, "temppolls.dat", requested);
  if (!expandedPolls.data() || !expandedRaw.data() ||
      !expandedTemperatures.data() ||
      expandedPolls.size() < minimumRecords ||
      expandedRaw.size() < minimumRecords ||
      expandedTemperatures.size() < minimumRecords) {
    return false;
  }

  polls = std::move(expandedPolls);
  rawpolls = std::move(expandedRaw);
  temppolls = std::move(expandedTemperatures);
  return true;
}

inline int nightSensorAfterRewind(int currentSensor, int targetSensor) {
  if (currentSensor <= 0)
    return currentSensor;
  return std::min(currentSensor, targetSensor);
}

} // namespace directstream
