// SPDX-License-Identifier: GPL-3.0-or-later
// Host check for the portable Engine and a material file. The optional
// reference stream is the output of tools/clarity-material's reference
// counterpart; it is not part of the repository.
#include "net/clarity/block_decoder.hpp"
#include "net/clarity/cipher.hpp"
#include "net/clarity/engine.hpp"
#include "net/clarity/material.hpp"
#include "net/clarity/protocol.hpp"
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <optional>
#include <random>
#include <string>
#ifdef __ANDROID__
#include <dlfcn.h>
// cipher.cpp loads the platform libcrypto through this app-provided hook.
void *opencrypto() { return dlopen("/system/lib64/libcrypto.so", RTLD_NOW); }
#endif

using namespace clarity;
using namespace clarity::portable;

namespace {
struct TestCase {
  SessionKey ciphertext;
  Block iv;
  SessionKey expected;
};
static_assert(sizeof(TestCase) == 80);

Bytes randomBytesForTest(std::mt19937_64 &random, std::size_t size) {
  Bytes result(size);
  for (auto &byte : result)
    byte = static_cast<std::uint8_t>(random());
  return result;
}

bool checkKeys(const ProviderMaterial &material, Engine &engine) {
  std::mt19937_64 random(0x833);
  for (const auto &key : material.keys) {
    const auto name = key.name;
    if (name.starts_with("QEK_") || name.starts_with("REK_")) {
      const auto iv = randomBytesForTest(random, 16);
      const auto plain = randomBytesForTest(random, 32);
      if (name.starts_with("QEK_")) {
        const auto encrypted = engine.invoke("encrypt", {name, iv, plain});
        if (encrypted != aes256cbc(key.value, iv, plain, true)) {
          std::fprintf(stderr, "encrypt mismatch for %s\n", name.c_str());
          return false;
        }
      } else {
        const auto cipher = randomBytesForTest(random, 32);
        const auto decrypted = engine.invoke("decrypt", {name, iv, cipher});
        if (decrypted != aes256cbc(key.value, iv, cipher, false)) {
          std::fprintf(stderr, "decrypt mismatch for %s\n", name.c_str());
          return false;
        }
      }
    }
    if (name.starts_with("HSK_") || name == "WBCloakedSigKey") {
      const auto message = randomBytesForTest(random, 37);
      const auto signature = engine.invoke("sign", {name, message});
      if (signature != hmac256(key.value, message)) {
        std::fprintf(stderr, "sign mismatch for %s\n", name.c_str());
        return false;
      }
      const Bytes truncated(signature.begin(), signature.begin() + 16);
      auto corrupted = truncated;
      corrupted[0] ^= 1;
      if (!engine.verify("verify", {name, message, truncated}) ||
          engine.verify("verify", {name, message, corrupted})) {
        std::fprintf(stderr, "verify mismatch for %s\n", name.c_str());
        return false;
      }
    }
  }
  return true;
}

bool checkChoices(const ProviderMaterial &material, Engine &engine) {
  std::map<std::string, int> seen;
  for (int i = 0; i < 2000; ++i) {
    const auto encryption = engine.stringCall("chooseEncryptionKeyId");
    const auto signing = engine.stringCall("chooseSigningKeyId");
    if (!encryption.starts_with("QEK_") || !signing.starts_with("HSK_")) {
      std::fprintf(stderr, "invalid chosen key id\n");
      return false;
    }
    ++seen[encryption];
    ++seen[signing];
  }
  std::size_t encryptionKeys = 0, signingKeys = 0;
  for (const auto &key : material.keys) {
    if (key.name.starts_with("QEK_"))
      ++encryptionKeys;
    if (key.name.starts_with("HSK_"))
      ++signingKeys;
  }
  std::size_t seenEncryption = 0, seenSigning = 0;
  for (const auto &[name, count] : seen) {
    if (!count) {
      std::fprintf(stderr, "zero choice count\n");
      return false;
    }
    if (name.starts_with("QEK_"))
      ++seenEncryption;
    if (name.starts_with("HSK_"))
      ++seenSigning;
  }
  if (seenEncryption != encryptionKeys || seenSigning != signingKeys) {
    std::fprintf(stderr, "key choice coverage mismatch\n");
    return false;
  }
  return true;
}

bool checkReference(Engine &engine, const char *path,
                    std::optional<TestCase> *firstCase = nullptr) {
  auto *file = std::fopen(path, "rb");
  if (!file) {
    std::fprintf(stderr, "cannot open %s\n", path);
    return false;
  }
  std::array<std::uint8_t, sizeof(BlockMaterial)> ignored{};
  std::uint64_t count = 0;
  TestCase test{};
  bool ok = std::fread(ignored.data(), 1, ignored.size(), file) == ignored.size() &&
            std::fread(&count, sizeof(count), 1, file) == 1 && count > 0;
  for (std::uint64_t i = 0; ok && i < count; ++i) {
    ok = std::fread(&test, sizeof(test), 1, file) == 1;
    if (!ok)
      break;
    if (i == 0 && firstCase)
      *firstCase = test;
    const auto actual = engine.invoke(
        "decryptToEncryptionKey",
        {Bytes(test.ciphertext.begin(), test.ciphertext.end()),
         Bytes(test.iv.begin(), test.iv.end())});
    if (actual != Bytes(test.expected.begin(), test.expected.end())) {
      std::fprintf(stderr, "reference mismatch in case %llu\n",
                   static_cast<unsigned long long>(i + 1));
      ok = false;
    }
  }
  std::fclose(file);
  if (ok)
    std::printf("Engine matched %llu reference unwrap cases\n",
                static_cast<unsigned long long>(count));
  return ok;
}

bool checkProtocolRoundTrip(const char *materialPath,
                            const ProviderMaterial &material,
                            const TestCase &sample) {
  setenv("CLARITY_MATERIAL_FILE", materialPath, 1);
  // Default-constructed Provider, exactly as used by discoverEndpoints and uploadBytes.
  Provider client;
  const std::string hardwareId = base64(Bytes(16, 0x42), true);
  if (client.deviceId(hardwareId).size() != 22) {
    std::fprintf(stderr, "invalid derived deviceId length\n");
    return false;
  }

  // Client unwraps with argv[1]; server unwraps with compact key-only material
  // (zero white-box tables).
  const auto serverKeyOnly =
      std::make_shared<ProviderMaterial>(toKeyMaterial(material));
  Provider keyOnlyServer(serverKeyOnly);

  const std::string account = "11111111-2222-4333-8444-555555555555";
  const std::string installation = "66666666-7777-4888-9999-aaaaaaaaaaaa";
  const Json config{{"account", account},
                    {"appInstanceId", "22222222-3333-4444-8555-666666666666"}};
  const auto identity = client.identity();

  client.importSession(Bytes(sample.ciphertext.begin(), sample.ciphertext.end()),
                       Bytes(sample.ciphertext.begin(), sample.ciphertext.end()),
                       Bytes(sample.iv.begin(), sample.iv.end()));
  keyOnlyServer.importSession(
      Bytes(sample.ciphertext.begin(), sample.ciphertext.end()),
      Bytes(sample.ciphertext.begin(), sample.ciphertext.end()),
      Bytes(sample.iv.begin(), sample.iv.end()));

  const auto bulkId = uuid();
  const auto bulkHeader = messageHeader(config, identity, true, bulkId);
  const auto postJson = makePost(account, installation, 1, Json::object(), "");
  const auto bulkWire = seal(client, bulkHeader, postJson.dump());
  const auto openedBulk = openResponse(keyOnlyServer, bulkWire, bulkId, account);
  if (Json::parse(text(openedBulk.plain)) != postJson) {
    std::fprintf(stderr, "session-key bulk payload round-trip mismatch\n");
    return false;
  }

  const auto receipt = uuid();
  Json ackHeader = bulkHeader;
  ackHeader["EncKey"]["ID"] = "Private";
  ackHeader["SaltId"] = "Private";
  const auto ackWire = seal(keyOnlyServer, ackHeader, receipt);
  const auto openedAck = openResponse(client, ackWire, bulkId, account);
  if (text(openedAck.plain) != receipt) {
    std::fprintf(stderr, "session-key acknowledgment round-trip mismatch\n");
    return false;
  }
  std::printf("portable Provider session import and bulk protocol round-trip passed\n");
  return true;
}
} // namespace

int main(int argc, char **argv) {
  if (argc < 2 || argc > 3) {
    std::fprintf(stderr, "usage: %s <material.mat> [reference-stream]\n",
                 argv[0]);
    return 2;
  }
  try {
    const auto material = loadMaterial(argv[1]);
    const auto keyOnly =
        std::make_shared<ProviderMaterial>(toKeyMaterial(material));
    auto shared = std::make_shared<ProviderMaterial>(material);
    Engine engine(shared);
    if (!checkKeys(*shared, engine) || !checkChoices(*shared, engine))
      return 1;
    std::optional<TestCase> sample;
    if (argc == 3 && !checkReference(engine, argv[2], &sample))
      return 1;
    if (!material.keyOnly) {
      Engine keyOnlyEngine(keyOnly);
      if (argc == 3 && !checkReference(keyOnlyEngine, argv[2]))
        return 1;
    }
    if (sample && !checkProtocolRoundTrip(argv[1], material, *sample))
      return 1;
    std::printf("portable Engine key table, AES/HMAC and key selection passed\n");
    return 0;
  } catch (const std::exception &error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
