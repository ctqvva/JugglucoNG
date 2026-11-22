// SPDX-License-Identifier: GPL-3.0-or-later
#include "block_decoder.hpp"

#include <bit>
#include <cstddef>
#include <span>

namespace clarity::portable {
namespace {
using Byte = std::uint8_t;
using Columns = std::array<Word, 4>;
constexpr Word radix = 93139;

struct PackedIndex {
  Word quotient;
  Word remainder;
};
using PackedColumns = std::array<std::array<PackedIndex, 4>, 2>;

Block applyMap(Block state, const BlockMap &map) {
  for (std::size_t i = 0; i < state.size(); ++i) {
    const auto &byte = map[i];
    auto result = byte.offset;
    for (std::size_t bit = 0; bit < byte.basis.size(); ++bit)
      if ((state[i] >> bit) & 1u)
        result ^= byte.basis[bit];
    state[i] = result;
  }
  return state;
}

Byte wordByte(Word word, std::size_t position) {
  return static_cast<Byte>(word >> (8 * position));
}

Word columnWord(const Block &block, std::size_t column) {
  Word word = 0;
  for (std::size_t row = 0; row < 4; ++row)
    word |= Word{block[4 * column + row]} << (8 * row);
  return word;
}

Block columnBytes(const Columns &columns) {
  Block block{};
  for (std::size_t i = 0; i < block.size(); ++i)
    block[i] = wordByte(columns[i / 4], i % 4);
  return block;
}

std::size_t inverseShiftPosition(std::size_t column, std::size_t row) {
  return 4 * ((column + 4 - row) % 4) + row;
}

Block wordRound(const Block &state, const WordLookup &lookup,
                const Block &context, bool inverseShift) {
  Columns columns{};
  for (std::size_t column = 0; column < columns.size(); ++column) {
    auto value = columnWord(context, column);
    for (std::size_t row = 0; row < 4; ++row) {
      const auto position = inverseShift ? inverseShiftPosition(column, row)
                                         : 4 * column + row;
      value ^= lookup[256 * position + state[position]];
    }
    columns[column] = value;
  }
  return columnBytes(columns);
}

Word foldContext(const Block &context, Word seed) {
  for (auto byte : context) {
    seed = (seed << 4) + byte;
    seed ^= (seed >> 28) << 4;
  }
  return seed & 0x0fffffff;
}

Word byteParity(std::uint64_t word) {
  Word result = 0;
  for (std::size_t position = 0; position < 8; ++position) {
    const auto byte = static_cast<Byte>(word >> (8 * position));
    result |= static_cast<Word>(std::popcount(byte) & 1) << position;
  }
  return result;
}

PackedIndex splitIndex(Word index) {
  return {index / radix, index % radix};
}

PackedIndex projectColumn(Word word, const Projection &projection) {
  std::array<Word, 3> parity{};
  for (std::size_t group = 0; group < parity.size(); ++group) {
    std::uint64_t masked = 0;
    for (std::size_t row = 0; row < 4; ++row) {
      constexpr std::uint64_t byteLanes = 0x0101010101010101;
      const auto repeatedByte = wordByte(word, row) * byteLanes;
      masked ^= repeatedByte & projection.masks[4 * group + row];
    }
    parity[group] = byteParity(masked);
  }
  const auto tag = splitIndex(projection.tag);
  const auto upper = ((parity[0] << 4) | (parity[1] >> 4)) ^ tag.quotient;
  const auto lower = (((parity[1] & 15) << 8) | parity[2]) ^ tag.remainder;
  return splitIndex(upper * radix + lower);
}

std::optional<Columns> packedRound(const PackedColumns &state,
                                    const WordLookup &lookup,
                                    const std::array<Word, 2> &tags) {
  const auto firstTag = splitIndex(tags[0]);
  const auto secondTag = splitIndex(tags[1]);
  Columns columns{};
  for (std::size_t column = 0; column < columns.size(); ++column) {
    const std::array indices{
        state[0][column].quotient ^ firstTag.quotient,
        state[0][(column + 3) % 4].remainder ^ firstTag.remainder,
        state[1][(column + 2) % 4].quotient ^ secondTag.quotient,
        state[1][(column + 1) % 4].remainder ^ secondTag.remainder,
    };
    for (const auto index : indices) {
      if (index >= lookup.size())
        return std::nullopt;
      columns[column] ^= lookup[index];
    }
  }
  return columns;
}

// The late byte lookup uses the same four rows for each inverse-shifted
// column. Four additional rows compute a redundant copy of the result.
Word lateColumn(const Block &state, std::size_t column,
                std::span<const Word, 4 * 256> rows) {
  Word word = 0;
  for (std::size_t row = 0; row < 4; ++row)
    word ^= rows[256 * row + state[inverseShiftPosition(column, row)]];
  return word;
}

std::optional<Block> lateRounds(const Block &state,
                                const BlockMaterial &material) {
  constexpr Word foldSeed = (radix << 8) | 13u;
  const auto firstFold = foldContext(material.context[13], foldSeed);
  const auto secondFold = foldContext(material.context[14], foldSeed + 1);
  const auto thirdFold = foldContext(material.context[15], foldSeed + 2);
  const std::span rows(material.lateRows);

  PackedColumns projected{};
  for (std::size_t column = 0; column < 4; ++column) {
    const auto mixed = lateColumn(state, column, rows.first<4 * 256>());
    if (mixed != lateColumn(state, column, rows.last<4 * 256>()))
      return std::nullopt;
    const auto word = mixed ^ columnWord(material.context[14], column) ^ firstFold;
    for (std::size_t part = 0; part < projected.size(); ++part)
      projected[part][column] = projectColumn(word, material.firstProjections[part]);
  }

  const auto first =
      packedRound(projected, material.firstPackedLookup, material.lookupTags);
  if (!first || first != packedRound(projected, material.firstPackedLookup,
                                     material.checkTags))
    return std::nullopt;

  for (std::size_t column = 0; column < first->size(); ++column) {
    const auto word = (*first)[column] ^ columnWord(material.context[15], column) ^ secondFold;
    for (std::size_t part = 0; part < projected.size(); ++part)
      projected[part][column] =
          projectColumn(word, material.secondProjections[part][column]);
  }
  auto second = packedRound(projected, material.secondPackedLookup, {});
  if (!second)
    return std::nullopt;
  for (std::size_t column = 0; column < second->size(); ++column)
    (*second)[column] ^= columnWord(material.context[16], column) ^ thirdFold;
  return columnBytes(*second);
}
} // namespace

std::optional<Block> decodeBlock(const Block &ciphertext,
                                 const BlockMaterial &material) {
  auto state = applyMap(ciphertext, material.input);
  for (std::size_t round = 0; round < material.inputRounds.size(); ++round)
    state = wordRound(state, material.inputRounds[round],
                      material.context[round + 1], false);

  constexpr std::array inverseSchedule{0, 1, 2, 3, 4, 1, 2, 3, 4, 1, 5};
  for (std::size_t round = 0; round < inverseSchedule.size(); ++round)
    state = wordRound(state, material.inverseRounds[inverseSchedule[round]],
                      material.context[round + 3], true);

  const auto late = lateRounds(state, material);
  if (!late)
    return std::nullopt;
  const auto last = wordRound(*late, material.finalRound, material.context[17], false);
  return applyMap(applyMap(last, material.terminal), material.output);
}
} // namespace clarity::portable
