// A real TURN allocation and bidirectional payload test. No external service or
// user credentials: the runner supplies two loopback relay entries, one invalid.
#include "net/ICE/BundledTurnServers.hpp"
#include <array>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <arpa/inet.h>
#include <sys/socket.h>

static std::atomic<uint16_t> peerPorts[2]{};
static std::atomic<int> droppedDirectPackets = 0;

// Only the test copy of libjuice is compiled with sendto redirected here. This
// simulates NAT blocking peer-to-peer UDP, including discovered prflx routes.
// TURN control and server relay sockets continue using the real network stack.
extern "C" ssize_t clone_turn_test_sendto(int socket, const void *data, size_t size,
                                         int flags, const sockaddr *destination,
                                         socklen_t destinationSize) {
    sockaddr_in source{};
    socklen_t sourceSize = sizeof(source);
    if (destination->sa_family == AF_INET &&
        getsockname(socket, reinterpret_cast<sockaddr *>(&source), &sourceSize) == 0) {
        const auto from = ntohs(source.sin_port);
        const auto to = ntohs(reinterpret_cast<const sockaddr_in *>(destination)->sin_port);
        const auto first = peerPorts[0].load();
        const auto second = peerPorts[1].load();
        if (first && second && ((from == first && to == second) || (from == second && to == first))) {
            ++droppedDirectPackets;
            return size;
        }
    }
    return sendto(socket, data, size, flags, destination, destinationSize);
}

struct Peer {
    int index = 0;
    juice_agent_t *agent = nullptr;
    std::mutex mutex;
    std::vector<std::string> candidates;
    std::atomic<bool> received = false;
};

static void candidate(juice_agent_t *, const char *sdp, void *ptr) {
    auto &peer = *static_cast<Peer *>(ptr);
    if (strstr(sdp, " typ host")) {
        unsigned short port = 0;
        if (sscanf(sdp, "%*s %*d %*s %*u %*s %hu", &port) == 1)
            peerPorts[peer.index] = port;
    }
    // Exchange only relay candidates, and block subsequently discovered direct
    // paths in the sendto shim, so successful delivery must use an allocation.
    if (strstr(sdp, " typ relay ")) {
        std::lock_guard lock(peer.mutex);
        peer.candidates.emplace_back(sdp);
    }
}

static void receive(juice_agent_t *, const char *data, size_t size, void *ptr) {
    if (size == 11 && memcmp(data, "clone-relay", size) == 0)
        static_cast<Peer *>(ptr)->received = true;
}

int main(int argc, char **argv) {
    const bool rejectAll = argc == 2 && strcmp(argv[1], "--reject-all") == 0;
    if (argc != 1 && !rejectAll)
        return 1;
    juice_set_log_level(getenv("CLONE_TURN_TEST_LOG") ? JUICE_LOG_LEVEL_DEBUG : JUICE_LOG_LEVEL_NONE);
    auto relays = std::to_array(bundledTurnServers);
    if (relays.size() != 2)
        return 1;
    juice_server_credentials_t credentials{relays[1].username, relays[1].password, 8};
    juice_server_config_t serverConfig{};
    serverConfig.credentials = &credentials;
    serverConfig.credentials_count = 1;
    serverConfig.bind_address = "127.0.0.1";
    serverConfig.external_address = "127.0.0.1";
    serverConfig.realm = "clone-relay-test";
    juice_server_t *servers[2]{};
    for (size_t i = 0; i < relays.size(); ++i) {
        servers[i] = juice_server_create(&serverConfig);
        if (!servers[i]) {
            for (auto *server : servers)
                if (server)
                    juice_server_destroy(server);
            return 2;
        }
        relays[i].port = juice_server_get_port(servers[i]);
    }
    if (rejectAll)
        relays[1].password = "deliberately-wrong-password";

    Peer peers[2];
    peers[1].index = 1;
    bool success = true;
    for (auto &peer : peers) {
        juice_config_t config{};
        config.concurrency_mode = JUICE_CONCURRENCY_MODE_THREAD;
        config.bind_address = "127.0.0.1";
        config.turn_servers = relays.data();
        config.turn_servers_count = relays.size();
        config.cb_candidate = candidate;
        config.cb_recv = receive;
        config.user_ptr = &peer;
        peer.agent = juice_create(&config);
        success &= peer.agent != nullptr;
    }
    if (success) {
        char sdp[JUICE_MAX_SDP_STRING_LEN];
        for (int i = 0; i < 2; ++i) {
            success &= juice_get_local_description(peers[i].agent, sdp, sizeof(sdp)) == 0;
            success &= juice_set_remote_description(peers[1-i].agent, sdp) == 0;
        }
        for (auto &peer : peers)
            success &= juice_gather_candidates(peer.agent) == 0;
        int exchanged = 0;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(rejectAll ? 3 : 15);
        while (success && std::chrono::steady_clock::now() < deadline &&
               !(peers[0].received && peers[1].received)) {
            for (int i = 0; i < 2; ++i) {
                std::vector<std::string> pending;
                {
                    std::lock_guard lock(peers[i].mutex);
                    pending.swap(peers[i].candidates);
                }
                for (const auto &sdp : pending) {
                    success &= juice_add_remote_candidate(peers[1-i].agent, sdp.c_str()) == 0;
                    ++exchanged;
                }
                // Repeated sends also cover setup completing at different times.
                const auto state = juice_get_state(peers[i].agent);
                if (state == JUICE_STATE_CONNECTED || state == JUICE_STATE_COMPLETED)
                    juice_send(peers[i].agent, "clone-relay", 11);
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        if (rejectAll) {
            success &= exchanged == 0 && !peers[0].received && !peers[1].received;
        } else {
            success &= exchanged >= 2 && peers[0].received && peers[1].received && droppedDirectPackets > 0;
        }
        if (!success)
            fprintf(stderr, "relays exchanged=%d received=%d/%d states=%s/%s\n", exchanged,
                    int(peers[0].received), int(peers[1].received),
                    juice_state_to_string(juice_get_state(peers[0].agent)),
                    juice_state_to_string(juice_get_state(peers[1].agent)));
        for (auto &peer : peers) {
            if (rejectAll)
                break;
            char local[JUICE_MAX_CANDIDATE_SDP_STRING_LEN];
            char remote[JUICE_MAX_CANDIDATE_SDP_STRING_LEN];
            success &= juice_get_selected_candidates(peer.agent, local, sizeof(local), remote, sizeof(remote)) == 0;
            if (success) {
                const bool relayed = strstr(local, " typ relay ") || strstr(remote, " typ relay ");
                if (!relayed)
                    fprintf(stderr, "Selected candidates are not relayed: %s | %s\n", local, remote);
                success &= relayed;
            }
        }
    }
    for (auto &peer : peers)
        if (peer.agent)
            juice_destroy(peer.agent);
    for (auto *server : servers)
        juice_server_destroy(server);
    puts(!success ? "FAIL: TURN delivery or selected relay verification"
         : rejectAll ? "PASS: no delivery when all relay credentials are rejected"
         : "PASS: bidirectional TURN delivery with a failed relay and direct UDP blocked");
    return success ? 0 : 3;
}
