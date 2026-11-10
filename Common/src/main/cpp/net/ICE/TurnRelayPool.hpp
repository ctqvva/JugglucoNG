#pragma once

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

enum class TurnRelayTransport { Udp, Tcp, Tls };

struct TurnRelayOptions {
    TurnRelayTransport transport{TurnRelayTransport::Udp};
    const char *provider{""};
    const char *region{""};
};

struct TurnRelay {
    std::string host, username, password;
    uint16_t port{3478};
    TurnRelayTransport transport{TurnRelayTransport::Udp};
    std::string provider, region;

    bool sameService(const TurnRelay &other) const {
        return host == other.host && port == other.port && transport == other.transport &&
               username == other.username && password == other.password;
    }
    bool operator==(const TurnRelay &) const = default;
};

inline constexpr size_t maxTurnRelayPoolSize = 16;
inline constexpr size_t simultaneousTurnRelays = 2;

inline void appendTurnRelay(std::vector<TurnRelay> &pool, TurnRelay relay, bool allowEmptyCredentials = false) {
    if (relay.host.empty() || !relay.port ||
        (!allowEmptyCredentials && (relay.username.empty() || relay.password.empty()))) return;
    if (std::none_of(pool.begin(), pool.end(), [&](const auto &other) {
            return relay.sameService(other);
        })) pool.push_back(std::move(relay));
}

// libjuice allocates at most two relays per agent. Keep the larger provider /
// region pool outside the agent and rotate only after an ICE attempt the peer
// took part in fails. A peer that never answered says nothing about the relays,
// and signaling retries must not burn through relay choices.
class TurnRelayRotation {
    std::mutex mutex;
    std::vector<TurnRelay> previousPool;
    bool previousPinned = false;
    size_t cursor = 0;
    uint64_t generation = 0;
    bool failed = false;
    bool peerAnswered = false;

    static bool sameEndpoint(const TurnRelay &a, const TurnRelay &b) {
        return a.host == b.host && a.port == b.port && a.transport == b.transport;
    }
public:
    // pinFirst keeps pool[0] (the user's own relay) in every selection; the
    // second slot then walks the rest of the pool in order, so each backup
    // gets its turn.
    std::vector<TurnRelay> select(const std::vector<TurnRelay> &pool, uint64_t agentGeneration,
                                  bool pinFirst = false) {
        std::lock_guard lock(mutex);
        generation = agentGeneration;
        failed = false;
        peerAnswered = false;
        if (pool.empty()) {
            previousPool.clear();
            cursor = 0;
            return {};
        }
        const size_t pinned = pinFirst && pool.size() > 1 ? 1 : 0;
        const size_t span = pool.size() - pinned;
        if (pool != previousPool || (pinned != 0) != previousPinned) {
            // A token refresh or settings change keeps the relay that was up
            // next, rather than starting the walk over from the first entry.
            size_t kept = 0;
            if (!previousPool.empty()) {
                const auto &head = previousPool[(previousPinned ? 1 : 0) +
                    cursor % (previousPool.size() - (previousPinned ? 1 : 0))];
                // The same account first: several accounts can share one
                // endpoint. Only refreshed credentials fall back to it.
                const auto find = [&](auto &&match) {
                    for (size_t i = pinned; i < pool.size(); ++i)
                        if (match(pool[i])) return i - pinned;
                    return span;
                };
                kept = find([&](const TurnRelay &relay) { return relay.sameService(head); });
                if (kept == span)
                    kept = find([&](const TurnRelay &relay) { return sameEndpoint(relay, head); });
                if (kept == span) kept = 0;
            }
            previousPool = pool;
            previousPinned = pinned != 0;
            cursor = kept;
        }
        cursor %= span;
        const auto at = [&](size_t offset) { return pinned + (cursor + offset) % span; };
        std::vector<TurnRelay> selected{pool[pinned ? 0 : at(0)]};
        if (pool.size() == 1) return selected;
        if (pinned) {
            selected.push_back(pool[at(0)]);
            return selected;
        }
        size_t next = at(1);
        int bestScore = -1;
        for (size_t offset = 1; offset < span; ++offset) {
            const size_t index = at(offset);
            const auto &candidate = pool[index];
            const auto &first = selected.front();
            // Prefer another transport (UDP-blocking networks), then another
            // provider (outages / quotas), then another region.
            const int score = 4 * (candidate.transport != first.transport) +
                2 * (!candidate.provider.empty() && candidate.provider != first.provider) +
                (!candidate.region.empty() && candidate.region != first.region);
            if (score > bestScore) { bestScore = score; next = index; }
        }
        selected.push_back(pool[next]);
        return selected;
    }
    // The peer's description reached this agent: from here on a failure is
    // evidence against the chosen relays.
    void answered(uint64_t agentGeneration) {
        std::lock_guard lock(mutex);
        if(agentGeneration==generation) peerAnswered=true;
    }
    void connected(uint64_t agentGeneration) {
        std::lock_guard lock(mutex);
        if(agentGeneration==generation) peerAnswered=false;
    }
    void failedAttempt(uint64_t agentGeneration) {
        std::lock_guard lock(mutex);
        if (agentGeneration != generation || failed || !peerAnswered || previousPool.empty()) return;
        // Every rotating entry leads within one traversal of the pool,
        // regardless of which alternative won the diversity ranking.
        cursor = (cursor + 1) % (previousPool.size() - (previousPinned ? 1 : 0));
        failed = true;
        peerAnswered = false;
    }
};
