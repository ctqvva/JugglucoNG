// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "bytes.hpp"
#include <limits>
#include <set>
#include <optional>
#include <functional>
namespace clarity {
enum class NumberKind { Ignore, Rapid, Long, Carbs, Note, Blood };
struct Reading {
    std::string sensor;
    int64_t time, start;
    int mgdl;
    double rate = std::numeric_limits<double>::quiet_NaN();
    int sessionLength = 0, warmup = 0;
    bool history = false; // Libre 3 history is already sampled every five sensor minutes.
};
struct Number {
    std::string key, label;
    int64_t time;
    double value;
    NumberKind kind;
    double weight = 1;
    bool mmol = false;
};
struct SensorSource {
    std::string sensor;
    int64_t start, end, first;
};
// The appended run and preceding prefix are already ordered by time. Merge
// only an overlap between sensors; never sort a sensor's stored readings.
void mergeReadingRun(std::vector<Reading> &readings, size_t begin);
std::vector<Reading> selectReadings(const std::vector<Reading> &input,
                                    const std::set<int64_t> &sent, int64_t since, int64_t now,
                                    size_t limit, const std::vector<SensorSource> &sources = {});
struct SentNeighbours { std::optional<int64_t> before, atOrAfter; };
using SentLookup = std::function<SentNeighbours(int64_t)>;
using SensorLookup = std::function<bool(const Reading &)>;
std::vector<Reading> selectIndexedReadings(const std::vector<Reading> &input,
    int64_t since, int64_t now, size_t limit, const SentLookup &sent, const SensorLookup &sensor);
std::string transmitterId(const std::string &installation, const std::string &sensor);
Json glucoseRecord(const Reading &reading, const std::string &transmitter);
Json sessionRecord(const Reading &reading, const std::string &transmitter);
// Native event identity. Recorded times/status belong to the outgoing request,
// not to the acknowledged source value.
struct EventContent {
    std::string id, displayTime, value, description;
    int64_t time = 0;
    NumberKind kind = NumberKind::Ignore;
    bool operator==(const EventContent &) const = default;
};
EventContent eventContent(const Number &number, const std::string &id);
Json eventRecord(const EventContent &event, int64_t now, const std::string &status);
Json numberRecord(const Number &number, const std::string &id, int64_t now,
                  const std::string &status = "New");
} // namespace clarity
