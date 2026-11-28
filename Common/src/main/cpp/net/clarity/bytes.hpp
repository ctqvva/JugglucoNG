// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "net/clarity/vendor/nlohmann/json.hpp"
#include "logging.hpp"
#include <cstdint>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>
namespace clarity {
using Bytes = std::vector<uint8_t>;
using Json = nlohmann::ordered_json;
// Only explicitly constructed diagnostics may be shown in the settings UI.
class Error : public std::runtime_error {
  public:
    explicit Error(const std::string &message,
                   const std::source_location &where = std::source_location::current());
};
inline Bytes bytes(std::string_view s) { return {s.begin(), s.end()}; }
inline std::string text(std::span<const uint8_t> b) { return {b.begin(), b.end()}; }
Bytes randomBytes(size_t n);
Bytes sha256(std::span<const uint8_t> input);
Bytes hmac256(std::span<const uint8_t> key, std::span<const uint8_t> input);
std::string hex(std::span<const uint8_t> b);
Bytes unhex(std::string_view s);
std::string base64(std::span<const uint8_t> b, bool url = false);
Bytes unbase64(std::string_view s, bool url = false);
std::string uuid();
std::string stableUuid(std::string_view name);
bool isUuid(std::string_view s);
std::string timestamp(int64_t seconds, bool local = false, bool seven = false);
Bytes gzip(std::span<const uint8_t> input);
Bytes gunzip(std::span<const uint8_t> input);
std::string readFile(const std::string &path, size_t limit = 16 * 1024 * 1024);
void atomicFile(const std::string &path, std::string_view contents);
} // namespace clarity
