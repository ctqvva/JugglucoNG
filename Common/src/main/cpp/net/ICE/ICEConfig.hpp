#pragma once

#include <cstdint>
#include <string>
#include <string_view>

struct ICEConfigSnapshot {
    std::string rendezvousHost;
    uint16_t rendezvousPort{6789};
    bool useTurnForStun{false};
};

ICEConfigSnapshot currentICEConfig();
void updateICEConfig(std::string rendezvousHost, uint16_t rendezvousPort,
                     bool useTurnForStun);

struct RendezvousEndpoint {
    std::string host;
    uint16_t port;
};

RendezvousEndpoint resolveRendezvousEndpoint(std::string_view label);
