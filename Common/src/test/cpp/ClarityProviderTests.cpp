// SPDX-License-Identifier: GPL-3.0-or-later
#include "net/clarity/engine.hpp"
#include <algorithm>
#include <array>
#include <iostream>
#include <optional>

#ifdef __ANDROID__
#include <dlfcn.h>
// The comparison runner is independent of the app's HTTPS stack. This hook
// is reached only by the fixture's AES tests, when a libcrypto is available.
void *opencrypto() { return dlopen("libcrypto.so", RTLD_NOW | RTLD_LOCAL); }
#endif

namespace {
using namespace clarity;

void require(bool condition, std::string_view message) {
  if (!condition)
    throw std::runtime_error(std::string(message));
}

void expectError(auto operation, std::string_view reason) {
  try {
    operation();
  } catch (const Error &error) {
    require(error.what() == reason, "unexpected provider error");
    return;
  }
  throw std::runtime_error("provider accepted an invalid operation");
}

constexpr std::array unwrapMethods{"decryptToEncryptionKey",
                                   "decryptToDecryptionKey",
                                   "decryptToSigningKey"};

void testStandardCrypto(Engine &engine) {
  // NIST SP 800-38A F.2.5, first two blocks.
  const auto key = unhex("603deb1015ca71be2b73aef0857d7781"
                         "1f352c073b6108d72d9810a30914dff4");
  const auto iv = unhex("000102030405060708090a0b0c0d0e0f");
  const auto plain = unhex("6bc1bee22e409f96e93d7e117393172a"
                           "ae2d8a571e03ac9c9eb76fac45af8e51");
  const auto cipher = unhex("f58c4c04d6e5f1ba779eabfb5f7bfbd6"
                            "9cfc4e967edb808d679f777bc6702c7d");
  require(engine.invoke("encryptwk", {key, iv, plain}) == cipher,
          "raw AES encryption vector differs");
  require(engine.invoke("decryptwk", {key, iv, cipher}) == plain,
          "raw AES decryption vector differs");

  // RFC 4231 case 1, with its short key zero-padded to the raw key width.
  Bytes signingKey(32);
  std::fill_n(signingKey.begin(), 20, 0x0b);
  const auto message = bytes("Hi There");
  const auto signature =
      unhex("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
  require(engine.invoke("signwk", {signingKey, message}) == signature,
          "raw HMAC vector differs");
  Bytes tag(signature.begin(), signature.begin() + 16);
  require(engine.verify("verifywk", {signingKey, message, tag}),
          "valid truncated HMAC was rejected");
  tag.back() ^= 1;
  require(!engine.verify("verifywk", {signingKey, message, tag}),
          "altered HMAC was accepted");
  require(!engine.verify("verifywk", {signingKey, message, signature}),
          "wrong-length HMAC was accepted");
}

void testApplicationKeys(Engine &engine) {
  for (const auto &[method, prefix] :
       {std::pair{"chooseEncryptionKeyId", "QEK_test_"},
        std::pair{"chooseSigningKeyId", "HSK_test_"}}) {
    for (int i = 0; i < 20; ++i) {
      const auto id = engine.stringCall(method);
      require(id.starts_with(prefix) && id.size() == 10 &&
                  id.back() >= '0' && id.back() <= '9',
              "selected an ID outside its application key family");
    }
  }

  const auto iv = unhex("000102030405060708090a0b0c0d0e0f");
  const auto plain = unhex("6bc1bee22e409f96e93d7e117393172a");
  const auto cipher = unhex("e07836277c862d6e5be37b990bd2d641");
  const auto message = bytes("Clarity test vector");
  const auto signature =
      unhex("1c9dc3480e33da69933bfb67e058b0b1dbde3f54853027938805ec9156ec6bb8");
  require(engine.invoke("encrypt", {"QEK_test_0", iv, plain}) == cipher &&
              engine.invoke("decrypt", {"REK_test_0", iv, cipher}) == plain,
          "application AES vectors differ");
  require(engine.invoke("sign", {"HSK_test_0", message}) == signature,
          "application HMAC vector differs");
  require(engine.verify("verify", {"HSK_test_0", message,
                                   Bytes(signature.begin(), signature.begin() + 16)}),
          "valid application key signature was rejected");
  expectError([&] { engine.invoke("encrypt", {"REK_test_0", iv, plain}); },
              "Invalid Clarity application key ID");
  expectError([&] { engine.invoke("sign", {"HSK_test_missing", message}); },
              "Unknown Clarity application key ID");
}

void testSessionImport(Engine &engine, const char *fixture) {
  const auto wrappedEncryption = unhex("000102030405060708090a0b0c0d0e0f"
                                       "101112131415161718191a1b1c1d1e1f");
  const auto wrappedSigning = unhex("202122232425262728292a2b2c2d2e2f"
                                    "303132333435363738393a3b3c3d3e3f");
  const auto iv = unhex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
  const auto encryption =
      unhex("f0fae4eed8e2ccd6c0aab49ea8929c86707a444e58622c36400a141e28f2fc06");
  const auto signing =
      unhex("d0dac4cef802ecf6a08a94bec8b2bc66505a646e78820c16202a343e48d2dce6");
  for (const auto *method : unwrapMethods)
    require(engine.invoke(method, {wrappedEncryption, iv}) == encryption,
            "decoder output differs across the ABI boundary");

  expectError([&] { engine.invoke(unwrapMethods[0], {Bytes(31), iv}); },
              "Invalid encrypted Clarity session key");
  expectError([&] { engine.invoke(unwrapMethods[0], {wrappedEncryption, Bytes(15)}); },
              "Invalid encrypted Clarity session key");

  Provider provider(fixture);
  const Json request = {{"EncKey", {{"ID", "PRIVATE"}}}, {"SaltId", "PRIVATE"}};
  const Json response = {{"EncKey", {{"ID", "Private"}}}, {"SaltId", "Private"}};
  const auto payload = bytes("Synthetic record");
  expectError([&] { provider.sign(request, payload); }, "No Clarity session keys");
  provider.importSession(wrappedEncryption, wrappedSigning, iv);
  const auto cipher = provider.encrypt(request, iv, payload);
  const auto signature = provider.sign(request, payload);
  require(cipher == engine.invoke("encryptwk", {encryption, iv, payload}) &&
              provider.decrypt(response, iv, cipher) == payload,
          "session encryption or decryption key differs");
  require(signature == engine.invoke("signwk", {signing, payload}) &&
              provider.verify(response, payload,
                              Bytes(signature.begin(), signature.begin() + 16)),
          "session signing key differs");

  expectError([&] { provider.importSession(wrappedSigning, Bytes(32, 0xff), iv); },
              "Clarity session-key import failed");
  require(provider.encrypt(request, iv, payload) == cipher &&
              provider.sign(request, payload) == signature,
          "failed rekey replaced part of the previous session");
}

std::optional<Bytes> decode(Engine &engine, const char *method,
                            const Bytes &cipher, const Bytes &iv) {
  try {
    return engine.invoke(method, {cipher, iv});
  } catch (const Error &error) {
    if (std::string_view(error.what()) != "Clarity session-key import failed")
      throw;
    return std::nullopt;
  }
}

void compareDecoders(Engine &reference, Engine &candidate) {
  unsigned accepted = 0;
  unsigned rejected = 0;
  const auto compare = [&](const Bytes &cipher, const Bytes &iv) {
    for (const auto *method : unwrapMethods) {
      const auto expected = decode(reference, method, cipher, iv);
      const auto actual = decode(candidate, method, cipher, iv);
      require(expected == actual, "decoder mismatch at operation " +
                                      std::to_string(accepted + rejected + 1) +
                                      " (" + method + ")");
      if (expected)
        ++accepted;
      else
        ++rejected;
    }
  };

  compare(Bytes(32), Bytes(16));
  compare(Bytes(32, 0xff), Bytes(16, 0xff));
  const auto cipher = unhex("000102030405060708090a0b0c0d0e0f"
                            "101112131415161718191a1b1c1d1e1f");
  const auto iv = unhex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
  compare(cipher, iv);
  // Flip each ciphertext and IV bit individually, starting from the same input.
  for (size_t bit = 0; bit < (cipher.size() + iv.size()) * 8; ++bit) {
    auto changedCipher = cipher;
    auto changedIV = iv;
    const size_t byte = bit / 8;
    auto &changed = byte < cipher.size() ? changedCipher[byte]
                                        : changedIV[byte - cipher.size()];
    changed ^= 1u << (bit % 8);
    compare(changedCipher, changedIV);
  }
  require(accepted != 0, "both decoders rejected the entire synthetic corpus");
  std::cout << "Decoder comparison passed (" << accepted << " accepted, "
            << rejected << " rejected operations)\n";
}
} // namespace

int main(int argc, char **argv) {
  try {
    if (argc == 2) {
      clarity::Engine engine(argv[1]);
      testStandardCrypto(engine);
      testApplicationKeys(engine);
      testSessionImport(engine, argv[1]);
      std::cout << "Clarity provider fixture tests passed\n";
    } else if (argc == 3) {
      clarity::Engine reference(argv[1]), candidate(argv[2]);
      compareDecoders(reference, candidate);
    } else {
      std::cerr << "usage: clarity-provider-test FIXTURE | REFERENCE CANDIDATE\n";
      return 2;
    }
  } catch (const std::exception &error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
