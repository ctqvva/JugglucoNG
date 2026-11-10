#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <thread>

// A generation-owned transport adapter for libjuice's UDP TURN interface.
// Only TURN control/data frames cross this loopback socket. The remote link
// uses RFC 8656 TCP framing, optionally with verified TLS. ICE, authentication,
// allocations and permissions remain in the existing libjuice implementation.
class TurnStreamRelay {
    std::string host;
    uint16_t port;
    bool tls;
    int datagramSocket = -1;
    uint16_t localPort = 0;
    std::shared_ptr<std::atomic_bool> cancelled = std::make_shared<std::atomic_bool>(false);
    std::jthread worker;
    TurnStreamRelay(std::string host, uint16_t port, bool tls);
    void run();
public:
    static std::unique_ptr<TurnStreamRelay> start(std::string host, uint16_t port, bool tls);
    ~TurnStreamRelay();
    uint16_t loopbackPort() const { return localPort; }
    void cancel() { cancelled->store(true, std::memory_order_release); }
};
