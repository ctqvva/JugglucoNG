// SPDX-License-Identifier: GPL-3.0-or-later
#include "certificate.hpp"
#include "auth.hpp"
#include <ctime>
namespace clarity {
bool certificateIdentityMatches(const Json &record, const Json &config) {
    if (!record.is_object())
        return false;
    for (const char *key : {"account", "hardwareId", "deviceId"})
        if (!record.contains(key) || record.at(key) != config.at(key))
            return false;
    return true;
}
Json certificateRequest(const Json &c, const std::string &csr) {
    return {{"DeviceId", c.at("deviceId")}, {"HardwareId", c.at("hardwareId")},
            {"RuntimeInfo", c.at("runtimeInfo")}, {"UserCsr", csr}};
}
void saveIssuedCertificate(Json &c, const Json &payload,
                            const std::function<void(const Json &)> &save) {
    if (!c.contains("mtlsPending") || !certificateIdentityMatches(c.at("mtlsPending"), c))
        throw Error("Pending certificate request belongs to another identity");
    auto record = c.at("mtlsPending");
    record.erase("csr");
    record["certificate"] = payload.at("Certificate");
    record["issuer"] = payload.at("Issuer");
    const auto cert = loadCertificate(record);
    if (!cert.validFor(time(nullptr), 86400))
        throw Error("Issued client certificate is expired, not yet valid or too short lived");
    auto next = c;
    next["mtls"] = std::move(record);
    next.erase("mtlsPending");
    save(next); // No session/bulk POST before durable storage of the usable identity.
    c = std::move(next);
    diagnostic("TLS client certificate verified and saved; ready for authenticated HTTPS");
}
void ensureUserCertificate(Json &c, Provider &provider, const Transport &post,
                           const std::function<void(const Json &)> &save,
                           const std::function<void()> &checkEnabled) {
    if (c.contains("mtls") && certificateIdentityMatches(c.at("mtls"), c)) {
        const auto cert = loadCertificate(c.at("mtls"));
        if (cert.validFor(time(nullptr), 86400)) {
            diagnostic("cached TLS client certificate is valid");
            return;
        }
        diagnostic("TLS client certificate expires within one day; requesting renewal");
    }
    if (!c.contains("certificateUrl")) {
        checkEnabled();
        diagnostic("discovering certificate service for existing account");
        discoverEndpoints(c, post, provider);
        save(c);
    }
    if (!c.contains("mtlsPending") || !certificateIdentityMatches(c.at("mtlsPending"), c)) {
        checkEnabled();
        auto pending = createCertificateKey();
        for (const char *key : {"account", "hardwareId", "deviceId"})
            pending[key] = c.at(key);
        auto next = c;
        next["mtlsPending"] = std::move(pending);
        // Keep the same key/CSR after a lost response or process restart.
        save(next);
        c = std::move(next);
        diagnostic("native P-256 client key and CSR saved before certificate request");
    }
    const auto identity = provider.identity();
    const auto h = messageHeader(c, identity, false, uuid());
    const auto account = c.at("account").get<std::string>();
    const auto request = certificateRequest(c, c.at("mtlsPending").at("csr"));
    const auto headers =
        "\r\nContent-Type: application/json\r\nAccept: application/json\r\nAuthorization: Bearer " +
        c.at("accessToken").get<std::string>() + "\r\nX-Account-Id: " + account +
        "\r\nX-Software-Id: " + identity.at("SoftwareId").get<std::string>() +
        "\r\nX-Trace-Id: " + h.at("RequestId").get<std::string>() +
        "\r\nX-Security-Schema: SignedMessage";
    checkEnabled();
    diagnostic("requesting Dexcom TLS user certificate");
    const auto r = post(c.at("certificateUrl"), seal(provider, h, request.dump()), headers);
    if (r.status == 401) {
        httpFailure("Dexcom client certificate", r, c);
        refreshAccess(c, post, save, true);
        throw Error("Access token refreshed; retry certificate request");
    }
    if (r.status != 200)
        throw Error(httpFailure("Dexcom client certificate", r, c));
    const auto response = openResponse(provider, r.body, h.at("RequestId"), account,
                                       ResponseBinding::CertificateEnrollment);
    const auto payload = Json::parse(text(response.plain));
    saveIssuedCertificate(c, payload, save);
}
} // namespace clarity
