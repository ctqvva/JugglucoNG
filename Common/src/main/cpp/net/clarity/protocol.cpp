// SPDX-License-Identifier: GPL-3.0-or-later
#include "protocol.hpp"
#include <ctime>
namespace clarity {
static std::string wrap(const std::string &account, const Json &value, bool compressed) {
    std::string content = value.dump();
    if (compressed)
        content = base64(gzip(bytes(content)));
    auto mac = hmac256(bytes("DxDs" + account), bytes(content));
    mac.resize(16);
    return Json{{"Content", content}, {"Hmac", base64(mac)}, {"IsZip", compressed}}.dump();
}
static std::string precision(std::string s) {
    auto dot = s.find('.');
    if (dot == s.npos || dot + 4 >= s.size())
        throw Error("Invalid record timestamp");
    s.insert(dot + 4, "0000");
    return s;
}
Json makePost(const std::string &account, const std::string &installation, int32_t sequence,
              const Json &groups, const std::string &transmitter) {
    if (!isUuid(account) || !isUuid(installation))
        throw Error("Invalid Clarity identity");
    Json entries = Json::array(), manifests = Json::array(), counts = Json::array();
    for (auto it = groups.begin(); it != groups.end(); ++it) {
        const auto &records = it.value();
        if (records.empty())
            continue;
        // Session start/InSession pairs from overlapping sensors can interleave.
        // Determine the manifest's bounds without copying or reordering records.
        const auto bounds =
            std::minmax_element(records.begin(), records.end(), [](const Json &a, const Json &b) {
                return a.at("RecordedSystemTime").get<std::string>() <
                       b.at("RecordedSystemTime").get<std::string>();
            });
        const auto &first = *bounds.first, &last = *bounds.second;
        Json manifest = {{"Count", records.size()},
                         {"FirstDateTime", precision(first.at("RecordedDisplayTime"))},
                         {"LastDateTime", precision(last.at("RecordedDisplayTime"))},
                         {"RecordType", it.key()}};
        if (it.key() == "GlucoseRecord" || it.key() == "SensorSessionRecord") {
            manifest["FirstKey"] = first.at("TransmitterId").get<std::string>() + "|" +
                                   std::to_string(first.at("TransmitterTime").get<int64_t>());
            manifest["LastKey"] = last.at("TransmitterId").get<std::string>() + "|" +
                                  std::to_string(last.at("TransmitterTime").get<int64_t>());
        }
        manifests.push_back(manifest);
        entries.push_back({{"RecordType", it.key()}, {"Records", records.dump()}});
        counts.push_back({{"count", records.size()}, {"recordType", it.key()}});
    }
    Json empty = {{"Entries", Json::array()}};
    Json header = {{"InstallationId", installation},
                   {"PatientId", account},
                   {"RecordVersion", "1.1"},
                   {"SequenceNumber", sequence},
                   {"SourceStream", "Phone18"},
                   {"TransmitterNumber", transmitter},
                   {"TransmitterSW", ""},
                   {"TransmitterVersion", ""}};
    return {{"DataPost",
             {{"AccountID", account},
              {"PostHeader", wrap(account, header, false)},
              {"PrivateDataContent", wrap(account, empty, true)},
              {"PrivateDataManifest", wrap(account, empty, true)},
              {"PublicDataContent", wrap(account, Json{{"Entries", entries}}, true)},
              {"PublicDataManifest", wrap(account, Json{{"Entries", manifests}}, true)},
              {"RecordCount", counts},
              {"Route", "NORMAL"},
              {"SequenceID", sequence},
              {"Version", 0}}}};
}
Json messageHeader(const Json &c, const Json &identity, bool session, const std::string &id) {
    return {{"AccountId", c.at("account")},
            {"AppInstanceId", c.at("appInstanceId")},
            {"EncKey",
             {{"ID", session ? Json("PRIVATE") : identity.at("EncryptionKeyId")},
              {"IV", base64(randomBytes(16), true)}}},
            {"IsZip", false},
            {"Nonce", uuid()},
            {"RequestId", id},
            {"SaltId", session ? Json("PRIVATE") : identity.at("SigningKeyId")},
            {"SoftwareId", identity.at("SoftwareId")},
            {"Timestamp", timestamp(time(nullptr), true, true)}};
}
std::string seal(Provider &p, const Json &h, std::string_view plain) {
    auto encoded = bytes(base64(bytes(plain), true));
    auto padding = 16 - encoded.size() % 16;
    encoded.insert(encoded.end(), padding, padding);
    auto iv = unbase64(h.at("EncKey").at("IV").get<std::string>(), true);
    auto encrypted = p.encrypt(h, iv, encoded);
    if (encrypted.size() != encoded.size())
        throw Error("Invalid encrypted message length");
    auto signedText = base64(bytes(h.dump()), true) + "." + base64(encrypted, true);
    auto sig = p.sign(h, bytes(signedText));
    sig.resize(16);
    return Json(signedText + "." + base64(sig, true)).dump();
}
Opened openResponse(Provider &p, std::string_view body, const std::string &id,
                    const std::string &account, ResponseBinding binding) {
    if (body.size() > 8 * 1024 * 1024)
        throw Error("Response too large");
    // Requests are JSON strings, but the captured GCS/session/bulk HTTP
    // responses contain the compact token directly. Accept either envelope;
    // both then go through the same signature and identity checks.
    const auto begin = body.find_first_not_of(" \t\r\n");
    if (begin == body.npos)
        throw Error("Empty SignedMessage response");
    body = body.substr(begin, body.find_last_not_of(" \t\r\n") - begin + 1);
    std::string token;
    if (body.front() == '"') {
        try {
            token = Json::parse(body).get<std::string>();
        } catch (const Json::exception &) {
            caught("parse SignedMessage envelope");
            throw Error("Invalid SignedMessage JSON envelope");
        }
    } else
        token = body;
    if (token.find_first_not_of(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_=.") != token.npos)
        throw Error("Invalid SignedMessage characters");
    auto first = token.find('.'), second = token.find('.', first == token.npos ? 0 : first + 1);
    if (first == token.npos || second == token.npos || first == 0 || second == first + 1 ||
        second + 1 == token.size() || token.find('.', second + 1) != token.npos)
        throw Error("Invalid SignedMessage framing");
    Json h;
    try {
        h = Json::parse(text(unbase64(token.substr(0, first), true)));
    } catch (const Json::exception &) {
        caught("parse SignedMessage header");
        throw Error("Invalid SignedMessage header JSON");
    }
    if (!h.is_object() || !h.contains("RequestId") || !h.contains("AccountId"))
        throw Error("Missing SignedMessage response identity");
    auto sig = unbase64(token.substr(second + 1), true);
    const bool permittedNull = (binding == ResponseBinding::RegionalDiscovery ||
                                binding == ResponseBinding::CertificateEnrollment) &&
                               h.at("AccountId").is_null();
    if (h.at("RequestId") != id || (h.at("AccountId") != account && !permittedNull))
        throw Error("Response identity mismatch");
    if (!p.verify(h, bytes(token.substr(0, second)), sig))
        throw Error("Response signature mismatch");
    // No decryption or key import occurs before signature verification.
    auto padded = p.decrypt(h, unbase64(h.at("EncKey").at("IV").get<std::string>(), true),
                            unbase64(token.substr(first + 1, second - first - 1), true));
    auto count = padded.empty() ? 0 : padded.back();
    if (count < 1 || count > 16 || count > padded.size() ||
        !std::all_of(padded.end() - count, padded.end(), [count](uint8_t c) { return c == count; }))
        throw Error("Invalid response padding");
    padded.resize(padded.size() - count);
    auto plain = unbase64(text(padded), true);
    if (h.at("IsZip").get<bool>())
        plain = gunzip(plain);
    return {std::move(h), std::move(plain)};
}
} // namespace clarity
