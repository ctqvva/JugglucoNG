// SPDX-License-Identifier: GPL-3.0-or-later
#include "auth.hpp"
#include <map>
namespace clarity {
static void authText(std::string_view s, size_t maximum = 32768) {
    if (s.empty() || s.size() > maximum ||
        std::any_of(s.begin(), s.end(), [](unsigned char c) { return c < 32 || c == 127; }))
        throw Error("Invalid Dexcom authentication data");
}
static bool countryCode(std::string_view s) {
    return s.size() == 2 && s[0] >= 'A' && s[0] <= 'Z' && s[1] >= 'A' && s[1] <= 'Z';
}
std::string formEncode(std::string_view s) {
    std::string out;
    for (unsigned char c : s) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
            c == '-' || c == '.' || c == '_' || c == '~')
            out += char(c);
        else {
            out += '%';
            out += "0123456789ABCDEF"[c >> 4];
            out += "0123456789ABCDEF"[c & 15];
        }
    }
    return out;
}
Json tokenClaims(std::string_view value) {
    authText(value);
    auto a = value.find('.'), b = value.find('.', a == value.npos ? 0 : a + 1);
    if (a == value.npos || b == value.npos || value.find('.', b + 1) != value.npos)
        throw Error("Dexcom returned an invalid token");
    return Json::parse(text(unbase64(value.substr(a + 1, b - a - 1), true)));
}
std::string tokenPermissionSummary(std::string_view value) {
    try {
        const auto claims = tokenClaims(value);
        auto member = [&](const Json &scopes, std::string_view name) {
            if (scopes.is_array()) {
                for (const auto &s : scopes)
                    if (s.is_string() && s.get_ref<const std::string &>() == name)
                        return true;
            } else if (scopes.is_string()) {
                std::string_view remaining = scopes.get_ref<const std::string &>();
                while (!remaining.empty()) {
                    auto end = remaining.find(' ');
                    if (remaining.substr(0, end) == name)
                        return true;
                    if (end == remaining.npos)
                        break;
                    remaining.remove_prefix(end + 1);
                }
            }
            return false;
        };
        std::string result;
        for (const char *key : {"scope", "original_requested_scopes"}) {
            result += std::string(key) + ":";
            auto it = claims.find(key);
            if (it == claims.end() || !(it->is_array() || it->is_string()))
                result += " unavailable; ";
            else {
                for (const char *name : {"AccountManagement", "DataShare", "egv", "event",
                                         "calibration", "Clarity"})
                    result += std::string(" ") + name + "=" + (member(*it, name) ? "1" : "0");
                result += "; ";
            }
        }
        for (const char *name : {"cnst_clarity", "is_consent_required", "gcs_read"}) {
            auto it = claims.find(name);
            const char *flag = "unknown";
            if (it != claims.end()) {
                if (*it == true || *it == 1 || *it == "1" || *it == "true")
                    flag = "1";
                else if (*it == false || *it == 0 || *it == "0" || *it == "false")
                    flag = "0";
            }
            result += std::string(name) + "=" + flag + " ";
        }
        return result;
    } catch (...) {
        caught("read token permission diagnostics");
        return "permission claims unavailable";
    }
}
std::string tokenHeaders(const Json &c) {
    std::string result =
        "\r\nContent-Type: application/x-www-form-urlencoded\r\nAccept: application/json";
    if (c.contains("countryCode")) {
        std::string country = c.at("countryCode");
        if (!countryCode(country))
            throw Error("Invalid Dexcom account country");
        result += "\r\nX-Dexcom-Country: " + country;
    }
    return result;
}
Json beginLogin(const std::string &country, const std::string &locale, const Json &device,
                int64_t now) {
    if (!countryCode(country))
        throw Error("Enter your two-letter country code, for example NL or US");
    authText(locale, 64);
    for (auto k : {"manufacturer", "model", "osVersion"})
        authText(device.at(k).get<std::string>(), 256);
    Json attempt = {{"created", now},
                    {"state", base64(randomBytes(32), true)},
                    {"nonce", base64(randomBytes(32), true)},
                    {"verifier", base64(randomBytes(32), true)},
                    {"countryCode", country},
                    {"appInstanceId", uuid()},
                    {"installationId", uuid()},
                    {"hardwareId", base64(randomBytes(16), true)},
                    {"runtimeInfo",
                     {{"AppName", "com.dexcom.g7"},
                      {"AppVersion", "2.15.0"},
                      {"AppNumber", "SW12299"},
                      {"DeviceOsName", "Android"},
                      {"DeviceManufacturer", device.at("manufacturer")},
                      {"DeviceModel", device.at("model")},
                      {"DeviceOsVersion", device.at("osVersion")}}}};
    const auto verifier = attempt.at("verifier").get<std::string>();
    // Match the G7 hosted hybrid flow. The password stays on Dexcom's page.
    attempt["url"] =
        "https://global.dexcom.com/identity/connect/authorize?client_id=" +
        formEncode(oauthClientId) +
        "&response_type=code%20id_token&redirect_uri=" + formEncode(oauthRedirect) +
        "&scope=" + formEncode(oauthScopes) +
        "&prompt=login&state=" + attempt.at("state").get<std::string>() +
        "&nonce=" + attempt.at("nonce").get<std::string>() +
        "&code_challenge_method=S256&code_challenge=" + base64(sha256(bytes(verifier)), true) +
        "&ui_locales=" + formEncode(locale) + "&phone_location_country_code=" + country;
    return attempt;
}
static std::map<std::string, std::string> callbackFields(std::string_view callback) {
    if (callback.size() > 64 * 1024 || !callback.starts_with(oauthRedirect) ||
        callback.size() <= oauthRedirect.size() ||
        (callback[oauthRedirect.size()] != '#' && callback[oauthRedirect.size()] != '?'))
        throw Error("Unexpected Dexcom login redirect");
    auto decode = [](std::string_view s) {
        std::string result;
        for (size_t i = 0; i < s.size(); ++i) {
            if (s[i] == '%') {
                if (i + 2 >= s.size())
                    throw Error("Invalid login redirect encoding");
                auto byte = unhex(s.substr(i + 1, 2));
                result += char(byte.at(0));
                i += 2;
            } else
                result += s[i] == '+' ? ' ' : s[i];
        }
        authText(result);
        return result;
    };
    std::map<std::string, std::string> fields;
    callback.remove_prefix(oauthRedirect.size() + 1);
    while (!callback.empty()) {
        const auto end = callback.find('&');
        auto field = callback.substr(0, end);
        const auto eq = field.find('=');
        if (eq == field.npos ||
            !fields.emplace(decode(field.substr(0, eq)), decode(field.substr(eq + 1))).second)
            throw Error("Invalid or duplicate login redirect parameter");
        if (end == callback.npos)
            break;
        callback.remove_prefix(end + 1);
    }
    return fields;
}
Json exchangeLogin(const Json &attempt, std::string_view callback, std::string_view clientSecret,
                   const Transport &post, int64_t now) {
    auto created = attempt.at("created").get<int64_t>();
    if (now < created || now - created > 900)
        throw Error("Dexcom sign-in expired; sign in again");
    const auto fields = callbackFields(callback);
    auto field = [&](const char *name) -> const std::string & {
        auto it = fields.find(name);
        if (it == fields.end())
            throw Error("Incomplete Dexcom login redirect");
        return it->second;
    };
    if (field("state") != attempt.at("state").get<std::string>())
        throw Error("Dexcom sign-in state does not match");
    if (fields.contains("error"))
        throw Error("Dexcom did not authorize sign-in; sign in again");
    auto id = tokenClaims(field("id_token"));
    if (id.at("nonce") != attempt.at("nonce") || !isUuid(id.at("sub").get<std::string>()))
        throw Error("Dexcom login token does not match this sign-in");
    // Callback claims are not used as authenticated account/configuration data.
    // Bind them to the independently obtained, TLS-authenticated token response.
    authText(clientSecret, 256);
    std::string request = "grant_type=authorization_code&client_id=" + formEncode(oauthClientId) +
                          "&client_secret=" + formEncode(clientSecret) +
                          "&redirect_uri=" + formEncode(oauthRedirect) +
                          "&code=" + formEncode(field("code")) +
                          "&code_verifier=" + formEncode(attempt.at("verifier").get<std::string>());
    Json c = {{"schema", "juggluco-clarity-setup/1"},
              {"countryCode", attempt.at("countryCode")},
              {"clientId", oauthClientId},
              {"clientSecret", clientSecret},
              {"tokenUrl", oauthTokenUrl}};
    // The captured initial exchange sends an empty country header. The phone
    // location is not necessarily the account's country; learn the latter from
    // this authenticated response before regional discovery or refresh.
    diagnostic("exchanging Dexcom authorization code for tokens");
    auto r = post(std::string(oauthTokenUrl), request,
                  tokenHeaders(Json::object()) + "\r\nX-Dexcom-Country: ");
    if (r.status != 200) {
        // Redact any reflected authorization parameters as well as config data.
        auto secrets = c;
        secrets["code"] = field("code");
        secrets["verifier"] = attempt.at("verifier");
        throw Error(httpFailure("Dexcom sign-in token exchange", r, secrets));
    }
    auto response = Json::parse(r.body);
    std::string access = response.at("access_token"), refresh = response.at("refresh_token");
    authText(refresh);
    auto claims = tokenClaims(access);
    std::string account = claims.at("sub"), country = claims.at("country_code");
    if (!isUuid(account) || claims.at("client_id") != oauthClientId || id.at("sub") != account ||
        !countryCode(country))
        throw Error("Dexcom returned an unexpected account or client");
    const auto expiry =
        std::min(claims.at("exp").get<int64_t>(), now + response.at("expires_in").get<int64_t>());
    if (expiry <= now + 60 || expiry > now + 31 * 86400)
        throw Error("Dexcom returned an invalid token lifetime");
    c["account"] = account;
    c["countryCode"] = country;
    c["accessToken"] = access;
    c["refreshToken"] = refresh;
    c["tokenExpiresAt"] = expiry;
    for (auto key : {"appInstanceId", "installationId", "hardwareId", "runtimeInfo"})
        c[key] = attempt.at(key);
    diagnostic("sign-in token permissions: %s", tokenPermissionSummary(access).c_str());
    return c;
}
void setDiscoveredEndpoints(Json &c, const Json &response) {
    if (!response.is_array())
        throw Error("Invalid Dexcom service discovery response");
    std::string gateway, certificates;
    for (const auto &entry : response) {
        const auto type = entry.at("UrlType");
        if (type != "UDP_API_GATEWAY_BASE" && type != "CLM_PROXY")
            continue;
        auto &base = type == "CLM_PROXY" ? certificates : gateway;
        if (!base.empty())
            throw Error("Duplicate Dexcom upload/certificate service");
        base = entry.at("Url").get<std::string>();
    }
    for (const auto &base : {gateway, certificates}) {
        validateDexcomUrl(base);
        if (base.substr(8).find('/') != base.size() - 9 || base.back() != '/')
            throw Error("Unexpected Dexcom service base URL");
    }
    auto next = c;
    next["sessionUrl"] = gateway + "security/v1/patient/updateSessionKeys";
    next["bulkUrl"] = gateway + "bulkData/v1/patient/postBulkData";
    next["certificateUrl"] = certificates + "Certificate/UserCert";
    validateConfig(next);
    c = std::move(next);
}
void discoverEndpoints(Json &c, const Transport &post) {
    diagnostic("initializing native provider for regional service discovery");
    Provider provider;
    discoverEndpoints(c, post, provider);
}
void discoverEndpoints(Json &c, const Transport &post, Provider &provider) {
    updateDeviceIdentity(c, provider);
    // Early imported setup files had endpoints but no countryCode. New logins
    // already save it; migration can recover routing from the stored token.
    if (!c.contains("countryCode")) {
        const auto claims = tokenClaims(c.at("accessToken").get<std::string>());
        const auto country = claims.value("country_code", "");
        if (!countryCode(country))
            throw Error("Dexcom account country is unavailable; sign in again");
        c["countryCode"] = country;
        diagnostic("account country restored from stored token for discovery");
    }
    const auto identity = provider.identity();
    auto h = messageHeader(c, identity, false, uuid());
    Json request = {{"CountryCode", c.at("countryCode")},
                    {"UrlTypes", Json::array({"UDP_API_GATEWAY_BASE", "CLM_PROXY"})},
                    {"SoftwareNumber", "SW12299"},
                    {"AppVersion", "2.15"}};
    const auto account = c.at("account").get<std::string>();
    auto headers =
        "\r\nContent-Type: application/json\r\nAccept: application/json\r\nAuthorization: Bearer " +
        c.at("accessToken").get<std::string>() + "\r\nX-Account-Id: " + account +
        "\r\nX-Software-Id: " + identity.at("SoftwareId").get<std::string>() +
        "\r\nX-Trace-Id: " + h.at("RequestId").get<std::string>() +
        "\r\nX-Security-Schema: SignedMessage";
    diagnostic("requesting regional upload service");
    auto r =
        post("https://gcs2.dexcom.com/gcs/listOfUrls", seal(provider, h, request.dump()), headers);
    if (r.status != 200)
        throw Error(httpFailure("Dexcom service discovery", r, c));
    diagnostic("decoding regional service response (%zu bytes)", r.body.size());
    auto response = openResponse(provider, r.body, h.at("RequestId"), account,
                                 ResponseBinding::RegionalDiscovery);
    diagnostic("regional response signature and request verified; decoding endpoint list");
    Json endpoints;
    try {
        endpoints = Json::parse(text(response.plain));
    } catch (const Json::exception &) {
        caught("parse regional endpoint list");
        throw Error("Invalid regional service payload");
    }
    setDiscoveredEndpoints(c, endpoints);
    diagnostic("regional upload service verified");
}
} // namespace clarity
