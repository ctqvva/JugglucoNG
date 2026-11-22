// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <array>
#include <cstdint>
#include <optional>

namespace clarity::portable {
using Block = std::array<std::uint8_t, 16>;
using Word = std::uint32_t;
using WordLookup = std::array<Word, 16 * 256>;

struct ByteMap {
  std::uint8_t offset;
  std::array<std::uint8_t, 8> basis;
};
using BlockMap = std::array<ByteMap, 16>;

struct Projection {
  std::array<std::uint64_t, 12> masks;
  Word tag;
};

// The caller supplies data for the supported layout. Words and masks are in
// host byte order. Context and block bytes retain the provider's byte order.
struct BlockMaterial {
  std::array<Block, 18> context;
  BlockMap input;
  std::array<WordLookup, 2> inputRounds;
  std::array<WordLookup, 6> inverseRounds;

  std::array<Word, 8 * 256> lateRows;
  std::array<Projection, 2> firstProjections;
  std::array<Word, 2> lookupTags;
  std::array<Word, 2> checkTags;
  WordLookup firstPackedLookup;
  std::array<std::array<Projection, 4>, 2> secondProjections;
  WordLookup secondPackedLookup;

  WordLookup finalRound;
  BlockMap terminal;
  BlockMap output;
};

// The decoded zero-IV, single-block operation. Returns an empty result if
// an index is out of range or a redundant computation disagrees.
std::optional<Block> decodeBlock(const Block &ciphertext,
                                 const BlockMaterial &material);
} // namespace clarity::portable
