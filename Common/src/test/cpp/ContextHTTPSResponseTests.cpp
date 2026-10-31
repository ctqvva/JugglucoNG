#include "net/ICE/ContextHTTPS.hpp"
#include "net/ICE/TurnTokenResponse.hpp"
#include <cassert>
#include <chrono>
#include <cstdio>
#include <string>
#include <thread>

int main(int argc,char **argv) {
    assert(argc==3);
    const std::string mode=argv[2];
    const bool cancellation=mode=="cancel";
    auto cancelled=std::make_shared<std::atomic_bool>(false);
    std::jthread cancel;
    if(cancellation) cancel=std::jthread([cancelled] {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        cancelled->store(true);
    });
    const std::string input="Ttl=600";
    const auto begin=std::chrono::steady_clock::now();
    auto [body,status]=ContextHTTPS::getContext().postRequest("localhost",std::stoi(argv[1]),
        "/token",{input.data(),input.size()},"\r\nContent-Type: application/x-www-form-urlencoded",
        {.timeoutMilliseconds=mode=="timeout"||mode=="longpoll"?350:1500,
         .responseTimeoutMilliseconds=mode=="longpoll"?1500:0,.cancelled=cancelled});
    const auto elapsed=std::chrono::steady_clock::now()-begin;
    if(mode=="reject"||mode=="abrupt"||mode=="timeout"||cancellation) {
        assert(status==-1&&body.empty());
        if(mode=="timeout"||cancellation) assert(elapsed<std::chrono::seconds(1));
    } else if(mode=="empty") {
        assert(status==204&&body.empty());
    } else {
        assert(status==201);
        const auto token=TurnTokenReader({body.data(),body.size()}).read();
        assert(token&&token->username=="synthetic-user"&&token->password=="synthetic-password");
        assert(token->ttlSeconds==600);
    }
    std::puts("PASS: complete HTTPS response / bounded failure");
}
