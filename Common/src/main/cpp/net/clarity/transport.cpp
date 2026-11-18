// SPDX-License-Identifier: GPL-3.0-or-later
#include "client.hpp"
#include "certificate.hpp"
#include "net/ICE/ContextHTTPS.hpp"
#include <ctime>
namespace clarity {
static HttpResult send(const std::string &url, const std::string &body, const std::string &headers,
                        const HttpsClientIdentity *client) {
    return postWithRedirects(
        url, body, headers,
        [&](const std::string &target, const std::string &content, const std::string &fields) {
            if (client && target.substr(0, target.find('/', 8)) != url.substr(0, url.find('/', 8)))
                throw Error("Refusing client certificate redirect to another host");
            const auto slash = target.find('/', 8); // validated by postWithRedirects
            std::string host = target.substr(8, slash - 8), path = target.substr(slash), location;
            HTTPSRequestOptions options;
            options.timeoutMilliseconds = 45000; // upstream's per-request deadline
            options.location = &location;
            options.client = client;
            auto [data, status] = ContextHTTPS::getContext().request(
                host, 443, path, "POST", std::span(content.data(), content.size()), fields,
                options);
            return HttpResult{status, {data.begin(), data.end()}, std::move(location)};
        });
}
HttpResult nativePost(const std::string &url, const std::string &body, const std::string &headers) {
    return send(url, body, headers, nullptr);
}
HttpResult nativeAccountPost(const Json &c, const std::string &url, const std::string &body,
                             const std::string &headers) {
    if (url != c.value("sessionUrl", "") && url != c.value("bulkUrl", ""))
        return nativePost(url, body, headers);
    if (!c.contains("mtls") || !certificateIdentityMatches(c.at("mtls"), c))
        throw Error("TLS client certificate is missing or belongs to another identity");
    const auto cert = loadCertificate(c.at("mtls"));
    if (!cert.validFor(time(nullptr), 0))
        throw Error("TLS client certificate is expired or not yet valid");
    const auto client = cert.tls();
    return send(url, body, headers, &client);
}
} // namespace clarity
