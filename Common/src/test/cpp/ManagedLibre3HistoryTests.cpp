// Host tests for LibreView history of a Libre 3 read by the Kotlin driver
// (net/libreview/managedlibre3history.hpp): which five-minute slots are sent,
// under which record lifeCount, and where the cursor stops.
#include "net/libreview/managedlibre3history.hpp"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <vector>

using namespace managed_libre3_history;

#define CHECK(cond)                                                                      \
    do {                                                                                 \
        if (!(cond)) {                                                                   \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            std::exit(1);                                                                \
        }                                                                                \
    } while (0)

namespace {

constexpr uint32_t activation = 1'790'000'000u;

struct Minute { uint32_t time; int mgdl; };

/* Stored minutes by sensor lifeCount, as the driver publishes them. */
struct Shell {
    std::map<int, Minute> minutes;
    void store(int from, int to) {
        for (int life = from; life <= to; ++life) minutes[life] = {activation + life * 60u, 100 + life % 50};
    }
    void erase(int from, int to) {
        for (int life = from; life <= to; ++life) minutes.erase(life);
    }
    uint32_t newest() const { return minutes.empty() ? 0u : minutes.rbegin()->second.time; }
};

struct Upload {
    std::vector<int> sent;
    int next;
};

Upload upload(const Shell &shell, int from) {
    Upload result{};
    const uint32_t newest = shell.newest();
    result.next = collect(from, endSlot(activation, newest), activation, newest,
        [&](int life) -> const Minute * {
            auto it = shell.minutes.find(life);
            return it == shell.minutes.end() ? nullptr : &it->second;
        },
        [&](int life, const Minute *minute) {
            CHECK(minute->time == activation + life * 60u);
            result.sent.push_back(life);
        });
    return result;
}

void slotsFollowTheSensorGrid() {
    CHECK(endSlot(0, activation + 600) == 0);
    CHECK(endSlot(activation, activation - 1) == 0);
    CHECK(endSlot(activation, activation) == 1);
    CHECK(endSlot(activation, activation + 299) == 1);
    CHECK(endSlot(activation, activation + 300) == 2);
    // A whole 15-day Libre 3 Plus fits the 14-bit cursor; nothing runs past it.
    CHECK(endSlot(activation, activation + 15 * 24 * 3600) == 15 * 24 * 12 + 1);
    CHECK(endSlot(activation, activation + 200u * 24 * 3600) == maxSlots);
}

void everyFifthMinuteIsSentOnceUnderItsLifeCount() {
    Shell shell;
    shell.store(60, 200);
    const auto first = upload(shell, 0);
    // Warm-up minutes were never stored; they are long past and do not hold the cursor.
    CHECK(first.sent.front() == 60);
    CHECK(first.sent.back() == 200);
    for (size_t i = 1; i < first.sent.size(); ++i) CHECK(first.sent[i] - first.sent[i - 1] == 5);
    CHECK(first.next == 41);

    shell.store(201, 212);
    const auto second = upload(shell, first.next);
    CHECK((second.sent == std::vector<int>{205, 210}));
    CHECK(second.next == 43);
    CHECK(upload(shell, second.next).sent.empty());
}

void aRecentGapWaitsForTheBackfill() {
    Shell shell;
    shell.store(60, 300);
    shell.erase(281, 296); // link lost; the reconnect backfill has not landed yet
    const auto held = upload(shell, 0);
    CHECK(held.sent.back() == 280);
    CHECK(held.next == 57); // slot of lifeCount 285: waits
    shell.store(281, 296); // backfill arrives
    const auto filled = upload(shell, held.next);
    CHECK((filled.sent == std::vector<int>{285, 290, 295, 300}));
    CHECK(filled.next == 61);
}

void anOldGapIsPassedOver() {
    Shell shell;
    shell.store(60, 400);
    shell.erase(100, 130); // sensor error, never coming back
    const auto result = upload(shell, 0);
    for (int life : result.sent) CHECK(life < 100 || life > 130);
    CHECK(result.sent.back() == 400);
    CHECK(result.next == 81);
}

} // namespace

int main() {
    slotsFollowTheSensorGrid();
    everyFifthMinuteIsSentOnceUnderItsLifeCount();
    aRecentGapWaitsForTheBackfill();
    anOldGapIsPassedOver();
    std::puts("managed Libre 3 LibreView history: ok");
    return 0;
}
