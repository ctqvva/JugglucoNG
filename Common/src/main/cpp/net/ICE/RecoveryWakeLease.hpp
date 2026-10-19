#pragma once

#include <cstdint>
#include <mutex>

uint64_t acquireCloneRecoveryWake();
void releaseCloneRecoveryWake(uint64_t token);

// One bounded platform lease across replacement-agent creation. Retries do not
// refresh it, and a late success from the superseded agent cannot release it.
class RecoveryWakeLease {
    std::mutex mutex;
    uint64_t token = 0;
    uint64_t previousGeneration = 0;
    uint64_t (*acquire)();
    void (*release)(uint64_t);
    void clear() {
        if (token) release(token);
        token = 0;
    }
public:
    RecoveryWakeLease(uint64_t (*begin)() = acquireCloneRecoveryWake,
                      void (*end)(uint64_t) = releaseCloneRecoveryWake)
        : acquire(begin), release(end) {}
    ~RecoveryWakeLease() { cancel(); }

    template<class Request>
    bool restart(uint64_t generation, bool protect, Request request) {
        const std::lock_guard<std::mutex> lock(mutex);
        if (!request()) return false;
        if (protect && !token) {
            previousGeneration = generation;
            token = acquire();
        }
        return true;
    }
    void connected(uint64_t generation) {
        const std::lock_guard<std::mutex> lock(mutex);
        if (generation > previousGeneration) clear();
    }
    void cancel() {
        const std::lock_guard<std::mutex> lock(mutex);
        clear();
    }
};
