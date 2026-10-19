#pragma once

#include <cstdint>
#include <optional>
#include <span>
#include <string_view>

// Legacy /address replies have a four-byte timestamp and a NUL-terminated
// candidate. Empty/short HTTP 200 replies also mean missing/replaced entries
// and interrupted polls, so they cannot prove gathering completion.
inline std::optional<std::string_view> rendezvousCandidate(std::span<const char> body) {
    if (body.size() <= sizeof(uint32_t)) return std::nullopt;
    std::string_view text(body.data() + sizeof(uint32_t), body.size() - sizeof(uint32_t));
    if (text.back() != '\0') return std::nullopt;
    text.remove_suffix(1);
    if (text.find('\0') != std::string_view::npos ||
        !(text.starts_with("a=candidate:") || text.starts_with("candidate:")))
        return std::nullopt;
    return text;
}

enum class CandidateStreamResult { ConnectedEnd, Cancelled, Unavailable };

inline bool legacyEmptyCandidateReply(std::span<const char> body) {
    if (body.empty()) return true;
    if (body.size() == sizeof(uint32_t) + 1 && body.back() == '\0') return true;
    std::string_view text(body.data(), body.size());
    while (!text.empty() && (text.back() == '\n' || text.back() == '\r' || text.back() == ' '))
        text.remove_suffix(1);
    return text == "{}";
}

// The callbacks keep the production loop testable without an HTTPS service.
// active() includes agent generation, shutdown and signaling cancellation.
template<class Active, class Connected, class Request, class Accept, class Wait, class Pending>
CandidateStreamResult readRendezvousCandidates(
        Active active, Connected connected, Request request, Accept accept,
        Wait wait, Pending pending) {
    int errors = 0;
    while (active()) {
        const auto [body, code] = request();
        if (!active()) return CandidateStreamResult::Cancelled;
        if (code == 200) {
            if (const auto candidate = rendezvousCandidate(body)) {
                if (accept(*candidate)) {
                    errors = 0;
                    continue;
                }
                // Invalid candidate data is a failed read, not an end marker.
                ++errors;
            } else if (legacyEmptyCandidateReply(body)) {
                if (connected()) return CandidateStreamResult::ConnectedEnd;
                errors = 0;
                pending(body.size());
            } else {
                ++errors;
            }
        } else {
            ++errors;
        }
        if (errors >= 5) return CandidateStreamResult::Unavailable;
        if (!wait(code == 200 || code == 400 ? 2 : 10))
            return CandidateStreamResult::Cancelled;
    }
    return CandidateStreamResult::Cancelled;
}
