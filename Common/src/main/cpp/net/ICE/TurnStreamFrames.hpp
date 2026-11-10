#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <algorithm>
#include <deque>
#include <vector>

struct TurnStreamFrame { size_t payloadBytes, wireBytes; };

// A missing value means malformed framing; zero wireBytes means incomplete.
// ChannelData is padded on a stream, whereas STUN carries its own aligned size.
inline std::optional<TurnStreamFrame> turnStreamFrame(std::span<const char> bytes) {
    if (bytes.size() < 4) return TurnStreamFrame{0, 0};
    const auto byte = [&](size_t index) { return static_cast<uint8_t>(bytes[index]); };
    const size_t length = (size_t(byte(2)) << 8) | byte(3);
    if ((byte(0) & 0xc0) == 0x40) {
        const size_t payload = 4 + length;
        return TurnStreamFrame{payload, (payload + 3) & ~size_t(3)};
    }
    if ((byte(0) & 0xc0) != 0 || length % 4) return std::nullopt;
    if (bytes.size() < 20) return TurnStreamFrame{0, 0};
    if (byte(4) != 0x21 || byte(5) != 0x12 || byte(6) != 0xa4 || byte(7) != 0x42)
        return std::nullopt;
    return TurnStreamFrame{20 + length, 20 + length};
}

// libjuice retransmits requests as if its loopback hop were an unreliable
// external UDP path. A stream already provides retransmission. In particular,
// queued duplicate unauthenticated Allocates must not generate several 401s
// after the first challenge has advanced the client's authentication state.
class TurnStreamRequests {
    std::deque<std::vector<char>> recent;
public:
    bool accept(std::span<const char> bytes) {
        if(bytes.size()<20||(static_cast<uint8_t>(bytes[0])&0xc1)!=0||
           (static_cast<uint8_t>(bytes[1])&0x10)!=0) return true;
        for(const auto &previous:recent)
            if(previous.size()==bytes.size()&&std::equal(previous.begin(),previous.end(),bytes.begin()))
                return false;
        // Authentication retries may reuse an ID with different attributes.
        // Compare the entire request, not just its transaction ID.
        if(bytes.size()<=8192) {
            recent.emplace_back(bytes.begin(),bytes.end());
            if(recent.size()>64) recent.pop_front();
        }
        return true;
    }
};

inline bool turnStreamServerFrameAllowed(std::span<const char> bytes) {
    if(bytes.size()<4) return false;
    if((static_cast<uint8_t>(bytes[0])&0xc0)==0x40) return true;
    if(bytes.size()<20) return false;
    for(size_t offset=20;offset<bytes.size();) {
        if(bytes.size()-offset<4) return false;
        const auto byte=[&](size_t i) {return static_cast<uint8_t>(bytes[offset+i]);};
        const unsigned type=(unsigned(byte(0))<<8)|byte(1);
        const size_t length=(size_t(byte(2))<<8)|byte(3);
        const size_t padded=(length+7)&~size_t(3);
        // A redirect handed to the UDP engine would silently lose the chosen
        // TCP/TLS transport. Fail this relay and let the pool select another.
        if(type==0x8023||padded>bytes.size()-offset) return false;
        offset+=padded;
    }
    return true;
}
