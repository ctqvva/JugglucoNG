// SPDX-License-Identifier: GPL-3.0-or-later
// Standard AES/HMAC plus either the small ARM session-key decoder or portable
// material extracted from a trusted provider.
#include "engine.hpp"
#include "block_decoder.hpp"
#include "cipher.hpp"
#include "material.hpp"
#include "native_abi.hpp"
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <memory>
#include <mutex>
#include <optional>
#include <unistd.h>
#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif
#ifdef CLARITY_JUGGLUCO_LOG
extern std::string_view globalbasedir;
#endif
namespace clarity {
namespace {
constexpr size_t maxInput = 16 * 1024 * 1024;
void wipe(Bytes &value) {
  volatile uint8_t *p = value.data();
  for (size_t n = value.size(); n; --n)
    *p++ = 0;
}
// The retained decoder has a write-only obfuscation scratch word. Its calls
// are serialized across Engine instances; regular AES/HMAC has no such state.
std::mutex importMutex;
const Bytes &binary(const std::vector<Engine::Arg> &args, size_t at) {
  if (at < args.size())
    if (const auto *b = std::get_if<Bytes>(&args[at]))
      return *b;
  throw Error("Invalid Clarity crypto argument type");
}
void arity(const std::vector<Engine::Arg> &args, size_t count) {
  if (args.size() != count)
    throw Error("Invalid Clarity crypto argument count");
}
#ifndef CLARITY_JUGGLUCO_LOG
bool materialPath(const std::string &path) {
  return path.ends_with(".mat") || path.ends_with(".clarity-material");
}
#endif
std::string configuredMaterialPath(const std::string &path) {
#ifdef CLARITY_JUGGLUCO_LOG
  (void)path;
  if (globalbasedir.empty())
    return {};
  const auto candidate = std::string(globalbasedir) + "/clarity-indexed/clarity.mat";
  if (::access(candidate.c_str(), F_OK) == 0 || errno != ENOENT)
    return candidate;
  return {};
#else
  if (const char *environment = std::getenv("CLARITY_MATERIAL_FILE");
      environment && *environment)
    return environment;
#ifdef __ANDROID__
  char property[PROP_VALUE_MAX]{};
  if (__system_property_get("debug.juggluco.clarity_material", property) > 0 &&
      *property)
    return property;
#endif
  if (materialPath(path))
    return path;
  return {};
#endif
}
std::optional<Bytes> resolveSessionWrapKey(const ProviderMaterial &material) {
  for (const auto &key : material.keys)
    if (key.name == sessionWrapKeyName && key.value.size() == 32)
      return key.value;
  if (material.keyOnly)
    return std::nullopt;
  if (const auto recovered = portable::recoverSessionWrapKey(material.block))
    return Bytes(recovered->begin(), recovered->end());
  return std::nullopt;
}
void validate(const ProviderMaterial &material) {
  if (material.keys.empty() || material.keys.size() > maxApplicationKeys)
    throw Error("Invalid Clarity provider key table");
  bool hasWrapKey = false;
  for (const auto &key : material.keys) {
    if (key.name.empty() || key.name.size() >= sizeof(NativeKey::name) ||
        (key.value.size() != 32 && key.value.size() != 64) ||
        (key.name == sessionWrapKeyName && key.value.size() != 32))
      throw Error("Invalid Clarity provider key table");
    if (key.name == sessionWrapKeyName)
      hasWrapKey = true;
  }
  if (material.keyOnly && !hasWrapKey)
    throw Error("Invalid Clarity provider key table");
}
} // namespace
struct Engine::Impl {
  void *library = nullptr;
  const NativeABI *abi = nullptr;
  std::shared_ptr<const ProviderMaterial> material;
  std::optional<Bytes> sessionWrapKey;
  bool explicitSessionWrapKey = false;
  void initPortable() {
    validate(*material);
    for (const auto &k : material->keys)
      if (k.name == sessionWrapKeyName)
        explicitSessionWrapKey = true;
    sessionWrapKey = resolveSessionWrapKey(*material);
    if (material->keyOnly && !sessionWrapKey)
      throw Error("Missing Clarity session-wrap key");
    diagnostic(
        "portable crypto provider ready (AES/HMAC and session-key decoder)");
  }
  explicit Impl(std::shared_ptr<const ProviderMaterial> value)
      : material(std::move(value)) {
    if (!material)
      throw Error("Missing Clarity provider material");
    try {
      initPortable();
    } catch (...) {
      diagnostic("error: portable Clarity provider initialization failed");
      throw;
    }
  }
  explicit Impl(const std::string &path) {
    const auto source = configuredMaterialPath(path);
    if (!source.empty()) {
      try {
        material =
            std::make_shared<const ProviderMaterial>(loadMaterial(source));
        initPortable();
      } catch (...) {
        diagnostic("error: portable Clarity provider initialization failed");
        throw;
      }
      return;
    }
#ifndef CLARITY_JUGGLUCO_LOG
    if (!path.empty() && path != CLARITY_PROVIDER_LIBRARY &&
        path != "builtin") {
      library = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
      if (!library) {
        const char *reason = dlerror();
        diagnostic("error: Clarity provider load failed: %s",
                   reason ? reason : "unknown");
        throw Error("Cannot load Clarity provider: " + path);
      }
      try {
        abi = reinterpret_cast<const NativeABI *>(
            dlsym(library, "jg_clarity_abi"));
        if (!abi || abi->magic != 0x3249544e414c434a || abi->version != 2 ||
            !abi->unwrap || abi->keyCount != 31)
          throw Error("Clarity provider ABI mismatch: " + path);
        for (const auto &k : abi->keys)
          if (!std::memchr(k.name, 0, sizeof(k.name)) ||
              (k.size != 32 && k.size != 64))
            throw Error("Invalid Clarity provider key table");
      } catch (...) {
        diagnostic("error: compact Clarity provider initialization failed");
        close();
        throw;
      }
      diagnostic(
          "compact crypto provider ready (AES/HMAC and session-key decoder)");
      return;
    }
#endif
    try {
      material = std::shared_ptr<const ProviderMaterial>(
          std::shared_ptr<const ProviderMaterial>{}, &defaultKeyMaterial());
      initPortable();
    } catch (...) {
      diagnostic("error: built-in Clarity provider initialization failed");
      throw;
    }
  }
  void close() {
    if (library && dlclose(library) != 0)
      diagnostic("error: Clarity provider close failed");
    library = nullptr;
    abi = nullptr;
  }
  ~Impl() {
    if (sessionWrapKey)
      wipe(*sessionWrapKey);
    close();
  }
  std::span<const uint8_t> key(const Arg &arg, bool session,
                               std::string_view prefix) {
    if (session) {
      const auto *k = std::get_if<Bytes>(&arg);
      if (!k || k->size() != 32)
        throw Error("Invalid raw Clarity session key");
      return *k;
    }
    const auto *name = std::get_if<std::string>(&arg);
    if (!name || !(name->starts_with(prefix) ||
                   (prefix == "HSK_" && *name == "WBCloakedSigKey")))
      throw Error("Invalid Clarity application key ID");
    if (material) {
      for (const auto &k : material->keys)
        if (*name == k.name)
          return k.value;
      throw Error("Unknown Clarity application key ID");
    }
    for (const auto &k : abi->keys)
      if (*name == k.name)
        return {k.value, static_cast<size_t>(k.size)};
    throw Error("Unknown Clarity application key ID");
  }
  std::string choose(std::string_view prefix) {
    uint8_t choice;
    do {
      choice = randomBytes(1)[0];
    } while (choice >= 250);
    size_t index = choice % 10;
    if (material) {
      for (const auto &k : material->keys)
        if (std::string_view(k.name).starts_with(prefix) && index-- == 0)
          return k.name;
      throw Error("Incomplete Clarity application key table");
    }
    for (const auto &k : abi->keys)
      if (std::string_view(k.name).starts_with(prefix) && index-- == 0)
        return k.name;
    throw Error("Incomplete Clarity application key table");
  }
  Bytes unwrap(const Bytes &cipher, const Bytes &iv) {
    if (cipher.size() != 32 || iv.size() != 16)
      throw Error("Invalid encrypted Clarity session key");
    if (material) {
      if (explicitSessionWrapKey && sessionWrapKey)
        return aes256cbc(*sessionWrapKey, iv, cipher, false);
      const auto decoded =
          portable::unwrapSessionKey(cipher, iv, material->block);
      if (const auto *key = std::get_if<portable::SessionKey>(&decoded)) {
        Bytes result(key->begin(), key->end());
        if (sessionWrapKey &&
            aes256cbc(*sessionWrapKey, iv, cipher, false) != result) {
          wipe(result);
          diagnostic("error: Clarity session-key AES schedule mismatch");
          throw Error("Clarity session-key import failed");
        }
        return result;
      }
      diagnostic("error: portable Clarity session-key decoder failed");
      throw Error("Clarity session-key import failed");
    }
    Bytes localIV(iv), result(32);
    int status;
    {
      std::lock_guard lock(importMutex);
      status = abi->unwrap(abi->context, cipher.data(), cipher.size(),
                           localIV.data(), result.data(), 1);
    }
    if (status != 0) {
      wipe(result);
      diagnostic("error: Clarity session-key decoder returned %d", status);
      throw Error("Clarity session-key import failed");
    }
    for (size_t i = 0; i < result.size(); ++i)
      result[i] = abi->decode[i % 16][result[i]];
    return result;
  }
};
Engine::Engine(const std::string &library)
    : p(std::make_unique<Impl>(library)) {}
Engine::Engine(std::shared_ptr<const ProviderMaterial> material)
    : p(std::make_unique<Impl>(std::move(material))) {}
Engine::~Engine() = default;
Bytes Engine::invoke(const std::string &method, const std::vector<Arg> &args) {
  if (method == "decryptToEncryptionKey" ||
      method == "decryptToDecryptionKey" || method == "decryptToSigningKey") {
    arity(args, 2);
    return p->unwrap(binary(args, 0), binary(args, 1));
  }
  if (method == "encrypt" || method == "encryptwk" || method == "decrypt" ||
      method == "decryptwk") {
    arity(args, 3);
    const bool encrypt = method.starts_with("encrypt");
    return aes256cbc(
        p->key(args[0], method.ends_with("wk"), encrypt ? "QEK_" : "REK_"),
        binary(args, 1), binary(args, 2), encrypt);
  }
  if (method == "sign" || method == "signwk") {
    arity(args, 2);
    const auto &message = binary(args, 1);
    if (message.size() > maxInput)
      throw Error("Clarity signing input exceeds size limit");
    return hmac256(p->key(args[0], method == "signwk", "HSK_"), message);
  }
  throw Error("Unsupported Clarity crypto operation");
}
std::string Engine::stringCall(const std::string &method) {
  if (method == "getSoftwareId")
    return "0300wAECCzACDwAA0000";
  if (method == "getEngineSigningKeyId")
    return "WBCloakedSigKey";
  if (method == "chooseEncryptionKeyId")
    return p->choose("QEK_");
  if (method == "chooseSigningKeyId")
    return p->choose("HSK_");
  throw Error("Unsupported Clarity crypto identity operation");
}
bool Engine::verify(const std::string &method, const std::vector<Arg> &args) {
  arity(args, 3);
  if (method != "verify" && method != "verifywk")
    throw Error("Unsupported Clarity signature verification operation");
  const auto &signature = binary(args, 2);
  if (signature.size() != 16) {
    diagnostic("error: invalid Clarity signature length=%zu", signature.size());
    return false;
  }
  const auto actual =
      invoke(method == "verifywk" ? "signwk" : "sign", {args[0], args[1]});
  uint8_t difference = 0;
  for (size_t i = 0; i < 16; ++i)
    difference |= actual[i] ^ signature[i];
  if (difference)
    diagnostic("error: Clarity signature mismatch");
  return difference == 0;
}
} // namespace clarity
