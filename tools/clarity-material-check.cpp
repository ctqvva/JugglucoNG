// SPDX-License-Identifier: GPL-3.0-or-later
// Compare a portable material file with the reference provider's stream.
// The reference tool writes BlockMaterial, a case count and the cases.
#include "net/clarity/block_decoder.hpp"
#include "net/clarity/cipher.hpp"
#include "net/clarity/material.hpp"
#include <array>
#include <cstdint>
#include <cstdio>
#include <memory>

using namespace clarity;
using namespace clarity::portable;

namespace {
struct TestCase {
  SessionKey ciphertext;
  Block iv;
  SessionKey expected;
};
static_assert(sizeof(TestCase) == 80);
} // namespace

int main(int argc, char **argv) {
  if (argc != 2) {
    std::fprintf(stderr, "usage: %s <material.mat|--builtin> < reference-stream\n",
                 argv[0]);
    return 2;
  }
  const auto material = std::string_view(argv[1]) == "--builtin"
                            ? defaultKeyMaterial()
                            : loadMaterial(argv[1]);
  const auto compact = toKeyMaterial(material);
  Bytes wrapKey;
  for (const auto &k : compact.keys)
    if (k.name == sessionWrapKeyName)
      wrapKey = k.value;
  if (wrapKey.size() != 32) {
    std::fprintf(stderr, "failed to resolve AES-256 session-wrap key from material\n");
    return 1;
  }
  std::array<std::uint8_t, sizeof(BlockMaterial)> ignored{};
  if (std::fread(ignored.data(), 1, ignored.size(), stdin) != ignored.size()) {
    std::fprintf(stderr, "short reference material\n");
    return 1;
  }
  std::uint64_t count = 0;
  if (std::fread(&count, sizeof(count), 1, stdin) != 1 || count == 0) {
    std::fprintf(stderr, "invalid reference case count\n");
    return 1;
  }
  TestCase test{};
  for (std::uint64_t i = 0; i < count; ++i) {
    if (std::fread(&test, sizeof(test), 1, stdin) != 1) {
      std::fprintf(stderr, "short reference case %llu\n",
                   static_cast<unsigned long long>(i));
      return 1;
    }
    if (!material.keyOnly) {
      const auto result =
          unwrapSessionKey(test.ciphertext, test.iv, material.block);
      if (result != UnwrapResult(test.expected)) {
        std::fprintf(stderr, "block decoder mismatch in case %llu\n",
                     static_cast<unsigned long long>(i + 1));
        return 1;
      }
    }
    const auto aesResult = aes256cbc(
        wrapKey, Bytes(test.iv.begin(), test.iv.end()),
        Bytes(test.ciphertext.begin(), test.ciphertext.end()), false);
    if (aesResult != Bytes(test.expected.begin(), test.expected.end())) {
      std::fprintf(stderr, "AES-256-CBC mismatch in case %llu\n",
                   static_cast<unsigned long long>(i + 1));
      return 1;
    }
  }
  std::printf("%s material file matched %llu reference unwrap cases\n",
              material.keyOnly ? "key-only" : "full+key-only",
              static_cast<unsigned long long>(count));
  return 0;
}
