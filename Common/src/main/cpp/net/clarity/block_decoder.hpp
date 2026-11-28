// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <variant>

namespace clarity::portable {
using Block = std::array<std::uint8_t, 16>;
using SessionKey = std::array<std::uint8_t, 32>;
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

enum class UnwrapError { invalidLength, decoderFailure };
using UnwrapResult = std::variant<SessionKey, UnwrapError>;

// Like Engine's session-key import, accepts exactly two ciphertext blocks
// and a one-block IV. Errors carry no partial key and leave the IV intact.
UnwrapResult unwrapSessionKey(std::span<const std::uint8_t> ciphertext,
                              std::span<const std::uint8_t> iv,
                              const BlockMaterial &material);

// Strips the BGE per-round affine encodings from inverseRounds and
// context[3..12], verifies the 10-round AES-256 key schedule recurrence, and
// rewinds the schedule to the 32-byte AES-256 session-wrap key.
std::optional<SessionKey> recoverSessionWrapKey(const BlockMaterial &material);
} // namespace clarity::portable
