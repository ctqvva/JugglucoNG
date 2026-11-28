#pragma once

#include <algorithm>
#include <cstdint>
#include <limits>
#include <map>

// Call under SensorGlucoseData::pollWriteMutex(). Cursor lives in info.dat; only
// the in-flight acknowledgement guard is process-local. A restart therefore
// resumes at the persisted rewind even if the export died before acknowledging.
class HealthConnectBackfillProgress {
  struct Batch {
    int end;
    int dirty = std::numeric_limits<int>::max();
  };
  std::map<const void *, Batch> batches;

public:
  void begin(const void *sensor, int start, int end) {
    if (start < end) batches[sensor] = Batch{end};
    else batches.erase(sensor);
  }

  void rewind(const void *sensor, int index, uint16_t &cursor) {
    if (index < 0 || index > UINT16_MAX) return;
    cursor = static_cast<uint16_t>(std::min<int>(cursor, index));
    const auto it = batches.find(sensor);
    // Even a gap ahead of the persisted cursor may already have been skipped
    // while the current batch was being serialized for insertRecords().
    if (it != batches.end() && index < it->second.end)
      it->second.dirty = std::min(it->second.dirty, index);
  }

  bool acknowledge(const void *sensor, int position, uint16_t &cursor) {
    const auto it = batches.find(sensor);
    if (it != batches.end() && it->second.dirty != std::numeric_limits<int>::max()) {
      cursor = static_cast<uint16_t>(std::min<int>(cursor, it->second.dirty));
      return false;
    }
    cursor = static_cast<uint16_t>(std::clamp(position, 0, int{UINT16_MAX}));
    if (it != batches.end() && position >= it->second.end) batches.erase(it);
    return true;
  }
};
