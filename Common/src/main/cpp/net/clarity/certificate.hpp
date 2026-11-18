// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "client.hpp"
#include "net/ICE/ContextHTTPS.hpp"
#include <memory>
namespace clarity {
struct ClientCertificate {
    std::shared_ptr<X509> certificate, issuer;
    std::shared_ptr<EVP_PKEY> privateKey;
    HttpsClientIdentity tls() const { return {certificate.get(), privateKey.get(), issuer.get()}; }
    bool validFor(int64_t now, int64_t seconds) const;
};
Json createCertificateKey(); // privateKey (DER/base64url) and CSR (PEM)
Json certificateRequest(const Json &config, const std::string &csr);
ClientCertificate loadCertificate(const Json &record);
bool certificateIdentityMatches(const Json &record, const Json &config);
void saveIssuedCertificate(Json &config, const Json &verifiedPayload,
                            const std::function<void(const Json &)> &save);
void ensureUserCertificate(Json &config, Provider &provider, const Transport &post,
                           const std::function<void(const Json &)> &save,
                           const std::function<void()> &checkEnabled);
HttpResult nativeAccountPost(const Json &config, const std::string &url,
                             const std::string &body, const std::string &headers);
} // namespace clarity
