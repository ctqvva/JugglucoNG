#include "net/ICE/TurnRelayPool.hpp"
#include "net/ICE/TurnTokenResponse.hpp"
#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdio>
#include <thread>
#include <span>
#define JUGGLUCO_HAS_TWILIO_TOKEN 1
#define TWILIOACCOUNT "synthetic-test-account"
#define USERPASSBASE64 "synthetic-test-authorization"
#define LOGGERICE(...) ((void)0)
#define LOGARICE(...) ((void)0)
std::atomic<int64_t> clockMillis{1000};
int64_t elapsedRealtimeMilliseconds() { return clockMillis.load(); }
struct Options { int timeoutMilliseconds; };
std::atomic_bool releaseRequest{false};
std::atomic<int> requests{0},code{201};
struct ContextHTTPS {
    static ContextHTTPS &getContext() { static ContextHTTPS context;return context; }
    std::pair<std::vector<char>,int> postRequest(std::string_view,int,std::string_view,
            std::span<const char>,std::string_view,Options options) {
        assert(options.timeoutMilliseconds<=4000);
        ++requests;
        while(!releaseRequest.load()) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        const std::string json=R"({"username":"test-user","password":"test-password","ttl":"600"})";
        return {std::vector<char>(json.begin(),json.end()),code.load()};
    }
};
/* TWILIO_PROVIDER */

template<class Predicate> void until(Predicate ready) {
    const auto end=std::chrono::steady_clock::now()+std::chrono::seconds(3);
    while(!ready()&&std::chrono::steady_clock::now()<end)
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    assert(ready());
}
int main() {
    const auto begin=std::chrono::steady_clock::now();
    assert(twilioTurnRelays().empty());
    assert(std::chrono::steady_clock::now()-begin<std::chrono::milliseconds(100));
    until([] {return requests.load()==1;});
    for(int i=0;i<20;++i) assert(twilioTurnRelays().empty());
    assert(requests==1); // one global fetch, no parallel stampede
    releaseRequest=true;
    until([] {return twilioTurnRelays().size()==12;});
    const auto relays=twilioTurnRelays();
    assert(relays[0].transport==TurnRelayTransport::Udp);
    assert(relays[1].transport==TurnRelayTransport::Tls&&relays[1].port==443);
    assert(relays[2].transport==TurnRelayTransport::Tcp);
    const char *regions[]{"frankfurt","dublin","ashburn","umatilla","singapore",
                          "tokyo","mumbai","sydney","sao-paulo"};
    for(size_t i=0;i<std::size(regions);++i) {
        const auto &relay=relays[i+3];
        assert(relay.region==regions[i]&&relay.provider=="twilio");
        assert(relay.host==std::string(regions[i])+".turn.twilio.com");
        assert(relay.transport==TurnRelayTransport::Tls&&relay.port==443);
        assert(relay.username==relays[0].username&&relay.password==relays[0].password);
    }
    code=500;clockMillis=451001; // refresh early, retaining the still-valid token
    assert(twilioTurnRelays().size()==12);
    until([] {return requests.load()==2;});
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    assert(twilioTurnRelays().size()==12&&requests==2); // failure backoff
    releaseRequest=false;clockMillis=601001; // old credentials have expired
    assert(twilioTurnRelays().empty());
    until([] {return requests.load()==3;});
    assert(twilioTurnRelays().empty()&&requests==3);
    code=201;releaseRequest=true;
    until([] {return twilioTurnRelays().size()==12;});
    std::puts("Twilio token cache: nonblocking fetch, expiry, failure backoff and transport choices passed");
}
