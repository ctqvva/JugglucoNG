#include "net/ICE/TurnStreamRelay.hpp"
#include "net/ICE/TurnStreamFrames.hpp"
#include <array>
#include <cassert>
#include <chrono>
#include <cstring>
#include <cstdio>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <unistd.h>

int main(int argc,char **argv) {
    assert(argc==5);
    const bool tls=std::string(argv[1])=="tls";
    const std::string mode=argv[4];
    auto relay=TurnStreamRelay::start(argv[2],std::stoi(argv[3]),tls);
    assert(relay);
    const int sock=socket(AF_INET,SOCK_DGRAM,0);
    assert(sock>=0);
    timeval timeout{.tv_sec=2};
    setsockopt(sock,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    sockaddr_in address{};
    address.sin_family=AF_INET;
    address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    address.sin_port=htons(relay->loopbackPort());
    std::array<char,20> first{0,3,0,0,0x21,0x12,char(0xa4),0x42};
    first[8]=1;
    auto second=first;second[8]=2;
    const std::array<char,7> channel{0x40,1,0,3,'a','b','c'};
    assert(sendto(sock,first.data(),first.size(),0,reinterpret_cast<sockaddr *>(&address),sizeof(address))==20);
    if(mode=="cancel") {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        const auto begin=std::chrono::steady_clock::now();
        relay.reset();
        assert(std::chrono::steady_clock::now()-begin<std::chrono::seconds(1));
    } else if(mode=="reject") {
        std::array<char,100> reply{};
        assert(recv(sock,reply.data(),reply.size(),0)<0);
    } else {
        assert(sendto(sock,second.data(),second.size(),0,reinterpret_cast<sockaddr *>(&address),sizeof(address))==20);
        assert(sendto(sock,channel.data(),channel.size(),0,reinterpret_cast<sockaddr *>(&address),sizeof(address))==7);
        std::array<char,100> reply{};
        for(const auto &expected:{first,second}) {
            const int size=recv(sock,reply.data(),reply.size(),0);
            assert(size==20&&std::equal(expected.begin(),expected.end(),reply.begin()));
        }
        const int size=recv(sock,reply.data(),reply.size(),0);
        assert(size==7&&std::equal(channel.begin(),channel.end(),reply.begin()));
    }
    close(sock);
    std::puts("TURN stream: framing / verification / cancellation check passed");
}
