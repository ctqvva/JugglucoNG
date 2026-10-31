#include "net/ICE/TurnRelayPool.hpp"
#include "net/ICE/TurnStreamFrames.hpp"
#include "net/ICE/TurnTokenResponse.hpp"
#include "net/ICE/IceLogPrivacy.hpp"
#include <array>
#include <cassert>
#include <cstdio>
#include <set>

TurnRelay relay(std::string host, TurnRelayTransport transport = TurnRelayTransport::Udp,
                std::string provider = "one", std::string region = "global") {
    return {std::move(host), "user", "password", 3478, transport,
            std::move(provider), std::move(region)};
}

int main() {
    assert(iceLogContainsCredentials("Got username: synthetic-user"));
    assert(iceLogContainsCredentials("STUN integrity check failed, password=\"synthetic\""));
    assert(iceLogContainsCredentials("Parsed remote description: ufrag=\"synthetic\", pwd=\"secret\""));
    assert(!iceLogContainsCredentials("TURN authentication failed"));
    const auto token=TurnTokenReader(R"({"ice_servers":[{"username":"nested"}],"password":"p\/ass","ttl":600,"username":"\u0075ser"})").read();
    assert(token && token->username=="user" && token->password=="p/ass" && token->ttlSeconds==600);
    const auto quotedTtl=TurnTokenReader(R"({"username":"u","password":"p","ttl":"86400"})").read();
    assert(quotedTtl&&quotedTtl->ttlSeconds==86400);
    for(const std::string_view invalid:{
            R"({"username":"u","password":"p"})",
            R"({"username":"u","password":"p","ttl":0})",
            R"({"username":"u","password":"p","ttl":86401})",
            R"({"username":"u","password":"p","ttl":600.1})",
            R"({"username":"u","password":"p","ttl":"600.1"})",
            R"({"username":"u","password":"p","ttl":"600seconds"})",
            R"({"username":"u","password":"p","ttl":""})",
            R"({"username":"u","password":"p","ttl":600,"username":"other"})",
            R"({"username":"u","password":"p\u0000","ttl":600})",
            R"({"username":"u","password":"p","ttl":600}garbage)"})
        assert(!TurnTokenReader(invalid).read());
    std::array<char,20> stun{0,3,0,0,0x21,0x12,char(0xa4),0x42};
    assert(turnStreamFrame(stun)->wireBytes == 20);
    TurnStreamRequests requests;
    assert(requests.accept(stun));
    assert(!requests.accept(stun));
    auto authentication=stun;authentication[8]=1;
    assert(requests.accept(authentication));
    std::vector<char> authenticated(stun.begin(),stun.end());
    authenticated[3]=4;authenticated.insert(authenticated.end(),{0,6,0,0});
    assert(requests.accept(authenticated)&&!requests.accept(authenticated));
    auto indication=stun;indication[1]|=0x10;
    assert(requests.accept(indication)&&requests.accept(indication));
    assert(turnStreamFrame(std::span(stun).first(3))->wireBytes == 0);
    assert(turnStreamFrame(std::span(stun).first(19))->wireBytes == 0);
    stun[3]=1; assert(!turnStreamFrame(stun));
    stun[3]=0; stun[4]=0; assert(!turnStreamFrame(stun));
    stun[0]=char(0x80); assert(!turnStreamFrame(stun));
    std::array<char,7> channel{0x40,1,0,3,'a','b','c'};
    const auto framed=turnStreamFrame(channel);
    assert(framed->payloadBytes == 7 && framed->wireBytes == 8);
    assert(requests.accept(channel)&&requests.accept(channel));
    assert(turnStreamServerFrameAllowed(channel));
    std::vector<char> redirected(24,0);
    redirected[20]=char(0x80);redirected[21]=0x23;
    assert(!turnStreamServerFrameAllowed(redirected));
    TurnRelayRotation rotation;
    assert(rotation.select({}, 1).empty());
    std::vector<TurnRelay> pool{relay("custom"), relay("same-provider"),
        relay("other-region", TurnRelayTransport::Udp, "one", "asia"),
        relay("other-provider", TurnRelayTransport::Udp, "two"),
        relay("tls-backup", TurnRelayTransport::Tls, "two")};
    auto chosen = rotation.select(pool, 2);
    assert(chosen.size() == 2 && chosen[0].host == "custom" && chosen[1].host == "tls-backup");
    // A signaling retry has not failed ICE and keeps the same relay choices.
    chosen = rotation.select(pool, 3);
    assert(chosen[0].host == "custom");
    rotation.failedAttempt(3); // the peer never answered: says nothing about relays
    assert(rotation.select(pool, 4)[0].host == "custom");
    rotation.answered(4);
    rotation.failedAttempt(2); // stale callback
    rotation.connected(2); // stale success cannot hide a pending attempt
    rotation.failedAttempt(4);
    rotation.failedAttempt(4); // duplicate notification must not skip a relay
    assert(rotation.select(pool, 5)[0].host == "same-provider");
    std::set<std::string> attempted;
    for (uint64_t generation = 6; generation < 6 + pool.size(); ++generation) {
        chosen = rotation.select(pool, generation);
        attempted.insert(chosen[0].host);
        rotation.answered(generation);
        rotation.failedAttempt(generation);
    }
    assert(attempted.size() == pool.size());
    // Changed credentials (a token refresh) keep the relay that was up next
    // instead of restarting the walk, without retaining a pointer into the
    // replaced configuration.
    const auto head = rotation.select(pool, 19)[0].host;
    for (auto &entry : pool) entry.password = "replacement";
    assert(rotation.select(pool, 20)[0].host == head);
    pool.insert(pool.begin() + 1, relay("joined-later"));
    assert(rotation.select(pool, 20)[0].host == head);
    pool.erase(pool.begin() + 1);
    pool.resize(1);
    assert(rotation.select(pool, 21).size() == 1);
    rotation.answered(21);
    rotation.failedAttempt(21);
    assert(rotation.select(pool, 22).size() == 1);
    pool.push_back(relay("next"));
    assert(rotation.select(pool,23)[0].host=="custom");
    rotation.answered(23);
    rotation.connected(23);
    rotation.failedAttempt(23); // successful disconnect retains its choices
    assert(rotation.select(pool,24)[0].host=="custom");
    rotation.answered(24);
    rotation.failedAttempt(24); // abandoned / failed initialization rotates
    assert(rotation.select(pool,25)[0].host=="next");
    rotation.failedAttempt(25); // signaling-only replacement must not rotate
    assert(rotation.select(pool,26)[0].host=="next");
    {
        // The user's own relay stays in every selection when backups are
        // allowed; the second slot walks every backup in order.
        TurnRelayRotation pinned;
        std::vector<TurnRelay> backups{relay("own"), relay("b1"),
            relay("b2", TurnRelayTransport::Tls), relay("b3", TurnRelayTransport::Tcp)};
        std::vector<std::string> second;
        for (uint64_t generation = 1; generation <= 6; ++generation) {
            const auto selected = pinned.select(backups, generation, true);
            assert(selected.size() == 2 && selected[0].host == "own");
            second.push_back(selected[1].host);
            pinned.answered(generation);
            pinned.failedAttempt(generation);
        }
        assert((second == std::vector<std::string>{"b1", "b2", "b3", "b1", "b2", "b3"}));
        // An absent peer does not move the walk.
        const auto before = pinned.select(backups, 7, true)[1].host;
        pinned.failedAttempt(7);
        assert(pinned.select(backups, 8, true)[1].host == before);
        // Alone, the own relay is the whole selection.
        assert(pinned.select({relay("own")}, 9, true).size() == 1);
    }
    {
        // Two accounts on one endpoint: after the first fails, a pool change
        // (a Twilio token arriving) keeps the second account up next.
        TurnRelayRotation shared;
        auto second = relay("shared"); second.username = "second";
        std::vector<TurnRelay> accounts{relay("own"), relay("shared"), second, relay("b")};
        assert(shared.select(accounts, 1, true)[1].username == "user");
        shared.answered(1);
        shared.failedAttempt(1);
        accounts.push_back(relay("twilio", TurnRelayTransport::Tls, "twilio"));
        const auto selected = shared.select(accounts, 2, true);
        assert(selected[1].host == "shared" && selected[1].username == "second");
    }
    pool.clear();
    appendTurnRelay(pool, relay("a"));
    appendTurnRelay(pool, relay("a"));
    appendTurnRelay(pool, relay("a", TurnRelayTransport::Tls));
    auto incomplete = relay("invalid"); incomplete.password.clear();
    appendTurnRelay(pool, incomplete);
    assert(pool.size() == 2);
    // Accounts on the same service can have independent quotas/revocations.
    // Retain both a different username and a password-only replacement.
    auto otherAccount=pool.front();otherAccount.username="backup-user";
    appendTurnRelay(pool,otherAccount);
    auto otherPassword=pool.front();otherPassword.password="backup-password";
    appendTurnRelay(pool,otherPassword);
    appendTurnRelay(pool,otherAccount); // exact account duplicate still collapses
    assert(pool.size()==4);
    TurnRelayRotation accounts;
    std::set<std::pair<std::string,std::string>> attemptedAccounts;
    for(uint64_t generation=1;generation<=pool.size();++generation) {
        const auto selected=accounts.select(pool,generation);
        attemptedAccounts.emplace(selected.front().username,selected.front().password);
        accounts.answered(generation);
        accounts.failedAttempt(generation);
    }
    assert(attemptedAccounts.contains({"backup-user","password"}));
    assert(attemptedAccounts.contains({"user","backup-password"}));
    // Preserve explicitly configured anonymous/self-hosted TURN services.
    incomplete.username.clear();
    appendTurnRelay(pool, incomplete, true);
    assert(pool.size() == 5 && pool.back().username.empty());
    std::puts("TURN pool: diversity, rotation, stale generations, dedupe and configuration changes passed");
}
