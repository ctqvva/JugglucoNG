// SPDX-License-Identifier: GPL-3.0-or-later
#include "auth.hpp"
#include <algorithm>
#include <set>
namespace clarity {
static std::string endpointPath(const std::string &url) {
    const auto begin = url.find('/', 8);
    return url.substr(begin, url.find('?', begin) - begin);
}
static std::string redirectTarget(const std::string &current, const std::string &location) {
    if (location.empty() || location.size() > 2048 ||
        std::any_of(location.begin(), location.end(),
                    [](unsigned char c) { return c <= 32 || c == 127 || c == '\\' || c == '#'; }))
        throw Error("Invalid or missing Dexcom redirect Location");
    std::string target;
    const auto origin = current.substr(0, current.find('/', 8));
    const auto base = current.substr(0, current.find('?'));
    if (location.starts_with("https://"))
        target = location;
    else if (location.starts_with("//"))
        target = "https:" + location;
    else if (location.starts_with('/'))
        target = origin + location;
    else if (location.starts_with('?'))
        target = base + location;
    else {
        // Reject other schemes instead of treating them as relative paths.
        const auto colon = location.find(':');
        if (colon != location.npos && colon < location.find_first_of("/?"))
            throw Error("Dexcom redirect requires HTTPS");
        target = base.substr(0, base.rfind('/') + 1) + location;
    }
    validateDexcomUrl(target);
    return target;
}
HttpResult postWithRedirects(const std::string &url, const std::string &body,
                             const std::string &headers, const Transport &singlePost) {
    validateDexcomUrl(url);
    const auto originalPath = endpointPath(url);
    std::string current = url;
    std::set<std::string> visited{current};
    constexpr unsigned maxRedirects = 3;
    for (unsigned hop = 0;; ++hop) {
        auto response = singlePost(current, body, headers);
        if (response.status != 307 && response.status != 308)
            return response;
        if (hop == maxRedirects)
            throw Error("Too many Dexcom POST redirects");
        auto target = redirectTarget(current, response.location);
        const auto path = endpointPath(target);
        // The global identity router redirects POST /identity/connect/token to
        // accounts-api.dexcom.com/connect/token. Preserve its code/verifier or
        // refresh token and client registration exactly, but only to a Dexcom
        // HTTPS token endpoint. Other API redirects must keep their API path.
        const bool tokenRedirect = originalPath.ends_with("/connect/token") &&
                                   (path == "/connect/token" || path == "/identity/connect/token");
        if (path != originalPath && !tokenRedirect)
            throw Error("Unexpected Dexcom redirect endpoint");
        if (!visited.insert(target).second)
            throw Error("Dexcom POST redirect loop");
        const auto safeTarget = target.substr(0, target.find('?'));
        diagnostic("following HTTP %d POST redirect %u/%u to %s", response.status, hop + 1,
                   maxRedirects, safeTarget.c_str());
        current = std::move(target);
    }
}
} // namespace clarity
