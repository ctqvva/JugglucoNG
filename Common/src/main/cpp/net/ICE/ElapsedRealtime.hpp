#pragma once

#include <chrono>
#include <cstdint>
#include <time.h>

inline int64_t elapsedRealtimeMilliseconds() noexcept {
#ifdef CLOCK_BOOTTIME
    timespec now{};
    if(clock_gettime(CLOCK_BOOTTIME,&now)==0)
        return static_cast<int64_t>(now.tv_sec)*1000+now.tv_nsec/1000000;
#endif
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
