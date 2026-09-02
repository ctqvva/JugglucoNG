#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <string_view>

#include "libjuice/include/juice/juice.h"

class LocalICESignalSession {
public:
    LocalICESignalSession(int allindex, juice_agent_t *agent,
                          uint64_t agentGeneration, std::string_view label,
                          bool side, const std::array<uint8_t, 16> &key,
                          std::string_view localGeneration);
    ~LocalICESignalSession();

    LocalICESignalSession(const LocalICESignalSession &) = delete;
    LocalICESignalSession &operator=(const LocalICESignalSession &) = delete;

    bool start();
    void stop();
    void publishDescription(std::string_view description);
    void publishCandidate(std::string_view candidate);
    void publishGatheringDone();
    void markConnected();
    bool hasAuthenticatedPeer() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};

std::shared_ptr<LocalICESignalSession> startLocalICESignal(
    int allindex, juice_agent_t *agent, uint64_t agentGeneration,
    std::string_view label, bool side, const std::array<uint8_t, 16> &key,
    std::string_view localGeneration);
