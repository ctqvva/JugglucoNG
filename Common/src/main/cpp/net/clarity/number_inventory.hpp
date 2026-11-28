// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <charconv>
#include <cstdint>
#include <map>
#include <set>
#include <string>
#include <string_view>

namespace clarity {
// A complete copy of ONE retained number database, made under its number
// lock, before filtering by upload date, category or value. An absent source
// is not an empty source. A pruned prefix is outside this inventory's scope.
struct NumberInventory {
    std::string source;
    int64_t firstTime = 0;
    int firstPosition = 0;
    std::set<std::string, std::less<>> keys, unreadablePrefixes;
    std::map<std::string, unsigned> occurrences;

    std::string prefix(int64_t time, uint32_t type) const {
        return source + "/" + std::to_string(time) + "/" + std::to_string(type);
    }
    std::string add(int64_t time, uint32_t type) {
        auto p = prefix(time, type);
        auto key = p + "/" + std::to_string(occurrences[p]++);
        keys.insert(key);
        return key;
    }
    void unreadable(int64_t time, uint32_t type) {
        unreadablePrefixes.insert(prefix(time, type));
    }
    bool missing(std::string_view key, bool previouslyRetained = false) const {
        const auto p = source + "/";
        if (!key.starts_with(p) || keys.contains(key))
            return false;
        const auto endTime = key.find('/', p.size());
        if (endTime == key.npos)
            return false;
        int64_t time = 0;
        const auto end = key.data() + endTime;
        const auto parsed = std::from_chars(key.data() + p.size(), end, time);
        if (parsed.ec != std::errc{} || parsed.ptr != end ||
            (time < firstTime && !previouslyRetained))
            return false;
        // Unknown/legacy key formats are never treated as deletions.
        const auto endType = key.find('/', endTime + 1);
        if (endType == key.npos || endType == endTime + 1 || endType + 1 == key.size())
            return false;
        uint32_t type = 0, occurrence = 0;
        auto t = std::from_chars(key.data() + endTime + 1, key.data() + endType, type);
        auto n = std::from_chars(key.data() + endType + 1, key.data() + key.size(), occurrence);
        return t.ec == std::errc{} && t.ptr == key.data() + endType &&
               n.ec == std::errc{} && n.ptr == key.data() + key.size() &&
               !unreadablePrefixes.contains(key.substr(0, endType));
    }
};
} // namespace clarity
