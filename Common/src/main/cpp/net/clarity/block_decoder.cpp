// SPDX-License-Identifier: GPL-3.0-or-later
#include "block_decoder.hpp"

#include <algorithm>
#include <bit>
#include <cstddef>
#include <ranges>

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

void erase(Block &block) {
  volatile Byte *bytes = block.data();
  for (std::size_t i = 0; i < block.size(); ++i)
    bytes[i] = 0;
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

UnwrapResult unwrapSessionKey(std::span<const std::uint8_t> ciphertext,
                              std::span<const std::uint8_t> iv,
                              const BlockMaterial &material) {
  constexpr auto blockSize = Block{}.size();
  if (ciphertext.size() != SessionKey{}.size() || iv.size() != blockSize)
    return UnwrapError::invalidLength;

  Block firstCiphertext, secondCiphertext;
  std::ranges::copy(ciphertext.first<blockSize>(), firstCiphertext.begin());
  std::ranges::copy(ciphertext.last<blockSize>(), secondCiphertext.begin());
  auto first = decodeBlock(firstCiphertext, material);
  if (!first)
    return UnwrapError::decoderFailure;
  auto second = decodeBlock(secondCiphertext, material);
  if (!second) {
    erase(*first);
    return UnwrapError::decoderFailure;
  }

  SessionKey key;
  for (std::size_t i = 0; i < blockSize; ++i) {
    key[i] = (*first)[i] ^ iv[i];
    key[i + blockSize] = (*second)[i] ^ firstCiphertext[i];
  }
  erase(*first);
  erase(*second);
  return key;
}

namespace {
using Perm = std::array<Byte, 256>;
using Basis = std::array<Byte, 8>;

constexpr Byte gfMul(Byte a, Byte b) {
  Byte result = 0;
  for (int bit = 0; bit < 8; ++bit) {
    if (b & 1u)
      result ^= a;
    const bool carry = (a & 0x80u) != 0;
    a = static_cast<Byte>(a << 1);
    if (carry)
      a ^= 0x1bu;
    b >>= 1;
  }
  return result;
}

struct AesTables {
  Perm gfInv{};
  Perm sbox{};
  Perm invSbox{};
  Perm betaCoords{};
  std::array<std::array<Byte, 4>, 4> invMix{};
  std::array<std::array<Byte, 4>, 4> fwdMix{};

  constexpr AesTables() {
    std::array<Byte, 256> exp{};
    Byte cur = 1;
    for (std::size_t i = 0; i < 255; ++i) {
      exp[i] = cur;
      cur = gfMul(cur, 0x03);
    }
    exp[255] = 1;
    for (std::size_t i = 0; i < 255; ++i)
      gfInv[exp[i]] = exp[255 - i];
    for (int x = 0; x < 256; ++x) {
      const Byte inv = gfInv[x];
      const Byte s = static_cast<Byte>(
          inv ^ std::rotl(inv, 1) ^ std::rotl(inv, 2) ^ std::rotl(inv, 3) ^
          std::rotl(inv, 4) ^ 0x63u);
      sbox[x] = s;
      invSbox[s] = static_cast<Byte>(x);
    }
    constexpr std::array<Byte, 4> invCoeff{0x0e, 0x0b, 0x0d, 0x09};
    constexpr std::array<Byte, 4> fwdCoeff{0x02, 0x03, 0x01, 0x01};
    for (std::size_t i = 0; i < 4; ++i)
      for (std::size_t r = 0; r < 4; ++r) {
        invMix[i][r] = invCoeff[(r + 4 - i) % 4];
        fwdMix[i][r] = fwdCoeff[(r + 4 - i) % 4];
      }
    const Byte beta = gfMul(gfMul(invMix[0][0], gfInv[invMix[1][0]]),
                            gfMul(invMix[1][1], gfInv[invMix[0][1]]));
    Basis pows{};
    pows[0] = 1;
    for (std::size_t b = 1; b < 8; ++b)
      pows[b] = gfMul(pows[b - 1], beta);
    for (int mask = 0; mask < 256; ++mask) {
      Byte value = 0;
      for (std::size_t b = 0; b < 8; ++b)
        if ((mask >> b) & 1)
          value ^= pows[b];
      betaCoords[value] = static_cast<Byte>(mask);
    }
  }

  constexpr Byte gfDiv(Byte a, Byte b) const { return gfMul(a, gfInv[b]); }
};

constexpr AesTables aes{};

constexpr Byte evalLinear(const Basis &basis, Byte x) {
  Byte value = 0;
  for (std::size_t b = 0; b < 8; ++b)
    if ((x >> b) & 1u)
      value ^= basis[b];
  return value;
}

bool invertPerm(const Perm &p, Perm &inv) {
  std::array<bool, 256> seen{};
  for (std::size_t i = 0; i < 256; ++i) {
    if (seen[p[i]])
      return false;
    seen[p[i]] = true;
    inv[p[i]] = static_cast<Byte>(i);
  }
  return true;
}

Perm composePerm(const Perm &f, const Perm &g) {
  Perm out{};
  for (std::size_t i = 0; i < 256; ++i)
    out[i] = f[g[i]];
  return out;
}

struct InputEncoding {
  Basis basis{};
  Byte offset = 0;
  bool valid = false;
};
} // namespace

std::optional<SessionKey> recoverSessionWrapKey(const BlockMaterial &material) {
  std::array<std::array<std::array<Perm, 4>, 4>, 6> baseP{};
  std::array<std::array<std::array<InputEncoding, 256>, 16>, 6> candidates{};

  auto rawByte = [&](std::size_t g, std::size_t c, std::size_t i,
                     std::size_t r) {
    const auto pos = inverseShiftPosition(c, r);
    Perm out{};
    for (std::size_t x = 0; x < 256; ++x)
      out[x] = wordByte(material.inverseRounds[g][256 * pos + x], i);
    return out;
  };

  for (std::size_t g = 0; g < 6; ++g) {
    for (std::size_t c = 0; c < 4; ++c) {
      std::array<std::array<Perm, 4>, 4> tTilde{};
      std::array<std::array<Perm, 4>, 4> qMap{};
      for (std::size_t i = 0; i < 4; ++i)
        for (std::size_t r = 0; r < 4; ++r) {
          tTilde[i][r] = rawByte(g, c, i, r);
          const Byte w0 = tTilde[i][r][0];
          for (auto &v : tTilde[i][r])
            v ^= w0;
        }
      for (std::size_t r = 0; r < 4; ++r) {
        Perm inv0{};
        if (!invertPerm(tTilde[0][r], inv0))
          return std::nullopt;
        for (std::size_t i = 0; i < 4; ++i)
          qMap[i][r] = composePerm(tTilde[i][r], inv0);
      }
      Perm q10Inv{};
      if (!invertPerm(qMap[1][0], q10Inv))
        return std::nullopt;
      const Perm ratioMap = composePerm(q10Inv, qMap[1][1]);
      Basis pBasis{};
      pBasis[0] = 1;
      for (std::size_t b = 1; b < 8; ++b)
        pBasis[b] = ratioMap[pBasis[b - 1]];
      for (std::size_t u = 0; u < 256; ++u)
        baseP[g][c][0][u] = evalLinear(pBasis, aes.betaCoords[u]);
      Perm p0Inv{};
      if (!invertPerm(baseP[g][c][0], p0Inv))
        return std::nullopt;
      for (std::size_t i = 1; i < 4; ++i) {
        const Byte scale = aes.gfDiv(aes.invMix[0][0], aes.invMix[i][0]);
        for (std::size_t u = 0; u < 256; ++u)
          baseP[g][c][i][u] =
              qMap[i][0][baseP[g][c][0][gfMul(scale, static_cast<Byte>(u))]];
        Perm piInv{};
        if (!invertPerm(baseP[g][c][i], piInv))
          return std::nullopt;
      }

      for (std::size_t r = 0; r < 4; ++r) {
        const auto pos = inverseShiftPosition(c, r);
        Perm fPos{};
        for (std::size_t x = 0; x < 256; ++x)
          fPos[x] = aes.gfDiv(p0Inv[tTilde[0][r][x]], aes.invMix[0][r]);
        for (std::size_t alpha = 1; alpha < 256; ++alpha) {
          const Byte alphaInv = aes.gfInv[alpha];
          const Byte af1 = gfMul(alphaInv, fPos[1]);
          const Byte af2 = gfMul(alphaInv, fPos[2]);
          const Byte af3 = gfMul(alphaInv, fPos[3]);
          const Byte af4 = gfMul(alphaInv, fPos[4]);
          const Byte af5 = gfMul(alphaInv, fPos[5]);
          for (std::size_t d0 = 0; d0 < 256; ++d0) {
            const Byte q = aes.sbox[d0];
            if ((aes.sbox[af1 ^ d0] ^ aes.sbox[af2 ^ d0] ^ q) !=
                    aes.sbox[af3 ^ d0] ||
                (aes.sbox[af1 ^ d0] ^ aes.sbox[af4 ^ d0] ^ q) !=
                    aes.sbox[af5 ^ d0])
              continue;
            Basis bVals{};
            for (std::size_t b = 0; b < 8; ++b)
              bVals[b] = aes.sbox[gfMul(alphaInv, fPos[1u << b]) ^ d0] ^ q;
            bool ok = true;
            for (std::size_t x : {std::size_t{7}, std::size_t{15},
                                  std::size_t{85}, std::size_t{170},
                                  std::size_t{255}}) {
              const Byte actual =
                  aes.sbox[gfMul(alphaInv, fPos[x]) ^ d0] ^ q;
              if (actual != evalLinear(bVals, static_cast<Byte>(x))) {
                ok = false;
                break;
              }
            }
            if (ok) {
              candidates[g][pos][alpha] = {bVals, q, true};
              break;
            }
          }
          if (!candidates[g][pos][alpha].valid)
            return std::nullopt;
        }
      }
    }
  }

  std::array<std::array<Byte, 4>, 6> alphas{};
  constexpr std::array<std::pair<std::size_t, std::size_t>, 6> transitions{
      {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 1}, {1, 5}}};
  for (const auto &[g1, g2] : transitions) {
    for (std::size_t c = 0; c < 4; ++c) {
      for (std::size_t i = 0; i < 4; ++i) {
        const auto pos = 4 * c + i;
        const auto c2 = (c + i) % 4;
        Byte matchedA1 = 0, matchedA2 = 0;
        std::size_t matchCount = 0;
        const auto &pBase = baseP[g1][c][i];
        for (std::size_t a1 = 1; a1 < 256; ++a1) {
          const Byte p1 = pBase[a1];
          const Byte p2 = pBase[gfMul(static_cast<Byte>(a1), 2)];
          const Byte p4 = pBase[gfMul(static_cast<Byte>(a1), 4)];
          for (std::size_t a2 = 1; a2 < 256; ++a2) {
            const auto &cand = candidates[g2][pos][a2];
            if (evalLinear(cand.basis, p1) != 1 ||
                evalLinear(cand.basis, p2) != 2 ||
                evalLinear(cand.basis, p4) != 4)
              continue;
            bool allBits = true;
            for (std::size_t b = 3; b < 8; ++b)
              if (evalLinear(cand.basis,
                             pBase[gfMul(static_cast<Byte>(a1),
                                         static_cast<Byte>(1u << b))]) !=
                  (1u << b)) {
                allBits = false;
                break;
              }
            if (allBits) {
              matchedA1 = static_cast<Byte>(a1);
              matchedA2 = static_cast<Byte>(a2);
              ++matchCount;
            }
          }
        }
        if (matchCount != 1)
          return std::nullopt;
        if (alphas[g1][c] && alphas[g1][c] != matchedA1)
          return std::nullopt;
        if (alphas[g2][c2] && alphas[g2][c2] != matchedA2)
          return std::nullopt;
        alphas[g1][c] = matchedA1;
        alphas[g2][c2] = matchedA2;
      }
    }
  }

  std::array<std::array<Perm, 16>, 6> pTrue{};
  std::array<std::array<Basis, 16>, 6> aTrue{};
  std::array<std::array<Byte, 16>, 6> qTrue{};
  std::array<std::array<Byte, 16>, 6> constSum{};

  for (std::size_t g = 0; g < 6; ++g) {
    for (std::size_t c = 0; c < 4; ++c) {
      const Byte alpha = alphas[g][c];
      for (std::size_t i = 0; i < 4; ++i)
        for (std::size_t u = 0; u < 256; ++u)
          pTrue[g][4 * c + i][u] =
              baseP[g][c][i][gfMul(alpha, static_cast<Byte>(u))];
      for (std::size_t r = 0; r < 4; ++r) {
        const auto pos = inverseShiftPosition(c, r);
        aTrue[g][pos] = candidates[g][pos][alpha].basis;
        qTrue[g][pos] = candidates[g][pos][alpha].offset;
      }
    }
    for (std::size_t c = 0; c < 4; ++c) {
      for (std::size_t i = 0; i < 4; ++i) {
        Byte sum = 0;
        for (std::size_t r = 0; r < 4; ++r) {
          const auto pos = inverseShiftPosition(c, r);
          const auto raw = rawByte(g, c, i, r);
          const Byte cVal =
              raw[0] ^
              pTrue[g][4 * c + i]
                   [gfMul(aes.invMix[i][r], aes.invSbox[qTrue[g][pos]])];
          for (std::size_t x = 0; x < 256; ++x) {
            const Byte ax =
                evalLinear(aTrue[g][pos], static_cast<Byte>(x)) ^ qTrue[g][pos];
            const Byte expected =
                pTrue[g][4 * c + i]
                     [gfMul(aes.invMix[i][r], aes.invSbox[ax])] ^
                cVal;
            if (raw[x] != expected)
              return std::nullopt;
          }
          sum ^= cVal;
        }
        constSum[g][4 * c + i] = sum;
      }
    }
  }

  constexpr std::array<std::size_t, 11> inverseSchedule{0, 1, 2, 3, 4, 1,
                                                        2, 3, 4, 1, 5};
  std::array<std::array<Byte, 4>, 60> words{};
  for (std::size_t t = 0; t < 10; ++t) {
    const auto g1 = inverseSchedule[t];
    const auto g2 = inverseSchedule[t + 1];
    Block dw{};
    for (std::size_t pos = 0; pos < 16; ++pos) {
      const Byte encoded =
          constSum[g1][pos] ^ material.context[t + 3][pos];
      dw[pos] = evalLinear(aTrue[g2][pos], encoded) ^ qTrue[g2][pos];
    }
    const auto roundIndex = 13 - t;
    for (std::size_t c = 0; c < 4; ++c)
      for (std::size_t i = 0; i < 4; ++i) {
        Byte value = 0;
        for (std::size_t r = 0; r < 4; ++r)
          value ^= gfMul(aes.fwdMix[i][r], dw[4 * c + r]);
        words[4 * roundIndex + c][i] = value;
      }
  }

  constexpr std::array<Byte, 8> rcon{0x00, 0x01, 0x02, 0x04,
                                     0x08, 0x10, 0x20, 0x40};
  auto scheduleStep = [&](std::size_t index,
                          const std::array<Byte, 4> &prev) {
    std::array<Byte, 4> temp = prev;
    if (index % 8 == 0) {
      temp = {aes.sbox[prev[1]], aes.sbox[prev[2]], aes.sbox[prev[3]],
              aes.sbox[prev[0]]};
      temp[0] ^= rcon[index / 8];
    } else if (index % 8 == 4) {
      for (std::size_t j = 0; j < 4; ++j)
        temp[j] = aes.sbox[prev[j]];
    }
    return temp;
  };

  for (int i = 15; i >= 0; --i) {
    const auto next = static_cast<std::size_t>(i + 8);
    const auto temp = scheduleStep(next, words[next - 1]);
    for (std::size_t j = 0; j < 4; ++j)
      words[i][j] = words[next][j] ^ temp[j];
  }

  for (std::size_t i = 8; i < 56; ++i) {
    const auto temp = scheduleStep(i, words[i - 1]);
    for (std::size_t j = 0; j < 4; ++j) {
      const Byte expected = words[i - 8][j] ^ temp[j];
      if (i < 16)
        words[i][j] = expected;
      else if (words[i][j] != expected)
        return std::nullopt;
    }
  }

  SessionKey key{};
  for (std::size_t w = 0; w < 8; ++w)
    for (std::size_t j = 0; j < 4; ++j)
      key[4 * w + j] = words[w][j];
  return key;
}
} // namespace clarity::portable
