// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "bytes.hpp"
#include <memory>
#include <variant>
#ifndef CLARITY_PROVIDER_LIBRARY
#define CLARITY_PROVIDER_LIBRARY "libg7clarity.so"
#endif
namespace clarity {
// AES-256-CBC/HMAC-SHA256 with the compact APK-packaged ARM key decoder.
// No JNI, emulation, initialization challenge, or per-instance native arena.
class Engine {
  struct Impl;
  std::unique_ptr<Impl> p;

public:
  using Arg = std::variant<Bytes, std::string>;
  explicit Engine(const std::string &library = CLARITY_PROVIDER_LIBRARY);
  ~Engine();
  Engine(const Engine &) = delete;
  Bytes invoke(const std::string &method, const std::vector<Arg> &args = {});
  std::string stringCall(const std::string &method);
  bool verify(const std::string &method, const std::vector<Arg> &args);
};
class Provider {
  Engine engine;
  Bytes encryption, decryption, signing;
  void eraseKeys();
  Engine::Arg key(const std::string &id, const Bytes &session);

public:
  explicit Provider(const std::string &library = CLARITY_PROVIDER_LIBRARY)
      : engine(library) {}
  ~Provider();
  Json identity();
  std::string deviceId(const std::string &hardwareId);
  void importSession(const Bytes &enc, const Bytes &sig, const Bytes &iv);
  Bytes encrypt(const Json &header, const Bytes &iv, const Bytes &plain);
  Bytes decrypt(const Json &header, const Bytes &iv, const Bytes &cipher);
  Bytes sign(const Json &header, const Bytes &message);
  bool verify(const Json &header, const Bytes &message, const Bytes &signature);
};
} // namespace clarity
