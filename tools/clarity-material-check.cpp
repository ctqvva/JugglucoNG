// SPDX-License-Identifier: GPL-3.0-or-later
// Compare a portable material file with the reference provider's stream.
// The reference tool writes BlockMaterial, a case count and the cases.
#include "net/clarity/block_decoder.hpp"
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
    std::fprintf(stderr, "usage: %s <material.mat> < reference-stream\n",
                 argv[0]);
    return 2;
  }
  const auto material = loadMaterial(argv[1]);
  const auto wrapKey = recoverSessionWrapKey(material.block);
  if (!wrapKey) {
    std::fprintf(stderr, "failed to recover AES-256 session-wrap key from material\n");
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
    const auto result = unwrapSessionKey(test.ciphertext, test.iv, material.block);
    if (result != UnwrapResult(test.expected)) {
      std::fprintf(stderr, "mismatch in case %llu\n",
                   static_cast<unsigned long long>(i + 1));
      return 1;
    }
  }
  std::printf("material file matched %llu reference unwrap cases\n",
              static_cast<unsigned long long>(count));
  return 0;
}
