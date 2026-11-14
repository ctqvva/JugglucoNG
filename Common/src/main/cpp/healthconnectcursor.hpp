#pragma once

#include <cstdint>
#include <mutex>
#include <unordered_map>

// The persisted cursor alone cannot detect a gap filled inside an in-flight
// chunk: that write need not move the cursor at all. Track a process-local
// revision as well, without changing the on-disk sensor layout.
namespace healthconnect {
inline std::mutex cursorMutex;
inline std::unordered_map<uint16_t *, uint32_t> revisions;

inline uint64_t snapshot(uint16_t *cursor, uint16_t first, uint16_t end) {
  std::lock_guard lock(cursorMutex);
  uint16_t start = __atomic_load_n(cursor, __ATOMIC_RELAXED);
  if (!start) {
    start = first;
    __atomic_store_n(cursor, start, __ATOMIC_RELAXED);
  }
  return (uint64_t(revisions[cursor]) << 32) | (uint64_t(end) << 16) | start;
}

inline bool advance(uint16_t *cursor, uint64_t token, uint16_t next) {
  std::lock_guard lock(cursorMutex);
  if (revisions[cursor] != uint32_t(token >> 32)) return false;
  uint16_t expected = uint16_t(token);
  const uint16_t end = uint16_t(token >> 16);
  if (next < expected || next > end) return false;
  // Compare with the exported chunk's start, never a freshly loaded cursor.
  return __atomic_compare_exchange_n(cursor, &expected, next, false,
                                      __ATOMIC_RELAXED, __ATOMIC_RELAXED);
}

inline void gapFilled(uint16_t *cursor, uint16_t position) {
  std::lock_guard lock(cursorMutex);
  ++revisions[cursor];
  const uint16_t current = __atomic_load_n(cursor, __ATOMIC_RELAXED);
  if (position < current) __atomic_store_n(cursor, position, __ATOMIC_RELAXED);
}

inline void reset(uint16_t *cursor, uint16_t position) {
  std::lock_guard lock(cursorMutex);
  ++revisions[cursor];
  __atomic_store_n(cursor, position, __ATOMIC_RELAXED);
}
} // namespace healthconnect
