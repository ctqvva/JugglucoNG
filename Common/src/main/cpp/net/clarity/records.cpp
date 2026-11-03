// SPDX-License-Identifier: GPL-3.0-or-later
#include "records.hpp"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <map>
#include <tuple>
namespace clarity {
void mergeReadingRun(std::vector<Reading> &readings, size_t begin) {
    const auto before = [](const Reading &a, const Reading &b) {
        return a.time != b.time ? a.time < b.time : a.sensor < b.sensor;
    };
    if (begin && begin < readings.size() && before(readings[begin], readings[begin - 1]))
        std::inplace_merge(readings.begin(), readings.begin() + begin, readings.end(), before);
}
std::vector<Reading> selectReadings(const std::vector<Reading> &input,
                                    const std::set<int64_t> &sent, int64_t since, int64_t now,
                                    size_t limit, const std::vector<SensorSource> &sources) {
    // Order only the small set of sensor activation boundaries, never readings.
    // A stopped/removed source remains here through the persisted sources list.
    auto newer = [](const SensorSource &a, const SensorSource &b) {
        return std::tie(a.end, a.start, a.sensor) > std::tie(b.end, b.start, b.sensor);
    };
    std::map<int64_t, SensorSource> timeline;
    std::set<std::string> known;
    auto add = [&](const SensorSource &s) {
        if (s.first > now || s.first < s.start || !known.insert(s.sensor).second)
            return;
        auto [it, inserted] = timeline.try_emplace(s.first, s);
        if (!inserted && newer(s, it->second))
            it->second = s;
    };
    for (const auto &s : sources)
        add(s);
    for (const auto &r : input)
        if (r.time <= now && r.time >= r.start && r.mgdl >= 20 && r.mgdl <= 600)
            add({r.sensor, r.start, r.start + r.sessionLength, r.time});
    // Only a strictly higher expected end date (with deterministic ties) can
    // supersede the current source. Expiry and missing data never undo it.
    for (auto it = timeline.begin(); it != timeline.end();) {
        if (it != timeline.begin() && !newer(it->second, std::prev(it)->second))
            it = timeline.erase(it);
        else
            ++it;
    }
    auto occupied = [&](int64_t time) {
        SentNeighbours result;
        auto next = sent.lower_bound(time);
        if (next != sent.end()) result.atOrAfter = *next;
        if (next != sent.begin()) result.before = *std::prev(next);
        return result;
    };
    auto sensor = [&](const Reading &r) {
        auto source = timeline.upper_bound(r.time);
        return source != timeline.begin() && std::prev(source)->second.sensor == r.sensor;
    };
    return selectIndexedReadings(input, since, now, limit, occupied, sensor);
}
std::vector<Reading> selectIndexedReadings(const std::vector<Reading> &input,
    int64_t since, int64_t now, size_t limit, const SentLookup &sent, const SensorLookup &sensor) {
    // Bounded scratch for this not-yet-persisted batch only.
    std::set<int64_t> selectedTimes;
    std::vector<Reading> out;
    if (!limit) return out;
    for (const auto &r : input) {
        if (r.time < since || r.time > now || r.time < r.start || r.mgdl < 20 || r.mgdl > 600)
            continue;
        if (!sensor(r))
            continue;
        auto conflicts = [&](int64_t time) {
            if (time == r.time)
                return true;
            // Keep stream sampling and protection against nearby stream
            // receipts when changing modes. A missing trend is not evidence
            // of history: stream backfill can also have no trend.
            constexpr int64_t minimumSpacing = 300 - 30;
            if ((time < r.time ? r.time - time : time - r.time) >= minimumSpacing)
                return false;
            if (!r.history)
                return true;
            // History already has one record per five sensor minutes. Its
            // wall-clock timestamps can be 279 or 283 seconds apart. Two
            // distinct records in that same history must not filter each
            // other out. Recognize old timestamp-only receipts as well as
            // records selected in this batch, without resetting the outbox.
            // Input is already ordered; this lookup does not sort it again.
            auto match = std::lower_bound(input.begin(), input.end(), time,
                                          [](const Reading &value, int64_t t) {
                                              return value.time < t;
                                          });
            for (; match != input.end() && match->time == time; ++match)
                if (match->history && match->sensor == r.sensor &&
                    match->time >= match->start && match->mgdl >= 20 && match->mgdl <= 600)
                    return false;
            return true;
        };
        auto clashes = [&](const std::set<int64_t> &occupied) {
            auto next = occupied.lower_bound(r.time);
            return (next != occupied.end() && conflicts(*next)) ||
                   (next != occupied.begin() && conflicts(*std::prev(next)));
        };
        const auto nearby = sent(r.time);
        if ((nearby.before && conflicts(*nearby.before)) ||
            (nearby.atOrAfter && conflicts(*nearby.atOrAfter)) || clashes(selectedTimes))
            continue;
        selectedTimes.insert(r.time);
        out.push_back(r);
        if (out.size() >= limit)
            break;
    }
    return out;
}
std::string transmitterId(const std::string &installation, const std::string &sensor) {
    auto h = sha256(bytes(installation + "/" + sensor));
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i)
        v = (v << 8) | h[i];
    char buf[16];
    snprintf(buf, sizeof(buf), "99%010llu", static_cast<unsigned long long>(v % 10000000000ULL));
    return buf;
}
static std::string arrow(double r) {
    if (!std::isfinite(r) || r < -8 || r > 8)
        return "None";
    if (r <= -3)
        return "DoubleDown";
    if (r <= -2)
        return "SingleDown";
    if (r <= -1)
        return "FortyFiveDown";
    if (r < 1)
        return "Flat";
    if (r < 2)
        return "FortyFiveUp";
    if (r < 3)
        return "SingleUp";
    return "DoubleUp";
}
Json glucoseRecord(const Reading &r, const std::string &tx) {
    auto utc = timestamp(r.time), local = timestamp(r.time, true);
    auto trend = arrow(r.rate);
    Json j = {{"RecordVersion", "1.2"},
              {"Value", r.mgdl},
              {"TransmitterId", tx},
              {"TransmitterTime", 79 + r.time - r.start},
              {"SessionStartTime", 79},
              {"InternalStatus", 6},
              {"SecondaryAlgorithmState", trend == "None" ? 12 : 14},
              {"IsBackfilled", false},
              {"CapturedBy", "Phone"},
              {"TrendArrow", trend},
              {"RecordedSystemTime", utc},
              {"RecordedDisplayTime", local},
              {"GlucoseSystemTime", utc},
              {"GlucoseDisplayTime", local},
              {"CapturedBySystemTime", utc},
              {"CapturedByDisplayTime", local}};
    if (trend != "None")
        j["TrendRate"] = r.rate;
    return j;
}
Json sessionRecord(const Reading &r, const std::string &tx) {
    return {{"RecordVersion", "1.1"},
            {"RecordedSystemTime", timestamp(r.start)},
            {"RecordedDisplayTime", timestamp(r.start, true)},
            {"SensorSessionLength", r.sessionLength},
            {"SensorWarmupLength", r.warmup},
            {"SessionCalibrationType", "FactoryCalMode"},
            {"SessionId", tx + "|79"},
            {"SessionState", "SessionStartedOnTransmitterFromThisDisplay"},
            {"TransmitterId", tx},
            {"TransmitterTime", 79}};
}
static std::string decimal(double v) {
    char s[64];
    snprintf(s, sizeof(s), "%.9g", v);
    return s;
}
EventContent eventContent(const Number &n, const std::string &id) {
    if (!std::isfinite(n.value) || !isUuid(id)) throw Error("Invalid number record");
    EventContent e{id, timestamp(n.time, true), "", "", n.time, n.kind};
    const auto description = n.label + " " + decimal(n.value);
    switch (n.kind) {
    case NumberKind::Rapid:
    case NumberKind::Long:
        if (n.value < 0) throw Error("Invalid insulin value");
        e.value = decimal(std::round(n.value * 100));
        e.description = description;
        break;
    case NumberKind::Carbs:
        if (n.value < 0 || !std::isfinite(n.weight) || n.weight <= 0)
            throw Error("Invalid carbohydrate value");
        e.value = decimal(std::round(n.value * n.weight));
        break;
    case NumberKind::Blood:
        if (n.value <= 0) throw Error("Invalid blood value");
        e.value = decimal(std::round(n.value * (n.mmol ? 18.02 : 1)));
        e.description = description + (n.mmol ? " mmol/L" : " mg/dL");
        break;
    case NumberKind::Note:
        e.value = description;
        break;
    default: throw Error("Unmapped number category");
    }
    return e;
}
Json eventRecord(const EventContent &e, int64_t now, const std::string &status) {
    Json j = {{"RecordVersion", "1.1"}, {"EventId", e.id}, {"RecordStatus", status},
              {"EventSystemTime", timestamp(e.time)}, {"EventDisplayTime", e.displayTime},
              {"RecordedSystemTime", timestamp(now)}, {"RecordedDisplayTime", timestamp(now, true)}};
    switch (e.kind) {
    case NumberKind::Rapid: case NumberKind::Long:
        j["Name"] = "Insulin";
        j["Units"] = "units";
        j["SubType"] = e.kind == NumberKind::Rapid ? "Fast-Acting" : "Long-Acting";
        j["Value"] = e.value;
        j["ValueDescription"] = e.description;
        break;
    case NumberKind::Carbs:
        j["Name"] = "Carbs"; j["Units"] = "grams"; j["Value"] = e.value;
        break;
    case NumberKind::Blood:
        j["Name"] = "BG"; j["Units"] = "mgdl"; j["Value"] = e.value;
        j["ValueDescription"] = e.description;
        break;
    case NumberKind::Note: j["Name"] = "Note"; j["Value"] = e.value; break;
    default: throw Error("Invalid saved Clarity event category");
    }
    return j;
}
Json numberRecord(const Number &n, const std::string &id, int64_t now, const std::string &status) {
    return eventRecord(eventContent(n, id), now, status);
}
} // namespace clarity
