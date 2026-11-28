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

const ProviderMaterial &defaultKeyMaterial() {
  static const ProviderMaterial instance = [] {
    ProviderMaterial material{};
    material.keyOnly = true;
    material.keys = {
        {"HSK_1F7F_1CFC_0CC1",
         unhex("4c23d77473077e5e5db69bbd372f5fb7337fbe718a76590461b0d85483ea2ca7"
               "c2b015d74c5c9ce534b552b8f429f84efaf38b6db898c7337094d3fbff4c1fdc")},
        {"HSK_20AC_17F5_2B39",
         unhex("ee19f05ce6e5e0dafcdc1ccd6ac43edd29c98d5a66de265fb1ec238bead8c67e"
               "5358995c6d9472d8ebcb8b7c598c315894f166d20b645ecccf706803de09eb12")},
        {"HSK_3CD4_5134_8A6C",
         unhex("9dbdaf844d7598b7f8647f8e414b2c6612cb2151d9a82e438816804bfb6d0e8a"
               "d5725b5867980a227c72f870274b526404e7683ba723debf2ad4e2866382f22b")},
        {"HSK_4191_2034_D8EE",
         unhex("25997956d48c9d9f7754991276c3dc2e82523b687c59a43a1e5d0e6091d4582c"
               "aae42f3fdd8f3e2fb4e1096bd93e1ed3bbd454cdf7cdadaef41c73ec5aafa49c")},
        {"HSK_5AA0_439F_6E0D",
         unhex("b80e9af99a6275c218e32d5f88daadb6ab1ed0fe70a30cd7828b9fe7a2d31892"
               "96369bc4ec20f0aefb6540943c91c316d0b1f023d235f6794e767584df5dc2d7")},
        {"HSK_8CC7_2E20_3E55",
         unhex("dd176ddf275f5a9f41cb5579b1c9eb8b09a30683831c825a3c7cf55595b15e72"
               "3d921f8281066a913b684b0d1d2a0e40d4779c30d57f71ab6386755219653c86")},
        {"HSK_9485_132B_DA26",
         unhex("8d1faec0b3ae8e5fdd66617bbcad771382fad5f5ba6ef3bf94ee2463e74ed236"
               "34d6d90e6738e152ac89729b0b17db0023c2ad4cece06542bd161c23ec534789")},
        {"HSK_AE8B_A0E1_7D79",
         unhex("1fc2eebaac33cfad349f03ef99cda8c24e39f59b834feb64dafe9a4f9513cc63"
               "71699164a49f8d749bf73c957eed69f2d14d477e9f084587012fc3c6cc58c560")},
        {"HSK_CCAC_4838_8EE4",
         unhex("ea8a4fc843fef9fc74eb3f533aad359abec3b70748a94862f37ef5dbc2d273b3"
               "ce56218a7434ae3da965fc1f75fb516b3bc10a30d95f525e2f441fdcf8b86a2f")},
        {"HSK_F58F_2BE9_6ABA",
         unhex("531e47ca38eadef798088ea09c5f465bbbe40e6a30a4b39d8bd672b8470e175a"
               "4590c13fcdbc13518ffed5a5ee378fb0eab7d771697bfdaf02e47f826589b409")},
        {"QEK_0552_661A_9C76",
         unhex("be2026cc3cdbb5f7e11f179c19867571ebc9f8b1f197649bf0e660f9269526a0")},
        {"QEK_2D84_1B85_D4C1",
         unhex("01657b4cb7a5ad41e64d433e177b74879ecebe5352bef1280c44f8bfd91ac017")},
        {"QEK_3F05_FC68_8541",
         unhex("1486306a3d24a84b493f1663ea6950555bb4fcf58f95ac4c6b68ab5ddacc50d8")},
        {"QEK_4C60_1DE1_6A10",
         unhex("3a918c1640280b6e119fde9d1910b0f3ed3d691e36e957eae6b97097ae60668b")},
        {"QEK_53D1_0F2B_7207",
         unhex("de4d68c531214867a7231b64180db608c3300056e5de48ab4e2625fbb9978833")},
        {"QEK_68AF_D556_F5BA",
         unhex("f1597c49d2c0652c9b61963bb46d57a07557f432a869bc9de38aac2d363521c7")},
        {"QEK_8C08_FABB_5EA4",
         unhex("5a7914c766fdf1f95fd1b6c01229a6033ffb8d89753aefc1a7751d6fbf5ed96a")},
        {"QEK_96CA_7041_E6AB",
         unhex("88c5e6bdc5af87a837da76315a2af1eb1600063547d7b8fea9b1c02f4188e998")},
        {"QEK_C4A6_3988_6AFA",
         unhex("37510ca1868296764f9bb0a3da9cde856904559abe564da50331989b00bc2d9d")},
        {"QEK_F447_071F_C481",
         unhex("485617246e225cff3f75889c29726ea1eda39930870e594cd3de7a51b325305a")},
        {"REK_3670_EF3F_BD95",
         unhex("56ff5566cd385f40b31e8db9d826df72f88191e6a70c49a3e6b7fb2634efb1f7")},
        {"REK_37F2_EB86_89CE",
         unhex("b82f9e768cf554abed73d6b39a73f0e42898062649eae84d1641d54628b109b8")},
        {"REK_3BE9_CF81_1905",
         unhex("187e7d1eab73f0ad00bb0eba631251f279936f6051a31464c3e4d31c9f3335f9")},
        {"REK_6991_84E5_5DC2",
         unhex("7b3cf526ff68c1fdb76e54145f667ebe5b4cf51c9dfe45ae56b8ee0f9da5f6bd")},
        {"REK_6E51_4E87_C363",
         unhex("2ef7148a2a38a18d71e20c07c3782568d7d5312bb1b39cd0625ea6c52b4029e3")},
        {"REK_8C75_972F_0FC5",
         unhex("46b093d73020577578d667435e40a465f2c93606a0c4fd943d1fcaa1529b7055")},
        {"REK_D3A1_CC4C_3BD2",
         unhex("d8cbf93c9df325352fe30ffcdd48b7f0ea99c78c41cf1d051b177eef0f2e90d2")},
        {"REK_D88C_C2BA_5211",
         unhex("1a2e09edb4d35c90589de2241f318675d63e3f081fe25454d31e6440abe8b057")},
        {"REK_F01C_B428_9CB4",
         unhex("68297c4468e3ffe1547694ea683a99d9a245d245c60cbcb5cfeb72a81c70bc63")},
        {"REK_F421_609F_20DB",
         unhex("e9e6e5755564aea157687ca82339bb7618bb791d8d3b3dcf796a9c3b990eaa25")},
        {"WBCloakedSigKey",
         unhex("0ce32d0b440cc16554d1597153d4b4afe6d9a9720f94b3dbe6ba6582232173e9"
               "82dff82e8674433e61218a84e32a0d06658ed87bfaa16a17be2faa9a8629fd64")},
        {std::string(sessionWrapKeyName),
         unhex("f66d7b0887c23c8bae7e627659e87d073bcab450dac1aebb88389c3be3b72751")},
    };
    return material;
  }();
  return instance;
}

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
