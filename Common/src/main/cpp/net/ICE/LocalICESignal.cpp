#include "LocalICESignal.hpp"

#include <arpa/inet.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#ifdef __ANDROID__
#include <jni.h>
extern JNIEnv *getenv();
namespace {
std::mutex multicastBridgeMutex;
jclass multicastClass{};
jmethodID multicastAcquire{}, multicastRelease{};
bool multicastLease(bool acquire) {
    const std::lock_guard<std::mutex> lock(multicastBridgeMutex);
    auto *env = getenv();
    if (!env || !multicastClass) return false;
    bool held = false;
    if (acquire) held = env->CallStaticBooleanMethod(multicastClass, multicastAcquire);
    else env->CallStaticVoidMethod(multicastClass, multicastRelease);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return held;
}
}

// Resolve on the Java configuration thread, not a native receiver thread whose
// FindClass would use the system class loader.
void initializeLocalICEMulticast(JNIEnv *env) {
    const std::lock_guard<std::mutex> lock(multicastBridgeMutex);
    if (multicastClass) return;
    auto cls = env->FindClass("tk/glucodata/CloneMulticastLock");
    if (cls) {
        multicastAcquire = env->GetStaticMethodID(cls, "acquire", "()Z");
        if (!env->ExceptionCheck())
            multicastRelease = env->GetStaticMethodID(cls, "release", "()V");
        if (!env->ExceptionCheck() && multicastAcquire && multicastRelease)
            multicastClass = static_cast<jclass>(env->NewGlobalRef(cls));
        env->DeleteLocalRef(cls);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
}
#else
namespace { bool multicastLease(bool) { return false; } }
#endif

#include <ascon.h>
#include <zlib.h>

#include "logs.hpp"
#include "net/makerandom.hpp"

#define LOGGERLOCALICE(...) LOGGER("ICE local signal: " __VA_ARGS__)
#define LOGARLOCALICE(...) LOGAR("ICE local signal: " __VA_ARGS__)

bool applyLocalICEDescription(int allindex, juice_agent_t *agent,
                              uint64_t agentGeneration,
                              std::string_view description,
                              std::string_view remoteGeneration);
void applyLocalICECandidate(int allindex, juice_agent_t *agent,
                            uint64_t agentGeneration,
                            std::string_view candidate);
void applyLocalICEGatheringDone(int allindex, juice_agent_t *agent,
                                uint64_t agentGeneration);
void localICEPeerGenerationChanged(int allindex, juice_agent_t *agent,
                                   uint64_t agentGeneration);
void localICEPromotionAvailable(int allindex, juice_agent_t *agent,
                                uint64_t agentGeneration);

namespace {

constexpr std::array<uint8_t, 6> wireMagic{'J', 'N', 'G', 'I', 1, 0};
constexpr size_t nonceLength = ASCON_AEAD_NONCE_LEN;
constexpr size_t tagLength = 16;
constexpr size_t tokenLength = 32;
constexpr size_t plainHeaderLength = 2 + 2 + 2 + tokenLength + tokenLength;
constexpr size_t maximumPayloadLength = 6144;
constexpr uint16_t firstLocalPort = 20000;
constexpr uint16_t localPortCount = 10000;
constexpr auto snapshotRetryInterval = std::chrono::seconds(1);

enum class SnapshotKind { Handshake, Retry, Reply };

enum class MessageType : uint8_t {
    Hello = 1,
    Description = 2,
    Candidate = 3,
    GatheringDone = 4,
    PromotionProbe = 5,
    PromotionAck = 6,
};

bool isGenerationToken(std::string_view token) {
    return token.size() == tokenLength &&
           std::all_of(token.begin(), token.end(), [](unsigned char value) {
               return (value >= '0' && value <= '9') ||
                      (value >= 'a' && value <= 'f');
           });
}

std::optional<std::string> makeProbeToken() {
    std::array<uint8_t, 16> random{};
    if (!makerandom(random.data(), random.size()))
        return std::nullopt;
    static constexpr char hex[] = "0123456789abcdef";
    std::string token(random.size() * 2, '0');
    for (size_t index = 0; index < random.size(); ++index) {
        token[index * 2] = hex[random[index] >> 4];
        token[index * 2 + 1] = hex[random[index] & 0x0f];
    }
    return token;
}

uint16_t readUint16(const uint8_t *source) {
    uint16_t value;
    std::memcpy(&value, source, sizeof(value));
    return ntohs(value);
}

void appendUint16(std::vector<uint8_t> &target, size_t value) {
    const uint16_t networkValue = htons(static_cast<uint16_t>(value));
    const auto *bytes = reinterpret_cast<const uint8_t *>(&networkValue);
    target.insert(target.end(), bytes, bytes + sizeof(networkValue));
}

uint16_t localPortForLabel(std::string_view label) {
    const auto hash = crc32(0, reinterpret_cast<const Bytef *>(label.data()),
                            static_cast<uInt>(label.size()));
    return static_cast<uint16_t>(firstLocalPort + hash % localPortCount);
}

std::vector<sockaddr_in> broadcastDestinations(uint16_t port) {
    std::vector<sockaddr_in> destinations;
    ifaddrs *addresses = nullptr;
    if (getifaddrs(&addresses) != 0)
        return destinations;
    for (const ifaddrs *entry = addresses; entry; entry = entry->ifa_next) {
        if (!entry->ifa_addr || entry->ifa_addr->sa_family != AF_INET ||
            !(entry->ifa_flags & IFF_UP) ||
            !(entry->ifa_flags & IFF_BROADCAST) ||
            (entry->ifa_flags & IFF_LOOPBACK) || !entry->ifa_broadaddr)
            continue;
        sockaddr_in destination =
            *reinterpret_cast<const sockaddr_in *>(entry->ifa_broadaddr);
        destination.sin_port = htons(port);
        const auto duplicate = std::find_if(
            destinations.begin(), destinations.end(),
            [&destination](const sockaddr_in &existing) {
                return existing.sin_addr.s_addr == destination.sin_addr.s_addr;
            });
        if (duplicate == destinations.end())
            destinations.push_back(destination);
    }
    freeifaddrs(addresses);
    return destinations;
}

in6_addr multicastGroup(std::string_view label) {
    // Transient, link-local group with a dynamically chosen 32-bit group ID
    // (RFC 3307). The pairing label selects a group; AEAD authenticates peers.
    in6_addr group{};
    group.s6_addr[0] = 0xff;
    group.s6_addr[1] = 0x12;
    const uint32_t id = htonl(0x80000000u | crc32(0,
        reinterpret_cast<const Bytef *>(label.data()), label.size()));
    std::memcpy(group.s6_addr + 12, &id, sizeof(id));
    return group;
}

std::vector<unsigned> multicastInterfaces() {
    std::vector<unsigned> interfaces;
    ifaddrs *addresses = nullptr;
    if (getifaddrs(&addresses) != 0) return interfaces;
    for (const auto *entry = addresses; entry; entry = entry->ifa_next) {
        if (!entry->ifa_addr || entry->ifa_addr->sa_family != AF_INET6 ||
            !entry->ifa_name || !(entry->ifa_flags & IFF_UP) ||
            !(entry->ifa_flags & IFF_MULTICAST) || (entry->ifa_flags & IFF_LOOPBACK))
            continue;
        const auto index = if_nametoindex(entry->ifa_name);
        if (index && std::find(interfaces.begin(), interfaces.end(), index) == interfaces.end())
            interfaces.push_back(index);
    }
    freeifaddrs(addresses);
    return interfaces;
}

}  // namespace

struct LocalICESignalSession::Impl {
    const int allindex;
    juice_agent_t *const agent;
    const uint64_t agentGeneration;
    const std::string label;
    const bool side;
    const std::array<uint8_t, 16> key;
    const uint16_t port;

    std::atomic_bool stopping{false};
    std::atomic_bool connected{false};
    std::atomic_bool authenticatedPeer{false};
    std::atomic_bool remoteDescriptionApplied{false};
    std::mutex stateMutex;
    std::mutex sendMutex;
    std::string localGeneration;
    std::string peerGeneration;
    std::string localDescription;
    std::vector<std::string> localCandidates;
    bool localGatheringDone{false};
    std::vector<std::string> pendingRemoteCandidates;
    bool pendingRemoteGatheringDone{false};
    std::chrono::steady_clock::time_point nextSnapshot{};
    std::string promotionProbeToken;
    std::atomic_bool promotionProbeActive{false};
    int socketFd{-1};
    int multicastFd{-1};
    std::vector<unsigned> joinedInterfaces; // Protected by sendMutex.
    bool multicastLockHeld{false};
    // The same authenticated datagram is sent twice on each discovery family.
    // Ignore those copies, but allow retry snapshots with fresh nonces.
    std::array<std::array<uint8_t, nonceLength>, 64> receivedNonces{};
    size_t receivedNonceCount{}, nextReceivedNonce{}; // Receiver thread only.
    std::jthread receiveThread;

    Impl(int index, juice_agent_t *candidate, uint64_t generation,
         std::string_view connectionLabel, bool connectionSide,
         const std::array<uint8_t, 16> &connectionKey,
         std::string_view connectionGeneration)
        : allindex(index),
          agent(candidate),
          agentGeneration(generation),
          label(connectionLabel),
          side(connectionSide),
          key(connectionKey),
          port(localPortForLabel(connectionLabel)),
          localGeneration(connectionGeneration) {}

    bool createIPv4Socket() {
        socketFd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (socketFd < 0)
            return false;
        int enabled = 1;
        setsockopt(socketFd, SOL_SOCKET, SO_REUSEADDR, &enabled,
                   sizeof(enabled));
#ifdef SO_REUSEPORT
        setsockopt(socketFd, SOL_SOCKET, SO_REUSEPORT, &enabled,
                   sizeof(enabled));
#endif
        if (setsockopt(socketFd, SOL_SOCKET, SO_BROADCAST, &enabled,
                       sizeof(enabled)) != 0) {
            close(socketFd);
            socketFd = -1;
            return false;
        }
        sockaddr_in address{};
        address.sin_family = AF_INET;
        address.sin_addr.s_addr = htonl(INADDR_ANY);
        address.sin_port = htons(port);
        if (bind(socketFd, reinterpret_cast<const sockaddr *>(&address),
                 sizeof(address)) != 0) {
            close(socketFd);
            socketFd = -1;
            return false;
        }
        return true;
    }

    bool createIPv6Socket() {
        multicastFd = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (multicastFd < 0) return false;
        int enabled = 1;
        setsockopt(multicastFd, SOL_SOCKET, SO_REUSEADDR, &enabled, sizeof(enabled));
        // A separate v6-only socket cannot steal IPv4 broadcasts from the
        // existing listener. Either family may fail without disabling the other.
        const int hops = 1;
        sockaddr_in6 address{};
        address.sin6_family = AF_INET6;
        address.sin6_port = htons(port);
        if (setsockopt(multicastFd, IPPROTO_IPV6, IPV6_V6ONLY, &enabled, sizeof(enabled)) != 0 ||
            setsockopt(multicastFd, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, &hops, sizeof(hops)) != 0 ||
            bind(multicastFd, reinterpret_cast<const sockaddr *>(&address), sizeof(address)) != 0) {
            close(multicastFd);
            multicastFd = -1;
            return false;
        }
        return true;
    }

    void refreshMemberships() {
        if (multicastFd < 0) return;
        const auto interfaces = multicastInterfaces();
        const std::lock_guard<std::mutex> lock(sendMutex);
        ipv6_mreq request{};
        request.ipv6mr_multiaddr = multicastGroup(label);
        for (auto it = joinedInterfaces.begin(); it != joinedInterfaces.end();) {
            if (std::find(interfaces.begin(), interfaces.end(), *it) != interfaces.end()) {
                ++it;
                continue;
            }
            request.ipv6mr_interface = *it;
            setsockopt(multicastFd, IPPROTO_IPV6, IPV6_LEAVE_GROUP, &request, sizeof(request));
            it = joinedInterfaces.erase(it);
        }
        for (auto index : interfaces) {
            if (std::find(joinedInterfaces.begin(), joinedInterfaces.end(), index) != joinedInterfaces.end())
                continue;
            request.ipv6mr_interface = index;
            if (setsockopt(multicastFd, IPPROTO_IPV6, IPV6_JOIN_GROUP, &request, sizeof(request)) == 0) {
                joinedInterfaces.push_back(index);
                LOGGERLOCALICE("joined IPv6 interface %u\n", index);
            }
        }
    }

    bool createSocket() {
        const bool ipv4 = createIPv4Socket();
        const bool ipv6 = createIPv6Socket();
        refreshMemberships();
        LOGGERLOCALICE("discovery listeners IPv4=%d IPv6=%d\n", ipv4, ipv6);
        return ipv4 || ipv6;
    }

    std::vector<uint8_t> makePacket(MessageType type, std::string_view data,
                                    std::string_view echo) {
        if (label.size() > UINT16_MAX || data.size() > UINT16_MAX ||
            data.size() > maximumPayloadLength ||
            !isGenerationToken(localGeneration))
            return {};
        std::vector<uint8_t> plain;
        plain.reserve(plainHeaderLength + label.size() + data.size());
        plain.push_back(static_cast<uint8_t>(type));
        plain.push_back(side ? 1 : 0);
        appendUint16(plain, label.size());
        appendUint16(plain, data.size());
        plain.insert(plain.end(), localGeneration.begin(), localGeneration.end());
        if (isGenerationToken(echo))
            plain.insert(plain.end(), echo.begin(), echo.end());
        else
            plain.insert(plain.end(), tokenLength, 0);
        plain.insert(plain.end(), label.begin(), label.end());
        plain.insert(plain.end(), data.begin(), data.end());

        std::array<uint8_t, nonceLength> nonce{};
        if (!makerandom(nonce.data(), nonce.size()))
            return {};
        std::vector<uint8_t> packet;
        packet.resize(wireMagic.size() + nonce.size() + sizeof(uint16_t) +
                      plain.size() + tagLength);
        auto *cursor = packet.data();
        std::memcpy(cursor, wireMagic.data(), wireMagic.size());
        cursor += wireMagic.size();
        std::memcpy(cursor, nonce.data(), nonce.size());
        cursor += nonce.size();
        const uint16_t encryptedLength = htons(static_cast<uint16_t>(plain.size()));
        std::memcpy(cursor, &encryptedLength, sizeof(encryptedLength));
        cursor += sizeof(encryptedLength);
        auto *ciphertext = cursor;
        auto *tag = ciphertext + plain.size();
        const size_t associatedLength = wireMagic.size() + nonce.size() +
                                        sizeof(encryptedLength);
        ascon_aead128a_encrypt(ciphertext, tag, key.data(), nonce.data(),
                               packet.data(), plain.data(), associatedLength,
                               plain.size(), tagLength);
        return packet;
    }

    void sendPacket(MessageType type, std::string_view data = {},
                    std::string_view explicitEcho = {}) {
        if (stopping.load(std::memory_order_acquire))
            return;
        std::string echo;
        {
            const std::lock_guard<std::mutex> lock(stateMutex);
            echo = explicitEcho.empty() ? peerGeneration
                                        : std::string(explicitEcho);
        }
        auto packet = makePacket(type, data, echo);
        if (packet.empty())
            return;
        const auto destinations = broadcastDestinations(port);
        const std::lock_guard<std::mutex> lock(sendMutex);
        if (stopping.load(std::memory_order_acquire)) return;
        if (socketFd >= 0)
        for (const auto &destination : destinations) {
            for (int attempt = 0; attempt < 2; ++attempt)
                sendto(socketFd, packet.data(), packet.size(), MSG_NOSIGNAL,
                       reinterpret_cast<const sockaddr *>(&destination),
                       sizeof(destination));
        }
        if (multicastFd >= 0) {
            sockaddr_in6 destination{};
            destination.sin6_family = AF_INET6;
            destination.sin6_port = htons(port);
            destination.sin6_addr = multicastGroup(label);
            for (auto index : joinedInterfaces) {
                destination.sin6_scope_id = index;
                for (int attempt = 0; attempt < 2; ++attempt)
                    sendto(multicastFd, packet.data(), packet.size(), MSG_NOSIGNAL,
                           reinterpret_cast<const sockaddr *>(&destination), sizeof(destination));
            }
        }
    }

    void sendSnapshot(std::string_view echo = {},
                      SnapshotKind kind = SnapshotKind::Handshake) {
        std::string description;
        std::vector<std::string> candidates;
        bool gatheringDone;
        {
            const std::lock_guard<std::mutex> lock(stateMutex);
            const auto now = std::chrono::steady_clock::now();
            if (kind != SnapshotKind::Handshake && now < nextSnapshot)
                return;
            nextSnapshot = now + snapshotRetryInterval;
            description = localDescription;
            candidates = localCandidates;
            gatheringDone = localGatheringDone;
        }
        // A confirmed retry reply must not solicit another reply, even when
        // network delay exceeds the retry interval.
        if (kind != SnapshotKind::Reply)
            sendPacket(MessageType::Hello, {}, echo);
        if (!description.empty())
            sendPacket(MessageType::Description, description, echo);
        for (const auto &candidate : candidates)
            sendPacket(MessageType::Candidate, candidate, echo);
        if (gatheringDone)
            sendPacket(MessageType::GatheringDone, {}, echo);
    }

    bool decryptPacket(const uint8_t *packet, size_t length,
                       std::vector<uint8_t> &plain) {
        const size_t wireHeaderLength = wireMagic.size() + nonceLength +
                                        sizeof(uint16_t);
        if (length < wireHeaderLength + tagLength ||
            std::memcmp(packet, wireMagic.data(), wireMagic.size()) != 0)
            return false;
        const auto *nonce = packet + wireMagic.size();
        const uint16_t encryptedLength =
            readUint16(packet + wireMagic.size() + nonceLength);
        if (encryptedLength < plainHeaderLength ||
            encryptedLength > maximumPayloadLength + plainHeaderLength +
                                  label.size() ||
            length != wireHeaderLength + encryptedLength + tagLength)
            return false;
        const auto *ciphertext = packet + wireHeaderLength;
        const auto *tag = ciphertext + encryptedLength;
        plain.resize(encryptedLength);
        return ascon_aead128a_decrypt(
            plain.data(), key.data(), nonce, packet, ciphertext, tag,
            wireHeaderLength, encryptedLength, tagLength);
    }

    void applyPendingRemote() {
        std::vector<std::string> candidates;
        bool gatheringDone;
        {
            const std::lock_guard<std::mutex> lock(stateMutex);
            candidates.swap(pendingRemoteCandidates);
            gatheringDone = std::exchange(pendingRemoteGatheringDone, false);
        }
        for (const auto &candidate : candidates)
            applyLocalICECandidate(allindex, agent, agentGeneration, candidate);
        if (gatheringDone)
            applyLocalICEGatheringDone(allindex, agent, agentGeneration);
    }

    void handlePacket(const uint8_t *packet, size_t length) {
        std::vector<uint8_t> plain;
        if (!decryptPacket(packet, length, plain))
            return;
        const auto type = static_cast<MessageType>(plain[0]);
        const bool remoteSide = plain[1] != 0;
        const size_t labelLength = readUint16(plain.data() + 2);
        const size_t dataLength = readUint16(plain.data() + 4);
        if (remoteSide == side ||
            plain.size() != plainHeaderLength + labelLength + dataLength)
            return;
        const std::string_view generation(
            reinterpret_cast<const char *>(plain.data() + 6), tokenLength);
        const std::string_view echo(
            reinterpret_cast<const char *>(plain.data() + 6 + tokenLength),
            tokenLength);
        const std::string_view receivedLabel(
            reinterpret_cast<const char *>(plain.data() + plainHeaderLength),
            labelLength);
        const std::string_view data(
            reinterpret_cast<const char *>(plain.data() + plainHeaderLength +
                                           labelLength),
            dataLength);
        if (receivedLabel != label || !isGenerationToken(generation))
            return;

        std::array<uint8_t, nonceLength> nonce;
        std::memcpy(nonce.data(), packet + wireMagic.size(), nonce.size());
        const auto end = receivedNonces.begin() + receivedNonceCount;
        if (std::find(receivedNonces.begin(), end, nonce) != end) return;
        receivedNonces[nextReceivedNonce] = nonce;
        nextReceivedNonce = (nextReceivedNonce + 1) % receivedNonces.size();
        receivedNonceCount = std::min(receivedNonceCount + 1, receivedNonces.size());

        const bool echoMatches = echo == localGeneration;
        bool peerChanged = false;
        bool unconfirmedPeerChange = false;
        {
            const std::lock_guard<std::mutex> lock(stateMutex);
            if (peerGeneration.empty()) {
                peerGeneration.assign(generation);
            } else if (peerGeneration != generation && echoMatches) {
                peerGeneration.assign(generation);
                peerChanged = true;
            } else if (peerGeneration != generation) {
                unconfirmedPeerChange = true;
            }
        }
        const bool wasConfirmed =
            authenticatedPeer.load(std::memory_order_acquire);
        if (echoMatches)
            authenticatedPeer.store(true, std::memory_order_release);
        if (peerChanged) {
            // Side 0 owns the offer. Let side 1 follow a replacement offer,
            // but never let both peers answer a generation change by
            // replacing each other forever. A stable side-0 agent republishes
            // its snapshot so the new side-1 generation can answer it.
            if (side) {
                LOGARLOCALICE("authenticated offerer generation changed");
                localICEPeerGenerationChanged(allindex, agent, agentGeneration);
            } else {
                LOGARLOCALICE("authenticated answerer generation changed; keeping offer stable");
                sendSnapshot(generation);
            }
            return;
        }

        if (type == MessageType::PromotionProbe) {
            if (isGenerationToken(data)) {
                sendPacket(MessageType::PromotionAck, data, generation);
                // Side 0 owns replacement offers. If side 1 discovers the
                // shared LAN first, this authenticated request lets side 0
                // coordinate the new generation instead of both peers racing.
                if (!side)
                    localICEPromotionAvailable(allindex, agent,
                                               agentGeneration);
            }
            return;
        }
        if (type == MessageType::PromotionAck) {
            bool matches = false;
            if (!side && echoMatches && isGenerationToken(data)) {
                const std::lock_guard<std::mutex> lock(stateMutex);
                matches = promotionProbeActive.load(std::memory_order_acquire) &&
                          promotionProbeToken == data;
                if (matches) {
                    promotionProbeActive.store(false,
                                               std::memory_order_release);
                    promotionProbeToken.clear();
                }
            }
            if (matches)
                localICEPromotionAvailable(allindex, agent, agentGeneration);
            return;
        }

        if (type == MessageType::Hello) {
            if (unconfirmedPeerChange)
                sendPacket(MessageType::Hello, {}, generation);
            else
                // Confirmation proves peer identity, not delivery of its SDP,
                // candidates or completion. Even a connected peer must answer
                // retries from a peer still negotiating. Confirmed replies are
                // rate-limited and do not contain another Hello.
                sendSnapshot(generation, echoMatches && wasConfirmed
                    ? SnapshotKind::Reply : SnapshotKind::Handshake);
            return;
        }
        if (!echoMatches)
            return;
        switch (type) {
            case MessageType::Description:
                if (!remoteDescriptionApplied.load() &&
                    applyLocalICEDescription(allindex, agent, agentGeneration,
                                             data, generation)) {
                    remoteDescriptionApplied = true;
                    applyPendingRemote();
                }
                break;
            case MessageType::Candidate:
                if (remoteDescriptionApplied.load()) {
                    applyLocalICECandidate(allindex, agent, agentGeneration,
                                           data);
                } else {
                    const std::lock_guard<std::mutex> lock(stateMutex);
                    if (std::find(pendingRemoteCandidates.begin(),
                                  pendingRemoteCandidates.end(), data) ==
                        pendingRemoteCandidates.end())
                        pendingRemoteCandidates.emplace_back(data);
                }
                break;
            case MessageType::GatheringDone:
                if (remoteDescriptionApplied.load()) {
                    applyLocalICEGatheringDone(allindex, agent,
                                               agentGeneration);
                } else {
                    const std::lock_guard<std::mutex> lock(stateMutex);
                    pendingRemoteGatheringDone = true;
                }
                break;
            case MessageType::Hello:
                break;
            default:
                break;
        }
    }

    void run(std::stop_token stopToken) {
        std::array<uint8_t, 8192> packet{};
        auto nextHello = std::chrono::steady_clock::now();
        auto nextInterfaces = nextHello;
        while (!stopToken.stop_requested() &&
               !stopping.load(std::memory_order_acquire)) {
            const auto now = std::chrono::steady_clock::now();
            if (now >= nextInterfaces) {
                refreshMemberships();
                nextInterfaces = now + std::chrono::seconds(2);
            }
            if (!connected.load(std::memory_order_acquire) && now >= nextHello) {
                // UDP publication can be lost after the Hello handshake has
                // completed. Retry the saved signaling data, not just identity.
                sendSnapshot({}, SnapshotKind::Retry);
                nextHello = now + snapshotRetryInterval;
            }
            pollfd descriptors[]{{socketFd, POLLIN, 0}, {multicastFd, POLLIN, 0}};
            const int result = poll(descriptors, 2, 250);
            if (result <= 0) continue;
            for (const auto &descriptor : descriptors) {
                if (!(descriptor.revents & POLLIN)) continue;
                const ssize_t received = recvfrom(descriptor.fd, packet.data(),
                                              packet.size(), 0, nullptr, nullptr);
                if (received > 0)
                    handlePacket(packet.data(), static_cast<size_t>(received));
            }
        }
    }
};

LocalICESignalSession::LocalICESignalSession(
    int allindex, juice_agent_t *agent, uint64_t agentGeneration,
    std::string_view label, bool side, const std::array<uint8_t, 16> &key,
    std::string_view localGeneration)
    : impl(std::make_unique<Impl>(allindex, agent, agentGeneration, label, side,
                                  key, localGeneration)) {}

LocalICESignalSession::~LocalICESignalSession() { stop(); }

bool LocalICESignalSession::start() {
    if (!isGenerationToken(impl->localGeneration)) return false;
    if (!impl->createSocket()) {
        LOGGERLOCALICE("could not bind UDP port %u\n", impl->port);
        return false;
    }
    impl->multicastLockHeld = multicastLease(true);
    impl->receiveThread =
        std::jthread([state = impl.get()](std::stop_token token) {
            state->run(token);
        });
    return true;
}

void LocalICESignalSession::stop() {
    if (!impl || impl->stopping.exchange(true))
        return;
    if (impl->receiveThread.joinable())
        impl->receiveThread.request_stop();
    if (impl->socketFd >= 0)
        shutdown(impl->socketFd, SHUT_RDWR);
    if (impl->multicastFd >= 0)
        shutdown(impl->multicastFd, SHUT_RDWR);
    if (impl->receiveThread.joinable())
        impl->receiveThread.join();
    const std::lock_guard<std::mutex> lock(impl->sendMutex);
    if (impl->socketFd >= 0) {
        close(impl->socketFd);
        impl->socketFd = -1;
    }
    if (impl->multicastFd >= 0) {
        close(impl->multicastFd);
        impl->multicastFd = -1;
    }
    if (impl->multicastLockHeld) {
        multicastLease(false);
        impl->multicastLockHeld = false;
    }
}

void LocalICESignalSession::publishDescription(std::string_view description) {
    {
        const std::lock_guard<std::mutex> lock(impl->stateMutex);
        impl->localDescription.assign(description);
    }
    impl->sendPacket(MessageType::Description, description);
}

void LocalICESignalSession::publishCandidate(std::string_view candidate) {
    {
        const std::lock_guard<std::mutex> lock(impl->stateMutex);
        if (std::find(impl->localCandidates.begin(),
                      impl->localCandidates.end(), candidate) ==
            impl->localCandidates.end())
            impl->localCandidates.emplace_back(candidate);
    }
    impl->sendPacket(MessageType::Candidate, candidate);
}

void LocalICESignalSession::publishGatheringDone() {
    {
        const std::lock_guard<std::mutex> lock(impl->stateMutex);
        impl->localGatheringDone = true;
    }
    impl->sendPacket(MessageType::GatheringDone);
}

void LocalICESignalSession::markConnected() { impl->connected = true; }

bool LocalICESignalSession::requestPromotionProbe() {
    const auto token = makeProbeToken();
    if (!token)
        return false;
    {
        const std::lock_guard<std::mutex> lock(impl->stateMutex);
        impl->promotionProbeToken = *token;
        impl->promotionProbeActive.store(true, std::memory_order_release);
    }
    impl->sendPacket(MessageType::PromotionProbe, *token);
    return true;
}

bool LocalICESignalSession::hasAuthenticatedPeer() const {
    return impl->authenticatedPeer.load(std::memory_order_acquire);
}

std::shared_ptr<LocalICESignalSession> startLocalICESignal(
    int allindex, juice_agent_t *agent, uint64_t agentGeneration,
    std::string_view label, bool side, const std::array<uint8_t, 16> &key,
    std::string_view localGeneration) {
    auto session = std::make_shared<LocalICESignalSession>(
        allindex, agent, agentGeneration, label, side, key, localGeneration);
    return session->start() ? std::move(session) : nullptr;
}
