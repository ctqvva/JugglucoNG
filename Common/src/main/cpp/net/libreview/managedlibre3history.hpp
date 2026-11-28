// LibreView history for a Libre 3 read by the open Kotlin driver.
//
// The removed proprietary driver kept the sensor's five-minute history in the
// native record's history array, and the Libre 3 upload sent those entries with
// their lifeCount as record number. The Kotlin driver mirrors only minutes into
// a managed stream shell, whose history geometry is the generic 15 minutes. So
// the upload takes one stored minute per five-minute lifeCount slot instead:
// the slot's own minute, which is the sensor's clinical minute or, where only
// history reached the phone, its five-minute value at that same lifeCount.
// Record numbers stay on the sensor's lifeCount grid, so entries the removed
// driver already sent for a migrated sensor carry the same numbers.
#pragma once

#include <algorithm>
#include <cstdint>

namespace managed_libre3_history {

inline constexpr int slotMinutes = 5;
// Info::libreviewnotsend, the slot cursor, is 14 bits.
inline constexpr int maxSlots = (1 << 14) - 1;
// A missing slot this close to the newest stored minute may still be filled by
// the reconnect backfill, so it holds the cursor instead of being skipped.
inline constexpr uint32_t backfillHoldSeconds = 30 * 60;

inline uint32_t slotTime(uint32_t activation, int slot) {
  return activation + static_cast<uint32_t>(slot) * slotMinutes * 60u;
}

/* One past the last slot covered by the newest stored minute. */
inline int endSlot(uint32_t activation, uint32_t newest) {
  if (!activation || newest < activation)
    return 0;
  const uint32_t slots = (newest - activation) / (slotMinutes * 60u) + 1u;
  return static_cast<int>(std::min<uint32_t>(slots, maxSlots));
}

/*
 * Visits slots [from, end) in order. minuteAt(lifeCount) returns the stored
 * minute or nullptr; emit(lifeCount, minute) sends it. A missing slot is passed
 * over once it is older than backfillHoldSeconds, and stops the walk before
 * that. Returns the cursor for the next upload: one past the last slot decided.
 */
template <class MinuteAt, class Emit>
int collect(int from, int end, uint32_t activation, uint32_t newest,
            MinuteAt &&minuteAt, Emit &&emit) {
  int slot = std::max(from, 0);
  for (; slot < end; ++slot) {
    const int lifeCount = slot * slotMinutes;
    if (const auto *minute = minuteAt(lifeCount)) {
      emit(lifeCount, minute);
      continue;
    }
    if (slotTime(activation, slot) + backfillHoldSeconds > newest)
      break;
  }
  return slot;
}

} // namespace managed_libre3_history
