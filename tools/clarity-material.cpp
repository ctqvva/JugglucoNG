// SPDX-License-Identifier: GPL-3.0-or-later
// Extract portable provider material from the verified ARM64 provider.
//
// This tool is deliberately tied to the pinned Juggluco 11.5.6 library: the
// helper and table addresses below are relative to its load base. It runs on
// an ARM64 Android device or emulator with that library available. The output
// contains vendor key material and lookup tables; do not commit or redistribute
// it.
//
// Build (NDK):
//   aarch64-linux-android26-clang++ -std=c++20 -O2 -I Common/src/main/cpp \
//     tools/clarity-material.cpp \
//     Common/src/main/cpp/net/clarity/{material,block_decoder,bytes,logging}.cpp \
//     -lz -ldl -o clarity-material
#include "net/clarity/block_decoder.hpp"
#include "net/clarity/material.hpp"
#include "net/clarity/native_abi.hpp"
#include <array>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <exception>
#include <memory>
#include <string>

using namespace clarity;
using namespace clarity::portable;
using Byte = std::uint8_t;

namespace {
constexpr std::uintptr_t abiAddress = 0x2f20000;
constexpr std::uintptr_t unwrapAddress = 0x26591a4;
constexpr std::uintptr_t inputTransform = 0x26784a0;
constexpr std::uintptr_t inputConfig = 0x28475b0;
constexpr std::uintptr_t outputTransform = 0x271e5f4;
constexpr std::uintptr_t outputConfig = 0x2cfb0d8;

struct Reference {
  void *handle = nullptr;
  const NativeABI *abi = nullptr;
  std::uintptr_t base = 0;

  template <class T> void read(T &out, std::uintptr_t rva) const {
    std::memcpy(&out, reinterpret_cast<const void *>(base + rva), sizeof(out));
  }

  Block inputMap(const Block &block) const {
    using Transform = int (*)(const void *, const void *, std::size_t, void *);
    auto fill = reinterpret_cast<Transform>(base + inputTransform);
    Block result{};
    if (fill(reinterpret_cast<const void *>(base + inputConfig), block.data(),
             block.size(), result.data()) != 0)
      throw Error("Clarity input transform failed");
    return result;
  }

  Block decoded(Block block) const {
    for (std::size_t i = 0; i < block.size(); ++i)
      block[i] = abi->decode[i][block[i]];
    return block;
  }

  Block outputMap(const Block &block) const {
    using Transform =
        int (*)(const void *, const void *, std::size_t, const void *, void *);
    auto fill = reinterpret_cast<Transform>(base + outputTransform);
    Block iv{}, result{};
    if (fill(reinterpret_cast<const void *>(base + outputConfig), iv.data(),
             iv.size(), block.data(), result.data()) != 0)
      throw Error("Clarity output transform failed");
    return decoded(result);
  }

  explicit Reference(const char *path) {
    handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!handle)
      throw Error(std::string("Cannot load provider: ") + dlerror());
    abi = static_cast<const NativeABI *>(dlsym(handle, "jg_clarity_abi"));
    if (!abi || abi->magic != 0x3249544e414c434a || abi->version != 2 ||
        abi->keyCount != 31 || !abi->unwrap)
      throw Error("Unsupported provider ABI");
    base = reinterpret_cast<std::uintptr_t>(abi) - abiAddress;
    if (reinterpret_cast<std::uintptr_t>(abi->unwrap) != base + unwrapAddress)
      throw Error("Provider does not match the verified 11.5.6 ARM64 layout");
  }

  ~Reference() {
    if (handle)
      dlclose(handle);
  }

  void fill(ProviderMaterial &material) const {
    auto &m = material.block;
    std::memcpy(&m.context, abi->context, sizeof(m.context));
    constexpr std::array initial{0x2888e54, 0x2c3928b};
    constexpr std::array inverse{0x28e6110, 0x2d2ba8c, 0x285d184,
                                 0x2b9ec98, 0x2dc984d, 0x2b926fc};
    for (std::size_t i = 0; i < initial.size(); ++i)
      read(m.inputRounds[i], initial[i]);
    for (std::size_t i = 0; i < inverse.size(); ++i)
      read(m.inverseRounds[i], inverse[i]);
    read(m.lateRows, 0x28db930);
    read(m.firstProjections[0].masks, 0x2634770);
    read(m.firstProjections[1].masks, 0x26341f0);
    read(m.lookupTags[0], 0x2634f44);
    read(m.lookupTags[1], 0x2634768);
    read(m.checkTags[0], 0x2634268);
    read(m.checkTags[1], 0x2634a54);
    read(m.firstPackedLookup, 0x2c489d5);

    constexpr std::uintptr_t masks[2][4] = {
        {0x26348b8, 0x2634c78, 0x2634ba0, 0x2634480},
        {0x2634ce8, 0x2634d50, 0x2634ee0, 0x26345d8}};
    constexpr std::uintptr_t tags[2][4] = {
        {0x2634264, 0x2634250, 0x26349e0, 0x2634f40},
        {0x26345d0, 0x2634b3c, 0x2634474, 0x26347d8}};
    for (std::size_t part = 0; part < 2; ++part)
      for (std::size_t column = 0; column < 4; ++column) {
        read(m.secondProjections[part][column].masks,
             masks[part][column]);
        read(m.secondProjections[part][column].tag, tags[part][column]);
      }
    read(m.secondPackedLookup, 0x2de907c);
    read(m.finalRound, 0x2c85d9c);

    constexpr std::array terminal{0x2d90602, 0x2ce8bb8, 0x2dcd86d,
                                  0x28d151e, 0x2d05a58, 0x289cb28,
                                  0x2d70d9c, 0x2888d0c, 0x2db3070,
                                  0x28d5804, 0x2b60a28, 0x2e82c5c,
                                  0x284d160, 0x2d2b98c, 0x2d2fa8c,
                                  0x2d6cc34};
    const auto input0 = inputMap({});
    const auto output0 = outputMap({});
    for (std::size_t i = 0; i < terminal.size(); ++i) {
      m.input[i].offset = input0[i];
      m.output[i].offset = output0[i];
      read(m.terminal[i].offset, terminal[i]);
      for (std::size_t bit = 0; bit < 8; ++bit) {
        Block changed{};
        changed[i] = static_cast<Byte>(1u << bit);
        m.input[i].basis[bit] = inputMap(changed)[i] ^ input0[i];
        m.output[i].basis[bit] = outputMap(changed)[i] ^ output0[i];
        Byte entry{};
        read(entry, terminal[i] + changed[i]);
        m.terminal[i].basis[bit] = entry ^ m.terminal[i].offset;
      }
    }

    std::memcpy(material.decode.data(), abi->decode, sizeof(material.decode));
    material.keys.clear();
    for (const auto &key : abi->keys)
      material.keys.push_back(
          {key.name, Bytes(key.value, key.value + key.size)});
  }
};
} // namespace

int main(int argc, char **argv) {
  bool full = false;
  bool strip = false;
  int argi = 1;
  if (argi < argc && std::string_view(argv[argi]) == "--full") {
    full = true;
    ++argi;
  } else if (argi < argc && std::string_view(argv[argi]) == "--strip") {
    strip = true;
    ++argi;
  }
  if (argc - argi != 2) {
    std::fprintf(
        stderr,
        "usage: %s [--full] <provider.so> <material.mat>\n"
        "       %s --strip <input.mat> <key-only.mat>\n",
        argv[0], argv[0]);
    return 2;
  }
  try {
    if (strip) {
      const auto loaded = loadMaterial(argv[argi]);
      const auto compact = toKeyMaterial(loaded);
      saveMaterial(argv[argi + 1], compact);
      std::printf("wrote %zu keys and %zu bytes (key-only) to %s\n",
                  compact.keys.size(), encodeMaterial(compact).size(),
                  argv[argi + 1]);
      return 0;
    }
    Reference reference(argv[argi]);
    ProviderMaterial material;
    reference.fill(material);
    const auto out = full ? material : toKeyMaterial(material);
    saveMaterial(argv[argi + 1], out);
    std::printf("wrote %zu keys and %zu bytes (%s) to %s\n", out.keys.size(),
                encodeMaterial(out).size(), full ? "full" : "key-only",
                argv[argi + 1]);
    return 0;
  } catch (const std::exception &error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
