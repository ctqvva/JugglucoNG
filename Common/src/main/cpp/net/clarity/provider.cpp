// SPDX-License-Identifier: GPL-3.0-or-later
#include "engine.hpp"
namespace clarity {
static void erase(Bytes &b) {
  volatile uint8_t *at = b.data();
  for (size_t n = b.size(); n; --n)
    *at++ = 0;
}
void Provider::eraseKeys() {
  erase(encryption);
  erase(decryption);
  erase(signing);
}
Provider::~Provider() { eraseKeys(); }
static bool privateKey(const std::string &id) {
  // G7 requests use "PRIVATE", while captured server responses use "Private".
  // Both refer to imported session keys, never to a public-key identifier.
  return id == "Private" || id == "PRIVATE";
}
Engine::Arg Provider::key(const std::string &id, const Bytes &session) {
  if (id.empty() || id.size() > 128)
    throw Error("Invalid provider key ID");
  if (!privateKey(id))
    return id;
  if (session.empty())
    throw Error("No Clarity session keys");
  return session;
}
Json Provider::identity() {
  return {{"SoftwareId", engine.stringCall("getSoftwareId")},
          {"EncryptionKeyId", engine.stringCall("chooseEncryptionKeyId")},
          {"SigningKeyId", engine.stringCall("chooseSigningKeyId")},
          {"EngineSigningKeyId", engine.stringCall("getEngineSigningKeyId")}};
}
std::string Provider::deviceId(const std::string &hardwareId) {
  if (hardwareId.size() != 22)
    throw Error("Invalid Clarity hardware ID");
  const auto raw = unbase64(hardwareId, true);
  if (raw.size() != 16 || base64(raw, true) != hardwareId)
    throw Error("Invalid Clarity hardware ID");
  // SecureNetworkManager.generateDeviceId in G7 signs the UTF-8 text,
  // including the base64url HardwareId string, not its decoded bytes.
  Json header = {{"SaltId", engine.stringCall("getEngineSigningKeyId")}};
  auto signature =
      sign(header, bytes(engine.stringCall("getSoftwareId") + hardwareId));
  signature.resize(16);
  return base64(signature, true);
}
void Provider::importSession(const Bytes &enc, const Bytes &sig,
                             const Bytes &iv) {
  if (enc.size() != 32 || sig.size() != 32 || iv.size() != 16)
    throw Error("Invalid encrypted session keys");
  auto e = engine.invoke("decryptToEncryptionKey", {enc, iv}),
       d = e, // AES uses the same raw key in both directions.
      s = engine.invoke("decryptToSigningKey", {sig, iv});
  if (e.size() != 32 || d.size() != 32 || s.size() != 32)
    throw Error("Session key import failed");
  eraseKeys();
  encryption = std::move(e);
  decryption = std::move(d);
  signing = std::move(s);
}
static void blocks(const Bytes &iv, const Bytes &b) {
  if (iv.size() != 16 || b.empty() || b.size() % 16)
    throw Error("Invalid crypto block length");
}
Bytes Provider::encrypt(const Json &h, const Bytes &iv, const Bytes &b) {
  blocks(iv, b);
  std::string id = h.at("EncKey").at("ID");
  return engine.invoke(privateKey(id) ? "encryptwk" : "encrypt",
                       {key(id, encryption), iv, b});
}
Bytes Provider::decrypt(const Json &h, const Bytes &iv, const Bytes &b) {
  blocks(iv, b);
  std::string id = h.at("EncKey").at("ID");
  return engine.invoke(privateKey(id) ? "decryptwk" : "decrypt",
                       {key(id, decryption), iv, b});
}
Bytes Provider::sign(const Json &h, const Bytes &b) {
  std::string id = h.at("SaltId");
  auto r =
      engine.invoke(privateKey(id) ? "signwk" : "sign", {key(id, signing), b});
  if (r.size() != 32)
    throw Error("Invalid signature length");
  return r;
}
bool Provider::verify(const Json &h, const Bytes &b, const Bytes &s) {
  if (s.size() != 16) {
    diagnostic("error: invalid response signature size=%zu", s.size());
    return false;
  }
  std::string id = h.at("SaltId");
  bool valid = engine.verify(privateKey(id) ? "verifywk" : "verify",
                             {key(id, signing), b, s});
  if (!valid)
    diagnostic("error: response signature verification failed");
  return valid;
}
} // namespace clarity
