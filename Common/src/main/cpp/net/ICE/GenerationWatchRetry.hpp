#pragma once

#include <algorithm>

enum class GenerationWatchResult { Continue, Retry, Stop };

// Keep the same watcher (and its observed peer identity) across outages.
// active/wait must honor session replacement and cancellation, including
// during backoff. Unsupported endpoints still stop via the request result.
template<class Active, class Request, class Wait>
void runGenerationWatchRequests(Active active, Request request, Wait wait) {
    int delaySeconds = 2;
    while (active()) {
        const auto result = request();
        if (!active() || result == GenerationWatchResult::Stop) return;
        if (result == GenerationWatchResult::Retry) {
            if (!wait(delaySeconds)) return;
            delaySeconds = std::min(delaySeconds * 2, 30);
        } else {
            delaySeconds = 2;
        }
    }
}
