#include "TurnTestProxy.hpp"
#include <cstdio>
#include <memory>

int main() {
    const int udp=socket(AF_INET,SOCK_DGRAM,0);
    assert(udp>=0);
    sockaddr_in local{};
    local.sin_family=AF_INET;local.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    assert(bind(udp,reinterpret_cast<sockaddr *>(&local),sizeof(local))==0);
    socklen_t length=sizeof(local);
    assert(getsockname(udp,reinterpret_cast<sockaddr *>(&local),&length)==0);
    std::jthread echo([udp](std::stop_token stop) {
        while(!stop.stop_requested()) {
            pollfd descriptor{.fd=udp,.events=POLLIN};
            if(poll(&descriptor,1,100)<=0) continue;
            std::array<char,100> bytes{};
            sockaddr_in peer{};socklen_t size=sizeof(peer);
            const int count=recvfrom(udp,bytes.data(),bytes.size(),0,reinterpret_cast<sockaddr *>(&peer),&size);
            assert(count>0);
            assert(sendto(udp,bytes.data(),count,0,reinterpret_cast<sockaddr *>(&peer),size)==count);
        }
    });
    auto proxy=std::make_unique<TurnTestProxy>(ntohs(local.sin_port),true,7);
    SSL_CTX *context=SSL_CTX_new(TLS_client_method());assert(context);
    // This tests the synthetic relay facade's buffering, not client trust.
    // Production certificate checks have separate positive/negative tests.
    SSL_CTX_set_verify(context,SSL_VERIFY_NONE,nullptr);
    const int stream=socket(AF_INET,SOCK_STREAM,0);assert(stream>=0);
    timeval timeout{.tv_sec=2};
    setsockopt(stream,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    local.sin_port=htons(proxy->port());
    assert(connect(stream,reinterpret_cast<sockaddr *>(&local),sizeof(local))==0);
    SSL *ssl=SSL_new(context);assert(ssl);SSL_set_fd(ssl,stream);
    assert(SSL_connect(ssl)==1);
    std::array<char,40> requests{};
    for(size_t offset=0;offset<requests.size();offset+=20) {
        requests[offset+1]=3;requests[offset+4]=0x21;requests[offset+5]=0x12;
        requests[offset+6]=char(0xa4);requests[offset+7]=0x42;
        requests[offset+8]=char(offset+1);
    }
    // One TLS record contains two frames. Seven-byte proxy reads leave
    // plaintext inside OpenSSL even with no further bytes on the socket.
    assert(SSL_write(ssl,requests.data(),requests.size())==int(requests.size()));
    std::array<char,40> reply{};
    size_t received=0;
    while(received<reply.size()) {
        const int count=SSL_read(ssl,reply.data()+received,reply.size()-received);
        assert(count>0);
        received+=count;
    }
    assert(reply==requests);
    assert(SSL_write(ssl,requests.data(),7)==7); // incomplete next frame
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    const auto begin=std::chrono::steady_clock::now();
    proxy.reset(); // peer remains open; shutdown must still be cancellable
    assert(std::chrono::steady_clock::now()-begin<std::chrono::seconds(1));
    SSL_free(ssl);SSL_CTX_free(context);close(stream);
    echo.request_stop();echo.join();close(udp);
    std::puts("PASS: test relay drains buffered TLS records and cancels with an open peer");
}
