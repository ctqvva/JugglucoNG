// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "engine.hpp"
namespace clarity {
Json makePost(const std::string &account, const std::string &installation, int32_t sequence,
              const Json &groups, const std::string &transmitter);
Json messageHeader(const Json &config, const Json &identity, bool session, const std::string &requestId);
std::string seal(Provider &provider, const Json &header, std::string_view plain);
struct Opened {
    Json header;
    Bytes plain;
};
// GCS and CLM certificate replies have AccountId: null in the G7 captures.
// Certificate enrollment additionally verifies the issued key against our CSR.
// Session-key and bulk responses must retain the exact account binding.
enum class ResponseBinding { Account, RegionalDiscovery, CertificateEnrollment };
Opened openResponse(Provider &provider, std::string_view body, const std::string &requestId,
                    const std::string &account,
                    ResponseBinding binding = ResponseBinding::Account);
} // namespace clarity
