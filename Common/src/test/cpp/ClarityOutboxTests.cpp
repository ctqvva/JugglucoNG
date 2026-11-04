// Host tests for the Dexcom Clarity outbox and mapped storage (net/clarity).
// Built and run by tools/test-clarity-native.py; no Dexcom provider is needed.
#include "net/clarity/account_store.hpp"
#include "net/clarity/mapped.hpp"
#include "net/clarity/outbox.hpp"
#include <cassert>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <set>
#include <string>
#include <vector>

using namespace clarity;
namespace fs = std::filesystem;

namespace {
constexpr int64_t day = 86400, minute = 60;
constexpr int64_t base = 1'800'000'000; // 2027-01-15, a fixed "now" for every case
const std::string account = "11111111-2222-4333-8444-555555555555";
const std::string installation = "66666666-7777-4888-9999-aaaaaaaaaaaa";

fs::path scratch;
std::string fresh(const char *name) {
    auto dir = scratch / name;
    fs::remove_all(dir);
    fs::create_directories(dir);
    return (dir / "outbox.map").string();
}

void stream(std::vector<Reading> &out, const std::string &sensor, int64_t start, int wearDays,
            int64_t from, int64_t to, int mgdl = 120) {
    for (int64_t t = from; t <= to; t += 5 * minute)
        out.push_back({sensor, t, start, mgdl, 0.0, int(wearDays * day), 0, false});
}

// Prepares and acknowledges batches until nothing is left; returns every sent time.
std::set<int64_t> drain(Outbox &box, const Snapshot &s, int64_t since, int64_t now) {
    std::set<int64_t> sent;
    for (int batch = 0; batch < 1000 && box.prepare(s, since, now, false); ++batch) {
        for (auto t : box.pending().times) sent.insert(t);
        char receipt[37];
        std::snprintf(receipt, sizeof receipt, "00000000-0000-4000-8000-%012d", batch);
        box.acknowledge(receipt);
    }
    return sent;
}

Snapshot snapshotOf(std::vector<Reading> readings) {
    std::sort(readings.begin(), readings.end(), [](const Reading &a, const Reading &b) {
        return a.time != b.time ? a.time < b.time : a.sensor < b.sensor;
    });
    Snapshot s;
    s.readings = std::move(readings);
    return s;
}

// A 10-day G7 started during a 15-day Libre's life takes over from its first
// reading on, although the Libre's planned end (base + 7 d) is later than the
// G7's (base + 4 d).
void shorterReplacementTakesOver() {
    const int64_t libreStart = base - 8 * day, g7Start = base - 6 * day, g7First = g7Start + 30 * minute;
    std::vector<Reading> readings;
    stream(readings, "LIBRE3PLUS", libreStart, 15, libreStart + 60 * minute, base);
    stream(readings, "G7SENSOR", g7Start, 10, g7First + 150, base); // offset from Libre times
    Outbox box(fresh("replacement"), account, installation);
    const auto sent = drain(box, snapshotOf(readings), libreStart, base);
    size_t g7 = 0, libreAfter = 0;
    for (const auto &r : readings) {
        if (!sent.contains(r.time)) continue;
        if (r.sensor == "G7SENSOR") ++g7;
        else if (r.time > g7First + 5 * minute) ++libreAfter;
    }
    const size_t g7Total = std::count_if(readings.begin(), readings.end(),
                                         [](const Reading &r) { return r.sensor == "G7SENSOR"; });
    std::printf("replacement: G7 sent %zu/%zu, Libre after takeover %zu\n", g7, g7Total, libreAfter);
    assert(g7 + 1 >= g7Total && "the replacement's readings must be uploaded");
    assert(libreAfter == 0 && "the replaced sensor must not interleave after takeover");
}

// Backfill that arrives after a sensor was first seen is uploaded too.
void lateBackfillIsUploaded() {
    const int64_t start = base - 2 * day, now = base;
    const auto path = fresh("backfill");
    {
        std::vector<Reading> current;
        stream(current, "G7SENSOR", start, 10, now, now); // only the live reading so far
        Outbox box(path, account, installation);
        assert(drain(box, snapshotOf(current), start, now).size() == 1);
    }
    std::vector<Reading> all;
    stream(all, "G7SENSOR", start, 10, now - 6 * 60 * minute, now); // 6 h backfill arrives
    Outbox box(path, account, installation); // reopened, as after a restart
    const auto sent = drain(box, snapshotOf(all), start, now);
    std::printf("backfill: sent %zu of %zu older readings\n", sent.size(), all.size() - 1);
    assert(sent.size() == all.size() - 1 && "older backfill must not be skipped");
}

// GlucoseRecord values in a pending post: PublicDataContent is a wrapped,
// gzipped and base64-encoded list of entries whose Records are JSON text.
std::vector<int> uploadedGlucose(std::string_view body) {
    const auto post = Json::parse(body).at("DataPost");
    const auto wrapped = Json::parse(post.at("PublicDataContent").get<std::string>());
    const auto content = Json::parse(text(gunzip(unbase64(wrapped.at("Content").get<std::string>()))));
    std::vector<int> values;
    for (const auto &entry : content.at("Entries"))
        if (entry.at("RecordType") == "GlucoseRecord")
            for (const auto &record : Json::parse(entry.at("Records").get<std::string>()))
                values.push_back(record.at("Value").get<int>());
    return values;
}

// The upload value comes from Snapshot::exported, which is asked only about
// readings going into a batch: never for the whole history, never when up to date.
void exportedValueOnlyForSentReadings() {
    const int64_t start = base - 14 * day;
    std::vector<Reading> readings;
    for (int64_t t = start + 60 * minute; t <= base; t += minute) // a Libre 3 minute stream
        readings.push_back({"LIBRE3PLUS", t, start, 120, 0.0, int(15 * day), 0, false});
    auto s = snapshotOf(readings);
    size_t calls = 0;
    s.exported = [&](const Reading &r) { ++calls; return r.mgdl + 7; };
    Outbox box(fresh("exported"), account, installation);
    size_t sent = 0, batches = 0;
    for (; box.prepare(s, start, base, false); ++batches) {
        const auto values = uploadedGlucose(box.pendingBody());
        assert(values.size() == box.pending().times.size());
        for (int v : values) assert(v == 127 && "the exported value is uploaded, not the stored one");
        sent += box.pending().times.size();
        char receipt[37];
        std::snprintf(receipt, sizeof receipt, "00000000-0000-4000-8000-%012zu", batches);
        box.acknowledge(receipt);
    }
    std::printf("exported: %zu calls for %zu sent of %zu readings in %zu batches\n",
                calls, sent, readings.size(), batches);
    assert(calls == sent && "only readings in a batch are resolved");
    const auto before = calls;
    assert(!box.prepare(s, start, base, false));
    assert(calls == before && "an up-to-date scan resolves nothing");
}

struct Small { uint64_t value; };

// A creation interrupted before its first checkpoint was published reopens.
void interruptedCreationRecovers() {
    const auto dir = scratch / "interrupted";
    fs::remove_all(dir);
    fs::create_directories(dir);
    const auto grown = (dir / "grown.map").string();
    { std::ofstream(grown, std::ios::binary) << std::string(4096, '\0'); } // stopped after the first grow
    {
        MappedCheckpoint<Small> c(grown, "TESTCKP1", Small{7});
        assert(c.get().value == 7);
        c.save(Small{8});
    }
    {
        MappedCheckpoint<Small> c(grown, "TESTCKP1", Small{7});
        assert(c.get().value == 8 && "saved data must survive reopening");
    }
    // First checkpoint half written (body, no header): slot 1 still untouched.
    const auto half = (dir / "half.map").string();
    {
        std::string bytes(2 * 65536, '\0');
        bytes[sizeof(MappedHeader)] = 7;
        std::ofstream(half, std::ios::binary) << bytes;
    }
    {
        MappedCheckpoint<Small> c(half, "TESTCKP1", Small{9});
        assert(c.get().value == 9);
    }
    // The account store opens after the same interruption.
    const auto store = (dir / "account.map").string();
    { std::ofstream(store, std::ios::binary) << std::string(4096, '\0'); }
    assert(AccountStore(store).load().is_null());
    // Damage to a file that did save data still stops instead of resetting it.
    const auto damaged = (dir / "damaged.map").string();
    {
        MappedCheckpoint<Small> c(damaged, "TESTCKP1", Small{1});
        c.save(Small{2}); // writes slot 1
    }
    {
        std::fstream f(damaged, std::ios::binary | std::ios::in | std::ios::out);
        f.seekp(0); f.write("XXXXXXXX", 8);
        f.seekp(65536); f.write("XXXXXXXX", 8);
    }
    bool refused = false;
    try { MappedCheckpoint<Small> c(damaged, "TESTCKP1", Small{1}); }
    catch (const Error &) { refused = true; }
    assert(refused && "a damaged file with saved data must not be reset");
    // Truncated files that saved data: never mistaken for an unfinished creation.
    // The last save is the 5th (slot 0), or the 2nd (slot 1) so that slot 0 still
    // holds generation 1 and only the truncation hides the later save.
    auto truncatedRefused = [&](const char *name, int saves, size_t length) {
        const auto path = (dir / name).string();
        {
            MappedCheckpoint<Small> c(path, "TESTCKP1", Small{1});
            for (int i = 2; i <= saves; ++i) c.save(Small{uint64_t(i)});
        }
        fs::resize_file(path, length);
        bool stopped = false;
        try { MappedCheckpoint<Small> c(path, "TESTCKP1", Small{1}); }
        catch (const Error &) { stopped = true; }
        return stopped;
    };
    assert(truncatedRefused("t128.map", 5, 128) && "128-byte truncation must not reset saved state");
    assert(truncatedRefused("t4096.map", 2, 4096) && "4096-byte truncation must not reset saved state");
    assert(truncatedRefused("tslot.map", 2, 65536) && "one-slot truncation must not reset saved state");
    std::printf("interrupted creation: recovered; damaged or truncated saved data refused\n");
}

// selectReadings follows the same rules as the outbox.
void selectReadingsMatchesOutbox() {
    const int64_t libreStart = base - 8 * day, g7Start = base - 6 * day;
    std::vector<Reading> readings;
    stream(readings, "LIBRE3PLUS", libreStart, 15, libreStart + 60 * minute, base);
    stream(readings, "G7SENSOR", g7Start, 10, g7Start + 30 * minute + 150, base);
    const auto s = snapshotOf(readings);
    const auto picked = selectReadings(s.readings, {}, base - day, base, 100000);
    assert(!picked.empty());
    for (const auto &r : picked) assert(r.sensor == "G7SENSOR");
    // A source known earlier than its readings (backfill) moves the boundary back.
    std::vector<Reading> late;
    stream(late, "G7SENSOR", g7Start, 10, base - 60 * minute, base);
    const auto moved = selectReadings(snapshotOf(late).readings, {}, g7Start, base, 100000,
                                      {{"G7SENSOR", g7Start, g7Start + 10 * day, base}});
    assert(moved.size() == late.size());
    std::printf("selectReadings: activation order and earliest first\n");
}
} // namespace

int main() {
    scratch = fs::temp_directory_path() / ("clarity-native-" + std::to_string(::getpid()));
    fs::create_directories(scratch);
    shorterReplacementTakesOver();
    lateBackfillIsUploaded();
    exportedValueOnlyForSentReadings();
    interruptedCreationRecovers();
    selectReadingsMatchesOutbox();
    fs::remove_all(scratch);
    std::puts("PASS: Clarity outbox and mapped storage");
}
