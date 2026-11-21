#pragma once

#include <algorithm>
#include <atomic>
#include <cstdint>

// Only accessed under the poll write lock. The file-backed cursor survives a restart.
// The revision prevents a network response for an older payload from undoing a rewind.
class NightscoutHistoryProgress {
  // Restart also revisits the upload window so persisted sensor rewinds are found
  // even if the previous process exited before revisiting an older sensor.
  static inline std::atomic<bool> revisitWindow{true};
  uint64_t revision = 0;
  bool recentInvalidated = false;

public:
  struct Batch {
    uint16_t start;
    uint64_t revision;
    bool clearRecent;
  };

  void rewind(uint16_t index, uint16_t &cursor) {
    lowerCursor(index, cursor);
    ++revision;
    recentInvalidated = true;
    revisitWindow.store(true);
  }

  static bool takeWindowRevisit() { return revisitWindow.exchange(false); }

  Batch begin(const uint16_t &cursor) {
    const Batch batch{__atomic_load_n(&cursor, __ATOMIC_RELAXED), revision, recentInvalidated};
    recentInvalidated = false;
    return batch;
  }

  bool changed(const Batch &batch) const { return batch.revision != revision; }

  static void lowerCursor(uint16_t index, uint16_t &cursor) {
    auto current = __atomic_load_n(&cursor, __ATOMIC_RELAXED);
    while (index < current &&
           !__atomic_compare_exchange_n(&cursor, &current, index, false,
                                        __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {}
  }

  void acknowledge(const Batch &batch, uint16_t next, uint16_t &cursor) const {
    if (changed(batch)) return;
    auto current = __atomic_load_n(&cursor, __ATOMIC_RELAXED);
    while (current < next &&
           !__atomic_compare_exchange_n(&cursor, &current, next, false,
                                        __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {}
  }
};
