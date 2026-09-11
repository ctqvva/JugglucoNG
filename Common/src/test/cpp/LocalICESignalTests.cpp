// Exercise production LAN signaling and AEAD with a lossy in-process link.
// Only interface enumeration, datagram delivery, and the libjuice boundary
// are substituted. No Rendezvous service participates in these tests.
#include <arpa/inet.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <sys/socket.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <functional>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include "net/ICE/GenerationWatchRetry.hpp"
#include "net/ICE/RendezvousCandidateStream.hpp"
#include "net/ICE/RecoveryWakeLease.hpp"

// Access the production receive loop without binding a real Wi-Fi interface.
#define private public
#include "net/ICE/LocalICESignal.hpp"
#undef private

static int testGetIfAddrs(ifaddrs **result);
static void testFreeIfAddrs(ifaddrs *);
static unsigned testIfNameToIndex(const char *);
static int testSetSockOpt(int, int, int, const void *, socklen_t);
static ssize_t testSendTo(int, const void *, size_t, int,
                          const sockaddr *, socklen_t);
#define getifaddrs testGetIfAddrs
#define freeifaddrs testFreeIfAddrs
#define sendto testSendTo
#define if_nametoindex testIfNameToIndex
#define setsockopt testSetSockOpt
#include "net/ICE/LocalICESignal.cpp"
#undef setsockopt
#undef if_nametoindex
#undef sendto
#undef freeifaddrs
#undef getifaddrs

using namespace std::chrono_literals;

struct Peer {
    // Same first-description-wins rule as ICE.cpp: 1 is LAN, 2 is Rendezvous.
    std::atomic_int winner{0};
    std::atomic_int candidates{0};
    std::atomic_bool gatheringDone{false};
    std::atomic_int restarts{0};
    std::string lastCandidate;
    std::mutex candidateMutex;
};

struct Link {
    std::array<Peer, 2> peers;
    std::array<int, 2> sockets{};
    std::array<int, 2> v6sockets{-1, -1};
    std::atomic_bool blockIPv4{false};
    size_t ipv4Packets{}, ipv6Packets{};
    std::atomic_int joins{0}, leaves{0};
    bool failJoin{false};
    std::array<uint8_t, 16> key{};
    std::array<std::unique_ptr<LocalICESignalSession>, 2> sessions;
    std::mutex mutex;
    std::function<bool(int, MessageType)> drop;
    size_t packets{};
    size_t dropped{};
    static inline Link *active{};

    Link(bool ipv4 = true, bool ipv6 = false) {
        if (socketpair(AF_UNIX, SOCK_DGRAM, 0, sockets.data()) != 0)
            throw std::runtime_error("socketpair failed");
        active = this;
        if (ipv6 && socketpair(AF_UNIX, SOCK_DGRAM, 0, v6sockets.data()) != 0)
            throw std::runtime_error("IPv6 test socketpair failed");
        for (int side = 0; side != 2; ++side) {
            sessions[side] = std::make_unique<LocalICESignalSession>(
                side, nullptr, 1, "paired-lan-test", side, key,
                std::string(32, side ? 'b' : 'a'));
            sessions[side]->impl->socketFd = ipv4 ? sockets[side] : -1;
            sessions[side]->impl->multicastFd = v6sockets[side];
            if (!ipv4) close(sockets[side]);
        }
    }

    ~Link() {
        for (auto &session : sessions)
            session->stop();
        active = nullptr;
    }

    void start() {
        for (auto &session : sessions) {
            auto *state = session->impl.get();
            state->receiveThread = std::jthread(
                [state](std::stop_token stop) { state->run(stop); });
        }
    }

    bool confirmed() const {
        return sessions[0]->hasAuthenticatedPeer() &&
               sessions[1]->hasAuthenticatedPeer();
    }

    bool negotiated() const {
        return peers[0].winner == 1 && peers[1].winner == 1;
    }

    void setDrop(std::function<bool(int, MessageType)> filter) {
        std::lock_guard lock(mutex);
        drop = std::move(filter);
    }

    size_t packetCount() {
        std::lock_guard lock(mutex);
        return packets;
    }
};

static int testGetIfAddrs(ifaddrs **result) {
    static sockaddr_in broadcast{AF_INET, 0, {htonl(INADDR_BROADCAST)}, {}};
    static sockaddr_in6 ipv6{AF_INET6};
    static ifaddrs duplicate = [] {
        ifaddrs value{};
        value.ifa_name = const_cast<char *>("wlan-test");
        value.ifa_flags = IFF_UP | IFF_MULTICAST;
        value.ifa_addr = reinterpret_cast<sockaddr *>(&ipv6);
        return value;
    }();
    static ifaddrs ipv6Interface = [] {
        auto value = duplicate;
        value.ifa_next = &duplicate;
        return value;
    }();
    static ifaddrs interface = [] {
        ifaddrs value{};
        value.ifa_flags = IFF_UP | IFF_BROADCAST;
        value.ifa_addr = reinterpret_cast<sockaddr *>(&broadcast);
        value.ifa_broadaddr = reinterpret_cast<sockaddr *>(&broadcast);
        value.ifa_next = &ipv6Interface;
        return value;
    }();
    *result = &interface;
    return 0;
}

static void testFreeIfAddrs(ifaddrs *) {}

static unsigned testIfNameToIndex(const char *name) {
    return std::string_view(name) == "wlan-test" ? 7 : 0;
}

static int testSetSockOpt(int socket, int level, int option, const void *value, socklen_t length) {
    auto *link = Link::active;
    if (link && (socket == link->v6sockets[0] || socket == link->v6sockets[1]) && level == IPPROTO_IPV6) {
        if (option == IPV6_JOIN_GROUP) {
            ++link->joins;
            if (link->failJoin) return -1;
        }
        if (option == IPV6_LEAVE_GROUP) ++link->leaves;
        return 0;
    }
    return setsockopt(socket, level, option, value, length);
}

static ssize_t testSendTo(int socket, const void *data, size_t length, int flags,
                          const sockaddr *destination, socklen_t addressLength) {
    Link &link = *Link::active;
    const int side = socket == link.sockets[0] || socket == link.v6sockets[0] ? 0 : 1;
    std::vector<uint8_t> plain;
    if (!link.sessions[1 - side]->impl->decryptPacket(
            static_cast<const uint8_t *>(data), length, plain))
        throw std::runtime_error("production packet failed authentication");
    std::lock_guard lock(link.mutex);
    ++link.packets;
    if (destination->sa_family == AF_INET6) {
        const auto &address = *reinterpret_cast<const sockaddr_in6 *>(destination);
        if (addressLength != sizeof(sockaddr_in6) || address.sin6_scope_id != 7 ||
            address.sin6_addr.s6_addr[0] != 0xff || address.sin6_addr.s6_addr[1] != 0x12 ||
            ntohs(address.sin6_port) != link.sessions[side]->impl->port)
            throw std::runtime_error("IPv6 multicast destination is not scoped to the LAN interface");
        ++link.ipv6Packets;
    } else {
        ++link.ipv4Packets;
        if (link.blockIPv4) return static_cast<ssize_t>(length);
    }
    if (link.drop && link.drop(side, static_cast<MessageType>(plain[0]))) {
        ++link.dropped;
        return static_cast<ssize_t>(length); // UDP send succeeds despite loss.
    }
    return send(socket, data, length, flags);
}

bool applyLocalICEDescription(int index, juice_agent_t *, uint64_t,
                              std::string_view description,
                              std::string_view generation) {
    if (description.empty() || generation.size() != 32)
        throw std::runtime_error("invalid description at ICE boundary");
    int expected = 0;
    if (!Link::active->peers[index].winner.compare_exchange_strong(expected, 1))
        return false;
    Link::active->sessions[index]->setAcceptedRemoteDescription(description);
    return true;
}

void applyLocalICECandidate(int index, juice_agent_t *, uint64_t, std::string_view candidate) {
    std::lock_guard lock(Link::active->peers[index].candidateMutex);
    Link::active->peers[index].lastCandidate = candidate;
    ++Link::active->peers[index].candidates;
}

void applyLocalICEGatheringDone(int index, juice_agent_t *, uint64_t) {
    Link::active->peers[index].gatheringDone = true;
}

void localICEPeerGenerationChanged(int index, juice_agent_t *, uint64_t) {
    ++Link::active->peers[index].restarts;
}

void localICEPromotionAvailable(int index, juice_agent_t *, uint64_t) {
    ++Link::active->peers[index].restarts;
}

static void require(bool condition, const char *message) {
    if (!condition)
        throw std::runtime_error(message);
}

static bool waitUntil(const std::function<bool()> &done,
                      std::chrono::milliseconds timeout = 3500ms) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    do {
        if (done()) return true;
        std::this_thread::sleep_for(10ms);
    } while (std::chrono::steady_clock::now() < deadline);
    return done();
}

static void recoverLostDescription(int source) {
    Link link;
    link.start();
    require(waitUntil([&] { return link.confirmed(); }), "peers did not authenticate");
    std::this_thread::sleep_for(300ms);
    if (source == 1) {
        link.sessions[0]->publishDescription("offer");
        require(waitUntil([&] { return link.peers[1].winner == 1; }),
                "answerer did not receive the offer");
    }
    // Hello exchange is complete. Lose both immediate copies of the SDP.
    link.setDrop([source, remaining = 2](int side, MessageType type) mutable {
        return side == source && type == MessageType::Description && remaining-- > 0;
    });
    link.sessions[source]->publishDescription(source ? "answer" : "offer");
    require(waitUntil([&] { return link.peers[1 - source].winner == 1; }),
            "lost description never retransmitted after authenticated Hello");
    if (source == 0)
        link.sessions[1]->publishDescription("answer");
    require(waitUntil([&] { return link.negotiated(); }),
            "offer/answer exchange did not finish after packet loss");
    require(link.peers[0].restarts == 0 && link.peers[1].restarts == 0,
            "recovery must not require a generation restart");
    link.setDrop({});
}

static void recoverSnapshotFromConnectedPeer() {
    Link link;
    link.start();
    require(waitUntil([&] { return link.confirmed(); }), "peers did not authenticate");
    link.setDrop([](int side, MessageType type) {
        return side == 0 && (type == MessageType::Candidate ||
                            type == MessageType::GatheringDone);
    });
    link.sessions[0]->publishDescription("offer");
    link.sessions[1]->publishDescription("answer");
    require(waitUntil([&] { return link.negotiated(); }), "description exchange failed");
    link.sessions[0]->publishCandidate("candidate");
    link.sessions[0]->publishGatheringDone();
    // One side can reach connected before the other has all signaling data.
    link.sessions[0]->markConnected();
    std::this_thread::sleep_for(1200ms);
    link.setDrop({});
    require(waitUntil([&] {
        return link.peers[1].candidates > 0 && link.peers[1].gatheringDone;
    }), "connected peer did not repair lost candidate/gathering completion");
}

static void noTrafficAfterBothConnected() {
    Link link;
    link.start();
    require(waitUntil([&] { return link.confirmed(); }), "peers did not authenticate");
    link.sessions[0]->publishDescription("offer");
    link.sessions[1]->publishDescription("answer");
    require(waitUntil([&] { return link.negotiated(); }), "description exchange failed");
    for (auto &session : link.sessions) session->markConnected();
    std::this_thread::sleep_for(300ms); // Drain already queued datagrams.
    const size_t before = link.packetCount();
    std::this_thread::sleep_for(1300ms);
    require(link.packetCount() == before, "connected peers keep broadcasting");
}

static void rendezvousWinnerIsPreserved(bool dualStack = false) {
    Link link(true, dualStack);
    for (auto &peer : link.peers) peer.winner = 2;
    link.sessions[0]->publishDescription("offer");
    link.sessions[1]->publishDescription("answer");
    link.start();
    require(waitUntil([&] { return link.confirmed(); }), "peers did not authenticate");
    std::this_thread::sleep_for(1200ms);
    for (const auto &peer : link.peers) {
        require(peer.winner == 2, "LAN replaced the accepted Rendezvous description");
        require(peer.restarts == 0, "LAN retry restarted the Rendezvous generation");
    }
    require(link.packetCount() < 100, "Hello replies create an unbounded echo loop");
}

static void lateHelloDoesNotStartAReplyLoop(bool rendezvous = false) {
    Link link;
    if (rendezvous)
        for (auto &peer : link.peers) peer.winner = 2;
    link.start();
    require(waitUntil([&] { return link.confirmed(); }), "peers did not authenticate");
    link.sessions[0]->publishDescription("offer");
    link.sessions[1]->publishDescription("answer");
    if (!rendezvous)
        require(waitUntil([&] { return link.negotiated(); }), "description exchange failed");
    for (auto &session : link.sessions) session->markConnected();
    std::this_thread::sleep_for(1300ms);
    std::atomic_int replyHellos{0};
    std::atomic_int replyDescriptions{0};
    link.setDrop([&](int side, MessageType type) {
        if (side == 1 && type == MessageType::Hello) ++replyHellos;
        if (side == 1 && type == MessageType::Description) ++replyDescriptions;
        return false;
    });
    link.sessions[0]->impl->sendPacket(MessageType::Hello);
    const bool replied = waitUntil([&] { return replyDescriptions > 0; });
    link.setDrop({});
    require(replied, "late retry did not receive saved signaling data");
    require(replyHellos == 0, "confirmed reply solicits another Hello reply");
    for (const auto &peer : link.peers) {
        require(peer.winner == (rendezvous ? 2 : 1), "late LAN retry changed the winning channel");
        require(peer.restarts == 0, "late LAN retry renegotiated an existing connection");
    }
}

static void candidateRetriesDoNotAccumulateBeforeDescription() {
    Link link;
    link.start();
    require(waitUntil([&] { return link.confirmed(); }), "peers did not authenticate");
    for (int repeat = 0; repeat != 20; ++repeat)
        link.sessions[0]->publishCandidate("same candidate");
    std::this_thread::sleep_for(1200ms);
    {
        std::lock_guard lock(link.sessions[1]->impl->stateMutex);
        require(link.sessions[1]->impl->pendingRemoteCandidates.size() == 1,
                "retries accumulate duplicate candidates while waiting for SDP");
    }
    link.sessions[0]->publishDescription("offer");
    require(waitUntil([&] { return link.peers[1].candidates > 0; }),
            "buffered candidate was lost when the description arrived");
}

static void invalidDescriptionsAreRejected() {
    Link link;
    auto packet = link.sessions[0]->impl->makePacket(MessageType::Description, "offer", "");
    link.sessions[1]->impl->handlePacket(packet.data(), packet.size());
    require(link.peers[1].winner == 0, "description without a generation echo was accepted");
    packet = link.sessions[0]->impl->makePacket(
        MessageType::Description, "offer", std::string(32, 'c'));
    link.sessions[1]->impl->handlePacket(packet.data(), packet.size());
    require(link.peers[1].winner == 0, "description echoing another session was accepted");
    packet = link.sessions[0]->impl->makePacket(
        MessageType::Description, "offer", std::string(32, 'b'));
    packet.back() ^= 1;
    link.sessions[1]->impl->handlePacket(packet.data(), packet.size());
    require(link.peers[1].winner == 0 && !link.sessions[1]->hasAuthenticatedPeer(),
            "tampered description authenticated or changed the connection");
}

static void connectedPromotionProbesStillReachOfferer() {
    Link link;
    link.start();
    require(waitUntil([&] { return link.confirmed(); }), "peers did not authenticate");
    for (auto &session : link.sessions) session->markConnected();
    // The production ICE boundary decides whether the route is TURN. Here we
    // verify both probe directions still dispatch to the offerer, never side 1.
    require(link.sessions[1]->requestPromotionProbe(), "answerer probe failed");
    require(waitUntil([&] { return link.peers[0].restarts > 0; }),
            "answerer probe did not reach offerer");
    std::this_thread::sleep_for(300ms);
    const int before = link.peers[0].restarts;
    require(link.sessions[0]->requestPromotionProbe(), "offerer probe failed");
    require(waitUntil([&] { return link.peers[0].restarts > before; }),
            "authenticated probe acknowledgement did not reach offerer");
    require(link.peers[1].restarts == 0, "probe restarted the answerer independently");
}

static void exchangeIPv6Candidate(bool ipv4, bool ipv6, bool blockIPv4 = false) {
    Link link(ipv4, ipv6);
    link.blockIPv4 = blockIPv4;
    link.start();
    require(waitUntil([&] { return link.confirmed(); }), "peers did not authenticate");
    link.sessions[0]->publishDescription("offer");
    link.sessions[1]->publishDescription("answer");
    require(waitUntil([&] { return link.negotiated(); }), "LAN descriptions not exchanged");
    const std::string candidate = "candidate:1 1 UDP 2122260223 2001:db8::42 45678 typ host";
    link.sessions[0]->publishCandidate(candidate);
    require(waitUntil([&] { return link.peers[1].candidates > 0; }), "IPv6 candidate was not delivered");
    {
        std::lock_guard lock(link.peers[1].candidateMutex);
        require(link.peers[1].lastCandidate == candidate, "IPv6 candidate changed in transit");
    }
    for (auto &session : link.sessions) session->markConnected();
    std::this_thread::sleep_for(300ms);
    const auto before = link.packetCount();
    std::this_thread::sleep_for(1300ms);
    require(link.packetCount() == before, "connected peers keep multicasting");
    std::lock_guard lock(link.mutex);
    require(!ipv4 || link.ipv4Packets > 0, "IPv4 discovery was removed");
    require(!ipv6 || link.ipv6Packets > 0, "IPv6 discovery was not exercised");
    require(ipv4 || link.ipv4Packets == 0, "IPv6-only case depended on IPv4");
}

static void multicastMembershipRetryAndDeduplication() {
    Link link(false, true);
    auto &state = *link.sessions[0]->impl;
    link.failJoin = true;
    state.refreshMemberships();
    require(state.joinedInterfaces.empty(), "failed join recorded as successful");
    link.failJoin = false;
    state.refreshMemberships();
    require(state.joinedInterfaces == std::vector<unsigned>{7}, "interface addresses were not deduplicated");
    const int before = link.joins;
    state.refreshMemberships();
    require(link.joins == before, "unchanged membership joined repeatedly");
    const auto group = multicastGroup("paired-lan-test");
    require(group.s6_addr[1] == 0x12 && (group.s6_addr[12] & 0x80),
            "group is not transient link-local with a dynamic group ID");
    const auto otherGroup = multicastGroup("another-pair");
    require(std::memcmp(&group, &otherGroup, sizeof(group)) != 0,
            "different pairs use identical groups");
}

static const std::string oldOffer = "a=ice-ufrag:old1\r\na=ice-pwd:old-password\r\n";
static const std::string newOffer = "a=ice-ufrag:new1\r\na=ice-pwd:new-password\r\n";

static void replacementOfferWithoutLanHistory(bool ipv4, bool ipv6) {
    Link link(ipv4, ipv6);
    // The receiver's current offer arrived via Rendezvous while off-LAN.
    // Sender has since created a replacement agent. No receiver UI or
    // connectivity callback participates in recognizing the replacement.
    link.peers[1].winner = 2;
    link.sessions[1]->setAcceptedRemoteDescription(oldOffer);
    link.sessions[1]->markConnected();
    require(!link.sessions[1]->hasAuthenticatedPeer(), "unexpected prior LAN peer");
    link.sessions[0]->publishDescription(newOffer);
    link.start();
    require(waitUntil([&] { return link.peers[1].restarts > 0; }),
            "receiver ignored replacement offer without prior LAN history");
    require(link.sessions[1]->hasAuthenticatedPeer(), "restart preceded authentication");
    require(link.peers[0].restarts == 0, "replacement offer restarted its own sender");
    require(link.peers[1].winner == 2, "new SDP was mixed into the old ICE agent");
}

static void firstLanContactPreservesAcceptedCredentials() {
    Link link;
    link.peers[1].winner = 2;
    link.sessions[1]->setAcceptedRemoteDescription(oldOffer);
    link.sessions[1]->markConnected();
    // Same ICE session, different line endings and additional trickled data.
    link.sessions[0]->publishDescription(
        "a=ice-pwd:old-password\na=ice-ufrag:old1\na=candidate:extra\n");
    link.start();
    require(waitUntil([&] { return link.confirmed(); }), "first LAN contact failed");
    std::this_thread::sleep_for(1200ms);
    require(link.peers[1].restarts == 0 && link.peers[1].winner == 2,
            "first LAN contact replaced a healthy Rendezvous connection");
}

static void replacementOffersRequireAuthenticatedCompleteCredentials() {
    Link link;
    link.peers[1].winner = 2;
    link.sessions[1]->setAcceptedRemoteDescription(oldOffer);
    link.sessions[1]->markConnected();
    auto deliver = [&](std::string_view offer, std::string_view echo, bool tamper = false) {
        auto packet = link.sessions[0]->impl->makePacket(MessageType::Description, offer, echo);
        if (tamper) packet.back() ^= 1;
        link.sessions[1]->impl->handlePacket(packet.data(), packet.size());
    };
    const std::string echo(32, 'b');
    deliver(newOffer, "");
    deliver(newOffer, std::string(32, 'c'));
    deliver(newOffer, echo, true);
    deliver("a=ice-ufrag:new1\r\n", echo);
    deliver("a=ice-ufrag:new1\na=ice-pwd:\n", echo);
    deliver(newOffer + "a=ice-ufrag:duplicate\r\n", echo);
    deliver("a=ice-ufrag:new1\na=ice-pwd:invalid password\n", echo);
    require(link.peers[1].restarts == 0 && link.peers[1].winner == 2,
            "invalid or unconfirmed replacement offer interrupted the old agent");
    deliver(newOffer, echo);
    require(link.peers[1].restarts == 1, "valid replacement was not recognized");
}

static void changedAnswerDoesNotRestartOfferer() {
    Link link;
    link.peers[0].winner = 2;
    link.sessions[0]->setAcceptedRemoteDescription(oldOffer);
    auto packet = link.sessions[1]->impl->makePacket(
        MessageType::Description, newOffer, std::string(32, 'a'));
    link.sessions[0]->impl->handlePacket(packet.data(), packet.size());
    require(link.peers[0].restarts == 0 && link.peers[0].winner == 2,
            "answerer independently replaced the offerer");
}

static void generationWatchRetriesPastLongOutages() {
    int requests = 0;
    std::vector<int> delays;
    runGenerationWatchRequests([] { return true; }, [&] {
        ++requests;
        if (requests == 1001) return GenerationWatchResult::Continue;
        if (requests == 1003) return GenerationWatchResult::Stop;
        return GenerationWatchResult::Retry;
    }, [&](int seconds) { delays.push_back(seconds); return true; });
    require(requests == 1003 && delays.size() == 1001,
            "watcher gave up before service recovery or retried a terminal result");
    require(delays[0] == 2 && delays[1] == 4 && delays[2] == 8 && delays[3] == 16,
            "retry delay does not back off");
    for (size_t i = 4; i < 1000; ++i)
        require(delays[i] == 30, "retry delay does not remain bounded");
    require(delays.back() == 2, "successful response did not reset backoff");
}

static void generationWatchCancellationStopsRetries() {
    bool active = true;
    int requests = 0, waits = 0;
    runGenerationWatchRequests([&] { return active; }, [&] {
        ++requests;
        return GenerationWatchResult::Retry;
    }, [&](int) { ++waits; active = false; return false; });
    require(requests == 1 && waits == 1, "cancellation during backoff sent more requests");
    active = true;
    requests = waits = 0;
    runGenerationWatchRequests([&] { return active; }, [&] {
        ++requests;
        active = false; // Session was replaced while HTTP was in flight.
        return GenerationWatchResult::Retry;
    }, [&](int) { ++waits; return true; });
    require(requests == 1 && waits == 0, "stale HTTP response entered backoff");
    requests = 0;
    runGenerationWatchRequests([] { return false; }, [&] {
        ++requests;
        return GenerationWatchResult::Continue;
    }, [](int) { return true; });
    require(requests == 0, "disabled watcher made an HTTP request");
}

static std::vector<char> candidateReply(std::string_view candidate) {
    std::vector<char> body(sizeof(uint32_t), 0);
    body.insert(body.end(), candidate.begin(), candidate.end());
    body.push_back('\0');
    return body;
}

static void emptyRepliesDoNotFinishPendingCandidates() {
    int requests = 0, waits = 0, pending = 0;
    bool connected = false;
    std::vector<std::string> accepted;
    const std::string candidates[] = {
        "a=candidate:1 1 UDP 2116026367 192.168.0.106 40000 typ host",
        "a=candidate:2 1 UDP 1679818239 198.51.100.2 40000 typ srflx raddr 0.0.0.0 rport 0",
        "a=candidate:3 1 UDP 2096383 203.0.113.4 40001 typ relay raddr 0.0.0.0 rport 0",
    };
    const auto result = readRendezvousCandidates([] { return true; },
        [&] { return connected; }, [&] {
            ++requests;
            // Replay the captured ordering, including enough virtual elapsed
            // time to exceed the old prematurely completed attempt's deadline.
            if (requests <= 50)
                return std::pair{requests % 2 ? std::vector<char>{'{', '}', '\n'} :
                                              candidateReply(""), 200};
            if (requests <= 53) return std::pair{candidateReply(candidates[requests - 51]), 200};
            return std::pair{std::vector<char>{'{', '}'}, 200};
        }, [&](std::string_view candidate) {
            accepted.emplace_back(candidate);
            if (accepted.size() == 3) connected = true;
            return true;
        }, [&](int seconds) { require(seconds == 2, "empty response busy-looped"); ++waits; return true; },
        [&](size_t) { ++pending; });
    require(result == CandidateStreamResult::ConnectedEnd && requests == 54 &&
            waits == 50 && pending == 50, "empty reply prematurely finished candidate stream");
    require(accepted == std::vector<std::string>(std::begin(candidates), std::end(candidates)),
            "late host/reflexive/TURN candidates were lost");
}

static void candidateReaderStopsOnCancellationAndStaleResponse() {
    bool active = true;
    int requests = 0, accepted = 0, waits = 0;
    auto result = readRendezvousCandidates([&] { return active; }, [] { return false; }, [&] {
        ++requests;
        return std::pair{std::vector<char>{}, 200};
    }, [&](std::string_view) { ++accepted; return true; }, [&](int) {
        ++waits; active = false; return false;
    }, [](size_t) {});
    require(result == CandidateStreamResult::Cancelled && requests == 1 && waits == 1,
            "cancelled pending reader continued polling");
    active = true;
    result = readRendezvousCandidates([&] { return active; }, [] { return true; }, [&] {
        active = false; // Old HTTP response finishes after session replacement.
        return std::pair{candidateReply("a=candidate:old"), 200};
    }, [&](std::string_view) { ++accepted; return true; }, [](int) { return true; }, [](size_t) {});
    require(result == CandidateStreamResult::Cancelled && accepted == 0,
            "stale response altered replacement agent");
}

static void connectedCandidateReaderPreservesLegacyCompletion() {
    int requests = 0, accepted = 0;
    const auto result = readRendezvousCandidates([] { return true; }, [] { return true; }, [&] {
        ++requests;
        return std::pair{requests == 1 ? candidateReply("a=candidate:late-relay") :
                                       std::vector<char>{'{', '}'}, 200};
    }, [&](std::string_view) { ++accepted; return true; }, [](int) {
        throw std::runtime_error("connected completion unnecessarily waited"); return false;
    }, [](size_t) {});
    require(result == CandidateStreamResult::ConnectedEnd && accepted == 1 && requests == 2,
            "connected reader dropped queued candidates or ignored legacy end");
}

static void candidateReaderErrorsRemainBounded() {
    int requests = 0, waits = 0;
    const auto result = readRendezvousCandidates([] { return true; }, [] { return false; }, [&] {
        ++requests;
        return std::pair{std::vector<char>{}, requests % 2 ? 400 : -1};
    }, [](std::string_view) { return true; }, [&](int seconds) {
        require(seconds == (requests % 2 ? 2 : 10), "HTTP error backoff changed");
        ++waits; return true;
    }, [](size_t) {});
    require(result == CandidateStreamResult::Unavailable && requests == 5 && waits == 4,
            "failed stream never returned for session recovery");
}

static void candidateBodiesAreBoundedBeforeIceParsing() {
    require(!rendezvousCandidate(std::vector<char>{'{', '}'}), "empty JSON parsed as candidate");
    auto body = candidateReply("a=candidate:valid");
    require(rendezvousCandidate(body) == "a=candidate:valid", "valid candidate rejected");
    body.pop_back();
    require(!rendezvousCandidate(body), "unterminated candidate accepted");
    body = candidateReply("not-a-candidate");
    require(!rendezvousCandidate(body), "unrelated payload accepted");
    body = candidateReply("a=candidate:valid");
    body.insert(body.end() - 1, '\0');
    require(!rendezvousCandidate(body), "embedded NUL accepted");
    require(!legacyEmptyCandidateReply(body), "malformed candidate was treated as legacy completion");
    int requests = 0;
    const auto result = readRendezvousCandidates([] { return true; }, [] { return true; }, [&] {
        ++requests;
        return std::pair{body, 200};
    }, [](std::string_view) { throw std::runtime_error("malformed candidate reached ICE"); return false; },
       [](int) { return true; }, [](size_t) {});
    require(result == CandidateStreamResult::Unavailable && requests == 5,
            "malformed HTTP 200 completed a connected checklist");
}

static uint64_t wakeAcquires = 0, wakeReleases = 0;
static uint64_t testAcquireWake() { return ++wakeAcquires; }
static void testReleaseWake(uint64_t) { ++wakeReleases; }

static void recoveryWakeSpansReplacementWithoutRetryRenewal() {
    wakeAcquires = wakeReleases = 0;
    RecoveryWakeLease lease(testAcquireWake, testReleaseWake);
    require(lease.restart(7, true, [] { return true; }), "restart refused");
    lease.connected(7); // Late callback from the agent being replaced.
    require(wakeReleases == 0, "old agent released replacement wake lease");
    lease.restart(8, false, [] { return true; });
    lease.restart(9, true, [] { return true; });
    require(wakeAcquires == 1, "retry or overlapping peer change renewed timeout");
    lease.connected(10);
    require(wakeReleases == 1, "replacement success did not release wake lease");
    lease.cancel();
    require(wakeReleases == 1, "lease released twice");
}

static void recoveryWakeRejectsStaleAndUnprotectedRequests() {
    wakeAcquires = wakeReleases = 0;
    RecoveryWakeLease lease(testAcquireWake, testReleaseWake);
    require(!lease.restart(7, true, [] { return false; }), "stale restart accepted");
    lease.restart(7, false, [] { return true; });
    require(wakeAcquires == 0, "stale or ordinary retry acquired wake lease");
    lease.restart(8, true, [] { return true; });
    lease.cancel();
    require(wakeAcquires == 1 && wakeReleases == 1, "cancellation leaked lease");
    lease.restart(9, true, [] { return true; });
    lease.connected(8);
    require(wakeAcquires == 2 && wakeReleases == 1,
            "stale completion released the newer recovery lease");
    lease.connected(10);
    require(wakeReleases == 2, "new recovery success leaked lease");
}

static void recoveryWakeDestructionReleasesPendingLease() {
    wakeAcquires = wakeReleases = 0;
    {
        RecoveryWakeLease lease(testAcquireWake, testReleaseWake);
        lease.restart(1, true, [] { return true; });
    }
    require(wakeAcquires == 1 && wakeReleases == 1, "destruction leaked recovery lease");
}

int main() {
    const std::pair<const char *, std::function<void()>> tests[] = {
        {"lost offer after peer authentication", [] { recoverLostDescription(0); }},
        {"lost answer after peer authentication", [] { recoverLostDescription(1); }},
        {"connected peer repairs lost snapshot", recoverSnapshotFromConnectedPeer},
        {"both connected stop broadcasts", noTrafficAfterBothConnected},
        {"Rendezvous winner remains authoritative", [] { rendezvousWinnerIsPreserved(); }},
        {"late Hello cannot start a connected reply loop", [] { lateHelloDoesNotStartAReplyLoop(); }},
        {"existing Rendezvous connection is not renegotiated", [] { lateHelloDoesNotStartAReplyLoop(true); }},
        {"pending candidates remain bounded under retries", candidateRetriesDoNotAccumulateBeforeDescription},
        {"unauthenticated and stale-echo descriptions rejected", invalidDescriptionsAreRejected},
        {"connected promotion probes work in both directions", connectedPromotionProbesStillReachOfferer},
        {"IPv4 discovery carries IPv6 candidates", [] { exchangeIPv6Candidate(true, false); }},
        {"IPv6-only discovery exchanges authenticated signaling", [] { exchangeIPv6Candidate(false, true); }},
        {"dual-stack discovery works when IPv4 broadcast is lost", [] { exchangeIPv6Candidate(true, true, true); }},
        {"simultaneous IPv4 and IPv6 discovery stays connected", [] { exchangeIPv6Candidate(true, true); }},
        {"dual-stack discovery preserves Rendezvous winner", [] { rendezvousWinnerIsPreserved(true); }},
        {"IPv6 memberships retry and deduplicate interface addresses", multicastMembershipRetryAndDeduplication},
        {"IPv4 replacement offer recovers without LAN history", [] { replacementOfferWithoutLanHistory(true, false); }},
        {"IPv6 replacement offer recovers without LAN history", [] { replacementOfferWithoutLanHistory(false, true); }},
        {"dual-stack replacement offer recovers without LAN history", [] { replacementOfferWithoutLanHistory(true, true); }},
        {"first LAN contact preserves accepted ICE credentials", firstLanContactPreservesAcceptedCredentials},
        {"replacement requires authenticated complete credentials", replacementOffersRequireAuthenticatedCompleteCredentials},
        {"changed answer leaves offerer stable", changedAnswerDoesNotRestartOfferer},
        {"generation watch retries past long outages with bounded backoff", generationWatchRetriesPastLongOutages},
        {"generation watch cancellation stops retries", generationWatchCancellationStopsRetries},
        {"empty replies do not finish pending candidates", emptyRepliesDoNotFinishPendingCandidates},
        {"candidate reader cancels and rejects stale responses", candidateReaderStopsOnCancellationAndStaleResponse},
        {"connected reader preserves queued candidates and legacy completion", connectedCandidateReaderPreservesLegacyCompletion},
        {"candidate reader HTTP failures return for recovery", candidateReaderErrorsRemainBounded},
        {"candidate bodies are bounded before ICE parsing", candidateBodiesAreBoundedBeforeIceParsing},
        {"recovery wake spans replacement without retry renewal", recoveryWakeSpansReplacementWithoutRetryRenewal},
        {"recovery wake rejects stale and unprotected requests", recoveryWakeRejectsStaleAndUnprotectedRequests},
        {"recovery wake destruction releases pending lease", recoveryWakeDestructionReleasesPendingLease},
    };
    int failures = 0;
    for (const auto &[name, test] : tests) {
        try {
            test();
            std::printf("PASS %s\n", name);
        } catch (const std::exception &error) {
            ++failures;
            std::printf("FAIL %s: %s\n", name, error.what());
        }
    }
    return failures ? 1 : 0;
}
