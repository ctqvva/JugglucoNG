// Runs the production answer-upload loop with fake HTTP and a held backup poll.
// The runner inserts putDescription() from ICE.cpp at PUT_DESCRIPTION below.
#include "net/ICE/RendezvousFailover.hpp"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <functional>

#define LOGGERICE(...) ((void)0)
struct juice_agent {};
struct ICEConnect {
    std::atomic_bool endConnect{false}, remoteDescriptionWasLocal{false};
    std::atomic_bool remoteDescriptionSet{true}, rendezvousEscalated{false};
    bool current = true;
    char sdp[64] = "local-description-for-test";
    int sdplen = 26;
    bool isCurrentAgent(juice_agent *, uint64_t = 0) { return current; }
    uint64_t currentAgentGeneration() { return 1; }
    void beginRendezvous() {}
};
ICEConnect connection;
ICEConnect *connections[] = {&connection};
int restartCalls = 0, requests = 0, failures = 0;
bool restartRejectedNegotiation(ICEConnect *con, juice_agent *, uint64_t, int, const char *) {
    ++restartCalls;
    con->endConnect = true;
    return true;
}
struct CreateAgentData {
    char bytes[64]{};
    CreateAgentData(std::string_view, bool, const char *, int) {}
    const char *data() const { return bytes; }
    int size() const { return sizeof(bytes); }
};
struct BackDescription { uint32_t was; char description[1]; };
constexpr int givefirst = 0;
constexpr int maxconnectionunused = 86400;
std::string_view description = "/description";
uint32_t getConnectTime(int) { return 0; }
void setConnectTime(int, time_t) {}
bool waitForAttempt(ICEConnect *con, juice_agent *, const RendezvousAttempt &attempt, int) {
    return !con->endConnect.load() && !attempt.isSuperseded();
}
RendezvousAttemptResult stoppedAttempt(const RendezvousAttempt &attempt) {
    return attempt.isSuperseded() ? RendezvousAttemptResult::Lost : RendezvousAttemptResult::Failed;
}
bool applyRemoteDescription(int, juice_agent *, uint64_t, const char *, bool) { return true; }
int rendezvousLongPollOptions(ICEConnect *, const RendezvousEndpoint &, const RendezvousAttempt *) {
    return 0;
}
std::function<std::pair<std::vector<char>, int>()> response;
struct ContextHTTPS {
    static ContextHTTPS &getContext() { static ContextHTTPS context; return context; }
    std::pair<std::vector<char>, int> putRequest(
            std::string_view, uint16_t, std::string_view, std::span<const char>, std::string_view, int) {
        ++requests;
        return response();
    }
};

/* PUT_DESCRIPTION */

void check(bool condition, const char *what) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        ++failures;
    }
}
void reset(int code, bool empty = false) {
    connection.endConnect = false;
    connection.remoteDescriptionWasLocal = false;
    connection.remoteDescriptionSet = true;
    connection.rendezvousEscalated = false;
    connection.current = true;
    restartCalls = requests = 0;
    response = [=] {
        return std::pair{code == 200 && !empty ? std::vector<char>(64) : std::vector<char>{}, code};
    };
}
juice_agent agent;
const RendezvousEndpoint endpoint{"accepted-offer-server", 6789, true};

void testRejectedAnswerStopsHeldBackup() {
    reset(400);
    std::atomic<int64_t> clock{0};
    std::atomic_bool backupStarted{false}, backupStopped{false};
    const auto outcome = raceRendezvous(2, true,
        [&](size_t index, RendezvousAttempt &attempt) {
            if (index == 0) {
                while (!backupStarted.load()) std::this_thread::yield();
                return putDescription(0, &agent, "long-enough-label", 1, endpoint, attempt);
            }
            backupStarted = true;
            while (!attempt.isSuperseded())
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            backupStopped = true;
            return RendezvousAttemptResult::Lost;
        }, [&] { return !connection.endConnect.load() && clock.load() < 120000; },
        [&] { return clock.load(); },
        [&] { clock += 250; std::this_thread::sleep_for(std::chrono::milliseconds(1)); }, [] {});
    check(restartCalls == 1 && requests == 1, "rejected answer restarts immediately");
    check(connection.rendezvousEscalated && connection.endConnect,
          "rejected answer preserves escalation for the replacement agent");
    check(!outcome.winner && backupStopped, "rejected answer cancels and joins the held backup");
    check(clock < 120000, "rejected answer does not wait for the backup poll deadline");
}

void testOfferRejectionCanFailOver() {
    reset(400);
    RendezvousAttempt attempt;
    const auto result = putDescription(0, &agent, "long-enough-label", 0, endpoint, attempt);
    check(result == RendezvousAttemptResult::Failed && restartCalls == 0 && !connection.endConnect,
          "rejected offer leaves other servers in the same negotiation");
}

void testAnswerRetryAndSuccess() {
    for (const int code : {-1, 500, 200}) {
        reset(code, true);
        RendezvousAttempt attempt;
        const auto result = putDescription(0, &agent, "long-enough-label", 1, endpoint, attempt);
        check(result == RendezvousAttemptResult::Failed && requests == 3 && restartCalls == 1 &&
                  connection.rendezvousEscalated,
              "transport failures and empty answers retain the three-try restart");
    }
    reset(200);
    RendezvousAttempt attempt;
    const auto result = putDescription(0, &agent, "long-enough-label", 1, endpoint, attempt);
    check(result == RendezvousAttemptResult::Won && requests == 1 && restartCalls == 0 &&
              !connection.rendezvousEscalated,
          "healthy answer completes without restarting");
}

void testLateRejectionCannotRestartStoppedAttempt() {
    for (int stop = 0; stop < 3; ++stop) {
        reset(400);
        RendezvousAttempt attempt;
        response = [&] {
            if (stop == 0) attempt.superseded->store(true);
            if (stop == 1) connection.endConnect = true;
            if (stop == 2) connection.current = false;
            return std::pair{std::vector<char>{}, 400};
        };
        putDescription(0, &agent, "long-enough-label", 1, endpoint, attempt);
        check(restartCalls == 0 && !connection.rendezvousEscalated,
              "late HTTP rejection cannot restart a cancelled or stale attempt");
    }
}

int main() {
    testRejectedAnswerStopsHeldBackup();
    testOfferRejectionCanFailOver();
    testAnswerRetryAndSuccess();
    testLateRejectionCannotRestartStoppedAttempt();
    if (failures) return EXIT_FAILURE;
    std::puts("rendezvous description upload: all checks passed");
    return EXIT_SUCCESS;
}
