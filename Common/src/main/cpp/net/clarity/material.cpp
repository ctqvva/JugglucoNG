// SPDX-License-Identifier: GPL-3.0-or-later
#include "material.hpp"
#include <algorithm>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <type_traits>
#include <unistd.h>

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

Bytes readMaterialFile(const std::string &path) {
  int openFlags = O_RDONLY | O_CLOEXEC | O_NOFOLLOW;
  int fd = ::open(path.c_str(), openFlags);
  if (fd < 0) {
    diagnostic("error: open Clarity material: errno=%d (%s)", errno,
               std::strerror(errno));
    throw Error("Cannot read Clarity material file");
  }
  struct Close {
    int fd;
    ~Close() {
      if (fd >= 0)
        ::close(fd);
    }
  } closer{fd};

  struct stat st{};
  if (::fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) {
    diagnostic("error: Clarity material is not a regular file");
    throw Error("Invalid Clarity material file");
  }
  if (st.st_uid != ::geteuid() || (st.st_mode & 0077) != 0) {
    diagnostic("error: Clarity material has unsafe ownership or permissions");
    throw Error("Unsafe Clarity material file permissions");
  }
  constexpr std::size_t maxMaterialSize = 512 * 1024;
  if (st.st_size <= 0 ||
      static_cast<std::size_t>(st.st_size) > maxMaterialSize) {
    diagnostic("error: invalid Clarity material file size");
    throw Error("Invalid Clarity material length");
  }
  Bytes out(static_cast<std::size_t>(st.st_size));
  std::size_t at = 0;
  while (at < out.size()) {
    const auto n = ::read(fd, out.data() + at, out.size() - at);
    if (n < 0 && errno == EINTR)
      continue;
    if (n <= 0)
      throw Error("Cannot read Clarity material file");
    at += static_cast<std::size_t>(n);
  }
  std::uint8_t extra = 0;
  if (::read(fd, &extra, 1) != 0)
    throw Error("Invalid Clarity material length");
  return out;
}
} // namespace

ProviderMaterial toKeyMaterial(const ProviderMaterial &material) {
  Bytes wrapKey;
  for (const auto &k : material.keys)
    if (k.name == sessionWrapKeyName) {
      if (k.value.size() != 32)
        throw Error("Invalid Clarity session-wrap key size");
      wrapKey = k.value;
      break;
    }
  if (wrapKey.empty()) {
    if (material.keyOnly)
      throw Error("Missing Clarity session-wrap key");
    const auto recovered = portable::recoverSessionWrapKey(material.block);
    if (!recovered)
      throw Error("Cannot recover Clarity session-wrap key from material");
    wrapKey.assign(recovered->begin(), recovered->end());
  }

  ProviderMaterial compact{};
  compact.keyOnly = true;
  std::size_t qek = 0, rek = 0, hsk = 0, cloaked = 0;
  for (const auto &k : material.keys) {
    if (k.name == sessionWrapKeyName)
      continue;
    if (k.name.starts_with("QEK_") && k.value.size() == 32)
      ++qek;
    else if (k.name.starts_with("REK_") && k.value.size() == 32)
      ++rek;
    else if (k.name.starts_with("HSK_") && k.value.size() == 64)
      ++hsk;
    else if (k.name == "WBCloakedSigKey" && k.value.size() == 64)
      ++cloaked;
    else
      throw Error("Invalid Clarity application key entry");
    compact.keys.push_back(k);
  }
  if (qek != 10 || rek != 10 || hsk != 10 || cloaked != 1)
    throw Error("Incomplete Clarity application key table");
  compact.keys.push_back({std::string(sessionWrapKeyName), std::move(wrapKey)});
  return compact;
}

Bytes encodeMaterial(const ProviderMaterial &material) {
  if (material.keys.size() > maxApplicationKeys)
    throw Error("Too many Clarity application keys");
  bool hasWrapKey = false;
  for (const auto &key : material.keys) {
    if (key.name.empty() || key.name.size() >= sizeof(KeyRecord::name))
      throw Error("Invalid Clarity application key name");
    if (key.value.size() != 32 && key.value.size() != 64)
      throw Error("Invalid Clarity application key size");
    if (key.name == sessionWrapKeyName) {
      if (key.value.size() != 32)
        throw Error("Invalid Clarity session-wrap key size");
      hasWrapKey = true;
    }
  }
  if (material.keyOnly && !hasWrapKey)
    throw Error("Key-only Clarity material requires SessionWrapKey");

  Header header{};
  std::memcpy(header.magic, magic.data(), magic.size());
  header.version = currentVersion;
  header.headerSize = sizeof(Header);
  header.blockSize = material.keyOnly ? 0 : sizeof(portable::BlockMaterial);
  header.decodeSize = material.keyOnly ? 0 : sizeof(material.decode);
  header.keyCount = material.keys.size();

  Bytes out;
  out.reserve(sizeof(header) + header.blockSize + header.decodeSize +
              material.keys.size() * sizeof(KeyRecord));
  append(out, header);
  if (!material.keyOnly) {
    append(out, material.block);
    append(out, material.decode);
  }
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
  const bool keyOnly = header.blockSize == 0 && header.decodeSize == 0;
  const bool full = header.blockSize == sizeof(portable::BlockMaterial) &&
                    header.decodeSize == sizeof(ProviderMaterial::decode);
  if (header.headerSize != sizeof(Header) || (!keyOnly && !full) ||
      header.keyCount > maxApplicationKeys)
    throw Error("Invalid Clarity material header");
  const auto expected = header.blockSize + header.decodeSize +
                        header.keyCount * sizeof(KeyRecord);
  if (file.size() != expected)
    throw Error("Invalid Clarity material length");

  ProviderMaterial material;
  material.keyOnly = keyOnly;
  if (!keyOnly) {
    material.block = take<portable::BlockMaterial>(file);
    std::memcpy(material.decode.data(), file.data(), header.decodeSize);
    file = file.subspan(header.decodeSize);
  }
  material.keys.reserve(header.keyCount);
  bool hasWrapKey = false;
  for (std::uint64_t i = 0; i < header.keyCount; ++i) {
    const auto record = take<KeyRecord>(file);
    const auto *end = std::memchr(record.name, 0, sizeof(record.name));
    if (!end || end == record.name)
      throw Error("Invalid Clarity application key name");
    if (record.size != 32 && record.size != 64)
      throw Error("Invalid Clarity application key size");
    std::string name(record.name);
    if (name == sessionWrapKeyName) {
      if (record.size != 32)
        throw Error("Invalid Clarity session-wrap key size");
      hasWrapKey = true;
    }
    material.keys.push_back(
        {std::move(name), Bytes(record.value, record.value + record.size)});
  }
  if (keyOnly && !hasWrapKey)
    throw Error("Key-only Clarity material requires SessionWrapKey");
  return material;
}

ProviderMaterial loadMaterial(const std::string &path) {
  const auto contents = readMaterialFile(path);
  return parseMaterial(contents);
}

void saveMaterial(const std::string &path, const ProviderMaterial &material) {
  const auto contents = encodeMaterial(material);
  atomicFile(path, std::string_view(reinterpret_cast<const char *>(contents.data()),
                                    contents.size()));
}

void saveKeyMaterial(const std::string &path, const ProviderMaterial &material) {
  saveMaterial(path, toKeyMaterial(material));
}
} // namespace clarity
