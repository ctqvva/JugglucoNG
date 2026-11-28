// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "block_decoder.hpp"
#include "bytes.hpp"
#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace clarity {
// One application key from the provider's table.
struct ApplicationKey {
  std::string name;
  Bytes value;
};

// Everything the portable provider needs: the decoded single-block operation,
// the ABI output rows and the application keys. The rows are kept for
// completeness; BlockMaterial::output already includes them.
struct ProviderMaterial {
  portable::BlockMaterial block{};
  std::array<std::array<std::uint8_t, 256>, 16> decode{};
  std::vector<ApplicationKey> keys;
  bool keyOnly = false;
};

inline constexpr std::size_t maxApplicationKeys = 32;
inline constexpr std::string_view sessionWrapKeyName = "SessionWrapKey";

// Built-in compact key-only material (31 application keys + SessionWrapKey)
// used when no external clarity.mat override file is installed.
const ProviderMaterial &defaultKeyMaterial();

// Converts full or key-only material to a compact key-only representation
// (31 application keys + SessionWrapKey, with blockSize=0 and decodeSize=0),
// recovering SessionWrapKey via BGE if needed.
ProviderMaterial toKeyMaterial(const ProviderMaterial &material);

// A versioned, little-endian file for optional key-material overrides or
// full white-box table snapshots.
Bytes encodeMaterial(const ProviderMaterial &material);
ProviderMaterial parseMaterial(std::span<const std::uint8_t> file);
ProviderMaterial loadMaterial(const std::string &path);
void saveMaterial(const std::string &path, const ProviderMaterial &material);
void saveKeyMaterial(const std::string &path, const ProviderMaterial &material);
} // namespace clarity
