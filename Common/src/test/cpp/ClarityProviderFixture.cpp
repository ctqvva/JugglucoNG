// SPDX-License-Identifier: GPL-3.0-or-later
#include "net/clarity/native_abi.hpp"
#include <cstdio>
#include <initializer_list>

namespace {
// A reversible test transform with separate context, IV and decode steps. The
// fixture has no Dexcom material and makes no assumptions about its decoder.
int unwrap(const void *context, const void *cipher, size_t size, void *iv,
           void *out, int format) {
  if (size != 32 || format != 1)
    return 1;
  const auto *wrapped = static_cast<const uint8_t *>(cipher);
  if (wrapped[0] == 0xff && wrapped[1] == 0xff)
    return 2;
  const auto *mask = static_cast<const uint8_t *>(context);
  auto *localIV = static_cast<uint8_t *>(iv);
  auto *decoded = static_cast<uint8_t *>(out);
  for (size_t i = 0; i < size; ++i)
    decoded[i] = wrapped[i] ^ mask[i] ^ localIV[i % 16];
  localIV[0] ^= 0xff;
  return 0;
}

clarity::NativeABI fixture() {
  clarity::NativeABI abi{};
  abi.magic = 0x3249544e414c434a;
  abi.version = 2;
  abi.unwrap = unwrap;
  abi.keyCount = 31;
  for (size_t pos = 0; pos < 16; ++pos) {
    for (size_t value = 0; value < 256; ++value) {
      abi.decode[pos][value] = static_cast<uint8_t>(value + pos);
#ifdef CLARITY_TEST_DIFFERENT_DECODER
      if (pos == 0 && value == 0)
        ++abi.decode[pos][value];
#endif
    }
  }
  for (size_t i = 0; i < 32; ++i)
    abi.context[i] = static_cast<uint8_t>(i * 9);

  size_t slot = 0;
  for (const char *family : {"QEK", "REK", "HSK"}) {
    for (unsigned index = 0; index < 10; ++index) {
      auto &key = abi.keys[slot++];
      std::snprintf(key.name, sizeof(key.name), "%s_test_%u", family, index);
      key.size = family[0] == 'H' ? 64 : 32;
      for (size_t i = 0; i < key.size; ++i)
        key.value[i] = static_cast<uint8_t>(i + index);
    }
  }
  auto &identity = abi.keys[slot];
  std::snprintf(identity.name, sizeof(identity.name), "WBCloakedSigKey");
  identity.size = 64;
  return abi;
}
} // namespace

extern "C" const clarity::NativeABI jg_clarity_abi = fixture();
