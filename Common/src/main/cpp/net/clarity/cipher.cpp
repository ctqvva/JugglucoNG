// SPDX-License-Identifier: GPL-3.0-or-later
#include "cipher.hpp"
#include <dlfcn.h>
#include <memory>
#include <openssl/evp.h>
#ifdef __ANDROID__
extern void *opencrypto();
#endif
namespace clarity {
namespace {
constexpr size_t maxInput = 16 * 1024 * 1024;
void wipe(Bytes &value) {
  volatile uint8_t *p = value.data();
  for (size_t n = value.size(); n; --n)
    *p++ = 0;
}
struct CipherAPI {
  void *handle;
  template <class T> T symbol(const char *name) {
    auto p = reinterpret_cast<T>(dlsym(handle, name));
    if (!p)
      throw Error(std::string("Clarity cipher function unavailable: ") + name);
    return p;
  }
  static void *load() {
#ifdef __ANDROID__
    void *p = opencrypto();
#else
    void *p = dlopen("libcrypto.so", RTLD_NOW | RTLD_LOCAL);
    if (!p)
      p = dlopen("libcrypto.so.3", RTLD_NOW | RTLD_LOCAL);
#endif
    if (!p)
      throw Error("Clarity cipher library unavailable");
    return p; // Same process-lifetime crypto library used by HTTPS/CSR.
  }
  CipherAPI() : handle(load()) {}
#define CIPHER_SYMBOL(name)                                                    \
  decltype(&::name) name = symbol<decltype(&::name)>(#name)
  CIPHER_SYMBOL(EVP_CIPHER_CTX_new);
  CIPHER_SYMBOL(EVP_CIPHER_CTX_free);
  CIPHER_SYMBOL(EVP_aes_256_cbc);
  CIPHER_SYMBOL(EVP_CipherInit_ex);
  CIPHER_SYMBOL(EVP_CIPHER_CTX_set_padding);
  CIPHER_SYMBOL(EVP_CipherUpdate);
  CIPHER_SYMBOL(EVP_CipherFinal_ex);
#undef CIPHER_SYMBOL
};
} // namespace
Bytes aes256cbc(std::span<const uint8_t> key, const Bytes &iv,
                const Bytes &input, bool encrypt) {
  if (key.size() != 32 || iv.size() != 16 || input.empty() ||
      input.size() % 16 || input.size() > maxInput)
    throw Error("Invalid Clarity AES input size");
  static CipherAPI api;
  std::unique_ptr<EVP_CIPHER_CTX, decltype(api.EVP_CIPHER_CTX_free)> ctx(
      api.EVP_CIPHER_CTX_new(), api.EVP_CIPHER_CTX_free);
  if (!ctx)
    throw Error("Cannot allocate Clarity cipher context");
  if (api.EVP_CipherInit_ex(ctx.get(), api.EVP_aes_256_cbc(), nullptr,
                            key.data(), iv.data(), encrypt ? 1 : 0) != 1 ||
      api.EVP_CIPHER_CTX_set_padding(ctx.get(), 0) != 1)
    throw Error("Cannot initialize Clarity AES cipher");
  Bytes result(input.size() + 16);
  int written = 0, tail = 0;
  if (api.EVP_CipherUpdate(ctx.get(), result.data(), &written, input.data(),
                           static_cast<int>(input.size())) != 1 ||
      written < 0 || static_cast<size_t>(written) > input.size() ||
      api.EVP_CipherFinal_ex(ctx.get(), result.data() + written, &tail) != 1 ||
      tail < 0 ||
      static_cast<size_t>(written) + static_cast<size_t>(tail) !=
          input.size()) {
    wipe(result);
    throw Error("Clarity AES operation failed");
  }
  result.resize(input.size());
  return result;
}
} // namespace clarity
