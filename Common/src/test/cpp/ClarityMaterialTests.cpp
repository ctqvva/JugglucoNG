// SPDX-License-Identifier: GPL-3.0-or-later
#include "net/clarity/material.hpp"
#include <bit>
#include <cstdio>
#include <cstring>
#include <string>

using namespace clarity;

namespace {
int failures = 0;

void expect(bool condition, const char *what) {
  if (!condition) {
    std::fprintf(stderr, "FAIL: %s\n", what);
    ++failures;
  }
}

template <class Function> void expectThrow(Function function, const char *what) {
  try {
    function();
    std::fprintf(stderr, "FAIL: %s did not throw\n", what);
    ++failures;
  } catch (const Error &) {
  }
}

ProviderMaterial sample() {
  ProviderMaterial material{};
  for (std::size_t i = 0; i < sizeof(material.block); ++i)
    reinterpret_cast<std::uint8_t *>(&material.block)[i] =
        static_cast<std::uint8_t>(i * 37 + 11);
  for (std::size_t row = 0; row < material.decode.size(); ++row)
    for (std::size_t column = 0; column < material.decode[row].size(); ++column)
      material.decode[row][column] =
          static_cast<std::uint8_t>(row * 17 + column * 31);
  material.keys.push_back({"QEK_TEST", Bytes(32, 0x5a)});
  material.keys.push_back({"HSK_TEST", Bytes(64, 0xa5)});
  return material;
}

void roundTrip() {
  const auto original = sample();
  const auto encoded = encodeMaterial(original);
  const auto decoded = parseMaterial(encoded);
  expect(std::memcmp(&decoded.block, &original.block, sizeof(original.block)) == 0,
         "block material round-trip");
  expect(decoded.decode == original.decode, "decode rows round-trip");
  expect(decoded.keys.size() == original.keys.size(), "key count round-trip");
  for (std::size_t i = 0; i < original.keys.size(); ++i) {
    expect(decoded.keys[i].name == original.keys[i].name, "key name round-trip");
    expect(decoded.keys[i].value == original.keys[i].value, "key value round-trip");
  }
}

void rejects() {
  auto encoded = encodeMaterial(sample());
  auto broken = encoded;
  broken[0] = 'X';
  expectThrow([&] { parseMaterial(broken); }, "bad magic");
  expectThrow([&] { parseMaterial({encoded.data(), encoded.size() - 1}); },
              "truncated file");
  expectThrow([&] { parseMaterial({encoded.data(), 4}); }, "short header");
  auto version = encoded;
  version[8] = 2;
  expectThrow([&] { parseMaterial(version); }, "unsupported version");

  auto tooMany = sample();
  for (std::size_t i = 0; i < maxApplicationKeys; ++i)
    tooMany.keys.push_back({"QEK_EXTRA", Bytes(32, 0)});
  expectThrow([&] { encodeMaterial(tooMany); }, "too many keys");

  auto badSize = sample();
  badSize.keys[0].value.resize(31);
  expectThrow([&] { encodeMaterial(badSize); }, "bad key size");

  auto badName = sample();
  badName.keys[0].name.assign(32, 'A');
  expectThrow([&] { encodeMaterial(badName); }, "long key name");
}

void defaults() {
  ProviderMaterial material{};
  expect(material.keys.empty(), "default has no keys");
  expect(encodeMaterial(material).size() ==
             40 + sizeof(portable::BlockMaterial) + sizeof(material.decode),
         "empty material size");
}

std::uint8_t gfMul(std::uint8_t a, std::uint8_t b) {
  std::uint8_t r = 0;
  for (int bit = 0; bit < 8; ++bit) {
    if (b & 1u)
      r ^= a;
    const bool carry = (a & 0x80u) != 0;
    a = static_cast<std::uint8_t>(a << 1);
    if (carry)
      a ^= 0x1bu;
    b >>= 1;
  }
  return r;
}

void whiteBoxKeyRecovery() {
  using Byte = std::uint8_t;
  std::array<Byte, 256> gfInv{}, sbox{}, invSbox{};
  for (int x = 1; x < 256; ++x)
    for (int y = 1; y < 256; ++y)
      if (gfMul(static_cast<Byte>(x), static_cast<Byte>(y)) == 1)
        gfInv[x] = static_cast<Byte>(y);
  for (int x = 0; x < 256; ++x) {
    const Byte inv = gfInv[x];
    const Byte s = static_cast<Byte>(
        inv ^ std::rotl(inv, 1) ^ std::rotl(inv, 2) ^ std::rotl(inv, 3) ^
        std::rotl(inv, 4) ^ 0x63u);
    sbox[x] = s;
    invSbox[s] = static_cast<Byte>(x);
  }

  // NIST SP 800-38A F.1.5 AES-256 key.
  const auto rawKey = unhex("603deb1015ca71be2b73aef0857d7781"
                            "1f352c073b6108d72d9810a30914dff4");
  portable::SessionKey expectedKey{};
  std::memcpy(expectedKey.data(), rawKey.data(), expectedKey.size());

  std::array<std::array<Byte, 4>, 60> w{};
  for (std::size_t i = 0; i < 8; ++i)
    for (std::size_t j = 0; j < 4; ++j)
      w[i][j] = expectedKey[4 * i + j];
  constexpr std::array<Byte, 8> rcon{0x00, 0x01, 0x02, 0x04,
                                     0x08, 0x10, 0x20, 0x40};
  for (std::size_t i = 8; i < 60; ++i) {
    auto temp = w[i - 1];
    if (i % 8 == 0) {
      temp = {sbox[temp[1]], sbox[temp[2]], sbox[temp[3]], sbox[temp[0]]};
      temp[0] ^= rcon[i / 8];
    } else if (i % 8 == 4) {
      for (auto &b : temp)
        b = sbox[b];
    }
    for (std::size_t j = 0; j < 4; ++j)
      w[i][j] = w[i - 8][j] ^ temp[j];
  }

  constexpr std::array<Byte, 4> invCoeff{0x0e, 0x0b, 0x0d, 0x09};
  std::array<std::array<Byte, 4>, 4> invMix{};
  for (std::size_t i = 0; i < 4; ++i)
    for (std::size_t r = 0; r < 4; ++r)
      invMix[i][r] = invCoeff[(r + 4 - i) % 4];

  // Build non-identity GF(2^8) column encodings and affine offsets across 6 groups.
  constexpr std::array<std::array<Byte, 4>, 6> scales{{
      {3, 5, 17, 29},
      {7, 11, 19, 37},
      {13, 23, 41, 53},
      {31, 43, 59, 67},
      {3, 5, 17, 29},
      {71, 71, 71, 71},
  }};
  std::array<std::array<Byte, 16>, 6> q{};
  std::array<std::array<Byte, 16>, 6> constSum{};
  portable::BlockMaterial block{};

  for (std::size_t g = 0; g < 6; ++g)
    for (std::size_t pos = 0; pos < 16; ++pos)
      q[g][pos] = static_cast<Byte>((g * 29 + pos * 17 + 0x35) & 0xff);

  constexpr std::array<std::size_t, 6> prevGroup{0, 0, 1, 2, 3, 1};
  for (std::size_t g = 0; g < 6; ++g) {
    for (std::size_t c = 0; c < 4; ++c) {
      const Byte outScale = scales[g][c];
      for (std::size_t r = 0; r < 4; ++r) {
        const auto srcCol = (c + 4 - r) % 4;
        const auto pos = 4 * srcCol + r;
        const Byte inScaleInv = gfInv[scales[prevGroup[g]][srcCol]];
        std::array<Byte, 4> cVal{};
        for (std::size_t i = 0; i < 4; ++i) {
          cVal[i] = static_cast<Byte>((g * 43 + c * 19 + r * 11 + i * 7 + 5) & 0xff);
          constSum[g][4 * c + i] ^= cVal[i];
        }
        for (std::size_t x = 0; x < 256; ++x) {
          const Byte ax = gfMul(inScaleInv, static_cast<Byte>(x)) ^ q[g][pos];
          const Byte s = invSbox[ax];
          portable::Word word = 0;
          for (std::size_t i = 0; i < 4; ++i) {
            const Byte y = gfMul(outScale, gfMul(invMix[i][r], s)) ^ cVal[i];
            word |= portable::Word{y} << (8 * i);
          }
          block.inverseRounds[g][256 * pos + x] = word;
        }
      }
    }
  }

  constexpr std::array<std::size_t, 11> schedule{0, 1, 2, 3, 4, 1, 2, 3, 4, 1, 5};
  for (std::size_t t = 0; t < 10; ++t) {
    const auto g1 = schedule[t];
    const auto g2 = schedule[t + 1];
    const auto roundIndex = 13 - t;
    for (std::size_t c = 0; c < 4; ++c) {
      for (std::size_t i = 0; i < 4; ++i) {
        Byte dw = 0;
        for (std::size_t r = 0; r < 4; ++r)
          dw ^= gfMul(invMix[i][r], w[4 * roundIndex + c][r]);
        const auto pos = 4 * c + i;
        const Byte inScale = scales[g1][c];
        block.context[t + 3][pos] =
            constSum[g1][pos] ^ gfMul(inScale, dw ^ q[g2][pos]);
      }
    }
  }

  const auto recovered = portable::recoverSessionWrapKey(block);
  expect(recovered.has_value(), "BGE white-box AES-256 key recovery succeeded");
  if (recovered)
    expect(*recovered == expectedKey, "BGE recovered exact NIST AES-256 key");

  auto corrupted = block;
  corrupted.context[5][0] ^= 1;
  expect(!portable::recoverSessionWrapKey(corrupted).has_value(),
         "BGE rejects inconsistent AES-256 key schedule");
}
} // namespace

int main() {
  roundTrip();
  rejects();
  defaults();
  whiteBoxKeyRecovery();
  if (failures)
    return 1;
  std::puts("Clarity material serialization tests passed");
  return 0;
}
