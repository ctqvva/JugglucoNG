// SPDX-License-Identifier: GPL-3.0-or-later
// Standard AES/HMAC plus the small, APK-packaged session-key decoder.
#include "engine.hpp"
#include "cipher.hpp"
#include "native_abi.hpp"
#include <algorithm>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
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
} // namespace
struct Engine::Impl {
  void *library = nullptr;
  const NativeABI *abi = nullptr;
  explicit Impl(const std::string &path) {
    library = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!library) {
      const char *reason = dlerror();
      diagnostic("error: Clarity provider load failed: %s",
                 reason ? reason : "unknown");
      throw Error("Cannot load Clarity provider: " + path);
    }
    try {
      abi =
          reinterpret_cast<const NativeABI *>(dlsym(library, "jg_clarity_abi"));
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
  }
  void close() {
    if (library && dlclose(library) != 0)
      diagnostic("error: Clarity provider close failed");
    library = nullptr;
    abi = nullptr;
  }
  ~Impl() { close(); }
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
    for (const auto &k : abi->keys)
      if (std::string_view(k.name).starts_with(prefix) && index-- == 0)
        return k.name;
    throw Error("Incomplete Clarity application key table");
  }
  Bytes unwrap(const Bytes &cipher, const Bytes &iv) {
    if (cipher.size() != 32 || iv.size() != 16)
      throw Error("Invalid encrypted Clarity session key");
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
