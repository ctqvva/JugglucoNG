#pragma once

#include <algorithm>
#include <array>
#include <optional>
#include <string>
#include <string_view>

namespace dexcom {
inline constexpr std::string_view manualPrefix = "JUGGLUCO-MANUAL-G7:";
inline constexpr int maximumStorageDays = 17; // 15-day sensor plus storage slack.
constexpr int wearMinutes(int days) { return (days == 15 ? 15 : 10) * 24 * 60; }
constexpr int maximumSeconds(int minutes) { return (minutes + 12 * 60 + 5) * 60; }
constexpr int maximumCount(int minutes) { return (minutes + 12 * 60) / 5 + 1; }

struct ScanIdentity {
    std::array<char, 16> name{};
    int days = 10;
    bool manual = false;
    std::string payload;
};

inline bool digits(std::string_view value) {
    return !value.empty() && std::all_of(value.begin(), value.end(),
            [](char c) { return c >= '0' && c <= '9'; });
}
inline bool serialCharacters(std::string_view value) {
    return !value.empty() && std::all_of(value.begin(), value.end(), [](char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z');
    });
}

// Parse only Dexcom's GS1 fields. Other sensor scan contracts are deliberately
// untouched. Bounds/duplicate checks precede identity construction and storage.
inline std::optional<ScanIdentity> parseScan(std::string_view raw) {
    if (raw.size() > 512) return std::nullopt;
    if (raw.starts_with(manualPrefix)) {
        if (raw.size() != 55 || raw.substr(48, 3) != "240" ||
                !digits(raw.substr(51)) || raw[19] != 'M' ||
                !serialCharacters(raw.substr(19, 12))) return std::nullopt;
        const auto padding = raw.substr(31, 17);
        if (padding != "00000000000000000" && padding != "15000000000000000")
            return std::nullopt;
        ScanIdentity scan;
        scan.manual = true;
        scan.days = padding.starts_with("15") ? 15 : 10;
        std::copy_n(raw.data() + 19, 12, scan.name.data());
        std::copy_n(raw.data() + 51, 4, scan.name.data() + 12);
        scan.payload = raw;
        return scan;
    }

    // Scanner symbology headers and printable GS representations carry no identity.
    if (raw.starts_with("]d2") || raw.starts_with("]C1")) raw.remove_prefix(3);
    std::string normalized;
    normalized.reserve(raw.size());
    for (size_t i = 0; i < raw.size(); ++i) {
        if (raw[i] == '^' && i + 1 < raw.size() && raw[i + 1] == ']') {
            normalized.push_back('\x1d');
            ++i;
        } else normalized.push_back(raw[i]);
    }
    const std::string_view text = normalized;
    std::array<std::string_view, 7> values{};
    constexpr std::array<std::string_view, 7> ais{"01", "11", "17", "10", "21", "240", "250"};
    constexpr std::array<size_t, 7> fixed{14, 6, 6, 0, 0, 0, 0};
    size_t pos = 0;
    while (pos < text.size()) {
        if (text[pos] == '\x1d') { ++pos; continue; }
        size_t field = ais.size();
        if (text[pos] == '(') {
            const auto end = text.find(')', pos);
            if (end == std::string_view::npos) return std::nullopt;
            const auto ai = text.substr(pos + 1, end - pos - 1);
            for (size_t f = 0; f < ais.size(); ++f) if (ai == ais[f]) field = f;
            pos = end + 1;
        } else {
            for (size_t f = 0; f < ais.size(); ++f) {
                if (text.substr(pos).starts_with(ais[f])) { field = f; break; }
            }
            if (field < ais.size()) pos += ais[field].size();
        }
        if (field == ais.size() || !values[field].empty()) return std::nullopt;
        const auto end = fixed[field] ? pos + fixed[field] : text.find_first_of("\x1d(", pos);
        const auto limit = end == std::string_view::npos ? text.size() : end;
        if (limit > text.size() || limit <= pos || limit - pos > 30) return std::nullopt;
        values[field] = text.substr(pos, limit - pos);
        pos = limit;
        if (fixed[field] && !digits(values[field])) return std::nullopt;
    }
    const auto gtin = values[0], serial = values[4], pin = values[5];
    if (gtin.size() != 14 || gtin.substr(1, 7) != "0386270" ||
            serial.size() > 12 || !serialCharacters(serial) ||
            pin.size() != 4 || !digits(pin)) return std::nullopt;

    ScanIdentity scan;
    // Same GTIN-based model selection and identity construction as Juggluco 10.8.0.
    scan.days = (gtin.ends_with("4581") || gtin.ends_with("4574")) ? 15 : 10;
    std::copy(serial.begin(), serial.end(), scan.name.begin());
    const auto suffix = gtin.substr(gtin.size() - (12 - serial.size()));
    std::copy(suffix.begin(), suffix.end(), scan.name.begin() + serial.size());
    std::copy(pin.begin(), pin.end(), scan.name.begin() + 12);
    // Store only the required fields, keeping Info::siId[68] and its ABI unchanged.
    scan.payload = "\x1d" "01" + std::string(gtin) + "21" + std::string(serial) +
                   "\x1d" "240" + std::string(pin);
    return scan;
}
} // namespace dexcom
