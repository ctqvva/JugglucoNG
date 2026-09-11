#pragma once

#include "ElapsedRealtime.hpp"
#include "logs.hpp"

// CLOCK_BOOTTIME advances during system suspend; CLOCK_MONOTONIC does not.
// Compare deltas on the same phone. A growing boot-minus-monotonic gap is
// suspend time, whereas an equally long delta in both requires investigating
// scheduling or application waits. This instrumentation never holds a wake lock.
inline void traceIceRecovery(int host, uint64_t generation, const char *stage) {
#ifndef NOLOG
    timespec active{};
    if (clock_gettime(CLOCK_MONOTONIC, &active) != 0) return;
    const auto mono = static_cast<int64_t>(active.tv_sec) * 1000 + active.tv_nsec / 1000000;
    LOGGER("ICE recovery: host=%d gen=%llu stage=%s boot_ms=%lld mono_ms=%lld\n",
           host, static_cast<unsigned long long>(generation), stage,
           static_cast<long long>(elapsedRealtimeMilliseconds()),
           static_cast<long long>(mono));
#endif
}
