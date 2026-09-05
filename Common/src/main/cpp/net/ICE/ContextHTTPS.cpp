/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+,              */
/*      Sibionics GS1Sb and Accu-Chek SmartGuide sensors.                            */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Fri Nov 21 11:08:14 CET 2025                                                 */


#include <iostream>
#include <string>
#include <vector>
#include <array>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <dirent.h>
#include <fcntl.h>
#include <mutex>
#include <poll.h>
#include <thread>
#include <unistd.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <netinet/tcp.h>
#include <sys/ioctl.h>

#include "ContextHTTPS.hpp"
#include "ElapsedRealtime.hpp"

#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <openssl/x509_vfy.h>
#include <openssl/err.h>
#include "destruct.hpp"
/*
#define LOGGERHTTPS(...) fprintf(stderr,__VA_ARGS__)
#define LOGARHTTPS(...) fprintf(stderr,"%s\n",__VA_ARGS__)
#define lerror(...) perror(__VA_ARGS__) */
#include "logs.hpp"
#include "inout.hpp"
#include "strsepconcat.hpp"

//#define MAIN 1
//#define LOGHTTPS
#ifdef LOGHTTPS
#define LOGGERHTTPS(...) LOGGER("HTTPS: " __VA_ARGS__)
#define LOGARHTTPS(...) LOGAR("HTTPS: " __VA_ARGS__)
#define flerrorHTTPS(...) flerror("HTTPS: " __VA_ARGS__)
#else
#define LOGGERHTTPS(...) 
#define LOGARHTTPS(...) 
#define flerrorHTTPS(...) 
#endif
//#define LOGGERHTTPS(...) 
//#define LOGARHTTPS(...) 

using namespace std::literals;

#ifdef __ANDROID_API__
#define READ_CACERTS 1
#define DLSYMS_SSL 1
#endif

#ifndef DLSYMS_SSL
extern "C" int X509_check_host(X509 *certificate,const char *hostname,
                                size_t hostnameLength,unsigned int flags,
                                char **peername);
extern "C" int X509_check_ip_asc(X509 *certificate,const char *address,
                                  unsigned int flags);
#endif

#ifdef DLSYMS_SSL 
#undef SSLv23_client_method
#undef SSLv23_method
typedef int (*SSL_verify_cb)(int preverify_ok, X509_STORE_CTX *x509_ctx);
#include <dlfcn.h>
extern void* opencrypto();
extern void* openssl();
#ifdef TEST
void* opencrypto() {
#ifdef __ANDROID_API__
	#if defined(__aarch64__) || defined(__x86_64__) 
	const char *lib="/system/lib64/libcrypto.so";
	#else
	const char *lib="/system/lib/libcrypto.so";
	#endif
#else
	const char *lib="/usr/lib/libcrypto.so";
#endif
  return dlopen(lib,RTLD_NOW);
  }
void* openssl() {
#ifdef __ANDROID_API__
	#if defined(__aarch64__) || defined(__x86_64__) 
	const char *lib="/system/lib64/libssl.so";
	#else
	const char *lib="/system/lib/libssl.so";
	#endif
#else
	const char *lib="/usr/lib/libssl.so";
#endif
  return dlopen(lib,RTLD_NOW);
  }
#endif

#include "cryptodecl.h"
#include "ssldecl.h"
static bool doinitcryptofuncs() {
   LOGARHTTPS("doinitcryptofuncs");
   #define hgetsym(handle,name) *((void **)&name##ptr)=dlsym(handle, #name)
   #define getsym(name) hgetsym(handle,name)
//   #define symtest(name) if(!(getsym(name))) { dlclose(handle);LOGGERHTTPS(#name ": %s\n",dlerror());return false;}
   #define symtest(name) if(!(getsym(name))) { LOGGERHTTPS(#name ": %s\n",dlerror());}
   void *handle=opencrypto();
   if(!handle)  {
        LOGARHTTPS("opencrypto() failed");
        return false;
        }
#include "cryptosyms.h"
   handle=openssl();
   if(!handle)  {
        LOGARHTTPS("openssl() failed");
        return false;
        }
    #include "sslsyms.h"

    if(!TLS_client_methodptr) {
        if(SSLv23_client_methodptr)
            TLS_client_methodptr=SSLv23_client_methodptr;
        else
            TLS_client_methodptr=SSLv23_methodptr;
        }
   LOGGERHTTPS("doinitcryptofuncs end TLS_client_methodptr=%p\n",TLS_client_methodptr);
    return true;
   }
#include "cryptodefs.h"
#include "ssldefs.h"
#endif

static int logcallback(const char *str, size_t len, void *u) {
    LOGGERHTTPS("logcallback(%.*s,%d,%p)\n",len,str,len,u );
    std::string *uit=(std::string *)u;
    uit->append(str,len);
    return 0;
    }


std::string get_openssl_error_string() {
    std::string uit("");

    LOGGERHTTPS("ERR_print_errors_cbptr=%p\n", ERR_print_errors_cb);
    ERR_print_errors_cb(logcallback,&uit);
    return uit;
    }
// Load Android system CA certs (DER format) into the given X509_STORE
static bool load_android_cacerts(SSL_CTX* ctx) {
#ifndef READ_CACERTS
     if(SSL_CTX_set_default_verify_paths(ctx)) {
        LOGARHTTPS("SSL_CTX_set_default_verify_paths Succeeded");
        return true;
        }
     else {
        std::string er=get_openssl_error_string();
        LOGGERHTTPS("SSL_CTX_set_default_verify_paths failed: %s\n",er.data());
        return false;
        }
#else
   constexpr const char ca_dir[] = "/system/etc/security/cacerts";
//    constexpr const char ca_dir[] = "/data/local/tmp/cacerts";
    if(SSL_CTX_load_verify_locations(ctx, NULL, ca_dir)) {
        LOGARHTTPS("SSL_CTX_load_verify_locations Succeeded");
        return true;
        }
     else {
        std::string er=get_openssl_error_string();
        LOGGERHTTPS("SSL_CTX_load_verify_locations failed: %s\n",er.data());
        return false;
        }
#endif
}
/*
       int flag = 1;
   setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));
   struct linger l = { .l_onoff = 0, .l_linger = 0 };
   setsockopt(sock, SOL_SOCKET, SO_LINGER, &l, sizeof(l));
*/

static void sockopt(int new_fd) {
//    LOGGER("sockopt(%d)\n",new_fd);
       const int keepalive = 1;
       if(setsockopt(new_fd, SOL_SOCKET, SO_KEEPALIVE, &keepalive, sizeof(keepalive)) < 0) {
        flerror("setsockopt(%d,SO_KEEPALIVE, ) failed",new_fd);
         }
      int retalive=-4;
    socklen_t retlen=sizeof(retalive);    

       if(getsockopt(new_fd, SOL_SOCKET, SO_KEEPALIVE, &retalive, &retlen) < 0) {
        flerror("getsockopt(%d,SO_KEEPALIVE, ) failed",new_fd);
         }
//    else LOGGER("KEEPALIVE=%d\n",retalive);
       const int keepcnt = 1;
    if(setsockopt(new_fd, IPPROTO_TCP, TCP_KEEPCNT, &keepcnt, sizeof(keepcnt))<0) {
        flerror("setsockopt(%d,TCP_KEEPCNT ) failed",new_fd);
        }
    retlen=sizeof(retalive);    
    if(getsockopt(new_fd, IPPROTO_TCP, TCP_KEEPCNT, &retalive, &retlen)<0) {
        flerror("getsockopt(%d,TCP_KEEPCNT ) failed",new_fd);
        }
//       else LOGGER("KEEPCNT=%d\n",retalive);
//       if(setsockopt(new_fd, IPPROTO_TCP, TCP_SYNCNT, keepcnt)<0)  {
 //       flerror("setsockopt(%d,TCP_SYNCNT) failed",new_fd); }
       const int keepidle = 10;
       if(setsockopt(new_fd, IPPROTO_TCP, TCP_KEEPIDLE, &keepidle, sizeof(keepidle)) < 0) {
        flerror("setsockopt(%d,TCP_KEEPIDLE, ) failed",new_fd);
         }
    retlen=sizeof(retalive);    

       if(getsockopt(new_fd, IPPROTO_TCP, TCP_KEEPIDLE, &retalive, &retlen) < 0) {
        flerror("getsockopt(%d,TCP_KEEPIDLE, ) failed",new_fd);
         }
//    else LOGGER("KEEPIDLE=%d\n",retalive);
       const int keepintvl = 10;
       if(setsockopt(new_fd, IPPROTO_TCP, TCP_KEEPINTVL, &keepintvl, sizeof(keepintvl)) < 0) {
        flerror("setsockopt(%d,TCP_KEEPINTVL, ) failed",new_fd);
         }
    retlen=sizeof(retalive);    
       if(getsockopt(new_fd, IPPROTO_TCP, TCP_KEEPINTVL, &retalive, &retlen) < 0) {
        flerror("getsockopt(%d,TCP_KEEPINTVL, ) failed",new_fd);
         }
//    else LOGGER("KEEPINTVL=%d\n",retalive);
     }

class RequestDeadline {
    int64_t endMilliseconds;
    std::shared_ptr<const std::atomic_bool> cancelled;
public:
    explicit RequestDeadline(const HTTPSRequestOptions &options)
        : endMilliseconds(elapsedRealtimeMilliseconds()+
              std::max(options.timeoutMilliseconds,1)),
          cancelled(options.cancelled) {}

    bool stopped() const {
        return (cancelled && cancelled->load(std::memory_order_acquire)) ||
               elapsedRealtimeMilliseconds()>=endMilliseconds;
    }

    int pollMilliseconds() const {
        if(stopped())
            return 0;
        const int64_t remaining=endMilliseconds-elapsedRealtimeMilliseconds();
        return static_cast<int>(std::clamp<int64_t>(remaining, 1, 250));
    }
};

struct AddressResolution {
    std::mutex mutex;
    std::condition_variable ready;
    addrinfo *addresses=nullptr;
    int status=EAI_SYSTEM;
    bool complete=false;

    ~AddressResolution() {
        if(addresses)
            freeaddrinfo(addresses);
        }
};

static std::shared_ptr<AddressResolution> resolveAddresses(
        std::string host,std::string service,const RequestDeadline &deadline) {
    auto resolution=std::make_shared<AddressResolution>();
    std::thread([resolution,host=std::move(host),service=std::move(service)] {
        addrinfo hints{.ai_family=AF_UNSPEC,.ai_socktype=SOCK_STREAM};
        addrinfo *addresses=nullptr;
        const int status=getaddrinfo(host.c_str(),service.c_str(),&hints,&addresses);
        {
            std::lock_guard<std::mutex> lock(resolution->mutex);
            resolution->addresses=addresses;
            resolution->status=status;
            resolution->complete=true;
        }
        resolution->ready.notify_one();
    }).detach();

    std::unique_lock<std::mutex> lock(resolution->mutex);
    while(!resolution->complete&&!deadline.stopped())
        resolution->ready.wait_for(
            lock,std::chrono::milliseconds(deadline.pollMilliseconds()));
    if(!resolution->complete)
        return {};
    return resolution;
}

static bool waitForSocket(int sock, short events, const RequestDeadline &deadline) {
    while(!deadline.stopped()) {
        pollfd descriptor{.fd=sock,.events=events,.revents=0};
        const int result=poll(&descriptor,1,deadline.pollMilliseconds());
        if(result>0) {
            if(descriptor.revents&(POLLERR|POLLHUP|POLLNVAL))
                return false;
            if(descriptor.revents&events)
                return true;
            continue;
            }
        if(result<0&&errno!=EINTR) {
            lerror("poll");
            return false;
            }
        }
    return false;
    }

// Create a TCP connection to host:port
static int tcp_connect(std::string_view host, int port,const RequestDeadline &deadline) {
    char port_str[16];
    snprintf(port_str, sizeof(port_str), "%d", port);
    LOGGERHTTPS("tcp_connect(%.*s,%d)\n",host.size(),host.data(),port);
    auto resolution=resolveAddresses(std::string(host),port_str,deadline);
    if(!resolution||resolution->status) {
        LOGGERHTTPS("getaddrinfo failed: %d\n",
                    resolution?resolution->status:EAI_AGAIN);
        return -1;
        }
    for(addrinfo *address=resolution->addresses;
        address&&!deadline.stopped();address=address->ai_next) {
        LOGARHTTPS("Before socket");
        int sock = socket(address->ai_family, address->ai_socktype, address->ai_protocol);
        LOGGERHTTPS("After socket sock=%d\n",sock);
        if(sock < 0)
            continue;
        const int oldFlags=fcntl(sock,F_GETFL,0);
        if(oldFlags<0||fcntl(sock,F_SETFL,oldFlags|O_NONBLOCK)<0) {
            lerror("fcntl O_NONBLOCK");
            close(sock);
            continue;
            }
        if(connect(sock,address->ai_addr,address->ai_addrlen)!=0) {
            if(errno!=EINPROGRESS||!waitForSocket(sock,POLLOUT,deadline)) {
                close(sock);
                continue;
                }
            int socketError=0;
            socklen_t errorLength=sizeof(socketError);
            if(getsockopt(sock,SOL_SOCKET,SO_ERROR,&socketError,&errorLength)<0||socketError) {
                if(socketError)
                    errno=socketError;
                lerror("connect");
                close(sock);
                continue;
                }
            }
        sockopt(sock);
        int flag = 1;
        setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));
        return sock;
        }
    return -1;
    }

#ifdef DLSYMS_SSL 
#undef SSL_CTRL_SET_TLSEXT_HOSTNAME   

//long SSL_ctrl(SSL *ssl, int cmd, long larg, void *parg);
#define SSL_CTRL_SET_TLSEXT_HOSTNAME            55
static int SSL_set_tlsext_host_name2(const SSL *s, const char *name) {
    if(SSL_set_tlsext_host_nameptr)
        return  SSL_set_tlsext_host_nameptr(s, name) ;
    return  SSL_ctrl((SSL*)s,SSL_CTRL_SET_TLSEXT_HOSTNAME,TLSEXT_NAMETYPE_host_name,(void *)name);

     }
 #else
#define SSL_set_tlsext_host_name2 SSL_set_tlsext_host_name
 #endif


    ContextHTTPS::ContextHTTPS(){
        LOGARHTTPS("ContextHTTPS()");
        static bool initlib=initLibrary();
        if(!initlib) {
            error=true;
            return;
            }
        ctx=SSL_CTX_new(TLS_client_method());
        if (!ctx) {
            LOGARHTTPS("Failed to create SSL_CTX");
            error=true;
            return;
            }
        error=!load_android_cacerts(ctx);
        SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, nullptr);
        }
 ContextHTTPS &ContextHTTPS::getContext() {
            static ContextHTTPS contex;
             return contex;
             }
     ContextHTTPS::~ContextHTTPS() {
        LOGARHTTPS("SSL_CTX_free(ctx)");
        if(ctx)
            SSL_CTX_free(ctx);
        }
bool ContextHTTPS::initLibrary() {

#ifdef DLSYMS_SSL 
       static bool getcrypto=doinitcryptofuncs();
        if(getcrypto) {
            if(SSL_library_initptr)
                SSL_library_init();
            if(SSL_load_error_stringsptr)
                SSL_load_error_strings();
                return true;
              }
        
         return false;
#else
          SSL_library_init();
          SSL_load_error_strings();
          return true;
#endif
        }
//s/^ssl.h:# define \([^	 ]*\)[	 ]*\([0-9]\+\)[^0-9]*$/case \1: return "\1";/g
//s/^ssl.h:# define \([^	 ]*\)[	 ]*\([0-9]\+\)[^0-9]*$/case \2: return "\1";/g
#ifndef NOLOG
static const char *geterrorstring(int error) {
    switch(error) {
        case 0: return "SSL_ERROR_NONE";
        case 1: return "SSL_ERROR_SSL";
        case 2: return "SSL_ERROR_WANT_READ";
        case 3: return "SSL_ERROR_WANT_WRITE";
        case 4: return "SSL_ERROR_WANT_X509_LOOKUP";
        case 5: return "SSL_ERROR_SYSCALL";
        case 6: return "SSL_ERROR_ZERO_RETURN";
        case 7: return "SSL_ERROR_WANT_CONNECT";
        case 8: return "SSL_ERROR_WANT_ACCEPT";
        case 9: return "SSL_ERROR_WANT_ASYNC";
        case 10: return "SSL_ERROR_WANT_ASYNC_JOB";
        case 11: return "SSL_ERROR_WANT_CLIENT_HELLO_CB";
        default: return "SSL_UNKNOWN_ERROR";
        }
}
#endif

static int SSLwait(SSL *ssl,int result,int sock,const RequestDeadline &deadline) {
    const int error=SSL_get_error(ssl,result);
    LOGGERHTTPS("SSL operation Error %d %s\n",error,geterrorstring(error));
    if(error==SSL_ERROR_WANT_READ)
        return waitForSocket(sock,POLLIN,deadline)?0:-1;
    if(error==SSL_ERROR_WANT_WRITE)
        return waitForSocket(sock,POLLOUT,deadline)?0:-1;
    return -1;
    }

static int SSLreadfull(SSL* ssl,int sock,char *dataptr,const int buflen,
                       const RequestDeadline &deadline) {
    LOGGERHTTPS("start SSLreadfull %d\n", buflen);
    int n=0;
     while(!deadline.stopped()) {
        if(n>=buflen) {
            LOGGERHTTPS("SSL_read all %d\n",n);
            break;
            }
        const int res=SSL_read(ssl, dataptr+n,buflen-n);
        if(res>0) {
            LOGGERHTTPS("SSLread %d\n",res);
            n+=res;
            continue;
            }
        if(SSLwait(ssl,res,sock,deadline)==0)
                continue;
        int err = SSL_get_error(ssl, res);
        if (err == SSL_ERROR_SSL) {
            unsigned long e = ERR_get_error();
            constexpr const int maxbuf=200;
            char buf[maxbuf];
            ERR_error_string_n(e, buf, maxbuf);
            LOGGERHTTPS("SSL_read SSL_ERROR %s\n",buf);
            }
        else {
            if(err == SSL_ERROR_SYSCALL) {
                lerror("SSL_read syscall error");
                }
            }
         break;
        }
    LOGGERHTTPS("end SSLreadfull %d\n",n);
    return n;
    }
    /*
static int SSLwritefull(SSL* ssl, const char *dataptr,const int buflen) {
    LOGGERHTTPS("start SSLwritefull %d",buflen);
    int n=0;
     for(int res;;n+=res) {
        if(n>=buflen) {
            LOGGERHTTPS("SSL_write all %d\n",buflen);
            break;
            }
        res=SSL_write(ssl, dataptr+n,buflen-n);
        if(res>0) {
            LOGGERHTTPS("SSLwrite %.*s %d\n",res,dataptr+n,res);
            continue;
            }
        int err = SSL_get_error(ssl, res);
        LOGGERHTTPS("SSL_write Error %d %s\n",err,geterrorstring(err));
        //int err = SSL_get_error(ssl, res);
        if (err == SSL_ERROR_SSL) {
            unsigned long e = ERR_get_error();
            constexpr const int maxbuf=200;
            char buf[maxbuf];
            ERR_error_string_n(e, buf, maxbuf);
            LOGGERHTTPS("SSL_read SSL_ERROR %s\n",buf);
            }
        else {
            if(err == SSL_ERROR_SYSCALL) {
                lerror("SSL_write syscall error");
                }
            }
         break;
        }
    LOGGERHTTPS("end SSLwritefull %d\n",n);
    return n;
    }
*/



std::pair<std::vector<char>,int> ContextHTTPS::request(const std::string_view host,int port,const std::string_view path,const std::string_view TYPE,const std::span<const char> input, const std::string_view header,const HTTPSRequestOptions &options) {
    std::vector<char> uit;   
    if(error) {
        return {uit,-1};
        }
    RequestDeadline deadline(options);
    int sock = tcp_connect(host, port,deadline);
    if (sock < 0) {
        return {uit,-1};
    }
    SSL* ssl = SSL_new(ctx);
    LOGGERHTTPS("after SSL_new(ctx)=%p\n",ssl);
    if(!ssl) {
       shutdown(sock,SHUT_RDWR);
       close(sock);
       return {uit,-1};
       }

    destruct _{[ssl,sock]{
       SSL_shutdown(ssl);
       shutdown(sock,SHUT_RDWR);
       close(sock);
       SSL_free(ssl);
       LOGGERHTTPS("close(%d)\n",sock);
        }};
    SSL_set_fd(ssl, sock);
    const std::string hostName(host);
    SSL_set_tlsext_host_name2(ssl, hostName.c_str());  // SNI
    if(!options.verifyCertificate) {
       #ifdef DLSYMS_SSL
       if(!SSL_set_verifyptr) {
          LOGARHTTPS("TLS verification override is unavailable");
          return {uit,-1};
          }
       #endif
       SSL_set_verify(ssl,SSL_VERIFY_NONE,nullptr);
       }

    LOGARHTTPS("before SSL_connect");
    int conres;
    while((conres=SSL_connect(ssl))!=1) {
        if(SSLwait(ssl,conres,sock,deadline)<0)
            break;
        }
    LOGGERHTTPS("after SSL_connect conres=%d\n",conres);
    if(conres != 1) {
       const std:: string mess=get_openssl_error_string();
       LOGGERHTTPS("SSL handshake failed: %s\n", mess.c_str());
        return {uit,-1};
       }
    if(options.verifyCertificate) {
       #ifdef DLSYMS_SSL
       if(!SSL_get_peer_certificateptr||!SSL_get_verify_resultptr||
          !X509_check_hostptr||!X509_check_ip_ascptr||!X509_freeptr||
          !X509_verify_cert_error_stringptr) {
          LOGARHTTPS("Missing TLS hostname verification functions");
          return {uit,-1};
          }
       #endif
       long verify_result = SSL_get_verify_result(ssl);
       if (verify_result != X509_V_OK) {
          LOGGERHTTPS("Certificate verification failed: %s\n", X509_verify_cert_error_string(verify_result));
          return {uit,-1};
          }
       X509 *peerCertificate=SSL_get_peer_certificate(ssl);
       if(!peerCertificate) {
          LOGARHTTPS("Rendezvous server did not provide a certificate");
          return {uit,-1};
          }
       destruct freeCertificate{[peerCertificate]{X509_free(peerCertificate);}};
       in_addr ipv4{};
       in6_addr ipv6{};
       const bool isIPAddress=inet_pton(AF_INET,hostName.c_str(),&ipv4)==1||
                              inet_pton(AF_INET6,hostName.c_str(),&ipv6)==1;
       const int hostMatches=isIPAddress
           ?X509_check_ip_asc(peerCertificate,hostName.c_str(),0)
           :X509_check_host(peerCertificate,hostName.c_str(),hostName.size(),0,nullptr);
       if(hostMatches!=1) {
          LOGGERHTTPS("Rendezvous certificate hostname mismatch for %s\n",hostName.c_str());
          return {uit,-1};
          }
       }
    const char closebuf[]{"\r\nConnection: close\r\n\r\n"};
    strsepconcat req {""sv,TYPE , " "sv,path," HTTP/1.1\r\nHost: "sv , host , "\r\nContent-Length: "sv,std::to_string(input.size()), header,closebuf};

    LOGGERHTTPS("connect %.*s %.*s\n",TYPE.size(),TYPE.data(),path.size(),path.data());
    const char *request=req.data();
   int requestsize=req.size();
    const int maxdata=  std::max((int)(input.size()+requestsize),16*1024);
    Mmap<char> data(maxdata);
    char *dataptr=data.data();
    memcpy(dataptr,request,requestsize);
    if(input.size()>0) {
        memcpy(dataptr+requestsize, input.data(), input.size());
        requestsize+=input.size();
       }
   int written=0;
   while(written<requestsize&&!deadline.stopped()) {
       const int result=SSL_write(ssl,dataptr+written,requestsize-written);
       if(result>0) {
           written+=result;
           continue;
           }
       if(SSLwait(ssl,result,sock,deadline)<0)
           break;
       }
   if(written!=requestsize)
       return {uit,-1};
   LOGGERHTTPS("after SSL_write %d\n", requestsize);
   int n;
   while((n=SSL_read(ssl,dataptr,maxdata))<=0) {
       if(SSLwait(ssl,n,sock,deadline)<0)
           return {uit,-1};
       }
   LOGGERHTTPS("after SSL_read=%d\n", n);
   char *enddata=dataptr+n;
   int   status_code=-1;
   char *startpos;
   if((startpos=std::find(dataptr,enddata,' '))!=enddata) {
        int end=sscanf(++startpos,"%d",&status_code);
        startpos+=end;
        }
    else {
        LOGGERHTTPS("no space in #%s# len=%d\n",dataptr,n);
        return {uit,-1};
        }
    LOGGERHTTPS("Status Code=%d\n",status_code);
    char zoek[]="\r\n\r\n";
    if(char *hit=std::search(startpos,enddata,zoek,zoek+sizeof(zoek)-1);hit!=enddata) {
        hit+=sizeof(zoek)-1;
        int len= enddata-hit;
        uit.reserve(len);
        #ifdef __cpp_lib_containers_ranges
        uit.append_range(std::span<char>(hit,len));
        #else
            uit.insert(uit.end(), hit, hit+len);
        #endif
        if(n==maxdata) {
            do {
                int n=SSLreadfull(ssl,sock,dataptr,maxdata,deadline);
                if(n<=0)
                    break;
                uit.reserve(uit.capacity()+n);
        #ifdef __cpp_lib_containers_ranges
                uit.append_range(std::span<char>(dataptr,n));
                #else
                  uit.insert(uit.end(), dataptr, dataptr+n);
                  #endif
                } while(n==maxdata);
            }
        }
    return {uit,status_code};
    }
#ifdef MAIN
int main() {
  ContextHTTPS context;
  const char inpstr[]{"Hallo this me"};
  /*(
  auto [res3,code]=context.putRequest("echo.free.beeceptor.com",443,"/address",inpstr);
  if(code==200)
      write(STDERR_FILENO,res3.data(),res3.size());
      */
/*  auto res2=context.getRequest("www.juggluco.nl",443,"/Juggluco/download.html");
  write(STDERR_FILENO,res2.data(),res2.size()); */
 // auto [res1,code]=context.getRequest("a.juggluco.nl",7777,"/hallo/x/stream?header&days=20");
  auto [res1,code]=context.getRequest("www.juggluco.nl",443,"/Juggluco/download.html");
  writeall("tmpfile",res1.data(),res1.size()); 
  //write(STDERR_FILENO,res1.data(),res1.size()); 
}
#endif
