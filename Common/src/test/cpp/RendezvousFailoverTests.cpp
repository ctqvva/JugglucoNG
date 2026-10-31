// Rendezvous failover: server order and the escalation race, with simulated
// servers and a simulated clock. No network.
#include "net/ICE/RendezvousFailover.hpp"

#include <array>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <thread>
#include <vector>

namespace {

int failures = 0;

void check(bool condition, const char *what) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        ++failures;
    }
}

constexpr std::array<std::string_view, 4> publicHosts{
    "a.example", "mx.example", "azure.example", "ipv6.example"};

void testServerOrder() {
    auto list = buildRendezvousEndpoints({"", 6789, true}, publicHosts, 2, 6789);
    check(list.size() == 2, "public only: hashed plus one");
    check(list[0].host == "azure.example", "public only: hashed first");
    check(list[1].host == "ipv6.example", "public only: next name second");

    list = buildRendezvousEndpoints({"", 6789, true}, publicHosts, 3, 6789);
    check(list[1].host == "a.example", "public only: next name wraps");

    list = buildRendezvousEndpoints({"own.example", 7000, false}, publicHosts, 0, 6789);
    check(list.size() == 3, "custom: custom plus two public");
    check(list[0].host == "own.example" && list[0].port == 7000 &&
              !list[0].verifyCertificate,
          "custom: first, with its own port and verification");
    check(list[1].host == "a.example" && list[1].verifyCertificate,
          "custom: public servers verify certificates");

    list = buildRendezvousEndpoints({"a.example", 6789, true}, publicHosts, 0, 6789);
    check(list.size() == 2, "custom equal to a public server is not repeated");
}

void testBundledServerAliases() {
    constexpr std::array<std::string_view, 4> hosts{
        "a.juggluco.nl", "mx.juggluco.nl", "azure.juggluco.nl", "ipv6.juggluco.nl"};
    for (const auto custom : hosts) {
        for (size_t hashed = 0; hashed < hosts.size(); ++hashed) {
            const auto list = buildRendezvousEndpoints(
                {std::string(custom), 6789, false}, hosts, hashed, 6789);
            check(list.size() == 2, "bundled custom server has only one attempt per machine");
            check(list[0].host == custom && !list[0].verifyCertificate,
                  "alias dedupe preserves the configured hostname and TLS policy");
        }
    }
    const auto mixedCase = buildRendezvousEndpoints(
        {"AZURE.Juggluco.NL.", 6789, false}, hosts, 0, 6789);
    check(mixedCase.size() == 2 && mixedCase[0].host == "AZURE.Juggluco.NL." &&
              mixedCase[1].host == "mx.juggluco.nl",
          "DNS case and terminal dot do not create another attempt on the same machine");
    const auto otherPort = buildRendezvousEndpoints(
        {"azure.juggluco.nl", 7000, false}, hosts, 0, 6789);
    check(otherPort.size() == 3 && otherPort[0].port == 7000,
          "different ports remain independent rendezvous services");
}

// A simulated server: what it does once its attempt starts.
using Behaviour = std::function<RendezvousAttemptResult(RendezvousAttempt &)>;

std::atomic<int64_t> clockMilliseconds{0};

void advance() {
    clockMilliseconds += 250;
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
}

// Waits on the simulated clock; false when this attempt was stopped first.
bool waitFor(RendezvousAttempt &attempt, int64_t milliseconds) {
    const int64_t until = clockMilliseconds + milliseconds;
    while (clockMilliseconds < until) {
        if (attempt.isSuperseded()) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return !attempt.isSuperseded();
}

Behaviour winsAfter(int64_t milliseconds) {
    return [milliseconds](RendezvousAttempt &attempt) {
        return waitFor(attempt, milliseconds) ? RendezvousAttemptResult::Won
                                              : RendezvousAttemptResult::Lost;
    };
}

Behaviour holds() {
    return [](RendezvousAttempt &attempt) {
        waitFor(attempt, 1'000'000'000);
        return RendezvousAttemptResult::Lost;
    };
}

Behaviour cannotConnect() {
    return [](RendezvousAttempt &attempt) {
        attempt.connectFailures.fetch_add(1);
        waitFor(attempt, 1'000'000'000);
        return RendezvousAttemptResult::Lost;
    };
}

Behaviour rejects() {
    return [](RendezvousAttempt &) { return RendezvousAttemptResult::Failed; };
}

struct Race {
    RendezvousRaceOutcome outcome;
    std::vector<int> started;
    std::vector<RendezvousAttemptResult> results;
    int escalations = 0;
};

Race race(std::vector<Behaviour> servers, bool startEscalated,
          std::function<bool()> active = [] { return true; }) {
    clockMilliseconds = 0;
    Race result;
    result.started.assign(servers.size(), 0);
    result.results.assign(servers.size(), RendezvousAttemptResult::Running);
    std::vector<std::atomic<int>> started(servers.size());
    result.outcome = raceRendezvous(
        servers.size(), startEscalated,
        [&](size_t index, RendezvousAttempt &attempt) {
            started[index] = 1;
            const auto outcome = servers[index](attempt);
            result.results[index] = outcome;
            return outcome;
        },
        active, [] { return clockMilliseconds.load(); }, advance,
        [&] { ++result.escalations; });
    for (size_t index = 0; index < servers.size(); ++index)
        result.started[index] = started[index];
    return result;
}

void testHealthyPrimaryStaysAlone() {
    const auto result = race({winsAfter(2000), winsAfter(0)}, false);
    check(result.outcome.winner == 0, "healthy primary wins");
    check(!result.outcome.escalated && result.escalations == 0,
          "healthy primary does not escalate");
    check(!result.started[1], "fallback never contacted while primary is healthy");
}

void testConnectFailureEscalatesAtOnce() {
    const auto result = race({cannotConnect(), winsAfter(1000)}, false);
    check(result.outcome.winner == 1, "fallback wins when primary cannot connect");
    check(result.outcome.escalated && result.escalations == 1, "escalated once");
    check(clockMilliseconds < rendezvousQuietEscalationMilliseconds,
          "connect failure escalates without waiting for the quiet period");
    check(result.results[0] == RendezvousAttemptResult::Lost,
          "unreachable primary attempt is stopped");
}

void testQuietPrimaryEscalatesAfterQuietPeriod() {
    const auto result = race({holds(), winsAfter(500)}, false);
    check(result.outcome.winner == 1, "fallback wins when primary stays quiet");
    check(result.outcome.escalated, "quiet primary escalates");
    check(clockMilliseconds >= rendezvousQuietEscalationMilliseconds,
          "quiet escalation waits for the quiet period");
    check(result.results[0] == RendezvousAttemptResult::Lost,
          "held primary attempt is stopped after fallback wins");
}

void testRejectedPrimaryEscalates() {
    const auto result = race({rejects(), winsAfter(0)}, false);
    check(result.outcome.winner == 1, "fallback wins when primary rejects");
}

void testEscalatedStartUsesEveryServer() {
    const auto result = race({winsAfter(500), holds(), holds()}, true);
    check(result.outcome.winner == 0, "primary can still win when escalated");
    check(result.started[1] && result.started[2], "escalated start contacts all servers");
    check(result.escalations == 0, "escalated start does not report a new escalation");
    check(result.results[1] == RendezvousAttemptResult::Lost &&
              result.results[2] == RendezvousAttemptResult::Lost,
          "losing servers are stopped");
}

void testInactiveStopsEverything() {
    std::atomic<bool> active{true};
    std::thread lan([&] {
        while (clockMilliseconds < 1000) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        active = false;
    });
    const auto result = race({holds(), holds()}, true, [&] { return active.load(); });
    lan.join();
    check(!result.outcome.winner, "LAN takeover leaves no rendezvous winner");
    check(result.results[0] == RendezvousAttemptResult::Lost &&
              result.results[1] == RendezvousAttemptResult::Lost,
          "LAN takeover stops every attempt");
}

void testEveryServerFailing() {
    const auto result = race({rejects(), rejects()}, false);
    check(!result.outcome.winner, "no winner when every server fails");
    check(result.outcome.escalated, "failing primary still tried the fallback");
}

void testSingleServerFailing() {
    const auto result = race({rejects()}, false);
    check(!result.outcome.winner && !result.outcome.escalated,
          "single server failure ends without escalation");
}

}  // namespace

int main() {
    testServerOrder();
    testBundledServerAliases();
    testHealthyPrimaryStaysAlone();
    testConnectFailureEscalatesAtOnce();
    testQuietPrimaryEscalatesAfterQuietPeriod();
    testRejectedPrimaryEscalates();
    testEscalatedStartUsesEveryServer();
    testInactiveStopsEverything();
    testEveryServerFailing();
    testSingleServerFailing();
    if (failures) {
        std::fprintf(stderr, "%d rendezvous failover check(s) failed\n", failures);
        return EXIT_FAILURE;
    }
    std::puts("rendezvous failover: all checks passed");
    return EXIT_SUCCESS;
}
