#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

#include "ICEConfig.hpp"

// Rendezvous state lives on one server, so a negotiation must finish where
// it started. Normally only the first server is used. When it struggles, the
// other servers join the same negotiation and whichever one completes the
// description exchange first carries the rest of it.

// The custom server (if any) first, then the label-hashed public server, then
// the next public name. The bundled public list alternates between two
// machines, so the next name is always on the other machine.
inline std::string rendezvousServerHost(std::string_view host) {
    if (host.ends_with('.')) host.remove_suffix(1);
    std::string normalized(host);
    std::transform(normalized.begin(), normalized.end(), normalized.begin(),
        [](unsigned char c) { return c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c; });
    // These bundled names share rendezvous state. Keep the selected spelling
    // for DNS/TLS, but never race an alias against the same service.
    if (normalized == "azure.juggluco.nl") return "a.juggluco.nl";
    if (normalized == "ipv6.juggluco.nl") return "mx.juggluco.nl";
    return normalized;
}

inline std::vector<RendezvousEndpoint> buildRendezvousEndpoints(
        const RendezvousEndpoint &custom,
        std::span<const std::string_view> publicHosts, size_t hashed,
        uint16_t publicPort) {
    std::vector<RendezvousEndpoint> endpoints;
    const auto add = [&](RendezvousEndpoint endpoint) {
        if (endpoint.host.empty()) return;
        const auto server = rendezvousServerHost(endpoint.host);
        const bool known = std::any_of(endpoints.begin(), endpoints.end(),
            [&](const RendezvousEndpoint &other) {
                return other.port == endpoint.port && rendezvousServerHost(other.host) == server;
            });
        if (!known) endpoints.push_back(std::move(endpoint));
    };
    add(custom);
    if (!publicHosts.empty()) {
        const size_t count = publicHosts.size();
        add({std::string(publicHosts[hashed % count]), publicPort, true});
        add({std::string(publicHosts[(hashed + 1) % count]), publicPort, true});
    }
    return endpoints;
}

enum class RendezvousAttemptResult : int {
    Running = 0,
    // This server delivered the peer's description and took ours.
    Won,
    // Another server or the LAN delivered first; this attempt stopped.
    Lost,
    // This attempt gave up (rejected generation, agent replaced, cancelled).
    Failed,
};

struct RendezvousAttempt {
    std::shared_ptr<std::atomic_bool> superseded =
        std::make_shared<std::atomic_bool>(false);
    // Requests that failed before the server could hold them: DNS, TCP, TLS.
    std::atomic<int> connectFailures{0};
    std::atomic<RendezvousAttemptResult> result{RendezvousAttemptResult::Running};

    bool isSuperseded() const {
        return superseded->load(std::memory_order_acquire);
    }
};

constexpr int64_t rendezvousQuietEscalationMilliseconds = 30000;

// More servers join once the first one fails to connect, gives up, or has not
// finished the exchange within the quiet period, or when the last negotiation
// got past the exchange but never connected.
constexpr bool shouldEscalateRendezvous(bool alreadyEscalated,
                                        int primaryConnectFailures,
                                        bool primaryFinishedWithoutWin,
                                        int64_t elapsedMilliseconds) {
    return alreadyEscalated || primaryConnectFailures > 0 ||
           primaryFinishedWithoutWin ||
           elapsedMilliseconds >= rendezvousQuietEscalationMilliseconds;
}

static_assert(!shouldEscalateRendezvous(false, 0, false, 29999));
static_assert(shouldEscalateRendezvous(true, 0, false, 0));
static_assert(shouldEscalateRendezvous(false, 1, false, 0));
static_assert(shouldEscalateRendezvous(false, 0, true, 0));
static_assert(shouldEscalateRendezvous(false, 0, false, 30000));

struct RendezvousRaceOutcome {
    std::optional<size_t> winner;
    bool escalated = false;
};

// run(index, attempt) performs the exchange on endpoint `index` and returns
// its result; it must return promptly once attempt.isSuperseded() or
// active() turns false. Every attempt has ended when this returns.
template <class Run, class Active, class Now, class Sleep, class Escalated>
RendezvousRaceOutcome raceRendezvous(size_t count, bool startEscalated, Run run,
                                     Active active, Now now, Sleep sleep,
                                     Escalated onEscalate) {
    RendezvousRaceOutcome outcome;
    if (count == 0) return outcome;
    std::vector<std::unique_ptr<RendezvousAttempt>> attempts;
    for (size_t index = 0; index < count; ++index)
        attempts.push_back(std::make_unique<RendezvousAttempt>());
    std::vector<std::jthread> threads;
    size_t started = 0;
    const auto startUpTo = [&](size_t limit) {
        for (; started < limit; ++started) {
            RendezvousAttempt *attempt = attempts[started].get();
            threads.emplace_back([&run, attempt, index = started] {
                attempt->result.store(run(index, *attempt),
                                      std::memory_order_release);
            });
        }
    };
    const int64_t begin = now();
    outcome.escalated = startEscalated && count > 1;
    startUpTo(outcome.escalated ? count : 1);
    while (true) {
        bool allEnded = true;
        for (size_t index = 0; index < started; ++index) {
            const auto result = attempts[index]->result.load(std::memory_order_acquire);
            if (result == RendezvousAttemptResult::Won) {
                outcome.winner = index;
                break;
            }
            if (result == RendezvousAttemptResult::Running) allEnded = false;
        }
        if (outcome.winner || !active()) break;
        if (started < count) {
            const auto primary = attempts[0]->result.load(std::memory_order_acquire);
            if (shouldEscalateRendezvous(
                    false, attempts[0]->connectFailures.load(std::memory_order_acquire),
                    primary != RendezvousAttemptResult::Running &&
                        primary != RendezvousAttemptResult::Won,
                    now() - begin)) {
                outcome.escalated = true;
                onEscalate();
                startUpTo(count);
                continue;
            }
        } else if (allEnded) {
            break;
        }
        sleep();
    }
    for (size_t index = 0; index < started; ++index)
        if (!outcome.winner || *outcome.winner != index)
            attempts[index]->superseded->store(true, std::memory_order_release);
    threads.clear();
    return outcome;
}
