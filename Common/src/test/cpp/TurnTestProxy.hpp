// Test-only TCP/TLS facade for the bundled UDP TURN server. Each stream gets
// its own UDP socket, preserving the allocation's five-tuple and permissions.
#pragma once
#include "net/ICE/TurnStreamFrames.hpp"
#include <openssl/ssl.h>
#include <algorithm>
#include <array>
#include <cassert>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <fcntl.h>
#include <thread>
#include <string>
#include <vector>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <poll.h>
#include <csignal>
#include <unistd.h>

class TurnTestProxy {
    int listener=-1;
    uint16_t service=0, target;
    size_t readLimit;
    SSL_CTX *context=nullptr;
    std::jthread acceptor;
    std::vector<std::jthread> clients;
    void client(std::stop_token stop,int stream) {
        int udp=socket(AF_INET,SOCK_DGRAM,0);
        sockaddr_in address{};
        address.sin_family=AF_INET;
        address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        address.sin_port=htons(target);
        connect(udp,reinterpret_cast<sockaddr *>(&address),sizeof(address));
        const int flags=fcntl(stream,F_GETFL,0);
        if(flags<0||fcntl(stream,F_SETFL,flags|O_NONBLOCK)<0) {close(stream);close(udp);return;}
        SSL *ssl=nullptr;
        const auto wait=[&](short events) {
            const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(5);
            while(!stop.stop_requested()&&std::chrono::steady_clock::now()<deadline) {
                pollfd descriptor{.fd=stream,.events=events};
                if(poll(&descriptor,1,100)<0) {if(errno==EINTR) continue;return false;}
                if(descriptor.revents&events) return true;
                if(descriptor.revents&(POLLHUP|POLLERR|POLLNVAL)) return false;
            }
            return false;
        };
        const auto retryTLS=[&](int result) {
            const int error=SSL_get_error(ssl,result);
            return error==SSL_ERROR_WANT_READ?wait(POLLIN):
                   error==SSL_ERROR_WANT_WRITE?wait(POLLOUT):false;
        };
        if(context) {
            if(const char *delay=getenv("TURN_TEST_HANDSHAKE_DELAY_MS")) {
                const auto end=std::chrono::steady_clock::now()+std::chrono::milliseconds(std::stoi(delay));
                while(!stop.stop_requested()&&std::chrono::steady_clock::now()<end)
                    std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
            ssl=SSL_new(context); SSL_set_fd(ssl,stream);
            int result;
            while(!stop.stop_requested()&&(result=SSL_accept(ssl))!=1)
                if(!retryTLS(result)) {SSL_free(ssl);close(stream);close(udp);return;}
        }
        const auto write=[&](std::span<const char> data) {
            size_t sent=0;
            while(sent<data.size()&&!stop.stop_requested()) {
                const int result=ssl?SSL_write(ssl,data.data()+sent,data.size()-sent):
                                     send(stream,data.data()+sent,data.size()-sent,0);
                if(result>0) {sent+=result;continue;}
                if(ssl) {if(!retryTLS(result)) return false;}
                else if(result<0&&(errno==EAGAIN||errno==EWOULDBLOCK)) {if(!wait(POLLOUT)) return false;}
                else if(result<0&&errno==EINTR) continue;
                else return false;
            }
            return sent==data.size();
        };
        std::vector<char> pending;
        std::array<char,65560> bytes{};
        while(!stop.stop_requested()) {
            pollfd sockets[2]{{.fd=stream,.events=POLLIN},{.fd=udp,.events=POLLIN}};
            // SSL_read may have already buffered more plaintext than it
            // returned. Drain that data without waiting for another TCP byte.
            if(poll(sockets,2,ssl&&SSL_pending(ssl)>0?0:100)<0) {
                if(errno==EINTR) continue;
                break;
            }
            bool failed=false;
            if((sockets[0].revents&(POLLIN|POLLHUP))||(ssl&&SSL_pending(ssl)>0)) {
                while(!stop.stop_requested()) {
                    const size_t capacity=std::min(readLimit,bytes.size());
                    const int count=ssl?SSL_read(ssl,bytes.data(),capacity):recv(stream,bytes.data(),capacity,0);
                    if(count<=0) {
                        if(ssl&&SSL_get_error(ssl,count)==SSL_ERROR_WANT_READ) break;
                        if(ssl&&SSL_get_error(ssl,count)==SSL_ERROR_WANT_WRITE&&wait(POLLOUT)) continue;
                        if(!ssl&&count<0&&(errno==EAGAIN||errno==EWOULDBLOCK)) break;
                        if(!ssl&&count<0&&errno==EINTR) continue;
                        failed=true;break;
                    }
                    pending.insert(pending.end(),bytes.begin(),bytes.begin()+count);
                    size_t consumed=0;
                    while(consumed<pending.size()) {
                        auto frame=turnStreamFrame(std::span(pending).subspan(consumed));
                        if(!frame) {failed=true;break;}
                        if(!frame->wireBytes||frame->wireBytes>pending.size()-consumed) break;
                        if(send(udp,pending.data()+consumed,frame->payloadBytes,0)!=static_cast<int>(frame->payloadBytes)) {
                            failed=true;break;
                        }
                        consumed+=frame->wireBytes;
                    }
                    pending.erase(pending.begin(),pending.begin()+consumed);
                    if(failed||pending.size()>65560) {failed=true;break;}
                }
            }
            if(failed||(sockets[0].revents&(POLLHUP|POLLERR|POLLNVAL))) break;
            if(sockets[1].revents&POLLIN) {
                const int count=recv(udp,bytes.data(),bytes.size()-3,0);
                if(count<=0) break;
                const auto frame=turnStreamFrame({bytes.data(),static_cast<size_t>(count)});
                if(!frame||!frame->wireBytes) break;
                std::fill(bytes.begin()+count,bytes.begin()+frame->wireBytes,0);
                if(!write({bytes.data(),frame->wireBytes})) break;
            }
        }
        // The socket is nonblocking, including TLS close-notify at teardown.
        if(ssl) { SSL_shutdown(ssl);SSL_free(ssl); }
        close(stream);close(udp);
    }
public:
    TurnTestProxy(uint16_t udpPort,bool tls,size_t maximumRead=65560):target(udpPort),readLimit(maximumRead) {
        assert(readLimit>0);
        signal(SIGPIPE,SIG_IGN);
        if(tls) {
            context=SSL_CTX_new(TLS_server_method());
            assert(context&&SSL_CTX_use_certificate_file(context,getenv("TURN_TEST_CERT"),SSL_FILETYPE_PEM)==1);
            assert(SSL_CTX_use_PrivateKey_file(context,getenv("TURN_TEST_KEY"),SSL_FILETYPE_PEM)==1);
        }
        listener=socket(AF_INET,SOCK_STREAM,0);assert(listener>=0);
        sockaddr_in address{};
        address.sin_family=AF_INET;address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        assert(::bind(listener,reinterpret_cast<sockaddr *>(&address),sizeof(address))==0);
        socklen_t length=sizeof(address);
        assert(getsockname(listener,reinterpret_cast<sockaddr *>(&address),&length)==0);
        service=ntohs(address.sin_port);assert(listen(listener,4)==0);
        acceptor=std::jthread([this](std::stop_token stop) {
            while(!stop.stop_requested()) {
                pollfd descriptor{.fd=listener,.events=POLLIN};
                if(poll(&descriptor,1,100)<=0) continue;
                const int stream=accept(listener,nullptr,nullptr);
                if(stream>=0) clients.emplace_back([this,stream](std::stop_token token) {client(token,stream);});
            }
        });
    }
    ~TurnTestProxy() {
        acceptor.request_stop();acceptor.join();close(listener);
        for(auto &thread:clients) thread.request_stop();
        clients.clear();
        if(context) SSL_CTX_free(context);
    }
    uint16_t port() const { return service; }
};
