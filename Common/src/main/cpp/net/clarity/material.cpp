// SPDX-License-Identifier: GPL-3.0-or-later
#include "material.hpp"
#include <cstring>
#include <type_traits>

namespace clarity {
namespace {
constexpr std::array<char, 8> magic{'J', 'G', 'C', 'L', 'M', 'A', 'T', '\0'};
constexpr std::uint32_t currentVersion = 1;

struct Header {
  char magic[8];
  std::uint32_t version;
  std::uint32_t headerSize;
  std::uint64_t blockSize;
  std::uint64_t decodeSize;
  std::uint64_t keyCount;
};
static_assert(std::is_trivially_copyable_v<Header>);
static_assert(sizeof(Header) == 40);

struct KeyRecord {
  char name[32];
  std::uint64_t size;
  std::uint8_t value[64];
};
static_assert(std::is_trivially_copyable_v<KeyRecord>);
static_assert(sizeof(KeyRecord) == 104);

static_assert(std::is_trivially_copyable_v<portable::BlockMaterial>);

template <class T> void append(Bytes &out, const T &value) {
  const auto *bytes = reinterpret_cast<const std::uint8_t *>(&value);
  out.insert(out.end(), bytes, bytes + sizeof(value));
}

template <class T> T take(std::span<const std::uint8_t> &file) {
  if (file.size() < sizeof(T))
    throw Error("Truncated Clarity material file");
  T value;
  std::memcpy(&value, file.data(), sizeof(value));
  file = file.subspan(sizeof(value));
  return value;
}
} // namespace

Bytes encodeMaterial(const ProviderMaterial &material) {
  if (material.keys.size() > maxApplicationKeys)
    throw Error("Too many Clarity application keys");
  for (const auto &key : material.keys) {
    if (key.name.empty() || key.name.size() >= sizeof(KeyRecord::name))
      throw Error("Invalid Clarity application key name");
    if (key.value.size() != 32 && key.value.size() != 64)
      throw Error("Invalid Clarity application key size");
  }

  Header header{};
  std::memcpy(header.magic, magic.data(), magic.size());
  header.version = currentVersion;
  header.headerSize = sizeof(Header);
  header.blockSize = sizeof(portable::BlockMaterial);
  header.decodeSize = sizeof(material.decode);
  header.keyCount = material.keys.size();

  Bytes out;
  out.reserve(sizeof(header) + sizeof(material.block) + sizeof(material.decode) +
              material.keys.size() * sizeof(KeyRecord));
  append(out, header);
  append(out, material.block);
  append(out, material.decode);
  for (const auto &key : material.keys) {
    KeyRecord record{};
    std::memcpy(record.name, key.name.data(), key.name.size());
    record.size = key.value.size();
    std::memcpy(record.value, key.value.data(), key.value.size());
    append(out, record);
  }
  return out;
}

ProviderMaterial parseMaterial(std::span<const std::uint8_t> file) {
  auto header = take<Header>(file);
  if (std::memcmp(header.magic, magic.data(), magic.size()) != 0)
    throw Error("Not a Clarity material file");
  if (header.version != currentVersion)
    throw Error("Unsupported Clarity material version");
  if (header.headerSize != sizeof(Header) ||
      header.blockSize != sizeof(portable::BlockMaterial) ||
      header.decodeSize != sizeof(ProviderMaterial::decode) ||
      header.keyCount > maxApplicationKeys)
    throw Error("Invalid Clarity material header");
  const auto expected = header.blockSize + header.decodeSize +
                        header.keyCount * sizeof(KeyRecord);
  if (file.size() != expected)
    throw Error("Invalid Clarity material length");

  ProviderMaterial material;
  material.block = take<portable::BlockMaterial>(file);
  std::memcpy(material.decode.data(), file.data(), header.decodeSize);
  file = file.subspan(header.decodeSize);
  material.keys.reserve(header.keyCount);
  for (std::uint64_t i = 0; i < header.keyCount; ++i) {
    const auto record = take<KeyRecord>(file);
    const auto *end = std::memchr(record.name, 0, sizeof(record.name));
    if (!end || end == record.name)
      throw Error("Invalid Clarity application key name");
    if (record.size != 32 && record.size != 64)
      throw Error("Invalid Clarity application key size");
    material.keys.push_back(
        {std::string(record.name), Bytes(record.value, record.value + record.size)});
  }
  return material;
}

ProviderMaterial loadMaterial(const std::string &path) {
  const auto contents = readFile(path);
  return parseMaterial({reinterpret_cast<const std::uint8_t *>(contents.data()),
                        contents.size()});
}

void saveMaterial(const std::string &path, const ProviderMaterial &material) {
  const auto contents = encodeMaterial(material);
  atomicFile(path, std::string_view(reinterpret_cast<const char *>(contents.data()),
                                    contents.size()));
}
} // namespace clarity
