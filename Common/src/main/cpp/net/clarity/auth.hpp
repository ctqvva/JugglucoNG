// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "client.hpp"
namespace clarity {
inline constexpr std::string_view oauthClientId = "B7DC62F4-7A40-44B0-A48B-98A95039A48A";
inline constexpr std::string_view oauthRedirect = "dexcomg7://uam_redirect_uri";
inline constexpr std::string_view oauthTokenUrl =
    "https://global.dexcom.com/identity/connect/token";
// original_requested_scopes from the successful G7 authorization, not the
// larger granted set (which also includes consent-dependent server additions).
inline constexpr std::string_view oauthScopes =
    "AccountManagement openid offline_access DataShare egv event calibration";
std::string formEncode(std::string_view value);
Json tokenClaims(std::string_view value);
// Only known permission/consent flags; never identifiers or the token itself.
std::string tokenPermissionSummary(std::string_view value);
std::string tokenHeaders(const Json &config);
void validateDexcomUrl(const std::string &value);
// Attempts contain only locally generated state, PKCE and device information.
Json beginLogin(const std::string &country, const std::string &locale, const Json &device,
                int64_t now);
Json exchangeLogin(const Json &attempt, std::string_view callback, std::string_view clientSecret,
                   const Transport &post, int64_t now);
void setDiscoveredEndpoints(Json &config, const Json &response);
void discoverEndpoints(Json &config, const Transport &post);
void discoverEndpoints(Json &config, const Transport &post, Provider &provider);
} // namespace clarity
