// Host tests for the Nightscout upload pass (net/watchserver/nightscoutbacklog.hpp).
//
// The pass is driven the way uploader.cpp drives it, against a server that records every
// request, so what is checked is the order things reach Nightscout in and what a restart, a
// refusal or a flood of live wakes does to the history still waiting.
#include "net/watchserver/nightscoutbacklog.hpp"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <map>
#include <utility>
#include <vector>

using namespace nightscout_backlog;

#define CHECK(cond)                                                                      \
    do {                                                                                 \
        if (!(cond)) {                                                                   \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            std::exit(1);                                                                \
        }                                                                                \
    } while (0)

namespace {

constexpr int interval = 60;

struct Sensor {
    std::vector<uint32_t> times;
    int nightiter = 0;
};

/* The phone's store, the uploader's settings and a Nightscout that remembers what it got. */
struct World {
    std::vector<Sensor> sensors;
    int nightsensor = 0;
    RecentUploads<8> recents;
    bool wake = false; // a live reading is waiting (wakestream)
    int requests = 0;
    int passes = 0;
    int interrupted = 0;
    std::map<std::pair<int, uint32_t>, int> received; // (sensor, time) -> times sent
    std::map<std::pair<int, uint32_t>, int> arrivedAt; // request number of the first arrival
    std::function<void(World &)> afterRequest;
    std::function<bool(const World &)> refuse; // consulted per request

    explicit World(const std::vector<int> &lengths) {
        for (size_t id = 0; id < lengths.size(); id++) {
            Sensor sensor;
            for (int index = 0; index < lengths[id]; index++)
                sensor.times.push_back(1000000u * (id + 1) + index * interval);
            sensors.push_back(std::move(sensor));
        }
    }

    int last() const { return static_cast<int>(sensors.size()) - 1; }

    void appendLive(int sensorid) {
        auto &times = sensors[sensorid].times;
        times.push_back(times.back() + interval);
        wake = true;
    }

    bool send(int sensorid, uint32_t time) {
        requests++;
        if (refuse && refuse(*this))
            return false;
        const auto key = std::make_pair(sensorid, time);
        received[key]++;
        arrivedAt.emplace(key, requests);
        if (afterRequest)
            afterRequest(*this);
        return true;
    }

    bool uploadRecent(int sensorid) {
        const auto &times = sensors[sensorid].times;
        if (times.empty())
            return true;
        const int iter = static_cast<int>(times.size()) - 1;
        recents.prune(sensorid, times[iter]);
        if (recents.covered(sensorid, times[iter]))
            return true;
        if (!send(sensorid, times[iter]))
            return false;
        recents.remember(sensorid, times[iter], iter > 0 ? times[iter - 1] : 0);
        return true;
    }

    int duplicates() const {
        int total = 0;
        for (const auto &entry : received)
            total += entry.second - 1;
        return total;
    }

    bool everythingSent() const {
        for (size_t id = 0; id < sensors.size(); id++)
            for (uint32_t time : sensors[id].times)
                if (!received.count({static_cast<int>(id), time}))
                    return false;
        return true;
    }

    /* A new process: the cursors are stored, the rest of what the uploader knew is not. */
    void restart() {
        recents.clear();
        wake = false;
    }
};

/* uploadCGM3(): one request per reading. */
Pass passV3(World &world, bool prioritizeRecent) {
    world.passes++;
    const Sweep swept = sweep(world.last(), world.nightsensor, [&](const int sensorid) -> Turn {
        Sensor &sensor = world.sensors[sensorid];
        const int len = static_cast<int>(sensor.times.size());
        const int left = len - sensor.nightiter;
        if (left < 0)
            return Turn::idle;
        if (prioritizeRecent && left > 1 && !world.uploadRecent(sensorid))
            return Turn::failed;
        return drainEach(
            sensor.nightiter, len, [&] { return sensor.nightiter; },
            [&](const int pos) -> Item {
                const uint32_t time = sensor.times[pos];
                world.recents.prune(sensorid, time);
                if (world.recents.covered(sensorid, time))
                    return Item::covered;
                return world.send(sensorid, time) ? Item::sent : Item::refused;
            },
            [&](const int next) { sensor.nightiter = std::max(sensor.nightiter, next); },
            [&] { return world.wake; });
    });
    world.nightsensor = swept.nightsensor;
    return swept.pass;
}

/* uploadCGM(): one request per chunk of up to 40, then 120, readings. */
Pass passV1(World &world, bool prioritizeRecent) {
    world.passes++;
    const Sweep swept = sweep(world.last(), world.nightsensor, [&](const int sensorid) -> Turn {
        Sensor &sensor = world.sensors[sensorid];
        const int len = static_cast<int>(sensor.times.size());
        const int left = len - sensor.nightiter;
        if (left < 0)
            return Turn::idle;
        if (prioritizeRecent && left > 1 && !world.uploadRecent(sensorid))
            return Turn::failed;
        return drainChunks(
            sensor.nightiter, len, [&] { return sensor.nightiter; },
            [](const int pos) { return pos < 120 ? 40 : 120; },
            [&](const int pos, const int end) -> Chunk {
                bool any = false;
                bool ok = true;
                for (int iter = pos; iter < end && ok; iter++) {
                    const uint32_t time = sensor.times[iter];
                    world.recents.prune(sensorid, time);
                    if (world.recents.covered(sensorid, time))
                        continue;
                    // The chunk is one request: it is counted once, below.
                    world.received[{sensorid, time}]++;
                    world.arrivedAt.emplace(std::make_pair(sensorid, time), world.requests + 1);
                    any = true;
                }
                if (!any)
                    return Chunk::nothing;
                world.requests++;
                if (world.refuse && world.refuse(world)) {
                    for (int iter = pos; iter < end; iter++) {
                        // Nothing in a refused chunk reached the server.
                        const auto key = std::make_pair(sensorid, sensor.times[iter]);
                        auto found = world.received.find(key);
                        if (found != world.received.end() && --found->second == 0) {
                            world.received.erase(found);
                            world.arrivedAt.erase(key);
                        }
                    }
                    return Chunk::refused;
                }
                if (world.afterRequest)
                    world.afterRequest(world);
                return Chunk::sent;
            },
            [&](const int next) { sensor.nightiter = std::max(sensor.nightiter, next); },
            [&] { return world.wake; });
    });
    world.nightsensor = swept.nightsensor;
    return swept.pass;
}

using PassFn = Pass (*)(World &, bool);

/* The uploader thread: take the wake, run a pass, and run again at once if it gave way.
   A refused pass would wait a quarter of an hour; here it simply goes again. */
void runUntilQuiet(World &world, PassFn pass, int maxPasses = 2000000) {
    std::uintptr_t retry = 0;
    constexpr std::uintptr_t glucose = 1;
    while (world.passes < maxPasses) {
        const bool prioritize = world.wake;
        world.wake = false;
        const std::uintptr_t current = (prioritize ? glucose : 0) | retry | glucose;
        const Pass result = pass(world, prioritize);
        retry = carriedAfter(result, current, glucose);
        if (result == Pass::interrupted)
            world.interrupted++;
        if (result == Pass::done && !world.wake)
            return;
    }
    CHECK(!"the uploader never came to rest");
}

// ---------------------------------------------------------------------------------------

void liveReadingDoesNotWaitForTheBacklog(PassFn pass) {
    // The report: the newest sensor is done, four older ones have a long history still to send.
    World world({2000, 2000, 2000, 2000, 2000});
    world.sensors[4].nightiter = 2000;
    for (uint32_t time : world.sensors[4].times)
        world.received[{4, time}] = 1;
    uint32_t liveTime = 0;
    int injectedAt = 0;
    world.afterRequest = [&](World &w) {
        // A third of the way into the backlog.
        if (!liveTime && w.received.size() >= 2000 + 2600) {
            w.appendLive(4);
            liveTime = w.sensors[4].times.back();
            injectedAt = w.requests;
        }
    };

    runUntilQuiet(world, pass);

    CHECK(liveTime != 0);
    const int arrived = world.arrivedAt.at({4, liveTime});
    CHECK(arrived <= injectedAt + 1); // the next request, not the end of the backlog
    CHECK(world.everythingSent());
    CHECK(world.duplicates() == 0);
    CHECK(world.interrupted == 1);
}

void liveReadingsOnASensorWithItsOwnBacklog(PassFn pass, int every) {
    // A new server: the sensor that is live is itself 3000 readings behind.
    World world({1000, 1000, 3000});
    std::vector<std::pair<int, uint32_t>> live; // request at injection, time
    world.afterRequest = [&](World &w) {
        if (w.requests % every == 0 && live.size() < 8) {
            w.appendLive(2);
            live.push_back({w.requests, w.sensors[2].times.back()});
        }
    };

    runUntilQuiet(world, pass);

    CHECK(live.size() == 8);
    CHECK(world.interrupted >= 8);
    for (const auto &entry : live)
        CHECK(world.arrivedAt.at({2, entry.second}) <= entry.first + 1);
    CHECK(world.everythingSent());
    // Each live reading follows the one before it, so they were one covered range and the
    // cursor stepped over all of them.
    CHECK(world.duplicates() == 0);
}

void historyFinishesUnderLiveWakes(PassFn pass, int every) {
    World world({1500, 1500, 1500, 1500});
    int injected = 0;
    world.afterRequest = [&](World &w) {
        if (injected < 200 && w.requests % every == 0) {
            w.appendLive(3);
            injected++;
        }
    };

    runUntilQuiet(world, pass);

    CHECK(injected > 0);
    CHECK(world.everythingSent());
    // More than one reading in a single pass leaves a gap, and the range sent ahead of the
    // cursor only ever follows its own end. The reading the gap skipped is then sent by the
    // cursor, and the earlier ones may go again: never lost, at most once more each.
    CHECK(world.duplicates() <= injected);
}

void oldSensorsAreNotStrandedByAnInterruption(PassFn pass) {
    // Interrupted while on sensor 2: sensors 1 and 0 are older and not yet visited. Raising
    // nightsensor to 2 would leave them for good.
    World world({500, 500, 500, 500});
    world.sensors[3].nightiter = 500;
    for (uint32_t time : world.sensors[3].times)
        world.received[{3, time}] = 1;
    bool injected = false;
    world.afterRequest = [&](World &w) {
        if (!injected && w.sensors[2].nightiter >= 20) {
            injected = true;
            w.appendLive(3);
        }
    };

    runUntilQuiet(world, pass);

    CHECK(injected);
    CHECK(world.everythingSent());
}

void aRefusalLeavesTheLowerBoundAlone(PassFn pass) {
    // The newest sensor has one reading to send, then the next one down is refused before it
    // sends anything: the case where the bound used to be raised past a sensor not yet done.
    World world({400, 400, 400});
    world.sensors[2].nightiter = 399;
    for (int id = 0; id <= 2; id++)
        for (uint32_t time : world.sensors[id].times)
            if (id == 2 && time == world.sensors[2].times.back())
                continue;
            else if (id == 2)
                world.received[{id, time}] = 1;
    bool refused = false;
    world.refuse = [&](const World &w) {
        if (!refused && w.requests == 2) {
            refused = true;
            return true;
        }
        return false;
    };

    const Pass first = pass(world, false);

    CHECK(first == Pass::failed);
    CHECK(world.nightsensor == 0);
    CHECK(world.received.count({2, world.sensors[2].times.back()}) == 1);

    runUntilQuiet(world, pass);
    CHECK(world.everythingSent());
}

void aRestartKeepsTheCursors(PassFn pass) {
    World world({800, 800, 800});
    std::vector<uint32_t> live;
    world.afterRequest = [&](World &w) {
        if (w.requests % 90 == 0 && live.size() < 6) {
            w.appendLive(2);
            live.push_back(w.sensors[2].times.back());
        }
    };
    // The process dies once about a third of the readings are out: after that nothing gets
    // through. The pass that hits it ends failed, which leaves the stored state as a death
    // at that moment would.
    world.refuse = [](const World &w) { return w.received.size() >= 1000; };
    bool died = false;
    while (!died) {
        const bool prioritize = world.wake;
        world.wake = false;
        const Pass result = pass(world, prioritize);
        died = result == Pass::failed;
        CHECK(result != Pass::done || world.wake); // it must not have finished first
    }
    CHECK(!world.everythingSent());
    const int sentBefore = static_cast<int>(world.received.size());

    world.refuse = nullptr;
    world.afterRequest = nullptr;
    world.restart();
    runUntilQuiet(world, pass);

    CHECK(world.everythingSent());
    CHECK(static_cast<int>(world.received.size()) > sentBefore);
    // What a restart forgets is which readings were sent ahead of the cursor: those may go
    // twice. Nothing else may.
    CHECK(world.duplicates() <= static_cast<int>(live.size()));
}

void aFloodOfWakesStillMakesProgress(PassFn pass) {
    // A wake after every single request: each pass must still move the history on.
    World world({300, 300, 300});
    world.afterRequest = [&](World &w) {
        w.wake = true;
    };
    runUntilQuiet(world, pass, 100000);
    CHECK(world.everythingSent());
}

void aPassWithNothingWaitingRunsToTheEnd(PassFn pass) {
    World world({600, 600, 600});
    runUntilQuiet(world, pass);
    CHECK(world.passes == 1);
    CHECK(world.interrupted == 0);
    CHECK(world.everythingSent());
    CHECK(world.duplicates() == 0);
}

// ---------------------------------------------------------------------------------------

void sweepBookkeeping() {
    // Ran to the end: the bound rises to the lowest sensor that still had work.
    {
        const auto swept = sweep(4, 0, [](int id) { return id == 3 || id == 2 ? Turn::busy : Turn::idle; });
        CHECK(swept.pass == Pass::done);
        CHECK(swept.nightsensor == 2);
    }
    // Nothing to do anywhere: the bound is not moved.
    {
        const auto swept = sweep(4, 1, [](int) { return Turn::idle; });
        CHECK(swept.pass == Pass::done);
        CHECK(swept.nightsensor == 1);
    }
    // Stopped for a live reading on sensor 2 after sensor 3 was done: sensors 1 and 0 are
    // unseen, so the bound stays where it was.
    {
        const auto swept = sweep(4, 0, [](int id) { return id == 4 || id == 3 ? Turn::busy : id == 2 ? Turn::interrupted : Turn::idle; });
        CHECK(swept.pass == Pass::interrupted);
        CHECK(swept.nightsensor == 0);
    }
    // A refusal is the same, whether or not that sensor had sent something first.
    {
        const auto swept = sweep(4, 1, [](int id) { return id == 4 ? Turn::busy : id == 3 ? Turn::failed : Turn::idle; });
        CHECK(swept.pass == Pass::failed);
        CHECK(swept.nightsensor == 1);
    }
    // Sensors are visited newest first, down to and including the bound.
    {
        std::vector<int> order;
        sweep(3, 1, [&](int id) { order.push_back(id); return Turn::idle; });
        CHECK((order == std::vector<int>{3, 2, 1}));
    }
}

void carriedWakes() {
    constexpr std::uintptr_t stream = 1, all = 8, journal = 1024;
    CHECK(carriedAfter(Pass::done, stream | all, stream | all) == 0);
    CHECK(carriedAfter(Pass::failed, stream | journal, stream | all) == stream);
    CHECK(carriedAfter(Pass::interrupted, all | journal, stream | all) == all);
}

void recentRanges() {
    RecentUploads<4> recents;
    CHECK(!recents.covered(0, 100));
    recents.remember(0, 100, 40);
    CHECK(recents.covered(0, 100));
    CHECK(!recents.covered(0, 101));
    CHECK(!recents.covered(1, 100));
    // The next reading directly follows: one range, so the cursor steps over both.
    recents.remember(0, 160, 100);
    CHECK(recents.covered(0, 100) && recents.covered(0, 160));
    // A reading after a gap does not extend the range: the one in between was never sent.
    recents.remember(0, 280, 220);
    CHECK(recents.covered(0, 280));
    CHECK(!recents.covered(0, 160));
    CHECK(!recents.covered(0, 220));
    // An unknown predecessor never extends.
    recents.remember(1, 50, 0);
    recents.remember(1, 110, 0);
    CHECK(recents.covered(1, 110) && !recents.covered(1, 50));
    // A cursor well past the end releases the range, a cursor behind it does not.
    recents.prune(0, 280 + 600);
    CHECK(recents.covered(0, 280));
    recents.prune(0, 280 + 601);
    CHECK(!recents.covered(0, 280));
    // Ids outside the table are ignored rather than trusted.
    recents.remember(-1, 10, 0);
    recents.remember(4, 10, 0);
    CHECK(!recents.covered(-1, 10) && !recents.covered(4, 10));
    recents.remember(2, 10, 0);
    recents.clear();
    CHECK(!recents.covered(2, 10) && !recents.covered(1, 110));
}

} // namespace

int main() {
    // The v3 upload costs a request per reading, the v1 upload one per chunk of 40 to 120, so
    // "often" is a different number of requests for each.
    struct Mode {
        PassFn pass;
        int often; // live reading every this many requests
        int seldom;
    };
    for (const Mode mode : {Mode{&passV3, 7, 41}, Mode{&passV1, 1, 3}}) {
        liveReadingDoesNotWaitForTheBacklog(mode.pass);
        liveReadingsOnASensorWithItsOwnBacklog(mode.pass, mode.seldom);
        historyFinishesUnderLiveWakes(mode.pass, mode.often);
        historyFinishesUnderLiveWakes(mode.pass, mode.seldom);
        oldSensorsAreNotStrandedByAnInterruption(mode.pass);
        aRefusalLeavesTheLowerBoundAlone(mode.pass);
        aRestartKeepsTheCursors(mode.pass);
        aFloodOfWakesStillMakesProgress(mode.pass);
        aPassWithNothingWaitingRunsToTheEnd(mode.pass);
    }
    sweepBookkeeping();
    carriedWakes();
    recentRanges();
    std::puts("nightscout backlog: all tests passed");
    return 0;
}
