#pragma once

#include <cstdint>
#include <string>
#include <string_view>

struct ICEConfigSnapshot {
    std::string rendezvousHost;
    uint16_t rendezvousPort{6789};
    bool useTurnForStun{false};
    bool verifyRendezvousCertificate{true};
    bool useLocalDiscovery{true};
};

ICEConfigSnapshot currentICEConfig();
void updateICEConfig(std::string rendezvousHost, uint16_t rendezvousPort,
                     bool useTurnForStun, bool verifyRendezvousCertificate,
                     bool useLocalDiscovery);

struct RendezvousEndpoint {
    std::string host;
    uint16_t port;
    bool verifyCertificate;
};

RendezvousEndpoint resolveRendezvousEndpoint(std::string_view label);
